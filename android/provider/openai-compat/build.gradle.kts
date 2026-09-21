// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ⚠️ 目录名是 openai-compat，但连字符不是合法的 Java 标识符，
    //    所以 namespace 里的这一段是 openaicompat（去掉连字符，不是换成下划线）——
    //    必须与源码包名 com.pocketagent.provider.openaicompat 一致。
    //    生成器已同步修正，见 gen_module_build_files.py 的 namespace_for()。
    namespace = "com.pocketagent.provider.openaicompat"
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
    // ⚠️ 补上来的。本模块从 com.pocketagent.provider.api 导入了 13 个符号
    //    （LlmProvider / ChatRequest / KeyValidationResult …），却没声明这个依赖。
    //
    //    藏了很久的原因：tools/verify/run_logic_tests.py 把 provider/api 与
    //    本模块塞进**同一次 kotlinc 调用**，跨模块引用就顺便解析成功了 ——
    //    漏声明的依赖在那个跑法下根本看不出来。
    //    只有 Gradle（每个模块独立编译）才会暴露。
    //
    //    这类问题的常驻防线是 tools/verify/check_module_deps.py。
    implementation(project(":provider:api"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
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
