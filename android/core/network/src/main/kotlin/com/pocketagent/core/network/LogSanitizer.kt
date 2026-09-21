package com.pocketagent.core.network

/**
 * 日志脱敏器。
 *
 * ⚠️ 这是**安全红线组件**：任何日志输出（Timber / Logcat / 崩溃上报 / 本地日志文件）
 *    都必须经过本类过滤。绕过它的日志写入视为缺陷。
 *
 * 设计要点：
 * 1. 用**正则模式匹配**而非关键词匹配，覆盖各厂商 Key 格式
 * 2. 脱敏后保留前 4 位与长度，便于排查「是哪个 Key 出错」而不泄漏内容
 * 3. 除了 Key，还要处理手机号、身份证、银行卡、邮箱等 PII
 * 4. 对超长字符串做截断，防止把整张截图的 base64 写进日志
 */
object LogSanitizer {

    /** 各厂商 API Key 的特征模式 */
    private val KEY_PATTERNS: List<Regex> = listOf(
        // OpenAI / DeepSeek / Moonshot / 硅基流动 等：sk- 前缀
        Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
        // Anthropic: sk-ant-api03-...
        Regex("""sk-ant-[A-Za-z0-9_\-]{16,}"""),
        // Google: AIza...
        Regex("""AIza[A-Za-z0-9_\-]{30,}"""),
        // 火山方舟 / 通用 UUID 风格
        Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""),
        // 通用 Bearer token
        Regex("""(?i)bearer\s+[A-Za-z0-9_\-\.]{20,}"""),
        // 通用 x-api-key
        Regex("""(?i)x-api-key["'\s:]+[A-Za-z0-9_\-\.]{20,}"""),
        // 长 hex 串（部分国内厂商）
        Regex("""\b[0-9a-f]{32,64}\b"""),
    )

    /** PII 模式 */
    private val PII_PATTERNS: List<Pair<Regex, String>> = listOf(
        // 中国大陆手机号
        Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""") to "手机号",
        // 身份证（18 位）
        Regex("""(?<!\d)\d{17}[\dXx](?!\d)""") to "身份证",
        // 银行卡（16-19 位，允许空格/短横线分隔）
        Regex("""(?<!\d)\d{4}[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4,7}(?!\d)""") to "银行卡",
        // 邮箱
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "邮箱",
    )

    /** 单个日志消息的最大长度，超出截断。防止 base64 图片/长 JSON 撑爆日志 */
    private const val MAX_LOG_LENGTH = 2_000

    /** base64 图片数据的特征（data URI 或裸 base64 长串） */
    private val BASE64_IMAGE = Regex("""data:image/[a-z]+;base64,[A-Za-z0-9+/=]{100,}""")
    private val RAW_BASE64 = Regex("""[A-Za-z0-9+/]{200,}={0,2}""")

    /**
     * 对任意日志文本脱敏。
     *
     * 调用方式：
     * ```kotlin
     * Timber.d(LogSanitizer.sanitize("request body: $body"))
     * ```
     * 或直接使用 [SanitizingTree]，把脱敏变成日志框架的内建行为。
     */
    fun sanitize(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""

        var result = input

        // 1. 先干掉 base64 图片（最长、最危险）
        result = BASE64_IMAGE.replace(result, "[IMAGE_DATA_REDACTED]")
        result = RAW_BASE64.replace(result, "[LONG_BASE64_REDACTED]")

        // 2. 脱敏 API Key
        for (pattern in KEY_PATTERNS) {
            result = pattern.replace(result) { match ->
                maskSecret(match.value)
            }
        }

        // 3. 脱敏 PII
        for ((pattern, label) in PII_PATTERNS) {
            result = pattern.replace(result) { match ->
                "[$label:${match.value.length}位]"
            }
        }

        // 4. 截断
        if (result.length > MAX_LOG_LENGTH) {
            result = result.take(MAX_LOG_LENGTH) + "...[TRUNCATED ${result.length - MAX_LOG_LENGTH} chars]"
        }

        return result
    }

    /**
     * 掩码：保留前 4 位与总长度，形如 `sk-a****(48位)`。
     * 保留前缀是为了让开发者能判断「用的是哪个 Key」，但不泄漏可用于鉴权的信息。
     */
    private fun maskSecret(secret: String): String {
        val prefix = secret.take(4)
        return "$prefix****(${secret.length}位)"
    }

    /**
     * 对 HTTP 请求头做脱敏。专门用于 OkHttp 的日志拦截器 —— 那里是最容易泄漏 Key 的地方。
     */
    fun sanitizeHeaders(headers: Map<String, List<String>>): String = buildString {
        headers.forEach { (name, values) ->
            val isSensitive = name.lowercase() in SENSITIVE_HEADERS
            append(name).append(": ")
            append(if (isSensitive) maskSecret(values.joinToString(",")) else values.joinToString(","))
            append('\n')
        }
    }.trimEnd()

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "x-api-key", "api-key", "x-goog-api-key",
        "proxy-authorization", "cookie", "set-cookie",
    )
}
