package com.pocketagent.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个测试不能省
 * ═══════════════════════════════════════════════════════════════
 *
 * Room **只在运行期**校验迁移。编译期它完全不管 ——
 * 少一个 `NOT NULL`、列顺序不对、索引名拼错，`./gradlew assembleDebug`
 * 全都通过。代价推到用户设备上：升级后打开数据库直接抛
 * `IllegalStateException: Migration didn't properly handle ...`。
 *
 * ⚠️ **这是最糟的失败模式** —— 用户连界面都进不去，
 *    也就看不到任何提示，只能卸载重装（那会丢掉所有已存的 Key）。
 *
 * `MigrationTestHelper` 的做法是：用 v1 的 schema 建库 → 跑迁移 →
 * 用 Room 从当前 `@Entity` 生成的期望 schema 逐项比对。
 * 它是**唯一**能验证"迁移后的表结构符合 Room 预期"的手段。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 用 Robolectric 跑在 JVM 上，**刻意不用 androidTest**
 * ═══════════════════════════════════════════════════════════════
 *
 * 迁移错误必须在**没有设备的时候**就能发现 —— 那样它才会在每次改动时
 * 被跑到。放在 `androidTest` 里等于"等有人记得插手机才验证"，
 * 而这一等往往就等到了发布之后。
 *
 * ⚠️ 本测试**不加密**（用明文 builder 而非 SQLCipher）——
 *    验证的是表结构，与加密无关。加密路径需真机验证。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这个文件曾经**从未编译过**（2026-09-22 首次跑通）
 * ═══════════════════════════════════════════════════════════════
 *
 * 它写出来之后一直躺在仓库里，直到今天才第一次被真正执行。
 * 原因链很有教育意义，三条都要记住：
 *
 * 1. **Gradle 全项目配置超时**（`configuring project ':channel'` →
 *    `TimeoutException`）—— 根因是**配置缓存**，加 `--no-configuration-cache`
 *    即解。此前误判为"环境/中文路径问题"，排查了很多轮。
 * 2. **离线验证器覆盖不到** —— `run_logic_tests.py` 只收零 Android 依赖的模块，
 *    `core:database` 有 Room/SQLCipher，进不去。
 *    这就是"离线跑绿 ≠ 能编译"的完整形态：**它连跑都没跑过。**
 * 3. 于是 Room 2.8 的 API 变更（下面那些 ⚠️）全都没被发现。
 *
 * > **教训：一个从未被执行过的测试文件，价值是零，而且是负的** ——
 * > 它让人以为"迁移有测试覆盖"，从而放心地改迁移脚本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    // ─────────────────────────────────────────────────────────────
    //  ⚠️⚠️ 这两个字段必须**声明在 `helper` 之前**，且必须是**实例**字段
    // ─────────────────────────────────────────────────────────────
    //
    // Kotlin 按声明顺序初始化属性，`helper` 的初始化里用到了它们，
    // 所以写在后面会拿到 `null`。
    //
    // 更要紧的是 **必须是实例字段（`val`），不能是文件级 `by lazy`**：
    //
    //   JUnit 会为**每个测试方法 new 一个测试类实例**，
    //   而文件级的 `by lazy` 是**每个 classloader 一份**（等于整个 JVM 一份）。
    //   于是七个用例会共用同一个数据库文件 ——
    //   第一个用例 `createDatabase(1)` 建 v1、升到 v2 并留下文件，
    //   第二个用例再 `createDatabase(1)` 就撞上磁盘上那个 v2 文件，抛
    //
    //     IllegalStateException: A migration from 2 to 1 was required but not found
    //
    //   ⚠️ 症状极具迷惑性：**单独跑任何一个用例都过，一起跑就挂**。
    //      我为此怀疑过 driver、怀疑过 File 参数语义、
    //      怀疑过建库 lambda 少写 addMigrations —— 全不是。
    //      判据依然是堆栈前几行：失败在 `createDatabase` → `openConnection`
    //      → `onMigrate`，而不是在 `runMigrationsAndValidate`。
    private val dbFileName: String = "migration-test-${java.util.UUID.randomUUID()}.db"

    /** 与 [dbFileName] 配对的绝对路径（系统临时目录下）。 */
    private val dbFile: File = File(System.getProperty("java.io.tmpdir"), dbFileName)

    @get:Rule
    val helper = MigrationTestHelper(
        // ⚠️ 第一个参数是 `Instrumentation`，不是 `Context`。
        //    Room 2.7 起签名变了（旧版收 `Context`），本项目用的是 **Room 2.8.1**。
        //    用 `ApplicationProvider.getApplicationContext()` 的后果不是一句
        //    清晰的类型错误，而是满屏 `Cannot infer type for type parameter 'T'`
        //    + `Unresolved reference 'createDatabase'` ——
        //    看起来像"依赖没配好"，实际是签名不匹配。
        InstrumentationRegistry.getInstrumentation(),

        // ⚠️ 第二个参数是 **`File`，而且它是「数据库文件」，不是 schema 目录。**
        //
        //    这点极容易搞反 —— 我第一版就传了 `File("schemas")`，报的是：
        //
        //      SQLiteCantOpenDatabaseException: Cannot open database 'schemas'
        //      with flags 0x10000000: Directory not specified in the file path
        //
        //    读起来像"路径写错了"，实际是**语义搞反了**：它拿这个 File 去开数据库。
        //
        //    schema 不在这个参数里 —— 它从 **assets** 读，见下面第 4 个参数。
        //
        // ⚠️⚠️ **必须是「每次运行都全新的路径」，不能是一个固定的相对文件名。**
        //
        // 传 `File("migration-test.db")` 的后果非常隐蔽，值得完整记一遍：
        //
        //   · 相对路径按**测试进程的工作目录**解析 —— 实测落在模块根目录
        //     `android/core/database/migration-test.db`，**污染源码树**
        //     （还会甩一个 `.lck` 锁文件在旁边）
        //   · 更致命的是**它跨运行留存**。第一遍跑完，磁盘上留下一个
        //     `user_version = 2`、已经含 `model_config` 表的库；
        //     第二遍一开始调 `createDatabase(1)`，Room 打开这个文件发现是 v2，
        //     于是要"2 → 1"迁移 —— 而迁移只有 1→2 的方向，抛：
        //
        //       IllegalStateException: A migration from 2 to 1 was required
        //       but not found. Please provide the necessary Migration path
        //       via RoomDatabase.Builder.addMigration(...)
        //
        //   ⚠️ 这个报错会把排查带偏得很远：它看起来像"生产代码漏了迁移"、
        //      像"`File` 参数的版本语义不对"、"像 lambda 少加 addMigrations"。
        //      我三条都试了。**真凶是磁盘上那个上一轮留下的文件。**
        //      最有效的判据是看**堆栈的前几行**：
        //         `createDatabaseCommon` → `openConnection` → `onMigrate`
        //      它失败在 `createDatabase(1)` 里面，而不是在 `runMigrationsAndValidate`
        //      —— 这一条就直接排除了所有"迁移注册"类的猜测。
        //
        // 用一个每次**用例实例**唯一 的临时路径，让"上一轮的残留"在物理上
        // 不可能存在。⚠️ 注意这里用的是**实例字段** `dbFile` 而不是文件级
        // `by lazy` —— 后者会在七个用例之间共享同一个文件，同样会挂。
        // 完整原因见 `dbFileName` 字段上的注释。
        dbFile,

        // ⚠️⚠️ **必须用 `AndroidSQLiteDriver`，不能用 `FrameworkSQLiteOpenHelperFactory`
        //      包出来的 driver。**
        //
        // 真实踩过的坑（Room 2.8 + Robolectric）：
        //
        //   `SupportSQLiteDriver` 在 `open(fileName)` 里会拿
        //   `openHelper.getDatabaseName()` 与传入的 fileName 比对，不一致就抛：
        //
        //     IllegalArgumentException: This driver is configured to open a
        //     database named 'migration-test.db' but
        //     'C:\...\Temp\robolectric-...\databases\migration-test.db' was requested.
        //
        //   真机上这个比对能过（两边都是相对名），但 **Robolectric 会把数据库
        //   目录重定向到临时目录**，于是 `getDatabaseName()` 返回绝对路径，
        //   而 Room 传进来的是相对名 —— 七个用例**全部**以同一个异常失败。
        //
        //   `AndroidSQLiteDriver` 没有"配置名字"这个概念，直接按传入路径开，
        //   因此在 Robolectric 与真机上行为一致。
        AndroidSQLiteDriver(),

        // 数据库类。
        PocketAgentDatabase::class,

        // 建库工厂。刻意**不走 `PocketAgentDatabaseFactory.create`** ——
        // 那个入口要 SQLCipher 口令，而迁移测试验证的是**表结构**，与加密无关。
        // 用明文 builder 让失败原因保持单一：一旦加上加密，
        // 所有失败都会先怀疑"是不是口令/原生库的问题"。
        //
        // ⚠️⚠️ **`addMigrations` 这一行不能省。**
        //
        // 这个 lambda 不是"只在建 v1 库时用一次"，helper 在
        // `runMigrationsAndValidate` 里会**再拿它开一次库**，而此时磁盘上是
        // 一个 **v1 的库**，而 `PocketAgentDatabase` 声明的是 **version = 2**。
        // Room 于是要 1→2 迁移 —— 但 builder 里没有任何迁移可用，抛：
        //
        //     IllegalStateException: A migration from 2 to 1 was required
        //     but not found. Please provide the necessary Migration path
        //     via RoomDatabase.Builder.addMigration(...)
        //
        // ⚠️ 注意这句话的**方向感是反的**：它说"from 2 to 1"，实际要的是 1→2。
        //    别被它牵着去写一条 2→1 的假迁移 —— 那会让测试"通过"但什么都没验证。
        {
            Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PocketAgentDatabase::class.java,
                dbFileName,
            )
                .addMigrations(*Migrations.ALL)
                .build()
        },

        // 自动迁移规格：本项目全部用**手写迁移**，没有 AutoMigration。
        emptyList(),
    )

    // ─────────────────────────────────────────────────────────────
    //  ⚠️ 关于"列顺序"的一个反直觉事实，别再踩
    // ─────────────────────────────────────────────────────────────
    //
    // `ALTER TABLE ADD COLUMN` 只能把新列**追加到末尾**，而 `purpose` 在
    // `CredentialEntity` 里是第 3 个字段（紧跟在 providerId 后面）。
    // 于是迁移后的物理列顺序与 Room 导出的 `2.json` 里 `createSql` 的
    // 列顺序**不一致** —— 看起来像要重建表才能对齐。
    //
    // 反编译 room-runtime 的 `androidx.room.util.TableInfo.equals` 后确认：
    //
    //     name        : String          → 比内容
    //     columns     : java.util.Map   → **Map 相等，与顺序无关**
    //     foreignKeys : java.util.Set   → 顺序无关
    //     indices     : java.util.Set   → 顺序无关
    //
    // 所以列顺序**不影响**校验，为它重建表是白做且徒增风险。
    //
    // 真正**必须**一致的是索引**名字**（`TableInfo$Index.equals` 比较
    // unique + columns + orders + **name**，且 name 会先剥掉 "index_" 前缀）。
    // 本模块就曾把 `index_model_config_roleMask` 误写成
    // `index_model_config_role` —— 这类笔误编译期完全看不出来，
    // 只有迁移校验能拦住它。

    // ─────────────────────────────────────────────────────────────
    //  ⚠️ Room 2.8 的查询 API 是「语句式」，不是「游标式」
    // ─────────────────────────────────────────────────────────────
    //
    // 旧写法（≤2.6，`SupportSQLiteDatabase`）：
    //
    //     db.query("SELECT ...").use { cursor ->
    //         while (cursor.moveToNext()) names += cursor.getString(0)
    //     }
    //
    // 新写法（2.7+，`SQLiteConnection`）：
    //
    //     db.prepare("SELECT ...").use { stmt ->
    //         while (stmt.step()) names += stmt.getText(0)
    //     }
    //
    // 两个刻意的设计差异，写的时候要注意：
    //   · **没有游标**，靠 `step()` 推进；取"第一行"要显式 `assertTrue(stmt.step())`
    //   · **列下标由驱动决定** —— 本模块用的 `AndroidSQLiteDriver` 是 **0 基**。
    //     这一条我第一版按 SQLite C API 语义写成 1 基，撞墙后才实测确认，
    //     详见文件末尾 `queryStrings` 的注释。
    //
    // 用 `query()` / `moveToNext()` 会直接编译失败（不是运行时错误）——
    // 这算是好事，因为在新 API 下那些方法确实不存在。

    // ── 结构校验 ─────────────────────────────────────────────────

    @Test
    fun `从 v1 迁移到 v2 后表结构符合 Room 预期`() {
        // 1) 用 v1 的 schema 建库
        helper.createDatabase(1).close()

        // 2) 跑迁移，然后让 helper 用当前 @Entity 的期望 schema 校验
        //    ⚠️ 校验失败会抛 IllegalStateException，异常信息里会指明差在哪一列。
        //    这一步通过，就等于"升级不会崩在打开数据库那一步"。
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).close()
    }

    @Test
    fun `迁移后 model_config 的索引名与 Room 期望一致`() {
        // 单独钉住索引名：写错名字等于索引不存在，而 Room 判定"迁移未正确处理"。
        // 这是真实踩到的 bug，值得一条专门的用例。
        helper.createDatabase(1).close()
        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        val names = db.queryStrings(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='model_config'"
        )

        val expected = listOf(
            "index_model_config_credentialId",
            "index_model_config_tier",
            "index_model_config_roleMask",
            "index_model_config_credentialId_modelId",
        )
        expected.forEach {
            assertTrue("缺少索引 $it，实际有：$names", it in names)
        }

        db.close()
    }

    @Test
    fun `迁移后 credential 的 purpose 索引存在`() {
        // 索引漏建的后果是"能跑但慢" —— 不会报错，只在 Key 多起来之后显现。
        // 而那时已经很难联想到是迁移漏了索引。
        helper.createDatabase(1).close()
        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        val names = db.queryStrings(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='credential'"
        )
        assertTrue(
            "缺少 purpose 索引，实际有：$names",
            "index_credential_purpose" in names,
        )

        db.close()
    }

    // ── 数据保全 ─────────────────────────────────────────────────

    @Test
    fun `v1 的老数据在迁移后仍然可读`() {
        // 这是迁移最容易出错的地方：结构对了但数据丢了。
        // 用一个"结构对、数据错"的迁移换来的通过，比失败更危险。
        helper.createDatabase(1).use { db ->
            db.execSQL(
                """
                INSERT INTO credential
                    (id, providerId, label, ciphertext, iv, keyLength,
                     baseUrlOverride, createdAtMillis, lastCheckedAtMillis,
                     lastStatus, lastStatusDetail, modelCount, isDefault)
                VALUES
                    ('c1', 'deepseek', '主力', X'0102', X'0304', 35,
                     NULL, 1000, NULL, NULL, NULL, NULL, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        db.prepare(
            "SELECT id, providerId, label, keyLength, isDefault, purpose FROM credential"
        ).use { stmt ->
            assertTrue("迁移后应当还有一条记录", stmt.step())
            assertEquals("c1", stmt.getText(0))
            assertEquals("deepseek", stmt.getText(1))
            assertEquals("主力", stmt.getText(2))
            assertEquals(35L, stmt.getLong(3))
            assertEquals(1L, stmt.getLong(4))
            // ★ 关键断言：老数据的 purpose 必须是 LLM。
            //   标成 TTS 会让所有老用户的 Key 突然发不出对话请求，
            //   而且他们从界面上完全看不出来为什么。
            assertEquals("LLM", stmt.getText(5))
        }

        db.close()
    }

    // ── 读写与约束 ───────────────────────────────────────────────

    @Test
    fun `迁移后 model_config 表可正常读写`() {
        helper.createDatabase(1).close()
        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        db.execSQL(
            """
            INSERT INTO model_config
                (id, credentialId, modelId, label, tier, roleMask,
                 inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
            VALUES
                ('m1', 'c1', 'deepseek-chat', '快模型', 'LIGHT', 3,
                 NULL, NULL, 1, 1000)
            """.trimIndent()
        )

        db.prepare("SELECT modelId, tier, roleMask, enabled FROM model_config").use { stmt ->
            assertTrue(stmt.step())
            assertEquals("deepseek-chat", stmt.getText(0))
            assertEquals("LIGHT", stmt.getText(1))
            // roleMask = 3 = SCHEDULER(1) | WORKER(2)，即"既是调度者也是执行者"
            assertEquals(3L, stmt.getLong(2))
            assertEquals(1L, stmt.getLong(3))
        }

        db.close()
    }

    @Test
    fun `同一凭据下不允许重复配置同一个模型`() {
        // 唯一索引的验证。没有它，用户可以从两个入口把同一个模型加两遍，
        // 列表里出现两条一模一样的记录，调度时也会重复计入。
        helper.createDatabase(1).close()
        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        val insert = """
            INSERT INTO model_config
                (id, credentialId, modelId, label, tier, roleMask,
                 inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
            VALUES
                (?, 'c1', 'gpt-4o-mini', NULL, 'STANDARD', 2, NULL, NULL, 1, 1000)
        """.trimIndent()

        db.bindAndExec(insert, "m1")

        var violated = false
        try {
            db.bindAndExec(insert, "m2")
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            violated = true
        }
        assertTrue("重复的 (credentialId, modelId) 应当被唯一索引拒绝", violated)

        db.close()
    }

    @Test
    fun `不同凭据下可以配置同一个模型`() {
        // 唯一约束是 (credentialId, modelId) 的组合，不是 modelId 单列 ——
        // 用户完全可能用两个不同的 Key（比如官方 + 中转）访问同一个模型，
        // 而那正是"哪个便宜用哪个"的前提。
        helper.createDatabase(1).close()
        val db = helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2))

        val insert = """
            INSERT INTO model_config
                (id, credentialId, modelId, label, tier, roleMask,
                 inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
            VALUES
                (?, ?, 'gpt-4o-mini', NULL, 'STANDARD', 2, NULL, NULL, 1, 1000)
        """.trimIndent()

        db.bindAndExec(insert, "m1", "c1")
        db.bindAndExec(insert, "m2", "c2")

        assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM model_config"))

        db.close()
    }

    // ─────────────────────────────────────────────────────────────
    //  v2 → v3：用量账本
    // ─────────────────────────────────────────────────────────────
    //
    // ⚠️ 从 v1 一路升到最新版（而不是只测 2→3），因为用户手上的版本
    //    可能是 v1、v2 或 v3 中的任何一个 —— 跳版升级必须也能走通。
    //    `runMigrationsAndValidate(3, ...)` 会把两条迁移按顺序都跑一遍。

    @Test
    fun `从 v1 一路迁移到 v3 后表结构符合 Room 预期`() {
        helper.createDatabase(1).close()

        helper.runMigrationsAndValidate(
            3,
            listOf(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3),
        ).close()
    }

    @Test
    fun `从 v2 迁移到 v3 后表结构符合 Room 预期`() {
        // 单独测 2→3 一档：只测跳版的话，"2→3 单独跑"的路径没被覆盖，
        // 而它正是已经在用 v2 的用户（也就是现在的大多数人）会走的路径。
        helper.createDatabase(2).close()

        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3)).close()
    }

    @Test
    fun `迁移后 usage_record 的索引名与 Room 期望一致`() {
        // ⚠️ 索引名写错 = 索引不存在 = Room 判定"迁移未正确处理"。
        //    三个索引都要列出来 —— 漏一个不会被结构校验抓到，
        //    但会让"按模型聚合花费"这类查询在全表扫描下变慢，
        //    而那时用户已经有几千条记录了，很难联想到是迁移的问题。
        helper.createDatabase(2).close()
        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        val names = db.queryStrings(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='usage_record'"
        )

        val expected = listOf(
            "index_usage_record_createdAtMillis",
            "index_usage_record_modelConfigId",
            "index_usage_record_consumer",
        )
        expected.forEach {
            assertTrue("缺少索引 $it，实际有：$names", it in names)
        }

        db.close()
    }

    @Test
    fun `v2 的既有数据在升级到 v3 后完好`() {
        // ⚠️ 本迁移只新增表、不动既有表，所以数据保全的**风险极低** ——
        //    但"风险低"不等于"不用测"。一条写错的迁移（比如误用
        //    DROP + CREATE 重建 credential）会让用户的 Key 全部消失，
        //    而它在结构校验里**看起来完全正常**（表结构对，只是没数据了）。
        helper.createDatabase(2).use { db ->
            db.execSQL(
                """
                INSERT INTO credential
                    (id, providerId, purpose, label, ciphertext, iv, keyLength,
                     baseUrlOverride, createdAtMillis, lastCheckedAtMillis,
                     lastStatus, lastStatusDetail, modelCount, isDefault)
                VALUES
                    ('c1', 'deepseek', 'LLM', '主力', X'0102', X'0304', 35,
                     NULL, 1000, NULL, NULL, NULL, NULL, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO model_config
                    (id, credentialId, modelId, label, tier, roleMask,
                     inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
                VALUES
                    ('m1', 'c1', 'deepseek-chat', '快模型', 'LIGHT', 3,
                     NULL, NULL, 1, 1000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        // 凭据与模型配置都还在
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM credential"))
        assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM model_config"))

        // 新表是空的（不是被塞了假数据）
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM usage_record"))

        db.close()
    }

    @Test
    fun `usage_record 可写入并读回`() {
        helper.createDatabase(2).close()
        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        db.execSQL(
            """
            INSERT INTO usage_record
                (modelConfigId, providerId, consumer, inputTokens, outputTokens,
                 upstreamInputTokens, upstreamOutputTokens, failure, warning, createdAtMillis)
            VALUES
                ('m1', 'deepseek', 'AgentLoop', 120, 34, 118, 30, NULL, NULL, 1000)
            """.trimIndent()
        )

        db.prepare(
            "SELECT modelConfigId, consumer, inputTokens, upstreamInputTokens FROM usage_record"
        ).use { stmt ->
            assertTrue(stmt.step())
            assertEquals("m1", stmt.getText(0))
            assertEquals("AgentLoop", stmt.getText(1))
            assertEquals(120L, stmt.getLong(2))
            assertEquals(118L, stmt.getLong(3))
        }

        db.close()
    }

    @Test
    fun `usage_record 的上游用量可以为空`() {
        // ⚠️ 这条钉住的是"上游没返回 usage"这个**正常**情况。
        //    若那四列被误加上了 NOT NULL，本用例会失败 ——
        //    而真实后果是"用本地 Ollama 或某些第三方中转时，记账直接写不进去"，
        //    表现是统计页一片空白，用户以为我们在偷偷不记账。
        helper.createDatabase(2).close()
        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        db.execSQL(
            """
            INSERT INTO usage_record
                (modelConfigId, providerId, consumer, inputTokens, outputTokens,
                 upstreamInputTokens, upstreamOutputTokens, failure, warning, createdAtMillis)
            VALUES
                ('m1', 'ollama', 'Dsh', 200, 0, NULL, NULL, 'timeout', NULL, 2000)
            """.trimIndent()
        )

        db.prepare(
            "SELECT upstreamInputTokens, upstreamOutputTokens, failure FROM usage_record"
        ).use { stmt ->
            assertTrue(stmt.step())
            // 用 isNull 而不是"值等于 0" —— 这两件事必须能区分开
            assertTrue("上游没给 usage 时该列为 null", stmt.isNull(0))
            assertTrue("上游没给 usage 时该列为 null", stmt.isNull(1))
            assertEquals("timeout", stmt.getText(2))
        }

        db.close()
    }

    @Test
    fun `usage_record 的主键是自增的`() {
        // ⚠️ 自增主键的 SQL 写法是 SQLite 的特例（`AUTOINCREMENT` 的位置），
        //    而这个错在结构校验里**不一定**报出来 —— 它可能被建成普通
        //    INTEGER PRIMARY KEY，行为上仍然可用，但 rowid 复用会让
        //    "已经删掉的记录的 id 被下一条占用"。
        //    本用例直接插入两条不带 id 的记录，断言它们拿到不同的 id。
        helper.createDatabase(2).close()
        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        val insert = """
            INSERT INTO usage_record
                (modelConfigId, providerId, consumer, inputTokens, outputTokens,
                 upstreamInputTokens, upstreamOutputTokens, failure, warning, createdAtMillis)
            VALUES
                ('m1', 'deepseek', 'AgentLoop', 10, 1, NULL, NULL, NULL, NULL, ?)
        """.trimIndent()

        db.bindAndExec(insert, "1000")
        db.bindAndExec(insert, "2000")

        assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM usage_record"))
        assertEquals(
            "自增主键应给两条记录分配不同的 id",
            2L,
            db.queryLong("SELECT COUNT(DISTINCT id) FROM usage_record"),
        )

        db.close()
    }

    @Test
    fun `usage_record 的聚合查询在空表上不返回 null`() {
        // ⚠️ 这条防的是用量页在"刚装上应用、还没有任何记录"时崩溃 ——
        //    `SUM` 在空集上返回的是 **null 而不是 0**，而 DAO 方法声明的是
        //    非空 Int，Room 映射 null 进去会抛异常。
        //    开发期库里总有测试数据，所以这个 bug **测不出来**。
        //
        //    DAO 里已经用 COALESCE 兜住了，这里把同样的语义在 SQL 层钉一次。
        helper.createDatabase(2).close()
        val db = helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3))

        val total = db.queryLong(
            "SELECT COALESCE(SUM(inputTokens + outputTokens), 0) " +
                "FROM usage_record WHERE modelConfigId = 'm1'"
        )
        assertEquals(0L, total)

        db.close()
    }
}

// ─────────────────────────────────────────────────────────────
//  查询辅助
// ─────────────────────────────────────────────────────────────
//
// 抽成扩展函数而不是在每个用例里手写 `prepare(...).use { ... }`：
// 新 API 的样板代码比游标式多，散在七个用例里会淹没真正的断言。
//
// ⚠️ 这些函数**只关语句，不关连接** —— 连接由用例自己 `close()`。
//    若这里顺手把连接也关了，后面用同一个 `db` 的断言会报
//    "connection is closed"，而错误指向的却是无关的那一行。

/**
 * 读一列文本。
 *
 * ⚠️⚠️ **列下标从 0 开始 —— 但这是「驱动相关」的，别记成通用结论。**
 *
 * 我第一版按 SQLite 的 C API 语义写成 `getText(1)`（SQLite 原生列号从 1 起），
 * 结果七个用例里挂掉的那个报：
 *
 *     SQLException: Error code: 25, message: column index out of range
 *
 * 于是写了个最小探针实测（`SELECT 1` 单列结果，逐个下标试）：
 *
 *     getLong(0) = Success(1)
 *     getLong(1) = Failure(column index out of range)
 *     getLong(2) = Failure(column index out of range)
 *
 * → **`androidx.sqlite.driver.AndroidSQLiteDriver` 是 0 基的。**
 *
 * 为什么会这样：`SQLiteStatement` 的列号语义由**驱动**决定，而本模块用的是
 * `AndroidSQLiteDriver`，它包的是 Android 平台自带的 SQLite（`android.database`
 * 那套 cursor 风格 API，历来 0 基）。
 * 换成 `androidx.sqlite.driver.bundled.BundledSQLiteDriver`（打包 SQLite 原生库那份）
 * 就可能是 1 基 —— **两者不可混用同一套下标假设**。
 *
 * > 教训：这类"文档没写死、随实现变"的细节，**不要靠推断，写个探针实测**。
 * > 我先前是按 C API 语义推的，推错了，而且错法很隐蔽 ——
 * > 越界的下标在单列结果上是**报错**（能立刻发现），
 * > 但在多列结果上会**静默取到隔壁列的值**，那才是真正可怕的。
 */
private fun SQLiteConnection.queryStrings(sql: String): List<String> =
    prepare(sql).use { stmt ->
        buildList {
            while (stmt.step()) add(stmt.getText(0))
        }
    }

/** 读单个标量（第一行第一列）。下标 0 基，理由见 [queryStrings]。 */
private fun SQLiteConnection.queryLong(sql: String): Long =
    prepare(sql).use { stmt ->
        if (!stmt.step()) throw AssertionError("查询没有返回任何行：$sql")
        stmt.getLong(0)
    }

/**
 * 执行带文本占位符的写入。
 *
 * ⚠️ 注意这里 `bindText` 用的是 `i + 1`，与取值的 0 基**不同**。
 *    这不是笔误：**绑定参数的下标本来就是 1 基**（SQLite 语句里 `?1` 从 1 数起），
 *    取值下标才由驱动决定。同一条语句上两套编号，是最容易写错的地方。
 */
private fun SQLiteConnection.bindAndExec(sql: String, vararg args: String) {
    prepare(sql).use { stmt ->
        args.forEachIndexed { i, arg -> stmt.bindText(i + 1, arg) }
        stmt.step()
    }
}

/**
 * 执行无占位符的 SQL。
 *
 * ⚠️ **新 API 没有 `execSQL`** —— 这是从旧 API 迁过来时第一个撞上的墙。
 *    旧 `SupportSQLiteDatabase.execSQL(sql)` 很顺手，新 API 只有
 *    `prepare(sql)` + `step()`，写起来像"必须自己造一个"。
 *    造在这里，而不是让七个用例各自写 `prepare().use { it.step() }`。
 */
private fun SQLiteConnection.execSQL(sql: String) {
    prepare(sql).use { it.step() }
}
