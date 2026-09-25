pluginManagement {
    repositories {
        // 国内网络建议优先使用镜像，按实际网络情况调整
        // maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketAgent"

// ── 应用壳 ──────────────────────────────────────────────
include(":app")

// ── 基础设施 ────────────────────────────────────────────
include(":core:common")
include(":core:crypto")
include(":core:database")
include(":core:network")

// ── 模型接入 ────────────────────────────────────────────
include(":provider:api")
include(":provider:openai-compat")
include(":provider:anthropic")
include(":provider:gemini")
include(":provider:local")
// 语音合成。与对话侧分开成独立模块 —— 协议不同（裸音频字节 vs SSE 文本流）、
// 厂商覆盖面也不同（OpenRouter 只有对话、没有语音），合在一起会得到
// 一堆"这一行对语音无效"的字段。
include(":provider:tts-openai")

// 模型网关的**核心逻辑**（纯 Kotlin，零 Android 依赖）。
//
// ⚠️ 与「HTTP 服务端」分开成两个模块，这是刻意的。网关有两个消费方：
//   · PocketAgent 自己的 agent 循环 —— 直接函数调用，不需要 HTTP
//   · dsh（Node 进程）             —— 需要 loopback HTTP + SSE
// 如果一上来就把"网关"等同于"HTTP 服务端"，agent 循环每轮都要绕一圈
// 127.0.0.1 发 HTTP 给自己（同机 TCP + SSE 编解码，纯浪费）。
//
// 拆开的另一个收益：路由 / 熔断 / 计量这些最值得单测的逻辑能进离线验证器，
// 而 HTTP 外壳只剩"协议翻译"，出错时排查面很小。
include(":provider:gateway")

// 多模型调度：按任务难度在已配置模型间派发。
// 纯 Kotlin、零 Android 依赖 —— 决策错误表现为"静默走贵了"或"任务办砸"，
// 都不报错，所以必须靠离线单测覆盖。
include(":modelrouter")

// 悬浮球的交互逻辑：状态机 / 贴边吸附几何 / 急停语义。
// ⚠️ 与 :overlay 是两件事 —— 本模块零 Android 依赖，可离线测试。
// 拆开的原因：贴边算错会让球移出屏幕（连带丢掉 Android 15 的 FGS 启动豁免），
// 这类几何/状态错误读代码极难发现，必须有测试钉住。
include(":overlaylogic")

// ── 能力层 ──────────────────────────────────────────────
include(":perception")
include(":action")
include(":agent")

// Agent 循环的纯逻辑部分：预算熔断 / 任务状态机 / 感知档位 / 检查点 / 消歧。
// ⚠️ 与 :agent 分开的理由和 :overlaylogic 与 :overlay 一样 ——
//    :agent 是接口与编排（要 Android），本模块零 Android 依赖，可离线完整测试。
//
// ★ 为什么要拆：这里每条逻辑错的后果**都是静默的** ——
//   预算算错只是多跑几轮（用户看不到）、卡死检测漏排除 WAIT
//   只是"任务莫名中止"、感知第 0 档被"顺手修掉"只是每步多花一次截图。
//   没有一条抛异常，没有一条在真机上"一眼看出来"。
include(":agentlogic")

// 文件能力沙箱的纯逻辑部分：范围模型 / 路径归一化 / 穿越防护 / 操作裁决 / 审计。
//
// ⚠️ 与 :action 是两件事 —— :action 管"点屏幕"，本模块管"动文件"。
//    拆开成零 Android 依赖的模块，理由和 :agentlogic 完全一样：
//
// ★ 这里判错的后果**全是静默的，而且方向是危险的那一侧**：
//   · 归一化漏掉一种 `..` 变体  → 授权一个目录等于授权整块存储（用户看不出来）
//   · 包含判定写成字符串前缀    → `/Documents-evil` 被当成在 `/Documents` 之下
//   · 覆盖不要求确认            → 用户的原文件被静默替换，没有第二次机会
//   · 黑名单可被用户设置覆盖    → 一条被诱导的规则就能解除全部保护
//   没有一条抛异常，没有一条在真机上"一眼看出来"。唯一可靠的抓手是确定性离线测试。
include(":filelogic")

// 第 0 档能力（零占屏）的纯逻辑部分：能力声明 / 硬拒绝清单 / 参数校验 / 裁决 / 规划 / 审计。
//
// ⚠️ 与 :action 是两件事 —— :action 管"点屏幕"，本模块管"**不占屏地**改系统状态"
//    （系统设置、Shizuku shell、通知、媒体控制）。
//    拆成零 Android 依赖的模块，理由和 :filelogic 完全一样：
//
// ★ 这里判错的后果**全是静默的，而且用户根本看不见** —— 第 0 档的定义
//   就是"屏幕上不出现任何因 agent 而起的界面变化"，所以连"看起来不对劲"
//   这个机会都没有：
//   · 硬拒绝清单漏一条设置键 → `secure/enabled_accessibility_services`
//     可写，agent 给自己授予无障碍权限，整个"默认拒绝、逐项放行"的模型归零
//   · 参数校验写松一点     → `cmd package disable` 收到系统 UI 的包名，
//     用户下次拿起手机发现没有状态栏、没有导航、没有桌面
//   · 确认标志被提前消费   → "用户点过确认"变成绕过一切的后门
//   没有一条抛异常，没有一条在真机上"一眼看出来"。
//   唯一可靠的抓手是确定性离线测试。
include(":capabilitylogic")

// 第 0 档能力的 Android 侧通道（T0-A 设置读写）。
// ⚠️ 它只做"把 :capabilitylogic 定下的端口接到系统 API 上"，
//    一行判定逻辑都没有 —— 理由见上面那一段。
include(":capability")

include(":safety")
include(":keymgmt")
include(":memory")
include(":overlay")

// ── 插件体系（v2.0 新增，社区分发的核心差异化）─────────────
include(":plugin:api")        // 插件契约、能力定义、Manifest —— 零 Android 依赖
include(":plugin:runtime")    // 加载器、生命周期、能力代理、沙箱
include(":plugin:rules")      // L1 规则包：选择器引擎、匹配器、动作执行
include(":plugin:script")     // L2 JS 沙箱运行时
include(":plugin:store")      // 插件页：列表、订阅源、导入导出、冲突检测
include(":plugin:devtools")   // 快照审查、规则编辑器、测试台

// ── 分发与引导（v2.0 新增，无商店环境的必需件）─────────────
include(":update")            // 应用内自更新、哈希校验
include(":channel")           // 渠道号与隐私友好统计
include(":onboarding")        // 分机型权限引导向导与自检

// ── 虚拟屏（v3.0 引入）──────────────────────────────────────
// ⚠️ 2026-09-25 状态修正：本模块此前的「已否决」结论**已被后续实验部分推翻**。
//
//    原否决理由（"副屏能建、能注入，但**启不了第三方 App**；shell uid=2000 也被
//    SafeActivityOptions.checkPermissions 拒"）**与后来的实测不符** ——
//    用 `app_process` + shell 特权**确实把第三方 App 启到了虚拟屏上**
//    （微信 / 系统设置 / 小米账号 均实测落屏），
//    且 `am display move-stack <taskId> <displayId>` 能把**已在运行**的第三方 App
//    搬到虚拟屏（只搬 RootTask、不启动 Activity ⇒ 绕开启动墙）。
//
//    ⇒ 真正的瓶颈**不是「启动」**，而是「读屏」与「不打扰用户」的取舍，外加隐私红线。
//
// ⚠️ 本模块接口（VirtualDisplayManager.kt）基于**旧的 overlay 路线**设计，
//    与现行 VDM 路线（`createVirtualDisplay` + 自带 `ImageReader` 消费面）
//    **不是同一套机制** —— 实现时**不要照抄接口里的命令**（如 `overlay_display_devices`
//    与 `screencap -d`，后者在 VDM 路线下由消费面取代）。
//
// ★ 现行结论 / flags 表 / 未解问题：`docs/无感虚拟屏方案存档与交接-v1.0.md`
include(":display")           // ⚠️ 视为「历史设计稿」；实现前必读交接文档
include(":display:preview")   // ⚠️ 同上

// ── 开源协作（v3.0 新增）──────────────────────────────────
// ⚠️ 凭据必须运行时注入，绝不硬编码进 APK
include(":github")            // GitHub Device Flow / BYO Token、上报通道
include(":contribute")        // 规则与插件贡献流程（生成 PR、内容预览）

// ── AI 手机助理（v4.0 新增）────────────────────────────────
// 方向变更（2026-09-26）：从「agent 工具」进化为「AI 手机助理」。
// 上面那些能力层**全部保留**，但角色从「主角」降为助理调用的**执行器**。
// ★ 设计稿：`docs/AI手机助理架构-v1.0.md`
//
// 三条新不变量（改动需评审）：
//   ① 助理层不直接碰 Provider —— 一切模型调用过 `:provider:gateway`
//      （路由 → 熔断 → 解密 → 转发 → 计量）；
//   ② 记忆层不自行发网络 —— 需要 LLM 蒸馏时走注入的端口；
//   ③ 人格层不能碰安全边界 —— `SafetyBones` 之外的字段才可微调。

// 人格模型 / 微调算法 / 变更账本 / 反漂移。
//
// ⚠️ 与 `:assistant` 分开的理由和 `:overlaylogic` 与 `:overlay` 一样 ——
//    后者是接口与编排（要 Android），而这里零 Android 依赖，能进离线验证器。
// ★ 为什么必须离线可测：这一层判错的后果**全是静默的** ——
//    "微调不生效"只是用户以为变了其实没变；"白名单漏一个字段"是安全边界
//    被击穿**且不抛异常**；"反漂移阈值算错"是助理慢慢变成另一个 AI，
//    用户说不出哪里不对，只是不再信任。没有一条在真机上「一眼看出来」。
include(":personalogic")

// L0–L3 分层记忆 / 上下文卸载 / 任务画布（移植 TencentDB Agent Memory 的纯逻辑部分）。
// ⚠️ 落库在 `:memory`（Room），这里只管算法。
// ★ 判错的后果同样是静默的："L1 提取漏一类"是该记住的没记住；"卸载顺序颠倒"
//    是 PrivacyFilter 跑在卸载之后 = 隐私**已经落盘**且无人知道。
include(":memorylogic")

// 语音会话状态机 / 打断判定 / 延迟预算。
// ⚠️ 与 `:voice` 分开的理由同上："打断漏掉取消在途请求"是助理停了但 token
//    还在烧；"延迟预算超了"只是「感觉有点慢」，没有任何报错。
include(":voicelogic")

// 助理编排：人格 + 记忆 + 对话循环。
// ⚠️ 它不直接依赖任何能力层实现（`:perception` / `:action` / …）—— 能力经端口注入，
//    否则「助理」就与「读屏/点击」绑死，换执行通道要改助理代码。
include(":assistant")

// 音频采集 / 播放 / VAD 的 Android 实现。
// 只做「把 `:voicelogic` 定下的端口接到 AudioRecord / AudioTrack 上」，一行判定逻辑都没有。
include(":voice")

// TTS 封装层：能力探测 + **显式**降级链 + 统一接口。厂商适配器在 `:provider:*`。
// ⚠️ 现有 `:provider:tts-openai` 是**一个适配器**，不是封装层 —— 本模块在它之上。
//    降级必须显式可观测：静默丢参数正是本项目头号 bug 形态「安静地少做一件事」。
include(":tts")
