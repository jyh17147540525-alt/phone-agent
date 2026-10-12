package com.pocketagent.assistant

import com.pocketagent.personalogic.DefaultPersonaTuner
import com.pocketagent.personalogic.DeltaSource
import com.pocketagent.personalogic.DeltaState
import com.pocketagent.personalogic.PersonaDelta
import com.pocketagent.personalogic.PersonaEvent
import com.pocketagent.personalogic.PersonaEventKind
import com.pocketagent.personalogic.PersonaField
import com.pocketagent.personalogic.PersonaFieldRef
import com.pocketagent.personalogic.PersonaPresets
import com.pocketagent.personalogic.PersonaRuntime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AssistantPromptBuilder]：人格真的进了系统提示词，且**会跟着变**。
 *
 * ⚠️ 这个测试守的是**静默失效**这一类 bug：
 *    - 提示词里没有人格 → 助理变成"通用模型"，界面上完全看不出来；
 *    - 状态被缓存 → 「今天心情差」在几轮之后失效，模型只是又变回中性语气；
 *    - 微调没接上 → 用户改了口癖，助理还是老样子（而他说不出哪里不对）。
 *
 * ⚠️ 它在 `:app` 的 JVM 单测里跑（`./gradlew :app:testDebugUnitTest`）——
 *    `:app` 不是纯 Kotlin 模块，进不了 `tools/verify/run_logic_tests.py`。
 *    这是位置选择的代价，如实记在这里。
 */
class AssistantPromptTest {

    private fun persona(): AssistantPersona = AssistantPersona(
        preset = PersonaPresets.MALE,
        runtime = PersonaRuntime(DefaultPersonaTuner(PersonaPresets.MALE)),
    )

    // ── ① 人格真的进去了 ────────────────────────────────────────

    @Test
    fun `提示词含自称与对用户的称呼`() {
        val text = AssistantPromptBuilder(persona()).build()
        assertTrue("应含自称：${text.take(400)}", text.contains("男声助理"))
        assertTrue("应含对用户的称呼", text.contains("你"))
    }

    @Test
    fun `提示词含语言指纹的禁用语`() {
        val text = AssistantPromptBuilder(persona()).build()
        // 禁用语来自 LanguageFingerprint 的默认集 —— 它们必须在提示词里，
        // 否则「不许说」这条约束对模型根本不存在。
        // ⚠️ 措辞以 `LanguageFingerprint.render()` 为准：**「禁止出现的说法」**。
        //    第一版我按「禁用语」断言，测试红了 —— 而功能是对的，是我记错了词。
        assertTrue("应含禁用语小节", text.contains("禁止出现的说法"))
    }

    @Test
    fun `提示词含 L0 安全骨骼`() {
        val text = AssistantPromptBuilder(persona()).build()
        assertTrue("应声明不代支付", text.contains("支付"))
        assertTrue("应声明不代解锁", text.contains("解锁"))
    }

    @Test
    fun `提示词含能力边界声明`() {
        val text = AssistantPromptBuilder(persona()).build()
        assertTrue("应含能力边界", text.contains("能力边界"))
        assertTrue("应说明只能对话", text.contains("只能对话"))
        // ★ 能力边界与人格是两段，都要在
        assertTrue("应含人格层（身份）", text.contains("身份") || text.contains("男声助理"))
    }

    // ── ② ★ 状态是现读的（缓存 = 静默失效）──────────────────────

    @Test
    fun `事件之后提示词会变 —— 状态没有被缓存`() {
        val p = persona()
        val before = AssistantPromptBuilder(p).build()

        // 制造一个**跨过渲染阈值**的状态：
        // ⚠️ `PersonaAssembler.renderState` 只在 mood ≥ 65（或 ≤ 35）时才渲染 ——
        //    这是刻意的（"必须可解释"，无由头/无幅度就不渲染）。
        //    单个 PRAISED 只把 mood 从 50 推到 62，**差 3 点进不了渲染区**，
        //    第一版我按"一次事件就该变"断言，测试红了 —— 而代码是对的。
        repeat(2) {
            p.runtime.apply(
                PersonaEvent(PersonaEventKind.PRAISED, reason = "陪用户熬夜把活儿赶完了"),
            )
        }
        val after = AssistantPromptBuilder(p).build()

        assertFalse("状态变了，提示词必须跟着变", before == after)
        assertTrue("由头应出现在提示词里：$after", after.contains("陪用户熬夜把活儿赶完了"))
    }

    @Test
    fun `同一实例连续 build 两次会反映最新的状态`() {
        val p = persona()
        val builder = AssistantPromptBuilder(p)
        val first = builder.build()
        // 同样要跨过渲染阈值（见上一个测试的注释）
        repeat(2) {
            p.runtime.apply(
                PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被用户凶了一句"),
            )
        }
        val second = builder.build()
        // 若 builder 缓存了组装结果，这里会相等 —— 而那正是要防的
        assertFalse("builder 不能缓存组装结果", first == second)
        assertTrue(second.contains("被用户凶了一句"))
    }

    // ── ③ ★ 微调会生效（账本回放接上了）─────────────────────────

    @Test
    fun `改称呼之后提示词跟着改`() {
        val tuner = DefaultPersonaTuner(PersonaPresets.MALE)
        val p = AssistantPersona(
            preset = PersonaPresets.MALE,
            runtime = PersonaRuntime(tuner),
        )
        val before = AssistantPromptBuilder(p).build()
        assertTrue("默认称呼应在：$before", before.contains("你"))

        tuner.tune(
            ref = PersonaFieldRef.Whitelisted(PersonaField.ADDRESS_STYLE),
            newValue = "老板",
            source = DeltaSource.EXPLICIT,
            evidence = listOf("以后叫我老板"),
            now = 1_000L,
        )

        val after = AssistantPromptBuilder(p).build()
        assertTrue("微调后的称呼应进提示词：$after", after.contains("老板"))
        assertFalse("提示词应当变了", before == after)
    }

    @Test
    fun `账本里非 ACTIVE 的变更不进提示词`() {
        val tuner = DefaultPersonaTuner(PersonaPresets.MALE)
        val p = AssistantPersona(PersonaPresets.MALE, PersonaRuntime(tuner))
        tuner.restore(
            listOf(
                PersonaDelta(
                    id = PersonaDelta.idOf(1),
                    field = PersonaField.ADDRESS_STYLE,
                    oldValue = "你",
                    newValue = "主人",
                    source = DeltaSource.IMPLICIT,
                    evidence = listOf("随口一说"),
                    confidence = 0.4,
                    // ★ 候选（未生效）—— 它**不该**影响提示词
                    state = DeltaState.CANDIDATE,
                    createdAt = 1_000L,
                ),
            ),
        )
        val text = AssistantPromptBuilder(p).build()
        assertFalse("候选变更不该进提示词：$text", text.contains("主人"))
    }

    // ── ④ 诊断信息 ──────────────────────────────────────────────

    @Test
    fun `diagnose 报出层与预算`() {
        val d = AssistantPromptBuilder(persona()).diagnose()
        assertTrue("应有 L0 骨骼层", d.layers.contains("L0_BONES"))
        assertTrue("预估 token 应为正", d.estimatedTokens > 0)
        assertTrue("默认人格不该超预算", !d.overBudget)
    }
}
