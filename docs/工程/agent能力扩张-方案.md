# Agent 能力扩张方案（v0.1）

> 2026-11-04 立。目标：把「云端大模型 + 这台手机」之间的关系，从**一问一答**
> 变成**它接下一个目标，然后自己把它做完**。
>
> 本文件只写工程决策与落点，不写产品愿景。

---

## 0. 现状（都已核实，不是估计）

| 维度 | 现在 | 位置 |
|---|---|---|
| MCP 工具 | **10 个**：`screen_read` / `screen_shot` / `tap` / `swipe` / `type_text` / `app_host` / `root_exec` / `persona_brief` / `office` / `context_offload` | `android/mcp/src/main/kotlin/com/pocketagent/mcp/*Tool.kt` |
| 第 0 档能力 | **20 条**参数受限动作 + 风险分级 | `android/capabilitylogic/.../CapabilityCatalog.kt` |
| 授权模型 | `RiskLevel`(READ/WRITE/EXTERNAL/MONEY/IDENTITY) + `PermissionPolicy`（纯函数，`Consent` 记忆） | `android/agentui/.../PermissionPolicy.kt`、`RiskLevel.kt` |
| 自主度 | **无**（只有逐次确认） | —— |
| 循环 | `AgentLoop` 存在，**无** Grounder / Verifier / Repair | `android/agentlogic/` |
| 目标边界 | **无**（没有"这台机器只打哪些目标"的概念） | —— |

结论：**工具面太窄、循环不会自我纠错、授权只有"每步问"这一档**。
三者合起来的效果是——模型能点，但点不动一件需要三十步的事，而且每一步都要人陪着。

---

## 1. 能力模型（五组）

```
感知 Sense     读懂屏幕上有什么（不只是节点树，还要"哪个能点"）
动作 Act       在屏幕上做一件事（含手势、输入、意图、系统设置）
编排 Orchestrate 把动作串成任务（等待、断言、重试、并行、断点）
知识 Know      查自己有什么工具、这台机器什么状态、上次做到哪
网络 Net       对外发包（扫、验、利用、收）
```

⚠️ 前四组是"能力"，第五组是"权限"—— 它必须单独有一套边界，见 §5。

---

## 2. 工具面：从 11 条扩到 ~40 条（分四批）

### 批次 A —— 输入补全（最高性价比，1 天）

现在只有 `tap(x,y)` / `swipe` / `type_text`。缺的每一条都让模型被迫"多轮试探"：

| 新工具 | 为什么非它不可 |
|---|---|
| `tap_node(nodeId)` | 坐标会因滚动/换肤失效；节点 id 不会 |
| `long_press` / `double_tap` | 长按才弹出的菜单，模型现在只能猜 |
| `drag(from, to, ms)` | 拖拽排序、滑块验证码 |
| `scroll_until(text, dir, max)` | **一次调用替代十次盲滑** |
| `key_combo(["CTRL","A"])` | 全选/复制/粘贴，输入框清空的唯一可靠方式 |
| `read_clipboard` / `write_clipboard` | 跨 App 搬运文本的带宽级差异 |
| `wait_for(cond, timeoutMs)` | `cond = 文本出现 / 节点消失 / Activity 变化`；**这是"能点"和"能完成任务"的分水岭** |
| `assert_ui(expect)` | 让"我以为点到了"变成可验证事实 |

### 批次 B —— 感知补强（2 天）

| 新工具 | 说明 |
|---|---|
| `screen_digest` | 把 UI 树压成 **~800 字的可读摘要**（分区 + 可点元素 + 文本），替掉让模型读原始 JSON 的做法。⚠️ 这是 token 账本上最划算的一步：一次 `screen_read` 原始树常见 6k–20k token，摘要后 ~250 |
| `screen_shot_marked` | 截图 + 在可点元素上叠编号，模型可以只说「点 7」 |
| `node_query(selector)` | `text~=正则` / `id=` / `desc=` / `clickable=true`，返回前 N 个 |
| `ocr(region)` | 节点树取不到文字的场景（游戏、图片、Canvas 渲染） |
| `toast_wait` / `logcat_tail(filter)` | 判断"到底成没成"的外部信号 |

### 批次 C —— 应用与系统（2 天）

`app_launch` / `app_kill` / `app_info` / `deep_link(uri)` / `intent_send` /
`settings_get` / `settings_put` / `wifi_toggle` / `volume` / `brightness` /
`notification_list` / `notification_dismiss`。

⚠️ `settings_put` 只放 **`CapabilityCatalog` 里已声明的 namespace:key 对**，
不接受任意 key —— 保持"一条能力＝一个受限动作"的既有纪律，
否则等于给整个白名单模型开后门（`CapabilityCatalog` 类注释里已经论证过一次）。

### 批次 D —— 编排原语（2 天，最容易被低估）

| 新工具 | 说明 |
|---|---|
| `checkpoint(label)` / `resume(label)` | 长任务断点。进程被杀后能续，这是"它真的在做一件事"的前提 |
| `spawn_subtask(goal)` | 子任务隔离上下文，避免主循环被中间结果污染 |
| `note(text)` | 显式工作记忆（不依赖模型自己记住） |
| `parallel([...])` | 只对**可交换**的只读动作开放（批量读、批量查）；写动作串行 |

---

## 3. 循环：Plan → Act → Observe → Verify → Repair

现在是「调一次工具看一眼」，缺的是**后置条件**：

```kotlin
// 伪代码，落在 android/agentlogic/
data class Step(val intent: String, val act: ToolCall, val expect: Expectation)

interface Expectation { fun verify(before: ScreenDigest, after: ScreenDigest): Verdict }
// Verdict = Satisfied | Violated(reason) | Unknown(需要再看一眼)
```

三条硬规则：

1. **每步必须带 `expect`**。没有后置条件的动作等于"点一下，然后祈祷"。
2. **同一类失败两次就换策略**，不是重试第三次。策略表：
   `点坐标` → `点节点` → `按键` → `意图直跳` → `放弃并说明`。
3. **`Unknown` 不许当成成功**。它要触发一次 `screen_digest` 复查；
   复查还是 `Unknown`，就如实告诉用户"我不确定成没成"。

这一步的目标不是"更聪明"，是**可判定**：任务结束时，系统能自己回答
「这件事做完了吗」，而不是让用户去屏幕上找。

---

## 4. 自主度：四档（已实现）

`android/agentui/src/main/kotlin/com/pocketagent/agentui/Autonomy.kt`
+ `AutonomyPolicyTest.kt`（11 个用例，**已随 run_logic_tests 跑过：3576 全绿**）

| 档位 | 放过什么 | 还拦什么 |
|---|---|---|
| `ASK` 每步都问 | 只读 | 一切会改动东西的动作 |
| `NOTIFY` 本机随便动 | 点按/输入/改设置 | 往外发、花钱、身份信息 |
| `AUTO` 自己看着办 | 连"发出去"也不问 | 花钱、身份信息 |
| `YOLO` 放开跑 | 除下面两件全放 | **花钱、身份信息** |

**唯一不由档位决定的硬边界**：`MONEY` / `IDENTITY` 在 `YOLO` 档仍问一次，
除非设置里另有一个 `allowIrreversible` 显式开关被打开。

理由不是"保守"，是这两类的错误**没有第二次机会**：钱付错要打银行电话，
身份证号发出去收不回来。而且 `RiskLevel.rememberable` 早就把这两档钉成
"永远只给这一次"——`Autonomy` 只是把这个既有判断延伸到了自动化档位。

> 想彻底不问：打开 `allowIrreversible`。它单独存在、单独命名、单独记审计，
> 不藏在档位里 —— 这样"我放开了"永远是一个可回溯的决定。

---

## 5. 网络能力的目标边界（已实现）

`android/safety/src/main/kotlin/com/pocketagent/safety/ScopeGuard.kt`
+ `ScopeGuardTest.kt`（12 个用例）

- **默认空清单 ⇒ 什么都打不了**（fail-closed，不是靠提醒）。
- 清单写法：`192.0.2.10` / `198.51.100.0/24` / `2001:db8::/32` /
  `api.example.com` / `*.example.com`。
- 域名按**标签边界**匹配：`evilexample.com` 与 `example.com.attacker.net`
  都不会被 `example.com` 命中（这条是扫描器最经典的越界方式）。
- **不做 DNS 解析**：解析会引入 TOCTOU（校验时 A，发包时 B）。
- 每次放行/拒绝都留 `(target, matched|reason)` 供审计页展示。

一个能打外网的模块，若"没配范围"时放行，等于装完就指向全网；
所以这里的默认值是**拒绝**，而不是 `0.0.0.0/0`。

---

## 6. 里程碑

| 里程碑 | 内容 | 验收（可执行） |
|---|---|---|
| **M1**（3 天） | 批次 A 八个输入工具 + `screen_digest` | 单测 + 真机：一条任务「把这份聊天记录里的地址复制到地图搜一下」零人工干预完成 |
| **M2**（3 天） | `Expectation`/`Verdict` + Repair 策略表 | 断网、权限缺失、弹窗打断三种注入故障下，任务仍然收敛或明确失败 |
| **M3**（2 天） | 批次 C + `checkpoint/resume` | 杀进程后 `resume` 能续上；`settings_put` 只认白名单 key |
| **M4**（2 天） | 批次 D + 子任务隔离 | 一条 40 步任务不爆上下文（`context_offload` 已有基础） |

M1 与 M2 是**并行可做**的两条线：工具面决定"能不能",循环决定"靠不靠得住"。

---

## 7. 明确不做的事

- **不做 `shell.exec(cmd: String)`**：`CapabilityCatalog` 类注释已经论证过，
  一旦存在任意命令执行，后面所有风险分级都只是给后门镶边。
- **不把第三方目标写进默认范围**：`ScopeGuard` 的空清单就是这道门。
  要打谁，得有人亲手把那个 IP/域名写进 `scope.txt` —— 一行一次，可审计。

---

## 8. 与既有验证体系的关系

- 新增逻辑全部进 `run_logic_tests.py` 的 27 个模块（现 **3576 用例**）。
- 涉及 Manifest/构建的改动走 `run_gradle_tests.py` + `:app:assembleDebug`。
- 真机部分（无障碍是否真的能点到、`wait_for` 在慢机型上的表现）写进
  `docs/协作/TO-WORKBUDDY.md`，不在此处推测。

---

## 9. 系统级 MCP：常驻 root 通道

### 9.1 先说清楚现有技术到哪一步

「安卓系统级别的 MCP」不是没人做过旁边的形态：

| 已有 | 机制 | 拿到的权限 | 缺什么 |
|---|---|---|---|
| **Shizuku** | `app_process` 起一个 Java 进程，`UserService` 走 binder | shell(2000) 或 root(0) | 协议是 binder，不是 MCP；没有批量事务；返回是 `Parcel` 不是结构化结果 |
| `uiautomator` | `Instrumentation` | 只在自己的测试进程里 | 不能跨 App 常驻 |
| `su -c cmd`（本项目现状） | 每条命令 fork 一次 | uid 0 | **每条 80–300ms**，且只有文本返回 |
| adb over TCP | 需要电脑 | shell | 依赖外部主机 |

⇒ 真正"前无古人"的部分不是"用 root 跑 Java"，而是**把它做成 MCP 的形状**：
一条长连接、批量事务、结构化返回、与 agent 循环的 `Expectation` 直接对接。
机制本身是既有技术的组合，这一点上不该自欺。

### 9.2 落点

`android/rootlogic/src/main/kotlin/com/pocketagent/rootlogic/`
- `PrivilegeWire.kt` —— 帧协议（13 字节固定头 + 长度前缀）、`RootVerb` 动词表
- `PrivilegeClient.kt` —— 一次往返发一批、**按 id 配对**、六类结局分离
- `PrivilegeWireTest.kt` —— 16 个用例（分片、粘包、乱序、断线、坏头）

守护进程侧（`app_process`，uid 0）尚未写，是下一里程碑。

### 9.3 为什么是二进制帧，不是 JSON

- `dumpsys` / `logcat` / 截图正文里满是引号与控制字符，JSON 要么翻倍要么在边缘字符上炸；
- 长度前缀对**分片读取**天然友好；JSON 流要靠括号配平扫描，那是解析器漏洞的经典来源；
- 有 `MAX_BODY = 8MB` 上限：没有上限的长度前缀 = 一行字节把对端 OOM。

### 9.4 六类结局必须分开

`Ok` / `Exit(code, stdout, stderr)` / `DaemonError` / `NoReply` / `Protocol` / `DaemonDown`

沿用 `RootProbeResult` 的三段论：**用户要做的事不同，就不能合并成一个"失败"**。
连接断了要去查 Magisk；命令非零退出只要看 stderr；`NoReply` 是守护进程被打断。

⚠️ 配对绝不允许"用最近的应答顶上"：乱序 + 顺序取 = 动作全错位，而日志一切正常。

### 9.5 延迟账本（**预算估算，非实测**）

| 环节 | 量级 | 处置 |
|---|---|---|
| LLM 一轮 | 800–3000ms | **最大项**。靠"计划编译"摊薄：一次规划 → N 步本地执行 |
| `su -c` 一条 | 80–300ms | 常驻通道 → <1ms |
| 无障碍树读取 | 30–200ms | `screen_digest` 压成 ~800 字 |
| 截图 | 150–400ms | 只在需要视觉时取；优先 digest |
| socket 往返 | <1ms | —— |

结论：**瓶颈不在手机，在"每步都回一次模型"**。所以顺序是
① 计划编译 ② 常驻通道 ③ 事件驱动 wait ④ digest。

> 真机数字由 WorkBuddy 回填，本表只用于排序，不作为结论引用。

### 9.6 桌面端能力映射

| 桌面 agent 会做 | 安卓上的对应 | 现状 |
|---|---|---|
| 读文件 / 写文件 | SAF + `filelogic` | 有 |
| 跑命令 | `PrivilegeClient(EXEC)`，白名单动词 | 协议有，守护进程无 |
| 看屏幕 | 无障碍树 / `CAPTURE` | 有（树）/ 待接（帧） |
| 点鼠标 | `injectInputEvent` 多指 | 只有无障碍 `tap` |
| 浏览器自动化 | `deep_link` + WebView 调试 | 无 |
| 网络请求 | `PROBE` + `ScopeGuard` | 边界有，执行无 |
| 定时 / 后台 | `WorkManager` + FGS | 有 |
| 剪贴板 | `ClipboardManager`（后台受限） | 无 |

### 9.7 攻击能力在 root 通道里的位置

`RootVerb.PROBE` 是唯一对外发包的动词，且：

1. 目标先过 `ScopeGuard.check()`（默认空清单 ⇒ 全拒）；
2. 速率上限写在守护进程侧，不信任客户端传参；
3. 每条探测留 `(target, verb, bytes, result)` 审计记录。

理由与 §5 相同：**默认失败**，而不是默认全通。
