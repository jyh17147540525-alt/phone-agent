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
    # 悬浮球的交互逻辑（状态机 / 几何 / 急停语义）。
    # 与 :overlay 分开：后者要 WindowManager/Service，只能真机验证；
    # 而这里的东西没有任何 Android 依赖，能被离线验证器完整覆盖。
    "overlaylogic",
    # 模型网关的核心（路由 / 熔断 / 解密 / 计量）。
    # 零 Android 与零 Room 依赖是**硬约束** —— 落库走接口注入
    # （UsageRecorder 是接口，实现在 keymgmt）。一旦这里依赖 :
    # core:database，它就再也进不了离线验证器，而"预算熔断算错成本"
    # 恰好是那种不报错、只是静默烧钱的问题，必须有测试钉住。
    "provider/gateway",
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
    # 语音合成。与 provider/openai-compat 分开成独立模块，而不是塞进那个 ——
    # 后者是"对话协议"的实现，多塞一个 TTS 会让它的类注释（"一份代码覆盖
    # OpenAI Chat Completions 协议"）变成谎话，也让只想用对话的人
    # 被迫编译语音代码。
    "provider/tts-openai": [
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
    "provider/gemini", "provider/local", "provider/tts-openai", "perception", "action", "agent",
    "safety", "keymgmt", "memory", "overlay",
    "plugin/runtime", "plugin/rules", "plugin/script", "plugin/store",
    "plugin/devtools", "update", "onboarding",
    "display", "display/preview", "github", "contribute",
}

# 哪些模块需要序列化插件
NEEDS_SERIALIZATION = {
    "core/network", "provider/openai-compat", "provider/anthropic", "provider/gemini",
    "provider/local", "provider/tts-openai", "agent", "keymgmt", "memory",
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
    # TTS 只依赖接口，不依赖任何对话侧实现 —— 语音和对话是两条独立的线，
    # 让它们互相依赖会让"只想用语音"的场景被迫拖进 11 家对话厂商的代码。
    "provider/tts-openai": [":provider:api"],
    "agent": [":perception", ":action"],
    # keymgmt 是"加密存储 + 校验"的粘合层：
    #   :core:crypto    —— 主密钥与加解密
    #   :core:database  —— 密文落库（SQLCipher）
    #   :provider:api   —— 校验 Key 时只依赖接口，不依赖任何厂商实现
    #   :provider:gateway —— 网关的凭据解密入口（CredentialSource 实现）
    #                        与用量落库（UsageRecorder 实现）。
    #                        ⚠️ 方向是 keymgmt → gateway，**绝不能反过来** ——
    #                        网关一旦依赖 keymgmt 就拖进了 Room，而它必须
    #                        保持纯 Kotlin 才能进离线验证器。契约在 gateway、
    #                        实现在 keymgmt，这是本项目的既定模式。
    "keymgmt": [
        ":core:crypto",
        ":core:database",
        ":provider:api",
        ":provider:gateway",
        # 调度层模型（ModelConfig / ModelTier / ModelRouter）。
        # 顺序必须与 build 文件里出现的顺序一致 —— 生成器是**逐行比对**的，
        # 换序会报成"不一致"，而那看起来像缺依赖。
        ":modelrouter",
    ],
    # 悬浮球：Android 壳只做"显示与交互"，状态机/几何/急停语义在 :overlaylogic。
    # 拆开的理由见 PURE_KOTLIN 里的注释 —— 那些逻辑必须能离线测试。
    "overlay": [":overlaylogic"],
    # 网关核心：路由决策来自 :modelrouter，请求/响应契约来自 :provider:api。
    # ⚠️ 刻意**不依赖任何具体 Provider 实现**（openai-compat / anthropic / …）——
    #    具体 Provider 一律通过构造器注入 `List<LlmProvider>`。否则每新增一家厂商
    #    都要回来改网关的 build 文件与 when 分支，而漏改的表现是
    #    "这家厂商怎么都配不上"（不报错，只是永远路由不到）。
    "provider/gateway": [":provider:api", ":modelrouter"],
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

# 哪些模块在标准测试依赖之外还要额外的 testImplementation。
#
# ═══════════════════════════════════════════════════════════════
#  ⚠️ 为什么要有这张表，而不是"谁需要谁手写"
# ═══════════════════════════════════════════════════════════════
#
# 因为「谁手写」的结果就是**漂移**：`--check` 会把它报成"模块与生成器不一致"，
# 而那个告警的位置**不分语义** —— 一条真实的缺失（比如少了 room-compiler）
# 与一条无害的本地新增（比如多一个测试库）在输出里长得一模一样。
#
# 当无害条目长期占用告警位时，真实条目就会被当成同类忽略。**一个总是报警的
# 检查等于没有检查** —— 这与 [structural_lines] 的注释是同一个道理。
#
# 所以：**凡是"多个模块都需要的测试依赖"，就搬进生成器**，让 `--check` 归零。
# 只有真正一次性的、单模块调试用的东西才留在磁盘上（见下面 robolectric 那段）。
EXTRA_TEST_DEPS = {
    # ═══════════════════════════════════════════════════════════
    #  协程测试：`kotlinx-coroutines-test`
    # ═══════════════════════════════════════════════════════════
    #
    # `kotlinx-coroutines-test` 提供了 `runTest` / `TestScope`，而本项目的
    # 单元测试几乎全都依赖它们（要控制虚拟时间、要断言流量）。
    #
    # ⚠️ `kotlinx.coroutines.core` 里**没有** `runTest` —— 两者是不同的坐标。
    #    只写 core 的话测试源码里 `runTest` 直接编译不过（Unresolved
    #    reference），而报错指向测试文件，看起来像"少 import"，实际是少依赖。
    #
    # ⚠️ **这张清单的判据是"今天磁盘上真的有它"，不是"将来可能需要"。**
    #    第一版我按后者写，把 21 个模块全列进去了 —— `--check` 立刻从
    #    4 条漂移涨到 21 条，方向恰好反了：生成器开始**期待**一个磁盘上
    #    还不存在的依赖。生成器是"描述现状"的，不是"描述理想"的。
    #
    #    将来某模块真的写出第一个 `runTest` 时，把它加进这张表即可 ——
    #    那一步是显式的，而且 `--check` 会提示。
    **{
        m: ["kotlinx.coroutines.test"]
        for m in (
            # ── 已有 runTest 的模块（`grep -rl runTest`）──
            "modelrouter",
            "overlaylogic",
            "provider/gateway",
            # ── 手工补过这一行、且确实需要的模块 ──
            "core/common",
            "core/network",
            "safety",
            # 这两个是纯 Kotlin 契约模块，测试里也要跑协程断言
            "provider/api",
            "plugin/api",
        )
    },
    # ═══════════════════════════════════════════════════════════
    #  Room 迁移测试：只有声明了 @Database 的模块需要
    # ═══════════════════════════════════════════════════════════
    #
    # `MigrationTestHelper` 要读 schemas/*.json，而那只有 ROOM_MODULES
    # 里的模块才有（`exportSchema = true` + `room.schemaLocation`）。
    "core/database": [
        # Room 只在**运行期**校验迁移（"Migration didn't properly handle …"），
        # 编译期完全不管。没有这个依赖就只能靠人工核对 SQL，而人工核对
        # 漏掉一个 NOT NULL 的表现是：用户装上后崩在打开数据库那一步 ——
        # 他连界面都进不去，也就看不到任何提示。
        "androidx.room.testing",
        # `ApplicationProvider` 来自这里（MigrationTestHelper 需要 Context）
        "androidx.test.core",
    ],
}

# 哪些模块声明了 Room 的 @Database —— 需要给 Room 处理器指定 schema 导出目录。
#
# ⚠️ 光写 `exportSchema = true` 是**不够的**：那个开关只在给了
#    `room.schemaLocation` 之后才真正生效。否则 Room 只在构建日志里打一条
#    "Schema export directory is not provided" 的警告，然后什么都不写。
#    于是你以为自己有迁移依据，实际上没有 —— 直到某天要加字段才发现，
#    而那时已经无从知道旧表长什么样。
ROOM_MODULES = {"core/database", "memory"}

# 哪些模块在 `testOptions { unitTests.isIncludeAndroidResources = true }` 之后
# 还要额外的行（Robolectric 调试开关、测试 assets 路径等）。
#
# ═══════════════════════════════════════════════════════════════
#  ⚠️ 这张表本来是"已确认的本地定制"，最终决定收进生成器
# ═══════════════════════════════════════════════════════════════
#
# 之前的注释写着"core/database 里那一行属于已确认的本地定制，跑 --check
# 时看到它报这一条是预期行为，不必处理"。那个立场现在**推翻**，理由是：
#
# 「预期会报的告警」和「真出问题的告警」在输出里**长得一模一样**。
# --check 只说"语义第 N 行不一致"，不会标注"这条是已知的"。
# 于是每次跑检查都要人肉回忆"这 2 条是白名单里的" ——
# 而一旦形成"这个检查总有几条红的，正常"的习惯，
# 将来真漂移（比如少了 room-compiler）就会被一起划掉。
#
# **一个总有红条的检查等于没有检查。** 目标是把红条数压到 0，
# 让它变成"红了就是有事"。这与 [structural_lines] 的注释是同一个道理。
EXTRA_TEST_OPTIONS = {
    # Robolectric 的日志往 stdout 打。
    # 本质是 core/database 的**局部调试需要**（要读 Room 抛的
    # "Migration didn't properly handle" 细节）。
    # ⚠️ 只给 core/database —— 全局默认会让 30+ 个模块凭空多一行。
    "core/database": '        unitTests.all { it.systemProperty("robolectric.logging", "stdout") }',
}

# 哪些模块需要往测试 assets 里挂 schema 目录（MigrationTestHelper 要读它）。
#
# ⚠️ `room.schemaLocation` 只是让 Room 把 JSON **写**出去；
#    测试要**读**它，还得把它挂进 assets。
#    漏了的表现是运行时 `FileNotFoundException: Cannot find schema file` ——
#    而那句报错完全不提示"你该配 sourceSets"。
SCHEMA_ASSETS_MODULES = {"core/database"}

HEADER = """// ⚠️ 自动生成（gen_module_build_files.py）。如需长期定制，请移出生成列表。
"""


def kotlin_jvm(module: str, path: str) -> str:
    # ⚠️ PROJECT_DEPS 以前**没有被这个模板消费** —— 纯 Kotlin 模块从来不产出
    #    `implementation(project(":xxx"))`，而之前 PURE_KOTLIN 里的模块
    #    （provider/api、core/common、plugin/api、modelrouter、overlaylogic）
    #    碰巧都没有项目间依赖，所以这个缺口一直没暴露。
    #    `provider/gateway` 是第一个既要当纯 Kotlin 模块、又要依赖
    #    `:provider:api` 与 `:modelrouter` 的模块 —— 加它的时候才发现。
    #
    #    这里补上 PROJECT_DEPS 的消费。不改的话表现是：生成器说"一致"，
    #    而真实的 build 文件里一条项目依赖都没有，编译时才报一堆
    #    "Unresolved reference" —— 而报错地点在源码，不在构建文件，
    #    排查方向会跑偏到"是不是包名写错了"。
    project_lines = "".join(
        f'    implementation(project("{dep}"))\n'
        for dep in PROJECT_DEPS.get(module, [])
    )
    # ★ 有项目依赖时，`dependencies {` 之后是"依赖行 + 一个空行"，再跟库依赖。
    #
    # ⚠️ 这块**连着踩了三次**，三次表象都一样 —— 检查器说格式不对，
    #    而磁盘上人写的那份才是对的。`--check` 是逐行比对，
    #    差一个空行报出来的也是两行看起来都无害的内容，
    #    很容易被读成"这个模块被人改坏了" —— 而实际信息是反的。
    #
    #    逐个排除，留档免得再猜：
    #      ① `f"{project_lines}"`    → 项目依赖与库依赖之间没有空行
    #      ② `f"{project_lines}\n"`  → `dependencies {` 与第一条依赖被挤到同一行
    #      ③ `f"\n{project_lines}"`  → 正确
    #
    #    关键细节：上面 `project_lines` 的每一行**自带尾 \n**，
    #    所以它末尾已有一个换行，不该也不能再补；而模板里
    #    `dependencies {` 后面**没有**换行，必须靠这里的前导 \n 补上。
    #
    #    **改完必须回跑 `--check` 确认归零** —— 目测这个差看不出来，
    #    我第三次才靠 `diff` 的 repr 输出定住。
    project_block = f"\n{project_lines}" if project_lines else ""

    # 模块级额外测试依赖（见 EXTRA_TEST_DEPS 的注释）。
    # ⚠️ 顺序必须与磁盘上的 build 文件**逐行一致** —— `--check` 是逐行比对的，
    #    换序会报成"不一致"，而那个表象看起来像缺依赖。
    extra_test_lines = "".join(
        f"    testImplementation(libs.{d})\n"
        for d in EXTRA_TEST_DEPS.get(module, [])
    )

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

dependencies {{{project_block}
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
{extra_test_lines}}}
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

    # Robolectric 的日志要不要往 stdout 打。
    #
    # ⚠️ 结论：**不要把它做进生成器。**
    #
    #    试过两种做法，都不划算：
    #      · 全局默认   → `--check` 报全部 31 个模块不一致，而生成器"只创建
    #                     不覆盖"的设计会让人误以为要重写 31 个文件
    #      · 仅 Room 模块 → `memory` 也是 ROOM_MODULES 但没这行，仍不一致
    #
    #    它本质是 core/database 的**局部调试需要**（要读 Room 抛的
    #    "Migration didn't properly handle" 细节）。收益是一个日志开关，
    #    代价是让 31 个模块的构建文件长期处于"与生成器不一致"的状态 ——
    #    而这个告警本身是有价值的，不该被一条已知噪声长期占用。
    #
    #    ⇒ core/database/build.gradle.kts 里的那一行属于**已确认的本地定制**，
    #      与 room.schemaLocation 同类。跑 `--check` 时看到它报这一条，
    #      是预期行为，不必处理。

    # Robolectric / 测试相关的额外配置（见 EXTRA_TEST_OPTIONS 的注释）。
    # ⚠️ 缩进是 8 空格，因为它落在 `testOptions {` 内部。
    extra_test_option_lines = "".join(
        f"\n{v}"
        for k, v in EXTRA_TEST_OPTIONS.items()
        if k == module
    )

    # 测试 assets 里挂 schema 目录（见 SCHEMA_ASSETS_MODULES 的注释）。
    # ⚠️ 它必须落在 `android { }` 内部、且**在 testOptions 之后** ——
    #    与磁盘上的 core/database/build.gradle.kts 逐行一致。
    schema_assets_block = ""
    if module in SCHEMA_ASSETS_MODULES:
        schema_assets_block = (
            "\n"
            "    // ⚠️ **这一块是 MigrationTestHelper 能工作的前提。**\n"
            "    //    `room.schemaLocation` 只是让 Room 把 JSON **写**出去；\n"
            "    //    测试要**读**它，还得把它挂进测试的 assets 路径。\n"
            "    //    漏了这块的表现是运行时 `FileNotFoundException: Cannot find schema file` ——\n"
            "    //    而那句报错完全不提示\"你该配 sourceSets\"。\n"
            "    sourceSets {\n"
            '        getByName("test").assets.srcDirs("${projectDir}/schemas")\n'
            '        getByName("androidTest").assets.srcDirs("${projectDir}/schemas")\n'
            "    }\n"
        )

    # 模块级额外测试依赖（见 EXTRA_TEST_DEPS 的注释）。
    # ⚠️ 放在 `testImplementation(libs.kotlinx.coroutines.core)` **之后** ——
    #    与磁盘上的 build 文件保持一致，否则逐行比对会报"不一致"。
    #
    # ⚠️ `androidx.room.testing` / `androidx.test.core` 也要走这里，
    #    不能像以前那样只留在 core/database 的磁盘文件里 ——
    #    那正是 `--check` 长期报漂移的来源之一。
    extra_test_lines = "".join(
        f"    testImplementation(libs.{d})\n"
        for d in EXTRA_TEST_DEPS.get(module, [])
    )

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
        unitTests.isIncludeAndroidResources = true{extra_test_option_lines}
    }}
{schema_assets_block}}}

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
{extra_test_lines}}}
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
