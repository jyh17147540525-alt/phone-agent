// 根构建脚本：只声明插件，不在此处配置具体模块
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false

    // ⚠️ kotlin.jvm 必须在这里也声明一次（apply false 即可），哪怕根项目自己不用它。
    //
    //    原因：:plugin:api / :core:* 这些纯 Kotlin 模块要请求 `org.jetbrains.kotlin.jvm`。
    //    而 kotlin-android / compose / serialization 三个插件会把
    //    kotlin-gradle-plugin 顺带放进构建的 classpath —— 于是子模块再带版本号去请求时，
    //    Gradle 会说"该插件已在 classpath 上但版本未知，无法校验兼容性"并直接失败。
    //
    //    报错信息完全指不到"根脚本漏了一行"，排查成本很高，所以留这段注释。
    //    规则：**子模块会用到的每个插件，根脚本都要 apply false 声明一遍。**
    alias(libs.plugins.kotlin.jvm) apply false
}

// 统一的编译选项，供各模块继承
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}
