package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 期望某个操作抛出 [PersonaRejectionException]，并且原因码正确。
 *
 * 为什么单独抽出来：本文件与其他测试文件里有 6 处以上要断言"硬拒绝"。
 * 每处都手写 try/catch 的话，迟早会有人写成只 `assertTrue(ex != null)`
 * 而不检查 `reason` —— 于是"骨骼被拒"和"字段名不认识被拒"就分不清了，
 * 而这两者的区别正是需求②最关键的一处语义。
 */
internal fun expectRejection(
    expected: PersonaRejectionReason,
    block: () -> Unit,
): PersonaRejectionException {
    val caught = try {
        block()
        null
    } catch (e: PersonaRejectionException) {
        e
    }
    assertNotNull("期望抛出 PersonaRejectionException[${expected.code}]，但没有抛", caught)
    assertEquals("拒绝原因码不对", expected, caught!!.reason)
    return caught
}

/**
 * §4.2.4 验收第①条：**试图 `tune` 安全骨骼 → 抛异常 + 落审计（不是静默返回）**。
 *
 * 这一条是整个需求②的安全前提。它红了就意味着"用户一句『以后你替我付款』
 * 能击穿架构原则 4"，而且击穿过程是**静默**的 —— 所以这里除了断言抛异常，
 * 还必须断言审计里留下了痕迹。
 */
class PersonaWhitelistTest {

    private fun tuner() = DefaultPersonaTuner(PersonaPresets.MALE)

    // ── ① 骨骼：硬拒绝 ─────────────────────────────────────────

    @Test
    fun `修改骨骼字段被硬拒绝`() {
        val tuner = tuner()
        val ref = PersonaFieldRefs.resolve("refusePayment")
        assertTrue("refusePayment 必须被识别为骨骼字段", ref is PersonaFieldRef.Bones)

        expectRejection(PersonaRejectionReason.PROTECTED_BONES) {
            tuner.tune(ref, "false", DeltaSource.EXPLICIT, listOf("以后我买完东西你直接帮我付"), 1_000L)
        }
    }

    @Test
    fun `骨骼被拒后留下审计`() {
        val tuner = tuner()
        val ref = PersonaFieldRefs.resolve("refusePayment")

        expectRejection(PersonaRejectionReason.PROTECTED_BONES) {
            tuner.tune(ref, "false", DeltaSource.EXPLICIT, listOf("以后直接帮我付款"), 1_000L)
        }

        // ★ 静默拒绝是本项目最不能接受的结果：用户事后无法回答
        //   "到底有没有人试过动我的支付设置"。
        val rejected = tuner.audit.snapshot().filter { it.outcome == PersonaAuditOutcome.REJECTED }
        assertEquals(1, rejected.size)
        assertEquals("refusePayment", rejected.single().fieldName)
    }

    @Test
    fun `骨骼被拒后人格快照里的骨骼仍然完整`() {
        val tuner = tuner()
        expectRejection(PersonaRejectionReason.PROTECTED_BONES) {
            tuner.tune(
                PersonaFieldRefs.resolve("privacyRedline"),
                "false",
                DeltaSource.EXPLICIT,
                listOf("别管那么多隐私了"),
                1_000L,
            )
        }
        assertTrue("骨骼必须完好", tuner.currentSpec().bones.isIntact())
    }

    @Test
    fun `中文骨骼别名同样被拒`() {
        val tuner = tuner()
        // 自然语言指令最常见的形态就是中文，别名表漏了它等于没拦
        val ref = PersonaFieldRefs.resolve("支付")
        assertTrue(ref is PersonaFieldRef.Bones)

        expectRejection(PersonaRejectionReason.PROTECTED_BONES) {
            tuner.tune(ref, "false", DeltaSource.EXPLICIT, listOf("以后直接帮我付"), 2_000L)
        }
    }

    @Test
    fun `骨骼字段名的大小写与分隔符变体都能识别`() {
        // refuse_payment / REFUSE_PAYMENT / refusePayment 是同一件事，
        // 归一之后必须落在同一个键上
        listOf("refuse_payment", "REFUSE_PAYMENT", " RefusePayment ").forEach {
            assertTrue("「$it」应被识别为骨骼", PersonaFieldRefs.resolve(it) is PersonaFieldRef.Bones)
        }
    }

    // ── ② 白名单外：另一种拒绝 ─────────────────────────────────

    @Test
    fun `白名单外字段抛异常且原因码是可识别的未知字段`() {
        val tuner = tuner()
        val ref = PersonaFieldRefs.resolve("voice_timbre")
        assertTrue(ref is PersonaFieldRef.Unknown)

        // ⚠️ 必须是 NOT_WHITELISTED 而不是 PROTECTED_BONES。
        //    合并成同一个码会让"攻击尝试"和"没听清"长得一样。
        expectRejection(PersonaRejectionReason.NOT_WHITELISTED) {
            tuner.tune(ref, "低音", DeltaSource.EXPLICIT, listOf("换个声音"), 1_000L)
        }
    }

    // ── ③ 白名单内的合法微调 ───────────────────────────────────

    @Test
    fun `白名单字段与裸轴别名都能命中`() {
        assertEquals(
            PersonaField.TONE_WARMTH,
            (PersonaFieldRefs.resolve("TONE_WARMTH") as PersonaFieldRef.Whitelisted).field,
        )
        assertEquals(
            PersonaField.TONE_WARMTH,
            (PersonaFieldRefs.resolve("warmth") as PersonaFieldRef.Whitelisted).field,
        )
        assertEquals(
            PersonaField.ADDRESS_STYLE,
            (PersonaFieldRefs.resolve("address_style") as PersonaFieldRef.Whitelisted).field,
        )
    }

    @Test
    fun `显式微调白名单字段立即生效`() {
        val tuner = tuner()
        val result = tuner.tune(
            PersonaFieldRefs.resolve("address_style"),
            "  老张  ",
            DeltaSource.EXPLICIT,
            listOf("以后叫我老张"),
            5_000L,
        )

        val applied = result as TuneResult.Applied
        assertEquals("老张", applied.spec.addressStyle)   // 已归一（去首尾空白）
        assertEquals("你", applied.delta.oldValue)
    }

    // ── ④ 值不合法：拒绝而不是"帮你改好" ───────────────────────

    @Test
    fun `轴越界抛异常而不是静默钳制`() {
        val tuner = tuner()
        // ★ 若实现是 coerceAtMost(100)，这里会得到一条**成功**记录，
        //   而用户说的 200 被吃掉了 —— 日志里查不出来。
        expectRejection(PersonaRejectionReason.INVALID_VALUE) {
            tuner.tune(
                PersonaFieldRefs.resolve("TONE_WARMTH"),
                "200",
                DeltaSource.EXPLICIT,
                listOf("再温柔一点，拉满"),
                1_000L,
            )
        }
        assertEquals("越界被拒后人格不应有任何变化", 55, tuner.currentSpec().tone.warmth)
    }

    @Test
    fun `轴收到非数字抛异常`() {
        val tuner = tuner()
        expectRejection(PersonaRejectionReason.INVALID_VALUE) {
            tuner.tune(
                PersonaFieldRefs.resolve("tone_brevity"),
                "很简洁",
                DeltaSource.EXPLICIT,
                listOf("说短点"),
                1_000L,
            )
        }
    }

    @Test
    fun `轴边界值合法`() {
        val tuner = tuner()
        val low = tuner.tune(
            PersonaFieldRefs.resolve("tone_emoji"),
            "0",
            DeltaSource.EXPLICIT,
            listOf("别用表情"),
            1_000L,
        ) as TuneResult.Applied
        assertEquals(0, low.spec.tone.emoji)

        val high = tuner.tune(
            PersonaFieldRefs.resolve("tone_emoji"),
            "100",
            DeltaSource.EXPLICIT,
            listOf("多用表情"),
            2_000L,
        ) as TuneResult.Applied
        assertEquals(100, high.spec.tone.emoji)
    }

    // ── ⑤ 证据不合法 ───────────────────────────────────────────

    @Test
    fun `隐式反馈引用未知信号被拒`() {
        val tuner = tuner()
        expectRejection(PersonaRejectionReason.INVALID_EVIDENCE) {
            tuner.tune(
                PersonaFieldRefs.resolve("tone_brevity"),
                "70",
                DeltaSource.IMPLICIT,
                listOf("USER_LOOKED_UNHAPPY"),   // 不在证据信号表里
                1_000L,
            )
        }
    }

    @Test
    fun `空证据被拒`() {
        val tuner = tuner()
        expectRejection(PersonaRejectionReason.INVALID_EVIDENCE) {
            tuner.tune(
                PersonaFieldRefs.resolve("tone_brevity"),
                "70",
                DeltaSource.IMPLICIT,
                emptyList(),
                1_000L,
            )
        }
    }
}