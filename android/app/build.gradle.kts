plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)

    // ⚠️ ksp 与 hilt 插件在 M0 一并摘掉，而不是"留着不用"。
    //    Hilt 的 Gradle 插件会校验是否声明了 hilt-compiler 依赖，
    //    只留插件不留依赖会直接构建失败 —— 而 M0 的 AppContainer 是手写的，
    //    没有任何 @Module 需要生成。等各模块的构造器就位时两个一起加回来。
    // alias(libs.plugins.ksp)
    // alias(libs.plugins.hilt)
}

android {
    namespace = "com.pocketagent"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.pocketagent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // ⚠️ 这两个数字**每次发版必须手动改**。
        //
        //    之前它们从项目开始就没动过（一直是 1 / "0.1.0-m0"），
        //    后果是：**从版本号分不出新旧**，只能靠 `lastUpdateTime` ——
        //    而 `lastUpdateTime` 连"重装了同一个包"都分不出来
        //    （实测：装了个内容完全没变的包，它也照样更新）。
        //
        //    ⇒ 判据要么是能区分构建的版本号，要么是查 dex 内容。
        //      版本号是给人看的那一个。
        //
        // ★ m2（2026-10-19）：聊天路径接上 `PhoneAgentLoop` —— 应用内
        //   聊天第一次真的能读屏、能点击、能输入。这是一次**功能性**更新，
        //   所以必须 bump：装到手机上之后，`lastUpdateTime` 只证明"装了东西"，
        //   而版本号才说明"装的是哪个东西"。
        versionCode = 3
        versionName = "0.1.0-m2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 只保留中文与英文资源，减小包体
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // 调试期保留日志，但脱敏器仍然生效（见 core-network/LogSanitizer）
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // ⚠️ 安全红线：禁止备份，防止 API Key 被 adb backup 提取
            // 具体配置见 AndroidManifest 与 data_extraction_rules.xml
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    // 保证 .so 与资源在测试与真机间一致
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// ⚠️ 这个块必须在 android { } **外面**。
//    compilerOptions 是 Kotlin Gradle 插件的扩展，写进 android { } 里
//    会变成未解析引用而直接构建失败。它与上面的 compileOptions 必须一致，
//    否则 AGP 会报 "Inconsistent JVM-target compatibility"。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ── 本项目模块 ──────────────────────────────────────
    //
    // ⚠️ M0 只挂真正被引用的模块，其余保留为注释。
    //
    // 理由和 Manifest 收窄权限是同一条：现在挂上 :perception / :agent，
    // 会连带把 ML Kit、Shizuku 这些重依赖拖进构建，而这个阶段的代码
    // 一行都没用到它们。构建时间和包体白涨，
    // 更糟的是**让人误以为这些能力已经可用**。
    //
    // 逐项启用的时机 = 对应模块写出第一行代码的时候。
    //
    // ⚠️ 代价要说清楚：`:core:database` 会把 SQLCipher 的四个 ABI 原生库
    //    （arm64-v8a / armeabi-v7a / x86 / x86_64）打进包体。目前 Key 管理
    //    确实需要它，所以这个代价是实的。等 M0 结束、要开始控制包体时，
    //    应该考虑用 `ndk { abiFilters }` 只留 arm64-v8a —— 但那是**发布前**
    //    的决定，现在就砍掉会让模拟器（x86_64）没法跑。
    implementation(project(":plugin:api"))            // 插件契约、校验、市场索引、完整性校验
    implementation(project(":core:crypto"))           // Keystore 主密钥 + AES-GCM
    implementation(project(":core:database"))         // SQLCipher + 凭据表（含 Room schema）
    implementation(project(":provider:api"))          // LlmProvider 契约与 ProviderCredential
    implementation(project(":provider:openai-compat"))// 11 家内置服务商（都走 OpenAI 兼容协议）
    implementation(project(":provider:tts-openai"))   // 语音合成（OpenAI /audio/speech 协议）
    implementation(project(":keymgmt"))               // API Key 的存储 / 校验 / 取用
    implementation(project(":modelrouter"))           // 模型配置仓储契约 + 难度路由 + 配置体检
    implementation(project(":core:network"))          // 日志脱敏（网关错误信息出站前的最后一道）
    implementation(project(":provider:gateway"))      // loopback 网关：dsh 的模型出口

    // 以下随对应模块落地启用：
    implementation(project(":core:common"))           // 原子写文本文件（配置投递用）
    // 第 0 档能力（零占屏）的 Android 侧通道。
    //
    // ⚠️ 加这一行不只是为了"能用" —— `WRITE_SECURE_SETTINGS` 与
    //    `WRITE_SETTINGS` 是声明在 :capability 自己的 manifest 里的，
    //    而 **manifest 合并只在有人依赖那个模块时才生效**。
    //    少这一行的话，权限声明不会进最终 APK，`pm grant` 会报
    //    "Unknown permission"（或静默不生效），而模块代码照样编译通过 ——
    //    又是一次"不报错、只是安静地少做一件事"。
    //
    // ⚠️ 它不引入任何新的第三方依赖：:capability 只用 androidx.core、
    //    coroutines 与 timber，这三样 :app 本来就有。
    implementation(project(":capability"))            // T0-A 设置读写通道（第 0 档）
    // 第 0 档能力页要直接列目录、直接裁决、直接把结果翻成文案 ——
    // 那些类型（Capability / CapabilityOutcome / CapabilityMessages）全在纯模块里。
    // ⚠️ 光有 `:capability` 不够：它是 `implementation` 依赖，
    //    `:capabilitylogic` 不会传递到 `:app` 的编译类路径上，
    //    而**离线跑器看不出来**（它把所有模块塞进同一次 kotlinc 调用）。
    implementation(project(":capabilitylogic"))
    // 第 0 档的**文件范围**（`FileScope` / `ScopeRoot`）—— 这一页要把它
    // 从 SAF 授权里现算出来，再交给 `CapabilityRunner`。
    //
    // ⚠️ 为什么不把这个 lambda 塞进 `:capability` 里、让 `:app` 完全看不到
    //    `:filelogic`：因为"用户现在授权了哪些目录"是**这一页的状态**，
    //    而状态属于 ViewModel。把它藏进下层只会让"授权完要刷新"这件事
    //    没有明确的触发点 —— 表现是"刚选完目录，点了却说还没授权"。
    implementation(project(":filelogic"))
    // ── MCP 能力桥（P2）开启的三项 ─────────────────────────
    //
    // ⚠️ 到了「对应模块写出第一行代码」的时机：screen_read 需要
    //    感知层的**真实实现**（无障碍树 → ScreenSnapshot）。
    //    代价说明白：:perception 会把 ML Kit 的两个文字识别包带进构建，
    //    而当前代码路径只用树、不用 OCR —— 这是为下一阶段（截图兜底）
    //    预付的成本，构建时间与包体会涨。
    implementation(project(":perception"))            // 无障碍树 → ScreenSnapshot（能力桥的读屏通道）
    // 敏感判定的引擎（SensitiveDetector + 规则库）：能力桥拒绝
    // 「支付 / 密码 / 验证码」页面靠它，而不是在 :mcp 里另写一份规则。
    implementation(project(":safety"))
    // 能力桥本体（纯 Kotlin）：协议 / loopback 服务器 / screen_read / dsh 配置渲染。
    // 它有 69 条离线测试（属于 tools/verify/run_logic_tests.py 的覆盖范围）。
    implementation(project(":mcp"))
    // 人格逻辑（DL-1…DL-4）：人格卡 / 语言指纹 / 装配器 / 输出守卫 / 状态机 /
    // 关系阶段 / 人格包。**纯 Kotlin、零 Android 依赖**（在 PURE_KOTLIN 里）。
    //
    // ⚠️ 为什么必须显式声明，而不是靠 `:mcp` 传过来：`:mcp` 用的是
    //    `implementation(project(":personalogic"))`，**不传递**。而本模块的
    //    `PersonaPorts.kt` 要在公开签名里用到 `PersonaSpec` / `PersonaState`
    //    （`personaBriefOf(spec, state)` 的入参），所以必须自己再声明一次。
    //    少这一行的话 Gradle 报 "Cannot access 'PersonaSpec'..."，
    //    而那句报错完全没提"你该去上游模块加 api"（同 `:capability` 与
    //    `:filelogic` 那条注释的同一个坑）。
    //
    // ★ 这条边是 2026-10-12 加的，它同时是 `:personalogic` 的**第一个消费方**：
    //   在此之前 DL-1…DL-4 的 5000+ 行、1852 条测试没有任何模块引用，
    //   编译得过、测试全绿，但 `classes.dex` 里一个字节都没有。
    implementation(project(":personalogic"))          // 人格卡 / 状态机 / 装配器（助理是谁）
    // 记忆逻辑（DL 记忆线）：上下文卸载 / 分层记忆 / 记忆闸门。
    // **纯 Kotlin、零 Android 依赖**（在 PURE_KOTLIN 里）。
    //
    // ★ 与 `:personalogic` 同一个由来：它此前也**没有任何模块引用** ——
    //   而它扛着一条红线「卸载前必须过 `PrivacyFilter`」（架构 §8.2-2）。
    //   红线由 `OffloadPayload` 的私有构造函数保证，但代码从没进过包 ⇒ 从未执行过。
    implementation(project(":memorylogic"))           // 上下文卸载 / 记忆闸门（记住什么）
    implementation(project(":rootlogic"))             // 权限模式 / 能力梯度 / 意图判定
    implementation(project(":legacy"))                // 未亡人：亲人识别 / 生活线 / 哀伤分期
    implementation(project(":hosting"))               // 应用托管：freeform 小窗 + task resize
    // ⚠️ `:memorylogic` 用的是 `implementation(project(":agentlogic"))`，**不传递** ——
    //    而本模块的 `OffloadPorts.kt` 要构造 `UploadRequest`（`OffloadDraft.request`
    //    的类型），所以必须自己再声明一次。
    //    少这一行 Gradle 报 "Cannot access 'UploadRequest'..."，
    //    那句报错完全没提"你该去上游模块加 api"（同 `:capability` 与 `:filelogic` 的坑）。
    //    这条坑在 `:memorylogic` 的登记注释里被预言过一次，现在应验。
    implementation(project(":agentlogic"))            // PrivacyFilter / UploadRequest / AgentBudget
    // 卡片协议（W1）：五类卡 / 时间线状态机 / 失败卡出口 / 撤销范围 / `@` 索引 / 导出。
    // **纯 Kotlin、零 Android 依赖**（在 PURE_KOTLIN 里，有 280 条离线测试）。
    //
    // ⚠️ 为什么必须显式声明，而不是靠 `:agentlogic` 之类传过来：本模块的
    //    `ui/agent/` 要在公开签名里用到 `Card` / `Timeline` / `Exit`
    //    （`AgentCard(card: Card)`），所以必须自己声明。
    //    少这一行 Gradle 报 "Cannot access 'Card'..."（同 :personalogic 那条注释的同一个坑）。
    //
    // ★ 它是本项目**第一个把"看得见/停得下/说得清"画出来的模块**：
    //   在此之前 ":agentui" 的卡片只是数据结构，没有任何界面消费它们。
    implementation(project(":agentui"))
    // implementation(project(":provider:anthropic"))
    // implementation(project(":provider:gemini"))
    // implementation(project(":provider:local"))
    // 执行通道抽象（P3）：android_tap / android_swipe 的派发端适配器用它。
    // ⚠️ 启用它的时机 = 这两个工具的第一行接线代码（ActionPorts.kt）落地。
    implementation(project(":action"))                // ActionExecutor / ActionDispatcher / UiAction / ElementRef
    // implementation(project(":agent"))
    // implementation(project(":memory"))
    // implementation(project(":overlay"))

    // ── AndroidX ────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Compose ─────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── 网络与序列化 ────────────────────────────────────
    // 直接依赖 okhttp，而不是靠 :core:network 传递 ——
    // 那个模块用的是 implementation 而非 api，本来就传不过来
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // ── 存储 ────────────────────────────────────────────
    // 只用来存订阅源地址。API Key 不走这里，见 keymgmt 模块
    implementation(libs.androidx.datastore.preferences)

    // ── 其他 ────────────────────────────────────────────
    implementation(libs.timber)

    // ── 测试 ────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
