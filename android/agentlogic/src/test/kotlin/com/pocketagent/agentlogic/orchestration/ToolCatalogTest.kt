package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手脚本目录的测试。
 *
 * 这里最值钱的一条是 [coverage 与真实工具表一致] —— 它把 `:mcp` 里那 18 个
 * `McpTool.name` 逐字钉在这里。新增工具而忘了登记，这条会红。
 * 没有它，目录会慢慢变成一份"写给人看的历史文档"。
 */
class ToolCatalogTest {

    private val catalog = ToolCatalog()

    /** 与 `:mcp` 源码里的 `override val name` 逐字一致（2026-11-04 核对）。 */
    private val realToolNames = listOf(
        "app_host",
        "office_tools",
        "office_run",
        "persona_brief",
        "context_offload",
        "context_recall",
        "root_exec",
        "screen_shot",
        "screen_read",
        "android_swipe",
        "android_batch",
        "file_read",
        "setting_write",
        "prop_write",
        "app_disable",
        "android_tap",
        "android_type",
        "android_key",
    )

    // ── 覆盖 ────────────────────────────────────────────────────────────────

    @Test
    fun `内建目录覆盖全部 18 个真实工具`() {
        assertEquals(18, catalog.all.size)
        assertEquals(18, realToolNames.size)
        for (name in realToolNames) {
            assertNotNull("目录里缺 $name", catalog.byId(name))
        }
    }

    @Test
    fun `coverage 与真实工具表一致`() {
        val coverage = catalog.coverage(realToolNames)
        assertTrue(coverage.explain(), coverage.complete)
        assertEquals(18, coverage.registered.size)
    }

    @Test
    fun `有工具没登记会被找出来`() {
        val coverage = catalog.coverage(realToolNames + "plugin_pdf_scan")
        assertFalse(coverage.complete)
        assertEquals(listOf("plugin_pdf_scan"), coverage.unregistered)
        assertTrue(coverage.explain().contains("有工具没登记"))
    }

    @Test
    fun `登记了但工具不存在会被找出来`() {
        val coverage = catalog.coverage(realToolNames - "android_key")
        assertFalse(coverage.complete)
        assertEquals(listOf("android_key"), coverage.phantom)
        assertTrue(coverage.explain().contains("登记了但工具不存在"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `重复工具名直接构造失败`() {
        // 同名两条时，后一条会静默遮掉前一条 —— 那是最难查的一类"配置写了但没生效"
        ToolCatalog(BuiltinTools.all + BuiltinTools.all.first())
    }

    @Test
    fun `未知工具查出来是 null 而不是空对象`() {
        assertNull(catalog.byId("nope_nope"))
    }

    // ── 代价分组 ────────────────────────────────────────────────────────────

    @Test
    fun `占屏集合里有动作工具但读文件不在里面`() {
        val screen = catalog.requiringScreen().map { it.id }.toSet()
        assertTrue("android_tap", "android_tap" in screen)
        assertTrue("android_batch", "android_batch" in screen)
        assertTrue("screen_read", "screen_read" in screen)
        assertFalse("file_read 不该占屏", "file_read" in screen)
        assertFalse("office_run 不该占屏", "office_run" in screen)
        // 虚拟屏托管改了屏幕，但改的是用户看不见的那块
        assertFalse("app_host 不该算占屏", "app_host" in screen)
    }

    @Test
    fun `不可逆集合只收真正撤不回来的`() {
        val irreversible = catalog.irreversible().map { it.id }.toSet()
        assertEquals(
            setOf("android_tap", "android_type", "android_batch", "prop_write", "root_exec", "office_run"),
            irreversible,
        )
        // setting_write 改回去就恢复；它不该进这个集合
        assertFalse("setting_write" in irreversible)
        // 停用应用能 pm enable 回来
        assertFalse("app_disable" in irreversible)
    }

    @Test
    fun `只读工具都标了 READ_ONLY`() {
        val readOnly = catalog.all.filter { it.readOnly }.map { it.id }.toSet()
        assertTrue(
            readOnly.containsAll(
                setOf("screen_read", "screen_shot", "file_read", "persona_brief", "context_recall", "office_tools"),
            ),
        )
        assertFalse("android_tap" in readOnly)
    }

    @Test
    fun `规划顺序先看后改最后碰不可逆`() {
        val order = catalog.planningOrder()
        val firstMutating = order.indexOfFirst { it.mutating }
        val lastReadOnly = order.indexOfLast { it.readOnly }
        assertTrue("只读的必须排在会动手的前面", lastReadOnly < firstMutating)
        assertFalse("最后一条必须是不容易撤回的", order.last().reversible)
    }

    @Test
    fun `每条都带代价说明`() {
        for (spec in catalog.all) {
            assertTrue("${spec.id} 的代价文案为空", spec.cost().isNotBlank())
        }
        assertEquals("会占住你的屏幕", catalog.byId("android_tap")!!.cost())
        assertEquals("只看不动", catalog.byId("file_read")!!.cost())
        assertEquals("做了不容易撤回", catalog.byId("prop_write")!!.cost())
        assertEquals("会改动东西", catalog.byId("setting_write")!!.cost())
    }

    // ── 与运行时对账 ────────────────────────────────────────────────────────

    @Test
    fun `运行时说占屏而目录说不占屏会被抓出来`() {
        // 目录说 android_tap 占屏；这里假装运行时说不占屏
        val drift = catalog.screenDrift { id -> if (id == "android_tap") false else catalog.byId(id)!!.needsScreen }
        assertEquals(listOf("android_tap（目录说占屏）"), drift)
    }

    @Test
    fun `进程内工具不参与占屏对账`() {
        // 所有进程内/授权目录/回环工具，无论 occupies 返回什么，都不该出现在漂移里
        val drift = catalog.screenDrift { false }
        assertTrue(drift.none { it.startsWith("persona_brief") || it.startsWith("office_run") || it.startsWith("app_host") })
    }

    // ── 提示词段 ────────────────────────────────────────────────────────────

    @Test
    fun `提示词段用工具名与使用时机`() {
        val text = catalog.describeForPrompt()
        assertTrue(text.contains("android_tap："))
        assertTrue(text.contains("（会占屏）"))
        assertTrue(text.contains("可用工具（18 条）"))
        assertTrue("超预算", text.length <= ToolCatalog.MAX_PROMPT_CHARS)
    }

    @Test
    fun `提示词段条数封顶并说明省略多少`() {
        val text = catalog.describeForPrompt(limit = 5)
        assertTrue(text.contains("另有 13 条没列出"))
        // 头部 1 行 + 5 条 + 省略说明 1 行
        assertEquals(7, text.lines().size)
    }

    // ── ToolSpec 自身校验 ───────────────────────────────────────────────────

    private fun spec(
        id: String = "sample_tool",
        title: String = "样例",
        effects: Set<ToolEffect> = setOf(ToolEffect.READ_ONLY),
        needsScreen: Boolean = false,
        reversible: Boolean = true,
        expectedMs: Long = 100,
        timeoutMs: Long = 5_000,
        whenToUse: String = "需要样例的时候用它",
        failureHint: String = "失败了就换个做法",
        source: ToolSource = ToolSource.BUILTIN,
    ) = ToolSpec(
        id = id,
        title = title,
        channel = ToolChannel.IN_PROCESS,
        effects = effects,
        needsScreen = needsScreen,
        reversible = reversible,
        expectedMs = expectedMs,
        timeoutMs = timeoutMs,
        whenToUse = whenToUse,
        failureHint = failureHint,
        source = source,
    )

    @Test
    fun `合法声明能构造出来`() {
        assertEquals("sample_tool", spec().id)
        assertTrue(spec().readOnly)
        assertFalse(spec(effects = setOf(ToolEffect.WRITES_DATA)).readOnly)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工具名必须是小写下划线`() {
        spec(id = "BadID")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工具名不能太短`() {
        spec(id = "ab")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超时不能低于下限`() {
        spec(expectedMs = 1, timeoutMs = 50)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `预期耗时比超时还长会被拒`() {
        spec(expectedMs = 9_000, timeoutMs = 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `没有声明效果会被拒`() {
        spec(effects = emptySet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `既只读又写数据会被拒`() {
        spec(effects = setOf(ToolEffect.READ_ONLY, ToolEffect.WRITES_DATA))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `使用时机太短会被拒`() {
        spec(whenToUse = "用它")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `文案里不许出现 markdown 粗体`() {
        spec(title = "**样例**")
    }

    @Test
    fun `插件来源能被单独筛出来`() {
        val pluginTool = spec(id = "p_weather_now", source = ToolSource.PLUGIN)
        val mixed = ToolCatalog(listOf(pluginTool))
        assertEquals(1, mixed.ofSource(ToolSource.PLUGIN).size)
        assertEquals(0, mixed.ofSource(ToolSource.BUILTIN).size)
    }
}
