package com.pocketagent.provider.gateway.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DshConfigPatch] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这些测试**不会**让任何一个在真机上"崩掉"的错误变得可见 —— 它们防的是
 * 这类**静默失效**：
 *
 * | 写错了什么 | 用户看到的现象 | 会去查的方向 |
 * |---|---|---|
 * | 段名不是 `llm-deepseek` | 配置写进去了，baseURL 不生效 | dsh 是不是不读我们的文件 |
 * | `apiKeyEnv` 名字与 refs 键不一致 | 每请求报凭据缺失 | 是不是 token 过期了 |
 * | token 没加引号且含 `#` | 网关回 401 | 是不是网关没启动 |
 * | 整文件重写 settings | 用户手写的注释没了 | "我的配置怎么没了" |
 * | 整文件重写 credentials | **用户的真 Key 被删** | "我的 DeepSeek 怎么不能用了" |
 *
 * 最后两条是**不可逆的数据损失**，所以本文件里关于"合并"的测试最重。
 *
 * ⚠️ 本模块零 Android 依赖，进 `tools/verify/run_logic_tests.py` 离线通道。
 */
class DshConfigPatchTest {

    private val patch = DshConfigPatch(
        baseUrl = "http://127.0.0.1:45678/v1",
        token = "abc123_XYZ-token",
    )

    // ─────────────────────────────────────────────────────────────
    //  构造校验
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `默认虚拟模型名与网关一致`() {
        // ⚠️ 这两处字面量分居两个类 —— 现在 HttpGatewayServer.VIRTUAL_MODEL
        //    是别名指向这里，所以编译器已钉住。本测试是**第二道**：
        //    万一有人把那边改回字面量，这条会红。
        assertEquals("pocketagent-auto", DshConfigPatch.VIRTUAL_MODEL_ID)
        assertEquals(DshConfigPatch.VIRTUAL_MODEL_ID, patch.advertisedModelIds.single())
    }

    @Test
    fun `空 token 被拒`() {
        val e = runCatching {
            DshConfigPatch(baseUrl = "http://127.0.0.1:1/v1", token = "")
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        // 报错要说清后果，不只是"参数非法"
        assertTrue(e!!.message!!.contains("401"))
    }

    @Test
    fun `空 baseUrl 被拒`() {
        val e = runCatching {
            DshConfigPatch(baseUrl = "  ", token = "t")
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun `非 POSIX 环境变量名的凭据引用被拒`() {
        // ⚠️ 连字符是最容易顺手写的一个（`pocketagent-token`），
        //    而它在多数 shell 里不是合法变量名 —— 失败表现是"凭据查不到"。
        val e = runCatching {
            DshConfigPatch(
                baseUrl = "http://127.0.0.1:1/v1",
                token = "t",
                credentialRef = "pocketagent-token",
            )
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("POSIX"))
    }

    @Test
    fun `下划线开头的凭据引用被接受`() {
        val p = DshConfigPatch(
            baseUrl = "http://127.0.0.1:1/v1",
            token = "t",
            credentialRef = "_INTERNAL_TOKEN",
        )
        assertEquals("_INTERNAL_TOKEN", p.credentialRef)
    }

    @Test
    fun `空模型清单被拒`() {
        val e = runCatching {
            DshConfigPatch(
                baseUrl = "http://127.0.0.1:1/v1",
                token = "t",
                advertisedModelIds = emptyList(),
            )
        }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        // 报错必须说清"空清单会让 dsh 回落内置真实模型名"
        assertTrue(e!!.message!!.contains("回落"))
    }

    // ─────────────────────────────────────────────────────────────
    //  渲染
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `settings 段的段名逐字是 llm-deepseek`() {
        // ⚠️ 这是最要命的一条：段名错了，dsh 不认识它 → 整段被忽略 →
        //    "配置写进去了但 baseURL 没生效"。而 NS 来自插件自己的 name。
        val section = patch.settingsSection()
        assertTrue("首行必须是顶格的 llm-deepseek:", section.startsWith("llm-deepseek:\n"))
    }

    @Test
    fun `settings 段含 baseURL 与 apiKeyEnv`() {
        val section = patch.settingsSection()
        assertTrue(section.contains("  baseURL: \"http://127.0.0.1:45678/v1\""))
        assertTrue(section.contains("  apiKeyEnv: \"POCKETAGENT_LOCAL_TOKEN\""))
    }

    @Test
    fun `settings 段的模型清单用列表形式且缩进正确`() {
        val section = patch.settingsSection()
        assertTrue(section.contains("  models:"))
        assertTrue(section.contains("    - id: \"pocketagent-auto\""))
    }

    @Test
    fun `settings 段不写 agent-default-model`() {
        // ⚠️ 那个键已经存在于真机的 settings.yaml，由 dsh 自己维护。
        //    我们去写它会覆盖用户的模型选择 —— 且他找不到地方改回来。
        assertFalse(patch.settingsSection().contains("agent-default-model"))
    }

    @Test
    fun `多模型时逐个列出`() {
        val p = patch.copy(advertisedModelIds = listOf("m-a", "m-b"))
        val section = p.settingsSection()
        assertTrue(section.contains("    - id: \"m-a\""))
        assertTrue(section.contains("    - id: \"m-b\""))
    }

    @Test
    fun `凭据条目缩进两格且含键与值`() {
        assertEquals(
            "  \"POCKETAGENT_LOCAL_TOKEN\": \"abc123_XYZ-token\"",
            patch.credentialEntry(),
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  YAML 标量转义 —— 一组"看起来多余、删掉就出事"的用例
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `值一律加双引号`() {
        assertEquals("\"plain\"", DshConfigPatch.yamlScalar("plain"))
        assertEquals("\"123\"", DshConfigPatch.yamlScalar("123"))
    }

    @Test
    fun `含井号的值被引号保护`() {
        // ⚠️ 裸写时 `#` 之后全被当注释 —— token 里出现 `#` 会静默截断，
        //    表现是网关回 401，而用户会去查网关是不是没起来。
        val v = DshConfigPatch.yamlScalar("a#b")
        assertEquals("\"a#b\"", v)
    }

    @Test
    fun `会被解析成布尔或空值的字符串被引号保护`() {
        // ⚠️ 最阴的一类：token 短时有概率恰好命中这些词。
        for (dangerous in listOf("yes", "no", "on", "off", "true", "false", "null", "~")) {
            assertEquals("\"$dangerous\"", DshConfigPatch.yamlScalar(dangerous))
        }
    }

    @Test
    fun `冒号与空格组合被引号保护`() {
        assertEquals("\"a: b\"", DshConfigPatch.yamlScalar("a: b"))
    }

    @Test
    fun `前导特殊字符被引号保护`() {
        for (dangerous in listOf("*star", "&anchor", "%directive", "@at", "-dash", "?q")) {
            assertEquals("\"$dangerous\"", DshConfigPatch.yamlScalar(dangerous))
        }
    }

    @Test
    fun `反斜杠与双引号被转义`() {
        assertEquals("\"a\\\\b\"", DshConfigPatch.yamlScalar("a\\b"))
        assertEquals("\"a\\\"b\"", DshConfigPatch.yamlScalar("a\"b"))
    }

    @Test
    fun `含冒号的 baseUrl 渲染后被完整保留`() {
        // 端到端的形态确认：`http://127.0.0.1:45678/v1` 里的冒号不能破坏 YAML
        val section = patch.settingsSection()
        val line = section.lines().first { it.trimStart().startsWith("baseURL:") }
        assertEquals("  baseURL: \"http://127.0.0.1:45678/v1\"", line)
    }

    // ─────────────────────────────────────────────────────────────
    //  settings.yaml 的合并 —— 必须"只动自己那一段"
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `空文件合并得到完整段`() {
        val out = patch.mergeIntoSettingsYaml("")
        assertEquals(patch.settingsSection() + "\n", out)
    }

    @Test
    fun `往既有文件合并时保留原有内容`() {
        // 真机 settings.yaml 的真实形态
        val existing = """
            ui-onboarding:
              welcomeNoticeVersion: 2026-08-13.1
            agent-presets:
              default: tavern-lite
            agent-default-model:
              provider: deepseek-official
              model: deepseek-flash
              reasoningEffort: low
        """.trimIndent()

        val out = patch.mergeIntoSettingsYaml(existing)

        // 原有三块必须一字不动
        assertTrue(out.contains("ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1"))
        assertTrue(out.contains("agent-presets:\n  default: tavern-lite"))
        assertTrue(out.contains("provider: deepseek-official"))
        assertTrue(out.contains("model: deepseek-flash"))
        // 我们的段被追加
        assertTrue(out.contains("llm-deepseek:"))
        assertTrue(out.contains("baseURL: \"http://127.0.0.1:45678/v1\""))
    }

    @Test
    fun `已存在同段时替换而不是追加`() {
        val existing = """
            ui-onboarding:
              welcomeNoticeVersion: x
            llm-deepseek:
              baseURL: "http://127.0.0.1:1111/v1"
              apiKeyEnv: "OLD_TOKEN"
            agent-default-model:
              provider: deepseek-official
        """.trimIndent()

        val out = patch.mergeIntoSettingsYaml(existing)

        // 旧值消失
        assertFalse(out.contains("1111"))
        assertFalse(out.contains("OLD_TOKEN"))
        // 新值就位
        assertTrue(out.contains("45678"))
        // 段只出现一次
        assertEquals(1, out.lines().count { it == "llm-deepseek:" })
        // 后面的段没被吃掉 —— 这是最容易错的一处（段结束行判定）
        assertTrue(out.contains("agent-default-model:\n  provider: deepseek-official"))
    }

    @Test
    fun `合并是幂等的`() {
        val existing = """
            agent-default-model:
              provider: deepseek-official
              model: deepseek-flash
        """.trimIndent()

        val once = patch.mergeIntoSettingsYaml(existing)
        val twice = patch.mergeIntoSettingsYaml(once)
        val thrice = patch.mergeIntoSettingsYaml(twice)

        assertEquals(once, twice)
        assertEquals(once, thrice)
    }

    @Test
    fun `多次合并不堆积空行`() {
        // ⚠️ 早期实现把"段后空行"留在原位，反复追加时空行会越积越多。
        var out = "agent-presets:\n  default: tavern-lite\n"
        repeat(5) { out = patch.mergeIntoSettingsYaml(out) }
        assertFalse("不应出现连续三个空行", out.contains("\n\n\n"))
        assertEquals(1, out.lines().count { it == "llm-deepseek:" })
    }

    @Test
    fun `缩进的同名键不被误认为我们的段`() {
        // ⚠️ 某个 plugin 的 config 里恰有 `llm-deepseek:` 作为嵌套子键。
        //    只匹配顶格行才能不误伤它。
        val existing = """
            some-plugin:
              llm-deepseek:
                baseURL: "http://should-stay:1/v1"
        """.trimIndent()

        val out = patch.mergeIntoSettingsYaml(existing)

        // 嵌套那个必须原样保留
        assertTrue(out.contains("  llm-deepseek:\n    baseURL: \"http://should-stay:1/v1\""))
        // 我们的是顶格的，被追加
        assertTrue(out.lines().any { it == "llm-deepseek:" })
    }

    @Test
    fun `段内的注释随段一起被替换`() {
        // ⚠️ 留着旧注释会让它描述新内容 —— 比没有注释更误导。
        val existing = """
            llm-deepseek:
              # 这是指向旧网关的配置
              baseURL: "http://127.0.0.1:1111/v1"
            other:
              k: v
        """.trimIndent()

        val out = patch.mergeIntoSettingsYaml(existing)

        assertFalse(out.contains("旧网关"))
        assertTrue(out.contains("other:\n  k: v"))
    }

    @Test
    fun `段是文件末尾时也能正确合并`() {
        val existing = "agent-presets:\n  default: tavern-lite\nllm-deepseek:\n  baseURL: \"http://old:1/v1\"\n"
        val out = patch.mergeIntoSettingsYaml(existing)
        assertFalse(out.contains("http://old"))
        assertEquals(1, out.lines().count { it == "llm-deepseek:" })
        assertTrue(out.contains("default: tavern-lite"))
    }

    @Test
    fun `CRLF 换行被归一化`() {
        // ⚠️ 用户在 Windows 上编辑过该文件时会有 \r\n。
        //    不归一化的话 `it == "llm-deepseek:"` 永远不匹配（尾部有 \r），
        //    表现是"每次都追加一段"，文件里出现 N 个同名段。
        val existing = "agent-presets:\r\n  default: tavern-lite\r\n"
        val out = patch.mergeIntoSettingsYaml(existing)
        assertFalse(out.contains("\r"))
        assertEquals(1, out.lines().count { it == "llm-deepseek:" })
    }

    @Test
    fun `结果以单个换行结尾`() {
        assertEquals(true, patch.mergeIntoSettingsYaml("").endsWith("\n"))
        assertEquals(true, patch.mergeIntoSettingsYaml("a: b").endsWith("\n"))
        assertFalse(patch.mergeIntoSettingsYaml("a: b").endsWith("\n\n"))
    }

    // ─────────────────────────────────────────────────────────────
    //  .credentials.yaml 的合并 —— 这里绝对不能毁数据
    // ─────────────────────────────────────────────────────────────

    /** 真机上读到的真实形态（真 Key 已替换为占位符） */
    private val realCredentials = """
        version: 1
        records:
          client-connection/browser-session:
            kind: grant
            payload:
              version: 1
              secret: some-opaque-secret
        refs:
          DEEPSEEK_API_KEY: sk-placeholder-not-a-real-key
    """.trimIndent()

    @Test
    fun `往 refs 追加我们的键`() {
        val out = patch.mergeIntoCredentialsYaml(realCredentials)
        assertNotNull(out)
        assertTrue(out!!.contains("  \"POCKETAGENT_LOCAL_TOKEN\": \"abc123_XYZ-token\""))
    }

    @Test
    fun `用户的真 Key 一字不动`() {
        // ★★ 本文件最重要的一条断言。
        //    整文档重写会删掉这一行 —— 用户"绕过网关直连 DeepSeek"的能力
        //    随之消失，而他的 Key 可能已无处可寻。
        val out = patch.mergeIntoCredentialsYaml(realCredentials)!!
        assertTrue(out.contains("  DEEPSEEK_API_KEY: sk-placeholder-not-a-real-key"))
    }

    @Test
    fun `用户的 grant 记录一字不动`() {
        val out = patch.mergeIntoCredentialsYaml(realCredentials)!!
        assertTrue(out.contains("client-connection/browser-session:"))
        assertTrue(out.contains("kind: grant"))
        assertTrue(out.contains("secret: some-opaque-secret"))
    }

    @Test
    fun `version 行一字不动`() {
        val out = patch.mergeIntoCredentialsYaml(realCredentials)!!
        assertTrue(out.lines().any { it == "version: 1" })
    }

    @Test
    fun `我们的键在 refs 块内而不是 records 块内`() {
        // ⚠️ 追加位置算错会把它写进 records 块 —— dsh 会当成一条
        //    "格式错误的记录"，可能拒绝加载**整份**凭据文件。
        val out = patch.mergeIntoCredentialsYaml(realCredentials)!!
        val lines = out.lines()
        val refsAt = lines.indexOfFirst { it == "refs:" }
        val ourAt = lines.indexOfFirst { it.contains("POCKETAGENT_LOCAL_TOKEN") }
        val recordsAt = lines.indexOfFirst { it == "records:" }

        assertTrue("我们那行必须在 refs 之后", ourAt > refsAt)
        assertTrue("records 在 refs 之前，我们的行应在文件更后面", ourAt > recordsAt)
    }

    @Test
    fun `已存在我们的键时替换那一行而不是再插一条`() {
        val existing = realCredentials + "\n  \"POCKETAGENT_LOCAL_TOKEN\": \"old-token\"\n"
        val out = patch.mergeIntoCredentialsYaml(existing)!!
        assertFalse(out.contains("old-token"))
        assertEquals(1, out.lines().count { it.contains("POCKETAGENT_LOCAL_TOKEN") })
    }

    @Test
    fun `token 轮换后旧 token 消失`() {
        val first = patch.mergeIntoCredentialsYaml(realCredentials)!!
        val rotated = patch.copy(token = "new-token-value")
        val out = rotated.mergeIntoCredentialsYaml(first)!!
        assertTrue(out.contains("new-token-value"))
        assertFalse(out.contains("abc123_XYZ-token"))
    }

    @Test
    fun `没有 refs 块时返回 null 而不是自己造一个`() {
        // ⚠️ 补一个 refs 块需要理解 version 与文档指令，做错会毁掉整份凭据。
        //    明确拒绝 比 猜着补 安全 —— 上层会据此提示用户手工处理。
        val noRefs = "version: 1\nrecords:\n  a/b:\n    kind: grant\n    payload: {}\n"
        assertNull(patch.mergeIntoCredentialsYaml(noRefs))
    }

    @Test
    fun `只有 version 行时返回 null`() {
        assertNull(patch.mergeIntoCredentialsYaml("version: 1\n"))
    }

    @Test
    fun `空内容时返回 null`() {
        assertNull(patch.mergeIntoCredentialsYaml(""))
    }

    @Test
    fun `refs 后没有其它块时也能追加`() {
        val out = patch.mergeIntoCredentialsYaml("version: 1\nrefs:\n")
        assertNotNull(out)
        assertTrue(out!!.contains("POCKETAGENT_LOCAL_TOKEN"))
    }

    @Test
    fun `凭据合并是幂等的`() {
        val once = patch.mergeIntoCredentialsYaml(realCredentials)!!
        val twice = patch.mergeIntoCredentialsYaml(once)!!
        assertEquals(once, twice)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 安全断言：真 Key 没有进入本类的任何产出
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `产出的片段里不出现任何像真实 API Key 的值`() {
        // ⚠️ 本类构造器上**没有**真 Key 的位置 —— 这条测试是把那个类型约束
        //    落到文本层面：万一将来有人加了个 apiKey 参数并把它渲染出来，
        //    这条会红。
        val generated = listOf(patch.settingsSection(), patch.credentialEntry())
        for (out in generated) {
            assertFalse("生成的片段中不应出现 sk-：$out", out.contains("sk-"))
        }
    }

    @Test
    fun `合并不会凭空引入新的 Key 形态字符串`() {
        // ⚠️ 这条与上一条**必须分开**，而且分开的理由是本测试文件里
        //    最值得记的一处：
        //
        //    `mergeIntoSettingsYaml(realCredentials)` 的输出里**确实会**出现
        //    `sk-placeholder` —— 但那不是我们生成的，是**输入文件里本来就有**、
        //    被我们如实保留的。第一版把两种断言混在一起写，于是这条红了，
        //    而红的原因不是代码错，是**测试在测一件不该测的事**：
        //    "合并"的正确行为就是"保留一切既有内容"，包括用户的 Key。
        //
        //    正确的断言是**计数不变**：合并前后 `sk-` 出现次数必须相同。
        val input = realCredentials
        val before = Regex("sk-").findAll(input).count()

        val mergedSettings = patch.mergeIntoSettingsYaml(input)
        val mergedCreds = patch.mergeIntoCredentialsYaml(input)!!

        assertEquals(
            "settings 合并不应引入新的 sk- 出现",
            before,
            Regex("sk-").findAll(mergedSettings).count(),
        )
        assertEquals(
            "凭据合并不应引入新的 sk- 出现",
            before,
            Regex("sk-").findAll(mergedCreds).count(),
        )
        // 反向确认：输入里确实有，否则上面两条是空过
        assertTrue(before > 0)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 回归：重复键（离线单测抓到的一个真实 bug）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `重复合并绝不产生第二条同名键`() {
        // ★★ 这是本类最值得记住的一个 bug，被离线单测抓到：
        //
        //    `credentialEntry()` 渲染出的键是**带引号**的
        //    （`"POCKETAGENT_LOCAL_TOKEN": "…"`），而第一版用裸键
        //    `"  POCKETAGENT_LOCAL_TOKEN:"` 做前缀匹配 —— 永远匹配不上。
        //    后果是**每次合并都追加一条重复键**：
        //      · 幂等性失效（文件越长越大）
        //      · token 轮换失效（旧 token 仍在文件里，YAML 重复键语义
        //        取决于解析器 —— "后者胜"或"直接报错"，两种都糟）
        //    而这一切**完全静默**，没有任何异常。
        val input = realCredentials
        var out = input
        repeat(3) { out = patch.mergeIntoCredentialsYaml(out)!! }

        assertEquals(
            "同名键必须只出现一次",
            1,
            out.lines().count { it.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF) },
        )
    }

    @Test
    fun `用户手写的裸键也能被识别并替换`() {
        // 兼容形态：用户可能不用引号手写。
        // ⚠️ 不兼容的话会退化成"重复键"，即上一条测的那个 bug。
        val existing = realCredentials + "\n  POCKETAGENT_LOCAL_TOKEN: old-bare-token\n"
        val out = patch.mergeIntoCredentialsYaml(existing)!!

        assertFalse("旧值必须消失", out.contains("old-bare-token"))
        assertEquals(
            1,
            out.lines().count { it.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF) },
        )
    }

    @Test
    fun `yamlKeyOf 同时认带引号与裸键`() {
        assertEquals("A", DshConfigPatch.yamlKeyOf("  \"A\": \"v\""))
        assertEquals("A", DshConfigPatch.yamlKeyOf("  A: v"))
        assertEquals("A", DshConfigPatch.yamlKeyOf("  'A': v"))
        assertEquals("A", DshConfigPatch.yamlKeyOf("  A: \"v: with colon\""))
    }

    @Test
    fun `yamlKeyOf 对注释与无冒号行返回 null`() {
        assertNull(DshConfigPatch.yamlKeyOf("  # 这是注释"))
        assertNull(DshConfigPatch.yamlKeyOf(""))
        assertNull(DshConfigPatch.yamlKeyOf("   "))
        assertNull(DshConfigPatch.yamlKeyOf("  no colon here"))
    }

    @Test
    fun `refs 块内的注释不会让块提前结束`() {
        // ⚠️ 若把注释行当"块结束"，我们的键会被插到注释**之前** ——
        //    YAML 上仍然合法，但用户读到的结构会变得莫名其妙。
        val existing = """
            version: 1
            refs:
              # 下面这些是各家 API Key
              DEEPSEEK_API_KEY: sk-placeholder-not-a-real-key
            records:
              a/b:
                kind: grant
                payload: {}
        """.trimIndent()

        val out = patch.mergeIntoCredentialsYaml(existing)!!
        val lines = out.lines()
        val commentAt = lines.indexOfFirst { it.trimStart().startsWith("#") }
        val ourAt = lines.indexOfFirst { it.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF) }

        assertTrue("我们的键应在注释之后（仍在 refs 块内）", ourAt > commentAt)
        // records 块没被吃掉
        assertTrue(out.contains("records:\n  a/b:"))
    }

    @Test
    fun `settings 段里的 apiKeyEnv 与 refs 键名逐字相同`() {
        // ★★ 这两个名字分居两个文件。不一致的表现是每请求
        //    MISSING_CREDENTIAL，而用户会去查"token 是不是过期了"。
        val refFromSettings = patch.settingsSection()
            .lines()
            .first { it.trimStart().startsWith("apiKeyEnv:") }
            .substringAfter(": ")
            .trim()
            .trim('"')

        val refFromCredentials = patch.credentialEntry()
            .substringBefore(":")
            .trim()
            .trim('"')

        assertEquals(refFromSettings, refFromCredentials)
        assertEquals(DshConfigPatch.DEFAULT_CREDENTIAL_REF, refFromSettings)
    }

    @Test
    fun `自定义凭据引用名两侧同步`() {
        val p = patch.copy(credentialRef = "MY_LOCAL_TOKEN")
        val settingsRef = p.settingsSection().lines()
            .first { it.trimStart().startsWith("apiKeyEnv:") }
            .substringAfter(": ").trim().trim('"')
        val credRef = p.credentialEntry().substringBefore(":").trim().trim('"')
        assertEquals("MY_LOCAL_TOKEN", settingsRef)
        assertEquals(settingsRef, credRef)
    }

    // ─────────────────────────────────────────────────────────────
    //  credentialsDocument —— 目标文件**不存在**时的新建路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `全新凭据文档带 version 与 refs 两个顶格键`() {
        val lines = patch.credentialsDocument().lines()

        assertEquals("version: 1", lines[0])
        assertEquals("refs:", lines[1])
        assertTrue(
            "凭据行必须缩进在 refs 之下，否则它是另一个顶格键而不是 refs 的成员",
            lines[2].startsWith("  "),
        )
    }

    @Test
    fun `全新凭据文档能被自己的合并逻辑接住`() {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这条是 credentialsDocument 存在的意义所在
        // ═══════════════════════════════════════════════════════════
        //
        // 新建出来的文档必须能被**下一次 start** 的合并路径
        // （mergeIntoCredentialsYaml）正常处理 —— 否则第二次启动就会
        // 追加一条**重复键**，而 YAML 重复键的语义取决于解析器
        // （"后者胜"或直接报错，两种都糟）。
        //
        // 这条测试同时钉住两件事：新建的文档结构**认识**，
        // 以及"新建 → 合并"这条路径**闭合**。
        val first = patch.credentialsDocument()
        val second = patch.copy(token = "rotated-token-xyz").mergeIntoCredentialsYaml(first)

        assertNotNull("新建文档必须能被合并，返回 null 就说明结构自己都不认识", second)

        assertTrue("轮换后的 token 应出现", second!!.contains("rotated-token-xyz"))
        assertFalse("轮换后旧 token 必须消失", second.contains(patch.token))

        val keyCount = second.lines().count { it.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF) }
        assertEquals("同名键只能有一行", 1, keyCount)
    }

    @Test
    fun `全新凭据文档里不出现任何像真实 API Key 的值`() {
        val doc = patch.credentialsDocument()

        assertFalse(
            "凭据文件里只该有本地 token，不该有任何 sk- 形态的串",
            Regex("sk-[A-Za-z0-9]{8,}").containsMatchIn(doc),
        )
    }

    @Test
    fun `全新凭据文档是幂等的`() {
        // 同一份 patch 生成两次必须逐字相同 —— 否则"用户多点一次按钮"
        // 会让文件内容发生变化，而变更无从解释。
        assertEquals(patch.credentialsDocument(), patch.credentialsDocument())
    }
}
