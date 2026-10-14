package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一轮对话（L0 原始层）。
 *
 * ⚠️ **本表本身就是隐私面**：它存着用户说过的每一句话。
 *    落盘前必须过 `MemoryGate`（既有红线，见 `docs/AI手机助理架构-v1.0.md` §8.2-2）。
 *
 * ⚠️ `ON DELETE CASCADE` **必须真生效**，否则删了会话、轮次还躺在库里 ——
 *    用户以为删干净了，而磁盘上留着他说的每一句话。
 *    **这是隐私问题，不是整洁问题。**
 *    ⇒ 除了这里声明外键，还要在 `PocketAgentDatabase` 的 `onOpen` 里开
 *      `PRAGMA foreign_keys = ON`（SQLite 默认是**关**的）。
 *      一个没被强制执行的外键比没有外键更糟 —— 它看起来像个保证。
 */
@Entity(
    tableName = "turn",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId", "at"),
        Index("parentTurnId"),
    ],
)
data class TurnEntity(
    @PrimaryKey val id: String,

    val sessionId: String,

    /** `USER` | `ASSISTANT` | `TOOL`。字面量，理由同 `SessionEntity.titleSource` */
    val role: String,

    val text: String,

    val at: Long,

    /** 可空：算不出来就是 null。⚠️ 不填 0 —— 0 是一个"确知为零"的断言 */
    val tokenCount: Int?,

    /**
     * 父轮次。null = 这条是根（或来自扁平结构的老数据）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 为什么这一列**现在**就要有（而不是等做分支 UI 时再加）
     * ═══════════════════════════════════════════════════════════════
     *
     * 加一个**可空**的父指针，代价是零。
     * 而**事后**再改造是**做不到**的：
     *
     * > 扁平结构里，「这两条是彼此的替代品」这个信息**从来没有被记录过**。
     * > 事后只能靠时间戳挨着猜 —— 猜错就是把用户的两条独立发言当成一对替代品，
     * > 然后其中一条从当前路径里消失。**那是静默的数据损坏。**
     *
     * ⚠️ **刻意不声明自引用外键**（设计稿 §3.5 里写了 `REFERENCES turn(id)`，这里偏离它）：
     *    自引用 + `ON DELETE CASCADE` 会让"删一个轮次"**级联删掉整个子树** ——
     *    而 §3.5 明确要求「**旧分支不删**，用户随时能切回去对比」。
     *    真正需要的是"**会话**删除时整棵树跟着走"，那个由 `sessionId` 的外键覆盖。
     *    ⇒ 自引用外键在这里**语义是错的**，不是省事。
     */
    val parentTurnId: String?,

    /**
     * 是否被"重新生成 / 改写"取代。
     *
     * ★ 它决定**这一轮进不进记忆**（设计稿 §3.5 最容易做错、也最贵的一点）：
     *   - 被放弃的**回答**不进 L1 —— 记下来会让助理下次答得更像那次
     *   - 但被放弃的**提问**要进（降权）—— 用户**真的问过**，那是他的真实意图。
     *     **「问过」是事实，「答错」不是用户的属性。**
     */
    val superseded: Boolean,
)
