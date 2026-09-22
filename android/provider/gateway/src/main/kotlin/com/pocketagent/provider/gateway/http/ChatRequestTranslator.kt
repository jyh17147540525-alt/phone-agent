package com.pocketagent.provider.gateway.http

import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * 协议请求 → `ChatRequest` 的翻译（BYOK §4.2 的 `ChatCompletionsHandler`）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 翻译失败必须**可解释**，不能是一个 null
 * ═══════════════════════════════════════════════════════════════
 *
 * 返回 [TranslationResult] 而不是 `ChatRequest?` —— 因为"为什么翻译不了"
 * 需要变成一条 400 响应体给客户端看（dsh 的日志里会出现它，用户能看到）。
 * 只回 null 的话，服务端只能发一句笼统的"bad request"，
 * 而写 dsh 插件的人完全不知道自己做错了什么。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它刻意不看 `model`
 * ═══════════════════════════════════════════════════════════════
 *
 * [GatewayChatRequest.model] 原样透传给 `ChatRequest.model`，
 * 但 `GatewayCore` 会用**路由结果覆盖**它 —— 见那个类的类注释。
 * 这里不做过滤，因为"谁负责忽略它"应当只有一个地方
 * （`GatewayCore`），两处都管会出现"改了这边忘了那边"。
 */
object ChatRequestTranslator {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    /** 翻译结果 */
    sealed interface TranslationResult {
        data class Ok(val request: ChatRequest) : TranslationResult

        /**
         * @param reason 给客户端看的说明。**不得包含请求体内容** ——
         *               请求体里可能有用户的隐私文本（截图 base64 更敏感）。
         */
        data class Invalid(val reason: String) : TranslationResult
    }

    /**
     * 解析网关请求体。
     *
     * @param bodyReader 惰性取 body 的字符串。**注册成函数而不是直接传 String**
     *                   是为了让调用方能先做大小上限检查再真正读进内存 ——
     *                   一个伪造的 200MB body 不该被读进来。
     */
    fun translate(bodyReader: () -> String): TranslationResult {
        val body = try {
            bodyReader()
        } catch (e: Exception) {
            return TranslationResult.Invalid("读取请求体失败：${e::class.simpleName}")
        }

        val parsed = try {
            json.decodeFromString(GatewayChatRequest.serializer(), body)
        } catch (e: Exception) {
            // ⚠️ 只说"JSON 解析失败"，**不回显异常 message** ——
            //    序列化库的报错有时会带上下文片段（含用户文本）。
            return TranslationResult.Invalid("请求体不是合法的 JSON 对象")
        }

        return translate(parsed)
    }

    /** 已反序列化的版本（测试与内部直通用） */
    fun translate(parsed: GatewayChatRequest): TranslationResult {
        if (parsed.messages.isEmpty()) {
            // ⚠️ 空 messages 是**明确错误**，不是"空对话"。
            //    放行的话会发一个空请求给上游，而厂商的报错千奇百怪
            //    （有的 400、有的返回空回答、个别厂商会直接扣费）。
            return TranslationResult.Invalid("messages 不能为空")
        }

        val messages = mutableListOf<ChatMessage>()
        for ((index, raw) in parsed.messages.withIndex()) {
            val role = parseRole(raw.role)
                // ⚠️ 报位置（第几条）—— 多轮对话里这是唯一能让调用方
                //    定位到具体消息的信息。只报"role 非法"等于没说。
                ?: return TranslationResult.Invalid(
                    "第 ${index + 1} 条消息的 role 无法识别：${raw.role}"
                )

            val parts = try {
                parseContent(raw.content)
            } catch (e: IllegalArgumentException) {
                return TranslationResult.Invalid("第 ${index + 1} 条消息的内容无法解析：${e.message}")
            }

            messages += ChatMessage(
                role = role,
                content = parts,
                name = raw.name,
                toolCallId = raw.toolCallId,
            )
        }

        val tools = parsed.tools.orEmpty().mapNotNull { raw ->
            val fn = raw.function ?: return@mapNotNull null
            if (fn.name.isEmpty()) return@mapNotNull null
            ToolSpec(
                name = fn.name,
                description = fn.description,
                // 上游要的是 JSON Schema 字符串
                parametersJsonSchema = fn.parameters?.toString() ?: EMPTY_SCHEMA,
            )
        }

        return TranslationResult.Ok(
            ChatRequest(
                // 照抄 —— 由 GatewayCore 覆盖，见类注释
                model = parsed.model,
                messages = messages,
                tools = tools,
                temperature = parsed.temperature,
                maxTokens = parsed.effectiveMaxTokens,
                jsonMode = parsed.wantsJsonMode,
            )
        )
    }

    /**
     * role 字符串 → 枚举。
     *
     * ⚠️ **穷举 `when` + 显式映射，不用 `valueOf`** ——
     *    这与项目既有立场一致：`valueOf` 在枚举改名时编译通过、
     *    运行时抛异常，而那个异常发生在请求处理路径上，
     *    对用户来说是 500（而不是可读的 400）。
     *
     * ⚠️ `"developer"` 是较新的 OpenAI 角色（替代 system），
     *    归到 SYSTEM。漏了它的表现是"用新 SDK 的客户端全部 400"。
     *
     * @return null = 无法识别
     */
    private fun parseRole(raw: String): ChatMessage.Role? = when (raw.lowercase()) {
        "system", "developer" -> ChatMessage.Role.SYSTEM
        "user" -> ChatMessage.Role.USER
        "assistant" -> ChatMessage.Role.ASSISTANT
        "tool", "function" -> ChatMessage.Role.TOOL
        else -> null
    }

    /**
     * content 字段 → [ContentPart] 列表。
     *
     * 两种形态：
     * - `"纯文本"` → 一个 [ContentPart.Text]
     * - `[{"type":"text",...},{"type":"image_url",...}]` → 多模态
     *
     * ⚠️ `null` content 是**合法**的（带 tool_calls 的 assistant 消息），
     *    返回空列表而不是报错。
     */
    private fun parseContent(element: kotlinx.serialization.json.JsonElement?): List<ContentPart> {
        if (element == null) return emptyList()

        return when (element) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw IllegalArgumentException("content 既不是字符串也不是数组")
                }
                listOf(ContentPart.Text(element.content))
            }

            is JsonArray -> element.flatMap { part -> parseMultiModalPart(part) }

            is JsonObject -> {
                // 个别客户端把单个 content part 直接发成对象（不套数组）
                parseMultiModalPart(element)
            }
        }
    }

    private fun parseMultiModalPart(
        element: kotlinx.serialization.json.JsonElement,
    ): List<ContentPart> {
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("content 数组的元素不是对象")

        return when (obj["type"]?.let { (it as? JsonPrimitive)?.content }) {
            "text" -> {
                val text = (obj["text"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("text 类型的片段缺少 text 字段")
                listOf(ContentPart.Text(text))
            }

            "image_url" -> {
                val url = ((obj["image_url"] as? JsonObject)?.get("url") as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("image_url 类型的片段缺少 image_url.url")
                listOf(parseImageUrl(url))
            }

            // ⚠️ **未知类型跳过而不是报错** —— 协议在演进
            //    （`input_audio` / `file` 等），因为我们不认识就整个拒绝
            //    会让新客户端完全不能用。跳过是"忽略这个模态"，
            //    而模型至少还能看到其余内容。
            else -> emptyList()
        }
    }

    /**
     * data URI → 原始字节。
     *
     * ⚠️ 只接受 `data:` 形式的 base64。**http(s) URL 形式直接跳过** ——
     *    让 Provider 去下载一个远程图片会：① 把用户的 IP 暴露给图床
     *    ② 引入一个我们控制不了的网络依赖与超时 ③ 可能是 SSRF 的入口。
     *    文档里记过"部分厂商只接受 http URL"，那是**厂商侧**的限制，
     *    不是我们该在网关里放开 URL 的理由。
     */
    private fun parseImageUrl(url: String): ContentPart {
        if (!url.startsWith(DATA_URI_PREFIX)) return SKIPPED_IMAGE

        val comma = url.indexOf(',')
        if (comma < 0) return SKIPPED_IMAGE

        val meta = url.substring(DATA_URI_PREFIX.length, comma)
        if (!meta.contains("base64", ignoreCase = true)) return SKIPPED_IMAGE

        val mime = meta.substringBefore(';').ifEmpty { "image/webp" }

        val bytes = try {
            Base64.getDecoder().decode(url.substring(comma + 1))
        } catch (e: IllegalArgumentException) {
            return SKIPPED_IMAGE
        }

        if (bytes.isEmpty()) return SKIPPED_IMAGE

        return ContentPart.Image(bytes = bytes, mimeType = mime)
    }

    private const val DATA_URI_PREFIX = "data:"

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""

    /**
     * 被跳过的图片的占位。
     *
     * ⚠️ 用**空字节数组**而不是"不加入列表"：
     *    后者会让 `estimateInputTokens` 少算 1000 token，
     *    而那张图片其实存在于请求里 —— 于是预算估低了，
     *    正是"估低 → 安静地多花钱"那个方向。
     *    占位的字节数组是空的，所以不会被误当成真实图片处理。
     */
    private val SKIPPED_IMAGE = ContentPart.Image(bytes = ByteArray(0), mimeType = "image/unknown")
}
