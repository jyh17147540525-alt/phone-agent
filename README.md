# PocketAgent

[English](README.en.md) | **简体中文**

> 自带 API Key（BYOK）的 Android 手机 AI 智能体 —— 用你自己的模型，让手机替你办事。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)](#系统要求)
[![Status](https://img.shields.io/badge/Status-M0%20进行中-orange.svg)](#项目状态)

---

## ⚠️ 项目状态：M0 进行中

**可以构建出可安装的 APK 了，但它还不会「干活」。**

已完成：
- ✅ 完整项目方案（v1.0 / v2.0 / v3.0）与 M0 技术验证手册（18 个实验）
- ✅ 工程骨架（30 个 Gradle 模块）
- ✅ Provider 层（OpenAI 兼容协议，覆盖 11 家厂商）
- ✅ 安全护栏（敏感页面 / 敏感控件 / 危险动作 / 频率限制 + 审计）
- ✅ **插件体系** —— 契约、三级分级、能力白名单、禁止前缀拦截
- ✅ **插件市场** —— 浏览 / 搜索 / 下载 / 安装。无服务端，订阅式静态源
- ✅ **本地导入** —— `.pagent` 包，逐条风险告知，高危插件需手打确认词
- ✅ **插件管理** —— 查看能力与风险、卸载（二次确认）
- ✅ **订阅源管理** —— 添加 / 移除 / 恢复内置源
- ✅ **社区源脚手架** —— 可复现打包、索引生成、2 个示例插件 + 模板
- ✅ **305 个单元测试全绿**
- ✅ **可构建的 debug APK**

未完成：
- ❌ **感知层 / 执行层 / agent 循环** —— 也就是 AI 真正"替你办事"的部分
- ❌ API Key 管理界面
- ❌ release 签名
- ❌ **内置源尚未上线** —— `pocketagent-community.github.io` 那个仓库还不存在，
  所以用户首次打开市场会看到「源加载失败」。这是**如实报告**，不是 bug；
  订阅源管理页就是为此准备的出路（见 [`community-source/`](community-source/)）

### 当前 APK 能做什么，不能做什么

**能**：装插件、看插件、卸载插件、管理订阅源。插件包会被完整校验 ——
哈希、zip slip / zip bomb 防护、清单规则、禁止能力拦截，一道都不少。

**不能**：它**不会读屏、不会点击、不会调用模型**。装好的插件也不会运行，
因为执行引擎还没落地。界面上对此有明确说明 —— 不会给一个点了没反应的假开关。

> 之所以先把插件体系做完整，是因为它是**唯一能在没有无障碍权限、没有 Shizuku、
> 没有真机的情况下被完整验证**的部分。而它恰好又是安全上最要命的部分：
> 校验、解压、能力白名单 —— 这些代码写错了不会报错，只会安静地放行一个恶意插件。

### 两个决定性问题仍未验证

它们决定产品形态是否成立：
1. **EX-16 虚拟屏可行性** —— Shizuku 能否把第三方 App 启动到虚拟屏上
2. **EX-13 侧载受限设置** —— Android 13+ 侧载安装后能否获得无障碍权限

---

## 这是什么

一个 Android 应用，让你**用自己的大模型 API Key**（OpenAI / DeepSeek / 通义 / 火山方舟 / Claude / Gemini / 本地模型…）驱动一个能看懂屏幕、替你操作 App 的智能体。

### 和"AI 手机助手"的区别

| 维度 | 系统级 AI 助手 | PocketAgent |
|------|---------------|-------------|
| 模型 | 厂商自有，服务端推理 | **你自带的任意模型，随时可换** |
| 数据 | 上传到厂商服务器 | **不经任何中间服务器，直连你的 Provider** |
| 成本 | 厂商定价 | **你按自己的 Provider 计费，本应用零推理成本** |
| 权限来源 | 厂商系统级合作 | 用户手动授权 |
| 透明性 | 黑盒 | **每步操作可见、可中止、可审计** |

### 这是什么，不是什么

**是**：
- 一个透明的、可换模型的、数据自主的手机自动化工具
- 一个可以让你自己写规则扩展的插件平台

**不是**：
- ❌ 抢红包 / 刷单 / 薅羊毛工具
- ❌ 游戏辅助 / 外挂
- ❌ 任何涉及支付、转账、密码输入的操作
- ❌ 破解、注入、Hook 第三方应用

---

## 核心设计原则

这些原则写在 [方案文档](docs/) 里，也约束着每一行代码：

1. **不建自有后端** —— 所有数据本地化，模型请求由客户端直连你指定的 Provider。这既是零成本的关键，也是"工具"而非"服务"定位的护城河。
2. **执行层多通道抽象** —— 无障碍 / Shizuku / 输入法 / 悬浮窗引导四通道可插拔、可运行时降级。**禁止任何模块直接依赖 AccessibilityService**（Android 17 正在收紧它）。
3. **感知层不依赖无障碍树** —— 统一走 `ScreenSnapshot`，树可用优先用树（省钱），不可用则截图兜底。
4. **主动放弃支付环节** —— 命中敏感页面规则立即停止、丢弃截图、交还用户。规则只允许加严，不允许配置绕过。
5. **手动引导模式是正式产品形态** —— 不是残废版，是政策收紧时的救命方案。
6. **Key 永不落明文** —— Keystore + AES-GCM + SQLCipher；不进日志、不进崩溃上报、不进备份。
7. **本体提供「能力」，插件提供「意图」** —— 所有插件调用必须经能力代理，插件永远拿不到 API Key 明文、拿不到原始节点对象、绕不过安全守卫。
8. **凭据一律运行时注入，绝不硬编码** —— 包括 GitHub 上报凭据。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                    app (UI 壳)                            │
│  对话 │ Key 管理 │ 任务 │ 插件页 │ 设置 │ 权限引导 │ 贡献  │
├──────────────────────────────────────────────────────────┤
│  plugin 层    契约 / 运行时 / 规则引擎 / JS 沙箱 / 插件页  │
├──────────────────────────────────────────────────────────┤
│  display 层   虚拟屏管理 / 副屏预览 / 坐标映射             │
├──────────────────────────────────────────────────────────┤
│  domain 层    纯 Kotlin，零 Android 依赖                   │
├──────────────────────────────────────────────────────────┤
│  data 层      Room / OkHttp / Keystore / 更新 / 渠道       │
├──────────────────────────────────────────────────────────┤
│  platform 层  无障碍 / Shizuku / MediaProjection / 悬浮窗  │
└──────────────────────────────────────────────────────────┘
```

共 30 个模块，详见 [`android/README.md`](android/README.md) 与 [方案文档](docs/)。

### 三级插件体系

| 级别 | 形态 | 能力 | 开发门槛 | 预期占比 |
|:---:|------|------|:---:|:---:|
| **L1** | 规则包（JSON 声明式） | 页面匹配 + 条件 + 动作 | 低 | 80% |
| **L2** | JS 脚本（沙箱） | L1 + 逻辑运算 + 调用本体 API | 中 | 18% |
| **L3** | 原生 APK（签名校验） | 完整 Android 能力 | 高 | 2% |

**主力是 L1** —— 因为社区贡献的门槛决定了生态规模。写一条规则不该需要会编程。

### 插件源：市场没有服务端

"插件市场"不是一台服务器，而是**若干个静态 JSON 地址**。客户端把用户订阅的源
拉下来、合并、检索、展示。这样做的收益按重要性排序：

1. **没有审核权** —— 任何人架源都不需要经过我们，我们也不承担内容审核义务
2. **没有单点** —— 官方源挂了，用户自己加的源照常工作
3. **没有账户体系**，零运维、零成本

代价是**没有中心化的下架能力**。一个源如果开始分发恶意插件，客户端只能把违规
条目**明确展示**为"被拒绝"（而不是悄悄过滤掉），并靠内置黑名单兜底。
所以源的声誉完全由源自己负责。

[`community-source/`](community-source/) 是一个**完整可发布的源脚手架**，
包含两个真实的示例插件、一个模板，以及打包脚本：

```bash
cd community-source
python build_source.py          # 打包 + 生成 index.json
python build_source.py --check  # 确认产物与源一致（适合放 CI）
```

产出 `site/plugins/` 可以直接扔到 GitHub Pages 上。**任何人都是源** ——
这句设计承诺只有在"自己搭一个源"的成本足够低时才成立，所以这个目录
和它的 [README](community-source/README.md) 是基础设施，不是示例代码。

> ⚠️ 打包是**可复现**的：固定了 zip 时间戳、权限位、条目顺序与压缩级别，
> 同样的输入一定得到同样的 sha256。这不是洁癖 —— 哈希对不上会让客户端
> 在解压前就拒绝安装，而报错会说"可能是下载途中被替换"，把用户引向
> 完全错误的方向。

---

## 系统要求

| 项 | 要求 |
|----|------|
| Android 版本 | **12 (API 31) 及以上** |
| 存储 | 约 100 MB（含本地模型则更多） |
| 网络 | 仅用于访问你配置的模型 Provider |
| 可选依赖 | [Shizuku](https://shizuku.rikka.app/) —— 虚拟屏模式必需 |
| 首要适配机型 | Redmi K60（HyperOS） |

---

## 安装

目前没有正式发布渠道，需要自己构建：

```bash
cd android && ./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk（约 61 MB）
```

把 APK 传到手机，在文件管理器里点开安装。首次安装需要在系统里允许「安装未知应用」。

> ⚠️ 当前是 **debug 签名**（`CN=Android Debug`），包名带 `.debug` 后缀，
> 因此可以与未来的 release 版共存安装。release 签名策略尚未确定 ——
> 社区分发要求所有版本用同一个 keystore（否则用户无法覆盖安装更新），
> 而 keystore 该由谁保管、要不要进公开仓库，需要维护者拍板。

> ⚠️ **Android 13+ 的「受限设置」**：侧载安装的应用会被系统标记为受限，
> 这会拦住后续的权限授予（尤其无障碍权限），且**覆盖安装后会重置**。
> 这是社区分发路线上的头号风险，详见方案文档的社区分发章节。
> M0 阶段用不到无障碍权限，所以暂时不受影响。

---

## 路线图

| 阶段 | 内容 | 状态 |
|------|------|:---:|
| M0 | 技术验证（18 个实验，含 2 个决定性问题） | 🔄 待开始 |
| M1 | MVP + 插件框架 | ⏳ |
| M2 | 任务引擎 + 脚本插件 + 虚拟屏集成 | ⏳ |
| M3 | 体验优化 + 开发工具 + GitHub 通道 | ⏳ |
| M4 | 开源治理 + 发布 | ⏳ |
| M5 | 插件生态运营 | ⏳ |

详见 [`docs/M0技术验证与工程落地手册-v1.0.md`](docs/) 与 [`docs/社区分发版方案-v3.0.md`](docs/)。

---

## 安全边界

**本项目对以下行为设定了不可绕过的限制**（代码层面强制，不是靠自觉）：

| 限制 | 说明 |
|------|------|
| 🚫 支付与转账 | 命中即停止，丢弃截图，交还用户 |
| 🚫 密码与验证码输入 | 不读取、不输入 |
| 🚫 金融类应用 | 包名黑名单，只允许打开到首页为止 |
| 🚫 抢红包 / 刷单 / 薅羊毛 | 明确禁止，不接受相关规则贡献 |
| 🚫 游戏辅助 | 明确禁止 |
| 🚫 插件申请 `payment.*` / `key.*` / `crypto.*` / `system.*` 能力 | 直接拒绝安装 |

**如果你发现任何绕过这些限制的方法，请按安全漏洞处理**，见 [SECURITY.md](SECURITY.md)（待补充）。

---

## 隐私

- **无账号体系**，不收集任何个人信息
- **无自有服务器**，所有数据留在你的设备上
- **模型请求直连你配置的 Provider**，不经过任何中间方
- **屏幕内容仅在执行任务时读取**，任务结束即销毁，不上传
- 崩溃上报**默认关闭**，需你显式授权后才启用，且内容经脱敏

---

## 开发与验证

### 完整构建（需要 JDK 17 + Android SDK 36）

**先告诉 Gradle 你的 SDK 在哪**，否则构建会直接死在配置阶段：

```bash
# 二选一
export ANDROID_HOME=/path/to/android-sdk        # 环境变量
echo "sdk.dir=/path/to/android-sdk" > android/local.properties   # 或本地属性文件
```

`local.properties` 已被 `.gitignore` 排除，是**每台机器各自一份**的东西 ——
它记录的是本机路径，提交上去只会给别人添乱。

```bash
cd android
./gradlew test          # 单元测试
./gradlew assembleDebug # 打 APK → app/build/outputs/apk/debug/app-debug.apk
```

JDK 用 **17**（不要用 21+，AGP 8.x 对高版本 JDK 支持不稳）。

> ⚠️ **项目路径不能含非 ASCII 字符**（比如中文目录名）—— AGP 在 Windows 上会直接拒绝构建。
> 临时绕过：`./gradlew assembleDebug -Pandroid.overridePathCheck=true`。
> 这是本地环境的权宜之计，**不要写进 `gradle.properties`** —— 不该让所有协作者继承这个绕过。

> ⚠️ 如果 `./gradlew` 要下载 Gradle 发行版却卡住不动，多半是网络问题。
> 仓库里的 wrapper 默认指向腾讯云镜像（`services.gradle.org` 在中国大陆实测不可达，
> `curl` 返回 `000`）。换回官方的写法在 `gradle/wrapper/gradle-wrapper.properties` 的注释里。

### ⚠️ 别给守护进程设 `-Dfile.encoding=UTF-8`

这一条曾经踩过，值得单独留档，因为**报错信息完全指不到真实原因**。

现象：`./gradlew test` 下**每一个测试类**都报

```
java.lang.ClassNotFoundException: com.pocketagent.plugin.api.PluginBundleTest
```

而 `.class` 文件明明就在磁盘上，`javap` 也能正常读出来。堆栈里还能看到 JUnit
自己的类 —— 于是很容易判断成"测试代码有问题"。

真实原因是一处**编码错配**：

| 环节 | 用的编码 |
|---|---|
| Gradle 把测试 worker 的 classpath 写进 `@argfile` | 守护进程的 `file.encoding` |
| JVM 读 `@argfile` | `sun.jnu.encoding`（**平台编码**，`-D` 改不动） |

一旦强制守护进程用 UTF-8，而平台编码是 GBK（中文 Windows），路径就乱了：

```
直接传参：    D:/手机agent开发/android/...        → 加载成功
UTF-8 argfile：D:/鎵嬫満agent寮?鍙?/android/...  → ClassNotFoundException
```

classpath 里的目录找不到，测试类自然加载不了。而 JUnit 的 jar 在纯 ASCII 路径
（`~/.gradle/caches`）下，所以它自己加载正常，进一步误导排查方向。

**所以 `gradle.properties` 里刻意不设这个参数** —— 去掉之后守护进程与 worker
都用平台编码，两边一致，Linux 与中文 Windows 都正常。详细推导在那个文件里。

> 这个坑只在**项目路径含非 ASCII 字符**时才会触发。把项目放在
> `D:\pocketagent` 这类纯 ASCII 路径下，设不设都不会出问题 ——
> 但依赖"路径是 ASCII"来避免一个编码 bug，不如直接把编码对齐。

### 版本上限（改依赖前必看）

| 依赖 | 当前 | 为什么不能更高 |
|---|---|---|
| Compose BOM | `2026.06.01` | 1.12.0 起要求 `minCompileSdk=37` / `minAGP=9.1.0`，超出本项目工具链 |
| `activity-compose` | 1.11.0 | 要求 `minCompileSdk=36` / `minAGP=8.9.1`，**正好卡在线上** |
| `core-ktx` | 1.17.0 | 同上 |

### 六个验证脚本

```bash
python tools/verify/run_logic_tests.py                     # 离线单测，不需要 Android SDK
python tools/verify/check_version_catalog.py android       # libs.* 访问器对账
python tools/verify/check_module_deps.py android           # 模块间 project 依赖是否漏声明
python tools/verify/check_kt_quotes.py android             # 中文文案里的 ASCII 引号误用
python tools/verify/check_aar_metadata.py --bom 2026.06.01 # 读 aar 里的 compileSdk 门槛
python community-source/build_source.py --check            # 社区源的产物是否与源一致
```

前五个在 `tools/verify/` 下，最后一个跟着社区源走（它校验的是那个目录的产物，
放在一起才不会被遗忘）。

**`run_logic_tests.py`** —— 本项目绝大多数高风险逻辑（SSE 解析、密钥脱敏、
token 估算、费用计算、插件校验、zip 防护）都在**零 Android 依赖**的纯 Kotlin 模块里，
但它们所在的 Gradle 模块声明了 `com.android.library`，没有 SDK 连编译都过不去。
这个脚本绕开 Gradle 与 AGP，直接用 Kotlin 命令行编译器把它们抓出来编译并跑 JUnit。
依赖（约 70MB）自动下载到用户级缓存目录，不进仓库。

> 这只是**没有 Android SDK 时的过渡手段**。SDK 就位后 `./gradlew test` 才是唯一权威，
> 两者都要能通过。
>
> ⚠️ **但它有一个重要的盲区：它看不出漏声明的模块依赖。**
> 它把所有模块塞进**同一次 kotlinc 调用**，于是 `:agent` 引用 `:action` 的类型
> 会"顺便"解析成功，哪怕 `build.gradle.kts` 里根本没声明这个依赖。
> 而 Gradle 是每个模块独立编译的，会直接报 `Unresolved reference`。
> 这就是下面那个脚本存在的理由。

**`check_module_deps.py`** —— 各模块的 `build.gradle.kts` 由生成器产出，
而那个生成器**只会硬编码一条 `project(":core:common")`**，没有表达模块间依赖的机制。
于是 `:provider:openai-compat` 从 `:provider:api` 导入 13 个符号却没声明依赖，
`:agent` 用了 `:action` 和 `:perception` 也没声明 —— 这些模块在 Gradle 下编译不过，
但离线测试跑器完全看不出来。

这个脚本扫源码里的 `import` 与**全限定名引用**，映射回模块，与声明的
`project(":...")` 对账。把八分钟一轮的构建反馈压成两秒。

**`check_version_catalog.py`** —— Gradle 版本目录的访问器（`libs.androidx.core.ktx`）
是**编译期**解析的。30 个模块里任何一个写错别名，配置阶段就失败，而且报错指向
一个你根本没在用的模块。这个脚本在构建之前把 31 个 `build.gradle.kts` 全对一遍。

**`check_aar_metadata.py`** —— 直接下载 aar、读出里面的
`META-INF/com/android/build/gradle/aar-metadata.properties`，把 `minCompileSdk`
和 `minAndroidGradlePluginVersion` 打出来。升任何 AndroidX 依赖前先跑它，
就不用等四分钟的构建报错。

**`check_kt_quotes.py`** —— 中文文案里写引号时很容易打成 ASCII `"`，而它在 Kotlin 里
是字符串定界符，会让字符串提前终止。这个错误在等宽字体下几乎看不出来，
报错行还会指向后面几行。

---

## 参与贡献

欢迎贡献，尤其是：

- **规则包（L1）** —— 门槛最低，收益最直接
- **机型适配反馈** —— 我们没有预算买测试机，你的反馈非常宝贵
- **文档与翻译**

请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，其中列明了不可提交的内容。

**贡献前必读的三条红线**：
1. 不得提交针对支付、转账、密码、验证码页面的规则
2. 不得提交抢红包、刷单、薅羊毛、游戏辅助类规则
3. 不得在规则或插件中申请被禁止的能力

---

## 许可证

[GPL-3.0](LICENSE)

本项目为开源非盈利项目。采用 GPL-3.0 是为了确保**任何衍生版本也必须开源**，
防止有人拿它做闭源套壳或商业滥用。

---

## 免责声明

本软件按"现状"提供，不提供任何形式的担保。

使用者需自行承担：
- 使用本软件操作第三方应用可能导致的账号风控、封禁等后果
- 因误操作造成的任何损失
- 因使用第三方模型 Provider 产生的费用

**本项目不提供、不代理、不转售任何模型服务。** 模型调用由使用者自备的第三方账号发起，
使用者与该 Provider 之间的服务关系与本项目无关。
