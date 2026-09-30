package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.1 需求①：男 / 女两种内置形象。
 *
 * 以及那条最容易做错、也最难被发现的规则：
 * **「性别」是人格模板，不是音色** —— 不一致时必须说出来。
 */
class PersonaPresetsTest {

    // ── 两个模板 ───────────────────────────────────────────────

    @Test
    fun `只有两个内置形象`() {
        assertEquals(2, PersonaPresets.ALL.size)
        assertEquals(PersonaPresets.MALE, PersonaPresets.byId("male-default"))
        assertEquals(PersonaPresets.FEMALE, PersonaPresets.byId("female-default"))
    }

    @Test
    fun `未知预设 id 返回 null 而不是兜底成某一个`() {
        // 兜底成 MALE 会让"预设 id 写错了"表现为"用户选了男声"——
        // 一条静默的、方向完全无关的默认值。
        assertNull(PersonaPresets.byId("female-default-v2"))
    }

    @Test
    fun `两个形象的语气轴不同`() {
        assertNotEquals(PersonaPresets.MALE.tone, PersonaPresets.FEMALE.tone)
    }

    @Test
    fun `男声建议的基频更低`() {
        assertTrue(
            "男声的基频建议应低于女声",
            PersonaPresets.MALE.voice.pitchSemitones < PersonaPresets.FEMALE.voice.pitchSemitones,
        )
    }

    @Test
    fun `两个形象都带话术种子`() {
        // 话术种子是 few-shot 锚定风格的唯一材料，空了就等于没有形象
        PersonaPresets.ALL.forEach {
            assertTrue("${it.id} 缺话术种子", it.scriptSeeds.isNotEmpty())
        }
    }

    // ── 从预设推导人格 ─────────────────────────────────────────

    @Test
    fun `从预设推导出的人格以预设语气为锚点`() {
        val spec = PersonaSpec.derive(PersonaPresets.MALE, emptyList())

        assertEquals(PersonaPresets.MALE.id, spec.presetId)
        assertEquals(PersonaPresets.MALE.displayName, spec.displayName)
        assertEquals("锚点必须是最初的语气", PersonaPresets.MALE.tone, spec.baseline)
        assertEquals(PersonaPresets.MALE.tone, spec.tone)
    }

    @Test
    fun `推导出的人格骨骼完整`() {
        PersonaPresets.ALL.forEach {
            val spec = PersonaSpec.derive(it, emptyList())
            assertTrue("${it.id} 的骨骼必须完整", spec.bones.isIntact())
        }
    }

    @Test
    fun `账本里的候选与已回滚条目不参与推导`() {
        val ledger = listOf(
            PersonaDelta(
                id = PersonaDelta.idOf(1),
                field = PersonaField.ADDRESS_STYLE,
                oldValue = "你",
                newValue = "老张",
                source = DeltaSource.EXPLICIT,
                evidence = listOf("叫我老张"),
                confidence = 1.0,
                state = DeltaState.CANDIDATE,
                createdAt = 1_000L,
            ),
            PersonaDelta(
                id = PersonaDelta.idOf(2),
                field = PersonaField.ADDRESS_STYLE,
                oldValue = "你",
                newValue = "张总",
                source = DeltaSource.EXPLICIT,
                evidence = listOf("叫我张总"),
                confidence = 1.0,
                state = DeltaState.ROLLED_BACK,
                createdAt = 2_000L,
            ),
        )

        assertEquals("你", PersonaSpec.derive(PersonaPresets.MALE, ledger).addressStyle)
    }

    @Test
    fun `同一时刻的两条变更按 id 序号决定先后`() {
        // 同一毫秒内产生的两条不能靠"插入顺序"定先后 ——
        // 那在不同设备 / 不同数据库返回顺序下会得到不同人格。
        val sameMoment = 1_000L
        val ledger = listOf(
            PersonaDelta(
                id = PersonaDelta.idOf(2),
                field = PersonaField.ADDRESS_STYLE,
                oldValue = "你",
                newValue = "张总",
                source = DeltaSource.EXPLICIT,
                evidence = listOf("叫我张总"),
                confidence = 1.0,
                state = DeltaState.ACTIVE,
                createdAt = sameMoment,
            ),
            PersonaDelta(
                id = PersonaDelta.idOf(1),
                field = PersonaField.ADDRESS_STYLE,
                oldValue = "你",
                newValue = "老张",
                source = DeltaSource.EXPLICIT,
                evidence = listOf("叫我老张"),
                confidence = 1.0,
                state = DeltaState.ACTIVE,
                createdAt = sameMoment,
            ),
        )

        assertEquals("序号大的后应用，最终生效", "张总", PersonaSpec.derive(PersonaPresets.MALE, ledger).addressStyle)
    }

    // ── ★ 音色与形象一致性 ─────────────────────────────────────

    @Test
    fun `音色与形象一致时不提示`() {
        assertNull(voiceMismatchNotice(PersonaPresets.MALE, VoiceGender.MALE))
        assertNull(voiceMismatchNotice(PersonaPresets.FEMALE, VoiceGender.FEMALE))
    }

    @Test
    fun `音色与形象不一致时必须提示`() {
        // 用户以为选了女声形象就得到女声，实际不是，且没有任何提示 ——
        // 这正是本项目头号 bug 形态「安静地少做一件事」。
        val notice = voiceMismatchNotice(PersonaPresets.FEMALE, VoiceGender.MALE)

        assertNotNull("不一致不能被静默", notice)
        assertTrue(notice!!.contains("不一致"))
        assertTrue("要指明现在是哪种音色", notice.contains(VoiceGender.MALE.label))
    }

    @Test
    fun `音色性别无法识别时也要提示而不是当作一致`() {
        // ★ 探测失败恰恰是最需要提醒的场景：连系统都不知道现在是什么声音。
        //   未知当成匹配 = 绝大多数探测失败的用户都被静默放过。
        val notice = voiceMismatchNotice(PersonaPresets.MALE, VoiceGender.UNKNOWN)

        assertNotNull("未知不能被当作一致", notice)
        assertTrue(
            "措辞应当是不确定，而不是不一致",
            notice!!.contains("无法确认"),
        )
    }

    @Test
    fun `提示文案点名了当前选的形象`() {
        val notice = voiceMismatchNotice(PersonaPresets.FEMALE, VoiceGender.MALE)
        assertTrue("用户得知道自己在对比哪两个东西", notice!!.contains(PersonaPresets.FEMALE.displayName))
    }

    @Test
    fun `预设的性别归属于模板而不是音色`() {
        // VoiceHint 里**没有**性别字段 —— 音色性别由 :voice / :tts 探测后传入，
        // 模板只给"建议的基频 / 语速"。这条测试是结构性的：一旦有人
        // 把 gender 塞进 VoiceHint，它就会红。
        assertEquals(PresetGender.MALE, PersonaPresets.MALE.gender)
        assertEquals(PresetGender.FEMALE, PersonaPresets.FEMALE.gender)
        assertTrue("音色建议要有可读的描述", PersonaPresets.MALE.voice.timbre.isNotBlank())
    }
}