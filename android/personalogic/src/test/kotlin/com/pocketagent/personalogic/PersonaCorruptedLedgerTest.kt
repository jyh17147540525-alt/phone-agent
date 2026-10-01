package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **坏账本只该让那一条不生效，不该让整个助理起不来。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  这条测试挡的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * [PersonaSpec.derive] 是人格快照的**唯一**构造入口，而它的输入是
 * 持久化的账本 —— 也就是说，一份来自更早版本、被手工编辑过、
 * 或序列化损坏的账本会**直接喂进回放路径**。
 *
 * 回放路径上会遇到两类"值不对"，处理方式**刻意不同**：
 *
 * | 情况 | 处理 | 为什么 |
 * |---|---|---|
 * | **越界**（`"200"`） | [ToneAxes.coerce] 钳到 `100` | 是有意义的数字，钳制后继续 |
 * | **不是数字**（`"abc"`） | **跳过这一条** | 无法解读，但**不能抛异常** |
 *
 * ⚠️ 第二类曾经会抛 `NumberFormatException`：`coerce` 只管**越界**，
 *    而调用点是 `delta.newValue.toInt()`。后果不是"这条不生效"，
 *    是 **`currentSpec()` 直接崩、助理起不来** —— 正是
 *    [ToneAxes.coerce] 注释里点名的「安静地做不了任何事」。
 *
 *    注释写着"回放路径钳制并继续，抛异常会让整个助理起不来"，
 *    而实现只覆盖了越界那一半。**这条测试就是那另一半。**
 *
 * ⇒ 判据：**坏数据只该让那一条不生效。**
 */
class PersonaCorruptedLedgerTest {

    private val preset = PersonaPresets.MALE

    /** 造一条轴类 delta。`value` 由调用方给 —— 可以给非数字，那是本文件的重点。 */
    private fun axisDelta(
        value: String,
        id: String = "pd-1",
        createdAt: Long = 1_000L,
        state: DeltaState = DeltaState.ACTIVE,
    ) = PersonaDelta(
        id = id,
        field = PersonaField.TONE_WARMTH,
        oldValue = "55",
        newValue = value,
        source = DeltaSource.EXPLICIT,
        evidence = listOf("测试构造的坏数据"),
        confidence = 1.0,
        state = state,
        createdAt = createdAt,
    )

    @Test
    fun `轴值不是数字时回放不抛异常`() {
        // ★ 这条如果红了，说明回放路径又会抛 —— 助理起不来。
        //   不需要额外断言"没抛"：抛了就走不到下一行。
        val spec = PersonaSpec.derive(preset, listOf(axisDelta("abc")))

        assertEquals("坏数据那一条不生效，保持预设值", preset.tone.warmth, spec.tone.warmth)
    }

    @Test
    fun `坏数据不阻断它前后的好数据`() {
        val spec = PersonaSpec.derive(
            preset,
            listOf(
                axisDelta("70", id = "pd-1", createdAt = 1L),
                axisDelta("不是数字", id = "pd-2", createdAt = 2L),
                axisDelta("90", id = "pd-3", createdAt = 3L),
            ),
        )

        // 第三条是好数据，必须照样生效 ——「跳过一条」≠「跳过后面所有」
        assertEquals(90, spec.tone.warmth)
    }

    @Test
    fun `越界的历史值仍然钳制而不是跳过`() {
        // 与"非数字"刻意区分：越界是有意义的数字，钳制后**继续**（见 ToneAxes.coerce）
        val spec = PersonaSpec.derive(preset, listOf(axisDelta("200")))

        assertEquals("越界应钳到上限，而不是被当成坏数据跳过", ToneAxes.MAX, spec.tone.warmth)
    }

    @Test
    fun `负的越界值钳到下界`() {
        val spec = PersonaSpec.derive(preset, listOf(axisDelta("-7")))

        assertEquals(ToneAxes.MIN, spec.tone.warmth)
    }

    @Test
    fun `空账本得到的就是预设本身`() {
        val spec = PersonaSpec.derive(preset, emptyList())

        assertEquals(preset.tone, spec.tone)
        assertEquals(preset.addressStyle, spec.addressStyle)
        assertEquals(preset.dialect, spec.dialect)
        assertTrue("骨骼必须完好", spec.bones.isIntact())
    }

    @Test
    fun `坏数据不影响候选区条目的排除`() {
        // CANDIDATE 本来就不参与回放；这里确认"坏数据 + 候选"两个因素叠加时
        // 仍然既不过滤掉候选（那是另一条规则的事）也不因候选而崩
        val spec = PersonaSpec.derive(
            preset,
            listOf(
                axisDelta("abc", id = "pd-1", createdAt = 1L),
                axisDelta("80", id = "pd-2", createdAt = 2L, state = DeltaState.CANDIDATE),
            ),
        )

        assertEquals("候选区条目不得参与回放", preset.tone.warmth, spec.tone.warmth)
    }
}
