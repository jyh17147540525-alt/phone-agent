// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pocketagent.agent"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // ⚠️ 这两条是补上来的，缺了它们 :agent 直接编译不过 ——
    //    AgentOrchestrator 里用到了 com.pocketagent.action.ElementRef
    //    与 com.pocketagent.perception 的类型（GroundResult 等）。
    //
    //    这个错误藏了很久，因为 tools/verify/run_logic_tests.py 只编译
    //    零 Android 依赖的模块，:agent 从来不在里面。
    //    生成器那边的 PROJECT_DEPS 也已同步补上，新模块不会重蹈覆辙。
    implementation(project(":perception"))
    implementation(project(":action"))
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
