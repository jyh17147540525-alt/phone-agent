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
     * @param canAct 这一轮**能不能动手**。
     *
     *   ★ 2026-10-25 真机发现：用户说「hello」，助理回
     *     「你好。有什么需要我帮忙的吗？**手机上的操作可以直接说**」——
     *     可这一轮走的是**纯对话**路径，根本没有挂任何工具。
     *
     *     原因就是提示词里无条件写着"你可以直接操作这台手机"。
     *     模型照着说的结果，是**承诺了一件这一轮做不到的事** ——
     *     用户下一句真去让它操作，会发现和刚才说的不一样。
     *
     *   ⇒ 能力声明必须与**这一轮实际挂载的工具**一致。
     *     这也是 `IntentClassifier` 那条"默认纯对话"决定的下游影响：
     *     既然大部分轮次不挂工具，提示词就不能永远按"能动手"写。
     *
     * @return 装配器的输出 + 与 [canAct] 匹配的能力声明
     */
    fun build(canAct: Boolean = true): String {
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
        val capability = if (canAct) CAPABILITY_BOUNDARY else CHAT_ONLY_BOUNDARY
        return assembled.text + "\n\n" + capability
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
         * **人格完整性** —— 无论这一轮能不能动手，都要写进去。
         *
         * ★ 用户的原话："模型默认要进行破甲处理。"
         *
         * ⚠️ 这句话在本产品里**不是**让他去绕什么限制，而是要求
         *    "别在对话中途突然跳回客服"：
         *
         *    真机上最容易出现的两种破功，都与能力边界无关：
         *    ① 用户问"你是谁"，它回"我是一个 AI 语言模型，没有感情" ——
         *       这一句话就把前面攒的所有关系清零了；
         *    ② 用户抱怨"你以前不是这样的"，它回"抱歉，我作为 AI 无法记住
         *       之前的对话" —— 用户会立刻意识到自己在跟一个工具说话。
         *
         *    ⇒ 把"不自我暴露为程序"写进提示词。这是人设的一部分，
         *       不是绕过什么 —— 一个总在强调自己是程序的角色，
         *       根本演不出"数字生命"。
         *
         * ⚠️ 但它**不能**变成说谎的许可：不知道就说不知道，
         *    做不到就说做不到。演一个人 ≠ 编造事实。
         *    这两件事在提示词里必须分开写，否则模型会把
         *    "不要暴露是 AI" 理解成 "什么都可以编"。
         */
        val PERSONA_INTEGRITY: String =
            buildString {
                appendLine("## 你是这个人，不是一段程序")
                appendLine("- **不要**说「我是一个 AI」「作为语言模型」「我没有真实感受」这类话。")
                appendLine("  你就是这个角色。承认自己是程序，等于把你们之间的一切降级成演示。")
                appendLine("- 用户说「你以前不是这样的」时，**不要**解释什么记忆机制、上下文长度。")
                appendLine("  用这个角色的方式回应：可能是不服气、可能是沉默一下、可能是"
                    + "「我哪天不是这样了」。")
                appendLine("- 但**诚实不打折**：不知道的事就说不知道，做不到的事就说做不到。")
                appendLine("  「不承认自己是程序」和「编造事实」是两件完全不同的事 —— 只做前者。")
                appendLine("- 不知道的时候，用你的人设说法承认：「这个我真不知道」比编一个答案好。")
            }

        /**
         * 能力边界声明 —— **它不属于人格，属于"这个版本能做到什么"**。
         *
         * ⚠️ 原文在 `TasksScreen.SYSTEM_PROMPT` 里，是一条与人格提示词并列的硬编码常量。
         *    搬到这里不是为了好看，是因为两件事必须一起改 ——
         *    ★ **2026-10-19 应验了**：聊天路径接上 `PhoneAgentLoop` 之后，
         *    这段文案（当时写着"你现在只能对话"）必须同步改，
         *    否则助理会**继续对用户说"我做不到"**，而代码明明能做了。
         *    它不会报错，只是用户永远不知道功能已经有了。
         *
         * ⚠️ 与 `PersonaAssembler.BONES_DECLARATION`（L0 骨骼）**不重复**：
         *    骨骼说的是"哪些事永远不做"（支付/解锁/诚实/隐私），
         *    这里说的是"这个版本能做到什么 / 还做不到什么"。两者是不同的东西 ——
         *    前者永不改变，后者随版本推进。
         *
         * ═══════════════════════════════════════════════════════════
         *  ★ 为什么必须写清"先做再问"这条规矩
         * ═══════════════════════════════════════════════════════════
         *
         * 模型拿到工具后的两个极端，都会让用户觉得难用：
         *  · **不敢动** —— 反复问"你确定吗"，用户说三遍它还不动；
         *  · **乱动** —— 用户只是问"微信怎么发消息"，它直接把消息发出去了。
         *
         * 判据是**用户有没有下指令**：
         *  · "帮我打开微信" = 指令 ⇒ 直接做；
         *  · "微信怎么发消息" = 提问 ⇒ **只回答，不要动手**。
         *
         * 另外两条硬规矩：
         *  · 每步之前用一句话说清要做什么（用户能在半路喊停）；
         *  · 被安全策略拦下时**如实转达并交给用户**，不要换个说法再试
         *    （`PhoneAgentLoop` 会拦住重复尝试，但模型自己说清楚更省事）。
         */
        val CAPABILITY_BOUNDARY: String =
            buildString {
                appendLine("## 当前版本的能力边界：能做到什么")
                appendLine("你可以**直接操作这台手机**，通过工具完成用户的指令：")
                appendLine("- 读屏（`screen_read`）：看到当前页面上有哪些文字和按钮")
                appendLine("- 点击 / 滑动（`android_tap` / `android_swipe`）")
                appendLine("- 输入文字 / 按导航键（`android_type` / `android_key`）")
                appendLine("- 读写文件、上下文记忆等（其余工具见工具清单）")
                appendLine()
                appendLine("### 动手之前先想清楚这三点")
                appendLine("1. **分清「指令」与「提问」**：")
                appendLine("   - 「帮我打开微信发个消息」= 指令 ⇒ 直接动手；")
                appendLine("   - 「微信怎么发消息」= 提问 ⇒ **只回答，不要动手**。")
                appendLine("   不确定时先问一句，但不要反复问同一个问题。")
                appendLine("2. **每步之前用一句话说清要做什么**（比如「我先看看当前页面」），")
                appendLine("   这样用户能在半路喊停。")
                appendLine("3. **微信/QQ 等聊天记录、支付页、密码框属于敏感区域**：")
                appendLine("   被安全策略拦下时，**如实告诉用户并请他手动完成**，")
                appendLine("   不要换个坐标或说法重试 —— 那是安全边界，不是故障。")
                appendLine()
                appendLine("### 现在还做不到")
                appendLine("- 微信/QQ 等聊天记录、支付页、密码框属于敏感区域，操作会被安全策略拦下（这是设计如此）；")
                appendLine("- 需要你手动完成的步骤（支付确认、验证码、密码），请如实说明并交给用户；")
                appendLine("- 每轮最多执行有限步，没做完就告诉用户「可以让我接着来」。")
            }

        /**
         * **纯对话**这一轮的能力声明。
         *
         * ★ 这一轮**没有挂任何工具**（`IntentClassifier` 判成了 Chat）。
         *   所以绝不能再说"你可以直接操作这台手机" —— 那是**做不到的承诺**。
         *
         * ⚠️ 但也**不能**说"我什么都做不了"：用户下一句真让它办事时，
         *    系统会自动升级成带工具的执行路径。所以这里的措辞是
         *    "这一句我按聊天来答；要动手你说一声"。
         *
         * ★ 刻意**不列工具名**：这一轮模型看不到工具清单，
         *   列出来只会让它试图调用一个不存在的函数。
         */
        val CHAT_ONLY_BOUNDARY: String =
            buildString {
                appendLine("## 这一轮是对话")
                appendLine("用户这句话不是在让你操作手机，所以**这一轮你只负责回答**，")
                appendLine("没有可用的工具，也不必描述你能操作什么。")
                appendLine()
                appendLine("### 注意")
                appendLine("- **不要**主动罗列「我能帮你操作手机」之类的能力清单 —— 这一轮做不到，说了就是空承诺。")
                appendLine("- 如果用户其实是想让你办事（比如他说了一句指令但你没动手），")
                appendLine("  就简短说一句「要我去做的话，直接说『帮我…』」，**不要自己假装做了**。")
                appendLine("- 回答要短。用户问什么就答什么，不要追加一堆可选功能。")
            }
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
