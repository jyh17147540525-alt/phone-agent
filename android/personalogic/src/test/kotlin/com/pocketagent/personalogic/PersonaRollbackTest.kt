package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.2.4 验收第④条：**回滚一条 `ACTIVE` delta → 人格精确回到前值**。
 *
 * 「精确」两个字是这条验收的全部重量。回滚不是"差不多退回去"：
 * 「ta 为我做出了改变」的另一面是「我说了算」，而"说了算"如果
 * 只是近似实现，用户是能感觉出来的 —— 他会发现自己撤销之后
 * 助理的语气跟以前还是不太一样。
 */
class PersonaRollbackTest {

    private fun tuner() = DefaultPersonaTuner(PersonaPresets.MALE)

    private fun addressField() = PersonaFieldRefs.resolve("address_style")

    private fun applyAddress(tuner: PersonaTuner, value: String, at: Long): PersonaDelta =
        (tuner.tune(addressField(), value, DeltaSource.EXPLICIT, listOf("以后叫我$value"), at)
            as TuneResult.Applied).delta

    // ── 基本回滚 ───────────────────────────────────────────────

    @Test
    fun `回滚一条已生效的变更后人格精确回到前值`() {
        val tuner = tuner()
        val delta = applyAddress(tuner, "老张", 1_000L)
        assertEquals("老张", tuner.currentSpec().addressStyle)

        val rolled = tuner.rollback(delta.id, 2_000L) as TuneResult.RolledBack

        assertEquals("必须精确回到预设里的称呼", "你", rolled.spec.addressStyle)
        assertEquals("你", rolled.delta.oldValue)
        assertEquals(DeltaState.ROLLED_BACK, rolled.delta.state)
    }

    @Test
    fun `回滚是原地改状态而不是追加反向变更`() {
        // ★ 追加一条 `老张 → 你` 的反向 delta 也能让"当前值"看起来对，
        //   但账本里会留下两条记录，用户问"你为什么变了"会得到
        //   两件互相抵消的答案。历史应当是"这一条被撤销了"。
        val tuner = tuner()
        val delta = applyAddress(tuner, "老张", 1_000L)
        tuner.rollback(delta.id, 2_000L)

        val ledger = tuner.ledgerSnapshot()
        assertEquals("账本长度不应增加", 1, ledger.size)
        assertEquals(DeltaState.ROLLED_BACK, ledger.single().state)
        assertEquals(delta.id, ledger.single().id)
    }

    @Test
    fun `回滚留下审计`() {
        val tuner = tuner()
        val delta = applyAddress(tuner, "老张", 1_000L)
        tuner.rollback(delta.id, 2_000L)

        assertTrue(tuner.audit.snapshot().any { it.outcome == PersonaAuditOutcome.ROLLED_BACK })
    }

    // ── 幂等 ───────────────────────────────────────────────────

    @Test
    fun `重复回滚返回已回滚而不是假装成功`() {
        val tuner = tuner()
        val delta = applyAddress(tuner, "老张", 1_000L)
        tuner.rollback(delta.id, 2_000L)

        // 连点两次"撤销"不该报错，但也不能显示一个并不存在的状态变化
        assertEquals(TuneResult.AlreadyRolledBack(delta.id), tuner.rollback(delta.id, 3_000L))
    }

    @Test
    fun `回滚不存在的 id 抛异常`() {
        val tuner = tuner()
        val ex = try {
            tuner.rollback("pd-999", 1_000L)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("找不到的变更必须报错，不能静默返回成功", ex != null)
    }

    // ── 同字段多条变更的回滚 ───────────────────────────────────

    @Test
    fun `回滚同字段较新的一条会退回到较旧的一条`() {
        val tuner = tuner()
        val first = applyAddress(tuner, "老张", 1_000L)
        val second = applyAddress(tuner, "张总", 2_000L)
        assertEquals("张总", tuner.currentSpec().addressStyle)

        val rolled = tuner.rollback(second.id, 3_000L) as TuneResult.RolledBack

        assertEquals("老张", rolled.spec.addressStyle)
        assertEquals("老张", second.oldValue)
    }

    @Test
    fun `逐条回滚后回到预设`() {
        val tuner = tuner()
        val first = applyAddress(tuner, "老张", 1_000L)
        val second = applyAddress(tuner, "张总", 2_000L)

        tuner.rollback(second.id, 3_000L)
        val rolled = tuner.rollback(first.id, 4_000L) as TuneResult.RolledBack

        assertEquals("你", rolled.spec.addressStyle)
    }

    @Test
    fun `回滚中间一条时较新的一条仍然生效`() {
        // 回放是"从预设出发按时间顺序应用全部 ACTIVE"，
        // 因此撤掉中间一条之后，最后那条依然把它自己的值写上去 ——
        // 这正是回放式实现天然正确、而"存一份当前值"很难做对的地方。
        val tuner = tuner()
        val first = applyAddress(tuner, "老张", 1_000L)
        applyAddress(tuner, "张总", 2_000L)

        val rolled = tuner.rollback(first.id, 3_000L) as TuneResult.RolledBack
        assertEquals("张总", rolled.spec.addressStyle)
    }

    // ── 回滚轴类字段 ───────────────────────────────────────────

    @Test
    fun `回滚轴类字段后数值精确复原`() {
        val tuner = tuner()
        val delta = (tuner.tune(
            PersonaFieldRefs.resolve("tone_brevity"),
            "90",
            DeltaSource.EXPLICIT,
            listOf("说短点"),
            1_000L,
        ) as TuneResult.Applied).delta
        assertEquals(90, tuner.currentSpec().tone.brevity)

        val rolled = tuner.rollback(delta.id, 2_000L) as TuneResult.RolledBack
        assertEquals("必须精确回到预设的 55", 55, rolled.spec.tone.brevity)
    }

    // ── 回滚候选 ───────────────────────────────────────────────

    @Test
    fun `回滚一条候选只改状态不影响人格`() {
        val tuner = tuner()
        tuner.tune(
            PersonaFieldRefs.resolve("tone_warmth"),
            "70",
            DeltaSource.IMPLICIT,
            listOf("INTERRUPT"),
            1_000L,
        )
        val candidateId = tuner.ledgerSnapshot().single().id

        val rolled = tuner.rollback(candidateId, 2_000L) as TuneResult.RolledBack

        assertEquals("候选本来就没生效，人格当然不变", 55, rolled.spec.tone.warmth)
        assertTrue("应留在账本里而不是被删掉", tuner.ledgerSnapshot().single().state == DeltaState.ROLLED_BACK)
    }

    // ── 回滚后可以重新改 ───────────────────────────────────────

    @Test
    fun `回滚后重新设置同一字段会用新的 id`() {
        val tuner = tuner()
        val first = applyAddress(tuner, "老张", 1_000L)
        tuner.rollback(first.id, 2_000L)

        val again = applyAddress(tuner, "老张", 3_000L)

        assertEquals(PersonaDelta.idOf(2), again.id)
        assertEquals("你", again.oldValue)
        assertEquals("老张", tuner.currentSpec().addressStyle)
    }
}