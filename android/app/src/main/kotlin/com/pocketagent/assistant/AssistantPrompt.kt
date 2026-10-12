package com.pocketagent.assistant

import com.pocketagent.personalogic.AssemblyInput
import com.pocketagent.personalogic.PersonaAssembler
import com.pocketagent.personalogic.PersonaCard
import com.pocketagent.personalogic.PersonaPreset
import com.pocketagent.personalogic.PersonaRuntime

/**
 * 助理人格 —— 「模板 + 运行时」这一对。
 *
 * ⚠️ 刻意把 [preset] 和 [runtime] **装在一起**：`PersonaRuntime` 内部只持有一个
 *    `PersonaTuner`，而 `PersonaCard.of(preset, spec)` 需要 preset。
 *    若在需要的地方各自去 `PersonaPresets.byId(spec.presetId) ?: 兜底`，
 *    那个兜底就会有多份 —— 而"模板 id 认不出来时退到哪个"是个会漂移的判断。
 */
class AssistantPersona(
    val preset: PersonaPreset,
    val runtime: PersonaRuntime,
)

/**
 * 助理的系统提示词构造器 —— 把 `:personalogic` 的装配器接到聊天路径上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是 `PersonaAssembler` 的第一个消费方
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，`PersonaAssembler`（"人格 → system prompt"的唯一出口）**零引用** ——
 * 与 `:personalogic` / `:memorylogic` 同一类问题。
 *
 * 而它接上的那一刻，"助理有人格"这件事才第一次**真的生效**：
 * 在此之前聊天页用的是一个硬编码常量（见 [CAPABILITY_BOUNDARY] 的注释），
 * 用户改口癖、改称呼，模型那边**一个字都不会变**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 状态是**每次组装时现读**的（不缓存）
 * ═══════════════════════════════════════════════════════════════
 *
 * `runtime.currentState()` 会推进时间（见 `PersonaRuntime` 的注释）。
 * 缓存组装结果会让"今天心情差"这个事实在几轮对话之后失效 ——
 * 而那种失效**在界面上完全看不出来**（模型只是又变回了中性语气）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 记忆层还没接（`memories` 传空）
 * ═══════════════════════════════════════════════════════════════
 *
 * `AssemblyInput.memories` 是 L3/L4 的注入位。`AtomExtractor` / `MemoryGate` 那条线
 * 还没有消费方（`:memorylogic` 的 `MemoryGate` 至今零引用），
 * 所以这里**如实传空** —— 而不是编几条假的"记忆"让提示词看起来丰富。
 *
 * 传空的后果是：助理目前**不会记住任何事**。这与 `:memory` 模块零源码是同一件事，
 * 不是本文件能解决的。
 */
class AssistantPromptBuilder(
    private val persona: AssistantPersona,
    private val assembler: PersonaAssembler = PersonaAssembler(),
) {

    /**
     * 组装系统提示词。
     *
     * @return 装配器的输出 + 一段**能力边界**声明（见 [CAPABILITY_BOUNDARY]）。
     */
    fun build(): String {
        val spec = persona.runtime.currentSpec()
        val assembled = assembler.assemble(
            AssemblyInput(
                card = PersonaCard.of(persona.preset, spec),
                tone = spec.tone,
                // ★ 现读：`currentState()` 会推进时间，冻结的状态等于说一句假话
                state = persona.runtime.currentState(),
                // ⚠️ 记忆层未接，如实传空（见类注释）
                memories = emptyList(),
            ),
        )
        return assembled.text + "\n\n" + CAPABILITY_BOUNDARY
    }

    /**
     * 装配结果的诊断信息（给将来的"人格体检页"用；当前无消费方，但**不删** ——
     * 它是"提示词是不是被裁过"的唯一可观测口径，而裁剪是静默的）。
     */
    fun diagnose(): PromptDiagnostics {
        val spec = persona.runtime.currentSpec()
        val assembled = assembler.assemble(
            AssemblyInput(
                card = PersonaCard.of(persona.preset, spec),
                tone = spec.tone,
                state = persona.runtime.currentState(),
            ),
        )
        return PromptDiagnostics(
            estimatedTokens = assembled.estimatedTokens,
            overBudget = assembled.overBudget,
            trimmedLayers = assembled.trimmedLayers.map { it.name },
            layers = assembled.layers.map { it.layer.name },
        )
    }

    companion object {

        /**
         * 能力边界声明 —— **它不属于人格，属于"这个版本能做到什么"**。
         *
         * ⚠️ 原文在 `TasksScreen.SYSTEM_PROMPT` 里，是一条与人格提示词并列的硬编码常量。
         *    搬到这里不是为了好看，是因为两件事必须一起改：
         *    哪天聊天页真的能操作手机了，**这两段要同时更新** ——
         *    而分散在两处时，漏改的那一处会让助理**继续对用户说"我做不到"**
         *    （它不会报错，只是用户永远不知道功能已经有了）。
         *
         * ⚠️ 与 `PersonaAssembler.BONES_DECLARATION`（L0 骨骼）**不重复**：
         *    骨骼说的是"哪些事永远不做"（支付/解锁/诚实/隐私），
         *    这里说的是"这个版本还做不到什么"。两者是不同的东西 ——
         *    前者永不改变，后者随版本推进。
         */
        const val CAPABILITY_BOUNDARY: String =
            "## 当前版本的能力边界\n" +
                "你现在「只能对话」，还不能操作手机（不能读屏、不能点击、不能读写文件）。" +
                "如果用户要求你操作手机，请如实说明你现在做不到，并说明这还在开发中。"

        // ⚠️ 没有 Key 时那句提示词**刻意不在这里** —— 它留在
        //    `TasksScreen.NO_KEY_HINT`：那是**界面文案**（用户点一下就能去配 Key），
        //    不是提示词的一部分。两处各放一份的后果是它们会漂移。
    }
}

/** 装配诊断（见 [AssistantPromptBuilder.diagnose]）。 */
data class PromptDiagnostics(
    val estimatedTokens: Int,
    val overBudget: Boolean,
    /** 被裁掉的层（名字）。**被裁是静默的** —— 这是唯一的可观测口径。 */
    val trimmedLayers: List<String>,
    val layers: List<String>,
)
