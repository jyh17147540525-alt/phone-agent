package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限档位的判定。
 *
 * ⚠️ 这组测试守的是**用户以为自己开了什么**。判错一档不会报错，
 *    只会让"我只开了只读"的那次对话悄悄动了东西。
 */
class AgentPermissionTest {

    private val read = setOf(ToolEffect.READ_ONLY)
    private val tap = setOf(ToolEffect.READ_ONLY, ToolEffect.MOVES_SCREEN)
    private val write = setOf(ToolEffect.READ_ONLY, ToolEffect.WRITES_DATA)
    private val send = setOf(ToolEffect.SENDS_TEXT)
    private val system = setOf(ToolEffect.WRITES_SYSTEM)

    @Test
    fun 只读只放行只读工具() {
        assertTrue(AgentPermission.READ_ONLY.allows(read))
        assertFalse("只读居然允许点屏幕", AgentPermission.READ_ONLY.allows(tap))
        assertFalse("只读居然允许写文件", AgentPermission.READ_ONLY.allows(write))
        assertFalse("只读居然允许发消息", AgentPermission.READ_ONLY.allows(send))
        assertFalse("只读居然允许改系统", AgentPermission.READ_ONLY.allows(system))
    }

    @Test
    fun 工作区可写多放开的是屏幕与文件() {
        assertTrue(AgentPermission.WORKSPACE_WRITE.allows(read))
        assertTrue("点屏幕不算动手", AgentPermission.WORKSPACE_WRITE.allows(tap))
        assertTrue("工作区里写文件被挡住了", AgentPermission.WORKSPACE_WRITE.allows(write))
        // ★ 这两条是"工作区可写"与"完全访问"的**唯一**区别。
        //   少了它们，两档就只是名字不同。
        assertFalse("工作区可写不该允许发消息", AgentPermission.WORKSPACE_WRITE.allows(send))
        assertFalse("工作区可写不该允许改系统设置", AgentPermission.WORKSPACE_WRITE.allows(system))
    }

    @Test
    fun 完全访问什么都放行() {
        for (effects in listOf(read, tap, write, send, system)) {
            assertTrue(AgentPermission.FULL_ACCESS.allows(effects))
        }
        assertTrue(AgentPermission.FULL_ACCESS.allows(ToolEffect.entries.toSet()))
    }

    @Test
    fun 混合效果按最严的那一个判() {
        // 一个工具同时"写文件"和"发消息" —— 它就不该在工作区可写里出现。
        val mixed = setOf(ToolEffect.READ_ONLY, ToolEffect.WRITES_DATA, ToolEffect.SENDS_TEXT)
        assertFalse(AgentPermission.WORKSPACE_WRITE.allows(mixed))
        assertFalse(AgentPermission.READ_ONLY.allows(mixed))
        assertTrue(AgentPermission.FULL_ACCESS.allows(mixed))
    }

    @Test
    fun 档位从松到紧排好序() {
        // ★ 界面上是**从上到下越放越开**。顺序错了，用户按"第二档是中间"
        //   的直觉去选，选到的是最严或最松的那一档。
        val order = AgentPermission.entries
        assertEquals(
            listOf(
                AgentPermission.READ_ONLY,
                AgentPermission.WORKSPACE_WRITE,
                AgentPermission.FULL_ACCESS,
            ),
            order,
        )
    }

    @Test
    fun 名字与电脑端harness逐字对应() {
        assertEquals("只读", AgentPermission.READ_ONLY.label)
        assertEquals("工作区可写", AgentPermission.WORKSPACE_WRITE.label)
        assertEquals("完全访问", AgentPermission.FULL_ACCESS.label)
    }

    @Test
    fun 存档读坏值不抛而是退回默认() {
        assertEquals(AgentPermission.FULL_ACCESS, AgentPermission.of("WORKSPACE_WRITE_NOT_A_NAME"))
        assertEquals(AgentPermission.FULL_ACCESS, AgentPermission.of(null))
        assertEquals(AgentPermission.FULL_ACCESS, AgentPermission.of(""))
        assertEquals(AgentPermission.READ_ONLY, AgentPermission.of("READ_ONLY"))
        assertEquals(AgentPermission.WORKSPACE_WRITE, AgentPermission.of("WORKSPACE_WRITE"))
    }

    @Test
    fun 默认档是原来的行为() {
        // ⚠️ 升级不该让老用户突然干不了活：默认必须是"没有额外限制"那一档。
        assertEquals(AgentPermission.FULL_ACCESS, AgentPermission.DEFAULT)
    }

    @Test
    fun 提示词段落按档位说清能与不能() {
        val r = AgentPermission.promptSection(AgentPermission.READ_ONLY, workspaceName = "文档")
        assertTrue(r.isNotBlank())
        assertTrue("没说不许动", r.contains("不改任何东西"))
        assertTrue("没写工作区名", r.contains("文档"))

        val w = AgentPermission.promptSection(AgentPermission.WORKSPACE_WRITE, workspaceName = "文档")
        assertTrue("工作区可写没划定边界", w.contains("工作区以外"))

        val f = AgentPermission.promptSection(AgentPermission.FULL_ACCESS)
        assertTrue("完全访问没提不可撤销的动作", f.contains("不可撤销"))
        assertTrue("完全访问没说「先说一句」", f.contains("先说一句"))
    }

    @Test
    fun 没有权限就不写这一段() {
        // ★ 聊天页（角色扮演）不该看见"权限"这种系统话 ——
        //   提示词里多一段"你的权限是只读"会让角色开始汇报工作。
        assertEquals("", AgentPermission.promptSection(null))
    }
}
