package com.pocketagent.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.pocketagent.core.database.dao.CredentialDao
import com.pocketagent.core.database.dao.SessionDao
import com.pocketagent.core.database.dao.TaskDao
import com.pocketagent.core.database.dao.TurnDao
import com.pocketagent.core.database.dao.ModelConfigDao
import com.pocketagent.core.database.dao.UsageDao
import com.pocketagent.core.database.entity.CredentialEntity
import com.pocketagent.core.database.entity.SessionEntity
import com.pocketagent.core.database.entity.TaskEntity
import com.pocketagent.core.database.entity.TurnEntity
import com.pocketagent.core.database.entity.ModelConfigEntity
import com.pocketagent.core.database.entity.UsageRecordEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 应用数据库。
 *
 * ## 版本历史
 *
 * | 版本 | 变更 |
 * |---|---|
 * | 1 | `credential` 表（密文 Key） |
 * | 2 | `credential.purpose` 列 + 索引；新增 `model_config` 表 |
 * | 3 | 新增 `usage_record` 表（用量账本，只有元数据、无请求正文） |
 *
 * ⚠️ **`exportSchema = true` 不是可选项。**
 *    Room 的 schema JSON 是写迁移的唯一依据：没有它，加字段时只能靠猜
 *    旧表长什么样。目录由 `core/database/build.gradle.kts` 的
 *    `room.schemaLocation` 指定，产物**必须入库**。
 *
 * ⚠️ **每次升版都要导出一份新的 `<version>.json`。**
 *    漏了它，下一次迁移就没有"上一版长什么样"的依据 ——
 *    而那时旧版已经在用户手机上了，无从反推。
 */
@Database(
    entities = [
        CredentialEntity::class,
        ModelConfigEntity::class,
        UsageRecordEntity::class,
        // ── v4：会话持久化（2026-10-14）──────────────────────────
        // ★ 在此之前任务**只在内存里**（TasksViewModel 的 mutableStateListOf），
        //   进程一死全没 —— 连"正常使用"都谈不上。
        SessionEntity::class,
        TurnEntity::class,
        TaskEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class PocketAgentDatabase : RoomDatabase() {

    abstract fun credentialDao(): CredentialDao

    abstract fun modelConfigDao(): ModelConfigDao

    abstract fun usageDao(): UsageDao

    abstract fun sessionDao(): SessionDao
    abstract fun turnDao(): TurnDao
    abstract fun taskDao(): TaskDao
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
            // ⚠️ **绝不能用 `fallbackToDestructiveMigration()` 代替迁移。**
            //    那会在升级时静默删掉整个库 —— 用户的 API Key 全部消失，
            //    而界面上只会表现为"配置没了"。这类数据丢失是不可逆的。
            .addMigrations(*Migrations.ALL)
            // ⚠️ **必须显式打开外键约束**：SQLite 的 `PRAGMA foreign_keys` 默认是**关**的。
            //    不打开的话，`turn` / `task` 上声明的 `ON DELETE CASCADE`
            //    **一行都不会生效** —— 删了会话，用户说过的每一句话还躺在库里。
            //    **那是隐私问题，不是整洁问题**（设计稿 §4.6）。
            //
            //    ⚠️ 而且"没被强制执行的外键"比"没有外键"更糟：它看起来像个保证。
            //    ⇒ 业务路径仍然走 `SessionDao.deleteCascade` 显式删，
            //      这一句是 DB 层的**第二道**保险，不是唯一的一道。
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
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
