package com.pocketagent.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
 * ⚠️ 本测试**不加密**（用 `FrameworkSQLiteOpenHelperFactory` 而非 SQLCipher）——
 *    验证的是表结构，与加密无关。加密路径需真机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        ApplicationProvider.getApplicationContext(),
        PocketAgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `从 v1 迁移到 v2 后表结构符合 Room 预期`() {
        // 1) 用 v1 的 schema 建库
        helper.createDatabase(TEST_DB, 1).apply { close() }

        // 2) 跑迁移，然后让 helper 用当前 @Entity 的期望 schema 校验
        //    ⚠️ 校验失败会抛 IllegalStateException，异常信息里会指明差在哪一列。
        //    这一步通过，就等于"升级不会崩在打开数据库那一步"。
        helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2).close()
    }

    // ── ⚠️ 关于"列顺序"的一个反直觉事实，别再踩 ──────────────────
    //
    // `ALTER TABLE ADD COLUMN` 只能把新列**追加到末尾**，而 `purpose` 在
    // `CredentialEntity` 里是第 3 个字段（紧跟在 providerId 后面）。
    // 于是迁移后的物理列顺序与 Room 导出的 `2.json` 里 `createSql` 的
    // 列顺序**不一致** —— 看起来像要重建表才能对齐。
    //
    // 反编译 room-runtime 2.6.1 的 `androidx.room.util.TableInfo.equals` 后确认：
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

    @Test
    fun `迁移后 model_config 的索引名与 Room 期望一致`() {
        // 单独钉住索引名：写错名字等于索引不存在，而 Room 判定"迁移未正确处理"。
        // 这是本次改动真实踩到的 bug，值得一条专门的用例。
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='model_config'"
        ).use { cursor ->
            val names = mutableListOf<String>()
            while (cursor.moveToNext()) names += cursor.getString(0)
            val expected = listOf(
                "index_model_config_credentialId",
                "index_model_config_tier",
                "index_model_config_roleMask",
                "index_model_config_credentialId_modelId",
            )
            expected.forEach {
                assertTrue("缺少索引 $it，实际有：$names", it in names)
            }
        }

        db.close()
    }

    @Test
    fun `v1 的老数据在迁移后仍然可读`() {
        // 这是迁移最容易出错的地方：结构对了但数据丢了。
        // 用一个"结构对、数据错"的迁移换来的通过，比失败更危险。
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
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
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT id, providerId, label, keyLength, isDefault, purpose FROM credential")
            .use { cursor ->
                assertTrue("迁移后应当还有一条记录", cursor.moveToFirst())
                assertEquals("c1", cursor.getString(0))
                assertEquals("deepseek", cursor.getString(1))
                assertEquals("主力", cursor.getString(2))
                assertEquals(35, cursor.getInt(3))
                assertEquals(1, cursor.getInt(4))
                // ★ 关键断言：老数据的 purpose 必须是 LLM。
                //   标成 TTS 会让所有老用户的 Key 突然发不出对话请求，
                //   而且他们从界面上完全看不出来为什么。
                assertEquals("LLM", cursor.getString(5))
            }

        db.close()
    }

    @Test
    fun `迁移后 model_config 表可正常读写`() {
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

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

        db.query("SELECT modelId, tier, roleMask, enabled FROM model_config").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("deepseek-chat", cursor.getString(0))
            assertEquals("LIGHT", cursor.getString(1))
            // roleMask = 3 = SCHEDULER(1) | WORKER(2)，即"既是调度者也是执行者"
            assertEquals(3, cursor.getInt(2))
            assertEquals(1, cursor.getInt(3))
        }

        db.close()
    }

    @Test
    fun `同一凭据下不允许重复配置同一个模型`() {
        // 唯一索引的验证。没有它，用户可以从两个入口把同一个模型加两遍，
        // 列表里出现两条一模一样的记录，调度时也会重复计入。
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        val insert = """
            INSERT INTO model_config
                (id, credentialId, modelId, label, tier, roleMask,
                 inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
            VALUES
                (?, 'c1', 'gpt-4o-mini', NULL, 'STANDARD', 2, NULL, NULL, 1, 1000)
        """.trimIndent()

        db.execSQL(insert, arrayOf("m1"))

        var violated = false
        try {
            db.execSQL(insert, arrayOf("m2"))
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
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        val insert = """
            INSERT INTO model_config
                (id, credentialId, modelId, label, tier, roleMask,
                 inputPriceOverride, outputPriceOverride, enabled, createdAtMillis)
            VALUES
                (?, ?, 'gpt-4o-mini', NULL, 'STANDARD', 2, NULL, NULL, 1, 1000)
        """.trimIndent()

        db.execSQL(insert, arrayOf("m1", "c1"))
        db.execSQL(insert, arrayOf("m2", "c2"))

        db.query("SELECT COUNT(*) FROM model_config").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }

        db.close()
    }

    @Test
    fun `迁移后 credential 的 purpose 索引存在`() {
        // 索引漏建的后果是"能跑但慢" —— 不会报错，只在 Key 多起来之后显现。
        // 而那时已经很难联想到是迁移漏了索引。
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='credential'")
            .use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                assertTrue(
                    "缺少 purpose 索引，实际有：$names",
                    names.any { it == "index_credential_purpose" },
                )
            }

        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
