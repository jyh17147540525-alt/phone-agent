package com.pocketagent.rootlogic

/**
 * 这一句话**要不要动手**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么默认是 [Chat]（纯对话）
 * ═══════════════════════════════════════════════════════════════
 *
 * 改之前的做法是**每一次对话都把全部 11 个工具挂上去**（见 `ChatPort` 调
 * `agentToolsForChat` 那行）。那带来三个真问题：
 *
 *  1. **模型被工具带偏**：问「今天几号」，它可能先去 `screen_read` 看一眼
 *     再答 —— 用户只是随口一问，却付出了读屏的代价和延迟。
 *  2. **每轮都在为用不到的东西付费**：工具的 JSON Schema 每次请求都要发，
 *     11 个工具的 schema 是几千 token。纯聊天根本不需要。
 *  3. **安全面被无必要地放大**：能调工具，就有调错的可能；
 *     而纯聊天**零风险**。默认收起来，风险面自然最小。
 *
 * ⇒ 默认 [Chat]，只有**判断出这句话要办事**才升级成 [Act]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 判错的代价是**不对称**的
 * ═══════════════════════════════════════════════════════════════
 *
 *  - 该 [Act] 却判成 [Chat]：用户说「打开微信」，助理说「我打不开」——
 *    用户会再说一遍，损失一轮。
 *  - 该 [Chat] 却判成 [Act]：用户问「微信怎么撤回消息」，助理**真的去操作了**——
 *    这是**动了用户的手机**，不可撤销。
 *
 * ⇒ 所以判定必须**偏保守**：拿不准就当 [Chat]。
 *   代价只是多一轮对话，而不是多一次误操作。
 */
enum class TurnIntent {

    /** 纯对话：不挂任何工具，模型只能回文字。 */
    Chat,

    /**
     * 要办事：挂工具，交给 agent 循环执行。
     *
     * @param reason 为什么判成要办事 —— 写进日志，也是排查"它为什么动了手"的唯一线索
     */
    Act;
}

/**
 * 意图判定的结论。
 *
 * @param intent 判定结果
 * @param reason 判据（人可读）。**必须非空** —— 一个说不出理由的判定无法复核
 * @param confidence 有多确定。低置信度的 [TurnIntent.Act] 应当先问一句再动手
 */
data class IntentDecision(
    val intent: TurnIntent,
    val reason: String,
    val confidence: Confidence,
) {
    enum class Confidence { HIGH, LOW }
}

/**
 * 意图判定 —— **纯规则，不调模型**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用规则而不是"让模型自己判断"
 * ═══════════════════════════════════════════════════════════════
 *
 * 让模型判断"这句话要不要动手"看起来更聪明，但有三个致命问题：
 *
 *  1. **它需要看到工具清单才知道能做什么** —— 而我们正是想省掉这一步。
 *     鸡生蛋。
 *  2. **多一次模型调用 = 多一次成本 + 多一次延迟**，纯聊天本该是零延迟的。
 *  3. **不可离线测试**。规则判错了可以写一条测试钉住；模型判错了只能靠运气。
 *
 * ⚠️ 规则一定不够聪明 —— 这是**已知且接受**的取舍。所以设计上做了两层兜底：
 *   - 判成 [TurnIntent.Chat] 时，系统提示词里明确说「如果用户是在让你做事，
 *     就告诉他『我需要动手，请再说一次让我执行』」→ 用户一句话就能升级
 *   - 判成 [TurnIntent.Act] 且置信度低时，先问一句再动手
 */
object IntentClassifier {

    /**
     * 明确的**动作动词** —— 出现在句首或"帮我/给我"之后时，几乎一定是要办事。
     *
     * ⚠️ 刻意**不含**「看」「说」「写」这类多义动词：
     *    「你觉得这个怎么样」是问意见，「写一段代码」是让我输出文字 ——
     *    都不该动手。误判成 Act 的代价是动了用户的手机。
     */
    private val ACTION_VERBS = listOf(
        "打开", "关闭", "关掉", "启动", "退出", "卸载", "安装",
        "点击", "点一下", "点开", "长按", "双击", "滑动", "滑到", "拖",
        "输入", "打字", "填入", "填写", "粘贴",
        "发送", "发给", "回复", "转发", "分享", "发一条", "发个",
        "设置", "改成", "调成", "切换到", "开启", "启用", "禁用",
        "删除", "删掉", "清空", "清除",
        "下载", "上传", "保存", "截图", "录屏",
        "拨打", "打电话", "拨号",
        "解锁", "锁屏", "返回", "回桌面", "回主页",
        "重启", "关机",
        "帮我", "替我", "给我",

        // ══════════════════════════════════════════════════════
        //  英文动作词
        // ══════════════════════════════════════════════════════
        // ★ 2026-10-24 真机发现的缺陷：此前只有中文，于是
        //   用户输 "open settings" 被判成**纯对话**，助理回
        //   "我打不开" —— 而它明明能打开。
        //
        //   为什么这不是"少数派需求"：本项目大量用户会混用中英文
        //   （应用名、菜单项、技术操作常是英文），而且开发者在测试时
        //   用的就是英文。判错的表现是"这个助手连 open settings 都做不到"。
        //
        // ⚠️ 用**小写**匹配（见 `classify` 里的 lowercase）。
        "open", "close", "launch", "start", "exit", "quit",
        "tap", "click", "press", "long press", "swipe", "scroll", "drag",
        "type", "enter", "input", "paste",
        "send", "reply", "forward", "share",
        "set", "change", "switch", "toggle", "enable", "disable",
        "delete", "remove", "clear",
        "download", "upload", "save", "screenshot",
        "call", "dial",
        "unlock", "lock", "go back", "go home", "home",
        "reboot", "restart", "shutdown",
        "turn on", "turn off",

        // ══════════════════════════════════════════════════════
        //  执行命令类（root / shell）
        // ══════════════════════════════════════════════════════
        // ★ 2026-10-24 真机发现：用户输 "run getprop ro.build.version.sdk"
        //   被判成**纯对话** —— 因为 "run" 不在词表里。
        //
        //   这类表达在 root 模式下尤其常见（用户想让我执行 shell 命令），
        //   而判错的表现是"助理跟你聊起 getprop 是什么"。
        "run", "execute", "exec", "shell", "command", "cmd",
        "getprop", "setprop", "dumpsys", "settings get", "settings put",
        "pm list", "pm install", "pm uninstall",
        "执行", "跑一下", "跑个", "命令", "终端",
    )

    /**
     * **提问标记** —— 出现这些时，即使句子里有动作动词，也倾向于判成纯对话。
     *
     * ★ 这是"保守"的具体实现：「微信怎么撤回消息」含「撤回」，
     *   但它是**问方法**，不是让我去撤回。
     */
    private val QUESTION_MARKERS = listOf(
        "怎么", "如何", "怎样", "为什么", "什么", "哪里", "哪个", "哪些",
        "能不能", "可不可以", "是否可以", "会不会", "是不是", "有没有",
        "吗", "呢", "？", "?",
        "是什么", "什么意思", "解释", "讲讲", "说说", "介绍一下",
        "区别", "原理", "原因",

        // ★ 英文提问标记（同 ACTION_VERBS 的理由：混用中英文是常态）
        "how", "what", "why", "when", "where", "which", "who",
        "can i", "can you", "could you", "should i", "is it", "are you",
        "explain", "difference", "meaning",
    )

    /**
     * **明确要求动手**的说法 —— 出现即判 Act，**无视提问标记**。
     *
     * ★ 这一层的存在是因为上面的规则会把「能不能帮我打开微信」判成提问，
     *   而用户的意思明明是"请打开"。这类"礼貌的祈使句"在中文里极常见。
     */
    private val EXPLICIT_REQUESTS = listOf(
        "帮我", "替我", "给我", "麻烦你", "请你",
        "去打开", "去点", "去发", "去设置", "去下载",
        "现在去", "马上", "立刻",
    )

    /**
     * 判定一句话的意图。
     *
     * @param text 用户这一轮的原话
     */
    fun classify(text: String): IntentDecision {
        val raw = text.trim()

        // 空输入 ⇒ 纯对话（没有可执行的东西）
        if (raw.isEmpty()) {
            return IntentDecision(TurnIntent.Chat, "空输入，没有要执行的内容", IntentDecision.Confidence.HIGH)
        }

        // ★ 匹配用小写副本 —— 词表里英文全是小写，而用户会写
        //   "Open Settings" / "OPEN". 大小写敏感会让这些全部漏掉，
        //   而漏掉的表现是"助理不肯动手"，用户完全看不出是大小写的问题。
        val haystack = raw.lowercase()

        // ── 第一层：明确要求动手 ⇒ 直接 Act ──────────────────
        // ★ 必须在提问标记之前判：否则「能不能帮我打开微信」会被判成提问。
        val explicit = EXPLICIT_REQUESTS.firstOrNull { haystack.contains(it) }
        if (explicit != null) {
            return IntentDecision(
                TurnIntent.Act,
                "出现明确请求「$explicit」，这是祈使句而不是提问",
                IntentDecision.Confidence.HIGH,
            )
        }

        val hasVerb = ACTION_VERBS.firstOrNull { haystack.contains(it) }
        val hasQuestion = QUESTION_MARKERS.firstOrNull { haystack.contains(it) }

        // ── 第二层：有动作词 + 无提问标记 ⇒ Act ──────────────
        if (hasVerb != null && hasQuestion == null) {
            return IntentDecision(
                TurnIntent.Act,
                "出现动作词「$hasVerb」且没有提问标记",
                IntentDecision.Confidence.HIGH,
            )
        }

        // ── 第三层：有动作词但也像提问 ⇒ 拿不准 ⇒ Chat ────────
        // ★ 这里刻意判 Chat（保守）。代价是多一轮对话；
        //   判 Act 的代价是**可能动了用户的手机**。
        if (hasVerb != null && hasQuestion != null) {
            return IntentDecision(
                TurnIntent.Chat,
                "同时出现动作词「$hasVerb」与提问标记「$hasQuestion」，" +
                    "更像是询问方法而不是要我动手 —— 按保守原则判为纯对话",
                IntentDecision.Confidence.LOW,
            )
        }

        // ── 第四层：没有动作词 ⇒ Chat ────────────────────────
        return IntentDecision(
            TurnIntent.Chat,
            if (hasQuestion != null) "是提问，且没有动作词" else "没有动作词，是普通对话",
            IntentDecision.Confidence.HIGH,
        )
    }

    /**
     * 判成纯对话时，**该不该顺带提一句"你可以让我来做"**。
     *
     * ★ 用户说「微信怎么撤回消息」时，除了回答方法，还值得补一句
     *   「要不要我帮你撤回？」—— 但**只在低置信度时补**。
     *   高置信度的纯对话（"今天几号"）补这句话就是骚扰。
     */
    fun shouldOfferHelp(decision: IntentDecision): Boolean =
        decision.intent == TurnIntent.Chat &&
            decision.confidence == IntentDecision.Confidence.LOW
}
