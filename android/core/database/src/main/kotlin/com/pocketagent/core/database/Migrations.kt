package com.pocketagent.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库迁移。
 *
 * ═══════════════════════════════════════════════════════════════
 *  写迁移的唯一依据是 `schemas/` 里的 JSON
 * ═══════════════════════════════════════════════════════════════
 *
 * 目录：`core/database/schemas/com.pocketagent.core.database.PocketAgentDatabase/`
 * 每升一版导出一个 `<version>.json`。**这些文件必须入库** ——
 * 它们是回答"上一版的表长什么样"的唯一来源。
 *
 * ⚠️ 迁移写完后**必须验证**：`MigrationTestHelper` 或手工开一个 v1 库再升上来。
 *    Room 只在运行时报 `IllegalStateException: Migration didn't properly handle ...`，
 *    编译期完全不检查。用户装上后崩在打开数据库那一步是最糟的失败模式 ——
 *    他连界面都进不去，也就看不到任何提示。
 */
object Migrations {

    /**
     * v1 → v2：多 Key 用途 + 多模型配置。
     *
     * 变更内容：
     * 1. `credential` 表加 `purpose` 列（默认 `LLM`）+ 对应索引
     * 2. 新建 `model_config` 表
     *
     * ⚠️ **`purpose` 的默认值只能是 `LLM`。**
     *    v1 时代只有模型 Key 一种用途，把现有行标成 LLM 是唯一正确的解释。
     *    标成 TTS 会让所有老用户的 Key 突然发不出对话请求 ——
     *    而且他们从界面上看不出来为什么。
     *
     * ⚠️ **`ALTER TABLE ADD COLUMN` 的 `NOT NULL` 必须带 `DEFAULT`**，
     *    否则 SQLite 会拒绝（旧行没有值可填）。这是 SQLite 的硬规则，不是 Room 的。
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ── 1. credential 加 purpose 列 ────────────────────────
            // 用常量字符串而非 CredentialPurpose.LLM.name ——
            // 迁移脚本应当"冻结"在写它那一刻的 schema，
            // 不应随 Kotlin 枚举改名而变化（枚举改了名，这段 SQL 就错了，
            // 但编译期完全看不出来）。
            db.execSQL(
                "ALTER TABLE `credential` ADD COLUMN `purpose` TEXT NOT NULL DEFAULT 'LLM'"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_credential_purpose` ON `credential` (`purpose`)"
            )

            // ── 2. 新建 model_config 表 ────────────────────────────
            // ⚠️ 这段 SQL 必须与 Room 从 @Entity 生成的 createSql **逐字一致**，
            //    包括反引号、列顺序、NOT NULL 的位置。
            //    不一致的表现是运行时 `Migration didn't properly handle` ——
            //    拿 schemas/2.json 的 createSql 抄，不要手写。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `model_config` (
                    `id` TEXT NOT NULL,
                    `credentialId` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `label` TEXT,
                    `tier` TEXT NOT NULL,
                    `roleMask` INTEGER NOT NULL,
                    `inputPriceOverride` REAL,
                    `outputPriceOverride` REAL,
                    `enabled` INTEGER NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_model_config_credentialId` " +
                    "ON `model_config` (`credentialId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_model_config_tier` " +
                    "ON `model_config` (`tier`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_model_config_roleMask` " +
                    "ON `model_config` (`roleMask`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_model_config_credentialId_modelId` " +
                    "ON `model_config` (`credentialId`, `modelId`)"
            )
        }
    }

    /**
     * v2 → v3：用量账本。
     *
     * 变更内容：新建 `usage_record` 表。
     *
     * ⚠️ 这是本项目**唯一一张纯新增**的迁移（不动既有表）——
     *    所以它没有"旧数据怎么填"的问题，也不存在数据丢失风险。
     *    但依然要小心 `createSql` 的逐字一致性，见 [MIGRATION_1_2] 的注释。
     *
     * ⚠️ **自增主键的写法**：`id INTEGER PRIMARY KEY AUTOINCREMENT` 是 SQLite 的
     *    特例语法 —— 它**不能**写成 `id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT`
     *    （`AUTOINCREMENT` 要求紧跟在 `PRIMARY KEY` 之后）。
     *    Room 生成的正是 `PRIMARY KEY(`id` AUTOINCREMENT)` 这种把约束并进列定义的写法。
     *    手写时抄 schemas/3.json，别按其他表的样式类推。
     *
     * ⚠️ **没有 `NOT NULL` 的列**：`upstreamInputTokens` / `upstreamOutputTokens` /
     *    `failure` / `warning` 四列可空，SQL 里就**不能**带 NOT NULL ——
     *    "上游没返回 usage" 与 "token 是 0" 是两件事，用 0 代替 null 会让
     *    "估算偏了多少" 这件事算不出来（0 会被当成真实值参与统计）。
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `usage_record` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `modelConfigId` TEXT NOT NULL,
                    `providerId` TEXT NOT NULL,
                    `consumer` TEXT NOT NULL,
                    `inputTokens` INTEGER NOT NULL,
                    `outputTokens` INTEGER NOT NULL,
                    `upstreamInputTokens` INTEGER,
                    `upstreamOutputTokens` INTEGER,
                    `failure` TEXT,
                    `warning` TEXT,
                    `createdAtMillis` INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_usage_record_createdAtMillis` " +
                    "ON `usage_record` (`createdAtMillis`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_usage_record_modelConfigId` " +
                    "ON `usage_record` (`modelConfigId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_usage_record_consumer` " +
                    "ON `usage_record` (`consumer`)"
            )
        }
    }

    /** 全部迁移，按顺序 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
