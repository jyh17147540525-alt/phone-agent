package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/** 人格模板的性别归属 —— **是人格模板的属性，不是音色的属性** */
@Serializable
enum class PresetGender {
    MALE,
    FEMALE,
}

/** 实际音色的性别。由语音层（`:voice` / `:tts`）探测后传入 */
enum class VoiceGender(val label: String) {
    MALE("男声"),
    FEMALE("女声"),

    /**
     * 探测不出来。
     *
     * ⚠️ 这一项**必须存在**，且**不能**在 UI 上被当成"匹配"。
     *    未知当成匹配 = 用户选了女声形象、装了男声音色、什么都没提示 ——
     *    这正是本项目头号 bug 形态「安静地少做一件事」。
     */
    UNKNOWN("未知音色"),
}

/** 音色参数**建议**。注意是"建议"：最终音色由用户导入的 TTS 决定 */
@Serializable
data class VoiceHint(
    /** 音色描述，给人看的 */
    val timbre: String,
    /** 建议基频偏移（半音）。负值更低 */
    val pitchSemitones: Double,
    /** 建议语速倍率 */
    val speedRatio: Double,
)

/**
 * 内置人格模板（男声助理 / 女声助理）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 「性别」是人格模板，不是音色
 * ═══════════════════════════════════════════════════════════════
 *
 * 音色来自**用户导入的 TTS**（需求④）。两者是独立的两件事，因此
 * 完全可能不一致：用户选了女声形象，导入的却是男声音色。
 *
 * ⇒ 不一致必须**被说出来**（见 [voiceMismatchNotice]），不能静默。
 *    否则用户以为选了女声就得到女声，实际不是，且没有任何提示。
 *
 * ⇒ 也正因如此，[VoiceHint] 只是**建议**。人格模板不能决定音色 ——
 *    它决定了也没有用，音色不归它管。
 *
 * @property addressStyle 默认称呼方式
 * @property tone 默认语气轴（同时是反漂移锚点 [PersonaSpec.baseline] 的来源）
 * @property voice 音色建议
 * @property scriptSeeds 话术种子，用于 few-shot 锚定风格
 * @property avatarRes 可选头像资源名；null 表示用默认图形
 */
@Serializable
data class PersonaPreset(
    val id: String,
    val displayName: String,
    val gender: PresetGender,
    val addressStyle: String,
    val tone: ToneAxes,
    val voice: VoiceHint,
    val catchphrase: String = "",
    val dialect: String = "",
    val scriptSeeds: List<String> = emptyList(),
    val avatarRes: String? = null,
)

/** 两个内置模板。**只有两个** —— 用户自建人格属于后续阶段 */
object PersonaPresets {

    val MALE: PersonaPreset = PersonaPreset(
        id = "male-default",
        displayName = "男声助理",
        gender = PresetGender.MALE,
        addressStyle = "你",
        tone = ToneAxes(
            warmth = 55,
            brevity = 55,
            humor = 25,
            proactivity = 40,
            formality = 35,
            emoji = 15,
        ),
        voice = VoiceHint(timbre = "低沉稳重", pitchSemitones = -2.0, speedRatio = 0.95),
        scriptSeeds = listOf(
            "收到，这就办。",
            "这事儿我帮你盯着，有结果就说。",
        ),
        avatarRes = null,
    )

    val FEMALE: PersonaPreset = PersonaPreset(
        id = "female-default",
        displayName = "女声助理",
        gender = PresetGender.FEMALE,
        addressStyle = "你",
        tone = ToneAxes(
            warmth = 65,
            brevity = 50,
            humor = 30,
            proactivity = 40,
            formality = 30,
            emoji = 25,
        ),
        voice = VoiceHint(timbre = "清亮柔和", pitchSemitones = 1.0, speedRatio = 1.0),
        scriptSeeds = listOf(
            "好呀，我来处理。",
            "别担心，交给我。",
        ),
        avatarRes = null,
    )

    val ALL: List<PersonaPreset> = listOf(MALE, FEMALE)

    /** 按 id 取预设；未知 id 返回 null（**不兜底成 MALE** —— 见函数注释） */
    fun byId(id: String): PersonaPreset? = ALL.firstOrNull { it.id == id }
}

/**
 * 生成"音色与形象不一致"的提示文案。
 *
 * @return null 表示一致、无需提示；非 null 时**必须展示给用户**
 *
 * ## 为什么未知音色也要给提示，而不是返回 null
 *
 * 探测失败时返回 null（= 视为一致）意味着：绝大多数探测失败的用户
 * 都会被静默地当成"没问题"。而探测失败恰恰是最需要提醒的场景 ——
 * 连系统都不知道现在是什么声音，用户更不知道。
 *
 * ⇒ 未知给出的是**措辞不同但同样明确**的提示：不是"不一致"，
 *    而是"不确定"。诚实是骨骼的一部分（[SafetyBones.honesty]），
 *    而"不确定时当作没问题"是对它的直接违反。
 */
fun voiceMismatchNotice(preset: PersonaPreset, actualVoice: VoiceGender): String? = when {
    actualVoice == VoiceGender.UNKNOWN ->
        "无法确认当前音色的性别。你选的是「${preset.displayName}」形象，" +
            "建议检查一下导入的音色是否与它一致。"

    actualVoice.matches(preset.gender) -> null

    else ->
        "当前音色是${actualVoice.label}，与你选的「${preset.displayName}」形象不一致 —— " +
            "形象决定说话的方式，音色决定声音，两者可以不同，但你选了形象时大概是想一致。"

}

private fun VoiceGender.matches(gender: PresetGender): Boolean = when (gender) {
    PresetGender.MALE -> this == VoiceGender.MALE
    PresetGender.FEMALE -> this == VoiceGender.FEMALE
}