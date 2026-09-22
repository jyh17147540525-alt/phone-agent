package com.pocketagent.overlaylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateMachineTest {

    // ── 圆点状态的迁移 ──────────────────────────────────────────

    @Test
    fun `圆点单击展开面板`() {
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.Tap)
        assertEquals(OverlayState.PANEL, t.movedTo)
        assertEquals(OverlayEffect.ShowPanel, t.effectOrNull)
    }

    @Test
    fun `圆点拖到边缘变为贴边`() {
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.DragToEdge)
        assertEquals(OverlayState.EDGE, t.movedTo)
        assertEquals(OverlayEffect.SnapToEdge, t.effectOrNull)
    }

    @Test
    fun `圆点收到任务开始进入运行态并显示进度`() {
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.TaskStarted("t1"))
        assertEquals(OverlayState.RUNNING, t.movedTo)
        assertEquals(OverlayEffect.ShowProgress, t.effectOrNull)
        assertEquals("t1", (OverlayEvent.TaskStarted("t1")).taskId)
    }

    @Test
    fun `圆点长按打开临时模型指定`() {
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.LongPress)
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.ShowModelPicker, t.effectOrNull)
    }

    @Test
    fun `圆点状态下收到任务完成事件被忽略`() {
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.TaskFinished("t1"))
        assertNull(t.movedTo)
        assertEquals(OverlayState.DOT, t.resultingState)
    }

    // ── 贴边状态的迁移 ──────────────────────────────────────────

    @Test
    fun `贴边单击展开面板`() {
        val t = OverlayStateMachine.reduce(OverlayState.EDGE, OverlayEvent.Tap)
        assertEquals(OverlayState.PANEL, t.movedTo)
        assertEquals(OverlayEffect.ShowPanel, t.effectOrNull)
    }

    @Test
    fun `贴边拖回屏幕内恢复圆点`() {
        val t = OverlayStateMachine.reduce(OverlayState.EDGE, OverlayEvent.DragFromEdge)
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.UnsnapFromEdge, t.effectOrNull)
    }

    @Test
    fun `贴边状态下任务开始仍保持贴边`() {
        // 用户明确表达了"别占我屏幕"，不擅自弹回圆点
        val t = OverlayStateMachine.reduce(OverlayState.EDGE, OverlayEvent.TaskStarted("t1"))
        assertEquals(OverlayState.EDGE, t.movedTo)
        assertEquals(OverlayEffect.ShowProgress, t.effectOrNull)
    }

    @Test
    fun `贴边状态下重复拖到边缘被忽略`() {
        val t = OverlayStateMachine.reduce(OverlayState.EDGE, OverlayEvent.DragToEdge)
        assertNull(t.movedTo)
        assertEquals(OverlayState.EDGE, t.resultingState)
    }

    // ── 运行态的迁移 ────────────────────────────────────────────

    @Test
    fun `运行中单击可展开面板查看详情`() {
        val t = OverlayStateMachine.reduce(OverlayState.RUNNING, OverlayEvent.Tap, runningTaskId = "t1")
        assertEquals(OverlayState.PANEL, t.movedTo)
    }

    @Test
    fun `运行中拖到边缘变为贴边`() {
        val t = OverlayStateMachine.reduce(OverlayState.RUNNING, OverlayEvent.DragToEdge, "t1")
        assertEquals(OverlayState.EDGE, t.movedTo)
    }

    @Test
    fun `运行中收到匹配任务的完成事件回到圆点并隐藏进度`() {
        val t = OverlayStateMachine.reduce(OverlayState.RUNNING, OverlayEvent.TaskFinished("t1"), "t1")
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.HideProgress, t.effectOrNull)
    }

    @Test
    fun `运行中收到其他任务的完成事件被忽略`() {
        // 防止过期事件把当前任务的状态搞乱
        val t = OverlayStateMachine.reduce(OverlayState.RUNNING, OverlayEvent.TaskFinished("t2"), "t1")
        assertNull(t.movedTo)
        assertEquals(OverlayState.RUNNING, t.resultingState)
        assertTrue((t as OverlayTransition.Ignored).reason.contains("其他任务"))
    }

    @Test
    fun `运行中拒绝并发任务`() {
        // 本项目刻意不支持并发执行 —— 会同时抢无障碍服务和屏幕，影响叠加
        val t = OverlayStateMachine.reduce(OverlayState.RUNNING, OverlayEvent.TaskStarted("t2"), "t1")
        assertNull(t.movedTo)
        assertEquals(OverlayState.RUNNING, t.resultingState)
        assertTrue((t as OverlayTransition.Ignored).reason.contains("并发"))
    }

    // ── 面板状态的迁移 ──────────────────────────────────────────

    @Test
    fun `面板收起回到圆点`() {
        val t = OverlayStateMachine.reduce(OverlayState.PANEL, OverlayEvent.DismissPanel)
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.HidePanel, t.effectOrNull)
    }

    @Test
    fun `面板再点一次也是收起`() {
        val t = OverlayStateMachine.reduce(OverlayState.PANEL, OverlayEvent.Tap)
        assertEquals(OverlayState.DOT, t.movedTo)
    }

    @Test
    fun `面板状态下任务完成也收起`() {
        val t = OverlayStateMachine.reduce(OverlayState.PANEL, OverlayEvent.TaskFinished("t1"), "t1")
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.HidePanel, t.effectOrNull)
    }

    @Test
    fun `面板展开时拒绝新任务`() {
        val t = OverlayStateMachine.reduce(OverlayState.PANEL, OverlayEvent.TaskStarted("t2"), "t1")
        assertNull(t.movedTo)
        assertEquals(OverlayState.PANEL, t.resultingState)
    }

    // ── 急停：最高优先级 ────────────────────────────────────────

    @Test
    fun `急停在任何状态下都回到圆点并广播停止信号`() {
        OverlayState.values().forEach { state ->
            val t = OverlayStateMachine.reduce(state, OverlayEvent.EmergencyStop, "t1")
            assertEquals("状态 $state 下急停应回到圆点", OverlayState.DOT, t.movedTo)
            assertEquals("状态 $state 下急停应广播停止", OverlayEffect.BroadcastStop, t.effectOrNull)
        }
    }

    @Test
    fun `即使没有任务在跑急停也要给反馈`() {
        // 急停开关不能"看起来没反应" —— 否则用户会以为坏了，反复点击
        val t = OverlayStateMachine.reduce(OverlayState.DOT, OverlayEvent.EmergencyStop, null)
        assertEquals(OverlayState.DOT, t.movedTo)
        assertEquals(OverlayEffect.BroadcastStop, t.effectOrNull)
    }

    // ── 状态属性 ────────────────────────────────────────────────

    @Test
    fun `所有状态都算可见以保住前台服务启动豁免`() {
        // Android 15 收窄豁免后，"存在可见 overlay 窗口"是后台启动 FGS 的条件
        OverlayState.values().forEach { state ->
            assertTrue("状态 $state 必须算作可见", state.countsAsVisibleOverlay)
        }
    }

    @Test
    fun `只有展开面板会占用显著屏幕区域`() {
        assertEquals(
            setOf(OverlayState.PANEL),
            OverlayState.values().filter { it.occupiesSignificantArea }.toSet(),
        )
    }
}
