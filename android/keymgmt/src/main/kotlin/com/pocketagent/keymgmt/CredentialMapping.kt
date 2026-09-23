package com.pocketagent.keymgmt

import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialEntity
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.ProviderException

/**
 * 纯映射与校验。
 *
 * 单独成文件是为了**能被离线单测覆盖** —— 这里的每个函数都不碰 Android、
 * 不碰 IO，输入输出都是纯数据。而它们恰好是"看起来对、实际会错"的高发区：
 * 校验结论的文案、长度的边界、状态到布尔值的推导，都属于
 * **不报错、不崩溃，只是安静地做错事**那一类问题。
 */

// ═══════════════════════════════════════════════════════════════
//  实体 → 领域模型
// ═══════════════════════════════════════════════════════════════

/**
 * @param providerDisplayName 由调用方从 Provider 注册表解析后传入。
 *        刻意不在这里查表 —— 那样这个函数就得依赖 Provider 列表，
 *        也就无法在不知道任何 Provider 的情况下被测试。
 */
internal fun CredentialEntity.toDomain(providerDisplayName: String): StoredCredential =
    StoredCredential(
        id = id,
        providerId = providerId,
        purpose = purpose,
        providerDisplayName = providerDisplayName,
        label = label,
        keyLength = keyLength,
        baseUrlOverride = baseUrlOverride,
        createdAtMillis = createdAtMillis,
        lastCheckedAtMillis = lastCheckedAtMillis,
        // 数据库里 null 表示"从未校验"。领域模型里把它显式化成 UNCHECKED，
        // 免得每个使用方都要处理一次 null。
        status = lastStatus ?: CredentialCheckStatus.UNCHECKED,
        statusDetail = lastStatusDetail,
        modelCount = modelCount,
        isDefault = isDefault,
    )

// ═══════════════════════════════════════════════════════════════
//  校验结论 → 状态 + 给用户看的一句话
// ═══════════════════════════════════════════════════════════════

/**
 * ⚠️ 文案里最重要的是把 **`UNREACHABLE` 与 `INVALID` 说清楚是两回事**。
 *
 *    用户看到"Key 无效"的第一反应是删掉重装。如果真实原因只是网络不通，
 *    他会删掉一个完好的 Key，然后重装也还是不行 —— 问题从来不在 Key 上，
 *    但界面上所有线索都在指向 Key。这是个用户无法自救的困境。
 */
internal fun KeyValidationResult.toOutcome(): ValidationOutcome = when (this) {
    is KeyValidationResult.Valid -> ValidationOutcome(
        status = CredentialCheckStatus.VALID,
        detail = "可用，探测到 $modelCount 个模型（耗时 ${latencyMs}ms）",
        modelCount = modelCount,
    )

    is KeyValidationResult.Invalid -> ValidationOutcome(
        status = CredentialCheckStatus.INVALID,
        detail = "服务商拒绝了这个 Key。确认没有复制错，或者它是否已经被吊销。",
    )

    is KeyValidationResult.NoBalance -> ValidationOutcome(
        status = CredentialCheckStatus.NO_BALANCE,
        // 服务商给了具体说明就用它的 —— 那通常比我们的通用文案更准确
        detail = message?.takeIf { it.isNotBlank() }
            ?: "Key 本身有效，但这个账户没有可用余额了。去充值即可，不用换 Key。",
    )

    is KeyValidationResult.RateLimited -> ValidationOutcome(
        status = CredentialCheckStatus.RATE_LIMITED,
        detail = retryAfterSeconds?.let { "请求太频繁，服务商要求 $it 秒后重试。" }
            ?: "请求太频繁，被服务商暂时限流了。等一会儿再试。",
    )

    is KeyValidationResult.Unreachable -> ValidationOutcome(
        status = CredentialCheckStatus.UNREACHABLE,
        detail = "连不上服务商：$reason。「这不代表 Key 有问题」—— " +
            "检查一下网络或代理，稍后重新校验。",
    )
}

// ═══════════════════════════════════════════════════════════════
//  异常 → 校验结论
// ═══════════════════════════════════════════════════════════════

/**
 * `ProviderException` 的兜底映射。
 *
 * [LlmProvider.validateKey] 的契约说它应该返回 [KeyValidationResult]，
 * 但同一个契约也允许它在网络层抛异常（`NetworkError` / `Timeout`）。
 * 一个实现上的疏漏不该让界面上出现一句用户看不懂的堆栈。
 *
 * ⚠️ 判断原则只有一条：**这个错误能不能证明 Key 本身是坏的？**
 *    只有 `AuthFailed` 能。其余全部归到"无法判定"，
 *    因为把"判定不了"说成"Key 无效"，会让用户去删一个完好的 Key。
 */
internal fun ProviderException.toOutcome(): ValidationOutcome = when (this) {
    is ProviderException.AuthFailed -> ValidationOutcome(
        status = CredentialCheckStatus.INVALID,
        detail = "服务商拒绝了这个 Key。确认没有复制错，或者它是否已经被吊销。",
    )

    is ProviderException.NoBalance -> ValidationOutcome(
        status = CredentialCheckStatus.NO_BALANCE,
        detail = "Key 本身有效，但这个账户没有可用余额了。去充值即可，不用换 Key。",
    )

    is ProviderException.RateLimited -> ValidationOutcome(
        status = CredentialCheckStatus.RATE_LIMITED,
        detail = retryAfterSeconds?.let { "请求太频繁，服务商要求 $it 秒后重试。" }
            ?: "请求太频繁，被服务商暂时限流了。等一会儿再试。",
    )

    is ProviderException.NetworkError -> ValidationOutcome(
        status = CredentialCheckStatus.UNREACHABLE,
        detail = "连不上服务商（${cause?.message ?: "网络错误"}）。" +
            "「这不代表 Key 有问题」—— 检查一下网络或代理，稍后重新校验。",
    )

    is ProviderException.Timeout -> ValidationOutcome(
        status = CredentialCheckStatus.UNREACHABLE,
        detail = "服务商没有在超时时间内响应。「这不代表 Key 有问题」，稍后重试。",
    )

    is ProviderException.ProtocolError -> ValidationOutcome(
        status = CredentialCheckStatus.UNREACHABLE,
        detail = "服务商的响应看不懂，可能它改了接口。「这不代表 Key 有问题」。" +
            "如果一直这样，请提交反馈。",
    )

    is ProviderException.ContentFiltered -> ValidationOutcome(
        status = CredentialCheckStatus.UNREACHABLE,
        detail = "校验请求被服务商的内容策略拦下了。稍后重试或换个服务商。",
    )
}

// ═══════════════════════════════════════════════════════════════
//  输入校验
// ═══════════════════════════════════════════════════════════════

/**
 * 输入合法性检查。返回 null 表示通过，否则返回给用户看的原因。
 *
 * ⚠️ 这里只做**格式自检**，不做"这个 Key 是不是真的有效"的判断 ——
 *    后者要发网络请求，属于 [CredentialRepository.validate] 的职责。
 *    两件事混在一起会让"网络不通"表现为"格式不对"，用户会去改一个
 *    本来就写对的 Key。
 */
internal object CredentialInput {

    /**
     * 已知最短的 API Key 长度。
     *
     * 没有哪个主流服务商的 Key 短于 8 个字符。这个下限的作用不是"安全"，
     * 而是拦住最常见的一类错误：粘贴时只复制到一小段。
     * 定得太高（比如 32）会误伤自建端点上的短 token。
     */
    const val MIN_KEY_LENGTH = 8

    fun validateKey(raw: String): String? {
        val trimmed = raw.trim()

        if (trimmed.isEmpty()) return "Key 不能为空。"

        if (trimmed.length < MIN_KEY_LENGTH) {
            return "Key 只有 ${trimmed.length} 个字符，看起来不完整 —— " +
                "粘贴时可能只复制到了一部分。"
        }

        // 内部有空白几乎一定是粘贴事故（比如连着复制了两行，
        // 或者从网页上带进了换行）。让它在保存前就被拦住，
        // 而不是等发请求时收到一个 401 再回来排查。
        if (trimmed.any { it.isWhitespace() }) {
            return "Key 中间有空格或换行 —— 粘贴时可能多带了一段内容。"
        }

        return null
    }

    /**
     * 自定义 BaseUrl 的校验。
     *
     * 只在用户**填了**的时候才校验 —— 留空表示用 Provider 默认地址，
     * 那是正常情况，不是错误。
     *
     * 允许 http 是刻意的：本地模型（Ollama / LM Studio）就跑在
     * `http://127.0.0.1:xxxx`，强制 https 会让整个本地推理场景不可用。
     * 但**远程**地址必须是 https —— 明文 http 会让 Key 在链路上裸奔。
     */
    fun validateBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "地址要以 http:// 或 https:// 开头。"
        }

        if (lower.startsWith("http://") && !isLoopback(trimmed)) {
            return "远程地址必须用 https —— 明文 http 会让你的 Key 在网络上传送时被看到。" +
                "（只有本机地址可以用 http，比如本地跑的 Ollama。）"
        }

        return null
    }

    /**
     * 是不是指向本机。
     *
     * ⚠️ 只认 `127.0.0.1` / `localhost` / `[::1]` 这三种写法。
     *    不要试图去解析整个 URL —— 一个宽松的解析器会把
     *    `http://127.0.0.1.evil.com` 也判成本机地址，而那个域名
     *    完全由攻击者控制。宁可漏判（用户填了别的本机写法被拒），
     *    也不能误判（把外网地址当成本机放行）。
     */
    private fun isLoopback(url: String): Boolean {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        val hostPort = afterScheme.substringBefore('/')
        val host = when {
            hostPort.startsWith("[") -> hostPort.substringBefore(']') + "]"
            else -> hostPort.substringBefore(':')
        }
        return host.equals("127.0.0.1", ignoreCase = true) ||
            host.equals("localhost", ignoreCase = true) ||
            host == "[::1]"
    }
}
