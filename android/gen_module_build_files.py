# -*- coding: utf-8 -*-
"""
为 PocketAgent 各模块生成 build.gradle.kts 样板。

用法：python gen_module_build_files.py
幂等：已存在的文件不会被覆盖。
"""
import pathlib

ROOT = pathlib.Path(__file__).parent

# 纯 Kotlin JVM 库（无 Android 依赖，可跑最快单元测试）
PURE_KOTLIN = {
    "provider/api",
    "core/common",
    # 插件契约必须零 Android 依赖 —— 插件作者要能在纯 JVM 环境开发与测试
    "plugin/api",
}

# Android Library 模块 → 该模块需要额外依赖的库别名
ANDROID_LIB = {
    "core/crypto": ["androidx.core.ktx"],
    "core/database": [
        "androidx.room.runtime", "androidx.room.ktx", "androidx.room.compiler",
        "androidx.datastore.preferences", "sqlcipher.android", "androidx.sqlite.ktx",
    ],
    "core/network": [
        "okhttp", "okhttp.logging", "retrofit",
        "kotlinx.serialization.json", "kotlinx.coroutines.android", "timber",
    ],
    "provider/openai-compat": [
        "okhttp", "kotlinx.serialization.json", "kotlinx.coroutines.android", "timber",
    ],
    "provider/anthropic": [
        "okhttp", "kotlinx.serialization.json", "kotlinx.coroutines.android", "timber",
    ],
    "provider/gemini": [
        "okhttp", "kotlinx.serialization.json", "kotlinx.coroutines.android", "timber",
    ],
    "provider/local": [
        "okhttp", "kotlinx.serialization.json", "kotlinx.coroutines.android", "timber",
    ],
    "perception": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "mlkit.text.recognition.zh", "mlkit.text.recognition.en", "timber",
    ],
    "action": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "shizuku.api", "shizuku.provider", "timber",
    ],
    "agent": [
        "kotlinx.coroutines.android", "kotlinx.serialization.json", "timber",
    ],
    "safety": ["androidx.core.ktx", "kotlinx.coroutines.android", "timber"],
    "keymgmt": [
        "androidx.datastore.preferences", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "timber",
    ],
    "memory": [
        "androidx.room.runtime", "androidx.room.ktx", "androidx.room.compiler",
        "kotlinx.coroutines.android", "timber",
    ],
    "overlay": [
        "androidx.core.ktx", "androidx.lifecycle.service",
        "kotlinx.coroutines.android", "timber",
    ],
    # ── 插件体系（v2.0）──────────────────────────────────
    "plugin/runtime": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "timber",
    ],
    "plugin/rules": [
        "kotlinx.coroutines.android", "kotlinx.serialization.json", "timber",
    ],
    "plugin/script": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "quickjs.android", "timber",
    ],
    "plugin/store": [
        "androidx.core.ktx", "androidx.lifecycle.viewmodel",
        "kotlinx.coroutines.android", "kotlinx.serialization.json",
        "androidx.navigation.compose", "timber",
    ],
    "plugin/devtools": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "timber",
    ],
    # ── 分发与引导（v2.0）────────────────────────────────
    "update": [
        "androidx.core.ktx", "androidx.work.runtime",
        "kotlinx.coroutines.android", "kotlinx.serialization.json",
        "okhttp", "timber",
    ],
    "channel": ["androidx.core.ktx", "timber"],
    "onboarding": [
        "androidx.core.ktx", "androidx.lifecycle.viewmodel",
        "kotlinx.coroutines.android", "androidx.navigation.compose",
        "accompanist.permissions", "timber",
    ],
    # ── 虚拟屏（v3.0）────────────────────────────────────
    # 注意：:display 依赖 shizuku，不依赖无障碍 —— 无障碍无法操作虚拟屏
    "display": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "shizuku.api", "shizuku.provider", "timber",
    ],
    "display/preview": [
        "androidx.core.ktx", "kotlinx.coroutines.android", "timber",
    ],
    # ── 开源协作（v3.0）──────────────────────────────────
    "github": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "okhttp", "timber",
    ],
    "contribute": [
        "androidx.core.ktx", "kotlinx.coroutines.android",
        "kotlinx.serialization.json", "timber",
    ],
}

# 哪些模块需要 Hilt（注入依赖的模块）
NEEDS_HILT = {
    "core/database", "core/network", "provider/openai-compat", "provider/anthropic",
    "provider/gemini", "provider/local", "perception", "action", "agent",
    "safety", "keymgmt", "memory", "overlay",
    "plugin/runtime", "plugin/rules", "plugin/script", "plugin/store",
    "plugin/devtools", "update", "onboarding",
    "display", "display/preview", "github", "contribute",
}

# 哪些模块需要序列化插件
NEEDS_SERIALIZATION = {
    "core/network", "provider/openai-compat", "provider/anthropic", "provider/gemini",
    "provider/local", "agent", "keymgmt", "memory",
    "plugin/api", "plugin/runtime", "plugin/rules", "plugin/script",
    "plugin/store", "plugin/devtools", "update",
    "github", "contribute",
}

HEADER = """// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
"""


def kotlin_jvm(module: str, path: str) -> str:
    return f"""{HEADER}plugins {{
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}}

// 纯 Kotlin 模块：无 Android 依赖，单元测试可在毫秒级完成
java {{
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}}

kotlin {{
    compilerOptions {{
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }}
}}

dependencies {{
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
}}
"""


def android_lib(module: str, deps: list[str]) -> str:
    ns = "com.pocketagent." + module.replace("/", ".")
    dep_lines = "\n".join(f"    implementation(libs.{d})" for d in deps
                          if d not in ("androidx.room.compiler",))
    ksp_lines = ""
    if "androidx.room.compiler" in deps:
        ksp_lines = "    ksp(libs.androidx.room.compiler)\n"

    hilt_impl = hilt_ksp = ""
    if module in NEEDS_HILT:
        hilt_impl = "    implementation(libs.hilt.android)\n"
        hilt_ksp = "    ksp(libs.hilt.compiler)\n"

    plugin_lines = [
        "    alias(libs.plugins.android.library)",
        "    alias(libs.plugins.kotlin.android)",
        "    alias(libs.plugins.ksp)",
    ]
    if module in NEEDS_HILT:
        plugin_lines.append("    alias(libs.plugins.hilt)")
    if module in NEEDS_SERIALIZATION:
        plugin_lines.append("    alias(libs.plugins.kotlin.serialization)")
    plugins = "\n".join(plugin_lines)

    return f"""{HEADER}plugins {{
{plugins}
}}

android {{
    namespace = "{ns}"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {{
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }}

    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}

    kotlin {{
        compilerOptions {{
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }}
    }}

    testOptions {{
        unitTests.isIncludeAndroidResources = true
    }}
}}

dependencies {{
    implementation(project(":core:common"))
{dep_lines}
{hilt_impl}{hilt_ksp}
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
}}
"""


def main() -> None:
    created, skipped = [], []

    for module in sorted(PURE_KOTLIN):
        target = ROOT / module / "build.gradle.kts"
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            skipped.append(str(target))
            continue
        target.write_text(kotlin_jvm(module, module), encoding="utf-8")
        created.append(str(target))

    for module, deps in sorted(ANDROID_LIB.items()):
        target = ROOT / module / "build.gradle.kts"
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            skipped.append(str(target))
            continue
        target.write_text(android_lib(module, deps), encoding="utf-8")
        created.append(str(target))

        # consumer-rules.pro 占位
        pro = ROOT / module / "consumer-rules.pro"
        if not pro.exists():
            pro.write_text(
                "# 本模块对外暴露的 ProGuard 规则\n"
                "# 默认不保留任何内容；如需保留公开 API，在此声明。\n",
                encoding="utf-8",
            )

    print(f"created: {len(created)}")
    for p in created:
        print("  +", p)
    print(f"skipped (already exists): {len(skipped)}")


if __name__ == "__main__":
    main()
