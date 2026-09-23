// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 纯 Kotlin 模块：无 Android 依赖，单元测试可在毫秒级完成
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ⚠️ 本模块**刻意不依赖 :core:common**。
    //
    //    写文件要用的 `AtomicTextFile` 在那边，但那是**通道实现**（Android 侧）的事，
    //    不是判定层的事 —— 本模块只组装文本，不碰文件系统。
    //    加上这条依赖会让模块图里多出一条"声明了但没用到"的边，
    //    而 `check_module_deps.py` 会如实报出来。
    //
    //    依赖方向是单向的：将来实现通道的模块同时依赖 :filelogic 与 :core:common。

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
