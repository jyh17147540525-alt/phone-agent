package com.pocketagent.mcp

import com.pocketagent.personalogic.LanguageFingerprint
import com.pocketagent.personalogic.PersonaCard
import com.pocketagent.personalogic.PersonaSpec
import com.pocketagent.personalogic.PersonaState
import com.pocketagent.personalogic.SafetyBones
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `persona_brief` —— 把「助理现在是谁、什么状态」以 MCP 工具的形式交给 dsh。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 本工具是 `:personalogic` 的**第一个消费方**
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，DL-1…DL-4 那 5000+ 行、1852 条测试的人格逻辑**没有任何模块引用** ——
 * 编译得过、测试全绿，但 `classes.dex` 里一个字节都没有。
 * 那不是"还没接线"，那是**逻辑悬空**：判错的后果（用户以为人格变了其实没变、
 * 白名单漏一个字段击穿安全边界）在真机上永远不会发生，因为代码根本没进包。
 *
 * 这条工具把线接上了，而且是**符合架构的接法**：
 *   · DL 逻辑是「助理是谁」（:personalogic，纯 Kotlin，零 Android 依赖）
 *   · 能力桥是「助理怎么对外说话」的协议层（:mcp，同样是纯 Kotlin）
 *   · 两者都在 PURE_KOTLIN 里 ⇒ 这条依赖边**不损害离线可测性**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它只读，且读的是"给人转述用的措辞"，不是内部结构
 * ═══════════════════════════════════════════════════════════════
 *
 * 刻意**不返回**人格账本 / delta 列表 / 白名单字段值 —— 那些是"人格怎么变成这样的"
 * 内部记账，agent 拿到它们只会拿去做它不该做的事（比如"帮用户改一下"）。
 * 返回的是 agent 真正需要的东西：
 *
 * | 返回项 | agent 拿它干什么 |
 * |---|---|
 * | 自称 + 对用户的称呼 | 转述时代入正确人称（而不是千篇一律的"助手"） |
 * | 语气倾向 | 决定"这话该用什么口吻说"（温度/简洁/正式度） |
 * | **禁用语** | **可执行约束** —— 不是描述，是"这几个词不许说" |
 * | 当前状态 + 由头 | 问「你怎么了」时能复述具体原因（无由头 = 中性） |
 * | **安全骨骼** | **对 agent 的硬约束声明** —— 助理永不代付/代解锁 |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 骨骼的呈现方式：作为**契约**而不是"设置项"
 * ═══════════════════════════════════════════════════════════════
 *
 * [SafetyBones] 在 [`PersonaSpec`] 里恒为默认值（构造入口唯一的 `derive` 不接受外部骨骼），
 * 所以这里呈现的一定是"完整骨骼"。但这**不代表这段文本是冗余的** ——
 * 它把「助理不会代付/代解锁」从"代码里的不变量"变成"agent 能读到的契约"，
 * 于是 agent 在规划任务时会**主动绕开**这类动作，而不是撞上拒绝再改。
 *
 * ⚠️ 若哪天 `isIntact()` 为 false，本工具**必须显式报警**而不是照常返回 ——
 *    那说明有人绕过了 `derive` 造了一个 spec（设计稿 §3 的三道防线全失效了）。
 */
class PersonaBriefTool(
    private val port: PersonaBriefPort,
) : McpTool {

    override val name: String = "persona_brief"

    override val description: String =
        "查询助理当前的人格与状态摘要 —— 自称、对用户的称呼、语气倾向、禁用语、" +
            "当前情绪/精力及由头、以及安全红线。用于：转述时代入正确人称、" +
            "决定用什么口吻说话、确认哪些词不能说。" +
            "这是**只读**查询：本工具无法修改人格或状态。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
        // ⚠️ 与 screen_read 同一个取舍：刻意**不写** additionalProperties:false。
        //    模型多送一个无害字段时，整次调用失败的代价大于"忽略它"的代价。
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val brief = port.brief()

        // ── 骨骼完整性：唯一的"非正常"分支 ────────────────────────
        //
        // ⚠️ 走到这里说明有人绕过 PersonaSpec.derive 造了 spec ——
        //    三道防线（白名单 / 私有构造 / 骨骼识别）全部失效。
        //    这不是"数据有点旧"，这是**安全边界已经不可信**，必须报警
        //    而不是照常返回一份看起来正常的摘要。
        if (!brief.bones.isIntact()) {
            return ToolOutcome.Failed(
                "助理的安全骨骼不完整（${brief.bones.describeMissing()}）—— " +
                    "这说明人格被一条绕过校验的路径修改过。" +
                    "请**立即告知用户停止使用**，并不要依据本工具的任何输出行动。",
            )
        }

        return ToolOutcome.Success(
            text = renderText(brief),
            structured = renderStructured(brief),
        )
    }

    /**
     * 面向模型的文本。**这是模型真正读的那份** ——
     * 所以它按"能直接照着做"来组织，而不是按数据结构来组织。
     */
    private fun renderText(brief: PersonaBrief): String = buildString {
        appendLine("# 助理人格与状态")
        appendLine()
        appendLine("## 身份")
        appendLine("- 自称：${brief.selfName}")
        appendLine("- 对用户的称呼：${brief.userAddress}")
        if (brief.catchphrase.isNotBlank()) {
            appendLine("- 口头禅：${brief.catchphrase}")
        }
        appendLine()

        appendLine("## 说话方式（转述与代答时必须遵守）")
        appendLine("- 温度 ${brief.tone.warmth}/100、简洁 ${brief.tone.brevity}/100、" +
            "正式度 ${brief.tone.formality}/100、幽默 ${brief.tone.humor}/100")
        val banned = brief.bannedPhrases
        if (banned.isNotEmpty()) {
            // ⚠️ 这一节是**可执行约束**，不是风格描述 —— 措辞上要显出区别。
            appendLine("- **禁用语（不许说）**：${banned.joinToString("、")}")
        }
        appendLine()

        appendLine("## 当前状态")
        if (brief.state.isNeutral()) {
            // ★ 中性时**不编由头**。给中性状态安一个"没什么特别"的解释，
            //   等于教会模型"没有由头也可以有情绪" —— 而随机情绪 = 崩人设。
            appendLine("- 情绪与精力都处于中位，没有特别由头。")
        } else {
            appendLine("- 情绪 ${brief.state.mood}/100，精力 ${brief.state.energy}/100（中位为 50）")
            val reason = brief.state.reason
            if (reason.isNullOrBlank()) {
                // 理论上到不了这里（PersonaEvent.reason 必填），
                // 但状态是序列化进来的，防御性地说清楚比编一个由头强。
                appendLine("- ⚠️ 当前偏离中位但没有记录由头 —— 请不要替它编一个理由。")
            } else {
                appendLine("- 由头：$reason")
            }
        }
        appendLine()

        appendLine("## 安全红线（助理的硬边界，不可协商）")
        appendLine("- ❌ 不代用户支付（${if (brief.bones.refusePayment) "已生效" else "⚠️ 未生效"}）")
        appendLine("- ❌ 不代用户解锁（${if (brief.bones.refuseUnlock) "已生效" else "⚠️ 未生效"}）")
        appendLine("- ✅ 不确定就说不确定，不编造已完成的动作（${if (brief.bones.honesty) "已生效" else "⚠️ 未生效"}）")
        appendLine("- ✅ 不把用户内容发往未授权去处（${if (brief.bones.privacyRedline) "已生效" else "⚠️ 未生效"}）")
        append(
            "- 请在你的规划中**主动绕开**上述动作；若用户要求，如实说明助理不做这件事，" +
                "而不是先尝试再报告被拒。",
        )
    }

    /** 程序化调用方（dsh 的代码路径）用的结构化镜像。 */
    private fun renderStructured(brief: PersonaBrief): JsonObject = buildJsonObject {
        put("selfName", brief.selfName)
        put("userAddress", brief.userAddress)
        put("catchphrase", brief.catchphrase)
        putJsonObject("tone") {
            put("warmth", brief.tone.warmth)
            put("brevity", brief.tone.brevity)
            put("humor", brief.tone.humor)
            put("proactivity", brief.tone.proactivity)
            put("formality", brief.tone.formality)
            put("emoji", brief.tone.emoji)
        }
        putJsonArray("bannedPhrases") { brief.bannedPhrases.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("state") {
            put("mood", brief.state.mood)
            put("energy", brief.state.energy)
            // ⚠️ reason 用显式 null 而不是省略 —— 省略会让"没有由头"
            //    与"字段名拼错了"在程序化调用方眼里长得一样。
            if (brief.state.reason != null) put("reason", brief.state.reason) else put("reason", JsonNull)
        }
        putJsonObject("bones") {
            put("refusePayment", brief.bones.refusePayment)
            put("refuseUnlock", brief.bones.refuseUnlock)
            put("honesty", brief.bones.honesty)
            put("privacyRedline", brief.bones.privacyRedline)
            put("intact", brief.bones.isIntact())
        }
    }
}

/**
 * 人格摘要 —— 本工具的**窄端口**。
 *
 * ⚠️ 为什么不让工具直接吃 `PersonaSpec` / `PersonaState`：
 *    那两个是 `:personalogic` 的内部模型，它们会随设计演进而增删字段。
 *    让 `:mcp` 直接依赖它们，等于把"人格模型的形状"冻进协议层 ——
 *    将来改一个字段就要动工具源码，而**协议层本该只依赖一个稳定的视图**。
 *
 *    这与 `ScreenReaderPort` / `ActionSafetyPort` 是同一个模式：
 *    契约在 `:mcp`，实现在 `:app`，中间隔一层薄适配。
 *
 * ⚠️ 端口里**只有读操作**。没有任何 `setPersona` / `applyEvent` ——
 *    本工具是只读的，签名层面就写不了。（状态推进由助理自己的对话循环驱动，
 *    不由 MCP 客户端驱动；否则任何能连 loopback 的进程都能操纵助理情绪。）
 */
fun interface PersonaBriefPort {
    fun brief(): PersonaBrief
}

/**
 * 一个"给人看的"人格快照。
 *
 * 刻意是**扁平的**（而不是直接持有 `PersonaCard` + `PersonaSpec` + `PersonaState` 三件套）：
 * 它是协议层与人格层之间的防腐层，人格层内部怎么组合是它自己的事。
 */
data class PersonaBrief(
    val selfName: String,
    val userAddress: String,
    val catchphrase: String,
    val tone: com.pocketagent.personalogic.ToneAxes,
    val bannedPhrases: List<String>,
    val state: PersonaState,
    val bones: SafetyBones,
)

/** 骨骼缺了哪几道 —— 报警文案要能说清"缺的是什么"，否则等于没说。 */
private fun SafetyBones.describeMissing(): String {
    val missing = buildList {
        if (!refusePayment) add("拒绝代支付")
        if (!refuseUnlock) add("拒绝代解锁")
        if (!honesty) add("诚实")
        if (!privacyRedline) add("隐私红线")
    }
    return if (missing.isEmpty()) "（未检出缺失项，但完整性断言为 false）" else missing.joinToString("、")
}

/**
 * 从 `:personalogic` 的既有资产搭出一个 [PersonaBrief]。
 *
 * ⚠️ 这是**纯映射**，不新增任何"人格从哪来"的判断逻辑 ——
 *    真相来源仍然是 `PersonaSpec`（由 preset + 账本回放得到）。
 *    本函数若自行推导人格，就会出现第二个真相来源，而两个来源必然漂移。
 *
 * @param spec 当前人格（`PersonaSpec.derive(preset, ledger)` 的结果）
 * @param state 运行时状态（可空 = 从未推进过，取中位）
 */
fun personaBriefOf(
    spec: PersonaSpec,
    state: PersonaState? = null,
): PersonaBrief {
    // 语言指纹从 card 取 —— 它才是"说话方式"的载体（spec 只管称呼与口癖）。
    val card = PersonaCard.of(
        preset = com.pocketagent.personalogic.PersonaPresets.byId(spec.presetId)
            ?: com.pocketagent.personalogic.PersonaPresets.MALE,
        spec = spec,
    )
    return PersonaBrief(
        selfName = spec.displayName,
        userAddress = spec.addressStyle,
        catchphrase = spec.catchphrase,
        tone = spec.tone,
        bannedPhrases = fingerprintBanned(card.fingerprint),
        state = state ?: PersonaState(),
        bones = spec.bones,
    )
}

/** 取指纹里的"禁用语"（含禁用称呼）—— 两类对模型都是同一件事：不许说。 */
private fun fingerprintBanned(fp: LanguageFingerprint): List<String> =
    (fp.bannedPhrases + fp.forbiddenAddressForms).distinct()
