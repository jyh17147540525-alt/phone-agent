package com.pocketagent.keymgmt

import com.pocketagent.core.database.entity.CredentialCheckStatus

/**
 * 用户提供的一条 API 凭据（界面视角）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个模型里**没有 Key**
 * ═══════════════════════════════════════════════════════════════
 *
 * 界面拿到的只有"有没有、多长、能不能用"，永远拿不到明文 ——
 * 明文只在一个地方出现：[CredentialRepository.withDefaultKey] 的作用域内，
 * 且用完立即清零。
 *
 * 这不是洁癖。`ProviderCredential` 的注释已经说明：明文一旦进了 JVM
 * 字符串池就**无法擦除**，内存 dump 可见。所以"能拿到明文的代码路径"
 * 越少越好，最好只有一条。
 */
data class StoredCredential(
    val id: String,

    /** 对应 `LlmProvider.id` / `ProviderProfile.id` */
    val providerId: String,

    /** Provider 的展示名，由仓储层从 Provider 注册表解析后填入 */
    val providerDisplayName: String,

    /** 用户起的名字。可能为空 —— 见 [effectiveLabel] */
    val label: String,

    /**
     * 明文长度（字符数）。
     *
     * 界面用它做**格式自检**：粘贴时少复制了一段，长度会明显不对，
     * 能在发请求之前就提示，而不是让用户对着一个"校验失败"发懵。
     * 长度不是秘密 —— 真正要防的是内容。
     */
    val keyLength: Int,

    val baseUrlOverride: String?,
    val createdAtMillis: Long,
    val lastCheckedAtMillis: Long?,
    val status: CredentialCheckStatus,
    val statusDetail: String?,
    val modelCount: Int?,
    val isDefault: Boolean,
) {
    /**
     * 界面上实际显示的名字。
     *
     * 用户没起名就退化成 Provider 名 —— 总比显示一行空白强，
     * 而且大多数用户只会配一个 Key，Provider 名足够辨认。
     */
    val effectiveLabel: String get() = label.ifBlank { providerDisplayName }

    /** 是否需要在界面上提醒用户去处理 */
    val needsAttention: Boolean
        get() = status == CredentialCheckStatus.INVALID ||
            status == CredentialCheckStatus.NO_BALANCE

    /** 是否可以拿它发请求 */
    val usable: Boolean
        get() = status == CredentialCheckStatus.VALID ||
            status == CredentialCheckStatus.UNCHECKED
}

/**
 * 一次校验的完整结论。
 *
 * [detail] 是给用户看的一句话，不是异常栈 —— 用户需要知道的是
 * "现在该干什么"（去充值 / 等一会儿 / 换个 Key），而不是错误码。
 */
data class ValidationOutcome(
    val status: CredentialCheckStatus,
    val detail: String? = null,
    val modelCount: Int? = null,
)

/**
 * 添加凭据的结果。
 *
 * ⚠️ 刻意**不把"校验失败"当作添加失败**。
 *
 *    很多产品会在添加时就卡住：Key 校验不通过就不让保存。但那会让用户
 *    在**网络不好的时候完全没法添加** —— 而网络问题跟 Key 好坏毫无关系。
 *
 *    本项目的选择是：**先存下来，校验结果单独反馈**。用户至少能看到
 *    "东西在这儿"，而不是反复重试一个永远失败的操作。
 */
sealed interface AddCredentialResult {
    data class Added(val credential: StoredCredential) : AddCredentialResult

    /** 输入不合法（空、太短、Provider 不认识）。这类问题重试无用，必须改输入 */
    data class Rejected(val reason: String) : AddCredentialResult

    /** 加密或落库失败。这类问题值得重试 */
    data class Failed(val reason: String) : AddCredentialResult
}
