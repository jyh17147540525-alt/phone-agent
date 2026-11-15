package com.pocketagent.personalogic

/**
 * 她在网上翻到的一条东西。
 *
 * ⚠️ 只是「一条」。不做列表、不做摘要、不做推荐 ——
 *    她要的不是资讯，是「今天看到一个有意思的东西，想讲给你听」。
 *    给她十条，她就会开始念新闻。
 */
data class WebItem(
    val title: String,
    val extract: String,
) {
    init {
        require(title.isNotBlank()) { "标题不能为空 —— 没有名字的东西她讲不出口" }
        require(extract.isNotBlank()) { "正文不能为空" }
    }
}

/**
 * 把网上取回来的一段转成「讲给她听」的那一段。
 *
 * ★ 这一层是**纯函数**：不联网、不看时钟、不读盘。
 *   取数在 `:app` 的 `WebReader` 里，这里只负责解析与措辞。
 *   分开的理由和 `LifeformDay` 一样 —— 联网的部分没法离线测，
 *   措辞的部分必须每句话都被测试盯住。
 */
object WebDigest {

    /** 摘录上限。再长她就从"翻到一条"变成"念词条"了。 */
    const val MAX_EXTRACT_CHARS: Int = 150

    /** 太短的摘录说不出什么，宁可这一条不要。 */
    const val MIN_EXTRACT_CHARS: Int = 24

    /** 标题上限。有些词条名长到没法在句子里念。 */
    const val MAX_TITLE_CHARS: Int = 40

    /**
     * 维基百科 REST 的 summary 接口 → 一条。
     *
     * ⚠️ 手写取值而**不引 JSON 库**：与 `SituationReader` 同一套做法，
     *    为了一个字段拉一个依赖不值得，而且这个 JSON 的形状是我们自己挑的。
     */
    fun parseSummary(json: String?): WebItem? {
        if (json.isNullOrBlank()) return null
        val title = stringField(json, "title")?.trim().orEmpty()
        val extract = stringField(json, "extract")?.let { trimExtract(it) }.orEmpty()
        return build(title, extract)
    }

    /**
     * 一言（hitokoto）→ 一条。
     *
     * 兜底用的第二种形状：`{"hitokoto":"...","from":"...","from_who":"..."}`。
     * 它给的是一句话而不是一件事，所以标题拼成「谁写的什么」。
     */
    fun parseHitokoto(json: String?): WebItem? {
        if (json.isNullOrBlank()) return null
        val quote = stringField(json, "hitokoto")?.trim().orEmpty()
        if (quote.length < MIN_EXTRACT_CHARS) return null
        val fromWho = stringField(json, "from_who")?.trim().orEmpty()
        val from = stringField(json, "from")?.trim().orEmpty()
        val title = when {
            fromWho.isNotBlank() && from.isNotBlank() -> "$fromWho 的《$from》"
            from.isNotBlank() -> "《$from》"
            else -> "某个人写的一句话"
        }
        return build(title, trimExtract(quote))
    }

    private fun build(title: String, extract: String): WebItem? {
        if (title.isBlank() || title.length > MAX_TITLE_CHARS) return null
        if (extract.length < MIN_EXTRACT_CHARS) return null
        if (looksLikeIndex(title) || looksLikeIndex(extract)) return null
        return WebItem(title = title, extract = extract)
    }

    /**
     * 消歧义页 / 目录页一律不要。
     *
     * 「XX 可以指：」这种东西念出来是灾难 —— 她会在对话里背一张清单。
     */
    private val INDEX_MARKS = listOf("消歧义", "可以指", "可能是指", "多义词", "是一个目录", "本条目")

    private fun looksLikeIndex(text: String): Boolean = INDEX_MARKS.any { text.contains(it) }

    /**
     * 压成一段能念的话。
     *
     * 规则：单行化 → 按句号切 → 贪心拼到上限 → 超了截断加省略号。
     * ⚠️ **不按字数硬切**：切在「土壤里放线菌的孢」这种地方，念出来是故障。
     */
    fun trimExtract(raw: String): String {
        val one = raw.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) return ""
        val sentences = Regex("[^。！？!?]+[。！？!?]?")
            .findAll(one)
            .map { it.value.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (sentences.isEmpty()) return one.take(MAX_EXTRACT_CHARS)
        val sb = StringBuilder()
        var taken = 0
        while (taken < sentences.size) {
            val s = sentences[taken]
            if (sb.isNotEmpty() && sb.length + s.length > MAX_EXTRACT_CHARS) break
            sb.append(s)
            taken++
            if (sb.length >= MAX_EXTRACT_CHARS) break
        }
        val dropped = taken < sentences.size || sb.length > MAX_EXTRACT_CHARS
        var out = sb.toString()
        if (out.length > MAX_EXTRACT_CHARS) out = out.take(MAX_EXTRACT_CHARS - 1)
        if (!dropped) return out
        // 丢掉了后面的句子就要留个尾巴 —— 不然后半截断得像"这条只讲到一半"。
        return out.trimEnd('。', '！', '？', '，', '、', ' ').take(MAX_EXTRACT_CHARS - 1) + "…"
    }

    /**
     * 那一段提示词。
     *
     * ⚠️ 三件事必须做到，缺一个这句提示词就会变成"念新闻"：
     *    ① 说清这是**她自己**看到的（不是用户告诉她的）；
     *    ② 说清**不必**主动汇报（否则她每轮开头都"我今天看到一个…"）；
     *    ③ 说清用**自己的话**（否则原样念词条）。
     *    ②③ 是负面/托底要求，所以写得短，不作列表 —— 见 `PromptVoice` 的体检表。
     */
    fun promptSection(item: WebItem): String = buildString {
        appendLine("## 你在网上看到的")
        appendLine()
        appendLine("今天你在网上看到「${item.title}」：${item.extract}")
        appendLine()
        append("这是你自己翻到的，他不在场。他没问就不必说，聊到了再提，用你自己的话讲，别念原文。")
    }

    /**
     * 断网时的兜底。
     *
     * ★ 为什么要有：这个模式的核心体验是"她今天看到点东西"，
     *   **不是**"她成功联网了"。网断了她仍然该有今天 ——
     *   和 `LifeformDay` 那些自己过出来的事一样，都是她的内在世界。
     *
     * ⚠️ 池子里只能是**经得起查的事实**（雨前土味的来源、月亮的自转周期这类），
     *    绝不能放"今天某地发生了什么" —— 那种东西一旦被用户查到是假的，
     *    坏掉的不是这一句，是"她说的关于外面的话还能不能信"。
     */
    fun fallback(dayIndex: Long): WebItem =
        POOL[Math.floorMod(dayIndex, POOL.size.toLong()).toInt()]

    /** 有网上的就用网上的，没有就用兜底。永不为 null —— 见 [fallback] 的理由。 */
    fun pick(dayIndex: Long, fetched: WebItem?): WebItem = fetched ?: fallback(dayIndex)

    private fun stringField(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = pattern.find(json) ?: return null
        return unescape(match.groupValues[1])
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = raw[i + 1]) {
                'n', 't', 'r' -> {
                    out.append(' ')
                    i += 2
                }
                '"' -> {
                    out.append('"')
                    i += 2
                }
                '\\' -> {
                    out.append('\\')
                    i += 2
                }
                '/' -> {
                    out.append('/')
                    i += 2
                }
                'u' -> {
                    if (i + 4 < raw.length) {
                        val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            out.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    out.append(e)
                    i += 2
                }
                else -> {
                    out.append(e)
                    i += 2
                }
            }
        }
        return out.toString()
    }

    private val POOL: List<WebItem> = listOf(
        WebItem(
            title = "雨前的土味",
            extract = "下雨之前闻到的那股土腥味，多半来自土壤里放线菌的孢子。",
        ),
        WebItem(
            title = "旧书的甜味",
            extract = "旧书翻开时那股甜味，一部分来自纸张里的木质素在慢慢分解。",
        ),
        WebItem(
            title = "海獭睡觉",
            extract = "海獭睡觉时会抓住同伴的手，或者缠一段海藻，免得被水冲走。",
        ),
        WebItem(
            title = "月亮的同一面",
            extract = "月亮自转一圈和绕地球一圈花的时间一样长，所以它总拿同一面朝着地球。",
        ),
        WebItem(
            title = "表针为什么顺时针",
            extract = "钟表的指针之所以顺时针走，沿用的是北半球日晷影子的方向。",
        ),
        WebItem(
            title = "企鹅的膝盖",
            extract = "企鹅有膝盖，只是藏在羽毛和脂肪里，从外面看不出来。",
        ),
        WebItem(
            title = "猫的呼噜",
            extract = "猫打呼噜的声音，大多落在二十多赫兹到一百多赫兹之间。",
        ),
        WebItem(
            title = "蜂蜜不容易坏",
            extract = "封好的蜂蜜放很久也不容易坏，因为水分少，而且偏酸。",
        ),
        WebItem(
            title = "打哈欠会传染",
            extract = "看见别人打哈欠，自己也想打，这件事在狗和黑猩猩身上也会发生。",
        ),
        WebItem(
            title = "香蕉几乎都一样",
            extract = "超市里卖的那种香蕉大多是同一个栽培品种，一代代靠无性繁殖种下来。",
        ),
        WebItem(
            title = "指甲长得快",
            extract = "指甲在夏天比冬天长得快一点，惯用的那只手也会略快一些。",
        ),
        WebItem(
            title = "老地图上的龙",
            extract = "很多老地图上写着「此处有龙」的地方，其实只是当时没人去过。",
        ),
    )
}
