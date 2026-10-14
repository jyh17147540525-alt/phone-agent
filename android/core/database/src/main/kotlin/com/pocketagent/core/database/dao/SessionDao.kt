package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pocketagent.core.database.entity.SessionEntity

/**
 * 会话表的读写。
 *
 * ⚠️ **删除走显式级联**（[deleteCascade]），不依赖外键的 `ON DELETE CASCADE`。
 *    原因：SQLite 的 `PRAGMA foreign_keys` **默认是关的** ——
 *    靠外键级联等于把"删干净"押在一个运行时开关上。
 *    外键仍然声明（DB 层的第二道保险），但**业务路径必须自己删**。
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Query(
        """
        UPDATE session SET
            title = :title,
            titleSource = :titleSource,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateTitle(id: String, title: String, titleSource: String, updatedAt: Long)

    @Query(
        """
        UPDATE session SET
            lastMessageAt = :lastMessageAt,
            updatedAt = :updatedAt,
            turnCount = turnCount + 1
        WHERE id = :id
        """,
    )
    suspend fun touchAfterTurn(id: String, lastMessageAt: Long, updatedAt: Long)

    @Query("UPDATE session SET activeLeafTurnId = :turnId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setActiveLeaf(id: String, turnId: String?, updatedAt: Long)

    @Query("UPDATE session SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE session SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAt: Long)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    /**
     * 侧边栏的主查询。
     *
     * ⚠️ 排序：未归档 → 置顶优先 → **最近发言**优先。
     *    用 `lastMessageAt` 而不是 `updatedAt` —— 后者会被"改名"推动，
     *    而**改个名字不该让会话跳到最前面**。
     */
    @Query(
        """
        SELECT * FROM session
        WHERE archived = 0
        ORDER BY pinned DESC, lastMessageAt DESC
        LIMIT :limit
        """,
    )
    suspend fun listActive(limit: Int): List<SessionEntity>

    @Query("SELECT * FROM session WHERE archived = 1 ORDER BY lastMessageAt DESC LIMIT :limit")
    suspend fun listArchived(limit: Int): List<SessionEntity>

    /**
     * 会话标题与正文的**子串搜索**。
     *
     * ⚠️ 刻意用 `LIKE` 而**不是** Room 的 FTS —— 中文分词器会坑人：
     *    `simple` 分词器按非字母数字切词 ⇒ 一整句中文变成**一个 token** ⇒
     *    搜「天气」搜不到「明天天气怎么样」。`unicode61` 同样不做 CJK 切分。
     *    子串匹配对中文天然可用。
     *    上 FTS 的触发条件写在设计稿 §3.4：**>10k 条 或 >50ms**。
     *
     * ⚠️ 结果里的敏感片段**必须在界面层遮蔽** ——
     *    否则"搜索"会变成一条绕过 `PrivacyFilter` 的读屏旁路。
     */
    @Query(
        """
        SELECT * FROM session
        WHERE archived = 0
          AND (title LIKE '%' || :kw || '%'
               OR id IN (SELECT sessionId FROM turn WHERE text LIKE '%' || :kw || '%'))
        ORDER BY lastMessageAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(kw: String, limit: Int): List<SessionEntity>

    /**
     * 删除会话，**并把它的轮次与执行记录一起删掉**。
     *
     * ⚠️ 这是**隐私要求**：用户以为删干净了，而磁盘上还留着他说的每一句话 ——
     *    那不是整洁问题。见设计稿 §4.6。
     *
     * ⚠️ **刻意不删 `atom`（L1 记忆）** —— 那一步要重算引用它的 scene、
     *    并且可能要调 LLM，不能放在一个事务里同步做。
     *    设计稿 §4.6 的规则是：**标记待重算 + 下次 L2 触发时处理**，
     *    在此之前该 scene 标 `stale`，进 prompt 时**跳过**
     *    （宁可少给一条记忆，也不要给一条基于已删数据的记忆）。
     */
    @Transaction
    suspend fun deleteCascade(id: String) {
        deleteTasksOf(id)
        deleteTurnsOf(id)
        deleteById(id)
    }

    @Query("DELETE FROM task WHERE sessionId = :id")
    suspend fun deleteTasksOf(id: String)

    @Query("DELETE FROM turn WHERE sessionId = :id")
    suspend fun deleteTurnsOf(id: String)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun deleteById(id: String)
}
