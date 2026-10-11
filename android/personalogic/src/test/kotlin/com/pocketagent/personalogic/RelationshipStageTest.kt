package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关系阶段：阈值单调、共同推进、只升不降、锁定生效、一次只上一级。
 */
class RelationshipStageTest {

    private val tracker = RelationshipTracker()
    private val NOW = 1_700_000_000_000L

    private fun state(stage: RelationshipStage = RelationshipStage.STRANGER, closeness: Int = 0, locked: Boolean = false) =
        RelationshipState(stage = stage, closeness = closeness, locked = locked)

    // ── 配置校验：阈值单调 ──────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `阈值必须严格递增`() {
        // 反了的话会出现"跨过某点反而更难亲近"，而那种 bug 只在涨到那一段才暴露。
        RelationshipConfig(
            thresholds = mapOf(
                RelationshipStage.STRANGER to 0,
                RelationshipStage.ACQUAINTED to 30,
                RelationshipStage.CLOSE to 20, // ← 反了
                RelationshipStage.BONDED to 85,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `STRANGER 阈值必须为 0`() {
        RelationshipConfig(
            thresholds = mapOf(
                RelationshipStage.STRANGER to 5,
                RelationshipStage.ACQUAINTED to 20,
                RelationshipStage.CLOSE to 55,
                RelationshipStage.BONDED to 85,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `深度事件门槛不能为负`() {
        // ⚠️ 只覆盖「事件门槛」这一项，其余走默认值 —— 否则会先撞上
        //    「缺少某阶段阈值」那条校验，测试变成**因为别的原因通过**。
        RelationshipConfig(
            requiredDeepEvents = mapOf(
                RelationshipStage.STRANGER to 0,
                RelationshipStage.ACQUAINTED to -1,
                RelationshipStage.CLOSE to 2,
                RelationshipStage.BONDED to 3,
            ),
        )
    }

    @Test
    fun `默认配置的阈值确实单调`() {
        val ordered = RelationshipStage.entries.map { RelationshipConfig.DEFAULT_THRESHOLDS.getValue(it) }
        assertEquals(ordered.sorted(), ordered)
        assertEquals(ordered.distinct(), ordered)
    }

    // ── ★ 共同推进：亲密度 + 深度事件，缺一不可 ─────────────────

    @Test
    fun `★ 亲密度够但没有深度事件时不推进`() {
        // 亲密度是可以靠"刷"涨上去的（每天问一百遍早安），
        // 而真正让关系变深的是有分量的时刻。两者都要满足，
        // 关系才不会变成活跃度积分。
        val out = tracker.advance(state(closeness = 95), baseGain = 5, deepEvents = emptySet(), nowMs = NOW)
        assertEquals(RelationshipStage.STRANGER, out.state.stage)
        assertNull(out.transition)
        assertEquals("亲密度照常累积", 100, out.state.closeness)
    }

    @Test
    fun `★ 有深度事件但亲密度不够时不推进`() {
        val out = tracker.advance(
            state(closeness = 10),
            baseGain = 0,
            deepEvents = setOf(DeepEvent.FIRST_TASK_COMPLETED),
            nowMs = NOW,
        )
        assertEquals(RelationshipStage.STRANGER, out.state.stage)
        assertNull(out.transition)
    }

    @Test
    fun `两者都满足时推进一级并给出仪式`() {
        val out = tracker.advance(
            state(closeness = 18),
            baseGain = 5,
            deepEvents = setOf(DeepEvent.FIRST_TASK_COMPLETED),
            nowMs = NOW,
        )
        assertEquals(RelationshipStage.ACQUAINTED, out.state.stage)
        val t = out.transition!!
        assertEquals(RelationshipStage.STRANGER, t.from)
        assertEquals(RelationshipStage.ACQUAINTED, t.to)
        assertEquals(NOW, t.atMs)
        assertTrue("跃迁要有专门表达（可回放）", t.ritual.isNotBlank())
    }

    // ── ★ 一次只上一级 ──────────────────────────────────────────

    @Test
    fun `★ 一次评估只推进一级`() {
        // 亲密度一口气给到顶，也只上一级 —— 跃迁是有仪式感的节点，
        // 连跳三级没有仪式可言；解锁后一次跳到底也会让人莫名其妙。
        val out = tracker.advance(
            state(closeness = 0),
            baseGain = 100,
            deepEvents = DeepEvent.entries.toSet(),
            nowMs = NOW,
        )
        assertEquals(RelationshipStage.ACQUAINTED, out.state.stage)
    }

    @Test
    fun `连续评估可以逐级走到最后一级`() {
        var s = state()
        val events = DeepEvent.entries.toSet()
        repeat(3) {
            val out = tracker.advance(s, baseGain = 100, deepEvents = events, nowMs = NOW)
            s = out.state
        }
        assertEquals(RelationshipStage.BONDED, s.stage)
    }

    @Test
    fun `已是最后一级时不再有跃迁且不会越界`() {
        val out = tracker.advance(
            state(stage = RelationshipStage.BONDED, closeness = 100),
            baseGain = 10,
            deepEvents = DeepEvent.entries.toSet(),
            nowMs = NOW,
        )
        assertEquals(RelationshipStage.BONDED, out.state.stage)
        assertNull(out.transition)
        assertEquals("亲密度封顶在 100", 100, out.state.closeness)
    }

    // ── ★ 只升不降 ──────────────────────────────────────────────

    @Test
    fun `★ 阶段不会因自动推进而降级`() {
        // 一个会自己"降温"的助理是种惩罚，而用户没有做错任何事。
        val out = tracker.advance(
            state(stage = RelationshipStage.CLOSE, closeness = 100),
            baseGain = 0,
            deepEvents = emptySet(),
            nowMs = NOW,
        )
        assertTrue("只能保持或上升", out.state.stage.isAtLeast(RelationshipStage.CLOSE))
    }

    @Test
    fun `重置是唯一的降级路径且由用户发起`() {
        val back = tracker.reset(RelationshipStage.STRANGER)
        assertEquals(RelationshipStage.STRANGER, back.stage)
        assertEquals(0, back.closeness)
        assertFalse("重置后不应仍是锁定态", back.locked)
    }

    // ── ★ 锁定（D-Life-4）──────────────────────────────────────

    @Test
    fun `★ 锁定后不再推进`() {
        val out = tracker.advance(
            state(closeness = 100, locked = true),
            baseGain = 10,
            deepEvents = DeepEvent.entries.toSet(),
            nowMs = NOW,
        )
        assertEquals(RelationshipStage.STRANGER, out.state.stage)
        assertNull("锁着的时候不该有跃迁", out.transition)
    }

    @Test
    fun `锁定期间亲密度照常累积`() {
        // 用户锁的是"关系别自己往前走"，不是"别再对我好"。
        val out = tracker.advance(
            state(closeness = 30, locked = true),
            baseGain = 10,
            deepEvents = emptySet(),
            nowMs = NOW,
        )
        assertEquals(40, out.state.closeness)
    }

    @Test
    fun `解锁后按当时亲密度如实推进，进度不会丢`() {
        var s = state(closeness = 100, locked = true)
        s = tracker.advance(s, baseGain = 0, deepEvents = DeepEvent.entries.toSet(), nowMs = NOW).state
        assertEquals("锁着时确实没动", RelationshipStage.STRANGER, s.stage)

        val unlocked = tracker.setLocked(s, false)
        val out = tracker.advance(unlocked, baseGain = 0, deepEvents = DeepEvent.entries.toSet(), nowMs = NOW)
        assertEquals("解锁后应立刻补上这一级", RelationshipStage.ACQUAINTED, out.state.stage)
    }

    @Test
    fun `setLocked 只改锁定标记`() {
        val s = state(stage = RelationshipStage.CLOSE, closeness = 70)
        val locked = tracker.setLocked(s, true)
        assertEquals(s.stage, locked.stage)
        assertEquals(s.closeness, locked.closeness)
        assertTrue(locked.locked)
    }

    // ── 调速（D-Life-4）────────────────────────────────────────

    @Test
    fun `速度倍率作用在获得的亲密度上`() {
        val slow = tracker.advance(state(), baseGain = 10, deepEvents = emptySet(), nowMs = NOW, speed = StageSpeed.SLOW)
        val normal = tracker.advance(state(), baseGain = 10, deepEvents = emptySet(), nowMs = NOW, speed = StageSpeed.NORMAL)
        val fast = tracker.advance(state(), baseGain = 10, deepEvents = emptySet(), nowMs = NOW, speed = StageSpeed.FAST)
        assertEquals(5, slow.state.closeness)
        assertEquals(10, normal.state.closeness)
        assertEquals(20, fast.state.closeness)
    }

    @Test
    fun `慢速下至少也能拿到 1 点`() {
        // 有互动就该有进展，只是多少 —— 否则慢速档会让"陪了很久"完全不被记录。
        assertEquals(1, StageSpeed.SLOW.apply(1))
        assertEquals(0, StageSpeed.SLOW.apply(0))
    }

    @Test
    fun `调速不影响已到达的阶段`() {
        val s = state(stage = RelationshipStage.CLOSE, closeness = 70)
        val out = tracker.advance(s, baseGain = 0, deepEvents = emptySet(), nowMs = NOW, speed = StageSpeed.SLOW)
        assertEquals(RelationshipStage.CLOSE, out.state.stage)
    }

    // ── 状态与序列化约定 ────────────────────────────────────────

    @Test
    fun `亲密度越界构造不出来`() {
        try {
            RelationshipState(closeness = 101)
            throw AssertionError("应当抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0..100"))
        }
    }

    @Test
    fun `阶段比较是可比的且最后一级没有下一级`() {
        assertTrue(RelationshipStage.BONDED.isAtLeast(RelationshipStage.STRANGER))
        assertFalse(RelationshipStage.STRANGER.isAtLeast(RelationshipStage.CLOSE))
        assertNull(RelationshipStage.BONDED.next)
        assertNotNull(RelationshipStage.STRANGER.next)
    }

    @Test
    fun `每个阶段都有可展示的名字`() {
        for (stage in RelationshipStage.entries) {
            assertTrue("${stage.name} 缺展示名", stage.label.isNotBlank())
        }
        for (e in DeepEvent.entries) {
            assertTrue("${e.name} 缺展示名", e.label.isNotBlank())
        }
    }
}
