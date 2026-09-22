package com.pocketagent.provider.gateway.http

import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatRequestTranslator] 的离线单测。
 *
 * ⚠️ 用 `org.junit.Assert`（Truth 不在离线 classpath）。
 *
 * ⚠️ 几乎每个用例都同时检查**失败时给出的理由里有没有位置信息** ——
 *    因为一个只说"请求非法"的网关对写 dsh 插件的人毫无帮助，
 *    而他只能看到 400 与一句话。
 */
class ChatRequestTranslatorTest {

    private fun json(body: String) = ChatRequestTranslator.translate { body }

    private fun ok(body: String): com.pocketagent.provider.api.ChatRequest {
        val r = json(body)
        assertTrue("期望翻译成功，实际：$r", r is ChatRequestTranslator.TranslationResult.Ok)
        return (r as ChatRequestTranslator.TranslationResult.Ok).request
    }

    private fun invalid(body: String): String {
        val r = json(body)
        assertTrue("期望翻译失败，实际：$r", r is ChatRequestTranslator.TranslationResult.Invalid)
        return (r as ChatRequestTranslator.TranslationResult.Invalid).reason
    }

    // ─────────────────────────────────────────────────────────────
    //  快乐路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `最简请求能翻译`() {
        val req = ok(
            """
            {"model":"gpt-4o","messages":[{"role":"user","content":"你好"}]}
            """.trimIndent()
        )

        assertEquals(1, req.messages.size)
        assertEquals(ChatMessage.Role.USER, req.messages[0].role)
        assertEquals(listOf(ContentPart.Text("你好")), req.messages[0].content)
    }

    @Test
    fun `model 字段原样带出来交给 GatewayCore 覆盖`() {
        // ⚠️ 翻译层**不负责**忽略它 —— 那由 GatewayCore 做。
        //    两处都管会出现"改了这边忘了那边"。这条测试就是钉住
        //    "这里不越权"这件事。
        val req = ok("""{"model":"client-guess","messages":[{"role":"user","content":"x"}]}""")

        assertEquals("client-guess", req.model)
    }

    @Test
    fun `中文与 emoji 不被破坏`() {
        val req = ok("""{"messages":[{"role":"user","content":"截图里有 🎉"}]}""")

        assertEquals(listOf(ContentPart.Text("截图里有 🎉")), req.messages[0].content)
    }

    @Test
    fun `四个 role 都能识别`() {
        val req = ok(
            """
            {"messages":[
              {"role":"system","content":"s"},
              {"role":"user","content":"u"},
              {"role":"assistant","content":"a"},
              {"role":"tool","content":"t","tool_call_id":"c1"}
            ]}
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ChatMessage.Role.SYSTEM,
                ChatMessage.Role.USER,
                ChatMessage.Role.ASSISTANT,
                ChatMessage.Role.TOOL,
            ),
            req.messages.map { it.role },
        )
        assertEquals("c1", req.messages[3].toolCallId)
    }

    @Test
    fun `developer 角色归到 SYSTEM`() {
        // ⚠️ developer 是较新的 OpenAI 角色（替代 system）。
        //    漏了它的表现是"用新 SDK 的客户端全部 400"。
        val req = ok("""{"messages":[{"role":"developer","content":"s"}]}""")

        assertEquals(ChatMessage.Role.SYSTEM, req.messages[0].role)
    }

    @Test
    fun `function 角色归到 TOOL`() {
        // 老协议的叫法，仍在使用
        val req = ok("""{"messages":[{"role":"function","content":"t"}]}""")

        assertEquals(ChatMessage.Role.TOOL, req.messages[0].role)
    }

    @Test
    fun `role 大小写不敏感`() {
        val req = ok("""{"messages":[{"role":"USER","content":"x"}]}""")

        assertEquals(ChatMessage.Role.USER, req.messages[0].role)
    }

    // ─────────────────────────────────────────────────────────────
    //  多模态
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `多模态数组里的 text 片段能解析`() {
        val req = ok(
            """
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"看看这个"}
            ]}]}
            """.trimIndent()
        )

        assertEquals(listOf(ContentPart.Text("看看这个")), req.messages[0].content)
    }

    @Test
    fun `data URI 图片能解出原始字节`() {
        // "AQID" 是 [0x01, 0x02, 0x03]
        val req = ok(
            """
            {"messages":[{"role":"user","content":[
              {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}
            ]}]}
            """.trimIndent()
        )

        val img = req.messages[0].content[0] as ContentPart.Image
        assertEquals("image/png", img.mimeType)
        assertEquals(3, img.bytes.size)
        assertEquals(1, img.bytes[0].toInt())
        assertEquals(2, img.bytes[1].toInt())
        assertEquals(3, img.bytes[2].toInt())
    }

    @Test
    fun `http 图片 URL 被跳过而不是下载`() {
        // ★ 刻意行为：让 Provider 去下载远程图片会 ① 暴露用户 IP
        //   ② 引入控制不了的超时 ③ 可能是 SSRF 入口。
        val req = ok(
            """
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"之前"},
              {"type":"image_url","image_url":{"url":"https://evil.example/x.png"}},
              {"type":"text","text":"之后"}
            ]}]}
            """.trimIndent()
        )

        // 两段文本都在
        assertEquals(
            listOf(ContentPart.Text("之前"), ContentPart.Text("之后")),
            req.messages[0].content.filterIsInstance<ContentPart.Text>(),
        )
    }

    @Test
    fun `未知 content 类型被跳过而不是报错`() {
        // ⚠️ 协议在演进（input_audio / file）。因为我们不认识就整个拒绝
        //    会让新客户端完全不能用；跳过至少让模型看到其余内容。
        val req = ok(
            """
            {"messages":[{"role":"user","content":[
              {"type":"text","text":"ok"},
              {"type":"input_audio","input_audio":{"data":"...","format":"wav"}}
            ]}]}
            """.trimIndent()
        )

        assertEquals(listOf(ContentPart.Text("ok")), req.messages[0].content)
    }

    @Test
    fun `null content 是合法的`() {
        // 带 tool_calls 的 assistant 消息 content 就是 null
        val req = ok("""{"messages":[{"role":"assistant","content":null}]}""")

        assertEquals(emptyList<ContentPart>(), req.messages[0].content)
    }

    @Test
    fun `单个 content 对象不套数组也能解析`() {
        // 个别客户端这么发
        val req = ok(
            """{"messages":[{"role":"user","content":{"type":"text","text":"x"}}]}"""
        )

        assertEquals(listOf(ContentPart.Text("x")), req.messages[0].content)
    }

    // ─────────────────────────────────────────────────────────────
    //  参数
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `max_tokens 带出来`() {
        val req = ok("""{"messages":[{"role":"user","content":"x"}],"max_tokens":100}""")

        assertEquals(100, req.maxTokens)
    }

    @Test
    fun `max_completion_tokens 也认`() {
        // 较新的 OpenAI 字段，新 SDK 会发它
        val req = ok("""{"messages":[{"role":"user","content":"x"}],"max_completion_tokens":200}""")

        assertEquals(200, req.maxTokens)
    }

    @Test
    fun `两个 max tokens 同时给时取较小者`() {
        // ⚠️ 协议上没定义谁优先。取较小者是安全方向：
        //    宁可答短一点，也不要看错字段超出客户端预期。
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "max_tokens":500,"max_completion_tokens":100}
            """.trimIndent()
        )

        assertEquals(100, req.maxTokens)
    }

    @Test
    fun `temperature 带出来`() {
        val req = ok("""{"messages":[{"role":"user","content":"x"}],"temperature":0.7}""")

        assertEquals(0.7, req.temperature!!, 1e-9)
    }

    @Test
    fun `response_format 带 json_object 时开 jsonMode`() {
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "response_format":{"type":"json_object"}}
            """.trimIndent()
        )

        assertTrue(req.jsonMode)
    }

    @Test
    fun `response_format 是 json_schema 时不误判为 jsonMode`() {
        // ⚠️ 类型是 JsonElement 而不是 String 就是为了这个 ——
        //    新版协议里 response_format 是嵌套对象，收成 String 会
        //    直接反序列化失败（用新 SDK 的客户端全 400）。
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "response_format":{"type":"json_schema","json_schema":{"name":"x"}}}
            """.trimIndent()
        )

        // 不崩、且不误判
        assertEquals(false, req.jsonMode)
    }

    @Test
    fun `tools 能翻译成 ToolSpec`() {
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "tools":[{"type":"function","function":{
               "name":"click","description":"点一下",
               "parameters":{"type":"object","properties":{}}}}]}
            """.trimIndent()
        )

        assertEquals(1, req.tools.size)
        assertEquals("click", req.tools[0].name)
        assertEquals("点一下", req.tools[0].description)
        assertTrue(req.tools[0].parametersJsonSchema.contains("object"))
    }

    @Test
    fun `tools 缺 parameters 时给空 schema`() {
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "tools":[{"type":"function","function":{"name":"click"}}]}
            """.trimIndent()
        )

        assertEquals("click", req.tools[0].name)
        assertTrue(req.tools[0].parametersJsonSchema.isNotBlank())
    }

    @Test
    fun `缺 name 的 tool 被丢弃而不是产生空工具`() {
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "tools":[{"type":"function","function":{"description":"无名"}}]}
            """.trimIndent()
        )

        assertEquals(emptyList<Any>(), req.tools)
    }

    @Test
    fun `未知字段被忽略`() {
        // dsh 会塞 top_p / presence_penalty / stream_options 之类
        val req = ok(
            """
            {"messages":[{"role":"user","content":"x"}],
             "top_p":0.9,"presence_penalty":0.1,"seed":42,
             "stream_options":{"include_usage":true}}
            """.trimIndent()
        )

        assertEquals(1, req.messages.size)
    }

    // ─────────────────────────────────────────────────────────────
    //  失败路径：理由必须可操作
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `非法 JSON 给出可读理由`() {
        assertTrue(invalid("这不是 JSON").contains("JSON"))
    }

    @Test
    fun `空 body 给出可读理由`() {
        assertTrue(invalid("").contains("JSON"))
    }

    @Test
    fun `messages 为空被拒`() {
        // ⚠️ 放行会发一个空请求给上游，报错千奇百怪
        //    （有的 400、有的返回空回答、个别厂商会直接扣费）
        assertTrue(invalid("""{"model":"x","messages":[]}""").contains("messages"))
    }

    @Test
    fun `缺 messages 字段被拒`() {
        assertTrue(invalid("""{"model":"x"}""").contains("messages"))
    }

    @Test
    fun `非法 role 的理由里有位置`() {
        // ★ 多轮对话里"第几条"是唯一能让调用方定位的信息。
        //   只报"role 非法"等于没说。
        val reason = invalid(
            """
            {"messages":[
              {"role":"user","content":"一"},
              {"role":"wizard","content":"二"}
            ]}
            """.trimIndent()
        )

        assertTrue("理由应指出第 2 条：$reason", reason.contains("2"))
        assertTrue("理由应回显非法值：$reason", reason.contains("wizard"))
    }

    @Test
    fun `非法 content 的理由里有位置`() {
        val reason = invalid(
            """
            {"messages":[
              {"role":"user","content":"一"},
              {"role":"user","content":[{"type":"text"}]}
            ]}
            """.trimIndent()
        )

        assertTrue("理由应指出第 2 条：$reason", reason.contains("2"))
    }

    @Test
    fun `失败理由里不含用户的内容文本`() {
        // ⚠️ 隐私：错误会回给客户端、也会进日志。请求体里可能是
        //    用户的私人对话，甚至截图 base64（数 MB）。
        val secret = "我的银行卡密码是 123456"
        val reason = invalid(
            """
            {"messages":[{"role":"wizard","content":"$secret"}]}
            """.trimIndent()
        )

        assertTrue("理由不得回显用户内容：$reason", !reason.contains(secret))
        assertTrue("理由不得回显用户内容：$reason", !reason.contains("123456"))
    }

    @Test
    fun `非法 role 时理由里也不含其它消息的内容`() {
        val secret = "敏感内容ABC"
        val reason = invalid(
            """
            {"messages":[
              {"role":"user","content":"$secret"},
              {"role":"wizard","content":"x"}
            ]}
            """.trimIndent()
        )

        assertTrue("理由不得泄露其它消息：$reason", !reason.contains(secret))
    }

    @Test
    fun `role 为 null 时不崩`() {
        assertTrue(invalid("""{"messages":[{"content":"x"}]}""").isNotBlank())
    }

    @Test
    fun `content 是数字时给出可读理由`() {
        val reason = invalid("""{"messages":[{"role":"user","content":123}]}""")

        assertTrue("理由应说明 content 形态问题：$reason", reason.isNotBlank())
    }

    @Test
    fun `已解析对象版本与字符串版本结论一致`() {
        // 两条入口（translate(String) 与 translate(GatewayChatRequest)）
        // 不能有分歧 —— 后者被 HttpGatewayServer 直接调用。
        val body = """{"messages":[{"role":"user","content":"x"}]}"""
        val fromString = json(body)
        val fromObject = ChatRequestTranslator.translate(
            GatewayChatRequest(
                model = "",
                messages = listOf(GatewayMessage(role = "user", content = kotlinx.serialization.json.JsonPrimitive("x"))),
            )
        )

        assertTrue(fromString is ChatRequestTranslator.TranslationResult.Ok)
        assertTrue(fromObject is ChatRequestTranslator.TranslationResult.Ok)
        assertEquals(
            (fromString as ChatRequestTranslator.TranslationResult.Ok).request.messages,
            (fromObject as ChatRequestTranslator.TranslationResult.Ok).request.messages,
        )
    }

    @Test
    fun `读取 body 抛异常时不崩且给出理由`() {
        val r = ChatRequestTranslator.translate { throw java.io.IOException("连接断了") }

        assertTrue(r is ChatRequestTranslator.TranslationResult.Invalid)
        assertTrue((r as ChatRequestTranslator.TranslationResult.Invalid).reason.isNotBlank())
    }

    @Test
    fun `成功时没有理由字段`() {
        // 类型上是 sealed 的两态，这条只是确认 Ok 不携带 reason
        val r = json("""{"messages":[{"role":"user","content":"x"}]}""")
        assertNull((r as? ChatRequestTranslator.TranslationResult.Invalid)?.reason)
    }

    @Test
    fun `assistant 带 tool_calls 时能翻译`() {
        val req = ok(
            """
            {"messages":[
              {"role":"assistant","content":null,
               "tool_calls":[{"id":"c1","type":"function",
                 "function":{"name":"click","arguments":"{}"}}]}
            ]}
            """.trimIndent()
        )

        assertEquals(ChatMessage.Role.ASSISTANT, req.messages[0].role)
        assertEquals(emptyList<ContentPart>(), req.messages[0].content)
    }
}
