package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具展开状态机。
 *
 * 钉住三条：①开局只露基础组与入口；②"调了就展开"，哪怕是凭记忆调收着的工具；
 * ③只开不合 —— 展开过的组在同一任务里不再收起。
 */
class ToolGroupsTest {

    private val catalog = ToolCatalog()

    @Test
    fun `开局只给基础组和每组入口`() {
        val explorer = ToolExplorer(catalog)
        val offer = explorer.offer()

        val base = catalog.all.filter { ToolGrouping.groupOf(it.id) == ToolGroup.BASE }.map { it.id }
        assertEquals((base + listOf("android_tap", "root_exec", "office_tools")).sorted(), offer.ids.sorted())
        assertTrue(offer.opened.isEmpty())
    }

    @Test
    fun `调入口后整组展开`() {
        val explorer = ToolExplorer(catalog)
        assertEquals(ToolGroup.UI, explorer.onCall("android_tap"))

        val ids = explorer.offer().ids.toSet()
        assertTrue(ids.containsAll(setOf("android_tap", "android_swipe", "android_type", "android_key", "android_batch", "app_host")))
        // 别的组仍只露入口
        assertTrue("root_exec" in ids)
        assertFalse("file_read" in ids)
    }

    @Test
    fun `凭记忆调收着的工具也会展开它那一组`() {
        // 模型从上一轮记得 android_batch，直接调了它
        val explorer = ToolExplorer(catalog)
        assertEquals(ToolGroup.UI, explorer.onCall("android_batch"))
        assertTrue(explorer.offer().ids.contains("android_swipe"))
    }

    @Test
    fun `同一组重复调用只展开一次`() {
        val explorer = ToolExplorer(catalog)
        assertEquals(ToolGroup.UI, explorer.onCall("android_tap"))
        assertNull(explorer.onCall("android_tap"))
        assertNull(explorer.onCall("android_swipe"))
    }

    @Test
    fun `基础组与未知工具都不会展开任何组`() {
        val explorer = ToolExplorer(catalog)
        assertNull(explorer.onCall("screen_read"))
        assertNull(explorer.onCall("no_such_tool"))
        assertTrue(explorer.opened().isEmpty())
    }

    @Test
    fun `展开只增不减`() {
        val explorer = ToolExplorer(catalog)
        explorer.onCall("android_tap")
        val first = explorer.opened()
        explorer.onCall("root_exec")
        val second = explorer.opened()

        assertTrue(second.containsAll(first))
        assertTrue(ToolGroup.SYSTEM in second)
        assertTrue(explorer.offer().ids.contains("root_exec"))
        assertTrue("展开后基础组也不会消失", explorer.offer().ids.contains("screen_read"))
    }

    @Test
    fun `目标里有文件相关词就预先展开授权目录`() {
        val explorer = ToolExplorer(catalog)
        explorer.preload("把上周的表格导出成 xlsx 放到目录里")
        assertTrue(ToolGroup.FILES in explorer.opened())
        assertTrue(explorer.offer().ids.contains("office_run"))
    }

    @Test
    fun `目标里有系统相关词就预先展开系统组`() {
        val explorer = ToolExplorer(catalog)
        explorer.preload("帮我把这个应用的包名停用掉")
        assertTrue(ToolGroup.SYSTEM in explorer.opened())
        assertTrue(explorer.offer().ids.contains("app_disable"))
    }

    @Test
    fun `普通任务不做任何预展开`() {
        val explorer = ToolExplorer(catalog)
        explorer.preload("给张三发条消息说晚点到")
        assertTrue(explorer.opened().isEmpty())
    }

    @Test
    fun `预展开重复调用无副作用`() {
        val explorer = ToolExplorer(catalog)
        explorer.preload("读一下这个文件")
        val once = explorer.offer().ids
        explorer.preload("读一下这个文件")
        assertEquals(once, explorer.offer().ids)
    }

    @Test
    fun `工具说明里说清还有哪些组收着`() {
        val text = ToolExplorer(catalog).promptText()
        assertTrue(text.contains("另有"))
        assertTrue(text.contains("android_tap"))
        assertTrue(text.contains("root_exec"))
        assertTrue(text.contains("office_tools"))
        // 收着的组里的工具名不能出现
        assertFalse(text.contains("android_swipe："))
        assertFalse(text.contains("setting_write："))
    }

    @Test
    fun `展开后说明变长且不再提那一组收着`() {
        val closed = ToolExplorer(catalog).promptText()
        val explorer = ToolExplorer(catalog)
        explorer.onCall("android_tap")
        val open = explorer.promptText()

        assertTrue(open.length > closed.length)
        assertTrue(open.contains("android_swipe："))
        assertFalse(open.contains("「界面操作」"))
    }

    @Test
    fun `说明文本的长度在展开一两次后仍可接受`() {
        val explorer = ToolExplorer(catalog)
        explorer.onCall("android_tap")
        // 一次展开后的说明不该超过目录全文的两倍（否则分组没起到作用）
        assertTrue(explorer.promptText().length < ToolCatalog.MAX_PROMPT_CHARS * 2)
    }

    @Test
    fun `每个目录里的工具都归属唯一一组`() {
        val grouped = ToolGrouping.of.keys
        val catalogIds = catalog.all.map { it.id }.toSet()

        assertEquals("静态映射里有目录之外的条目：${grouped - catalogIds}", catalogIds, grouped)
        // 反过来：目录里每一条都有组
        for (id in catalogIds) {
            assertTrue("$id 没有分组", ToolGrouping.of.containsKey(id))
        }
    }

    @Test
    fun `每组入口都在目录里且属于该组`() {
        for (group in ToolGroup.entries) {
            val entry = group.entry ?: continue
            val spec = catalog.byId(entry)
            assertTrue("入口 $entry 不在目录里", spec != null)
            assertEquals("入口 $entry 归属不对", group, ToolGrouping.groupOf(entry))
        }
    }

    @Test
    fun `全部展开后能拿到目录里的每一条`() {
        val explorer = ToolExplorer(catalog)
        for (group in ToolGroup.entries) {
            group.entry?.let { explorer.onCall(it) }
        }
        // 组里有些工具不是入口，逐个调一遍
        for (id in ToolGrouping.of.keys) explorer.onCall(id)

        assertEquals(catalog.all.map { it.id }.sorted(), explorer.offer().ids.sorted())
        assertEquals(catalog.all.size, explorer.offer().tools.size)
    }

    @Test
    fun `同一份状态两次取到的清单完全一致`() {
        val explorer = ToolExplorer(catalog)
        explorer.onCall("root_exec")
        assertEquals(explorer.offer().ids, explorer.offer().ids)
    }
}
