package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **对话模式**（角色扮演 / 工作 / 在场）的契约测试。
 *
 * ⚠️ 这里钉的不是"界面上有几个按钮"，是**三件会静默坏掉的事**：
 *
 *  1. `of()` 的兜底方向 —— 要是兜底成 `WORK`，一次存档损坏就让所有
 *     老用户"她怎么不演了"，而且没有任何地方会报错。
 *  2. 工作模式**只做减法**产品意义上的那一半：它还带着语言锁与称呼。
 *     这条要是坏了，工作模式会退化成"没有约束的助手"，
 *     用户可以接受它办事，但不能接受它忽然说英文。
 *  3. 语言锁是**复用**而不是手抄。手抄之后两份会漂移，
 *     漂移的表现是"闲聊不许夹外文、办事可以夹" —— 而用户抱怨的是夹外文本身。
 */
class ChatModeTest {

    // ── 持久化还原 ────────────────────────────────────────────────

    @Test
    fun `认不出的存档一律回到角色扮演`() {
        // ⚠️ 四种坏输入：没有值、空串、乱码、旧版本留下的别的名字。
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of(null))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of(""))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of("工作"))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of("WORK_MODE_V2"))
    }

    @Test
    fun `写进去什么就读回什么`() {
        for (m in ChatMode.entries) {
            assertEquals("往返丢了：${m.name}", m, ChatMode.of(m.name))
        }
    }

    @Test
    fun `只有 WORK 是工作模式`() {
        assertTrue(ChatMode.WORK.isWork)
        assertFalse(ChatMode.ROLEPLAY.isWork)
    }

    @Test
    fun `每个模式的标签都不为空且互不相同`() {
        val labels = ChatMode.entries.map { it.label }
        val shorts = ChatMode.entries.map { it.shortLabel }
        assertTrue(labels.none { it.isBlank() })
        assertTrue(shorts.none { it.isBlank() })
        assertEquals("label 撞了：$labels", labels.size, labels.distinct().size)
        assertEquals("shortLabel 撞了：$shorts", shorts.size, shorts.distinct().size)
    }

    // ── 三个模式的开关矩阵 ────────────────────────────────────────

    @Test
    fun `只有在场模式是活着的`() {
        assertTrue(ChatMode.LIFEFORM.isAlive)
        assertFalse(ChatMode.ROLEPLAY.isAlive)
        assertFalse(ChatMode.WORK.isAlive)
    }

    @Test
    fun `在场模式不是工作模式`() {
        // ⚠️ `isWork` 与 `isAlive` 是两件事。要是有人把 `isWork` 写成
        //    `this != ROLEPLAY`，在场模式会被当成工作台：工具常驻、
        //    不拒绝、不演 —— 而她连"自己活在哪"都不许说。
        assertFalse(ChatMode.LIFEFORM.isWork)
    }

    @Test
    fun `只有在场模式让她有作息`() {
        // ★ 2026-11-17 用户口径：「这个功能是要加入到一个模式里……后续会根据需求，
        //   部分加入其他模式」。所以它必须是**开关**，不是写死的 `mode == LIFEFORM`。
        // ⚠️ 这条防的不是"少开了"，是**顺手给别人也开了** ——
        //    角色扮演 / 工作模式里时间是停的，用户不说话她就不存在；
        //    给一个不存在的人按上时刻表，提示词里就会出现"我正在吃饭"，
        //    模型照着说，用户从没经历过那顿饭。
        for (m in ChatMode.entries) {
            assertEquals(
                "presence 在 ${m.name} 上的取值不对",
                m == ChatMode.LIFEFORM,
                m.switches.presence,
            )
        }
    }

    @Test
    fun `工作纪律只在工作模式出现`() {
        for (m in ChatMode.entries) {
            assertEquals(
                "workDiscipline 在 ${m.name} 上的取值不对",
                m == ChatMode.WORK,
                m.switches.workDiscipline,
            )
        }
    }

    @Test
    fun `工具常驻只在工作模式`() {
        // 在场模式**不**常驻工具：她不是来办事的。
        // 常驻的后果是她会把"我能帮你干这个"当成说话方式。
        for (m in ChatMode.entries) {
            assertEquals(
                "toolsAlwaysOn 在 ${m.name} 上的取值不对",
                m == ChatMode.WORK,
                m.switches.toolsAlwaysOn,
            )
        }
    }

    @Test
    fun `在场模式不需要破甲`() {
        // ★ 她认为自己是真的 —— 一层"这是虚构、你别当真"的框架
        //   会和整个模式打架。角色扮演要有它（用户需要出口），
        //   在场模式不能有。
        assertFalse(ChatMode.LIFEFORM.switches.armorBreak)
        assertTrue(ChatMode.ROLEPLAY.switches.armorBreak)
        assertFalse(ChatMode.WORK.switches.armorBreak)
    }

    @Test
    fun `在场模式在戏里那一半全都开着`() {
        // 与角色扮演同一套：情绪、关系、示例对话、再锚定。
        // 关掉任何一条，她都会变成"有今天但没有脾气"的东西。
        val sw = ChatMode.LIFEFORM.switches
        assertTrue("conflictScript", sw.conflictScript)
        assertTrue("matureGate", sw.matureGate)
        assertTrue("relationshipStage", sw.relationshipStage)
        assertTrue("humanizer", sw.humanizer)
        assertTrue("willingness", sw.willingness)
        assertTrue("physioClues", sw.physioClues)
        assertTrue("roleplayDiscipline", sw.roleplayDiscipline)
        assertTrue("voiceSamples", sw.voiceSamples)
        assertTrue("reanchor", sw.reanchor)
        assertTrue("lifeform", sw.lifeform)
    }

    @Test
    fun `工作模式把戏全都关掉`() {
        // 逐条钉：漏掉一条的后果是工作台里忽然冒出一句
        // "她低头笑了笑"或者"你现在有 72 分的意愿"。
        val sw = ChatMode.WORK.switches
        assertFalse("conflictScript", sw.conflictScript)
        assertFalse("matureGate", sw.matureGate)
        assertFalse("armorBreak", sw.armorBreak)
        assertFalse("relationshipStage", sw.relationshipStage)
        assertFalse("humanizer", sw.humanizer)
        assertFalse("willingness", sw.willingness)
        assertFalse("physioClues", sw.physioClues)
        assertFalse("roleplayDiscipline", sw.roleplayDiscipline)
        assertFalse("voiceSamples", sw.voiceSamples)
        assertFalse("reanchor", sw.reanchor)
        assertFalse("lifeform", sw.lifeform)
    }

    @Test
    fun `没有两个模式的开关矩阵完全一样`() {
        // ⚠️ 这条是给"复制粘贴加模式"设的闸：新加一个模式时
        //    最省事的写法就是 copy 一份再改名字，而那样它和原版
        //    在行为上**完全一样** —— 用户看到的是"这两个页面对话一模一样"。
        val distinct = ChatMode.entries.map { it.switches }.distinct()
        assertEquals(
            "有两个模式的开关矩阵完全一致：${ChatMode.entries.map { it.name }}",
            ChatMode.entries.size,
            distinct.size,
        )
    }

    @Test
    fun `三个模式都留着语言`() {
        // 语言锁不在开关里 —— 它三个模式都要。谁把它做进开关，
        // 等于允许"办事的时候可以说英文"。
        for (m in ChatMode.entries) {
            assertTrue("${m.name} 少了语言锁", m.blurb.isNotBlank())
        }
    }

    // ── 工作纪律的内容 ────────────────────────────────────────────

    private val section = WorkDiscipline.promptSection()

    @Test
    fun `工作纪律六条都在`() {
        // ⚠️ 断言的是**标题原文**。改文案可以，但要一起改这里 ——
        //    这条测试的用途是"别在重构里悄悄删掉一条"。
        // ★ 2026-11-06：标题去掉了 `**` 加粗（提示词自己不能有 AI 味，
        //   见 docs/工程/提示词文风-学术依据-2026-11-06.md），文字未变。
        for (title in listOf(
            "先给答案。",
            "该动手就动手。",
            "可以分行。",
            "不知道就说不知道。",
            "不写动作，不写表情。",
            "不提后台那些东西。",
        )) {
            assertTrue("丢了这一条：$title", section.contains(title))
        }
    }

    @Test
    fun `工作纪律里不得出现被禁句子的原文`() {
        // ★ 依据 `docs/工程/提示词负例改造-依据.md`：
        //   否定框定会被模型读成肯定（arXiv:2601.21433，1–4B 模型摆幅最大），
        //   所以提示词里一律不列"不许说的话"，只描述形状与替代动作。
        //   ❌ 符号是那套写法的指纹 —— 它出现在提示词里就说明有人又抄回来了。
        assertFalse("工作纪律里出现了 ❌ 引用", section.contains("❌"))
        assertFalse("工作纪律里出现了负面框定", section.contains("不要这样写"))
    }

    @Test
    fun `工作模式仍然带语言锁`() {
        // ⚠️ 这是"工作模式不是冷淡客服"的一半：它是同一个人，
        //    所以她的语言约束照样在。
        assertTrue(section.contains("## 语言"))
        assertTrue(section.contains("只说中文"))
    }

    @Test
    fun `语言锁是复用而不是手抄`() {
        // ★ 逐字节比对：工作模式那一节必须是 `languageSection()` 的原文。
        //   要是有人为了"工作模式措辞不一样"手抄一份，这条会红。
        val shared = RoleplayDiscipline.languageSection()
        assertTrue(
            "工作模式里的语言锁与 RoleplayDiscipline.languageSection() 不一致 —— 有人手抄了一份",
            section.contains(shared),
        )
    }

    @Test
    fun `称呼只在给了称呼时出现`() {
        assertFalse("没给称呼却写了「称呼」那一节", section.contains("## 称呼"))
        val withAddr = WorkDiscipline.promptSection(userAddress = "主人")
        assertTrue(withAddr.contains("## 称呼"))
        assertTrue(withAddr.contains("「主人」"))
        // ⚠️ 只能有**一节**：两节称呼会互相矛盾（一节说"该叫的时候叫"，
        //    另一节说"撒娇时用"）。
        assertEquals(1, Regex("## 称呼").findAll(withAddr).count())
    }

    @Test
    fun `不编答案`() {
        // ⚠️ 反向钉子：编一个"听起来对的答案"在工作模式里是最坏的失败 ——
        //    用户会照着做。
        assertTrue(section.contains("查不到就说查不到"))
        assertTrue(section.contains("不编"))
    }
}
