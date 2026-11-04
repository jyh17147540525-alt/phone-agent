package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四个系统级工具的测试。
 *
 * ★ 钉的只有两件事：
 *   1. **参数不合法时一个字节都不发** —— 这些工具在 root 下执行，
 *      少发一条错命令就少一次不可撤销的后果；
 *   2. 结局分档（Ok / Rejected / Failed）翻译成**模型能据此行动**的话术 ——
 *      该改参数的让它改参数，该停的让它停。
 */
class SystemOpsToolsTest {

    // ── 假端口：记录调用，可指定结局 ─────────────────────────────
    private class FakeOps(
        private val result: SystemOpOutcome = SystemOpOutcome.Ok("ok"),
    ) : SystemOpsPort {
        val calls = mutableListOf<String>()
        override fun readFile(path: String): SystemOpOutcome {
            calls += "read:$path"; return result
        }

        override fun putSetting(namespace: String, key: String, value: String): SystemOpOutcome {
            calls += "put:$namespace/$key=$value"; return result
        }

        override fun setProp(name: String, value: String): SystemOpOutcome {
            calls += "prop:$name=$value"; return result
        }

        override fun disablePackage(packageName: String): SystemOpOutcome {
            calls += "disable:$packageName"; return result
        }
    }

    private val allow: ActionSafetyPort = object : ActionSafetyPort {
        override suspend fun review(query: ActionSafetyQuery): ActionSafetyOutcome =
            ActionSafetyOutcome.Allowed
    }
    private val hasRoot: () -> Boolean = { true }
    private val noRoot: () -> Boolean = { false }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ── file_read ────────────────────────────────────────────────
    @Test
    fun `读文件成功返回内容`() = runTest {
        val ops = FakeOps(SystemOpOutcome.Ok("hello"))
        val out = FileReadTool(ops, hasRoot).call(args("path" to "/data/local/tmp/a.txt"))
        assertTrue(out is ToolOutcome.Success)
        assertTrue((out as ToolOutcome.Success).text.contains("hello"))
        assertEquals(listOf("read:/data/local/tmp/a.txt"), ops.calls)
    }

    @Test
    fun `相对路径不发包`() = runTest {
        val ops = FakeOps()
        val out = FileReadTool(ops, hasRoot).call(args("path" to "sdcard/a.txt"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `带 shell 元字符的路径不发包`() = runTest {
        val ops = FakeOps()
        val out = FileReadTool(ops, hasRoot).call(args("path" to "/data/x; rm -rf /"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `块设备一律拒绝且不发包`() = runTest {
        val ops = FakeOps()
        val out = FileReadTool(ops, hasRoot).call(args("path" to "/dev/block/sda"))
        assertTrue(out is ToolOutcome.Refused)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `超长文件被截断并说明原长`() = runTest {
        val ops = FakeOps(SystemOpOutcome.Ok("x".repeat(30_000)))
        val out = FileReadTool(ops, hasRoot).call(args("path" to "/data/local/tmp/big.log"))
        val text = (out as ToolOutcome.Success).text
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("30000"))
    }

    @Test
    fun `没有 root 时读文件直接被拒`() = runTest {
        val ops = FakeOps()
        val out = FileReadTool(ops, noRoot).call(args("path" to "/data/local/tmp/a.txt"))
        assertTrue(out is ToolOutcome.Refused)
        assertTrue(ops.calls.isEmpty())
    }

    // ── setting_write ────────────────────────────────────────────
    @Test
    fun `改设置成功并把命名空间传下去`() = runTest {
        val ops = FakeOps()
        val tool = SettingWriteTool(ops, allow, hasRoot)
        val out = tool.call(
            args(
                "namespace" to "secure",
                "key" to "enabled_accessibility_services",
                "value" to "a/b",
                "purpose" to "打开无障碍",
            ),
        )
        assertTrue(out is ToolOutcome.Success)
        assertEquals(listOf("put:secure/enabled_accessibility_services=a/b"), ops.calls)
    }

    @Test
    fun `非法命名空间不发包`() = runTest {
        val ops = FakeOps()
        val tool = SettingWriteTool(ops, allow, hasRoot)
        val out = tool.call(args("namespace" to "user", "key" to "k", "value" to "v", "purpose" to "p"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `缺 purpose 不发包`() = runTest {
        val ops = FakeOps()
        val tool = SettingWriteTool(ops, allow, hasRoot)
        val out = tool.call(args("namespace" to "secure", "key" to "k", "value" to "v"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `值是引号注入时不发包`() = runTest {
        val ops = FakeOps()
        val tool = SettingWriteTool(ops, allow, hasRoot)
        val out = tool.call(
            args("namespace" to "secure", "key" to "k", "value" to "v'; reboot; '", "purpose" to "p"),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `安全判定拦下时一个字节都不执行`() = runTest {
        val ops = FakeOps()
        val blocked = object : ActionSafetyPort {
            override suspend fun review(query: ActionSafetyQuery): ActionSafetyOutcome =
                ActionSafetyOutcome.Blocked("不允许改系统设置", canFallbackToManual = false)
        }
        val tool = SettingWriteTool(ops, blocked, hasRoot)
        val out = tool.call(args("namespace" to "system", "key" to "k", "value" to "v", "purpose" to "p"))
        assertTrue(out is ToolOutcome.Refused)
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `没有 root 时改设置直接被拒`() = runTest {
        val ops = FakeOps()
        val tool = SettingWriteTool(ops, allow, noRoot)
        val out = tool.call(args("namespace" to "secure", "key" to "k", "value" to "v", "purpose" to "p"))
        assertTrue(out is ToolOutcome.Refused)
        assertTrue(ops.calls.isEmpty())
    }

    // ── prop_write ───────────────────────────────────────────────
    @Test
    fun `改属性成功`() = runTest {
        val ops = FakeOps()
        val tool = PropWriteTool(ops, allow, hasRoot)
        val out = tool.call(args("name" to "debug.pocketagent", "value" to "1", "purpose" to "调试开关"))
        assertTrue(out is ToolOutcome.Success)
        assertEquals(listOf("prop:debug.pocketagent=1"), ops.calls)
    }

    @Test
    fun `属性名非法不发包`() = runTest {
        val ops = FakeOps()
        val tool = PropWriteTool(ops, allow, hasRoot)
        val out = tool.call(args("name" to "debug;reboot", "value" to "1", "purpose" to "p"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    // ── app_disable ──────────────────────────────────────────────
    @Test
    fun `停用应用成功时回执写清怎么恢复`() = runTest {
        val ops = FakeOps()
        val tool = AppDisableTool(ops, allow, hasRoot)
        val out = tool.call(args("package" to "com.example.junk", "purpose" to "用户说用不上"))
        val text = (out as ToolOutcome.Success).text
        assertTrue(text.contains("已停用 com.example.junk"))
        assertTrue(text.contains("pm enable"))
        assertEquals(listOf("disable:com.example.junk"), ops.calls)
    }

    @Test
    fun `系统关键包永远不许停用`() = runTest {
        val ops = FakeOps()
        val tool = AppDisableTool(ops, allow, hasRoot)
        listOf("android", "com.android.systemui", "com.android.settings", "com.android.phone").forEach {
            val out = tool.call(args("package" to it, "purpose" to "p"))
            assertTrue("$it 应被拒绝", out is ToolOutcome.Refused)
        }
        assertTrue(ops.calls.isEmpty())
    }

    @Test
    fun `包名非法不发包`() = runTest {
        val ops = FakeOps()
        val tool = AppDisableTool(ops, allow, hasRoot)
        val out = tool.call(args("package" to "com.x; rm -rf /", "purpose" to "p"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(ops.calls.isEmpty())
    }

    // ── 结局翻译 ─────────────────────────────────────────────────
    @Test
    fun `本地拒绝翻译成可重试并说清是参数问题`() = runTest {
        val ops = FakeOps(SystemOpOutcome.Rejected("包名不合法"))
        val tool = AppDisableTool(ops, allow, hasRoot)
        val out = tool.call(args("package" to "com.example.app", "purpose" to "p"))
        val text = (out as ToolOutcome.Failed).reason
        assertTrue(text.contains("没有执行"))
        assertTrue(text.contains("参数"))
    }

    @Test
    fun `执行失败翻译成失败并带原因`() = runTest {
        val ops = FakeOps(SystemOpOutcome.Failed("SELinux denied"))
        val tool = PropWriteTool(ops, allow, hasRoot)
        val out = tool.call(args("name" to "debug.x", "value" to "1", "purpose" to "p"))
        assertTrue((out as ToolOutcome.Failed).reason.contains("SELinux denied"))
    }

    // ── 参数规则（纯逻辑）───────────────────────────────────────
    @Test
    fun `命名空间只认三个`() {
        assertEquals("system", SystemArgs.namespace(" system "))
        assertEquals(null, SystemArgs.namespace("user"))
    }

    @Test
    fun `路径必须是绝对路径`() {
        assertEquals("/data/x", SystemArgs.path("/data/x"))
        assertEquals(null, SystemArgs.path("data/x"))
        assertEquals(null, SystemArgs.path("/data/x y"))
    }

    @Test
    fun `属性值允许斜杠与中文但不允许引号`() {
        assertEquals("Asia/Shanghai", SystemArgs.value("Asia/Shanghai"))
        assertEquals("中文值", SystemArgs.value("中文值"))
        assertEquals(null, SystemArgs.value("a\"b"))
        assertEquals(null, SystemArgs.value(""))
    }

    @Test
    fun `包名必须像包名`() {
        assertEquals("com.a.b", SystemArgs.packageName("com.a.b"))
        assertEquals(null, SystemArgs.packageName("com"))
        assertEquals(null, SystemArgs.packageName("1com.a"))
    }
}
