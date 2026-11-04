package com.pocketagent.agentlogic.orchestration

/**
 * 一句话进来之后的**分流结果**。
 *
 * 只有四种去处，加上一个"停"：聊天、任务、任务链、反问、停止。
 * 多一种就多一条要维护的分支，而分支错了的表现是"用户说了事，它却开始闲聊"。
 */
sealed interface Intake {

    /** 给用户看的一句话理由。必须短 —— 它会出现在"我理解成……"那一行里。 */
    val reason: String

    /** 纯聊天：不碰手机，不动手脚。 */
    data class Chat(val text: String, override val reason: String) : Intake

    /**
     * 单件任务。
     *
     * [needsConfirmation] 是**进来就问**的那一类（钱、删、停用）。它不等于安全闸门 ——
     * 真正的闸门在执行层（`AutonomyPolicy` + 档位网关）。这里只是"别不声不响地做"。
     */
    data class Task(
        val goal: String,
        val needsConfirmation: Boolean,
        val confidence: Double,
        override val reason: String,
    ) : Intake

    /** 多件任务，按顺序做。 */
    data class Chain(val goals: List<String>, override val reason: String) : Intake

    /** 问一句再动手。 [options] 是给用户点的候选，不是必须。 */
    data class Clarify(
        val question: String,
        val options: List<String>,
        override val reason: String,
    ) : Intake

    /** 用户在任务跑着的时候说"停"。 */
    data class Stop(override val reason: String) : Intake
}

/** 分流时能看到的上下文。默认全空 —— 单条文本也能分流，这是底线要求。 */
data class IntakeContext(
    /** 上一次任务的目标。用来解"那个""它"这类指代。 */
    val lastTaskGoal: String? = null,
    /** 现在有没有任务在跑。跑着的时候说"停"不该开新任务。 */
    val hasActiveTask: Boolean = false,
    /** 当前前台页面标题（有就读屏拿到，没有也不影响分流）。 */
    val screenTitle: String? = null,
)

/**
 * 一句话 → 分流。
 *
 * ## 它在整条链路里是什么位置（**下限，不是上限**）
 *
 * 正常情况下，分流由模型做：模型能理解"把昨天那个会议纪要整理成表格发我"
 * 这种带指代与隐含条件的句子。本类处理的是**模型不在场**的那几种时刻：
 * 模型超时、断网、输出不可解析、或者用户在设置里关掉了云端模型。
 *
 * ⇒ 它的定位是**地板**：宁可把"整理会议纪要"当成聊天，也不要猜错方向乱点屏幕。
 *   猜错一次的代价是用户手机被点乱，而漏判一次的代价只是"多问一句"。
 *
 * ## 三条明确的选择
 *
 * 1. **只认动作词，不认意图**。没有动作词的句子一律当聊天 —— 包括
 *    "我的手机好卡"这种抱怨（它的正确回应是安慰或反问，不是去清缓存）。
 * 2. **涉及钱、删除、停用一律先问**。这几类错误没有第二次机会。
 * 3. **指代没有前文就问**。"打开那个" 里的"那个"没有 [IntakeContext.lastTaskGoal]
 *    时无法解析，猜一个的后果是把用户带到无关页面。
 */
object TaskIntake {

    // ── 词表 ────────────────────────────────────────────────────────────────

    /** 动作词。宁可少，不要多 —— 多一个词就多一类误判。 */
    private val ACTION_VERBS = listOf(
        "打开", "启动", "进入", "关闭", "退出", "返回", "回桌面",
        "搜", "搜索", "查", "查询", "看看", "看一眼", "找", "翻",
        "发", "发送", "发个", "回复", "转发", "告诉", "通知",
        "点", "点一下", "点开", "滑动", "滑到", "下拉", "上滑",
        "输入", "填", "写", "改成", "换成", "调到", "设置", "设成",
        "关掉", "打开", "静音", "取消静音", "连上", "断开",
        "下载", "上传", "安装", "卸载", "停用", "启用",
        "截屏", "截图", "录屏", "录制",
        "整理", "归类", "汇总", "导出", "备份", "删除", "清理", "清空",
        "订", "预订", "下单", "买", "充", "支付", "转账", "付款",
        "定个", "提醒我", "记一下", "记住",
        "复制", "粘贴", "粘贴到", "收藏", "点赞", "关注",
    )

    /** 钱相关。命中的任务一律先问。 */
    private val MONEY_WORDS = listOf(
        "转账", "付款", "支付", "下单", "购买", "充值", "打赏", "红包", "买",
    )

    /** 破坏性动作。命中的任务一律先问。 */
    private val DESTRUCTIVE_WORDS = listOf(
        "删除", "删掉", "清空", "格式化", "卸载", "停用", "禁用", "抹掉", "重置",
    )

    /** 连接词。命中就按"任务链"拆。 */
    private val CONNECTORS = listOf(
        "然后", "接着", "之后", "最后", "顺便", "再帮我", "再把", "再给", "并且",
    )

    /** 疑问标记。带这些词、又没有动作词 → 当聊天。 */
    private val QUESTION_MARKS = listOf(
        "吗", "呢", "为什么", "怎么", "是不是", "能不能", "可不可以", "有没有", "啥意思",
    )

    /** 指代。没有前文时解析不了。 */
    private val PRONOUNS = listOf("那个", "这个", "它", "刚才那个", "上面那个", "刚才的")

    /** 请求语气。命中了就提高置信度 —— 「帮我……」比「打开」更像真的要去干。 */
    private val REQUEST_MARKERS = listOf("帮我", "给我", "替我", "麻烦", "请", "把", "去")

    /** 常见应用名。命中了说明宾语具体，置信度更高。 */
    private val KNOWN_APPS = listOf(
        "微信", "qq", "淘宝", "支付宝", "京东", "拼多多", "抖音", "快手",
        "设置", "相册", "短信", "电话", "浏览器", "地图", "高德", "日历",
        "闹钟", "计算器", "文件", "相机", "录音", "邮件", "飞书", "钉钉",
    )

    /** 停止命令。 */
    private val STOP_WORDS = listOf("停", "停下", "别做了", "取消", "算了", "不用了", "停止", "回来")

    // ── 入口 ────────────────────────────────────────────────────────────────

    fun route(text: String, context: IntakeContext = IntakeContext()): Intake {
        val raw = text.trim()
        val normalized = normalize(raw)

        if (normalized.isEmpty()) {
            return Intake.Clarify(
                question = "你想让我做什么？",
                options = listOf("打开微信给张三发消息", "把屏幕亮度调到一半", "看看现在屏幕上是哪个页面"),
                reason = "你没有输入内容",
            )
        }

        // 1. 任务跑着时喊停 —— 必须排在动作词之前，否则「取消订单」会被当成新任务
        if (context.hasActiveTask && isStop(normalized)) {
            return Intake.Stop(reason = "你说停，我就先停")
        }

        val hasAction = ACTION_VERBS.any { normalized.contains(it) }
        val hasQuestion = QUESTION_MARKS.any { normalized.contains(it) } ||
            normalized.endsWith("？") || normalized.endsWith("?")

        // 2. 纯提问：带疑问标记、又没有动作词
        if (hasQuestion && !hasAction) {
            return Intake.Chat(text = raw, reason = "这是一句提问，不需要动手机")
        }

        // 3. 没有动作词：当成聊天（包括"我的手机好卡"这种抱怨）
        if (!hasAction) {
            return Intake.Chat(text = raw, reason = "没识别出要做的动作，先当作聊天")
        }

        // 4. 指代无前文：问清楚
        val pronoun = PRONOUNS.firstOrNull { normalized.contains(it) }
        if (pronoun != null && context.lastTaskGoal.isNullOrBlank() && !hasConcreteObject(normalized)) {
            return Intake.Clarify(
                question = "你说的「$pronoun」是指哪一样？",
                options = listOfNotNull(context.screenTitle, context.lastTaskGoal).ifEmpty {
                    listOf("就是屏幕上现在这个", "我刚才说的那个")
                },
                reason = "句子里有指代，但没有可参照的上文",
            )
        }

        val goals = splitChain(normalized)

        // 5. 多件事：按顺序做
        if (goals.size >= 2) {
            return Intake.Chain(
                goals = goals,
                reason = "这句里有 ${goals.size} 件事，我按顺序做",
            )
        }

        // 6. 单件任务
        val confirmation = needsConfirmation(normalized)
        return Intake.Task(
            goal = raw,
            needsConfirmation = confirmation,
            confidence = confidenceOf(normalized, hasQuestion),
            reason = when {
                confirmation && hasMoney(normalized) -> "涉及钱，动手之前我先问你一次"
                confirmation && hasDestructive(normalized) -> "这一步不好撤回，动手之前我先问你一次"
                hasQuestion -> "这是一句请求，不是在提问"
                else -> "识别为一件要去手机里做的事"
            },
        )
    }

    /** 只有"停"这一类命令。供上层在任务运行中直接调用。 */
    fun isStop(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        return STOP_WORDS.any { word ->
            n == word || n == "${word}吧" || n == "${word}了" ||
                // 「停一下」「先停下」也算 —— 多出来的字最多 3 个，再多就已经是在说别的事了
                (n.startsWith(word) && n.length - word.length <= 3)
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun normalize(text: String): String =
        text.trim()
            .replace(Regex("""\s+"""), " ")
            .removeSuffix("？").removeSuffix("?").removeSuffix("。").removeSuffix("！")
            .trim()

    private fun hasMoney(text: String): Boolean = MONEY_WORDS.any { text.contains(it) }

    private fun hasDestructive(text: String): Boolean = DESTRUCTIVE_WORDS.any { text.contains(it) }

    private fun needsConfirmation(text: String): Boolean = hasMoney(text) || hasDestructive(text)

    /** 宾语具体：句子里出现了已知应用名或明确的文件/联系人样式。 */
    private fun hasConcreteObject(text: String): Boolean {
        if (KNOWN_APPS.any { text.contains(it, ignoreCase = true) }) return true
        // 带引号、带冒号、带路径：说明用户点明了对象
        if (text.contains("：") || text.contains(":")) return true
        if (text.contains("/") || text.contains(".txt") || text.contains(".pdf")) return true
        return false
    }

    /**
     * 拆任务链。
     *
     * 只在**连接词两侧都有内容**时才拆 —— "然后呢"这种单独出现不算。
     */
    private fun splitChain(text: String): List<String> {
        var working = text
        for (connector in CONNECTORS) {
            working = working.replace(connector, "\u0001")
        }
        return working.split("\u0001")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length >= 2 }
    }

    /**
     * 置信度。它不是概率，只是"这条分流有多像真的"的相对刻度。
     *
     * 低于 [LOW_CONFIDENCE] 时上层应当先反问，而不是直接开跑。
     */
    private fun confidenceOf(text: String, hasQuestion: Boolean): Double {
        var score = 0.55
        if (REQUEST_MARKERS.any { text.startsWith(it) || text.contains(it) }) score += 0.2
        if (KNOWN_APPS.any { text.contains(it, ignoreCase = true) }) score += 0.15
        if (text.length in 6..120) score += 0.05
        if (hasQuestion) score -= 0.1
        return score.coerceIn(0.0, 1.0)
    }

    /** 低于这个值就该先反问一句。 */
    const val LOW_CONFIDENCE = 0.5
}
