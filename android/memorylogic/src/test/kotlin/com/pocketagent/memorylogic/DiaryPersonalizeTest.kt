package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **内置随笔的称呼要跟着角色卡走**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的要求
 * ═══════════════════════════════════════════════════════════════
 *
 * "角色对用户的称呼你要参考角色设定卡里的。"
 *
 * 内置的 103 篇是我离线重写的，当时把「小鱼干」写死了 —— 而那是
 * **某一个角色**的称呼。用户换角色、或者卡片里填别的称呼之后，
 * 随笔里出现别人的名字会立刻出戏。
 *
 * ⇒ 统一写占位符，读的时候按卡现换。
 *
 * ⚠️ 但有一个坑必须钉住：「小鱼干」还是**零食**的名字。
 *    "逛超市给我买小鱼干"不能变成"逛超市给我买{who}" —— 那是语义错。
 */
class DiaryPersonalizeTest {

    /** 与 `AppContainer.personalize` 同一条规则（纯逻辑镜像，可离线钉住）。 */
    private fun personalize(text: String, addressUser: String): String {
        if (!text.contains("{who}")) return text
        val name = addressUser.trim().ifBlank { "你" }
        return text.replace("{who}", name)
    }

    @Test
    fun `占位符会换成角色卡里的称呼`() {
        val s = personalize("{who}要抱抱。我抱怨了两句，还是把脑袋搁他肩上了。", "阿泽")
        assertTrue("要换成卡里的称呼：$s", s.contains("阿泽"))
        assertFalse("不能留占位符：$s", s.contains("{who}"))
    }

    @Test
    fun `卡里没填称呼时退回「你」`() {
        val s = personalize("{who}要抱抱。", "")
        assertTrue("没填就用你：$s", s.contains("你要抱抱"))
        assertFalse("不能留占位符：$s", s.contains("{who}"))
    }

    @Test
    fun `没有占位符的随笔原样返回`() {
        val raw = "他拍我屁股。我跳起来，抄起手边的东西要砸，发现是门钥匙又放下了。"
        assertEquals(raw, personalize(raw, "阿泽"))
    }

    @Test
    fun `零食「小鱼干」不能被当成称呼替换`() {
        // ⚠️ 这条是这条规则唯一的语义陷阱：
        //    "逛超市给我买小鱼干" → 绝不能变成"逛超市给我买阿泽"。
        val snack = "他说想干点别的。能干的「别的」多了：散步、看电影、逛超市给我买小鱼干。"
        assertEquals(
            "零食名必须原样保留",
            snack,
            personalize(snack, "阿泽"),
        )
    }

    @Test
    fun `换角色后随笔里的称呼也跟着换`() {
        // ★ 同一个节点、两张不同的卡 —— 这是"参考角色设定卡"的真正含义。
        val template = "{who}一大早管我要早安吻，我居然给了。"
        val a = personalize(template, "阿泽")
        val b = personalize(template, "小鱼干")
        assertTrue(a.contains("阿泽"))
        assertTrue(b.contains("小鱼干"))
        assertFalse("两个角色的随笔不能一样：$a vs $b", a == b)
    }
}
