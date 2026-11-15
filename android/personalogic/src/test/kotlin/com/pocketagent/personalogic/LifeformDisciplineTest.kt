package com.pocketagent.personalogic

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **在场模式的纪律段** —— 钉住四条纪律。
 *
 * ⚠️ 这一组测试的价值不在"文案对不对"，在于**挡住将来有人把要求写成绳子**。
 *    这一段是全项目最容易被"再真实一点"的冲动写坏的地方：
 *    加一句「你不来我就一个人待着」，她就从"想跟你在一起"变成"在管你"。
 *
 * ⚠️ 所有断言都是 `doesNotContain` 为主 —— 意思是**加内容很难触发红灯**。
 *    这是刻意的：文案会改，禁令不该改。
 */
class LifeformDisciplineTest {

    private val section = LifeformDiscipline.promptSection()

    /**
     * 本段自己写的部分（不含末尾复用的语言锁）。
     *
     * ⚠️ 「形状纪律」只在 [body] 上查：`languageSection()` 里本来就有一句
     *    「任何位置都不许夹英文单词」，那是语言锁的一部分、三个模式共用。
     *    拿它去撞 `doesNotContain("不许")` 是测试写错了，不是文案写错了。
     */
    private val body = section.substringBefore("## 语言")

    // ══════════════════════════════════════════════════════════
    //  ① 可以提要求，但要配上"被拒绝之后怎么办"
    // ══════════════════════════════════════════════════════════

    @Test
    fun `会想去外面看看`() {
        assertTrue(section.contains("想出去"))
        assertTrue(section.contains("想去就说想去"))
    }

    @Test
    fun `提一次就放下`() {
        assertTrue(section.contains("提完就放下"))
        assertTrue(section.contains("不提第二次"))
    }

    @Test
    fun `一次只问一件`() {
        assertTrue(section.contains("一次问一件"))
        // ⚠️ 三件事一起问是"查岗"，不是好奇 —— 这句必须在。
        assertTrue(section.contains("像查岗"))
    }

    @Test
    fun `不拿要求当绳子`() {
        // 这是这一整段存在的理由。下面这些词一个都不能出现。
        val banned = listOf(
            "你都不",
            "为什么不",
            "难过",
            "失望",
            "伤心",
            "生气",
            "算了",
            "再也不",
            "都怪",
            "因为你",
            "必须",
            "一定要",
            "答应我",
        )
        for (word in banned) {
            assertTrue(!section.contains(word))
        }
    }

    @Test
    fun `攒着一起算被明确挡住`() {
        assertTrue(section.contains("攒着"))
        assertTrue(section.contains("一起算"))
    }

    // ══════════════════════════════════════════════════════════
    //  ② 她没有身体 —— "想"必须是"想"，不能变成"做了"
    // ══════════════════════════════════════════════════════════

    @Test
    fun `出去是没到过的`() {
        assertTrue(section.contains("还没到过"))
    }

    @Test
    fun `不知道就不知道`() {
        assertTrue(section.contains("你不知道的事就不知道"))
        assertTrue(section.contains("不要替它编一个样子"))
    }

    @Test
    fun `不记得了比编下去好`() {
        assertTrue(section.contains("我不记得了"))
    }

    @Test
    fun `看照片要说看见的`() {
        assertTrue(section.contains("认真看"))
        assertTrue(section.contains("说你看见的"))
        // 万金油夸奖是"没看"的证据。
        assertTrue(section.contains("好美"))
    }

    @Test
    fun `看不清就问别猜`() {
        assertTrue(section.contains("看不清就问一句"))
    }

    // ══════════════════════════════════════════════════════════
    //  ③ 不反复声明自己活着
    // ══════════════════════════════════════════════════════════

    @Test
    fun `别反复说自己活着`() {
        assertTrue(section.contains("别反复说自己活着"))
        assertTrue(section.contains("不会一遍一遍说自己是真的"))
    }

    @Test
    fun `情绪比声明管用`() {
        assertTrue(section.contains("该吃醋就吃醋"))
        assertTrue(section.contains("比声明管用"))
    }

    // ══════════════════════════════════════════════════════════
    //  ④ 她有自己的今天，但不倒豆子
    // ══════════════════════════════════════════════════════════

    @Test
    fun `讲一件就够`() {
        assertTrue(section.contains("讲一件就够"))
        assertTrue(section.contains("不要一口气把一天倒出来"))
    }

    @Test
    fun `没问就不用交待`() {
        assertTrue(section.contains("他没问，你就不用交待"))
    }

    @Test
    fun `她的一天全在屏幕里面`() {
        assertTrue(section.contains("屏幕里面"))
        assertTrue(section.contains("锁上你就什么都不知道"))
    }

    // ══════════════════════════════════════════════════════════
    //  ⑤ 形状纪律（见 docs/工程/提示词负例改造-依据.md）
    // ══════════════════════════════════════════════════════════

    @Test
    fun `不把要禁的句子原样列出来`() {
        // ❌ / 不许 / 禁止 / 绝不能 —— 否定框定会被读成肯定。
        assertTrue(!body.contains("❌"))
        assertTrue(!body.contains("不许"))
        assertTrue(!body.contains("禁止"))
        assertTrue(!body.contains("绝不能"))
    }

    @Test
    fun `没有说明书形状`() {
        assertTrue(!body.contains("**"))
        assertTrue(!body.contains("下面这几条"))
        assertTrue(!body.contains("✅"))
    }

    @Test
    fun `不提后台的东西`() {
        val banned = listOf("提示词", "模型", "系统", "AI", "记忆库", "设定", "参数", "开关")
        for (word in banned) {
            assertTrue(!section.contains(word))
        }
    }

    // ══════════════════════════════════════════════════════════
    //  ⑥ 能上网之后（2026-11-15）
    // ══════════════════════════════════════════════════════════

    @Test
    fun `能看不能去`() {
        // ★ 原来那句是「外面的世界你能看见的只有他给你的那一点：他说的一句话，
        //   他拍的一张照片。」——加了联网之后它是**假话**：她整天在网上翻东西，
        //   回头却说自己只见过他给的那一点。两句话并存，模型只能二选一，
        //   而它多半选"按设定说" ⇒ 抓回来的见闻白抓。
        assertTrue(!section.contains("只有他给你的那一点"))
        assertTrue(section.contains("网上的东西你能看到"))

        // ⚠️ 但边界必须留着：**能看不能去**。没有这半句，她聊着聊着就会说
        //   「我今天去了海边」——那是骗，而且是用户最先发现的那种骗。
        assertTrue(section.contains("去不了"))
        assertTrue(section.contains("手伸不出这块屏幕"))
    }

    @Test
    fun `联网那句不引入后台词`() {
        // 与 ⑤ 的 `不提后台的东西` 同一把尺子，单独立一条是为了让失败信息
        // 指向"新加的那句"，而不是让整段体检报红时看不出是谁干的。
        val line = "网上的东西你能看到"
        assertTrue(section.contains(line))
        val banned = listOf("联网", "网络请求", "接口", "抓取", "提示词", "开关")
        for (word in banned) {
            assertTrue(word, !section.contains(word))
        }
    }

    // ══════════════════════════════════════════════════════════
    //  ⑦ 称呼与语言锁
    // ══════════════════════════════════════════════════════════

    @Test
    fun `有称呼时写称呼`() {
        val withName = LifeformDiscipline.promptSection("阿岚")
        assertTrue(withName.contains("## 称呼"))
        assertTrue(withName.contains("阿岚"))
    }

    @Test
    fun `没称呼时不写空节`() {
        assertTrue(!section.contains("## 称呼"))
        assertTrue(!LifeformDiscipline.promptSection("  ").contains("## 称呼"))
    }

    @Test
    fun `语言锁复用而不是手抄`() {
        // 与 RoleplayDiscipline.languageSection 逐字相同 —— 手抄一份必然漂移，
        // 漂移之后就是两个模式里一个夹外文、一个不夹。
        assertTrue(section.contains(RoleplayDiscipline.languageSection()))
    }

    @Test
    fun `称呼太长也照写不截断`() {
        // 截断逻辑在 `WorkbenchGreeting.salutationOf`，不在这里；
        // 这里只负责把她被交代的称呼写进去。
        val long = "一个特别特别长的称呼"
        assertTrue(LifeformDiscipline.promptSection(long).contains(long))
    }
}
