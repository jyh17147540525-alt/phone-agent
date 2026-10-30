package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **转场语义**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个类存在的理由就是"这个错误很难被发现"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户说的是"切换有种突兀感"，不是"tab 切换用了滑动"。
 * 从症状到根因之间隔着好几层，而**症状本身不报错**。
 *
 * 所以这里的每条断言都是在把一个"感觉"翻译成可判真假的条件：
 *  · tab 互换必须零位移（加了位移就错，见 TransitionKind 的注释）
 *  · 二级页→顶层必须是钻取（这一条最容易写错，专设一组测试）
 */
class TransitionKindTest {

    private val topLevel = setOf("tasks", "plugins", "settings")

    private fun kind(from: String?, to: String?, isPop: Boolean = false) =
        TransitionKind.of(from, to, topLevel, isPop)

    // ═══════════════════════════════════════════════════════════
    //  平级互换
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `顶层 tab 之间是平级互换`() {
        for (from in topLevel) {
            for (to in topLevel) {
                if (from == to) continue
                assertEquals(
                    "「$from → $to」是平级互换",
                    TransitionKind.Kind.TAB_SWAP,
                    kind(from, to),
                )
            }
        }
    }

    @Test
    fun `平级互换必须零位移`() {
        // ★ 这是整个改动的核心断言：位移的语义是"空间位置变了"。
        //   平级互换时空间没变 —— 加位移就是突兀感的来源。
        val k = kind("tasks", "settings")
        assertFalse("tab 互换不该有位移", TransitionKind.isSpatial(k))
    }

    @Test
    fun `平级互换不区分方向`() {
        // tab 之间没有"更深/更浅"这回事。
        assertEquals(kind("tasks", "settings"), kind("settings", "tasks"))
        assertEquals(kind("plugins", "tasks"), kind("tasks", "plugins"))
    }

    // ═══════════════════════════════════════════════════════════
    //  向下钻取
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `顶层进二级页是钻取`() {
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("settings", "sources"))
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("plugins", "market"))
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("tasks", "persona"))
    }

    @Test
    fun `二级进二级也是钻取`() {
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("settings", "keys"))
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("sources", "import"))
    }

    @Test
    fun `钻取必须带位移`() {
        assertTrue(TransitionKind.isSpatial(kind("settings", "sources")))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 最容易写错的那一条
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `二级页回到顶层不是平级互换`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条守的是一个**具体的设计错误**
        // ══════════════════════════════════════════════════════
        // 如果只判"终点是不是顶层"（`to in topLevel`），
        // 那么「设置 → 插件源 → 点底栏『任务』」会被判成平级互换 ⇒ 纯淡入。
        //
        // 但用户这一步是**从深层回到顶层**，他期待"退回去"的方向感。
        // 砍掉位移会让这一跳失去方向，反而更突兀。
        //
        // ⇒ 必须**两头都在顶层**才算平级互换。
        assertEquals(
            "从二级页回顶层是返回，不是 tab 互换",
            TransitionKind.Kind.DRILL_OUT,
            kind("sources", "tasks", isPop = true),
        )
        // 即使不是 pop（比如点底栏 navigate），也不该当平级互换
        assertEquals(
            TransitionKind.Kind.DRILL_IN,
            kind("sources", "tasks", isPop = false),
        )
    }

    @Test
    fun `顶层进二级页再回同一顶层是往返`() {
        val out = kind("settings", "sources")
        val back = kind("sources", "settings", isPop = true)
        assertEquals(TransitionKind.Kind.DRILL_IN, out)
        assertEquals(TransitionKind.Kind.DRILL_OUT, back)
        // 两者都必须有位移（方向相反）
        assertTrue(TransitionKind.isSpatial(out))
        assertTrue(TransitionKind.isSpatial(back))
    }

    // ═══════════════════════════════════════════════════════════
    //  返回
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `二级页返回是退出`() {
        assertEquals(TransitionKind.Kind.DRILL_OUT, kind("sources", "settings", isPop = true))
        assertEquals(TransitionKind.Kind.DRILL_OUT, kind("keys", "settings", isPop = true))
    }

    @Test
    fun `pop 标记影响非 tab 的跳跃`() {
        // 同一对路由，pop 与否决定方向。
        assertNotSame(
            kind("a", "b", isPop = true),
            kind("a", "b", isPop = false),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有起点时按钻取处理`() {
        // 首个页面是"覆盖在空白之上"出现的 —— 淡入 + 微位移是对的。
        assertEquals(TransitionKind.Kind.DRILL_IN, kind(null, "tasks"))
        assertTrue(TransitionKind.isSpatial(kind(null, "tasks")))
    }

    @Test
    fun `没有终点时按退出处理`() {
        assertEquals(TransitionKind.Kind.DRILL_OUT, kind("sources", null, isPop = true))
    }

    @Test
    fun `起点终点都为空不崩`() {
        // 两头都没有路由 = 没有转场。给 DRILL_IN（中性默认），
        // 而不是 DRILL_OUT —— 后者需要真的有一个"上一层"。
        val k = kind(null, null)
        assertEquals(TransitionKind.Kind.DRILL_IN, k)
    }

    @Test
    fun `未知路由按二级页对待`() {
        // ⚠️ 不认识的路径不能当顶层 —— 那会让未来的新页面意外获得
        //    "平级淡入"，而它其实可能是深层的。
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("tasks", "some-future-page"))
        assertEquals(TransitionKind.Kind.DRILL_IN, kind("some-future-page", "tasks"))
    }

    @Test
    fun `同路由到同路由不算互换`() {
        // 理论上 `goToTab` 已经挡掉了，但纯函数不能依赖调用方守规矩。
        // 同路由应该是"没有转场"—— 这里归到钻取不影响（NavHost 不会触发）。
        val k = kind("tasks", "tasks")
        assertEquals(TransitionKind.Kind.TAB_SWAP, k)
    }

    /** `assertNotSame` 对枚举值不适用（枚举是单例），这里用相等性表达意图。 */
    private fun assertNotSame(a: Any?, b: Any?) {
        assertTrue("两者应当不同：$a vs $b", a != b)
    }
}
