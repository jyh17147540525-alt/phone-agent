package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 意图判定的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的核心是**判错的代价不对称**
 * ═══════════════════════════════════════════════════════════════
 *
 *  - 该 Act 判成 Chat：用户再说一遍，损失一轮对话
 *  - 该 Chat 判成 Act：**助理真的去操作了用户的手机**，不可撤销
 *
 * ⇒ 所以测试的重点不是"能不能认出来要办事"，而是
 *   **"像提问的那些绝对不能判成要动手"**。
 */
class IntentClassifierTest {

    // ═══════════════════════════════════════════════════════════
    //  该判 Act 的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `明确的动作指令判为要动手`() {
        for (text in listOf(
            "打开微信",
            "帮我打开设置",
            "点击确定按钮",
            "输入 hello world",
            "发送给张三",
            "把亮度调成 50",
            "回桌面",
        )) {
            val d = IntentClassifier.classify(text)
            assertEquals("「$text」应当判为要动手，理由：${d.reason}", TurnIntent.Act, d.intent)
        }
    }

    @Test
    fun `礼貌的祈使句必须判为要动手`() {
        // ★ 这是中文里最常见的形态，也是最容易误判的：
        //   「能不能帮我打开微信」含提问标记「能不能」，
        //   但用户的意思明明是"请打开"。
        val d = IntentClassifier.classify("能不能帮我打开微信")

        assertEquals("礼貌请求应判为要动手，理由：${d.reason}", TurnIntent.Act, d.intent)
        assertEquals("这是明确请求，置信度应为高", IntentDecision.Confidence.HIGH, d.confidence)
    }

    @Test
    fun `麻烦你这类敬语判为要动手`() {
        val d = IntentClassifier.classify("麻烦你把屏幕亮度调到最低")
        assertEquals("理由：${d.reason}", TurnIntent.Act, d.intent)
    }

    // ═══════════════════════════════════════════════════════════
    //  该判 Chat 的 —— 这部分最重要
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `问方法时必须判为纯对话而不是真去操作`() {
        // ★★ 最关键的一条。「微信怎么撤回消息」含动作词「撤回」的概念，
        //    但它是**问方法**。判成 Act 的后果是助理真去点了撤回 ——
        //    而消息发出去就撤不回来了。
        for (text in listOf(
            "微信怎么撤回消息",
            "如何打开开发者选项",
            "为什么我的设置里没有这个",
            "点击和长按有什么区别",
            "输入法哪个好用",
        )) {
            val d = IntentClassifier.classify(text)
            assertEquals(
                "「$text」是提问，不能判成要动手（理由：${d.reason}）",
                TurnIntent.Chat,
                d.intent,
            )
        }
    }

    @Test
    fun `纯闲聊判为纯对话`() {
        for (text in listOf(
            "今天几号",
            "你好",
            "讲个笑话",
            "介绍一下你自己",
        )) {
            assertEquals(
                "「$text」应当是纯对话",
                TurnIntent.Chat,
                IntentClassifier.classify(text).intent,
            )
        }
    }

    @Test
    fun `空输入判为纯对话`() {
        val d = IntentClassifier.classify("   ")
        assertEquals(TurnIntent.Chat, d.intent)
        assertTrue("空输入的理由不能为空", d.reason.isNotBlank())
    }

    // ═══════════════════════════════════════════════════════════
    //  拿不准的情况
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同时有动作词和提问标记时按保守原则判纯对话且置信度低`() {
        // "能不能帮我"已被明确请求层拦下，这里测**没有**敬语的那种。
        val d = IntentClassifier.classify("打开微信的方法是什么")

        assertEquals("拿不准时必须判纯对话", TurnIntent.Chat, d.intent)
        assertEquals("拿不准时置信度必须是低", IntentDecision.Confidence.LOW, d.confidence)
        assertTrue("理由里要点出两个线索：${d.reason}",
            d.reason.contains("打开") && d.reason.contains("什么"))
    }

    @Test
    fun `低置信度的纯对话应当主动提议帮忙`() {
        val low = IntentClassifier.classify("打开微信的方法是什么")
        assertTrue("拿不准时主动问一句「要不要我帮你」，比默默答完更好",
            IntentClassifier.shouldOfferHelp(low))
    }

    @Test
    fun `高置信度的纯对话不该提议帮忙`() {
        // ★ 问「今天几号」时补一句「要不要我帮你打开日历」就是骚扰。
        //   提议的价值来自稀缺。
        val high = IntentClassifier.classify("今天几号")
        assertFalse("确定的纯对话不该多嘴", IntentClassifier.shouldOfferHelp(high))
    }

    @Test
    fun `判成要动手时绝不提议帮忙`() {
        val act = IntentClassifier.classify("打开微信")
        assertFalse("已经在动手了，还问要不要帮忙是自相矛盾",
            IntentClassifier.shouldOfferHelp(act))
    }

    // ═══════════════════════════════════════════════════════════
    //  结构约束
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每个判定都必须给出非空理由`() {
        // ⚠️ 一个说不出理由的判定无法复核 —— 用户问"你为什么动了我的手机"时，
        //    日志里必须有一句话可查。
        for (text in listOf("打开微信", "今天几号", "", "微信怎么撤回消息", "能不能帮我打开")) {
            val d = IntentClassifier.classify(text)
            assertTrue("「$text」的判定理由不能为空", d.reason.isNotBlank())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  英文输入 —— 2026-10-24 真机发现的缺陷
    // ═══════════════════════════════════════════════════════════

    /**
     * 英文指令必须被判为要动手。
     *
     * ★ 这是真机上抓到的：用户输 `open settings`，被判成**纯对话**，
     *   助理回"我打不开" —— 而它明明能打开。
     *
     * 根因：动作词表最初只有中文。这不是"少数派需求" ——
     * 应用名、菜单项、技术操作常是英文，开发者测试时用的也是英文。
     */
    @Test
    fun `英文动作指令判为要动手`() {
        for (text in listOf(
            "open settings",
            "Open Settings",
            "OPEN SETTINGS",
            "open wechat",
            "click the button",
            "type hello",
            "go home",
            "take a screenshot",
        )) {
            val d = IntentClassifier.classify(text)
            assertEquals("「$text」应当判为要动手，理由：${d.reason}", TurnIntent.Act, d.intent)
        }
    }

    @Test
    fun `大小写不影响判定`() {
        // ⚠️ 大小写敏感会让 "Open Settings" 漏判，
        //   而用户完全看不出是大小写的问题。
        val lower = IntentClassifier.classify("open settings")
        val upper = IntentClassifier.classify("OPEN SETTINGS")
        assertEquals("大小写不该改变判定结果", lower.intent, upper.intent)
    }

    @Test
    fun `英文提问仍判为纯对话`() {
        // ★ 加了英文动作词之后，英文提问也必须能被正确挡住。
        for (text in listOf(
            "how do i open settings",
            "what is the difference between tap and click",
            "can you explain how swipe works",
            "why is my screen locked",
        )) {
            assertEquals(
                "「$text」是英文提问，不能判成要动手",
                TurnIntent.Chat,
                IntentClassifier.classify(text).intent,
            )
        }
    }

    @Test
    fun `英文闲聊判为纯对话`() {
        for (text in listOf("hello", "what day is today", "tell me a joke")) {
            assertEquals("「$text」应当是纯对话", TurnIntent.Chat, IntentClassifier.classify(text).intent)
        }
    }

    /**
     * 执行命令类表达必须判为要动手。
     *
     * ★ 真机发现的第二个缺陷：用户输
     *   `run getprop ro.build.version.sdk`，被判成纯对话，
     *   于是助理开始解释"getprop 是什么"，而不是执行它。
     *
     * 这类表达在 root 模式下尤其常见。
     */
    @Test
    fun `执行命令类表达判为要动手`() {
        for (text in listOf(
            "run getprop ro.build.version.sdk",
            "execute this command",
            "shell ls /system",
            "getprop ro.product.model",
            "dumpsys battery",
            "执行 ls /sdcard",
            "跑一下 getprop",
        )) {
            val d = IntentClassifier.classify(text)
            assertEquals("「$text」应当判为要动手，理由：${d.reason}", TurnIntent.Act, d.intent)
        }
    }
}
