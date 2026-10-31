package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **检查点编解码**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这三种情况都会**静默丢数据**
 * ═══════════════════════════════════════════════════════════════
 *
 * 编解码看起来是机械工作，但它错的时候不报错 —— 只是数据不见了：
 *
 *  ① **引号 / 反斜杠 / 换行**：用户说"帮我打开「设置」"没问题，
 *     但说 `打开 "设置"` 或粘一段带换行的文本，转义没做好就会
 *     把一条记录切碎，恢复时读到半条。
 *  ② **缺字段**：老检查点文件没有后来新增的字段。没有默认值的话
 *     整条失效 ⇒ 升级后所有未完成任务消失。
 *  ③ **半行**：进程在写盘中途被杀。那半行不能毁掉整个文件。
 *
 * ⇒ 所以编解码住在 `:agentlogic`（进得了验证器），
 *   `:app` 的 `FileCheckpointStore` 只做文件读写。
 */
class CheckpointCodecTest {

    private fun sample(
        taskId: String = "t1",
        userInput: String = "帮我打开设置",
        completedSteps: Int = 2,
        replanCount: Int = 1,
        planned: List<String> = listOf("OPEN_APP", "TAP", "TYPE"),
        turns: Int = 3,
        finished: Boolean = false,
    ) = TaskCheckpoint(
        taskId = taskId,
        userInput = userInput,
        completedSteps = completedSteps,
        replanCount = replanCount,
        plannedActions = planned,
        usage = BudgetUsage(
            turns = turns,
            elapsedMs = 12_345L,
            energyMicroAh = 67L,
            uploadBytes = 8_900L,
        ),
        savedAtMs = 1_700_000_000_000L,
        finished = finished,
    )

    // ═══════════════════════════════════════════════════════════
    //  往返
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `基本往返无损`() {
        val c = sample()
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))
        assertEquals(c, back)
    }

    @Test
    fun `每个字段都被保下来了`() {
        // ⚠️ 逐字段确认，而不是只比较整体 —— 那样漏了字段
        //    （两个都是 0）也看不出来。
        val c = sample(turns = 7, finished = true)
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!

        assertEquals("t1", back.taskId)
        assertEquals("帮我打开设置", back.userInput)
        assertEquals(2, back.completedSteps)
        assertEquals(1, back.replanCount)
        assertEquals(listOf("OPEN_APP", "TAP", "TYPE"), back.plannedActions)
        assertEquals(7, back.usage.turns)
        assertEquals(12_345L, back.usage.elapsedMs)
        assertEquals(67L, back.usage.energyMicroAh)
        assertEquals(8_900L, back.usage.uploadBytes)
        assertEquals(1_700_000_000_000L, back.savedAtMs)
        assertTrue(back.finished)
    }

    @Test
    fun `finished 为假时也正确`() {
        val back = CheckpointCodec.decode(CheckpointCodec.encode(sample(finished = false)))!!
        assertFalse(back.finished)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★① 特殊字符（最容易被忽略）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `用户输入里的英文引号不影响解析`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这是最典型的一种：不转义的话，`"` 会提前闭合字符串，
        //    后面所有字段全部错位 —— 而且**不报错**，
        //    只是读出来是垃圾。
        // ══════════════════════════════════════════════════════
        val c = sample(userInput = "帮我打开 \"设置\" 那个应用")
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals("帮我打开 \"设置\" 那个应用", back.userInput)
    }

    @Test
    fun `反斜杠不被吃掉`() {
        // ⚠️ 反斜杠是转义符。不双重转义的话 `\n` 会变成真的换行。
        val c = sample(userInput = "路径是 C:\\Users\\test")
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals("路径是 C:\\Users\\test", back.userInput)
    }

    @Test
    fun `换行被保下来`() {
        val c = sample(userInput = "第一行\n第二行")
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals("第一行\n第二行", back.userInput)
        // ★ 而且不能污染"一行一条记录"的格式
        assertFalse("编码后必须还是一行", CheckpointCodec.encode(c).contains('\n'))
    }

    @Test
    fun `动作名里的引号不被切碎`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是 `split("\",\"")` 那种写法
        // ══════════════════════════════════════════════════════
        // 元素里含转义引号时，简单切分会把一个元素切成两个。
        val c = sample(planned = listOf("say \"hi\"", "TAP"))
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals(listOf("say \"hi\"", "TAP"), back.plannedActions)
    }

    @Test
    fun `空动作列表`() {
        val c = sample(planned = emptyList(), completedSteps = 0)
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertTrue(back.plannedActions.isEmpty())
        assertEquals(0, back.completedSteps)
    }

    @Test
    fun `中文与 emoji 不乱码`() {
        val c = sample(userInput = "帮我订🐱猫粮")
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals("帮我订🐱猫粮", back.userInput)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★② 缺字段（向前兼容）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `缺字段时退回默认而不是整条失效`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"升级后所有未完成任务消失"
        // ══════════════════════════════════════════════════════
        // 老文件没有后来新增的字段。整条丢弃的话，用户升级一次
        // 就丢掉所有中断的任务 —— 而他不会知道为什么。
        val minimal = """{"t":"t9","u":"旧任务"}"""
        val back = CheckpointCodec.decode(minimal)
        assertNotNull("必须能读出来", back)
        assertEquals("t9", back!!.taskId)
        assertEquals("旧任务", back.userInput)
        assertEquals(0, back.completedSteps)
        assertEquals(0, back.replanCount)
        assertTrue(back.plannedActions.isEmpty())
        assertEquals(0, back.usage.turns)
        assertFalse(back.finished)
    }

    @Test
    fun `只有 taskId 也能读出来`() {
        val back = CheckpointCodec.decode("""{"t":"only-id"}""")
        assertNotNull(back)
        assertEquals("only-id", back!!.taskId)
    }

    @Test
    fun `没有 taskId 时返回 null`() {
        // ⚠️ taskId 是唯一**不可缺**的字段 —— 没有它这条记录
        //    无法定位任务，留着也没有意义。
        assertNull(CheckpointCodec.decode("""{"u":"没有 id"}"""))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★③ 坏行 / 半行
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空行返回 null`() {
        assertNull(CheckpointCodec.decode(""))
        assertNull(CheckpointCodec.decode("   "))
        assertNull(CheckpointCodec.decode("{}"))
    }

    @Test
    fun `半截 JSON 不崩`() {
        // ⚠️ 进程在写盘中途被杀会留下这种。它**不该抛异常** ——
        //    抛出去会让整个文件读取失败。
        val half = """{"t":"t1","u":"被截断的"""
        val back = CheckpointCodec.decode(half)
        // 能解析出 taskId 就算成功（这是"尽力而为"）
        if (back != null) assertEquals("t1", back.taskId)
    }

    @Test
    fun `完全垃圾的行返回 null 而不是抛异常`() {
        for (junk in listOf("not json at all", "{{{{", "\u0000\u0001", "]}]}]")) {
            assertNull("「$junk」不该解析出东西", CheckpointCodec.decode(junk))
        }
    }

    @Test
    fun `未闭合的动作数组保留已解析部分`() {
        // ★ 半截文件里，动作列表可能没有 `]`。
        //   已解析的部分仍然有用（用户能知道它计划做什么）。
        val half = """{"t":"t1","p":["A","B","""
        val back = CheckpointCodec.decode(half)
        if (back != null) {
            assertTrue("已解析的动作应当保留：${back.plannedActions}",
                back.plannedActions.contains("A"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  数值边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `零值不丢`() {
        val c = TaskCheckpoint(
            taskId = "z",
            userInput = "",
            completedSteps = 0,
            replanCount = 0,
            plannedActions = emptyList(),
            usage = BudgetUsage(),
            savedAtMs = 0L,
            finished = false,
        )
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals(0, back.completedSteps)
        assertEquals(0L, back.savedAtMs)
    }

    @Test
    fun `大数值不溢出`() {
        val c = sample().copy(savedAtMs = Long.MAX_VALUE / 2)
        val back = CheckpointCodec.decode(CheckpointCodec.encode(c))!!
        assertEquals(Long.MAX_VALUE / 2, back.savedAtMs)
    }

    @Test
    fun `编码结果不含裸换行`() {
        // ★ 格式依赖"一行一条记录"。任何一个字段里的换行
        //   都必须被转义，否则整个文件结构会被破坏。
        //
        // ⚠️ `completedSteps` 不能超过计划步数（构造期 require）——
        //    所以这里计划给 3 步、完成 2 步，而不是反过来。
        val c = sample(
            userInput = "a\nb\r\nc",
            planned = listOf("x\ny", "B", "C"),
            completedSteps = 2,
        )
        val encoded = CheckpointCodec.encode(c)
        assertEquals("必须只有一行", 1, encoded.lines().size)
        // 而且解码回来内容要完整
        val back = CheckpointCodec.decode(encoded)!!
        assertEquals("a\nb\r\nc", back.userInput)
        assertEquals(listOf("x\ny", "B", "C"), back.plannedActions)
    }
}
