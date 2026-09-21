package com.pocketagent.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pocketagent.core.database.dao.CredentialDao
import com.pocketagent.core.database.entity.CredentialEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 应用数据库。
 *
 * 当前只有凭据一张表。用量/费用表（`UsageEntity`）在网关落地时加进来 ——
 * 届时 `version` 升到 2 并补一条 `Migration`。
 *
 * ⚠️ **`exportSchema = true` 不是可选项。**
 *    Room 的 schema JSON 是写迁移的唯一依据：没有它，加字段时只能靠猜
 *    旧表长什么样。目录由 `core/database/build.gradle.kts` 的
 *    `room.schemaLocation` 指定，产物**必须入库**。
 */
@Database(
    entities = [CredentialEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PocketAgentDatabase : RoomDatabase() {

    abstract fun credentialDao(): CredentialDao
}

/**
 * 建库入口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么口令是"传进来"的，而不是这里自己取
 * ═══════════════════════════════════════════════════════════════
 *
 * 取口令要碰 Android Keystore，那是个可能失败的慢操作（StrongBox 首次生成
 * 密钥可能几百毫秒），而且失败时**必须让用户看到原因**。
 * 如果藏在建库函数里，调用方只能拿到一个笼统的失败，没法区分
 * "存储空间不足"和"系统密钥已失效（数据没了）"。
 *
 * 所以职责拆开：[DatabaseKeyProvider] 负责取口令并把失败原因说清楚，
 * 本函数只负责用它建库。
 */
object PocketAgentDatabaseFactory {

    /**
     * @param passphrase SQLCipher 口令。调用方用完应清零 ——
     *        本函数**不持有它**，只交给 SQLCipher。
     */
    fun create(context: Context, passphrase: ByteArray): PocketAgentDatabase {
        loadSqlCipherNativeLibrary()

        return Room.databaseBuilder(
            context.applicationContext,
            PocketAgentDatabase::class.java,
            DATABASE_NAME,
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    /**
     * 加载 SQLCipher 的 native 库。
     *
     * ⚠️ `net.zetetic:sqlcipher-android` **不会自动加载** —— 它的 AAR 清单里
     *    没有声明，类里也没有静态初始化块（已用 javap 核对过）。
     *    不调用这一句，会在第一次打开数据库时报 `UnsatisfiedLinkError`，
     *    而报错位置在 SQLCipher 内部，很难联想到"少了一句 loadLibrary"。
     *
     * 重复调用是安全的（同一 classloader 内加载同一个库是幂等的），
     * 所以用一个标志位避免每次都走一遍查找。
     */
    private var nativeLibraryLoaded = false

    private fun loadSqlCipherNativeLibrary() {
        if (nativeLibraryLoaded) return
        System.loadLibrary("sqlcipher")
        nativeLibraryLoaded = true
    }

    const val DATABASE_NAME = "pocketagent.db"
}
