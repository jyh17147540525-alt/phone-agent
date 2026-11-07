package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏会话表。
 *
 * 这里钉三件事：同一 App 只归一个会话、块数到上限就是"借不到"、
 * **关屏必须先 force-stop 再 release**（顺序反了系统会把 App 挪回主屏）。
 */
class DisplaySessionsTest {

    private val sessions = DisplaySessions(maxSessions = 2)

    @Test
    fun `按顺序分配副屏 id`() {
        assertEquals(DisplaySessions.Claim.Granted(1), sessions.claim("com.a"))
        assertEquals(DisplaySessions.Claim.Granted(2), sessions.claim("com.b"))
    }

    @Test
    fun `到上限时是借不到而不是失败`() {
        sessions.claim("com.a")
        sessions.claim("com.b")

        val third = sessions.claim("com.c")
        assertTrue("第三种情况必须区分开：$third", third is DisplaySessions.Claim.Busy)
        assertEquals(listOf(1, 2), (third as DisplaySessions.Claim.Busy).occupied)
    }

    @Test
    fun `同一个 App 不会被分到第二块屏`() {
        sessions.claim("com.a")
        val again = sessions.claim("com.a")

        assertTrue(again is DisplaySessions.Claim.AlreadyOwned)
        assertEquals(1, (again as DisplaySessions.Claim.AlreadyOwned).displayId)
        assertEquals(1, sessions.occupied().size)
    }

    @Test
    fun `先占屏后决定 App 也可以`() {
        val granted = sessions.claim(null) as DisplaySessions.Claim.Granted
        assertNull(sessions.sessionOf(granted.displayId)!!.packageName)

        sessions.launch(granted.displayId, "com.a", "com.a/.Main")
        assertEquals("com.a", sessions.sessionOf(granted.displayId)!!.packageName)
        assertTrue(sessions.sessionOf(granted.displayId)!!.launched)
    }

    @Test
    fun `拉起 App 生成全屏窗口模式的 am start`() {
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        assertEquals(
            listOf("am", "start", "--display", "$id", "--windowingMode", "1", "-n", "com.a/.Main"),
            sessions.launch(id, "com.a", "com.a/.Main"),
        )
    }

    @Test
    fun `没申请的屏不能操作`() {
        assertThrows(IllegalArgumentException::class.java) {
            sessions.tap(9, 10, 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sessions.launch(9, "com.a", "com.a/.Main")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sessions.capture(9, "/data/local/tmp/a.png")
        }
    }

    @Test
    fun `组件名非法时抛参数异常且不改状态`() {
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        assertThrows(UnsafeRootArgument::class.java) {
            sessions.launch(id, "com.a", "com.a")
        }
        assertTrue("非法组件名不该被记成已拉起", !sessions.sessionOf(id)!!.launched)
    }

    @Test
    fun `点按与抓帧都带 -d`() {
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        assertEquals(listOf("input", "-d", "$id", "tap", "3", "4"), sessions.tap(id, 3, 4))
        assertEquals(
            listOf("screencap", "-d", "$id", "-p", "/data/local/tmp/s.png"),
            sessions.capture(id, "/data/local/tmp/s.png"),
        )
    }

    @Test
    fun `关屏先停 App 再收屏`() {
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        sessions.launch(id, "com.a", "com.a/.Main")

        val plan = sessions.close(id)
        assertEquals(listOf("am", "force-stop", "com.a"), plan.forceStop)
        assertEquals(id, plan.displayId)
        // steps 的顺序就是执行顺序：force-stop 必须在收屏之前
        assertTrue(plan.steps.first().contains("force-stop"))
        assertTrue(plan.steps.last().contains("收屏"))
    }

    @Test
    fun `没拉起过 App 的会话只收屏`() {
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        val plan = sessions.close(id)

        assertNull(plan.forceStop)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.steps.single().contains("收屏"))
    }

    @Test
    fun `关掉之后那块屏能再借出去`() {
        val first = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        sessions.claim("com.b")
        sessions.close(first)

        val again = sessions.claim("com.c")
        assertEquals(DisplaySessions.Claim.Granted(first), again)
    }

    @Test
    fun `关没申请的屏是调用方写错了`() {
        assertThrows(IllegalArgumentException::class.java) { sessions.close(7) }
    }

    @Test
    fun `一次关掉全部时每块都有计划`() {
        sessions.claim("com.a")
        sessions.claim("com.b")
        val plans = sessions.closeAll()

        assertEquals(2, plans.size)
        assertTrue(sessions.occupied().isEmpty())
    }

    @Test
    fun `并行块数必须在 1 到 3 之间`() {
        assertThrows(IllegalArgumentException::class.java) { DisplaySessions(maxSessions = 0) }
        assertThrows(IllegalArgumentException::class.java) { DisplaySessions(maxSessions = 4) }
    }

    @Test
    fun `副屏 id 永远不从 0 开始`() {
        // 0 是用户主屏；把 App 拉过去等于把用户的手机抢走
        assertTrue(DisplaySessions().claim("com.a") is DisplaySessions.Claim.Granted)
        assertEquals(1, DisplaySessions().let { (it.claim("com.a") as DisplaySessions.Claim.Granted).displayId })
    }
}
