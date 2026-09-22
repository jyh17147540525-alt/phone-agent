package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pocketagent.core.database.entity.ModelConfigEntity
import com.pocketagent.core.database.entity.ModelRoleEntity
import kotlinx.coroutines.flow.Flow

/**
 * 模型配置表的读写。
 *
 * ⚠️ 本 DAO **不涉及任何 Key 操作** —— 它只存"用哪个凭据的 id"，
 *    解密与取用归 `keymgmt`。这条边界让模型配置可以被自由导出/导入
 *    （分享配置给别人不会有泄漏风险）。
 */
@Dao
interface ModelConfigDao {

    /** 配置页订阅。按添加顺序排，列表不会因为启用状态变化而重排 */
    @Query("SELECT * FROM model_config ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<ModelConfigEntity>>

    @Query("SELECT * FROM model_config ORDER BY createdAtMillis ASC")
    suspend fun all(): List<ModelConfigEntity>

    @Query("SELECT * FROM model_config WHERE id = :id")
    suspend fun byId(id: String): ModelConfigEntity?

    /**
     * 参与调度的模型：已启用 + 有 WORKER 角色。
     *
     * `roleMask & :workerBit != 0` 是 bit mask 的位测试写法。
     * SQLite 没有原生的位运算函数，但 `&` 运算符可用。
     */
    @Query(
        """
        SELECT * FROM model_config
        WHERE enabled = 1 AND (roleMask & :workerBit) != 0
        ORDER BY createdAtMillis ASC
        """
    )
    suspend fun enabledWorkers(workerBit: Int = ModelRoleEntity.WORKER.bit): List<ModelConfigEntity>

    /** 某档位下可用的执行模型 —— 调度的核心查询 */
    @Query(
        """
        SELECT * FROM model_config
        WHERE enabled = 1
          AND tier = :tier
          AND (roleMask & :workerBit) != 0
        ORDER BY createdAtMillis ASC
        """
    )
    suspend fun workersByTier(
        tier: String,
        workerBit: Int = ModelRoleEntity.WORKER.bit,
    ): List<ModelConfigEntity>

    /**
     * 配置为调度者的模型。
     *
     * ⚠️ 返回列表而非单条：本项目**不强制**唯一调度者。
     *    用户完全可能配两个（例如主用便宜的、备用贵的），
     *    由上层按可用性挑选。用 `LIMIT 1` 会让第二个配置永远不生效，
     *    而用户从界面上看不出为什么。
     */
    @Query(
        """
        SELECT * FROM model_config
        WHERE enabled = 1 AND (roleMask & :schedulerBit) != 0
        ORDER BY createdAtMillis ASC
        """
    )
    suspend fun schedulers(schedulerBit: Int = ModelRoleEntity.SCHEDULER.bit): List<ModelConfigEntity>

    /** 某个凭据下的全部模型配置。删凭据时要先清这些（应用层级联） */
    @Query("SELECT * FROM model_config WHERE credentialId = :credentialId")
    suspend fun byCredential(credentialId: String): List<ModelConfigEntity>

    @Query("SELECT COUNT(*) FROM model_config")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM model_config WHERE enabled = 1")
    suspend fun enabledCount(): Int

    /** 是否已存在同一凭据下的同一模型（用于配置页的去重提示） */
    @Query(
        "SELECT COUNT(*) FROM model_config WHERE credentialId = :credentialId AND modelId = :modelId"
    )
    suspend fun countByCredentialAndModel(credentialId: String, modelId: String): Int

    @Upsert
    suspend fun upsert(entity: ModelConfigEntity)

    @Query("DELETE FROM model_config WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 删除凭据时级联清理（Room 外键未启用，由仓储层在事务里调用） */
    @Query("DELETE FROM model_config WHERE credentialId = :credentialId")
    suspend fun deleteByCredential(credentialId: String)

    /** 只改启用状态，不碰其他字段 */
    @Query("UPDATE model_config SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 只改档位 */
    @Query("UPDATE model_config SET tier = :tier WHERE id = :id")
    suspend fun setTier(id: String, tier: String)

    /** 只改角色（bit mask） */
    @Query("UPDATE model_config SET roleMask = :roleMask WHERE id = :id")
    suspend fun setRoleMask(id: String, roleMask: Int)

    /** 清空全部配置。用于「一键清除所有数据」 */
    @Query("DELETE FROM model_config")
    suspend fun deleteAll()
}
