package com.pocketagent.capabilitylogic

import com.pocketagent.core.common.permission.SettingsPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写设置的授权映射 —— 守的是**"两类授权不能合并"**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这几条测试值得存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里没有任何"会不会崩溃"的问题 —— 映射写错、文案写错，程序都照跑。
 * 错的是**用户照着做了一遍，什么都没发生**。
 *
 * 最典型的一种错法：把 `Settings.System` 也归到 `WRITE_SECURE_SETTINGS`。
 * 那看起来更"整齐"（"写设置嘛，就一个权限"），而且**代码更短**。
 * 但它会让界面给出一条 `adb shell pm grant … WRITE_SECURE_SETTINGS` ——
 * 那条命令对 `Settings.System` **毫无作用**（`WRITE_SETTINGS` 是 appop 型，
 * 保护级别里没有 `development` 位，包管理器授不了）。
 *
 * ⇒ 用户执行完、界面依然说没权限、他再执行一遍……最后放弃。
 *   全程没有任何报错，因为每一步都"成功"了。
 */
class SettingsPermissionPolicyTest {

    // ══════════════════════════════════════════════════════════
    //  映射
    // ══════════════════════════════════════════════════════════

    @Test
    fun `GLOBAL 与 SECURE 需要的是同一个权限`() {
        // 这两者走的是同一个 SettingsProvider 通道、同一个权限。
        // 钉住它是因为：如果哪天有人给 SECURE 单独开一条路，
        // 说明他对 Android 的 Settings 模型理解有偏差，值得被拦下来看一眼。
        assertEquals(
            SettingsPermission.WRITE_SECURE_SETTINGS,
            SettingsPermissionPolicy.requiredFor(SettingNamespace.GLOBAL),
        )
        assertEquals(
            SettingsPermission.WRITE_SECURE_SETTINGS,
            SettingsPermissionPolicy.requiredFor(SettingNamespace.SECURE),
        )
    }

    @Test
    fun `SYSTEM 需要的不是同一个权限`() {
        // ★ 本文件最该守住的一条。见类注释。
        assertEquals(
            SettingsPermission.WRITE_SETTINGS_APPOP,
            SettingsPermissionPolicy.requiredFor(SettingNamespace.SYSTEM),
        )
        assertNotEquals(
            "把 SYSTEM 归到 WRITE_SECURE_SETTINGS 会让界面给出无效指引",
            SettingsPermissionPolicy.requiredFor(SettingNamespace.GLOBAL),
            SettingsPermissionPolicy.requiredFor(SettingNamespace.SYSTEM),
        )
    }

    @Test
    fun `三类命名空间一共只用两种授权`() {
        // ⚠️ 断言的是**去重后恰好两种**，不是"包含这两种"。
        //    写成 contains 的话，多出第三种授权（= 又有一条没被想清楚的路径）
        //    也不会变红。
        assertEquals(
            setOf(
                SettingsPermission.WRITE_SECURE_SETTINGS,
                SettingsPermission.WRITE_SETTINGS_APPOP,
            ),
            SettingNamespace.values()
                .map { SettingsPermissionPolicy.requiredFor(it) }
                .toSet(),
        )
    }

    // ══════════════════════════════════════════════════════════
    //  指引文案
    // ══════════════════════════════════════════════════════════

    @Test
    fun `两类指引各自给出实测可行的那条命令`() {
        // ★ 断言的是**逐字**的那两条命令 —— 因为它们是真机上跑通过的。
        //   （2026-09-23，红米 K60 / Android 15：
        //     `pm grant … WRITE_SECURE_SETTINGS` → granted=true
        //     `appops set … WRITE_SETTINGS allow` → allow）
        assertTrue(
            "SECURE 类的命令必须逐字对得上",
            SettingsPermissionPolicy.guidanceFor(SettingNamespace.GLOBAL, APP_ID)
                .contains("pm grant $APP_ID android.permission.WRITE_SECURE_SETTINGS"),
        )
        assertTrue(
            "SYSTEM 类的命令必须逐字对得上",
            SettingsPermissionPolicy.guidanceFor(SettingNamespace.SYSTEM, APP_ID)
                .contains("appops set $APP_ID WRITE_SETTINGS allow"),
        )
    }

    @Test
    fun `SYSTEM 类的指引不会给出 pm grant 那条无效命令`() {
        // ★ 这条防的是一个**已经犯过**的错：文案里同时写着
        //   「它不能通过 adb 命令授予」和一条 `adb shell appops …` ——
        //   自相矛盾，用户读完不知道该信哪一句。
        //
        //   而真正不能用的只是 `pm grant` 那一条。实测它会报
        //   `SecurityException: Permission … is managed by role`（退出码 255），
        //   所以**不能把这条命令交到用户手里**：他照着做会撞上一个
        //   完全看不懂的报错，而报错里没有一句话提到"你该改用 appops"。
        //
        // ⚠️ 断言的是**完整的命令串**，不是 `pm grant` 四个字 ——
        //    文案里需要提到它（"它不能用 pm grant 授予"）才能拦住用户去试。
        assertFalse(
            "这条命令实测会报错，不能给用户",
            SettingsPermissionPolicy.guidanceFor(SettingNamespace.SYSTEM, APP_ID)
                .contains("pm grant $APP_ID android.permission.WRITE_SETTINGS"),
        )
    }

    @Test
    fun `SYSTEM 类的指引指向系统设置界面`() {
        val system = SettingsPermissionPolicy.guidanceFor(SettingNamespace.SYSTEM, APP_ID)

        assertTrue(
            "appop 型权限只能由用户手动开，指引要说清去哪里开",
            system.contains("修改系统设置"),
        )
        assertTrue(
            "给出 appops 这条命令行通路 —— 界面上可以直接做成可复制的一段",
            system.contains("appops set"),
        )
    }

    @Test
    fun `两种指引不是同一句话`() {
        // 防的是"图省事共用一个字符串"。合并之后上面两条反向断言里
        // 至少有一条会红，但那条红的**归因**不如这一条直接。
        assertNotEquals(
            SettingsPermissionPolicy.guidanceFor(SettingNamespace.GLOBAL, APP_ID),
            SettingsPermissionPolicy.guidanceFor(SettingNamespace.SYSTEM, APP_ID),
        )
    }

    @Test
    fun `指引里的包名来自参数，不是硬编码`() {
        // ★ 硬编码成 `com.pocketagent` 的后果：debug 构建（`com.pocketagent.debug`）
        //   会拿到一条**跑得通、但什么也没授**的 `pm grant` 命令。
        //   用户执行完没报错，界面依然说没权限 —— 这是本项目最难查的一类 bug。
        val other = "com.example.some.other.app"

        val text = SettingsPermissionPolicy.guidanceFor(SettingNamespace.GLOBAL, other)

        assertTrue("指引必须带上真实的应用 id", text.contains(other))
        assertFalse(
            "不能出现任何硬编码的包名",
            text.contains("com.pocketagent"),
        )
    }

    @Test
    fun `两类指引都把应用 id 带上`() {
        // 上面那条只查了 SECURE 类。SYSTEM 类的 `appops set` 同样需要包名，
        // 漏掉的话用户拿到的是一条少一个参数的命令。
        for (namespace in SettingNamespace.values()) {
            assertTrue(
                "$namespace 的指引里应当出现应用 id",
                SettingsPermissionPolicy.guidanceFor(namespace, APP_ID).contains(APP_ID),
            )
        }
    }

    private companion object {
        /** 测试里**刻意**用一个不是本项目真实 id 的值 —— 见「不是硬编码」那条。 */
        const val APP_ID = "com.example.settings.policy.test"
    }
}
