package com.pocketagent.filelogic

/**
 * **回复里提到的文件** —— 从正文里认出"我做好了哪些文件"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这一层
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要的是"可以文件输出，用户点击后能打开这个文件，最好不要跳转别的软件"。
 * 工具层早就把文件写到工作区了（`输出/xxx.pptx`），回复里也写着路径 ——
 * 但界面上那只是一行**文字**：用户得自己去找文件管理器。
 *
 * 所以聊天界面要做的是：把正文里那些路径**认出来**，变成可点的卡片。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **只认带扩展名的**。`输出/` 这种目录不算文件 —— 点了也打不开。
 * 2. **不猜、不补**。正文里没写就不显示卡片：编一个"应该有"的文件出来，
 *    结果点开是空的，比没有卡片更糟（真机上已经见过"凭空造文件"）。
 * 3. **同一个文件只出现一次**。模型常把同一个路径说两遍。
 */
enum class ReplyFileKind { IMAGE, PDF, TEXT, OFFICE, ARCHIVE, AUDIO, OTHER }

data class ReplyFile(
    val path: String,
    val name: String,
    val kind: ReplyFileKind,
)

object ReplyFiles {

    const val MAX_FILES: Int = 6

    /** 认出来的扩展名 → 打开方式。没有的一律 OTHER（卡片仍然显示，只是提示打不开）。 */
    private val KINDS: Map<String, ReplyFileKind> = mapOf(
        "png" to ReplyFileKind.IMAGE, "jpg" to ReplyFileKind.IMAGE,
        "jpeg" to ReplyFileKind.IMAGE, "webp" to ReplyFileKind.IMAGE,
        "gif" to ReplyFileKind.IMAGE,
        "pdf" to ReplyFileKind.PDF,
        "txt" to ReplyFileKind.TEXT, "md" to ReplyFileKind.TEXT,
        "csv" to ReplyFileKind.TEXT, "json" to ReplyFileKind.TEXT,
        "log" to ReplyFileKind.TEXT,
        "docx" to ReplyFileKind.OFFICE, "xlsx" to ReplyFileKind.OFFICE,
        "pptx" to ReplyFileKind.OFFICE,
        "zip" to ReplyFileKind.ARCHIVE,
        "mp3" to ReplyFileKind.AUDIO, "m4a" to ReplyFileKind.AUDIO,
        "wav" to ReplyFileKind.AUDIO, "pcm" to ReplyFileKind.AUDIO,
    )

    /**
     * 从正文里挑出文件。
     *
     * 认的形状：`输出/报表.xlsx`、`/sdcard/PocketAgentTest/输出/报表.xlsx`、
     * 甚至裸文件名 `报表.xlsx` —— 只要扩展名在白名单里。
     */
    fun parse(reply: String, limit: Int = MAX_FILES): List<ReplyFile> {
        if (reply.isBlank()) return emptyList()
        val out = LinkedHashMap<String, ReplyFile>()
        for (m in PATH.findAll(reply)) {
            val cleaned = m.groupValues[1]
                .trim()
                .trim('"', '\'', '「', '」', '(', ')', '（', '）', ',', '，', '。', '`')
            if (cleaned.isEmpty()) continue
            val ext = cleaned.substringAfterLast('.', "").lowercase()
            val kind = KINDS[ext] ?: continue
            val raw = trimToPath(cleaned)
            if (raw.isEmpty()) continue
            if (out.containsKey(raw)) continue
            out[raw] = ReplyFile(path = raw, name = raw.substringAfterLast('/'), kind = kind)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }

    /** 这个格式应用内能不能打开（不能也要说清楚，而不是假装能）。 */
    fun openableInApp(kind: ReplyFileKind): Boolean = when (kind) {
        ReplyFileKind.IMAGE, ReplyFileKind.PDF, ReplyFileKind.TEXT, ReplyFileKind.OFFICE -> true
        ReplyFileKind.ARCHIVE, ReplyFileKind.AUDIO, ReplyFileKind.OTHER -> false
    }

    /** 打不开时给用户的话（告诉他文件在哪、怎么办）。 */
    fun unsupportedNote(kind: ReplyFileKind): String = when (kind) {
        ReplyFileKind.ARCHIVE -> "压缩包不能在应用里直接看。文件已经在工作区里了，用文件管理器打开。"
        ReplyFileKind.AUDIO -> "音频要在别的播放器里放。文件路径已经复制好了。"
        else -> "这个格式应用内打不开。文件已经在工作区里了，可以用别的软件打开。"
    }

    /**
     * 把"顺手被匹配进来的前面几个字"切掉。
     *
     * ⚠️ 单测撞过的形状：`保存成了输出/周报.docx` —— 因为路径字符类允许汉字，
     *   正则会把「保存成了」也算进路径里，于是卡片上出现一个根本不存在的文件。
     *   做法是**优先从已知目录名处切**，没有已知目录时只留最后一段文件名。
     */
    private fun trimToPath(raw: String): String {
        if (raw.startsWith("/")) return raw
        for (dir in KNOWN_DIRS) {
            val at = raw.indexOf(dir)
            if (at > 0) return raw.substring(at)
        }
        if (!raw.contains('/')) return raw
        val segments = raw.split('/')
        return if (segments.size >= 2 && segments[segments.size - 2].length <= 6) {
            segments.takeLast(2).joinToString("/")
        } else {
            segments.last()
        }
    }

    private val KNOWN_DIRS =
        listOf("输出/", "工作区/", "work/", "Download/", "downloads/", "PocketAgentTest/")

    /**
     * ⚠️ 必须带**前导分隔**（行首、空白、标点、反引号）。
     *    只写字符类的话，`保存成了输出/a.png` 这种中文连写会把前面的字吞进路径里。
     */
    private val PATH = Regex(
        "(?:^|[\\s，。；：！？、\"'「」（()【】`])" +
            "([\\w\\u4e00-\\u9fa5（）()【】\\[\\]\\-—．./]{1,120}?\\." +
            "(?:" + KINDS.keys.joinToString("|") + ")\\b)",
        RegexOption.IGNORE_CASE,
    )
}
