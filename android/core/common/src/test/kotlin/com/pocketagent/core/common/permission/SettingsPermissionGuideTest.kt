package com.pocketagent.core.common.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsPermissionGuide] 的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一组测试守的是「用户会照着做的那句话」
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里写错的代价不是崩溃，而是**用户按指引做了一遍、什么都没发生**。
 * 而它的两个消费方（权限页 / 执行层）必须拿到**同一份**文案 ——
 * 各写一份的话，用户在权限页抄到的命令与执行失败时看到的那条会不一样，
 * 而两条里最多只有一条是对的。
 *
 * ⇒ 所以：命令**逐字**断言（它们是真机上跑通过的），
 *    并且断言两条命令**互不相同**（合并成一条时至少有一条会失效）。
 */
class SettingsPermissionGuideTest {

    // ── 命令 ──────────────────────────────────────────────────────

    @Test
    fun `两条命令逐字对得上真机实测过的那两条`() {
        // ★ 逐字，不是 contains 某个片段 —— 因为它们是在红米 K60 / Android 15 上
        //   真的跑通过的（`pm grant …` → granted=true；`appops set … allow` → allow）。
        //   改一个字（比如漏掉 `shell`、或者把 allow 写成 enable）都会变红。
        assertEquals(
            "adb shell pm grant $APP_ID android.permission.WRITE_SECURE_SETTINGS",
            SettingsPermissionGuide.adbCommandFor(
                SettingsPermission.WRITE_SECURE_SETTINGS,
                APP_ID,
            ),
        )
        assertEquals(
            "adb shell appops set $APP_ID WRITE_SETTINGS allow",
            SettingsPermissionGuide.adbCommandFor(
                SettingsPermission.WRITE_SETTINGS_APPOP,
                APP_ID,
            ),
        )
    }

    @Test
    fun `两条命令不是同一条`() {
        // 防"图省事共用一个字符串"。合并之后，其中一项拿到的命令必然无效 ——
        // 而它不会报错，只会静默地什么都没授。
        assertFalse(
            SettingsPermissionGuide.adbCommandFor(
                SettingsPermission.WRITE_SECURE_SETTINGS,
                APP_ID,
            ) == SettingsPermissionGuide.adbCommandFor(
                SettingsPermission.WRITE_SETTINGS_APPOP,
                APP_ID,
            ),
        )
    }

    @Test
    fun `命令里的包名来自参数，不是硬编码`() {
        // ⚠️ 硬编码成 `com.pocketagent` 的后果：debug 构建（`com.pocketagent.debug`）
        //   拿到的 `pm grant` 是一条**跑得通、但什么也没授**的命令。
        val other = "com.example.another.app"

        for (permission in SettingsPermission.values()) {
            val command = SettingsPermissionGuide.adbCommandFor(permission, other)
            assertTrue("$permission 的命令必须带上真实的应用 id", command.contains(other))
            assertFalse("$permission 不能出现硬编码包名", command.contains("com.pocketagent"))
        }
    }

    // ── 「手机上能不能自己解决」──────────────────────────────────

    @Test
    fun `签名级那一项在手机上没有入口，appop 那一项有`() {
        // ★ 这个布尔值决定界面上**给不给按钮**。给错的代价是
        //   用户去系统设置里逐页找一个不存在的条目。
        assertFalse(
            "WRITE_SECURE_SETTINGS 是签名级权限，系统设置里没有它的开关",
            SettingsPermissionGuide.isReachableOnDevice(
                SettingsPermission.WRITE_SECURE_SETTINGS,
            ),
        )
        assertTrue(
            "WRITE_SETTINGS 是 appop 型，特殊应用权限里有开关",
            SettingsPermissionGuide.isReachableOnDevice(
                SettingsPermission.WRITE_SETTINGS_APPOP,
            ),
        )
    }

    @Test
    fun `两项的可达性不同 —— 这是它们必须分开的全部理由`() {
        // 如果哪天有人把两项的可达性改成一样，那说明他把它们合并了。
        assertFalse(
            SettingsPermissionGuide.isReachableOnDevice(SettingsPermission.WRITE_SECURE_SETTINGS) ==
                SettingsPermissionGuide.isReachableOnDevice(
                    SettingsPermission.WRITE_SETTINGS_APPOP,
                ),
        )
    }

    // ── 指引文案 ──────────────────────────────────────────────────

    @Test
    fun `签名级那一项的指引只说电脑，不提系统设置页`() {
        // ⚠️ 反向断言。文案里出现「特殊应用权限」就等于把用户引到
        //    一个没有这个开关的地方 —— 而他会以为是机型差异。
        val text = SettingsPermissionGuide.guidanceFor(
            SettingsPermission.WRITE_SECURE_SETTINGS,
            APP_ID,
        )

        assertTrue("必须给出那条命令", text.contains("adb shell pm grant $APP_ID"))
        assertTrue("要说清是在电脑上做", text.contains("在电脑上执行"))
        assertFalse("不能指向系统设置页 —— 那里没有这一项", text.contains("特殊应用权限"))
    }

    @Test
    fun `appop 那一项的指引先说系统设置页，并明确说 pm grant 无效`() {
        // ★ 两条都要有：
        //   ① 手机上能自己开 → 必须先说那一条（用户不需要电脑）
        //   ② 网上到处能搜到 `pm grant … WRITE_SETTINGS`，而它对这一项**只会报错**。
        //      实测报 `SecurityException: … is managed by role`，退出码 255。
        //      但报错里没有一句话提到"改用 appops" —— 所以必须提前拦住。
        val text = SettingsPermissionGuide.guidanceFor(
            SettingsPermission.WRITE_SETTINGS_APPOP,
            APP_ID,
        )

        assertTrue("先给手机上能做的那条路", text.contains("特殊应用权限"))
        assertTrue("要明确说 pm grant 无效", text.contains("pm grant"))
        assertFalse(
            "不能把那条会报错的命令交到用户手里",
            text.contains("pm grant $APP_ID android.permission.WRITE_SETTINGS"),
        )
        assertTrue("给出命令行通路", text.contains("adb shell appops set $APP_ID"))
    }

    @Test
    fun `两项的指引不是同一句话`() {
        assertFalse(
            SettingsPermissionGuide.guidanceFor(SettingsPermission.WRITE_SECURE_SETTINGS, APP_ID) ==
                SettingsPermissionGuide.guidanceFor(
                    SettingsPermission.WRITE_SETTINGS_APPOP,
                    APP_ID,
                ),
        )
    }

    @Test
    fun `两类指引都把应用 id 带上`() {
        // 漏掉包名的话，用户拿到的是一条少一个参数的命令。
        for (permission in SettingsPermission.values()) {
            assertTrue(
                "$permission 的指引里应当出现应用 id",
                SettingsPermissionGuide.guidanceFor(permission, APP_ID).contains(APP_ID),
            )
        }
    }

    @Test
    fun `指引与命令里没有 markdown 星号 —— 界面不解析 markdown，星号会原样显示`() {
        // ★ 这一条是 2026-09-23 **在真机上目击到之后**补的。
        //
        //   appop 分支里原本写的是 `**这一项无效**`，而 Android 的 `Text`
        //   与 Compose `Text` 都不解析 markdown —— 用户在能力页上看到的
        //   就是两个星号挂在句子中间（截图确认过）。
        //
        //   ⚠️ 关键是**当时没有任何东西报错**：编辑器、编译器、
        //   以及本文件里其它 8 条测试全都绿的。所以这条断言的价值
        //   不在于"它现在是对的"，而在于**它以后会变红**。
        //
        //   全项目的同类扫描在 `tools/verify/check_ui_markdown.py`
        //   （覆盖所有模块的所有字符串字面量）；这里钉住的是
        //   "这个 API 的输出契约"，两者不重复：脚本防的是新代码，
        //   这条防的是"有人改这个文件时又写回去"。
        for (permission in SettingsPermission.values()) {
            val guidance = SettingsPermissionGuide.guidanceFor(permission, APP_ID)
            val command = SettingsPermissionGuide.adbCommandFor(permission, APP_ID)

            assertFalse(
                "$permission 的指引里不能有 markdown 星号（界面会原样显示）",
                guidance.contains("**"),
            )
            assertFalse(
                "$permission 的命令里不能有 markdown 星号",
                command.contains("**"),
            )
        }
    }

    @Test
    fun `去掉星号时不能顺手把那句话删掉`() {
        // ⚠️ 防"为了过上面那条检查，把强调的部分整段删了"。
        //    那会让检查变绿、而用户丢掉一句本来该看到的话 ——
        //    这是比星号本身更坏的修法。
        val text = SettingsPermissionGuide.guidanceFor(
            SettingsPermission.WRITE_SETTINGS_APPOP,
            APP_ID,
        )

        assertTrue("「这一项无效」这句判断必须还在", text.contains("这一项无效"))
        assertTrue("强调要换成直角引号，不是直接删掉", text.contains("「这一项无效」"))
    }

    private companion object {
        const val APP_ID = "com.example.settings.guide.test"
    }
}
