package com.pocketagent.keymgmt

import com.pocketagent.core.database.dao.UsageDao
import com.pocketagent.core.database.entity.UsageRecordEntity
import com.pocketagent.provider.gateway.Consumer
import com.pocketagent.provider.gateway.UsageRecord
import com.pocketagent.provider.gateway.UsageRecorder
import timber.log.Timber

/**
 * [UsageRecorder] 的真实实现 —— 把用量写进 Room。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它必须**永不抛异常**（这是本类唯一真正的难点）
 * ═══════════════════════════════════════════════════════════════
 *
 * 契约里写了这一条，而它在实现里容易被当成"防御性编程"而忽略。
 * 它不是。网关调用 [record] 的位置是**流的 `onCompletion` 回调里**：
 *
 * ```
 *   provider.chat(...)
 *       .onCompletion { usageRecorder.record(...) }   // ← 这里
 * ```
 *
 * `onCompletion` 里抛出的异常会**取代**原本的完成原因。后果非常隐蔽：
 *
 * - 用户点了取消 → 取消异常被"记账失败"异常盖掉 → 上层看到的是
 *   "写入数据库失败"，而它其实与用户的操作毫无关系
 * - 一次成功的对话 → 被一个失败的 INSERT 变成"调用失败" →
 *   用户看到红字，但答案明明已经完整地流出来了
 *
 * 所以这里**吞掉所有异常**，只留一条日志。代价是"账本可能缺一条"，
 * 而那远比"一次成功的对话被报成失败"轻。
 *
 * ⚠️ 写日志时**不打 record 的完整内容** —— `UsageRecord` 里虽然没有
 *    请求正文，但它的 `warning`/`failure` 字段是从上游链路上来的字符串。
 *    只打 id 与异常类型，够定位问题。
 */
class RoomUsageRecorder(
    private val dao: UsageDao,
    /** 时间源。抽出来是为了单测能固定时间 */
    private val clock: () -> Long = System::currentTimeMillis,
) : UsageRecorder {

    override suspend fun record(record: UsageRecord) {
        try {
            dao.insert(record.toEntity(clock()))
        } catch (e: Exception) {
            // ★ 见类注释：这里**必须**吞掉。让记账失败打断对话，
            //   是把一个次要功能的故障升级成主要功能的故障。
            Timber.w(
                e,
                "用量记账失败（model=%s, consumer=%s, type=%s）",
                record.modelConfigId,
                record.consumer,
                e::class.simpleName,
            )
        }
    }
}

/**
 * 领域模型 → 数据库实体。
 *
 * ⚠️ 把上游 usage 的两列**分别**映射，不要合并成一个字段 ——
 *    `upstreamUsage == null`（上游没给）与 `upstreamUsage!!.inputTokens == 0`
 *    （上游说输入是 0 个 token）是**不同的信息**。合并之后统计出来的
 *    "估算偏差"会把"没数据"当成"估多了"，从而得出反向的校准结论。
 */
internal fun UsageRecord.toEntity(createdAtMillis: Long) = UsageRecordEntity(
    modelConfigId = modelConfigId,
    providerId = providerId,
    // ★ 存枚举**名**而不是 ordinal —— ordinal 会在枚举成员重排后
    //   把历史记录的含义整体错位，且编译期无任何提示。
    consumer = consumer.name,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    upstreamInputTokens = upstreamUsage?.inputTokens,
    upstreamOutputTokens = upstreamUsage?.outputTokens,
    failure = failure,
    warning = warning,
    createdAtMillis = createdAtMillis,
)

/**
 * 数据库实体 → 领域模型（用量页读取时用）。
 *
 * ⚠️ `consumer` 的解析**必须容错**：库里存的是枚举名，而枚举将来可能
 *    改名或删成员。用 `valueOf` 会在那种情况下直接抛异常 ——
 *    表现是"升级应用后用量页打不开"，而用户完全不知道是历史数据的问题。
 *    所以退回到 [Consumer.AgentLoop]，并在日志里留一条。
 */
internal fun UsageRecordEntity.toDomain(): UsageSnapshot = UsageSnapshot(
    id = id,
    modelConfigId = modelConfigId,
    providerId = providerId,
    consumer = runCatching { Consumer.valueOf(consumer) }
        .getOrElse {
            Timber.w("用量记录 #%d 的 consumer 值「%s」无法识别，按任务执行归类", id, consumer)
            Consumer.AgentLoop
        },
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    upstreamInputTokens = upstreamInputTokens,
    upstreamOutputTokens = upstreamOutputTokens,
    failure = failure,
    warning = warning,
    createdAtMillis = createdAtMillis,
)

/**
 * 用量页展示用的一条记录。
 *
 * ⚠️ 与 `GatewayCore` 的 `UsageRecord` **刻意分开**：那个是"要写进去的"
 *    （含 `TokenUsage` 对象、`Consumer` 枚举），这个是"读出来的"
 *    （上游用量已拍平成两列、id 是数据库给的）。合并成一个类的话，
 *    写路径要为 `id` 编一个假值，而那个假值迟早会有人当真。
 */
data class UsageSnapshot(
    val id: Long,
    val modelConfigId: String,
    val providerId: String,
    val consumer: Consumer,
    val inputTokens: Int,
    val outputTokens: Int,
    val upstreamInputTokens: Int?,
    val upstreamOutputTokens: Int?,
    val failure: String?,
    val warning: String?,
    val createdAtMillis: Long,
)
