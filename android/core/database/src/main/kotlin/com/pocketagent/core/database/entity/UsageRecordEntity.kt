package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次模型调用的用量记录。**只记账，不存任何请求/响应内容。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这个表里没有 prompt / completion 正文
 * ═══════════════════════════════════════════════════════════════
 *
 * 存正文看起来很有用（"用户可以回看历史对话"），但它与本项目的
 * **"数据不出设备 + 坚决不碰隐私内容"** 是同一件事的两面：
 *
 * - 屏幕内容会进 prompt（读屏结果、截图 base64）—— 那是用户**应用里**的
 *   内容，可能是聊天记录、支付页、验证码。存下来就等于我们在设备上
 *   建了一份隐私副本，而它还得跟着备份、跟着导出。
 * - 用户对我们的信任建立在"它只是转发，不留底"上。一旦留底，
 *   这个承诺就破了，而它破了之后用户**无法察觉**。
 *
 * 所以本表**只有元数据**：用哪个模型、多少 token、多少钱、成没成功。
 * 它足以回答"我这个月的钱花在哪了""为什么这个任务这么贵"，
 * 而回答不了"用户当时问了什么"。后者是刻意的能力缺失。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么 `inputTokens` 与 `upstreamInputTokens` 要分开存
 * ═══════════════════════════════════════════════════════════════
 *
 * [inputTokens] 是我们**估算**的（熔断要在解密前决策，只能估），
 * [upstreamInputTokens] 是上游**权威**给的。
 *
 * 两者都存才能回答一个问题：**我们的估算偏了多少？**
 * 而这不是"数据洁癖"：
 * - 估**低** → 熔断放行了实际超预算的请求 → 安静地多花钱，**不可见**
 * - 估**高** → 用户看到"超出上限"，可以调大上限 → 可见且可修
 *
 * 只有拿真实值对照，才能知道当前这个"1 token ≈ 1 字符"的保守假设
 * 是偏保守还是已经不够保守了。丢掉估算值就再也无法校准。
 */
@Entity(
    tableName = "usage_record",
    indices = [
        // 用量页按时间倒序翻（最高频查询）
        Index("createdAtMillis"),
        // "这个模型花了多少钱" —— 按模型聚合
        Index("modelConfigId"),
        // "任务执行 vs 深度会话各花了多少" —— 按消费方聚合
        Index("consumer"),
    ],
)
data class UsageRecordEntity(

    /** 自增主键。**这里允许自增** —— 它只在本机用，不会进日志或界面 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 路由选中的模型配置 id。用于按模型聚合花费 */
    val modelConfigId: String,

    /**
     * 实际调用的 Provider（`CredentialEntity.providerId`）。
     *
     * ⚠️ 与 [modelConfigId] 分开存 —— 同一个模型配置可以换绑到不同渠道的 Key
     *    （官方直连 vs 第三方中转），而两边的**计费口径与稳定性完全不同**。
     *    只存模型 id 的话，"为什么这个月贵了"会查不出是渠道换了。
     */
    val providerId: String,

    /** 谁在用（任务执行 / 深度会话 / 连接测试）。枚举名，不是 ordinal */
    val consumer: String,

    /** 我们**估算**的输入 token（熔断的依据） */
    val inputTokens: Int,

    /** 我们**估算**的输出 token。上游没给 usage 时为 0 */
    val outputTokens: Int,

    /** 上游给的权威输入 token；null = 上游没返回 usage */
    val upstreamInputTokens: Int?,

    /** 上游给的权威输出 token；null = 上游没返回 usage */
    val upstreamOutputTokens: Int?,

    /**
     * 失败原因（**已脱敏**，只有类型名）。
     *
     * ⚠️ 只允许存 `GatewayCore.describe()` 的产物。那个函数刻意
     *    只取异常类型、绝不透传 message —— 因为 `ProviderException`
     *    的 message 有时带请求片段，而这里的数据会被导出、被上报。
     *    **任何人都不要把原始异常 message 写进这一列。**
     */
    val failure: String?,

    /**
     * 预算警告（如"该模型未标价，熔断对它不生效"）。
     *
     * ⚠️ 必须落库。不存的话，"用户的预算对某个模型静默失效"这件事
     *    事后**完全无法追溯**，而它造成的损失是真金白银。
     */
    val warning: String?,

    val createdAtMillis: Long,
)
