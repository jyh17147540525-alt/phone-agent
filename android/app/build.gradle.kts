plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
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

dependencies {
    // ── 本项目模块 ──────────────────────────────────────
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":provider:api"))
    implementation(project(":provider:openai-compat"))
    implementation(project(":provider:anthropic"))
    implementation(project(":provider:gemini"))
    implementation(project(":provider:local"))
    implementation(project(":perception"))
    implementation(project(":action"))
    implementation(project(":agent"))
    implementation(project(":safety"))
    implementation(project(":keymgmt"))
    implementation(project(":memory"))
    implementation(project(":overlay"))

    // ── AndroidX ────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)

    // ── Compose ─────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ── DI ──────────────────────────────────────────────
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── 其他 ────────────────────────────────────────────
    implementation(libs.timber)
    implementation(libs.accompanist.permissions)

    // ── 测试 ────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}
