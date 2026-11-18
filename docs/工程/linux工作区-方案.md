# Linux 工作区 —— 落地方案（讨论稿）

> 目标读者：本项目维护者。**这份是方案，不是已实现的东西。**
> 讨论清楚之前不写代码；写完之后按 §7 的里程碑推进，每步都有离线验收与真机项。

---

## 1. 要解决什么

现在 agent 能做的"数据类活"全是手写的：`office_run` 的列目录/读文本/写表格、`file_read`
读一段文本。每多一种格式就要我们写一份 Kotlin 实现，而用户的需求是开放的
（"把这两百个文件按日期改名并压包"、"把这段录音转成 mp3 再切三段"）。

我们要的不是"手机里一台电脑"，而是：

> 给 agent 一个**受约束的工作区**，里面有一份真实工具链（bash / python3 / jq / ffmpeg / git），
> 让它用脚本干活，而**写坏也只能写坏 `/work`**。

### 非目标（写清楚，免得越做越大）

- ❌ 不做图形界面 / 不做桌面环境（VNC、X11）
- ❌ 不做"给用户一个终端去玩"（那是 Termux，不是我们）
- ❌ 不做通用包管理器的自由安装（见 §6 的决策 D4）
- ❌ 不替代虚拟屏/无障碍：**屏幕类活仍然只能靠它们**

---

## 2. 三条路线

| 路线 | 需要什么 | 体积 | 速度 | 结论 |
|---|---|---|---|---|
| **A. 复用外部 proot**（Termux / dsh 容器） | 用户自己装 Termux，容器已起 | **0** | 慢 2–5× | 开发机与"自己人"可用，普通用户不可依赖 |
| **B. 自带 proot + 按需下载 rootfs** | 无 root；app 内 `jniLibs` 放 proot | 首装 0，用时 +150–250 MB | 慢 2–5× | **普通用户的默认路线** |
| **C. chroot / mount namespace** | **root** | 同一份 rootfs | 接近原生 | root 档的加速与真隔离路线 |

三条**不互斥**：对外表现为一个 `LinuxEnv`，内部按档位选实现；命令构造与安全约束完全共用。

### 为什么无 root 只能 proot

- `chroot` 要 `CAP_SYS_ADMIN`（root）
- `unshare -m/-n` 同样要 root
- app 数据目录在 Android 10+ 是 **`noexec`** ⇒ 放在 `filesDir` 里的二进制**执行不了**，
  必须打进 `jniLibs`（`libproot.so`）再从 `nativeLibraryDir` 执行
- proot 靠 `ptrace` 拦截 syscall，app 对自己拉起的子进程做 ptrace 是允许的

---

## 3. 架构（一图层，不新增运行时）

```
       模型 / SuperAgent
             │  工具调用（linux_exec / linux_read）
             ▼
   :mcp  LinuxExecTool ──────────────┐
             │   参数校验（纯逻辑）  │
             ▼                      │
   LinuxCommands（argv 构造 + 目录约束 + 白名单）
             │                      │
             ▼                      │
   RootActions.run(LinuxShell…)  ← 复用现有三条出口
             │
   ┌─────────┴──────────┐
   ▼                    ▼
常驻通道(DISPLAY 同款)   直接 su（root 档）
   LINUX 动词
             │
             ▼
   proot  /  chroot  ──►  rootfs（只读） + /work（唯一可写）
```

要点：**不新造执行通道**。`PrivilegeWire` 的帧、`DaemonLoop` 的动词、`RootActions` 的
"通道优先、su 兜底"都已经在了，加一条 `LINUX` 动词即可。

---

## 4. 接口设计（这一节是讨论重点）

### 4.1 环境状态

```kotlin
enum class LinuxKind { EXTERNAL_PROOT, BUNDLED_PROOT, ROOT_CHROOT }   // A / B / C
enum class LinuxState { ABSENT, NEEDS_DOWNLOAD, READY, BROKEN }

data class LinuxEnv(
    val state: LinuxState,
    val kind: LinuxKind?,
    val rootfsPath: String?,     // 只读根
    val workDir: String,         // 唯一可写：/work
    val reason: String,          // 不可用时的原因（给用户看，不是堆栈）
)
```

`LinuxEnvProbe.detect()`：按 C → A → B 的顺序探测（root 用户优先走 chroot；有 Termux 容器就直接用；
都没有则报告"需要下载"）。**探测本身不发包、不跑命令**（只做文件存在性与一次 `--version` 的轻探）。

### 4.2 命令模型

```kotlin
sealed interface LinuxCall {
    data class Run(val argv: List<String>, val timeoutSec: Int) : LinuxCall   // 不经 shell
    data class Script(val script: String, val timeoutSec: Int) : LinuxCall    // bash -lc，脚本单独一层
    data class Read(val path: String, val maxBytes: Int) : LinuxCall          // 只读 /work 内文件
}
```

- `Run`：`argv` 数组，**默认路径**。模型给 `["python3","-c","print(1)"]` 就走这条。
- `Script`：确实需要管道/重定向时才用，落盘成 `/work/.tmp/<uuid>.sh` 再执行；脚本内容进审计。
- `Read`：把结果文件读回来（避免把大输出塞进上下文），`maxBytes` 默认 64 KB。

### 4.3 工具面（只暴露两个）

| 工具 | 参数 | 返回 |
|---|---|---|
| `linux_exec` | `argv[]` 或 `script`、`timeoutSec`(1–60)、`purpose`(必填, 给审计与安全判定) | `exitCode` / `stdout`(≤4KB 截断并注明) / `stderr`(≤2KB) / `durationMs` |
| `linux_read` | `path`（必须在 `/work` 内）、`maxBytes` | 文本；二进制返回大小与类型，不回灌字节 |

回执**不回显全量输出**：超过上限就截断并告诉模型"要细节就 `linux_read` 具体文件"。
否则一次 `ls -R` 就能把上下文塞满。

### 4.4 与现有目录的关系

`ToolCatalog` 新增一条：

```kotlin
ToolSpec(
    id = "linux_exec",
    title = "Linux 命令",
    channel = ToolChannel.LINUX,          // 新增枚举值
    effects = setOf(WRITES_DATA),         // 它确实会写 /work
    needsScreen = false,
    reversible = false,                   // 删掉的东西回不来
    idempotent = false,
    expectedMs = 1_500,
    timeoutMs = 65_000,
    whenToUse = "要做批处理、格式转换、文本/表格清洗，或者一条命令能顶很多步时",
    failureHint = "先看 exitCode 与 stderr；越界路径与未装工具都会被直接拒绝，不要换个写法硬试",
)
```

`RunOrchestrator` 的确认闸会自动生效（`WRITES_DATA` + 不可逆 ⇒ `requiresConfirmation`），
所以**第一次跑命令会让用户点一下** —— 这是对的，Linux 命令比点按危险得多。

---

## 5. 安全模型（这一节决定能不能交付）

| 约束 | 怎么做到 | 强度 |
|---|---|---|
| 只能写 `/work` | 以 **app 自己的 uid** 跑（不 root）→ 系统目录本来就没有写权限；再加路径校验 | **内核强制** |
| 系统只读 | 同上（app uid 对 `/system`、`/data/data/其他包` 都无权限） | **内核强制** |
| 不能读到别处 | `Read` 只接受 `/work` 前缀；`Run` 里读写别处也拿不到权限 | 内核 + 参数校验 |
| 越界参数 | `LinuxCommands` 拒绝 `..`、绝对路径越界、NUL、超长 | 参数校验 |
| 不默认联网 | 普通档：**软闸**（`Run` 的 argv 首词不在联网白名单，`curl/wget/pip/npm` 直接拒）+ 提示词声明；root 档：`unshare -n` **真隔离** | 普通档软、root 档硬 |
| 命令留痕 | 每条记录 `purpose / argv / exitCode / durationMs / 前 2KB stdout` | 审计 |
| 超时与叫停 | `timeoutSec` 上限 60；用户按停 → 杀进程组 | 进程管理 |
| 磁盘 | 任务开始前查 `/work` 体积，超过配额直接拒；单条命令后复检 | 软（无 per-dir quota） |

⚠️ 诚实标注：**普通档的"断网"不是内核级隔离**。app 有 INTERNET 权限，proot 里的子进程
天然能联网；我们能做的是"不把联网工具写进白名单 + 审计"，绕不过去的人会绕过。
root 档用 network namespace 才是真隔离。这一条必须写进用户能看到的说明里，不能含糊。

---

## 6. 需要你拍板的四件事

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| **D1** | 目标用户 | (a) 只服务装了 Termux 的自己人（体积 0）<br>(b) 普通用户（自带 proot + 按需下载 200MB） | **(b)**，但**先按 (a) 落地**跑通链路，再加下载；否则第一天就要处理断点/校验/解压 |
| **D2** | 发行版 | Alpine（~10MB 基座，musl，pip 装二进制轮子会踩坑）/ Debian slim（~50MB 基座，兼容好）/ 自定义最小集（只装 bash+coreutils+python3+jq+ffmpeg） | **Debian slim 的自定义子集**：兼容性比体积重要，用户真踩坑时我们没法远程救 |
| **D3** | 默认联网 | 软闸（普通档能联网但被审计）/ root 档 `unshare -n` 真隔离 | **就按这个分档**；普通档在界面上明确写"能联网" |
| **D4** | 允许装包吗 | (a) 不允许（工具集固定）<br>(b) 允许 `pip install --user`，但必须走外发闸 | **先 (a)**，把 `pip` 排除在白名单外；M4 再讨论 |

---

## 7. 里程碑（每步都能独立验收）

### M1 —— 纯逻辑层（离线，~1 天）

- `LinuxCommands`：argv 构造、`/work` 前缀校验、`..`/NUL/超长拒绝、白名单（首词）与超时夹取
- `LinuxEnv` / `LinuxEnvProbe`：三档探测的**纯函数部分**（给定文件存在与否 → 状态）
- `linux_exec` / `linux_read` 两个工具的**壳**：环境不可用时返回 `Refused`（"这台机器还没有 Linux 工作区，要我带你装吗"）
- 测试：参数越界、路径逃逸、白名单、超时夹取、状态机（≥ 20 条）
- 验收：`run_logic_tests.py` 全绿；此时**一个字节都不发给系统**

**状态（2026-11-17）：已完成。**

落地清单：

| 文件 | 内容 |
|---|---|
| `rootlogic/LinuxEnv.kt` | 三档探测的状态机（纯函数）+ `explain()` 人话 |
| `rootlogic/LinuxCommands.kt` | 首词白名单 / 路径规则（只许 `/work` 与 `/tmp`、禁 `..`）/ 超时夹取 / 三种 `launch` |
| `mcp/LinuxOps.kt` | `LinuxOpsPort` + `linux_exec` / `linux_read` 两个工具壳（`Rejected` / `NotReady` / `Failed` 三分） |
| `agentlogic/orchestration/ToolCatalog.kt` | 两条目录条目（`linux_exec` 不可逆 ⇒ 自动撞确认闸） |
| `agentlogic/orchestration/ToolGroups.kt` | 新组 `LINUX`，入口 `linux_exec` |

测试：`LinuxEnvTest` 11 条、`LinuxCommandsTest` 24 条、`LinuxOpsToolsTest` 13 条；
`run_logic_tests.py` → **OK (4518 tests)**。

M1 刻意不做：`script` 模式（`bash -lc` 会绕过首词白名单）、真实执行（下一个里程碑）。

### M2 —— 接上外部 proot（真机，半自动）

- `RootVerb.LINUX` + `DaemonHandlers` 的 `linux(runner, body)`：`proot --rootfs <rootfs> -b <work>:/work -w /work bash -lc …`
- `RootActions.LinuxShell(argv, timeout)` + `DaemonCalls` 映射 + `su` 兜底
- `linux_exec` 真正执行；`exitCode/stdout/stderr` 走现有 `ShellResult.describe()` 同款
- 真机项（WorkBuddy）：`python3 -c "print(2**100)"`、`jq` 处理一个 JSON、`ffmpeg -i` 转一段音频
- 验收：三档探测在真机上是 `EXTERNAL_PROOT / READY`，命令能跑；越界路径被拒（附原始报错）

### M3 —— 自带 proot + 按需 rootfs

- `jniLibs/libproot.so`（或 proot 静态二进制的 `.so` 命名）+ `nativeLibraryDir` 执行
- rootfs 下载器：校验（sha256）、断点、解压到 `filesDir/linux/rootfs`、失败可重试
- 首用引导：一句话 + 一个按钮（下载 200MB / 稍后）；下载中不阻塞其他能力
- 验收：清空数据重装 → 首次使用走完下载 → 命令能跑；断网下载失败给的是"网络原因"而不是"环境坏了"

### M4 —— 加固与体验

- root 档 `unshare -n` 真隔离；`--work` 配额检查；命令审计进任务卡片（"它跑了什么"）
- 与 `office_run` 的产出目录对齐：`/work/out` ↔ SAF 授权目录
- 验收：审计里能看到 argv 与退出码；配额超限的拒绝文案给的是"清一下 /work"而不是"失败"

---

## 8. 风险清单（提前说清，免得实施时才发现）

| 风险 | 影响 | 处置 |
|---|---|---|
| ptrace 被某些 ROM 拦 | proot 起不来 | 探测阶段直接判 `BROKEN` 并说"这台机器不支持，用 root 档或桌面端"；**不要把"没起来"报成"命令失败"** |
| rootfs 解压占满用户存储 | 装机后无法开机 | 下载前查剩余空间，要求 ≥ 3× 压缩包；不足直接拒 |
| 编译/转码过热 | 体验差、耗电 | 单任务累计 CPU 时间预算（接 `AgentBudget`），超了自动收尾 |
| 输出把上下文塞满 | 模型上下文爆掉 | 回执截断 + `linux_read` 单独取；`MAX_STDOUT = 4KB` |
| proot 里 `rm -rf /work` | 用户数据没了 | 这就是"工作区"的代价；确认闸 + 审计 + 可从备份候选恢复（M4 讨论） |

---

## 9. 我建议的推进顺序

1. 先按 **D1(a) 外部 proot** 做 M1 + M2 —— 你今天就能在 K60 上验证"agent 真能跑 python"
2. 验证通过后再做 M3（自带 proot）—— 那是给普通用户的，工作量主要在下载与失败处理
3. M4 的隔离与审计等有人真的用起来再做，否则是在给没人用的功能加护栏

一句话总结：**先让它在你自己手机上真的跑起来，再决定要不要为所有用户背 200MB。**


---

## 10. 复核（2026-11-18）：两处必须修正

这一节是方案写完隔天、动手做 M1 之后的一次回头复核。下面的两条**改变里程碑顺序**，
其余设计（三条出口、`/work` 单写目录、白名单、分档联网）不需要动。

### 10.1 实质错误：外部 proot 只在 root 档可达

Termux 的 rootfs 在 `/data/data/com.termux/files/usr/var/lib/proot-distro/...` ——
那是**另一个 uid 的私有目录**。我们的 app 既读不到也执行不了，跨 app 访问会被 SELinux 直接拒。

所以 §2 表格里 A 路线的"开发机与'自己人'可用"这句话成立，但 §7 的
**M2「接上外部 proot（半自动）」在普通档不成立** —— 普通档根本够不到那个目录。

**修正**：M2 只做 **root 档**（常驻通道以 root 跑，能读 Termux 目录，也能 chroot）；
普通档要么自带 proot，要么不做，没有第三种。

### 10.2 未验证假设：无 root 下，proot 能不能**在本 app 里**跑起来

- 本项目 `targetSdk = 36`、`minSdk = 31`（`gradle/libs.versions.toml`）。
- Android 10+ 禁止 app 执行自己可写目录里的文件（W^X / SELinux 对 `app_data_file` 的 execute），
  这正是 Termux 至今把 targetSdk 钉在 28 的原因。
- proot 自带 loader，理论上可以把 guest ELF 读进匿名内存再 `PROT_EXEC`（UserLAnd 这类
  targetSdk 较高的项目就在跑），但**这条对我们没有任何证据**。

⇒ 这是全案最大的未验证点。**先别写 200MB 的下载器**，先做 30 分钟实验：

1. 把 proot 的静态二进制放进 `jniLibs/arm64-v8a/libproot.so`（`extractNativeLibs=true`）；
2. 设备上跑 `nativeLibraryDir/libproot.so --version` —— 能不能起；
3. 指向一个 3MB 的 `alpine-minirootfs`（**仅用于验证**，不是发行版决策）跑 `/bin/sh -c 'echo hi'`；
4. 第 3 步若报 `Permission denied` / `exec format error` / SELinux 拒绝 ⇒ **BUNDLED 路线作废**。

### 10.3 修订后的推进顺序

| 顺序 | 做什么 | 依赖 |
|---|---|---|
| 1 | **M2a：root 档打通**（`RootVerb.LINUX` + chroot/proot + `/work`） | 无（今天可做） |
| 2 | **M2b：无 root 可行性实验**（§10.2 四步，结果写回这一节） | 一台无 root 的真机 |
| 3 | M3：自带 proot + rootfs 下载器 | **仅当 M2b 通过** |
| 4 | 若 M2b 不通过：普通档**明确不做** | 界面上写"需要 root 或 Termux"，不给点了没反应的下载按钮 |

### 10.4 两处代码级待补（M1 里已能改）

1. `LinuxEnv` 缺一个"被系统策略挡住"的状态：现在这种情况会落到 `ABSENT`
   （文案让用户去装 Termux），而真正原因是这台机器不允许。建议加 `BLOCKED`，
   或在 `BROKEN` 的文案里区分"环境坏了"与"系统不让"。
2. `linux_read` 工具自己又判了一次 `/work/` 前缀，与 `LinuxCommands.readPath` 重复。
   前缀写死在两处必然漂移：实现侧统一调 `LinuxCommands`，工具侧只判空。


---

## 11. 全员可用（2026-11-18 修订）：执行后端阶梯

用户目标改为「**所有用户**都能用上 agent 能力，包括 Linux 环境里的能力」。
按 §10 的两条结论，单靠 proot 做不到 —— 得把"执行"做成可插拔的四种**后端**。

### 11.1 四个后端

| 档 | 后端 | 谁有 | 能装包 | 能真断网 | 依赖 |
|---|---|---|---|---|---|
| B2 | `ROOT_CHROOT` | root 用户 | 能 | 能（`unshare -n`） | 常驻通道 |
| B1 | `TERMUX` | 装了 Termux 的人 | 能 | 不能（proot 无 netns） | Termux + `allow-external-apps` + `RUN_COMMAND` 权限 |
| B0 | `BUILTIN` | **所有用户** | 不能 | 不能 | 无（进程内调用，**不 exec 任何文件**） |
| — | `NONE` | 都没有时 | — | — | 如实报缺什么 |

### 11.2 为什么 B0 排在最前（按覆盖面，不是按性能）

Android 10 起 `targetSdk ≥ 29` 的 app 不能执行自己目录里的文件，我们 `targetSdk = 36`。
而 B0 走**进程内调用**（Python 解释器 JNI 内嵌、媒体转换走系统 API），一个字节都不 exec，
所以不受这条限制 —— 它是唯一"所有人都有"的一档，也覆盖真实任务的大头
（数据处理、表格清洗、格式转换、批处理）。

⚠️ B0 必须**说清自己做不到什么**：装不了包、断不了网、跑不了任意 ELF。
让用户以为有 Linux 然后发现 `apt` 用不了，比一开始说明白更伤信任。

### 11.3 B1 为什么是"借合法性"，不是绕沙箱

`com.termux.RUN_COMMAND` 是 Termux 官方给外部 app 的接口。Termux 自己钉在 targetSdk 28，
所以**在它那边** exec 是合法的。我们只传 `路径 + 参数数组`（`argv[0]` 的 basename 必须在白名单里），
不允许 `bash -lc "<任意脚本>"` —— 否则白名单形同虚设。

三个前提分开报（用户动作完全不同）：
1. 没装 Termux → 去装；
2. 没开外部调用 → 在 Termux 里 `echo allow-external-apps=true >> ~/.termux/termux.properties`；
3. 没给 `com.termux.permission.RUN_COMMAND` → 去授权。

### 11.4 两个决策的默认值（用户未另指定，按推荐项执行）

- **D5 安全姿态 = A：默认关**。B0 在本应用进程里跑 Python，能读本应用的数据（含聊天记录），
  但读不到相册/微信（沙箱外）。开启页必须写明这一点；不开启时 `linux_exec` 直接 `Refused`。
- **D6 Termux = 官方推荐搭配，但不强制**。设置页做"检测 + 跳转 + 图文引导"；
  没装 Termux 的用户仍然有 B0 可用。

### 11.5 本轮已落地（离线可测）

| 文件 | 内容 |
|---|---|
| `rootlogic/LinuxBackends.kt` | `ExecBackend{ROOT_CHROOT,TERMUX,BUILTIN,NONE}`、`LinuxCapability`、`BackendFacts`、`choose()`（root→Termux→内置）、`promptLine()`；降级必须显式（`degraded`） |
| `rootlogic/TermuxCommands.kt` | `build(argv)` → `TermuxRequest(path,arguments,workdir,background)`（只传程序名、参数复用 `LinuxCommands` 校验）、`preflight()` 三分、`advice()` 给可抄的一行 |
| `rootlogic/LinuxEnv.kt` | 新增 `LinuxState.BLOCKED` + `Facts.execBlocked`（系统禁止执行 ≠ 没装），优先级最高 |

测试：`LinuxBackendsTest` 11 条、`TermuxCommandsTest` 9 条、`LinuxEnvTest` +2；
`run_logic_tests.py` → **OK (4540 tests)**。

### 11.6 修订后的里程碑

| 阶段 | 内容 | 覆盖 |
|---|---|---|
| **M1.5** | B0 的 Python 后端（Chaquopy）+ `/work` 落盘 + 开关与说明 | 所有用户 |
| **M2** | B0 的媒体后端（Media3 Transformer，ffmpeg-kit 已停维不用） | 所有用户 |
| **M3** | B1 Termux 通道（`RUN_COMMAND` intent）+ 检测引导 | 装了 Termux 的人 |
| **M4** | B2 root 通道（`RootVerb.LINUX` + chroot） | root 用户 |
| **M5** | B3 自带 proot 实验（§10.2 四步）→ 过则做，不过则删 | 决定"零依赖真 Linux"是否存在 |


## 11.7 M1.5 落地状态（2026-11-18）

已完成（离线可验证）：

| 项 | 结果 |
|---|---|
| Chaquopy 接入 | `chaquopy = 17.2.0`；根脚本 `apply false`，`:app` 应用；`defaultConfig` 补 `abiFilters(arm64-v8a, armeabi-v7a)`；`chaquopy { defaultConfig { pip { } } }` 只用标准库 |
| 运行时 | APK 内含 `libpython3.10.so` + 42 个 chaquopy 条目（两套 ABI）；debug APK **127.3 MB** |
| 构建 | `:app:assembleDebug` BUILD SUCCESSFUL（首次 6m42s 下载，之后 36s） |
| 包装层 | `app/src/main/python/pa_builtin.py`：base64 传入代码、捕获 stdout/stderr、`sys.exit` 保留退出码、异常回 traceback |
| 桥 | `assistant/BuiltinLinuxOps.kt`：实现 `LinuxOpsPort`；超时**弃疗 + 停用**（进程内 Python 杀不掉），`exitCode=124/126` 沿用 shell 惯例 |
| 纯逻辑 | `rootlogic/BuiltinPython.kt`：只认 `python3 -c` 与 `--version`；`/work` ↔ 真实目录双向映射 |
| 接线 | `AppContainer.builtinLinuxOps` + `LinuxExecTool/LinuxReadTool` 进 `agentTools`；开关 `isLinuxBuiltinEnabled()/setLinuxBuiltinEnabled()`，**默认关**（D5） |
| 测试 | `BuiltinPythonTest` 10 条；`run_logic_tests.py` **OK (4550 tests)**；`verify_all.py` **14/14，357s** |

开关界面（2026-11-18 补齐）：

- 设置页新增「Linux 工作区 → 内置 Linux 引擎」，副标题直接把代价写在用户点开之前：
  「与应用同进程，可读到应用自己的数据（含聊天记录）；装不了包、断不了网」。
- `AppShell` 用 `remember { mutableStateOf(container.isLinuxBuiltinEnabled()) }` 承接状态 ——
  这是同一个坑的**第四次**（前三次：allowRefusal / lifeformWeb / lifeformProactive）：
  `@Volatile var` 不自带 Compose 状态，不承接就会出现「点了开关不动，但设置已生效」。

⇒ **M1.5 完成**（后端 + 开关 + 接线 + 说明）。

未完成：pip 包、媒体后端（M2）、Termux（M3）、root chroot（M4）。


## 11.8 M2 落地：媒体后端（Media3 替 ffmpeg-kit，2026-11-18）

| 项 | 结果 |
|---|---|
| 依赖 | `androidx.media3:media3-transformer / media3-effect / media3-common = 1.11.1`（Google Maven，稳定版；1.12 还是 beta） |
| 工具 | 新工具 `media_ops`，四个动作：`probe` / `extract_audio`（m4a）/ `transcode`（mp4、webm）/ `snapshot`（png/jpg） |
| 纯逻辑 | `rootlogic/MediaCommands.kt`：输入扩展名白名单、输出扩展名与动作匹配、禁止覆盖输入、只许 `/work`、`atMs` 夹 0..24h |
| 实现 | `app/.../assistant/Media3Ops.kt`：`MediaMetadataRetriever`（probe / snapshot）+ `Transformer`（抽音轨 / 转码，超时 120s 后 cancel） |
| 开关 | 与「内置 Linux 引擎」**共用**一个开关：两者都是本机算力，用户关掉就该都停 |
| 测试 | `MediaCommandsTest` 9 条、`MediaOpsToolTest` 8 条；`run_logic_tests.py` **OK (4567 tests)** |

**能力边界（写进工具描述，也写在这里）**：能抽音轨、统一转 mp4/webm、截帧、看时长与分辨率；
**不能**做任意滤镜、任意编码参数、字幕、拼接 —— 那些是 ffmpeg 的领域。做一个"像 ffmpeg 的壳"
却在运行时一半参数不支持，比不做更糟。

⚠️ 真机未验证项：Transformer 在部分机型上对 HEVC/AV1 不支持（会返回 `ExportException`），
错误文案已原样带回；见 TO-WORKBUDDY 的本轮四项。


## 11.9 M3 落地：Termux 通道（B1）

| 层 | 实现 |
|---|---|
| 命令构造 | `rootlogic/TermuxCommands.kt`：只接受**程序名**（含 `/` 直接拒）、首词走 `LinuxCommands` 白名单、参数复用同一套路径/长度/NUL 校验 |
| 三前提探测 | 装了（`PackageManager`）/ 有权限（`RUN_COMMAND`）/ 开了 `allow-external-apps`（用 `echo` 探活判定） |
| 结果回收 | `app/assistant/TermuxLinuxOps.kt`：每次调用给一个 `PendingIntent`（带 token），结果回到 `TermuxLinuxOps.RESULT` 广播；`ConcurrentHashMap<token, CompletableDeferred>` 配对，超时回 `exitCode=124` |
| 后端阶梯 | `app/assistant/LadderLinuxOps.kt`：**运行时**问一次 `availability()`，Termux 可用就走它，否则降级内置引擎；降级文案写清"装不了包、断不了网"，并附上 Termux 没就绪的原因 |
| 接线 | `AppContainer.linuxOps = LadderLinuxOps(termuxLinuxOps, builtinLinuxOps)`，`linux_exec`/`linux_read` 都用它；`MainActivity` 在 `onCreate` 注册结果接收器 |

**文件边界（重要）**：Termux 是另一个 uid，读不到我们的 `/work`。所以 B1 只适合"不需要我们的文件"的命令（查版本、装包、跑 Termux home 里的东西）；`linux_read` 仍由我们自己读（应用能读自己的目录），文件型流程走 B0 或 B2。这条写进了 `availability()` 的文案里。

测试：`LadderLinuxOpsTest` 5 条（Termux 优先 / 降级 / 降级文案含差别 / 两边都不可用 / 中途装上就改走 Termux —— 最后一条覆盖 `availability` 参数与方法同名导致 `StackOverflowError` 的坑）。


## 11.10 M4 落地：root 容器（B2）

| 层 | 实现 |
|---|---|
| 线形状 | `rootlogic/LinuxWire.kt`：`run <chroot\|proot> <rootfs> <workDir> <on\|off> <程序> [参数…]`，路径校验挡相对路径/`..`/空白/shell 元字符（因为 `su -c` 那条路要拼成一条字符串） |
| 协议 | `RootVerb.LINUX(10)`；`DaemonHandlers.linux` **服务端重新 decode 一遍**再执行 |
| 动作 | `RootAction.LinuxShell(kind, rootfs, workDir, inner, networkOff)`；`RootCommands.payload` 与 `DaemonCalls.of` 都从同一份 `LinuxWire` 推出 |
| 端口 | `app/assistant/RootLinuxOps.kt`：`availability()` 三态（没 root / 有 root 没 rootfs / 可用），`run` 走 `LinuxShell`，`read` 自己读 `/work` |
| 阶梯 | `LadderLinuxOps(termux, builtin, root)`：**root → Termux → 内置**，每次调用重新判断 |

三条出口的等价性由 `LinuxWireTest` 11 条钉住：编码形状、解码还原、形状/路径拒绝、通道 argv、`su` 字符串、`unshare -n` 只加在 chroot 上、能力归 `shell.root`。

**诚实边界**：当前 chroot 没做 bind mount，容器里的 `/work` 只在根文件系统自带时存在；需要处理手机文件时走内置引擎。根文件系统下载器属于 M5。
