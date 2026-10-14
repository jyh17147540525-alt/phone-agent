package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketagent.core.database.entity.TurnEntity

/**
 * L0 原始轮次的读写。
 *
 * ⚠️ 本表的每一行都是**用户说过的原话**。写入前必须过 `MemoryGate`。
 */
@Dao
interface TurnDao {

    /**
     * 插入一轮。
     *
     * ⚠️ `ABORT` 而不是 `REPLACE`：轮次 id 由调用方生成，撞号说明**调用方有 bug**
     *    （比如拿时间戳当 id 而两次调用落在同一毫秒）。
     *    静默覆盖会让用户**丢掉一句话**，而界面上完全看不出来。
     *    ⇒ 宁可抛，也不要静默丢。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(turn: TurnEntity)

    /**
     * 一个会话的**全部**轮次，按时间升序。
     *
     * ⚠️ 刻意返回**全部**（含 `superseded`），不做过滤 ——
     *    "哪些属于当前路径"由调用方沿 `parentTurnId` 上溯决定。
     *    在 SQL 里过滤会把树形结构拍平成链，**那正是设计稿 §3.5 警告的事**。
     */
    @Query("SELECT * FROM turn WHERE sessionId = :sessionId ORDER BY at ASC")
    suspend fun listBySession(sessionId: String): List<TurnEntity>

    /**
     * 最近 N 轮（给模型看的上下文窗口）。
     *
     * ⚠️ 取**最近的** N 条：先按时间倒序 LIMIT，再在调用方反转。
     *    直接升序 LIMIT 会拿到**最早**的 N 条 —— 那是"从头开始讲"，不是"接着聊"。
     */
    @Query("SELECT * FROM turn WHERE sessionId = :sessionId ORDER BY at DESC LIMIT :limit")
    suspend fun recent(sessionId: String, limit: Int): List<TurnEntity>

    @Query("SELECT COUNT(*) FROM turn WHERE sessionId = :sessionId")
    suspend fun countOf(sessionId: String): Int

    @Query("DELETE FROM turn WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
