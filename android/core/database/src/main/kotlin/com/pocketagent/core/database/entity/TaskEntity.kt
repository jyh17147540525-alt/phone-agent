package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次**执行**的记录（原 `AgentTaskUi`，从"列表主单位"降级为"会话内的明细"）。
 *
 * ⚠️ 它**不是**侧边栏的列表单位 —— 那是 [SessionEntity]。
 *    理由见设计稿 §2：拿"一条消息一张卡"做列表，用户会看到几百张 "hi"。
 *
 * ★ 保留它的理由：**执行记录有审计价值** ——
 *   轮次进度、感知档位、中止原因都在这里。纯聊天没有执行，也就没有本表的行。
 *
 * ⚠️ 用户能看到的"为什么停下"（`abortReason`）就在这一行里。
 *    2026-10-02 修过一次「失败时看不到原因」的 bug —— 那次是**界面**没渲染，
 *    而界面能渲染的前提是**这里存住了**。
 */
@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "createdAtMillis"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,

    val sessionId: String,

    /** 用户原话。**原样保留**，不做摘要 —— 它是审计的锚点 */
    val prompt: String,

    /** `Running` | `Done` | `Aborted` | `Failed`。字面量，理由同 `SessionEntity.titleSource` */
    val state: String,

    val step: Int,

    /** 当前提示文本。`Done` 时这里装的是**模型的答复**（2026-10-12 修的 bug）*/
    val note: String,

    /** 中止/失败的原因。⚠️ 只有**所有终止态**都写它，用户才不会看到"失败"两个字就没了下文 */
    val abortReason: String?,

    val perceptionTier: Int,

    val createdAtMillis: Long,

    val finishedAtMillis: Long?,
)
