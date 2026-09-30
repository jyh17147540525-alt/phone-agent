package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.2.4 验收第②条与第③条：
 * - 单条隐式反馈 → 停在 `CANDIDATE`，**不生效**
 * - 连续 N 条一致隐式反馈 → 升 `ACTIVE`，且**产生一条可引用的 `PersonaDelta`**
 *
 * 另外覆盖变更账本自身的纪律：候选不可被引用、冲突值作废旧链、
 * 冷启动恢复后 id 不撞。
 */
class PersonaDeltaLedgerTest {

    private fun tuner() = DefaultPersonaTuner(PersonaPresets.MALE)

    private fun warmthField() = PersonaFieldRefs.resolve("tone_warmth")

    // ── ② 单条隐式反馈不生效 ───────────────────────────────────

    @Test
    fun `单条隐式反馈停留在候选区且人格不变`() {
        val tuner = tuner()
        val result = tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)

        val candidate = result as TuneResult.CandidateRecorded
        assertEquals("还差两条", 1, candidate.have)
        assertEquals(3, candidate.need)
        assertEquals("单条 0.45 的置信度", 0.45, candidate.confidence, 1e-9)

        // ★ 最要紧的一条：候选**不参与回放**，人格必须一点没变
        assertEquals(55, tuner.currentSpec().tone.warmth)
    }

    @Test
    fun `两条一致的隐式反馈仍然不生效`() {
        val tuner = tuner()
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)
        val second = tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 2_000L)

        val candidate = second as TuneResult.CandidateRecorded
        assertEquals(2, candidate.have)
        assertEquals("两条 0.45 合并为 0.6975，仍差阈值一点点", 0.6975, candidate.confidence, 1e-9)
        assertEquals(55, tuner.currentSpec().tone.warmth)
    }

    // ── ③ 攒够证据后升级，并产出可引用的 delta ──────────────────

    @Test
    fun `三条一致的隐式反馈升级为已生效`() {
        val tuner = tuner()
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 2_000L)
        val third = tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 3_000L)

        val applied = third as TuneResult.Applied
        assertEquals(70, applied.spec.tone.warmth)
        assertTrue(applied.delta.isActive())
    }

    @Test
    fun `升级后产生的那条 delta 带得出人的证据`() {
        // ★ 需求②的成立条件：这条证据必须**可以被助理说出口**。
        //   如果账本只存"改成了 70"，用户问"你为什么变了"就答不出来。
        val tuner = tuner()
        repeat(2) { tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("SELF_CORRECTION"), 1_000L + it) }
        val applied = tuner.tune(
            warmthField(),
            "70",
            DeltaSource.IMPLICIT,
            listOf("SELF_CORRECTION"),
            3_000L,
        ) as TuneResult.Applied

        assertEquals(listOf("SELF_CORRECTION"), applied.delta.evidence)
        assertEquals(DeltaSource.IMPLICIT, applied.delta.source)
        assertEquals("55", applied.delta.oldValue)
        assertEquals("70", applied.delta.newValue)
        assertEquals(EvidenceSignal.SELF_CORRECTION.baseConfidence, applied.delta.confidence, 1e-9)
    }

    @Test
    fun `账本里存的是本条自身的置信度而不是合并值`() {
        // 存合并值就无法增量重算：第 4 条证据到来时，
        // 合并值会被当成"第 3 条的独立贡献"再合并一次，越算越高。
        val tuner = tuner()
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)

        assertEquals(0.45, tuner.ledgerSnapshot().single().confidence, 1e-9)
    }

    // ── 可引用性：候选绝不可被说出口 ───────────────────────────

    @Test
    fun `最近变更不包含候选区的条目`() {
        val tuner = tuner()
        tuner.tune(
            PersonaFieldRefs.resolve("address_style"),
            "老张",
            DeltaSource.EXPLICIT,
            listOf("以后叫我老张"),
            1_000L,
        )
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 2_000L)

        val recent = tuner.recentChanges(10)
        assertEquals("候选不可被引用", 1, recent.size)
        assertEquals(PersonaField.ADDRESS_STYLE, recent.single().field)
    }

    @Test
    fun `最近变更按时间倒序`() {
        val tuner = tuner()
        tuner.tune(PersonaFieldRefs.resolve("address_style"), "老张", DeltaSource.EXPLICIT, listOf("叫我老张"), 1_000L)
        tuner.tune(PersonaFieldRefs.resolve("catchphrase"), "好嘞", DeltaSource.EXPLICIT, listOf("加个口头禅"), 2_000L)

        val recent = tuner.recentChanges(10)
        assertEquals(PersonaField.CATCHPHRASE, recent[0].field)
        assertEquals(PersonaField.ADDRESS_STYLE, recent[1].field)
    }

    @Test
    fun `最近变更的条数上限生效`() {
        val tuner = tuner()
        repeat(3) {
            tuner.tune(PersonaFieldRefs.resolve("tone_humor"), "${40 + it}", DeltaSource.EXPLICIT, listOf("再幽默点"), 1_000L + it)
        }
        assertEquals(2, tuner.recentChanges(2).size)
    }

    @Test
    fun `已回滚的条目不再出现在最近变更里`() {
        val tuner = tuner()
        val applied = tuner.tune(
            PersonaFieldRefs.resolve("address_style"),
            "老张",
            DeltaSource.EXPLICIT,
            listOf("叫我老张"),
            1_000L,
        ) as TuneResult.Applied

        tuner.rollback(applied.delta.id, 2_000L)
        assertTrue("已回滚的变更已不是既成事实", tuner.recentChanges(10).isEmpty())
    }

    // ── 冲突值作废旧候选链 ─────────────────────────────────────

    @Test
    fun `同字段出现不同的候选值时旧候选链被作废`() {
        val tuner = tuner()
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 2_000L)

        val third = tuner.tune(warmthField(), "80", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 3_000L)
            as TuneResult.CandidateRecorded

        // ★ 若不作废，用户"改主意"的两次会被算成三次一致证据 ——
        //   也就是"被两句互相矛盾的话改坏"。
        assertEquals("旧链作废后从 1 重新数", 1, third.have)
        assertTrue(
            "账本里不应再有指向 70 的候选",
            tuner.ledgerSnapshot().none { it.isCandidate() && it.newValue == "70" },
        )
        assertTrue(
            tuner.audit.snapshot().any { it.outcome == PersonaAuditOutcome.CANDIDATE_CLEARED },
        )
    }

    @Test
    fun `不同字段的候选互不影响`() {
        val tuner = tuner()
        tuner.tune(warmthField(), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)
        tuner.tune(PersonaFieldRefs.resolve("tone_brevity"), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 2_000L)

        assertEquals("两条候选属于不同字段", 2, tuner.ledgerSnapshot().count { it.isCandidate() })
    }

    // ── 值未变 ─────────────────────────────────────────────────

    @Test
    fun `设成当前已有的值时不产生任何变更`() {
        val tuner = tuner()
        val result = tuner.tune(
            PersonaFieldRefs.resolve("address_style"),
            "你",
            DeltaSource.EXPLICIT,
            listOf("还是叫我你就行"),
            1_000L,
        )

        assertEquals(TuneResult.AlreadyAtValue(PersonaField.ADDRESS_STYLE, "你"), result)
        assertTrue("不应落任何 delta", tuner.ledgerSnapshot().isEmpty())
        // 但**必须留痕**：用户以为说了就有用，实际什么都没变
        assertTrue(tuner.audit.snapshot().any { it.outcome == PersonaAuditOutcome.NO_CHANGE })
    }

    // ── 冷启动恢复 ─────────────────────────────────────────────

    @Test
    fun `恢复账本后人格由账本回放得出`() {
        val tuner = tuner()
        tuner.restore(
            listOf(
                PersonaDelta(
                    id = PersonaDelta.idOf(7),
                    field = PersonaField.ADDRESS_STYLE,
                    oldValue = "你",
                    newValue = "老张",
                    source = DeltaSource.EXPLICIT,
                    evidence = listOf("以后叫我老张"),
                    confidence = 1.0,
                    state = DeltaState.ACTIVE,
                    createdAt = 1_000L,
                ),
            ),
        )
        assertEquals("老张", tuner.currentSpec().addressStyle)
    }

    @Test
    fun `恢复账本后新变更的 id 不与历史相撞`() {
        // ★ 撞 id 的后果是 rollback 回滚到**错误的那一条**，
        //   而表现出来只是"撤销了但没变化"—— 极难排查
        val tuner = tuner()
        tuner.restore(
            listOf(
                PersonaDelta(
                    id = PersonaDelta.idOf(7),
                    field = PersonaField.ADDRESS_STYLE,
                    oldValue = "你",
                    newValue = "老张",
                    source = DeltaSource.EXPLICIT,
                    evidence = listOf("以后叫我老张"),
                    confidence = 1.0,
                    state = DeltaState.ACTIVE,
                    createdAt = 1_000L,
                ),
            ),
        )

        val applied = tuner.tune(
            PersonaFieldRefs.resolve("catchphrase"),
            "好嘞",
            DeltaSource.EXPLICIT,
            listOf("加个口头禅"),
            2_000L,
        ) as TuneResult.Applied

        assertEquals(PersonaDelta.idOf(8), applied.delta.id)
    }

    @Test
    fun `恢复重复 id 的账本抛异常`() {
        val tuner = tuner()
        val one = PersonaDelta(
            id = PersonaDelta.idOf(1),
            field = PersonaField.ADDRESS_STYLE,
            oldValue = "你",
            newValue = "老张",
            source = DeltaSource.EXPLICIT,
            evidence = listOf("叫我老张"),
            confidence = 1.0,
            state = DeltaState.ACTIVE,
            createdAt = 1_000L,
        )

        val ex = try {
            tuner.restore(listOf(one, one))
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertFalse("恢复重复 id 的账本必须报错，而不是静默去重", ex == null)
    }
}