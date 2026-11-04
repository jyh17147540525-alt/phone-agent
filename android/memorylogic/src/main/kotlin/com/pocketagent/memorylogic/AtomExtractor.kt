package com.pocketagent.memorylogic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一条被丢弃的提取结果 + 原因。**丢弃必须可观测**，否则就是「安静地少做一件事」。 */
data class DroppedAtom(val reason: AtomDropReason, val snippet: String)

/** 丢弃原因。每一类都对应一种**真实的**模型输出缺陷，不是防御性枚举。 */
enum class AtomDropReason(val label: String) {
    /** 模型给了一个四类之外的类别名 */
    UNKNOWN_KIND("类别无法识别"),

    /** 文本为空 / 只有空白 */
    EMPTY_TEXT("内容为空"),

    /** 置信度不在 0..1（模型很爱写 90 当 90%） */
    BAD_CONFIDENCE("置信度不在 0..1"),

    /** 溯源指向了本轮之外的 Turn —— 通常是模型编的 Turn id */
    UNKNOWN_SOURCE_TURN("溯源指向了不存在的 Turn"),

    /** 整段响应根本不是可解析的结构 */
    MALFORMED_RESPONSE("整段响应无法解析"),
}

/**
 * 一次 L1 提取的结果。
 *
 * ★ 三个字段都**必须**有：只报"提取到几条"，不报"丢了几条、为什么丢"，
 * 等于把 L1 最典型的那类故障（该记住的没记住）做成了不可观测的。
 */
data class ExtractionReport(
    /** 本批次的产出（新增的 + 被更新过的），可直接并进调用方的原子表 */
    val atoms: List<Atom>,

    /** 被丢弃的条目及原因 */
    val dropped: List<DroppedAtom>,

    /** 与已有原子（或本批更早的同一原子）合并的条数 */
    val mergedCount: Int,
)

/**
 * L1 原子记忆层 —— 从 L0 原始对话里提取四类原子。
 *
 * ═══════════════════════════════════════════════════════════════
 *  职责边界：本类只做"提示词 + 校验 + 去重"，不做网络
 * ═══════════════════════════════════════════════════════════════
 *
 * 真正发请求的是注入的 [MemoryLlmPort]（不变量 2）。所以本类全程是**纯逻辑**：
 * 提示词怎么拼、模型返回的东西哪些能要哪些不能要、重复怎么算 ——
 * 这些都能在没有真机、没有网络的情况下逐条断言。
 *
 * ⚠️ **校验必须严，且严的方向是"丢"**：
 * 一条编造出来的记忆（编的 Turn id、编的偏好）会一路流到 L3 画像、
 * 再流进 `:personalogic` 的证据源，让助理"记住"一件从没发生过的事。
 * 那种错误的表象是"ta 懂我"变成"ta 认错人了"，且无从排查。
 */
class AtomExtractor(private val llm: MemoryLlmPort) {

    /**
     * 从 [turns] 里提取原子。
     *
     * @param turns 本批要提取的原始轮次。为空时**不调用模型**（返回空报告）。
     * @param known 已有的原子 —— 用来把"重复提取"识别成合并而不是新增。
     *   ⚠️ 刻意**不给默认值**：调用方必须显式说明"我现在手上有什么"，
     *   否则一次忘记传就会把重复提取变成新增。
     * @param now 提取时刻。由调用方传入，内核不读时钟（同 `:personalogic` 的取舍：
     *   离线可钉住、排队的提取不会因为处理时刻不同而乱序）。
     */
    fun extract(turns: List<Turn>, known: List<Atom>, now: Long): ExtractionReport {
        if (turns.isEmpty()) return ExtractionReport(emptyList(), emptyList(), 0)

        val raw = llm.complete(buildPrompt(turns), MemoryLlmPort.Purpose.ATOM_EXTRACTION)
        val entries = parseEntries(raw)
            ?: return ExtractionReport(
                atoms = emptyList(),
                dropped = listOf(DroppedAtom(AtomDropReason.MALFORMED_RESPONSE, snippet(raw))),
                mergedCount = 0,
            )

        val knownIds = turns.map { it.id }.toSet()
        val acc = LinkedHashMap<String, Atom>()
        for (atom in known) acc[atom.id] = atom

        val dropped = mutableListOf<DroppedAtom>()
        val produced = LinkedHashMap<String, Atom>()
        var merged = 0

        for (entry in entries) {
            val kind = AtomKind.byName(entry.kind)
            if (kind == null) {
                dropped += DroppedAtom(AtomDropReason.UNKNOWN_KIND, snippet("${entry.kind}|${entry.text}"))
                continue
            }
            val text = entry.text.trim()
            if (text.isEmpty()) {
                dropped += DroppedAtom(AtomDropReason.EMPTY_TEXT, snippet(entry.kind))
                continue
            }
            if (entry.confidence !in 0.0..1.0) {
                dropped += DroppedAtom(AtomDropReason.BAD_CONFIDENCE, snippet(text))
                continue
            }
            // 溯源只能落在本批真实存在的 Turn 上。指向别处的 id 一律剔除；
            // 一个都不剩时整条丢弃 —— 「记不住出处」比「记错出处」安全。
            val sources = entry.sourceTurnIds.filter { it in knownIds }.distinct()
            if (sources.isEmpty()) {
                dropped += DroppedAtom(AtomDropReason.UNKNOWN_SOURCE_TURN, snippet(text))
                continue
            }

            val candidate = Atom(
                id = Atom.idOf(kind, text),
                kind = kind,
                text = text,
                sourceTurnIds = sources,
                confidence = entry.confidence,
                at = now,
            )
            val previous = acc[candidate.id]
            val settled = if (previous == null) candidate else Atom.merge(previous, candidate).also { merged++ }
            acc[candidate.id] = settled
            produced[candidate.id] = settled
        }

        return ExtractionReport(
            atoms = produced.values.toList(),
            dropped = dropped,
            mergedCount = merged,
        )
    }

    /**
     * 组装提示词。
     *
     * ⚠️ 提示词里**必须**把四类原子的定义与"必须给出溯源 Turn id"写死：
     * 这两件事决定了后续所有校验能不能生效。让模型自己发明类别，
     * 或者让它省略出处，得到的是一堆无法回溯的字符串。
     */
    fun buildPrompt(turns: List<Turn>): String = buildString {
        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-11-03：**去 AI 味**
        // ══════════════════════════════════════════════════════════
        //
        // ⚠️ 上一版提示词是这么写的：
        //      "你在为一个手机助理维护长期记忆…"
        //      "偏好（用户喜欢/不喜欢什么）"
        //      "约束（用户定下的规则）"
        //    ⇒ 它在**明确要求用第三人称公文腔**。
        //      模型只是照做，所以抓出来的记忆必然是：
        //         ❌ "用户喜欢与角色亲密接触，并常主动发起。"
        //
        // ⚠️ 而记忆是要被**原样塞回提示词**的。这种句子在上下文里待久了，
        //    角色就会开始用同样的腔调说话 —— 聊得越久越像 AI。
        //    **清洗记忆治不了这个，得从抽取这一步就不生成它。**
        //
        // ★ 2026-11-04：原来这里还有一段「❌ 不许这样写：」+ 三句公文腔原句。
        //   已删：把要禁的句子写进提示词，等于把这些 token 又注入一遍，
        //   而且是在否定框定里注入（见 `docs/工程/提示词负例改造-依据.md`
        //   引的 arXiv:2601.21433：1–4B 模型在否定框定下支持率从 24% 抬到 100%）。
        //   改成 ✅ 正例 + 两条形状约束（主语只有我/他；一句只说一件事）。
        append("你要替「她」写下这段对话里值得记住的事——写成**她自己会怎么说**。\n")
        append("只输出 JSON，不要任何解释。格式：\n")
        append("""{"atoms":[{"kind":"fact|preference|constraint|stage_conclusion","text":"...","sourceTurnIds":["..."],"confidence":0.0}]}""")
        append("\n\n四类：\n")
        append("- fact：真发生过的事\n")
        append("- preference：他喜欢什么、她喜欢什么\n")
        append("- constraint：说好的规矩、界线\n")
        append("- stage_conclusion：这一段的整体印象\n")
        append("\n## text 怎么写（最重要）\n")
        append("用**第一人称**：她自己叫「我」，对方叫「他」。\n")
        append("写得像她在心里记着，不像在写档案。\n")
        append("可以带语气——嫌、笑、别扭、嘴硬都行。\n")
        append("\n✅ 好：\n")
        append("  \"他手被烫伤过，油炸的那些我一口都不让他碰。\"\n")
        append("  \"他爱点外卖。我嫌不健康，最后还是跟着吃。\"\n")
        append("  \"说好十一点睡。他做不到，我知道，但我还是要说。\"\n")
        append("\n写法上只有两条硬要求：主语只有「我」和「他」；一句话只说一件事。\n")
        append("没有主语的、把两边合成一个主语的、一句里塞两件事的，都会退化成档案腔。\n")
        append("\n## 禁用词\n")
        append("用户、角色、助理、助手、对方、双方、该、上述、本条、")
        append("进行、予以、予以、以及、并且、因此、方面、情况、相关、")
        append("必须、要求、规定、约定为、予以执行\n")
        append("（这些词一出现，那段记忆就废了——它会在下一步污染角色的说话方式。）\n")
        append("\n## 其余规则\n")
        append("1. kind 只允许上面四个值之一。\n")
        append("2. text 一句话说清，不要复制原文长段。**用半个句号都嫌多。**\n")
        append("3. sourceTurnIds 必须来自下面列出的 [turn-id]，「不得编造」；不确定就省略该条。\n")
        append("4. confidence 是 0 到 1 之间的小数（不要写百分比）。\n\n")
        append("对话：\n")
        for (turn in turns) {
            append("[").append(turn.id).append("] ").append(turn.role.name.lowercase()).append(": ")
            append(turn.text).append('\n')
        }
    }

    /**
     * 解析模型返回。
     *
     * 两件事必须做，因为真实模型天天这么干：
     * - **剥代码围栏**（```json … ```）
     * - **容忍裸数组**（返回 `[…]` 而不是 `{"atoms":[…]}`）
     *
     * 解析不出来返回 `null`，由调用方记一条 [AtomDropReason.MALFORMED_RESPONSE] ——
     * 不能"看不懂就当没这回事"。
     */
    private fun parseEntries(raw: String): List<AtomEntry>? {
        val json = stripCodeFence(raw)
        if (json.isEmpty()) return null
        return try {
            if (json.startsWith("[")) {
                JSON.decodeFromString(ListSerializer(AtomEntry.serializer()), json)
            } else {
                JSON.decodeFromString(AtomEnvelope.serializer(), json).atoms
            }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            // JsonDecodingException 是 SerializationException 的子类，这条是给
            // 将来可能出现的其它参数错误兜底；两者都不会被当成"无异常"放过。
            null
        }
    }
}

/** 剥掉 ```` ``` ```` 围栏，留下中间那段。没有围栏时原样返回。 */
private fun stripCodeFence(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.contains(FENCE)) return trimmed
    val open = trimmed.indexOf(FENCE)
    val bodyStart = trimmed.indexOf('\n', open)
    if (bodyStart < 0) return trimmed
    val close = trimmed.lastIndexOf(FENCE)
    if (close <= bodyStart) return trimmed
    return trimmed.substring(bodyStart + 1, close).trim()
}

private const val FENCE = "```"

/** 展示用的片段：控制字符转可见转义、超长截断。 */
internal fun snippet(raw: String, maxLength: Int = MAX_SNIPPET_LENGTH): String {
    val visible = buildString {
        for (ch in raw) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.isISOControl() -> append("\\u").append(ch.code.toString(16).uppercase().padStart(4, '0'))
                else -> append(ch)
            }
        }
    }
    return if (visible.length <= maxLength) visible else visible.take(maxLength) + "…"
}

internal const val MAX_SNIPPET_LENGTH = 80

private val JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** 模型返回的**外壳**。允许裸数组是刻意的（见 `parseEntries`）。 */
@Serializable
private data class AtomEnvelope(val atoms: List<AtomEntry> = emptyList())

/**
 * 模型返回的单条原子。
 *
 * ⚠️ 所有字段都有默认值，是为了让"缺字段"能走到**校验**那一层
 * （缺 `kind` → UNKNOWN_KIND，缺 `text` → EMPTY_TEXT），
 * 而不是在反序列化时抛异常、整批丢掉。
 * 默认值在这里不是"兜底"，是"把判断权交给显式校验"。
 */
@Serializable
private data class AtomEntry(
    val kind: String = "",
    val text: String = "",
    val sourceTurnIds: List<String> = emptyList(),
    val confidence: Double = -1.0,
)