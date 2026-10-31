package com.pocketagent.agentlogic

/**
 * **检查点的文本编解码** —— 纯逻辑，可离线测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么编解码必须住在纯 Kotlin 层
 * ═══════════════════════════════════════════════════════════════
 *
 * 我最初把编解码写在了 `:app/FileCheckpointStore` 里 —— 那里有
 * `Context`、能直接写文件，看起来是"自然的位置"。
 *
 * ⚠️ 但那样它**进不了离线验证器**。而编解码正是最需要测试的部分：
 *   · 用户的指令里可能有引号、反斜杠、换行（"帮我打开「设置」"）
 *   · 老检查点文件没有新增的字段，缺字段必须退回默认而不是整条失效
 *   · 进程被杀会留下**半行**，那半行不能毁掉整个文件
 *
 * ⇒ 这三种情况都是"看起来能跑、实际会静默丢数据"。
 *   所以编解码搬到这里，`:app` 只负责读写文件。
 *
 * ★ 与 `CustomPersonaStore` 同一套纪律：手写 JSON，每个字段带默认值，
 *   缺字段向前兼容、多字段向后兼容。
 */
object CheckpointCodec {

    /**
     * 编码成**一行**。
     *
     * ⚠️ 用短键名：这是内部格式，不需要人类可读，而它写在应用私有
     *    目录里长期驻留 —— 省下的空间虽小但没代价。
     *
     * ★ 字段顺序固定（便于 diff 与人工排查）。
     */
    fun encode(c: TaskCheckpoint): String = buildString {
        append('{')
        appendStr("t", c.taskId)
        append(',')
        appendStr("u", c.userInput)
        append(",\"s\":").append(c.completedSteps)
        append(",\"r\":").append(c.replanCount)
        append(",\"p\":[")
        append(c.plannedActions.joinToString(",") { "\"${esc(it)}\"" })
        append(']')
        append(",\"tu\":").append(c.usage.turns)
        append(",\"el\":").append(c.usage.elapsedMs)
        append(",\"eu\":").append(c.usage.energyMicroAh)
        append(",\"bu\":").append(c.usage.uploadBytes)
        append(",\"at\":").append(c.savedAtMs)
        append(",\"f\":").append(if (c.finished) 1 else 0)
        append('}')
    }

    /**
     * 解码一行。
     *
     * @return null = 这一行无法解析（调用方应当**跳过它**而不是丢弃整个文件）
     *
     * ⚠️ **每个字段都有默认值** —— 老文件没有后来新增的字段，
     *    缺字段必须退回默认而不是整条失效。这是 `CustomPersonaStore`
     *    定下的同一条纪律（"加字段不能让老用户的设置消失"）。
     *
     * ♥ 但 `taskId` 例外：**没有它这条记录就没有意义**（无法定位任务），
     *   所以它缺失时返回 null。
     */
    fun decode(line: String): TaskCheckpoint? {
        val s = line.trim()
        if (s.isEmpty() || s == "{}") return null

        fun str(key: String): String? {
            val marker = "\"$key\":\""
            val i = s.indexOf(marker)
            if (i < 0) return null
            val sb = StringBuilder()
            var j = i + marker.length
            while (j < s.length) {
                val ch = s[j]
                if (ch == '\\' && j + 1 < s.length) {
                    when (s[j + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        else -> sb.append(s[j + 1])
                    }
                    j += 2
                    continue
                }
                if (ch == '"') return sb.toString()
                sb.append(ch)
                j++
            }
            return null
        }

        fun num(key: String): Long? {
            val marker = "\"$key\":"
            val i = s.indexOf(marker)
            if (i < 0) return null
            val rest = s.substring(i + marker.length)
            val end = rest.indexOfFirst { it == ',' || it == '}' || it == ']' }
            val raw = if (end < 0) rest else rest.substring(0, end)
            return raw.trim().toLongOrNull()
        }

        val taskId = str("t") ?: return null

        return runCatching {
            TaskCheckpoint(
                taskId = taskId,
                userInput = str("u") ?: "",
                completedSteps = (num("s") ?: 0L).toInt(),
                replanCount = (num("r") ?: 0L).toInt(),
                plannedActions = decodeList(s),
                usage = BudgetUsage(
                    turns = (num("tu") ?: 0L).toInt(),
                    elapsedMs = num("el") ?: 0L,
                    energyMicroAh = num("eu") ?: 0L,
                    uploadBytes = num("bu") ?: 0L,
                ),
                savedAtMs = num("at") ?: 0L,
                finished = (num("f") ?: 0L) == 1L,
            )
        }.getOrNull()
    }

    /**
     * 解析 `"p":["a","b"]` 这个数组。
     *
     * ⚠️ 不能用简单的 `split("\",\"")`：元素里可能有转义的引号
     *   （`"p":["say \"hi\""]`）。那会把一个元素切碎成两个。
     *
     * ★ 这里写一个小状态机 —— 17 行，但比引正则更可靠，
     *   而且能被测试完整覆盖（见 `CheckpointCodecTest`）。
     */
    private fun decodeList(s: String): List<String> {
        val marker = "\"p\":["
        val i = s.indexOf(marker)
        if (i < 0) return emptyList()
        var j = i + marker.length
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inStr = false
        while (j < s.length) {
            val ch = s[j]
            when {
                ch == '\\' && inStr && j + 1 < s.length -> {
                    // ══════════════════════════════════════════════
                    //  ★★ 2026-10-31：这里原来直接 `append(s[j+1])` ——
                    //     那会让 `\n` 变成字面字符 `n`。
                    //
                    //  测试当场抓到：期望 `[x\ny, B, C]`，实际 `[xny, B, C]`。
                    //  ⚠️ 这个 bug 不报错 —— 只是恢复后动作名变成
                    //     `xny` 而不是两行。它会让"恢复上次任务"显示一串
                    //     奇怪的字符串，而没人知道是转义没还原。
                    //
                    //  ⇒ 必须与 `esc()` 的四个替换**严格对应**：
                    //    \\ → \   \" → "   \n → 换行   \r → 回车
                    // ══════════════════════════════════════════════
                    when (s[j + 1]) {
                        'n' -> cur.append('\n')
                        'r' -> cur.append('\r')
                        't' -> cur.append('\t')
                        else -> cur.append(s[j + 1])
                    }
                    j += 2
                    continue
                }
                ch == '"' -> {
                    if (inStr) { out += cur.toString(); cur.clear() }
                    inStr = !inStr
                }
                ch == ']' && !inStr -> return out
                inStr -> cur.append(ch)
            }
            j++
        }
        // ⚠️ 没遇到收尾的 `]` = 半截文件。已解析的部分仍然有效。
        return out
    }

    private fun StringBuilder.appendStr(key: String, value: String) {
        append('"').append(key).append("\":\"").append(esc(value)).append('"')
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
