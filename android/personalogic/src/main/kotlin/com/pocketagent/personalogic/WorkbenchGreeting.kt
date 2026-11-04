package com.pocketagent.personalogic

/**
 * **工作台开场白** —— 那个页面上"有人在那里等你"的证据。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-04：为什么开场白要单独成文件、还要有测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话：
 * "工作模式你要切到另一个页面啊，就是工作页，在这个页面，开场，背景上
 *  都会有这个角色的随机开场白，像 claude 那样，这个页面就叫「（用户对
 *  角色的昵称）的工作台」而不是从角色聊天切换到工作，这样太生硬了"
 *
 * ⇒ 它**不是装饰文案**，它要满足三条互相拉扯的要求：
 *
 *   ① **像这个人在说话** —— 所以称呼用户时用角色设定里的
 *      [CustomPersona.addressUser]，不是写死"你"。
 *   ② **不能撒娇** —— 工作台上摆一句"人家等你半天了"，用户会怀疑
 *      这个页面是不是又要陪聊。开场白只负责"报到"和"问要办什么"。
 *   ③ **不能不说话** —— 空白页 + 一个输入框是冷酷客服的形态。
 *      一句有人味的开场白正是"有人格的工作"与"客服"的分界线。
 *
 * ⚠️ 工作模式**不写动作描写**（见 [WorkDiscipline]），所以这里一句
 *    括号动作都没有 —— `（）` 在这里是硬禁止，测试会盯住。
 *
 * ⚠️ 纯函数 + 显式 `seed`：不要在里面读时钟或 `Random`。
 *    当前时间是**调用方**算好传进来的（`hourOfDay`），
 *    这样同一时刻同一颗种子永远给同一句 —— 可回放、可测试。
 *    真机上"每次进页面都换一句"由调用方传 `System.currentTimeMillis()` 实现。
 */
object WorkbenchGreeting {

    /** 一天里的四段。分段点选在人的作息边界上，不是整点。 */
    enum class Band {
        MORNING,
        DAY,
        EVENING,
        NIGHT,
    }

    /**
     * 由小时（0..24，可带小数）判断时段。
     *
     * ⚠️ 越界**不抛**：这个值来自设备时区，个别 ROM 上出现过 24.0、
     *    负数这类脏值。开场白不值得让页面崩掉 —— 兜底成 [Band.DAY]。
     */
    fun bandOf(hourOfDay: Double): Band {
        if (hourOfDay.isNaN() || hourOfDay < 0.0 || hourOfDay >= 24.0) return Band.DAY
        return when {
            hourOfDay < 5.0 -> Band.NIGHT
            hourOfDay < 11.0 -> Band.MORNING
            hourOfDay < 17.0 -> Band.DAY
            hourOfDay < 22.0 -> Band.EVENING
            else -> Band.NIGHT
        }
    }

    /**
     * 从 [CustomPersona.addressUser] 里**取出一个能直接喊出口的称呼**。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 2026-11-04 晚：装机后才发现的坑
     * ═══════════════════════════════════════════════════════════
     *
     * 这个字段的**标签**是"怎么称呼用户"，于是很容易被当成一个词来用 ——
     * 但真实数据里用户填的是一整段说明：
     *
     *   「她通常称呼你"主人"或直接叫"你"。婚后会更习惯叫"老公""亲爱的"…」
     *
     * 直接塞进占位符的后果，真机截图上是这样：
     * 工作台最上面那句大标题变成了三段带引号的散文，后面拖着
     * 「，还没睡？说吧。」—— 像谁把台词本当便签贴在了门框上。
     *
     * ⇒ 只接受"看起来像称呼"的值：**短、没有标点、不是在解释自己**。
     *   不满足就返回空串，让调用方退回不带称呼的那组文案。
     *   宁可少喊一声，也不能让一句话变成一屏字。
     */
    fun salutationOf(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return ""
        // 一个称呼喊出口最多几个字。超过就说明这多半是说明文字。
        if (t.length > MAX_SALUTATION_CHARS) return ""
        // ★ 只允许字母（含汉字）：出现标点、空格、斜杠、数字就不是称呼。
        //   刻意写成「非字母即拒」而不是列举黑名单 —— 标点变体永远列不完，
        //   真机上正是漏掉一个 `/` 让「主人/亲爱的」又整段漏了出去。
        if (!t.all { it.isLetter() }) return ""
        // 在解释"怎么称呼"的元叙述，本身就是说明文字的特征。
        if (SALUTATION_META_WORDS.any { t.contains(it) }) return ""
        return t
    }

    /**
     * 该时段可用的开场白。
     *
     * ⚠️ 传进来的 `addressUser` 会先过 [salutationOf] ——
     *    长段落、带标点、带"称呼/叫"的说明文字一律当没填。
     *
     * @param addressUser 角色怎么称呼用户（`CustomPersona.addressUser`）。
     *   空 = 没有设定称呼 ⇒ 换用**不带称呼**的那一组，
     *   而不是把"你"硬塞进"{称呼}，早"这种句式里 —— 那会变成"你，早"。
     */
    fun headlines(band: Band, addressUser: String = ""): List<String> {
        val call = salutationOf(addressUser)
        if (call.isEmpty()) return PLAIN[band].orEmpty()
        return ADDRESSED[band].orEmpty().map { it.replace(CALL, call) }
    }

    /** 开场白（大标题）。 */
    fun headline(band: Band, addressUser: String = "", seed: Int = 0): String {
        val pool = headlines(band, addressUser).ifEmpty { PLAIN.getValue(Band.DAY) }
        return pool[pickIndex(seed, pool.size)]
    }

    fun headline(hourOfDay: Double, addressUser: String = "", seed: Int = 0): String =
        headline(bandOf(hourOfDay), addressUser, seed)

    /** 开场白下面那行小字 —— 说明这个页面的规矩（可以直接动手）。 */
    fun hint(seed: Int = 0): String = HINTS[pickIndex(mix(seed), HINTS.size)]

    // ══════════════════════════════════════════════════════════════
    //  文案
    // ══════════════════════════════════════════════════════════════

    /**
     * 称呼占位符。
     *
     * ⚠️ 用 `{call}` 这种**显式占位**而不是 `String.format("%s")`：
     *    用户可以把称呼写成任何东西（"老板""学长"），万一里面带 `%`
     *    （"100%不靠谱"），格式化会直接抛 `UnknownFormatConversionException`
     *    —— 一句开场白把页面搞崩，是本项目最不能接受的那种失败。
     */
    private const val CALL = "{call}"

    /** 称呼最多几个字。见 [salutationOf]。 */
    private const val MAX_SALUTATION_CHARS = 8

    /** 出现这些词说明这段文字在**解释**称呼，它本身不是称呼。 */
    private val SALUTATION_META_WORDS =
        listOf("称呼", "叫", "通常", "有时", "习惯", "语气", "亲密", "亲昵", "昵称")

    private val ADDRESSED: Map<Band, List<String>> = mapOf(
        Band.MORNING to listOf(
            "早，{call}。今天要办什么？",
            "{call}，先办哪件？",
            "说事吧，{call}。",
        ),
        Band.DAY to listOf(
            "接下来做什么，{call}？",
            "{call}，今天的事排哪儿了？",
            "有事就说，{call}。",
        ),
        Band.EVENING to listOf(
            "晚上了，{call}。还剩什么？",
            "{call}，今天收尾要办什么？",
            "忙完了？说，{call}。",
        ),
        Band.NIGHT to listOf(
            "这么晚，{call}。什么事？",
            "{call}，还没睡？说吧。",
            "夜里也能办。说，{call}。",
        ),
    )

    private val PLAIN: Map<Band, List<String>> = mapOf(
        Band.MORNING to listOf(
            "今天要办什么？",
            "早。先干哪件？",
            "说事吧，我听着。",
        ),
        Band.DAY to listOf(
            "接下来做什么？",
            "有事就说，我在。",
            "先办哪件？",
        ),
        Band.EVENING to listOf(
            "晚上了，还剩什么？",
            "今天收尾要办什么？",
            "忙完了？说。",
        ),
        Band.NIGHT to listOf(
            "这么晚，什么事？",
            "还没睡？说吧。",
            "夜里也能办，说。",
        ),
    )

    /**
     * 大标题下面那行小字。
     *
     * ═══════════════════════════════════════════════════════
     *  ★★ 2026-11-04 晚：这一池整池换过
     * ═══════════════════════════════════════════════════════
     *
     * 上一版是「工具已经挂好了。你说，我做。」这一类 —— 用户看完真机
     * 截图说：「那句工具链已经挂好，你说，我做，换掉换掉，换成角色自己的话」。
     *
     * 问题不在措辞好不好听，而在**说话的人是谁**：
     *   · 「工具已经挂好了」是**安装日志**在说话；
     *   · 「你说，我做」是**任务队列**在说话。
     * 这行小字长在**她**的页面上、就在她的开场白下面，说话的必须是**她**。
     *
     * ⇒ 全部换成第一人称的自然口语。同一件事（"你只管开口，别的我来"）
     *   用人的方式说出来，而不是用产品的方式宣布。
     *
     * ⚠️ 仍然不许出现「工具」「挂好」「权限」「模型」这类机器词 ——
     *    它们会把这一屏从"有人在这儿"拽回"有个系统在这儿"。
     */
    private val HINTS: List<String> = listOf(
        "说吧，我这就去看。",
        "要开哪个、想查什么，跟我讲一声。",
        "慢慢来，我不催你。",
        "你先说，我边听边弄。",
        "这会儿有空，你说什么我都接。",
        "急的话先说重点，剩下的我再问。",
    )

    /**
     * 把种子搅一下，让「大标题」和「小字」不总是配同一对。
     *
     * ⚠️ 原来写的是 `seed ushr 7` —— 看着也能打散，但 `0..127` 的种子
     *    右移七位后**全是 0**，于是 `hint(0)` 到 `hint(40)` 拿到同一句话。
     *    测试 `小字说明这个页面能直接动手` 当场抓到（"小字太少"）。
     *
     * 用 32 位乘法 + 高位回灌：乘法让相邻种子立刻散开，异或高位让
     * 「按分钟取种子」这种只差一点的输入也不会连着撞同一句。
     */
    private fun mix(seed: Int): Int {
        val x = seed * -1640531527 // 0x9E3779B1
        return x xor (x ushr 16)
    }

    /**
     * 把任意 Int 映射到 `0 until size`。
     *
     * ⚠️ 不能写 `seed % size`：负数取模在 Kotlin 里还是负数
     *    （`-3 % 5 == -3`），会直接 `IndexOutOfBoundsException`。
     *    也不能用 `abs(seed)` —— `abs(Int.MIN_VALUE)` 还是负数。
     */
    private fun pickIndex(seed: Int, size: Int): Int {
        if (size <= 1) return 0
        return ((seed.toLong() and 0xFFFF_FFFFL) % size).toInt()
    }
}
