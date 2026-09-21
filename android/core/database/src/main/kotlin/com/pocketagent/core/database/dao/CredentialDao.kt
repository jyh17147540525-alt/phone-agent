package com.pocketagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

/**
 * 凭据表的读写。
 *
 * ⚠️ 本 DAO **没有任何返回明文的途径** —— 它只搬运密文。
 *    解密只发生在 `keymgmt` 的仓储层，且必须经 `CryptoManager.withDecryptedKey`
 *    在受控作用域内完成。这条边界是刻意的：让"能解密"这件事只存在于一处。
 */
@Dao
interface CredentialDao {

    /** 列表页订阅这个。按添加顺序排，列表不会因为校验结果变化而重排 */
    @Query("SELECT * FROM credential ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credential ORDER BY createdAtMillis ASC")
    suspend fun all(): List<CredentialEntity>

    @Query("SELECT * FROM credential WHERE id = :id")
    suspend fun byId(id: String): CredentialEntity?

    /** 当前使用的那一个。理论上至多一条，`LIMIT 1` 是防御性写法 */
    @Query("SELECT * FROM credential WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultCredential(): CredentialEntity?

    @Query("SELECT COUNT(*) FROM credential")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entity: CredentialEntity)

    @Query("DELETE FROM credential WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空所有凭据。用于「一键清除所有数据」 */
    @Query("DELETE FROM credential")
    suspend fun deleteAll()

    /**
     * 只更新校验结果，**不碰密文**。
     *
     * 单独开一个方法而不是读出来改完再 upsert，有两个理由：
     *  1. 校验是高频操作，重写整行会把密文无谓地搬进搬出内存
     *  2. 万一读取-修改-写回之间发生了并发修改（比如用户在别处改了标签），
     *     整行写回会把那次修改覆盖掉。定点更新不存在这个问题。
     */
    @Query(
        """
        UPDATE credential
        SET lastCheckedAtMillis = :checkedAtMillis,
            lastStatus = :status,
            lastStatusDetail = :detail,
            modelCount = :modelCount
        WHERE id = :id
        """
    )
    suspend fun updateCheckResult(
        id: String,
        checkedAtMillis: Long,
        status: CredentialCheckStatus,
        detail: String?,
        modelCount: Int?,
    )

    @Query("UPDATE credential SET isDefault = 0")
    suspend fun clearDefaultFlag()

    @Query("UPDATE credential SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    @Query("UPDATE credential SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String)
}
