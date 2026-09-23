package com.pocketagent.provider.gateway.dsh

import com.pocketagent.provider.gateway.GatewayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DshGatewaySession] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 会话本身只有几十行，但它是"起网关 → 渲染 → 合并 → 投递"这条链的
 * **唯一粘合点**。链上每一环错的后果都是静默的：
 *
 * | 写错了什么 | 用户看到的现象 | 会去查的方向 |
 * |---|---|---|
 * | 网关没起来却照样投递配置 | dsh 每请求连接被拒 | "是不是我的 Key 有问题" |
 * | 覆盖而不是合并 settings | 用户手写的注释与新键消失 | "我的配置怎么没了" |
 * | 凭据文件不存在时凭空造一份 | dsh 读不动配置（真机上可能） | "是不是你们把 dsh 弄坏了" |
 * | 重复 start 追加第二个段 | 配置里有两份 `llm-deepseek:` | 无从察觉 |
 * | token 进了日志 | 同机其它 App 可读到它 | **永远不会被发现** |
 *
 * 前四条靠**内容断言**抓，最后一条靠**遍历日志**抓。
 */
class DshGatewaySessionTest {

    // ─────────────────────────────────────────────────────────────
    //  假件
    // ─────────────────────────────────────────────────────────────

    /**
     * 假网关端点。
     *
     * ⚠️ 它是 [GatewayEndpoint] 而不是 `HttpGatewayServer` —— 后者持有
     *    `ServerSocket`，那是"只有真机能验"的一半（见会话类注释）。
     */
    private class FakeEndpoint(
        private val startSucceeds: Boolean = true,
        override val baseUrl: String = "http://127.0.0.1:45678/v1",
        override val token: String = "local-token-abc123",
    ) : GatewayEndpoint {
        var startCount = 0
            private set
        var stopCount = 0
            private set

        override fun start(): Boolean {
            startCount++
            return startSucceeds
        }

        override fun stop() {
            stopCount++
        }
    }

    /**
     * 内存落点。
     *
     * ⚠️ [read] 用 `files[path]` 而不是 `files[path] ?: ""` —— **`null` 必须
     *    能传出去**，它是"文件不存在"。会话靠这个区分"新建"与"合并"。
     */
    private class FakeSink(
        val files: MutableMap<String, String> = mutableMapOf(),
        override val mayCreateCredentialsFile: Boolean = true,
        private val failSettingsWrite: Boolean = false,
    ) : DshConfigSink {
        var writeCount = 0
            private set

        override val settingsPath: String = "dsh/settings.yaml"
        override val credentialsPath: String = "dsh/credentials.yaml"

        override fun read(relativePath: String): String? = files[relativePath]

        override fun write(settingsYaml: String, credentialsYaml: String?): DshWriteResult {
            writeCount++
            if (failSettingsWrite) {
                return DshWriteResult.Failure("无法写入配置文件。请检查应用存储空间是否足够。")
            }
            files[settingsPath] = settingsYaml
            if (credentialsYaml == null) {
                // ⚠️⚠️ **这条理由必须保持"不帮忙"** —— 它**故意**不提变量名。
                //
                // 因为会话承诺了一条不变量：*从会话出去的 Partial 理由一定点到
                // 用户该补哪一行*（理由见 DshGatewaySession.withActionableReason）。
                // 假件提了变量名，这条不变量就变成了"假件自己写对了"，
                // 测试会通过而覆盖为零 —— 而将来真有人删掉会话里那段补全代码，
                // 也不会有任何测试变红。
                //
                // 所以：**不要"顺手"把它补成真实落点那样的措辞。**
                return DshWriteResult.Partial("凭据文件结构不认识，请手工补一行。")
            }
            files[credentialsPath] = credentialsYaml
            return DshWriteResult.Success(location = settingsPath, credentialsWritten = true)
        }
    }

    private fun sessionOf(
        endpoint: GatewayEndpoint,
        sink: DshConfigSink,
        log: (String) -> Unit = {},
    ) = DshGatewaySession(endpoint = endpoint, sink = sink, log = log)

    // ─────────────────────────────────────────────────────────────
    //  网关起不来时**什么都不该发生**
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `网关起不来时不投递任何配置`() {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这是本类最重要的一条
        // ═══════════════════════════════════════════════════════════
        //
        // 把一份指向**不存在端口**的配置写进 dsh，会让 dsh 每次请求都
        // 连接被拒 —— 而用户会以为是我们改坏了他的配置，
        // 于是去重装 dsh、去删配置，排查方向完全错。
        //
        // 什么都不写虽然也是失败，但它是**诚实的失败**：
        // dsh 保持原样，用户看到的报错来自我们而不是一个被写坏的文件。
        val sink = FakeSink()

        val result = sessionOf(FakeEndpoint(startSucceeds = false), sink).start()

        assertTrue("实际 $result", result is DshGatewayStartResult.NotStarted)
        assertEquals("一次写都不该发生", 0, sink.writeCount)
        assertTrue("落点里不该有任何文件", sink.files.isEmpty())
    }

    @Test
    fun `网关起不来时给出用户可行动的理由`() {
        val result = sessionOf(FakeEndpoint(startSucceeds = false), FakeSink()).start()

        val reason = (result as DshGatewayStartResult.NotStarted).reason
        // ⚠️ 理由必须指向**用户能做的事**，而不是"启动失败"四个字
        assertTrue("应提示重启应用这个动作：$reason", reason.contains("重启"))
        assertFalse("不该出现裸的技术术语堆砌", reason.contains("ServerSocket"))
    }

    // ─────────────────────────────────────────────────────────────
    //  正常路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `启动成功时两份配置都被投递`() {
        val sink = FakeSink()

        val result = sessionOf(FakeEndpoint(), sink).start()

        val running = result as DshGatewayStartResult.Running
        assertEquals("http://127.0.0.1:45678/v1", running.baseUrl)
        assertTrue(
            "两份文件都该写成功，实际 ${running.delivery}",
            running.delivery is DshWriteResult.Success,
        )
        assertTrue(sink.files.containsKey(sink.settingsPath))
        assertTrue(sink.files.containsKey(sink.credentialsPath))
    }

    @Test
    fun `settings 内容里用的是 endpoint 给的 baseUrl 与 token`() {
        val sink = FakeSink()

        sessionOf(FakeEndpoint(baseUrl = "http://127.0.0.1:9999/v1", token = "tok-xyz"), sink)
            .start()

        val settings = sink.files.getValue(sink.settingsPath)
        assertTrue("baseUrl 应来自 endpoint：$settings", settings.contains("127.0.0.1:9999"))

        val credentials = sink.files.getValue(sink.credentialsPath)
        assertTrue("token 应来自 endpoint", credentials.contains("tok-xyz"))
    }

    @Test
    fun `settings 里声明的模型名是虚拟模型名`() {
        val sink = FakeSink()
        sessionOf(FakeEndpoint(), sink).start()

        val settings = sink.files.getValue(sink.settingsPath)
        assertTrue(settings.contains(DshConfigPatch.VIRTUAL_MODEL_ID))
        // ⚠️ 不得把任何真实厂商的模型名写进去 —— 那会让 dsh 按名字分支
        assertFalse("不该出现 deepseek 的真实模型名", settings.contains("deepseek-chat"))
    }

    // ─────────────────────────────────────────────────────────────
    //  合并而不是覆盖
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `既有 settings 里的用户内容被保住`() {
        // ⚠️ 真机上的 settings.yaml 里有用户手写的
        //    `# ---- user patch layer ----` 这类注释，以及 dsh 自己的
        //    `agent-default-model` / `agent-presets`。整文件覆盖会把它们删掉。
        val sink = FakeSink(
            files = mutableMapOf(
                "dsh/settings.yaml" to """
                    # 这是我自己写的注释
                    agent-default-model:
                      provider: deepseek-official
                      model: deepseek-flash
                """.trimIndent()
            )
        )

        sessionOf(FakeEndpoint(), sink).start()

        val settings = sink.files.getValue(sink.settingsPath)
        assertTrue("用户注释必须保住", settings.contains("# 这是我自己写的注释"))
        assertTrue("dsh 自己的配置必须保住", settings.contains("agent-default-model"))
        assertTrue("我们的段应被加上", settings.contains("${DshConfigPatch.SETTINGS_NS}:"))
    }

    @Test
    fun `既有凭据文件里的真 Key 被保住`() {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这条守的是"把用户的真 Key 删掉"这个不可逆后果
        // ═══════════════════════════════════════════════════════════
        val sink = FakeSink(
            files = mutableMapOf(
                "dsh/credentials.yaml" to """
                    version: 1
                    refs:
                      DEEPSEEK_API_KEY: sk-user-real-key
                    records:
                      client-connection/browser-session:
                        kind: grant
                """.trimIndent()
            )
        )

        sessionOf(FakeEndpoint(), sink).start()

        val credentials = sink.files.getValue(sink.credentialsPath)
        assertTrue("用户的真 Key 必须逐字保留", credentials.contains("DEEPSEEK_API_KEY: sk-user-real-key"))
        assertTrue("records 块必须保留", credentials.contains("client-connection/browser-session"))
        assertTrue(
            "我们的本地 token 应被加进 refs",
            credentials.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF),
        )
    }

    @Test
    fun `重复 start 不会产生第二个 llm-deepseek 段`() {
        // ⚠️ 用户多点一次按钮、或应用重启后重新进页面 —— 都不该让配置
        //    长出第二份同名段。YAML 重复段的语义取决于解析器。
        val sink = FakeSink()
        val session = sessionOf(FakeEndpoint(), sink)

        session.start()
        session.start()
        session.start()

        val settings = sink.files.getValue(sink.settingsPath)
        val sectionCount = settings.lines().count { it == "${DshConfigPatch.SETTINGS_NS}:" }
        assertEquals("段只能有一个", 1, sectionCount)
    }

    @Test
    fun `重复 start 不会在凭据文件里追加第二条同名键`() {
        val sink = FakeSink()
        val session = sessionOf(FakeEndpoint(), sink)

        session.start()
        session.start()

        val credentials = sink.files.getValue(sink.credentialsPath)
        val keyCount = credentials.lines()
            .count { it.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF) }
        assertEquals("同名键只能有一行", 1, keyCount)
    }

    // ─────────────────────────────────────────────────────────────
    //  凭据文件的三种情形
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `凭据文件不存在且落点允许新建时生成一份`() {
        val sink = FakeSink(mayCreateCredentialsFile = true)

        val result = sessionOf(FakeEndpoint(), sink).start() as DshGatewayStartResult.Running

        assertTrue(result.delivery is DshWriteResult.Success)
        val credentials = sink.files.getValue(sink.credentialsPath)
        assertTrue(credentials.contains("refs:"))
    }

    @Test
    fun `凭据文件不存在且落点禁止新建时报 Partial 而不是 Failure`() {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这条守的是"写 dsh 真实目录"时的边界
        // ═══════════════════════════════════════════════════════════
        //
        // 真实 `.credentials.yaml` 可能含 `records:` 等我们没见过的段，
        // 凭空造一份可能让 dsh 整个读不动配置。所以落点可以禁止新建。
        //
        // ⚠️ 此时结果必须是 **Partial 而不是 Failure**：settings.yaml
        //    **确实写成功了**，说成失败会让用户以为整个操作没生效，
        //    而他会重复操作、或去查一个不存在的写入故障。
        val sink = FakeSink(mayCreateCredentialsFile = false)

        val result = sessionOf(FakeEndpoint(), sink).start() as DshGatewayStartResult.Running

        assertTrue("实际 ${result.delivery}", result.delivery is DshWriteResult.Partial)
        assertTrue("settings 仍然应该被写", sink.files.containsKey(sink.settingsPath))
        assertFalse("但凭据文件不该被创建", sink.files.containsKey(sink.credentialsPath))
    }

    @Test
    fun `既有凭据文件结构不认识时如实报 Partial`() {
        // `refs:` 块不存在 → DshConfigPatch 返回 null（"我不处理"），
        // 会话必须把 null 如实转成 Partial，**绝不能退化成整文件覆盖**。
        val original = """
            version: 1
            records:
              something:
                kind: grant
        """.trimIndent()
        val sink = FakeSink(files = mutableMapOf("dsh/credentials.yaml" to original))

        val result = sessionOf(FakeEndpoint(), sink).start() as DshGatewayStartResult.Running

        assertTrue("实际 ${result.delivery}", result.delivery is DshWriteResult.Partial)
        assertEquals("原文件必须一个字节都没动", original, sink.files.getValue(sink.credentialsPath))
    }

    @Test
    fun `Partial 的理由里必须点出要手工补的那个键名`() {
        // ═══════════════════════════════════════════════════════════
        //  ★ 会话承诺的不变量：出去的 Partial 理由一定点到"补哪一行"
        // ═══════════════════════════════════════════════════════════
        //
        // ⚠️ "凭据写入失败"对用户没有任何用处 —— 他需要知道**补什么**。
        //    不知道补什么，用户唯一的出路是来问我们。
        //
        // ⚠️ 这条测试之所以有意义，是因为 [FakeSink] **故意**给了一条不带
        //    变量名的理由（见那个假件的注释）。也就是说这里验的是
        //    **会话把理由补全了**，而不是"假件自己写对了"。
        //    会话里那段补全代码一旦被删，这条就会红。
        val sink = FakeSink(mayCreateCredentialsFile = false)

        val result = sessionOf(FakeEndpoint(), sink).start() as DshGatewayStartResult.Running
        val reason = (result.delivery as DshWriteResult.Partial).reason

        assertTrue(
            "理由里要点出变量名：$reason",
            reason.contains(DshConfigPatch.DEFAULT_CREDENTIAL_REF),
        )
    }

    @Test
    fun `落点已点出变量名时不会重复追加`() {
        // ⚠️ 补全是**幂等**的：真实落点 `AppPrivateDshConfigSink` 自己就带变量名，
        //    会话不能再给它加一遍 —— 否则用户会看到同一句话出现两次，
        //    读起来像是"要补两行"。
        //
        // 这里用一个"帮忙的"落点来验另一侧：理由已含变量名 → 原样透传。
        val helpful = object : DshConfigSink {
            override val settingsPath = "dsh/settings.yaml"
            override val credentialsPath = "dsh/credentials.yaml"
            override val mayCreateCredentialsFile = false
            override fun read(relativePath: String): String? = null
            override fun write(settingsYaml: String, credentialsYaml: String?) = DshWriteResult.Partial(
                "凭据文件不存在，请把 ${DshConfigPatch.DEFAULT_CREDENTIAL_REF} 一行手工补进去。",
            )
        }

        val result = sessionOf(FakeEndpoint(), helpful).start() as DshGatewayStartResult.Running
        val reason = (result.delivery as DshWriteResult.Partial).reason

        assertEquals(
            "理由里变量名只应出现一次",
            1,
            Regex(Regex.escape(DshConfigPatch.DEFAULT_CREDENTIAL_REF)).findAll(reason).count(),
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  落点写失败时，网关**仍在跑**
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `落点写失败时报 Failure 但网关仍在跑`() {
        // ⚠️ "网关起来了"与"配置送达了"是两件独立的事实。
        //    合并成一个布尔值会让其中一种情况说不出话 ——
        //    用户的重试动作对这两者完全不同。
        val sink = FakeSink(failSettingsWrite = true)

        val result = sessionOf(FakeEndpoint(), sink).start()

        val running = result as DshGatewayStartResult.Running
        assertTrue("实际 ${running.delivery}", running.delivery is DshWriteResult.Failure)
        assertTrue("baseUrl 仍应可用", running.baseUrl.startsWith("http://127.0.0.1:"))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ token 绝不进日志
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `日志里不出现 token`() {
        // ═══════════════════════════════════════════════════════════
        //  ★★ 这条守的是一条**永远不会被用户发现**的泄露
        // ═══════════════════════════════════════════════════════════
        //
        // 日志会被应用内日志页收集，token 进了日志就等于泄露给任何能读
        // 日志的东西 —— 包括我们要防的同机其它 App 的辅助功能服务。
        // 而用户永远不会知道这件事发生过。
        val lines = mutableListOf<String>()
        val endpoint = FakeEndpoint(token = "super-secret-local-token")
        val session = sessionOf(endpoint, FakeSink()) { lines += it }

        session.start()
        session.stop()

        assertTrue("应确实记了日志（否则本测试是空的）", lines.isNotEmpty())
        val joined = lines.joinToString("\n")
        assertFalse("token 不得出现在日志里：$joined", joined.contains("super-secret-local-token"))
        // 连片段都不该有
        assertFalse(joined.contains("secret"))
    }

    @Test
    fun `日志里也不出现明文 Key 形态的串`() {
        val lines = mutableListOf<String>()
        sessionOf(FakeEndpoint(), FakeSink()) { lines += it }.start()

        val joined = lines.joinToString("\n")
        assertFalse(
            "日志里不该出现任何 sk- 形态的串",
            Regex("sk-[A-Za-z0-9]{8,}").containsMatchIn(joined),
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  stop
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `stop 会停掉端点`() {
        val endpoint = FakeEndpoint()
        val session = sessionOf(endpoint, FakeSink())

        session.start()
        session.stop()

        assertEquals(1, endpoint.stopCount)
    }

    @Test
    fun `stop 不清空已投递的配置`() {
        // ⚠️ 这是**刻意的取舍**，不是遗漏（理由见 stop 的长注释）：
        //    把配置删掉会让 dsh 进入"完全没配过"的状态，
        //    比"配了但连不上"更难向用户解释。
        val sink = FakeSink()
        val session = sessionOf(FakeEndpoint(), sink)

        session.start()
        val before = sink.files.toMap()
        session.stop()

        assertEquals("停止不该改动任何文件", before, sink.files)
    }

    @Test
    fun `没 start 过就 stop 不会抛异常`() {
        // 端点自身幂等；会话不该在它之上引入新的失败模式
        val endpoint = FakeEndpoint()
        sessionOf(endpoint, FakeSink()).stop()
        assertEquals(1, endpoint.stopCount)
    }

    @Test
    fun `start 之前不该读落点`() {
        // ⚠️ 网关没起来时连"读"都不该发生 —— 读到的旧内容可能被误用。
        //    用一个会抛的 read 来证明它真的没被碰。
        val throwingSink = object : DshConfigSink {
            override val settingsPath = "dsh/settings.yaml"
            override val credentialsPath = "dsh/credentials.yaml"
            override val mayCreateCredentialsFile = true
            override fun read(relativePath: String): String? =
                throw AssertionError("网关没起来时不该读落点")
            override fun write(settingsYaml: String, credentialsYaml: String?): DshWriteResult =
                throw AssertionError("网关没起来时不该写落点")
        }

        val result = sessionOf(FakeEndpoint(startSucceeds = false), throwingSink).start()

        assertTrue(result is DshGatewayStartResult.NotStarted)
        assertNull(null) // 走到这里说明上面没抛
    }
}
