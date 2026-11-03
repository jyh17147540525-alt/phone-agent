package com.pocketagent.personalogic

/**
 * **记忆使者** —— 改了人格设定之后，把**已经说过的话**改成一致的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户要求
 * ═══════════════════════════════════════════════════════════════
 *
 * > "每次用户修改完角色设定，都可以用这个功能去修改角色的上下文记忆，
 * >  把上下文里的角色设定也一起改成用户最新设置的设定"
 *
 * 问题很真实：改了她的名字/关系/背景之后，**旧的对话里还是旧设定**。
 * 模型读到那些旧台词，会照着旧的自相矛盾 ——
 * 而用户以为是"改了没生效"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条硬边界（这一段比功能本身重要）
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 只改"角色说过的话"，不改用户的原话。**
 *    用户说的话是**记录**，改它等于篡改他输入过的东西。
 *    （他可能说过"你叫小雨" —— 那句留着；要改的是**她后来怎么答的**。）
 *
 * **② 改之前必须留备份。**
 *    这是不可逆操作里最容易后悔的一种。
 *
 * **③ 只改"真的含旧设定"的那些轮。**
 *    全量重写既贵又会把本来没问题的句子改走味 ——
 *    模型每改一次都是一次重新创作。
 */
object PersonaSync {

    /**
     * 这一句**值得改写吗**。
     *
     * ⚠️ 判据是"含旧的设定痕迹"，不是"含角色名"。
     *    名字可能是用户说的（不该改），也可能只是人称 ——
     *    所以这里看的是**旧设定独有的词**（旧名字、旧关系、旧称谓）。
     *
     * @param text 待判断的文本
     * @param oldMarkers 旧设定里"独有"的词
     */
    fun needsRewrite(text: String, oldMarkers: List<String>): Boolean {
        if (text.isBlank() || oldMarkers.isEmpty()) return false
        val hit = oldMarkers.any { it.isNotBlank() && text.contains(it) }
        return hit
    }

    /**
     * 改写用的提示词。
     *
     * ⚠️ 三条要求写死在提示词里：
     * · 只改设定相关的部分，**其他一个字别动**
     * · 保留原句的语气与长度
     * · 想不出怎么改就**原样返回**
     */
    fun buildPrompt(
        oldSummary: String,
        newSummary: String,
        text: String,
    ): String = buildString {
        appendLine("## 任务")
        appendLine("下面这段是角色说过的话。当时的设定已经改了，请把它改成新设定。")
        appendLine()
        appendLine("## 旧设定")
        appendLine(oldSummary.ifBlank { "（未提供）" })
        appendLine()
        appendLine("## 新设定")
        appendLine(newSummary.ifBlank { "（未提供）" })
        appendLine()
        appendLine("## 要改的话")
        appendLine(text)
        appendLine()
        appendLine("## 要求")
        appendLine("- **只改与新设定冲突的部分**，其余一个字都不要动。")
        appendLine("- 保持原来的语气、长度、口语感。不要把它写得更好。")
        appendLine("- **不要**加任何解释、前缀、引号或标记。")
        appendLine("- 如果这句话本来就不冲突，**原样返回**。")
        appendLine("- 直接输出改写后的那句话。")
    }.trimEnd()

    /** 改写结果。 */
    sealed interface Verdict {
        /** 可以用。 */
        data class Accept(val text: String) : Verdict

        /** 不能用，保留原文。 */
        data class Keep(val reason: String) : Verdict
    }

    /**
     * **这道闸门防的是"改坏了"**。
     *
     * 模型改写时最常见的三种翻车：
     * 1. 空返回 / 只回了"好的"
     * 2. 把一句话膨胀成一段（它以为在"丰富细节"）
     * 3. 加了前言（"这是改写后的："）或引号
     *
     * ⇒ 任何一种都**保留原文**，而不是把坏结果写进历史。
     *    "没改成"比"改坏了"好得多 —— 后者不可逆。
     */
    fun accept(original: String, rewritten: String): Verdict {
        val t = rewritten.trim()

        if (t.isBlank()) return Verdict.Keep("模型返回了空内容")

        // 长度：允许 0.5x ~ 2x。超出范围说明它不是"改写"，是"重写"。
        val ratio = t.length.toDouble() / original.length.coerceAtLeast(1)
        if (ratio > 2.0) return Verdict.Keep("改写后长度膨胀到 ${"%.1f".format(ratio)} 倍")
        if (ratio < 0.4) return Verdict.Keep("改写后只剩 ${"%.1f".format(ratio)} 倍")

        // 前言 / 包裹：这类痕迹说明模型在"解释"而不是"改写"
        val prefixes = listOf("这是改写", "改写后", "修改后", "以下是", "好的，", "当然，")
        if (prefixes.any { t.startsWith(it) }) return Verdict.Keep("带了前言")

        // 被引号整体包起来 —— 那会把引号也写进历史
        if ((t.startsWith("\"") && t.endsWith("\"")) ||
            (t.startsWith("「") && t.endsWith("」"))
        ) {
            return Verdict.Keep("整体被引号包住")
        }

        return Verdict.Accept(t)
    }

    /**
     * 从人格设定里抽出**旧设定独有的词** —— 用来判断哪几句需要改。
     *
     * ⚠️ 只取"专有"的部分：名字、称谓、关系。
     *    取太多（比如"你""我"）会让每一句都被判成"需要改"，
     *    于是全量重写 —— 那既不省钱也会改走味。
     */
    fun markersOf(
        selfName: String,
        addressStyle: String,
        relationship: String,
    ): List<String> = buildList {
        if (selfName.isNotBlank() && selfName.length <= 12) add(selfName)
        if (addressStyle.isNotBlank() && addressStyle.length <= 12) add(addressStyle)
        if (relationship.isNotBlank() && relationship.length <= 12) add(relationship)
        // 单字不算标记 —— "你""我"这种到处都是
    }.distinct().filter { it.length >= 2 }
}

/** 一次记忆使者运行的结果。**要能对用户说清楚**。 */
data class PersonaSyncReport(
    /** 真的改了几条 */
    val changed: Int,
    /** 保留原文的条数 */
    val kept: Int,
    /** 非空表示**没跑成**，这是一句给用户看的话 */
    val problem: String = "",
    /** 这次一共扫了几条（含没改的）—— 用来区分"没改"和"没扫到" */
    val scanned: Int = 0,
) {
    val ok: Boolean get() = problem.isEmpty()

    /**
     * 给用户看的一句话。
     *
     * ⚠️ 2026-11-03：用户报"点开始不到一秒就显示清洗完成" ——
     *    根因是**我在提前返回时也显示得像"跑完了"**。
     *    现在三种结果在文字上**完全分开**：
     *
     *   没跑成     → "没改：<原因>"
     *   跑了但没改 → "扫了 N 条，没有需要改的地方"
     *   真的改了   → "改了 N 条（共扫 M 条）"
     */
    fun summary(): String = when {
        !ok -> "没改：$problem"
        scanned == 0 -> "没找到可检查的内容 —— 这个会话里还没有她说过的话"
        changed == 0 -> "扫了 $scanned 条，没有需要改的地方"
        else -> "改了 $changed 条（共扫 $scanned 条）"
    }
}

/**
 * **记忆的分层** —— 哪些先改、改得多认真。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户要求
 * ═══════════════════════════════════════════════════════════════
 *
 * > "你得按照记忆分层系统里的那些重点记忆开始改起，
 * >  不同等级的记忆用不同的思考强度去改"
 *
 * ⚠️ 这不是"优化"，是**必须的**：
 *   850 条原子如果平铺着一个强度改，要么烧穿 token，
 *   要么把最重要的（约束）和最不重要的（今天下雨）同等对待。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三层，权重与 🔥 强度都不同
 * ═══════════════════════════════════════════════════════════════
 *
 * | 层 | 例子 | 为什么是这个强度 |
 * |---|---|---|
 * | **约束** | "他不能吃辣" | 改错会**直接造成伤害** ⇒ 最强 |
 * | **偏好** | "他喜欢安静" | "你是谁"的证据 ⇒ 高 |
 * | **阶段结论** | "最近他在准备考试" | 会过时，改错影响小 ⇒ 低 |
 * | **事实** | "今天下雨" | 基本不该改 ⇒ 关闭 |
 * | **对话** | 角色说过的话 | 只是替换设定 ⇒ 低 |
 *
 * ⚠️ 强度用**档位序号**表示（0..3），不直接引用 `ThinkingEffort` ——
 *    `:personalogic` 不依赖 `:modelrouter`，那层映射放在 `:app`。
 */
enum class MemoryTier(
    val label: String,
    /** 排序用：越大越先改 */
    val priority: Int,
    /** 这一层**建议**的思考强度档位（0=off, 1=low, 2=high, 3=max） */
    val suggestedEffortRank: Int,
) {
    /** 约束：改错会直接造成伤害，最该认真。 */
    CONSTRAINT("约束", priority = 50, suggestedEffortRank = 3),

    /** 偏好："你是谁"的证据。 */
    PREFERENCE("偏好", priority = 40, suggestedEffortRank = 2),

    /** 角色说过的话：只是替换设定，不需要推演。 */
    DIALOGUE("说过的话", priority = 30, suggestedEffortRank = 1),

    /** 阶段结论：会过时，影响小。 */
    STAGE_CONCLUSION("阶段结论", priority = 20, suggestedEffortRank = 1),

    /** 事实：基本不该改（"今天下雨"与设定无关）。 */
    FACT("事实", priority = 10, suggestedEffortRank = 0),
    ;

    companion object {
        /** 原子类别的名字 → 分层。**不认识就当事实**（最保守，强度最低）。 */
        fun ofAtomKindName(kind: String): MemoryTier = when (kind.uppercase()) {
            "CONSTRAINT" -> CONSTRAINT
            "PREFERENCE" -> PREFERENCE
            "STAGE_CONCLUSION" -> STAGE_CONCLUSION
            else -> FACT
        }
    }
}

/** 一条待改写的条目，**带分层**。 */
data class RewriteTarget(
    val id: String,
    val text: String,
    val tier: MemoryTier,
)

/**
 * 把待改条目**按分层排好序**。
 *
 * ⚠️ 排序的目的不只是"先改重要的"：
 *    万一中途失败 / 用户中途退出，**已经改完的是最重要的那些**。
 *    反过来排的话，最可能的结果是"改了一堆不重要的，最要紧的没动"。
 */
fun orderByTier(targets: List<RewriteTarget>, maxPerTier: Int = Int.MAX_VALUE): List<RewriteTarget> {
    val byTier = targets.groupBy { it.tier }
    val out = mutableListOf<RewriteTarget>()
    // 层间：priority 高的先
    for (tier in MemoryTier.entries.sortedByDescending { it.priority }) {
        // 层内：保持原顺序，但封顶
        out += byTier[tier].orEmpty().take(maxPerTier)
    }
    return out
}

/**
 * 某一层该用多强的思考。
 *
 * @param capRank 用户在设置里选的**上限**（0..3）
 *
 * ★ 取 `min(层的建议, 用户上限)`：
 *   · 用户配 low ⇒ 全部用 low（他不想花钱，尊重）
 *   · 用户配 max ⇒ 按层分开（约束最强、事实关闭）
 *
 * ⚠️ 不做"层的建议优先于用户" —— 那等于用户的选择无效。
 */
fun effortRankFor(tier: MemoryTier, capRank: Int): Int =
    minOf(tier.suggestedEffortRank, capRank.coerceIn(0, 3))