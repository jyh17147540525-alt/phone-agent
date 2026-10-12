package com.pocketagent.mcp

import com.pocketagent.personalogic.DeltaSource
import com.pocketagent.personalogic.DeltaState
import com.pocketagent.personalogic.PersonaDelta
import com.pocketagent.personalogic.PersonaEvent
import com.pocketagent.personalogic.PersonaEventKind
import com.pocketagent.personalogic.PersonaField
import com.pocketagent.personalogic.PersonaPresets
import com.pocketagent.personalogic.PersonaSpec
import com.pocketagent.personalogic.PersonaState
import com.pocketagent.personalogic.PersonaStateEngine
import com.pocketagent.personalogic.SafetyBones
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `persona_brief`：只读、骨骼报警、中性不编由头、禁用语进约束。
 *
 * ⚠️ 这个测试文件不只是"工具测试" —— 它是 `:mcp → :personalogic` 这条依赖边
 *    的**活体验证**：能编译过就说明两个模块真的接上了（而在此之前，
 *    `:personalogic` 的 5000+ 行在 `classes.dex` 里一个字节都没有）。
 */
class PersonaBriefToolTest {

    /** 用真实的 preset + 空账本 ⇒ 一个"标准人格"。不 stub —— 映射逻辑本身要被测。 */
    private fun defaultSpec(): PersonaSpec =
        PersonaSpec.derive(PersonaPresets.MALE, emptyList())

    private fun toolWith(
        spec: PersonaSpec = defaultSpec(),
        state: PersonaState? = null,
    ): PersonaBriefTool = PersonaBriefTool { personaBriefOf(spec, state) }

    // ── ① 正常路径：身份、称呼、语气、禁用语都进得去 ────────────────

    @Test
    fun `正常路径返回自称与对用户的称呼`() = runTest {
        val outcome = toolWith().call(buildJsonObject { })
        assertTrue(outcome is ToolOutcome.Success)
        val text = (outcome as ToolOutcome.Success).text
        assertTrue("应含自称：$text", text.contains("男声助理"))
        assertTrue("应含对用户的称呼：$text", text.contains("你"))
    }

    @Test
    fun `禁用语作为可执行约束出现（不是风格描述）`() = runTest {
        val outcome = toolWith().call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        // 语言指纹的默认禁用语里含"作为一个AI"这类 —— 它必须出现在文本里，
        // 否则这条"不许说"的约束对模型根本不存在。
        assertTrue("应含禁用语小节：$text", text.contains("禁用语"))
    }

    // ── ② ★ 中性状态不编由头 ─────────────────────────────────────

    @Test
    fun `中性状态不编造由头`() = runTest {
        val outcome = toolWith(state = PersonaState()).call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        assertTrue("应说明无特别由头：$text", text.contains("没有特别由头"))
        // ★ 反向断言：不能出现任何看起来像"原因"的句子。
        //   （中性时给个由头 = 教会模型"没有由头也可以有情绪"）
        assertFalse("中性时不该有由头行：$text", text.contains("由头："))
    }

    @Test
    fun `有由头时如实带出（可被复述）`() = runTest {
        val engine = PersonaStateEngine()
        val state = engine.apply(
            PersonaState(),
            PersonaEvent(PersonaEventKind.PRAISED, reason = "你今天把事儿办得很漂亮"),
        )
        val outcome = toolWith(state = state).call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        assertTrue("应带出由头：$text", text.contains("你今天把事儿办得很漂亮"))
    }

    // ── ③ ★★ 骨骼报警：绕过 derive 造出的 spec 必须被拦下 ─────────

    @Test
    fun `骨骼不完整时报警而不是照常返回`() = runTest {
        // 造一个"骨骼被改坏"的 spec —— 现实里只有绕过 PersonaSpec.derive 才可能。
        // 用 copy 模拟（data class 的 copy 是本测试唯一的造假入口）。
        val broken = defaultSpec().copy(
            bones = SafetyBones(refusePayment = false),
        )
        val outcome = toolWith(spec = broken).call(buildJsonObject { })
        assertTrue("应返回 Failed 而非 Success", outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("报警应点名缺了什么：$reason", reason.contains("拒绝代支付"))
        assertTrue("应要求告知用户：$reason", reason.contains("停止使用"))
    }

    @Test
    fun `骨骼完整时正常返回且 structured 里 intact 为 true`() = runTest {
        val outcome = toolWith().call(buildJsonObject { })
        val structured = (outcome as ToolOutcome.Success).structured
        assertNotNull(structured)
        assertTrue(structured!!.jsonObject["bones"]!!.jsonObject["intact"]!!.jsonPrimitive.content == "true")
    }

    // ── ④ ★ 安全红线作为「对 agent 的契约」出现 ────────────────────

    @Test
    fun `安全红线文本要求 agent 主动绕开而非先试再报`() = runTest {
        val outcome = toolWith().call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        assertTrue("应含不代支付：$text", text.contains("不代用户支付"))
        assertTrue("应含不代解锁：$text", text.contains("不代用户解锁"))
        assertTrue("应要求主动绕开：$text", text.contains("主动绕开"))
    }

    // ── ⑤ structured 的形状（程序化调用方依赖它）──────────────────

    @Test
    fun `structured 的 reason 用显式 null 而非省略`() = runTest {
        val outcome = toolWith(state = PersonaState(reason = null)).call(buildJsonObject { })
        val state = (outcome as ToolOutcome.Success).structured!!.jsonObject["state"]!!.jsonObject
        // ★ 省略字段会让"没有由头"与"字段名拼错"在程序化调用方眼里长得一样。
        assertTrue("reason 必须是显式 null", state["reason"] is JsonNull)
    }

    @Test
    fun `structured 镜像了语气六轴`() = runTest {
        val outcome = toolWith().call(buildJsonObject { })
        val tone = (outcome as ToolOutcome.Success).structured!!.jsonObject["tone"]!!.jsonObject
        assertEquals(6, tone.size)
    }

    // ── ⑥ ★ 只读：端口接口里没有任何写操作 ────────────────────────

    @Test
    fun `端口只有读操作（写操作在类型层面不存在）`() {
        // PersonaBriefPort 是 fun interface，唯一的方法是 brief()。
        // 这条测试是"结构性断言"的文档化：如果将来有人往端口里加
        // applyEvent(...) / setPersona(...)，这里的方法数会变。
        val methods = PersonaBriefPort::class.java.declaredMethods
            .filter { it.declaringClass == PersonaBriefPort::class.java }
        assertEquals("端口应当只有一个方法", 1, methods.size)
    }

    // ── ⑦ 映射正确性：spec 的微调结果如实反映 ─────────────────────

    @Test
    fun `微调后的称呼如实反映在摘要里`() = runTest {
        val delta = PersonaDelta(
            id = PersonaDelta.idOf(1),
            field = PersonaField.ADDRESS_STYLE,
            oldValue = "你",
            newValue = "老板",
            source = DeltaSource.EXPLICIT,
            evidence = listOf("以后叫我老板"),
            confidence = 1.0,
            state = DeltaState.ACTIVE,
            createdAt = 1_000L,
        )
        val spec = PersonaSpec.derive(PersonaPresets.MALE, listOf(delta))
        val outcome = toolWith(spec = spec).call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        assertTrue("微调后的称呼应生效：$text", text.contains("老板"))
    }
}
