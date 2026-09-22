package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pocketagent.core.database.entity.UsageRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用量账本的读写。
 *
 * ⚠️ **本 DAO 刻意没有 `deleteAll()` 之外的清理接口，也没有 UPDATE。**
 *    账本一旦写入就不该被改写 —— "这条记录到底花了多少"如果可以被
 *    后来的写入覆盖，那它对账的价值就没了。清理只走"整表清空"
 *    （用户在设置里点"清除用量统计"），是一条明确的、可见的操作。
 */
@Dao
interface UsageDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    /** 用量页的主列表，最近的在前 */
    @Query("SELECT * FROM usage_record ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<UsageRecordEntity>>

    /**
     * 某个模型配置累计花了多少 token。
     *
     * ⚠️ `COALESCE` 不能省 —— 没有任何记录时 `SUM` 返回的是 **null 而不是 0**，
     *    而 Room 把 null 映射到非空 `Int` 会抛异常。表现是"刚装上应用，
     *    一打开用量页就崩"，而它在开发期（库里总有几条测试数据）**测不出来**。
     */
    @Query(
        """
        SELECT COALESCE(SUM(inputTokens + outputTokens), 0)
        FROM usage_record
        WHERE modelConfigId = :modelConfigId
        """
    )
    suspend fun totalTokensForModel(modelConfigId: String): Int

    /** 失败次数。用来判断"这个模型是不是一直不稳" */
    @Query("SELECT COUNT(*) FROM usage_record WHERE modelConfigId = :modelConfigId AND failure IS NOT NULL")
    suspend fun failureCountFor(modelConfigId: String): Int

    /**
     * 时间窗内的记录数。给"最近一小时用了多少次"这类护栏用。
     *
     * ⚠️ 用 `>=` 而边界值语义是"从该时刻起（含）"—— 与调用方的直觉一致。
     *    取成 `>` 会让"恰好卡在整点的那次"被漏掉，而这个偏差在低频场景下
     *    完全看不出来，只在压测时才现形。
     */
    @Query("SELECT COUNT(*) FROM usage_record WHERE createdAtMillis >= :sinceMillis")
    suspend fun countSince(sinceMillis: Long): Int

    /** 总记录数 */
    @Query("SELECT COUNT(*) FROM usage_record")
    suspend fun count(): Int

    @Query("DELETE FROM usage_record")
    suspend fun deleteAll()
}
