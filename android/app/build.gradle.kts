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
        versionCode = 1
        versionName = "0.1.0-m0"

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
    // 理由和 Manifest 收窄权限是同一条：现在挂上 :perception / :agent /
    // :keymgmt，会连带把 ML Kit、SQLCipher、Shizuku 这些重依赖拖进构建，
    // 而这个阶段的代码一行都没用到它们。构建时间和包体白涨，
    // 更糟的是**让人误以为这些能力已经可用**。
    //
    // 逐项启用的时机 = 对应模块写出第一行代码的时候。
    implementation(project(":plugin:api"))   // 插件契约、校验、市场索引、完整性校验

    // 以下随对应模块落地启用：
    // implementation(project(":core:common"))
    // implementation(project(":core:crypto"))
    // implementation(project(":core:database"))
    // implementation(project(":core:network"))
    // implementation(project(":provider:api"))
    // implementation(project(":provider:openai-compat"))
    // implementation(project(":provider:anthropic"))
    // implementation(project(":provider:gemini"))
    // implementation(project(":provider:local"))
    // implementation(project(":perception"))
    // implementation(project(":action"))
    // implementation(project(":agent"))
    // implementation(project(":safety"))
    // implementation(project(":keymgmt"))
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
