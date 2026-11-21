package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个**会话** —— 侧边栏列表的主单位。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是「会话」而不是「任务」
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前 `AgentTaskUi` 是「一条消息 = 一张卡」，而且**只在内存里**
 * （`TasksViewModel.tasks = mutableStateListOf`）—— 进程一死全没。
 *
 * 直接拿它做历史列表，用户会看到几百张 "hi"。
 * 而 `:memorylogic` 的 `Turn.sessionId` **早就存在**，注释写着
 * 「跨会话的区分（以及「上次我们说到」）靠它」
 * ⇒ **记忆层一直在等一个会话概念，UI 层从来没给过它。**
 *
 * 本表就是补上那层。设计依据见 `docs/会话侧边栏与全局记忆设计-v1.0.md` §2/§3。
 *
 * ⚠️ 会话边界 = **用户主动开新对话** 或 **空闲 30 分钟**。
 *    刻意不做"话题检测" —— 它要么花一次 LLM 调用，要么不准，两个代价都不值得。
 */
@Entity(
    tableName = "session",
    indices = [
        // 侧边栏的排序查询：未归档 → 置顶优先 → 最近活动优先
        Index("archived", "pinned", "lastMessageAt"),
    ],
)
data class SessionEntity(
    /** UUID。不用自增 —— 理由同 `CredentialEntity`（跨设备导入时不撞号） */
    @PrimaryKey val id: String,

    /** 展示标题。自动生成或用户改名，见 [titleSource] */
    val title: String,

    /**
     * 标题来源：`AUTO` | `USER`。
     *
     * ⚠️ 用**字面量**而不是枚举 `.name` —— 迁移 SQL 必须**冻结在写它那刻**，
     *    枚举改名不该让已发出的 SQL 变错（这类错误编译期看不出来）。
     *
     * ★ `USER` 之后**永不自动覆盖**：用户改过的名字被自动改回去，
     *   比不改更让人恼火 —— 而且他不会认为这是 bug，他会认为"这 App 记不住事"。
     */
    val titleSource: String,

    val createdAt: Long,

    /** 任意字段变更的时间。⚠️ 改名**不该**让它跳到最前 —— 排序用 [lastMessageAt] */
    val updatedAt: Long,

    /** 排序键：最后一次**发言**的时间 */
    val lastMessageAt: Long,

    val turnCount: Int,

    val pinned: Boolean,

    val archived: Boolean,

    /**
     * 当前路径的头（叶子轮次 id）。见 [TurnEntity.parentTurnId]。
     *
     * ⚠️ 可空：空表示"还没走过分支"，此时当前路径 = 全部轮次按时间顺序。
     *    **它让"切换分支"变成改一个字段**，不动任何 turn。
     */
    val activeLeafTurnId: String?,

    /**
     * 这个会话属于哪个**模式**：`ROLEPLAY`（和角色聊天）或 `WORK`（工作台）。
     *
     * ★ 2026-11-20 用户投诉："工作台里和角色聊天的历史全混在一起"。
     *   根因是这张表**根本没有模式概念** —— 两个入口共用一份会话列表，
     *   于是工作台会列出跟角色说的情话，聊天页会列出让它干的活。
     *
     * ⚠️ 用字面量而不是枚举 `.name`，理由同 [titleSource]：迁移 SQL 必须冻结。
     * ⚠️ 默认 `ROLEPLAY`：老库里的会话都是跟角色聊出来的（工作台是后来的），
     *   迁移时把历史全划给聊天侧，比"全划给工作台"更接近事实。
     */
    val mode: String = MODE_ROLEPLAY,
) {
    companion object {
        const val MODE_ROLEPLAY = "ROLEPLAY"
        const val MODE_WORK = "WORK"

        /**
         * 在场模式（「（昵称）的窗」）。
         *
         * ★ 2026-11-21：以前在场没有自己的值，被当成 WORK —— 于是工作台和在场
         *   共用一本书，两边都在往同一个抽屉里塞记录。
         */
        const val MODE_LIFEFORM = "LIFEFORM"
    }
}
