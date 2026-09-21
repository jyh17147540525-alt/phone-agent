# -*- coding: utf-8 -*-
"""
为 PocketAgent 各模块生成 build.gradle.kts 样板。

用法：python gen_module_build_files.py

═══════════════════════════════════════════════════════════════
  ⚠️ 这是「脚手架」，不是「生成器」
═══════════════════════════════════════════════════════════════

已存在的文件**一律跳过**，不会被覆盖。

后果必须说清楚：**改这个脚本不会更新任何已有的 build.gradle.kts。**
它只影响将来新增的模块。所以当你在这里修了一个配置缺陷
（比如补上 `PROJECT_DEPS`），必须**同时手动改对应的那个文件** ——
否则脚本显示"已是最新"，而实际的构建文件依然是错的。

同理，反过来说也成立：既然它不覆盖，那么各个 build.gradle.kts
上已经做过的定制是安全的，不会被这个脚本冲掉。
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

# 模块之间的项目依赖。
#
# ⚠️ 这张表是补上来的，因为生成器原本**只会硬编码一条 `:core:common`**，
#    没有任何表达"模块 A 要用模块 B 的类型"的机制。
#
#    后果不是"少个依赖"这么简单：`:agent` 引用了 `:action` 的 `ElementRef`
#    与 `:perception` 的类型，但两者都没声明，于是 `:agent` 编译不过。
#    而这个错误**藏了很久** —— 因为 `tools/verify/run_logic_tests.py`
#    只编译零 Android 依赖的那几个模块（provider / core / safety / plugin:api），
#    `:agent` 和 `:action` 从来不在里面。
#
#    教训：**改完代码光跑离线单测是不够的**。它覆盖的是纯逻辑模块，
#    而 Android 模块的编译错误只有 `./gradlew test` 才会暴露。
PROJECT_DEPS = {
    "provider/openai-compat": [":provider:api"],
    "agent": [":perception", ":action"],
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


def namespace_for(module: str) -> str:
    """
    从模块路径推导 Android namespace。

    ⚠️ 目录名里的连字符必须去掉。`provider/openai-compat` 如果只把斜杠换成点，
       得到的是 `com.pocketagent.provider.openai-compat` —— 而 `openai-compat`
       不是合法的 Java 标识符。AGP 会在**配置阶段**就报错，
       连编译都进不去，而且报错指向的是模块构建文件而不是这里的生成逻辑。

    注意是「去掉」而不是「换成下划线」：源码里的包名就是 `openaicompat`
    （见 provider/openai-compat/src/main/kotlin/...），namespace 必须与之一致，
    否则 R 类与 BuildConfig 会生成到另一个包下，引用起来全是找不到符号。
    """
    return "com.pocketagent." + module.replace("/", ".").replace("-", "")


def android_lib(module: str, deps: list[str]) -> str:
    ns = namespace_for(module)
    dep_lines = "\n".join(f"    implementation(libs.{d})" for d in deps
                          if d not in ("androidx.room.compiler",))
    # 跨模块依赖。`:core:common` 是全体共用的，单独一条写在模板里；
    # 这里排掉它，免得重复。
    project_lines = "\n".join(
        f'    implementation(project("{p}"))'
        for p in PROJECT_DEPS.get(module, [])
        if p != ":core:common"
    )
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
        // 此处原本声明 consumerProguardFiles("consumer-rules.pro")，
        // 但那个文件从未创建，AGP 找不到会直接构建失败。
        // consumer 规则只在模块真的对外发布混淆契约时才需要，届时连同文件一起加。
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
{project_lines}
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
