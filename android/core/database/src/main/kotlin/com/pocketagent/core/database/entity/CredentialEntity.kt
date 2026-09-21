package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条用户提供的 API 凭据。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个表里**没有明文 Key**
 * ═══════════════════════════════════════════════════════════════
 *
 * [ciphertext] 与 [iv] 是 Key 明文经 AES-256-GCM 加密后的产物，
 * 主密钥由 Android Keystore 保管（TEE/StrongBox，永不出芯片）。
 * 整个数据库文件再由 SQLCipher 加密一层。
 *
 * 所以想拿到 Key 明文，需要同时突破：SQLCipher 口令 → Keystore 主密钥 → GCM 密文。
 * 而中间那一步在硬件里，**无法被软件手段提取**。
 *
 * ⚠️ **刻意不存"Key 的前四位/后四位"**。
 *
 *    很多产品会存一个 `sk-...a1b2` 的提示串方便用户辨认，但那是在库里
 *    留一段明文。本项目的取舍是：**用户靠自己起的名字辨认**（[label]），
 *    界面上只显示 [keyLength] 与校验状态。少一点便利，换"库里没有任何明文片段"。
 */
@Entity(
    tableName = "credential",
    indices = [
        Index("providerId"),
        // 「当前使用哪一个」是最高频查询（每次发请求都要），给它建索引
        Index("isDefault"),
    ],
)
data class CredentialEntity(

    /** UUID。不用自增主键 —— 它会被写进日志/界面，自增 ID 会泄漏"用户有几个 Key" */
    @PrimaryKey val id: String,

    /** 对应 `ProviderProfile.id` / `LlmProvider.id`，如 "deepseek" / "openrouter" */
    val providerId: String,

    /** 用户自己起的名字，如"主力 Key"。用来在列表里区分多个 Key */
    val label: String,

    /** AES-GCM 密文 */
    val ciphertext: ByteArray,

    /** 该次加密的随机 IV。不敏感，与密文同行存放 */
    val iv: ByteArray,

    /**
     * 明文长度（字符数）。
     *
     * 用途是**格式自检**：用户粘贴时少复制了一段，长度会明显不对，
     * 界面可以在发请求之前就提示。长度本身不是秘密。
     */
    val keyLength: Int,

    /** 用户覆盖的 BaseUrl；null 表示用 Provider 默认值（自定义/自建端点场景） */
    val baseUrlOverride: String?,

    val createdAtMillis: Long,

    /** 上次校验时间；null 表示从未校验过 */
    val lastCheckedAtMillis: Long?,

    /** 上次校验结论；null 表示从未校验过 */
    val lastStatus: CredentialCheckStatus?,

    /**
     * 校验结论的补充说明。
     *
     * 存它是因为「不可达」这一个状态对应很多种原因（DNS、超时、证书、
     * 公司网络拦截），而这些原因的处置方式完全不同。只存一个枚举，
     * 用户下次打开应用就看不到"当时到底怎么了"。
     */
    val lastStatusDetail: String?,

    /** 校验时探到的可用模型数 */
    val modelCount: Int?,

    /**
     * 是否为当前使用的凭据。
     *
     * 同一时刻**至多一个**为 true —— 这个不变量由仓储层在事务里保证，
     * 数据库层没有唯一约束可以表达"至多一行为 true"（部分索引在 SQLite 上
     * 写法别扭且 Room 不生成），所以放在仓储层守。
     */
    val isDefault: Boolean,
) {
    // data class 带 ByteArray 时自动生成的 equals/hashCode 是**引用比较**，
    // 会导致两个内容相同的实体被判为不等 —— 在测试里表现为"明明写进去了却查不出来"。
    // 这里改成内容比较，和 core-crypto 的 EncryptedBlob 保持一致的处理方式。
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CredentialEntity &&
                id == other.id &&
                providerId == other.providerId &&
                label == other.label &&
                ciphertext.contentEquals(other.ciphertext) &&
                iv.contentEquals(other.iv) &&
                keyLength == other.keyLength &&
                baseUrlOverride == other.baseUrlOverride &&
                createdAtMillis == other.createdAtMillis &&
                lastCheckedAtMillis == other.lastCheckedAtMillis &&
                lastStatus == other.lastStatus &&
                lastStatusDetail == other.lastStatusDetail &&
                modelCount == other.modelCount &&
                isDefault == other.isDefault
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + providerId.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + keyLength
        result = 31 * result + (baseUrlOverride?.hashCode() ?: 0)
        result = 31 * result + createdAtMillis.hashCode()
        result = 31 * result + (lastCheckedAtMillis?.hashCode() ?: 0)
        result = 31 * result + (lastStatus?.hashCode() ?: 0)
        result = 31 * result + (lastStatusDetail?.hashCode() ?: 0)
        result = 31 * result + (modelCount ?: 0)
        result = 31 * result + isDefault.hashCode()
        return result
    }

    /** 禁止把密文打进日志。默认的 data class toString 会打印整个 ByteArray 的地址+内容摘要 */
    override fun toString(): String =
        "CredentialEntity(id=$id, provider=$providerId, label=$label, " +
            "keyLength=$keyLength, isDefault=$isDefault, status=$lastStatus)"
}

/**
 * 一次 Key 校验的结论。
 *
 * ⚠️ **`UNREACHABLE` 必须与 `INVALID` 分开。**
 *
 *    这是 `LlmProvider.validateKey` 的契约里明确要求的一条（见 `provider/api`）：
 *    网络不通时若报"Key 无效"，用户会去删掉一个**本来好好的 Key**，
 *    然后发现重装也还是不行 —— 因为问题从来不在 Key 上。
 *    把这两种失败合并成一个状态，是在制造一个用户无法自救的困境。
 */
enum class CredentialCheckStatus {
    /** 还没校验过（刚添加，或校验被取消） */
    UNCHECKED,

    /** Key 可用 */
    VALID,

    /** Key 错误或已被吊销 —— 只能换一个 */
    INVALID,

    /** Key 有效但余额不足 —— 去充值，别删 Key */
    NO_BALANCE,

    /** Key 有效但被限流 —— 等一会儿再来 */
    RATE_LIMITED,

    /** 网络不可达 —— **不能据此判断 Key 好坏** */
    UNREACHABLE,
}
