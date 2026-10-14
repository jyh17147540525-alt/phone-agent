package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketagent.core.database.entity.TaskEntity

/** 执行记录的读写。会话内的明细，**不是**侧边栏的列表单位。 */
@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity)

    /**
     * 更新执行状态。
     *
     * ⚠️ `finishedAtMillis` 只在**终止态**写。传 null 表示"还在跑"——
     *    用 0 表示"没结束"会让"1970 年结束的任务"变成一个真实存在的值。
     */
    @Query(
        """
        UPDATE task SET
            state = :state,
            step = :step,
            note = :note,
            abortReason = :abortReason,
            perceptionTier = :perceptionTier,
            finishedAtMillis = :finishedAtMillis
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: String,
        state: String,
        step: Int,
        note: String,
        abortReason: String?,
        perceptionTier: Int,
        finishedAtMillis: Long?,
    )

    @Query("SELECT * FROM task WHERE sessionId = :sessionId ORDER BY createdAtMillis ASC")
    suspend fun listBySession(sessionId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("DELETE FROM task WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
