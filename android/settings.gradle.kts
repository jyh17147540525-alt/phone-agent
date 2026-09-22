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

// ── 虚拟屏（v3.0 引入，2026-09-22 已否决）──────────────────
// ⚠️ P0-3 实测：副屏能建、能注入，但**启不了第三方 App**。
//    决定性证据：`uid=2000`(shell) 也被 SafeActivityOptions.checkPermissions 拒，
//    而 Shizuku 同为 uid 2000 → 拿不到更高权限。**这条路已被证伪。**
// 模块保留但不再前进，仅作为历史记录与复活可能（瓶颈只剩"启动"一环）。
// 四级降级链改为：~~虚拟屏~~ → 小窗(freeform) → 全屏接管 → 手动引导。
include(":display")           // ⚠️ 已否决，勿在此模块投入
include(":display:preview")   // ⚠️ 同上

// ── 开源协作（v3.0 新增）──────────────────────────────────
// ⚠️ 凭据必须运行时注入，绝不硬编码进 APK
include(":github")            // GitHub Device Flow / BYO Token、上报通道
include(":contribute")        // 规则与插件贡献流程（生成 PR、内容预览）
