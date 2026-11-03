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
    /** 保留原文的条数（含"本来就不冲突"） */
    val kept: Int,
    /** 非空表示没跑成，这是一句给用户看的话 */
    val problem: String = "",
) {
    val ok: Boolean get() = problem.isEmpty()

    /** 给用户看的一句话。 */
    fun summary(): String = if (!ok) {
        problem
    } else {
        "改了 $changed 条，保留了 $kept 条。"
    }
}
