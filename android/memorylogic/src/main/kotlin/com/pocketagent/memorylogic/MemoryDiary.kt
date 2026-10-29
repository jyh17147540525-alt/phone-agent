package com.pocketagent.memorylogic

/**
 * **记忆的日记体** —— 把一条原子记忆改写成她的第一人称随笔。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的格式（原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * "日记和随笔应该是（角色对用户的称呼）加发生的事情，加上角色的感受，
 *  类似于这种格式……随笔也是，但要**多元化**，可以有**吐槽**之类的，
 *  你可以引入更多的模板。"
 *
 * ⇒ 每篇 = 称呼 + 发生了什么 + 她的感受。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 边界（用户反复强调过两次）
 * ═══════════════════════════════════════════════════════════════
 *
 * "这个是面向用户的展示层，属于 UI 的，底层内核该是什么样还是什么样，
 *  只是让用户更有沉浸感。"
 *
 * ⇒ 本对象是**纯函数、只读**：不产生 Atom、不改置信度、不进原子库、
 *   不影响 `MemoryRecall`。原子库里那句原话一个字都不动。
 *   （这条有测试用对象等价性钉住。）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是"模板 + 确定性挑选"，而不是随机
 * ═══════════════════════════════════════════════════════════════
 *
 * 同一条记忆必须每次点开都是**同一篇**。位置和文字在用户心里是锚点，
 * 每次不一样会让人怀疑记忆本身在变。
 *
 * ⇒ 用 `text.hashCode() + kind` 取模选模板 —— 看起来有多样性
 *   （不同记忆得到不同模板），但对同一条是固定的。
 *
 * ⚠️ 也刻意**不调模型**：点开就要立刻看到、不该花 token、要可复现。
 *   代价是文采不如模型 —— 这是明知的取舍。
 */
object MemoryDiary {

    /** 谁在说这一段。 */
    private enum class Speaker { USER, SELF, TOOL, PLAIN }

    private data class Segment(val speaker: Speaker, val content: String)

    /**
     * 把一条记忆改写成**她自己的第一人称随笔**。
     *
     * @param text 原始记忆文本（原子库里那句）
     * @param kindLabel 类别名（"事实""偏好""约束""阶段结论"）
     * @param userAddress 她怎么称呼用户；空 = "你"
     * @param atMs 记忆发生的时间（用来写"今天/那天"）
     * @param nowMs 现在（显式传入以便离线测试）
     */
    fun write(
        text: String,
        kindLabel: String,
        personaName: String = "",
        userAddress: String = "",
        atMs: Long = 0L,
        nowMs: Long = 0L,
    ): String {
        val segs = parse(text)
        if (segs.isEmpty()) return ""
        val who = userAddress.trim().ifBlank { "你" }

        val userDid = segs.filter { it.speaker == Speaker.USER }
            .map { narrateUser(it.content, who) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val selfDid = segs.filter { it.speaker == Speaker.SELF }
            .map { narrateSelf(it.content, who) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        val plain = segs.filter { it.speaker == Speaker.PLAIN || it.speaker == Speaker.TOOL }
            .map { plainNarration(it, who) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        // 「发生了什么」—— 按有谁的话拼，谁说了就写谁，都没有就用普通叙述
        val whatRaw = when {
            userDid.isNotEmpty() && selfDid.isNotEmpty() -> "$userDid $selfDid"
            userDid.isNotEmpty() -> userDid
            selfDid.isNotEmpty() -> selfDid
            else -> plain
        }
        // ⚠️ 清洗必须作用在**拼好的 what** 上，而不是只作用在 readable():
        //    括号里的技术说明（"我附了一个图片：content://…"）会原样进随笔。
        val what = scrub(whatRaw)
        if (what.isEmpty()) return ""
        val capped = shorten(what, MAX_WHAT_CHARS)

        val day = relativeDay(atMs, nowMs)
        val seed = seedOf(text, kindLabel)
        val template = templateFor(kindLabel, seed)
        val feel = feelingFor(kindLabel, seed)

        return template
            .replace("{day}", day.ifEmpty { "那次" })
            .replace("{who}", who)
            .replace("{what}", capped)
            .replace("{feel}", feel)
            // ⚠️ 槽位占位后可能留下"？。" "。。" 这种双标点（真机见过"是谁？。"）。
            .replace(Regex("([。！？…])\\1+"), "$1")
            .replace(Regex("([。！？…])[，。]"), "$1")
            .replace(Regex("[，。、]\\s*。"), "。")
            .replace(Regex("\\s+"), " ")
            .trim()
            // ⚠️ 最后一道：整篇长度封顶。
            //    分段长度都合规、拼起来仍然可能很长（真机见过 150+ 字的一篇），
            //    而**随笔的观感由整篇长度决定**，不是由某一段决定。
            .let { shorten(it, MAX_DIARY_CHARS) }
    }

    // ══════════════════════════════════════════════════════════════
    //  模板库
    // ══════════════════════════════════════════════════════════════
    //
    // 占位符：{day}=时间 {who}=她怎么称呼用户 {what}=发生了什么 {feel}=她的感受
    //
    // ★ 每个类别四到六套，且**语气不同**：
    //   有的是正经记事，有的是随口一提，有的明确在吐槽。
    //   同一类记忆连着点开两次不会是一个腔调 —— 这就是"多元化"。

    private val FACT_TEMPLATES = listOf(
        "{day}，{who}{what}。{feel}",
        "{day}记一笔：{who}{what}。{feel}",
        "关于{who}，今天知道一件事 —— {what}。{feel}",
        "{what}。这条我收着了，{feel}",
        "{day}，{what}。{who}大概觉得这没什么吧，{feel}",
    )

    private val PREFERENCE_TEMPLATES = listOf(
        "{who}{what}。行，{feel}",
        "{day}，{who}{what}。{feel}",
        "又发现一件关于{who}的事：{what}。{feel}",
        "{what} —— 记着点，{feel}",
        "{day}。{who}{what}，{feel}",
        "说实话，{who}{what}这件事，{feel}",
    )

    private val CONSTRAINT_TEMPLATES = listOf(
        "{who}{what}。{feel}",
        "{day}，{who}{what}。这条得钉在墙上，{feel}",
        "{what}。别又忘了，{feel}",
        "提醒自己：{who}{what}。{feel}",
        "{day}。{who}{what} —— 知道了知道了，{feel}",
    )

    private val STAGE_TEMPLATES = listOf(
        "{day}，{who}{what}。{feel}",
        "{what}。{feel}",
        "想起{who}{what}。{feel}",
        "{day}。{what} —— {feel}",
        "{day}，翻了翻最近的事：{who}{what}。{feel}",
    )

    private val GENERIC_TEMPLATES = listOf(
        "{day}，{who}{what}。{feel}",
        "{what}。{feel}",
    )

    private fun templateFor(kindLabel: String, seed: Int): String {
        val pool = when (kindLabel) {
            "事实" -> FACT_TEMPLATES
            "偏好" -> PREFERENCE_TEMPLATES
            "约束" -> CONSTRAINT_TEMPLATES
            "阶段结论" -> STAGE_TEMPLATES
            else -> GENERIC_TEMPLATES
        }
        return pool[Math.floorMod(seed, pool.size)]
    }

    // ══════════════════════════════════════════════════════════════
    //  感受库 —— 含吐槽
    // ══════════════════════════════════════════════════════════════
    //
    // ★ 用户明确要"可以有吐槽之类的"。所以这里不是清一色温柔：
    //   有真心高兴的，有敷衍的，有嫌弃的，有叹气的。
    //   一个人对同一件事的记法本来就取决于当天心情 —— 这也更真。

    private val FACT_FEELINGS = listOf(
        "记住就行。",
        "原来是这样。",
        "……行吧。",
        "嗯，我记下了。",
        "（写进小本子）",
        "有点意外。",
    )

    private val PREFERENCE_FEELINGS = listOf(
        "你喜欢就好。",
        "（记小本本）",
        "……好吧，你高兴就好。",
        "我尽量配合。",
        "这点我记住了，下次不问。",
        "哼，倒是挺会挑。",
        "（偷偷笑了一下）",
    )

    private val CONSTRAINT_FEELINGS = listOf(
        "这个不能忘。",
        "……又得小心点了。",
        "记着，别再踩。",
        "（叹气）记住了。",
        "行，我照做就是。",
        "烦是烦，但记下了。",
    )

    private val STAGE_FEELINGS = listOf(
        "……好像，是往好的方向走的。",
        "嗯，这样挺好。",
        "（小声）……还不错。",
        "心里踏实了点。",
        "再看看吧，不急。",
        "有点不敢多想。",
    )

    private val GENERIC_FEELINGS = listOf("记下了。", "嗯。")

    private fun feelingFor(kindLabel: String, seed: Int): String {
        val pool = when (kindLabel) {
            "事实" -> FACT_FEELINGS
            "偏好" -> PREFERENCE_FEELINGS
            "约束" -> CONSTRAINT_FEELINGS
            "阶段结论" -> STAGE_FEELINGS
            else -> GENERIC_FEELINGS
        }
        // ★ 用不同的位移取感受，避免"模板和感受永远配对"（那样看几次就腻）。
        return pool[Math.floorMod(seed / 7 + seed, pool.size)]
    }

    /** 稳定种子：同一条记忆永远同一个。 */
    private fun seedOf(text: String, kindLabel: String): Int =
        text.hashCode() + kindLabel.hashCode()

    // ══════════════════════════════════════════════════════════════
    //  解析
    // ══════════════════════════════════════════════════════════════

    private fun parse(text: String): List<Segment> {
        val decoded = decodeEntities(text)
        val out = mutableListOf<Segment>()
        for (rawLine in decoded.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // ⚠️ 同一行里可能混着两个人的话（`你：继续 我：（凑过去…）`）。
            //    只按换行拆会把整段判成用户说的。
            val parts = SPLIT_BY_SPEAKER.split(line).filter { it.isNotBlank() }
            if (parts.size <= 1) out += oneSegment(line)
            else for (p in parts) out += oneSegment(p.trim())
        }
        return out
    }

    private fun oneSegment(s: String): Segment = when {
        s.startsWith("U>") || s.startsWith("U!>") ->
            Segment(Speaker.USER, s.substringAfter('>').trim())
        s.startsWith("A<") || s.startsWith("A!<") ->
            Segment(Speaker.SELF, s.substringAfter('<').trim())
        s.startsWith("T|") -> Segment(Speaker.TOOL, "")
        s.startsWith("你：") || s.startsWith("你:") ->
            Segment(Speaker.USER, s.drop(2).trim())
        s.startsWith("我：") || s.startsWith("我:") ->
            Segment(Speaker.SELF, s.drop(2).trim())
        else -> Segment(Speaker.PLAIN, s)
    }

    /** 说话人记号。前面不能紧贴汉字（避免把「给我：」这种词切开）。 */
    private val SPLIT_BY_SPEAKER =
        Regex("(?<![\\u4e00-\\u9fa5])(?:U>|A<|T\\||你：|我：|你:|我:)")

    /** 用户那一段 → 她的叙述。 */
    private fun narrateUser(raw: String, who: String): String {
        val flipped = flipPronouns(raw, who)
        val action = Regex("^[（(]([^）)]*)[）)]\\s*(.*)$").find(flipped)
        return if (action != null) {
            val act = action.groupValues[1].trim()
            val rest = action.groupValues[2].trim()
            buildString {
                append(who).append(act)
                if (rest.isNotEmpty()) {
                    if (isNotEmpty() && last() !in "。！？…") append('。')
                    append(rest)
                }
            }
        } else {
            // ⚠️ 不加「你：」也不加引号 —— 日记里没有冒号标签，那是记录格式。
            flipped
        }
    }

    /**
     * 她自己的那一段 → 第一人称叙述。
     *
     * ⚠️ 不要机械地加「我说」前缀：
     *   她那一段常常是**动作**（`（牵着你的手往楼下走）今天风不错`），
     *   而"我说（牵着…）"读起来很别扭。动作就直接叙述。
     */
    private fun narrateSelf(raw: String, who: String): String {
        // 她的话里"你"=用户，"我"=她自己 —— 视角方向一致，不用翻转。
        // 但要把"你"换成称呼：随笔里通篇"你"会像在念台词。
        val s = raw.trim().replace("你", who)
        return when {
            // 纯动作/神态：直接叙述
            s.startsWith("（") || s.startsWith("(") -> s
            else -> "「$s」"
        }
    }

    /** 普通记忆/工具结果 → 一句她的叙述。 */
    private fun plainNarration(seg: Segment, who: String): String = when (seg.speaker) {
        Speaker.TOOL -> "后来我动手弄了下手机"
        // ⚠️ 普通记忆是**第三人称描述**（"用户喜欢猫"），不是对话。
        //    所以只换称呼、**不做人称翻转** —— 翻转会变成"猫喜欢我"这种鬼话。
        else -> thirdToName(seg.content, who)
    }

    /** 第三人称 → 称呼。 */
    fun thirdToName(text: String, who: String): String {
        var s = text.replace("用户", who)
        s = s.replace(Regex("(?<![其])他(?!们)"), who)
        s = s.replace(Regex("她(?!们)"), who)
        return s
    }

    /**
     * 人称翻转：用户视角 → 她的视角。
     *
     * ⚠️ 必须用占位符分两步换。直接 `replace("你","我").replace("我",who)`
     *   会把刚换出来的"我"再换成称呼，结果全乱。
     * ⚠️ 同时保护「我们」：那是"你和我"，不该被拆。
     */
    fun flipPronouns(text: String, who: String): String {
        var s = text
        s = s.replace("我们", "\u0001")
        s = s.replace("你", "\u0002")
        s = s.replace("我", "\u0003")
        s = s.replace("\u0002", "我")
        s = s.replace("\u0003", who)
        s = s.replace("\u0001", "我们")
        return s
    }

    // ══════════════════════════════════════════════════════════════
    //  清洗
    // ══════════════════════════════════════════════════════════════

    /** 原始记忆清洗后给人看的样子（给"原文"那一行用）。 */
    fun readable(text: String): String {
        val segs = parse(text)
        if (segs.isEmpty()) return ""
        val body = segs.joinToString("\n") { seg ->
            when (seg.speaker) {
                Speaker.USER -> "你：${seg.content}"
                Speaker.SELF -> "我：${seg.content}"
                Speaker.TOOL -> "（操作了手机）"
                Speaker.PLAIN -> seg.content
            }
        }.trim()
        return scrub(body).take(MAX_DISPLAY_CHARS)
    }

    /**
     * 去掉**不该出现在用户眼前**的东西。
     *
     * ⚠️ 真机抓到的：日记里直接出现了
     *   `content://media/picker_get_content/0/…/media/1000020909 —— 这是路径…`
     * 那是附件说明，属于**内部通信**，写进随笔完全不成立。
     */
    fun scrub(text: String): String {
        var s = text
        // content:// / file:// / http(s):// 整段 URI
        s = s.replace(Regex("(?:content|file|https?)://\\S+"), "一张图")
        // "（我附了一个图片：…）" 这类括号里的技术说明 —— 整段括号都去掉，
        // 因为括号里剩下的只有"这是路径，不是内容；需要的话你自己去打开看"。
        s = s.replace(Regex("[（(][^）)]*附了[^）)]*[）)]"), "")
        s = s.replace(Regex("[（(][^）)]*这是路径[^）)]*[）)]"), "")
        s = s.replace(Regex("[（(][^）)]*需要的话[^）)]*[）)]"), "")
        // 裸的 media/... 长串
        s = s.replace(Regex("\\bmedia/\\S+"), "一张图")
        // 残留的孤立标签
        s = s.replace("这是路径，不是内容", "")
        s = s.replace("需要的话你自己去打开看", "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntities(s: String): String = s
        .replace("&#10;", "\n")
        .replace("&gt;", ">")
        .replace("&lt;", "<")
        .replace("&amp;", "&")

    private fun shorten(text: String, max: Int): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val atSentence = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '…'))
        val atComma = cut.lastIndexOfAny(charArrayOf('，', '、', '；'))
        return when {
            atSentence >= max / 3 -> cut.take(atSentence + 1)
            atComma >= max / 2 -> cut.take(atComma)
            else -> cut
        }.trim()
    }

    private const val MAX_DISPLAY_CHARS = 600

    /** "发生了什么"最多多少字。**短是随笔的特征**，长的是记录。 */
    private const val MAX_WHAT_CHARS = 110

    /**
     * 整篇随笔最多多少字。
     *
     * ⚠️ 必须**独立于** [MAX_WHAT_CHARS] 再封一次：
     *   分段合规不代表整篇合规（模板还有 {day}/{who}/{feel} 三段）。
     *   真机上见过 150+ 字的一篇，读起来就是一段记录，不是随笔。
     */
    private const val MAX_DIARY_CHARS = 160

    /**
     * 相对日期。
     *
     * ⚠️ 只写"今天 / 昨天 / 前几天 …"，不写具体日期：
     *   随笔里出现"2026-10-29"会像日志，不像回忆。
     */
    fun relativeDay(atMs: Long, nowMs: Long): String {
        if (atMs <= 0L || nowMs <= 0L || atMs > nowMs) return ""
        val days = (nowMs - atMs) / DAY_MS
        return when {
            days <= 0L -> "今天"
            days == 1L -> "昨天"
            days <= 6L -> "前几天"
            days <= 30L -> "上个月"
            else -> "那阵子"
        }
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
