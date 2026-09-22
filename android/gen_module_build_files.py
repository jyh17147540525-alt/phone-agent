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
import sys

ROOT = pathlib.Path(__file__).parent

# 纯 Kotlin JVM 库（无 Android 依赖，可跑最快单元测试）
PURE_KOTLIN = {
    "provider/api",
    "core/common",
    # 插件契约必须零 Android 依赖 —— 插件作者要能在纯 JVM 环境开发与测试
    "plugin/api",
    # 调度层是纯决策逻辑：不联网、不碰 Key、不读写数据库。
    # 零 Android 依赖让它能被 run_logic_tests.py 完整覆盖 ——
    # 而路由选错模型是"不报错、只是静默走贵了"的那类 bug，必须有测试钉住。
    "modelrouter",
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
    # 数据库需要 Keystore 提供 SQLCipher 口令（口令本身也用主密钥加密后落盘）
    "core/database": [":core:crypto"],
    "provider/openai-compat": [":provider:api"],
    "agent": [":perception", ":action"],
    # keymgmt 是"加密存储 + 校验"的粘合层：
    #   :core:crypto    —— 主密钥与加解密
    #   :core:database  —— 密文落库（SQLCipher）
    #   :provider:api   —— 校验 Key 时只依赖接口，不依赖任何厂商实现
    "keymgmt": [":core:crypto", ":core:database", ":provider:api"],
}

# 哪些模块的**公开 API 暴露了某个库的类型** —— 这些必须是 `api` 而不是 `implementation`。
#
# ⚠️ 判据是"这个类型有没有出现在本模块的公开签名里"，不是"用得多不多"。
#
#    `core:database` 的 `PocketAgentDatabase` **继承** `RoomDatabase`，
#    而且 `withTransaction` 来自 room-ktx。两者都在它的契约里 ——
#    用 implementation 声明的后果是：每个使用方都撞上
#       "Cannot access 'RoomDatabase' which is a supertype of 'PocketAgentDatabase'.
#        Check your module classpath for missing or conflicting dependencies."
#    这句报错**完全没提"你该去上游模块加 api"**，只会让人在自己模块里
#    反复加依赖试。
#
#    更糟的是它无法靠"多试几次"根治：以后每个碰数据库的新模块都要再踩一遍。
API_DEPS = {
    "core/database": {"androidx.room.runtime", "androidx.room.ktx"},
}

# 哪些模块声明了 Room 的 @Database —— 需要给 Room 处理器指定 schema 导出目录。
#
# ⚠️ 光写 `exportSchema = true` 是**不够的**：那个开关只在给了
#    `room.schemaLocation` 之后才真正生效。否则 Room 只在构建日志里打一条
#    "Schema export directory is not provided" 的警告，然后什么都不写。
#    于是你以为自己有迁移依据，实际上没有 —— 直到某天要加字段才发现，
#    而那时已经无从知道旧表长什么样。
ROOM_MODULES = {"core/database", "memory"}

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
    api_deps = API_DEPS.get(module, set())
    dep_lines = "\n".join(
        f"    {'api' if d in api_deps else 'implementation'}(libs.{d})"
        for d in deps
        if d not in ("androidx.room.compiler",)
    )
    # 跨模块依赖。`:core:common` 是全体共用的，单独一条写在模板里；
    # 这里排掉它，免得重复。
    project_lines = "\n".join(
        f'    implementation(project("{p}"))'
        for p in PROJECT_DEPS.get(module, [])
        if p != ":core:common"
    )
    # ⚠️ 这段历史值得记一笔：ksp_lines 曾经被**计算出来却从未写进模板** ——
    #    是个死变量。后果是生成器从第一天起就没输出过 room-compiler，
    #    而 @Database / @Dao 全是 abstract，**没有处理器也能编译通过**，
    #    错误一路推到运行时的 "Cannot find implementation for PocketAgentDatabase"。
    #
    #    改这里的任何东西之后，务必跑一次 `--check`：它就是为了让这类
    #    "生成器与磁盘不一致"的问题可见才加的。
    ksp_lines = ""
    if "androidx.room.compiler" in deps:
        ksp_lines = "    ksp(libs.androidx.room.compiler)\n"

    hilt_impl = hilt_ksp = ""
    if module in NEEDS_HILT:
        hilt_impl = "    implementation(libs.hilt.android)\n"
        hilt_ksp = "    ksp(libs.hilt.compiler)\n"

    # 单独拼而不是写进 f-string 模板：模板里的 ${projectDir} 会被 Python
    # 当成占位符展开，得写成 ${{projectDir}}，可读性太差。
    room_ksp_block = ""
    if module in ROOM_MODULES:
        room_ksp_block = (
            "// Room 的 schema 导出目录。声明 exportSchema = true 之后必须给这个路径，\n"
            "// 否则那个开关不生效，Room 只会打一条警告然后什么都不写。\n"
            "ksp {\n"
            '    arg("room.schemaLocation", "${projectDir}/schemas")\n'
            "}\n\n"
        )

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
        // 本脚本会在同目录创建 consumer-rules.pro 占位文件（见 main()），
        // 所以这里可以安全地声明它。
        //
        // ⚠️ 这里曾经写着"那个文件从未创建"并**省略了这条声明** —— 那是脚本
        //    早期版本的事实。后来脚本开始创建该文件，注释与模板却没跟着改，
        //    于是生成器与磁盘上的 26 个模块长期不一致，而"只创建不覆盖"的
        //    行为让这个不一致永远不会被自动修正。跑 --check 就能看到。
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

{room_ksp_block}dependencies {{
    implementation(project(":core:common"))
{project_lines}
{dep_lines}
{ksp_lines}{hilt_impl}{hilt_ksp}
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
}}
"""


def structural_lines(text: str) -> list[str]:
    """
    只保留有语义的行：去掉空行与整行注释。

    ⚠️ **为什么不比全文。**

    各模块的 build 文件里写了不少历史解释，比如 core:database 里那句
    "这一行曾经缺失，缺了它编译照样通过" —— 那些是给人看的，生成器
    既不可能也不需要复现。

    比全文的结果是 26 个模块**全部**报"不一致"（全都只差注释），
    而真正的信号 —— 少了一行 `ksp(libs.androidx.room.compiler)` ——
    被淹没在噪音里。**那等于没有检查**，而且比没有更糟：一个永远在报警的
    检查会被所有人忽略。

    行尾注释（`x = 1 // 说明`）保留，因为它可能藏着真实差异。
    """
    kept = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue
        kept.append(stripped)
    return kept


def first_difference(expected: str, actual: str) -> str:
    """
    找出第一处**语义**不同的行，用于 --check 时给出可操作的提示。

    只报"文件不一致"是没用的 —— 26 个模块里哪一行不对，得靠人一行行比。
    """
    expected_lines = structural_lines(expected)
    actual_lines = structural_lines(actual)

    for index in range(max(len(expected_lines), len(actual_lines))):
        want = expected_lines[index] if index < len(expected_lines) else "<文件到此结束>"
        got = actual_lines[index] if index < len(actual_lines) else "<文件到此结束>"
        if want != got:
            return f"语义第 {index + 1} 行\n      期望: {want}\n      实际: {got}"

    return "（语义一致，仅注释/空行差异）"


def main() -> None:
    """
    ⚠️ 默认模式**只创建缺失文件，绝不覆盖已存在的文件**。
    这是刻意的：模块的 build 文件允许本地定制（比如 core:database 手写了
    `ksp { arg("room.schemaLocation", ...) }`），覆盖会把它抹掉。

    但"只创建不更新"有一个恶性副作用：**生成器的修复永远传播不到已有文件。**
    本文件曾经把 `ksp(libs.androidx.room.compiler)` 生成对了，而 core:database
    磁盘上那份是旧版产物、缺这一行 —— 于是 Room 处理器根本没上 KSP 类路径。
    因为 @Database/@Dao 全是 abstract，**编译照样通过**，一路把错误推到
    运行时的 "Cannot find implementation for PocketAgentDatabase"。

    所以补了 `--check`：不写任何文件，只报告"现有文件与生成器预期是否一致"。
    改完生成器后跑一次，就知道哪些模块落后了。
    """
    check_only = "--check" in sys.argv[1:]

    created, skipped, drifted = [], [], []

    def handle(target: "pathlib.Path", expected: str) -> None:
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            skipped.append(str(target))
            if check_only:
                actual = target.read_text(encoding="utf-8")
                if structural_lines(actual) != structural_lines(expected):
                    drifted.append((str(target), first_difference(expected, actual)))
            return
        if check_only:
            # --check 不改磁盘，但"文件不存在"也是一种漂移
            drifted.append((str(target), "文件不存在"))
            return
        target.write_text(expected, encoding="utf-8")
        created.append(str(target))

    for module in sorted(PURE_KOTLIN):
        handle(ROOT / module / "build.gradle.kts", kotlin_jvm(module, module))

    for module, deps in sorted(ANDROID_LIB.items()):
        handle(ROOT / module / "build.gradle.kts", android_lib(module, deps))

        if check_only:
            continue
        # consumer-rules.pro 占位
        pro = ROOT / module / "consumer-rules.pro"
        if not pro.exists():
            pro.write_text(
                "# 本模块对外暴露的 ProGuard 规则\n"
                "# 默认不保留任何内容；如需保留公开 API，在此声明。\n",
                encoding="utf-8",
            )

    if check_only:
        if drifted:
            print(f"发现 {len(drifted)} 个模块的 build 文件与生成器预期不一致：")
            for path, detail in drifted:
                print(f"  ! {path}")
                print(f"      {detail}")
            print(
                "\n处置：确认生成器是对的 → 删掉该文件后重跑本脚本；"
                "\n      确认本地定制是对的 → 把它移出 ANDROID_LIB / PURE_KOTLIN，"
                "或把定制同步回生成器。"
            )
            raise SystemExit(1)
        print(f"全部 {len(skipped)} 个模块的 build 文件与生成器预期一致。")
        return

    print(f"created: {len(created)}")
    for p in created:
        print("  +", p)
    print(f"skipped (already exists): {len(skipped)}")
    print("\n提示：本模式不覆盖已有文件。要检查现有文件是否落后，跑 --check。")


if __name__ == "__main__":
    main()
