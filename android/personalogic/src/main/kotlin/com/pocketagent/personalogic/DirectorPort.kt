package com.pocketagent.personalogic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **后台 AI 的模型出口** —— 与 `:memorylogic/MemoryLlmPort` 同一纪律。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是一个端口，而不是自己发请求
 * ═══════════════════════════════════════════════════════════════
 *
 * 三条理由（与记忆层完全相同）：
 *
 * 1. **计量**：一切模型调用必须过网关（路由 → 熔断 → 解密 → 转发 → 计量）。
 *    后台任务偷偷直连一次，这次消耗不在任何预算里 ——
 *    用户看到的"本月花了多少"就是假的。
 * 2. **可测**：本模块要能进离线验证器，就不能有 `OkHttpClient`。
 * 3. **可换档位**：[Purpose] 让上层按用途选模型与思考强度。
 *
 * ⚠️ 与 `MemoryLlmPort` 分开而不是合并：
 *    - 那个在 `:memorylogic`，语义是"记忆层要 LLM"
 *    - 这个在 `:personalogic`，语义是"人格/生理/世界书要 LLM"
 *    合并会让两个模块互相依赖，而它们没有任何共同的下游。
 */
fun interface BackgroundLlmPort {

    /**
     * 一次补全。
     *
     * @param prompt 由调用方组装（提示词是**纯逻辑**，可离线断言）
     * @param purpose 用途 —— 决定用哪个模型、思考多深
     * @return 模型原始返回。⚠️ 可能带代码围栏、可能不合法，
     *   解析与校验的责任在调用方（见 [DirectorParser]）。
     */
    fun complete(prompt: String, purpose: BackgroundPurpose): String

    /** 后台任务有哪几类。每类可以配不同的模型。 */
    enum class BackgroundPurpose(val label: String) {
        /** 场记：识别事件 + 整理世界书事实 */
        DIRECTOR("场记"),

        /** 生理导演：从话里读出生理线索 */
        PHYSIO("生理导演"),
    }
}

/**
 * **场记的提示词** —— 严格 JSON，闭集枚举。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么提示词要单独一个对象，而不是拼在调用处
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是**纯逻辑**：同样的输入必须给出同样的字符串。
 * 拼在调用处就没法离线断言"枚举值写全了吗""该给的上下文给了吗"。
 *
 * ⚠️ 而这类提示词最容易出的错是**枚举漏项** ——
 *    模型返回一个没见过的类型，解析器丢弃，于是那一类事件**永远不触发**，
 *    而没有任何报错。
 */
object DirectorPrompt {

    /**
     * @param recentTurns 最近的几轮对话（升序，末尾最新）。**只给最近几轮** ——
     *   给太多会让模型在旧信息上做判断。
     * @param armor 破甲前缀（见 `BackgroundArmor`）
     * @param knownFactIds 世界书里已有的 id，让它别重复提议
     */
    fun build(
        recentTurns: List<Pair<String, String>>,
        armor: String,
        knownFactIds: Set<String> = emptySet(),
    ): String = buildString {
        if (armor.isNotBlank()) {
            appendLine(armor)
            appendLine()
        }

        appendLine("## 任务")
        appendLine("读下面几轮对话，回答两个问题：")
        appendLine("1. 有没有**值得记住**的事（用户说的、已经发生的事实）？")
        appendLine("2. 有没有**事件**（改变了关系、情绪、或身体状况的事）？")
        appendLine()
        appendLine("大多数轮次两个答案都是空的 —— **这是正常的**，不要为了凑而编。")
        appendLine()

        appendLine("## 事件类型（只能取这些值）")
        for (k in DirectorEventKind.entries) {
            appendLine("- `${k.name}` = ${k.label}")
        }
        appendLine()

        appendLine("## 输出格式（严格 JSON，不要代码块）")
        appendLine(
            """
            {
              "events": [{"kind": "枚举值", "reason": "可复述的具体事，如「用户说他发烧了」"}],
              "smallMoments": ["环境或氛围的小事，最多两条"],
              "proposedFacts": [{"id": "kebab-case-id", "title": "短标题",
                                 "keys": ["触发词1", "触发词2"], "content": "一句话事实"}]
            }
            """.trimIndent(),
        )
        appendLine()
        appendLine("⚠️ `reason` 必须是**具体的事**，不能写「检测到压力」这种系统语言。")
        appendLine("⚠️ `keys` 不能为空 —— 没有触发词的事实永远检索不到。")
        if (knownFactIds.isNotEmpty()) {
            appendLine("⚠️ 这些 id 已经有了，不要重复：${knownFactIds.joinToString("、")}")
        }
        appendLine()

        appendLine("## 最近的对话")
        if (recentTurns.isEmpty()) {
            appendLine("（没有内容 —— 输出空结果）")
        } else {
            for ((user, assistant) in recentTurns) {
                appendLine("用户：$user")
                appendLine("你：$assistant")
                appendLine()
            }
        }
    }.trimEnd()
}

/**
 * **解析场记的返回**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 容错策略：能解析多少算多少，但**不猜**
 * ═══════════════════════════════════════════════════════════════
 *
 * 模型输出可能：带代码围栏、字段名大小写不一致、枚举值翻译成了中文、
 * 多给一个没见过的类型。
 *
 * ⇒ 逐条容错：
 * · 代码围栏 → 剥掉
 * · 枚举值 → 按名字匹配，**不认识就丢弃**（并计数，让上层知道丢了多少）
 * · 缺字段 → 当空
 * · 整段不是 JSON → 返回 null（上层静默跳过，不打扰用户）
 *
 * ⚠️ **不猜**：不认识的枚举**不会**被硬塞进最接近的那一类。
 *    硬塞会让"冷漠"和"生气"混成一体，而那种错无法察觉。
 */
object DirectorParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 解析结果。 */
    data class Parsed(
        val output: DirectorOutput,
        /** 被丢掉的条目数（不认识的枚举、缺字段等）—— 用来发现"模型在乱说" */
        val dropped: Int,
    )

    /**
     * @return null = 整段没法解析（不是 JSON）。上层应当**静默跳过**。
     */
    fun parse(raw: String): Parsed? {
        val text = stripCodeFence(raw).trim()
        if (text.isEmpty()) return null

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        var dropped = 0

        // ── 事件 ────────────────────────────────────────────
        val events = mutableListOf<DirectorEvent>()
        (root["events"] as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: run { dropped++; return@forEach }
            val kindName = (obj["kind"] as? JsonPrimitive)?.contentOrNull()
            val reason = obj["reason"]?.let { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
            // ⚠️ 不认识的枚举直接丢，不猜
            val kind = DirectorEventKind.entries.firstOrNull { it.name == kindName }
            if (kind == null || reason.isNullOrBlank()) {
                dropped++
            } else {
                events += DirectorEvent(kind, reason)
            }
        }

        // ── 小事 ────────────────────────────────────────────
        val moments = (root["smallMoments"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // ── 事实 ────────────────────────────────────────────
        val facts = mutableListOf<ProposedFact>()
        (root["proposedFacts"] as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: run { dropped++; return@forEach }
            val id = obj["id"]?.let { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
            val title = obj["title"]?.let { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
            val content = obj["content"]?.let { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
            val keys = (obj["keys"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            // ⚠️ 没有触发词的事实永远检索不到 —— 直接丢
            if (id.isNullOrBlank() || content.isNullOrBlank() || keys.isEmpty()) {
                dropped++
            } else {
                facts += ProposedFact(
                    id = id,
                    title = title?.ifBlank { id } ?: id,
                    keys = keys,
                    content = content,
                )
            }
        }

        return Parsed(
            output = DirectorOutput(
                events = events,
                smallMoments = moments,
                proposedFacts = facts,
            ),
            dropped = dropped,
        )
    }

    /** 剥掉 ```json ... ``` 围栏。模型很爱加，而加上就解析失败。 */
    private fun stripCodeFence(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        val firstNewline = t.indexOf('\n')
        if (firstNewline < 0) return t
        val close = t.lastIndexOf("```")
        if (close <= firstNewline) return t
        return t.substring(firstNewline + 1, close).trim()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()
}

/**
 * **生理导演的提示词** —— 与场记**完全不同**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须分开
 * ═══════════════════════════════════════════════════════════════
 *
 * 场记在看"有没有事实、有没有事件"（世界书 / 关系）。
 * 生理导演在看**身体**：累不累、冷不冷、疼不疼、睡没睡。
 *
 * ⚠️ 用同一个提示词会让模型两边都想答，输出变成一锅粥 ——
 *    而那一锅粥里的生理线索往往被世界书事实挤掉。
 *
 * ⚠️ 它**不产出世界书事实**，只产出**生理线索**。
 */
object PhysioPrompt {

    /**
     * 生理线索的类型。**闭集** —— 与场记的事件类型不重叠。
     */
    enum class Clue(val label: String) {
        /** 疲劳 / 困 */
        TIRED("疲累"),

        /** 不适 / 疼痛 */
        UNWELL("身体不适"),

        /** 冷 / 热 */
        TEMPERATURE("冷热"),

        /** 饿 / 渴 */
        HUNGER("饥渴"),

        /** 睡眠相关 */
        SLEEP("睡眠"),

        /** 情绪的身体表现（心慌、发抖） */
        BODY_STRESS("紧张的身体反应"),

        /** 生理期相关 */
        CYCLE("生理期"),
    }

    fun build(recentTurns: List<Pair<String, String>>, armor: String): String = buildString {
        if (armor.isNotBlank()) {
            appendLine(armor)
            appendLine()
        }
        appendLine("## 任务")
        appendLine("读下面的对话，只回答一个问题：")
        appendLine("**用户或角色有没有提到跟身体有关的事？**")
        appendLine()
        appendLine("⚠️ 大多数轮次答案是「没有」—— 这是正常的，不要为了凑而编。")
        appendLine("⚠️ 比喻不算：「冷死了」如果是在夸张，不算体温线索；")
        appendLine("    但如果上下文是冬天、在室外，那就是。**看上下文**。")
        appendLine()

        appendLine("## 线索类型（只能取这些值）")
        for (c in Clue.entries) {
            appendLine("- `${c.name}` = ${c.label}")
        }
        appendLine()

        appendLine("## 输出格式（严格 JSON，不要代码块）")
        appendLine(
            """
            {"clues": [{"kind": "枚举值", "evidence": "原话里支撑这一判断的片段"}]}
            """.trimIndent(),
        )
        appendLine()
        appendLine("⚠️ `evidence` 必须是**用户或角色真的说过的话**，不能是你总结的。")
        appendLine()

        appendLine("## 最近的对话")
        if (recentTurns.isEmpty()) {
            appendLine("（没有内容 —— 输出 {\"clues\": []}）")
        } else {
            for ((user, assistant) in recentTurns) {
                appendLine("用户：$user")
                appendLine("你：$assistant")
                appendLine()
            }
        }
    }.trimEnd()

    /** 解析结果。 */
    data class Parsed(val clues: List<Pair<Clue, String>>, val dropped: Int)

    /** @return null = 整段不是 JSON */
    fun parse(raw: String): Parsed? {
        val text = raw.trim().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (text.isEmpty()) return null
        val root = runCatching {
            Json.parseToJsonElement(text).jsonObject
        }.getOrNull() ?: return null

        var dropped = 0
        val out = mutableListOf<Pair<Clue, String>>()
        (root["clues"] as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: run { dropped++; return@forEach }
            val name = (obj["kind"] as? JsonPrimitive)?.let {
                runCatching { it.content }.getOrNull()
            }
            val ev = (obj["evidence"] as? JsonPrimitive)?.let {
                runCatching { it.content }.getOrNull()
            }?.trim()
            val clue = Clue.entries.firstOrNull { it.name == name }
            if (clue == null || ev.isNullOrBlank()) dropped++ else out += clue to ev
        }
        return Parsed(out, dropped)
    }

    private val Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
}
