// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pocketagent.keymgmt"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
    implementation(project(":provider:api"))
    // 网关的契约（CredentialSource / UsageRecorder）在这里，实现也在本模块。
    // ⚠️ 方向不能反过来：网关一旦依赖 keymgmt 就拖进了 Room 与 Keystore，
    //    而它必须保持纯 Kotlin 才能进 run_logic_tests.py 离线验证。
    implementation(project(":provider:gateway"))
    // 调度层模型（ModelConfig / ModelTier / ModelRouter）。
    // 它是纯 Kotlin 模块 —— 依赖它不会把 android.* 带进 keymgmt 之外的地方。
    implementation(project(":modelrouter"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
}
