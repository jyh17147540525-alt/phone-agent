package com.pocketagent.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [AtomicTextFile] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个类只有几十行，但它是**唯一**挡在"半截配置文件"前面的东西，
 * 而那种故障的表现是"没有报错、只是行为诡异"：
 *
 * | 写错了什么 | 用户看到的现象 | 会去查的方向 |
 * |---|---|---|
 * | 没建父目录 | 投递静默失败（被吞成 false） | "为什么一直说写不进去" |
 * | 直接覆盖而不走临时文件 | 崩溃后配置被截断，dsh 按缺省值跑 | "是不是你们弄坏了 dsh" |
 * | 成功/失败后不清理临时文件 | 目录里堆着 `settings.yaml.tmp-xxxx` | 用户会以为那是配置文件 |
 * | 父路径被同名文件挡住时不报错 | 同上，且更难解释 | 无从下手 |
 *
 * 前三条都**不会抛异常**，最后一条连"失败"都说不清。
 */
class AtomicTextFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun namesIn(dir: File): List<String> =
        dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    // ─────────────────────────────────────────────────────────────
    //  正常路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `目标不存在时连父目录一起建出来`() {
        // ★ 这正是真实用法：`dsh/` 这一层一开始并不存在。
        //   不建目录的话，writeText 会抛 FileNotFoundException，
        //   而调用方只会看到一个 false —— 排查方向完全没有线索。
        val target = File(tmp.root, "dsh/settings.yaml")

        assertTrue(AtomicTextFile.write(target, "version: 1\n"))

        assertTrue("目标文件应当存在", target.isFile)
        assertEquals("version: 1\n", target.readText())
    }

    @Test
    fun `目标已存在时内容被整体替换`() {
        val target = File(tmp.root, "dsh/settings.yaml")
        AtomicTextFile.write(target, "旧内容很长很长很长\n")

        assertTrue(AtomicTextFile.write(target, "新\n"))

        // ★ 断言"整体替换"而不是"包含新内容" —— 后者在"追加"这种错误下也会通过
        assertEquals("新\n", target.readText())
    }

    @Test
    fun `内容为空串时也能写`() {
        val target = File(tmp.root, "dsh/empty.yaml")

        assertTrue(AtomicTextFile.write(target, ""))

        assertTrue(target.isFile)
        assertEquals("", target.readText())
    }

    @Test
    fun `缩进与尾部换行原样保留`() {
        // ★ YAML 对缩进敏感，而"顺手 trim 一下"是个很容易犯的错。
        val yaml = "llm-deepseek:\n  baseURL: http://127.0.0.1:1234/v1\n  apiKeyEnv: TOKEN\n"
        val target = File(tmp.root, "dsh/settings.yaml")

        AtomicTextFile.write(target, yaml)

        assertEquals(yaml, target.readText())
    }

    @Test
    fun `中文内容按 UTF-8 原样落盘`() {
        // ⚠️ 落盘编码错了会得到一串问号或乱码，而 dsh 读到乱码 YAML
        //   只会报一个与编码毫无关系的解析错。
        val target = File(tmp.root, "dsh/note.yaml")

        AtomicTextFile.write(target, "注释：这是用户手写的\n")

        assertEquals("注释：这是用户手写的\n", target.readText())
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ 原子性 —— 这个类唯一的价值
    // ─────────────────────────────────────────────────────────────

    /*
     * ⚠️ 这一节的两条测试**必须**依赖 `onStaged` 钩子，不能靠"写完看结果"。
     *
     *    因为原子性从外部是看不见的：写完之后的最终状态，走不走临时文件
     *    都一模一样。**实测过**：把 `write` 换成 `target.writeText(content)`
     *    （整个临时文件机制删掉），本文件其余测试**全部照过**。
     *
     *    也就是说，没有这两条，任何人都可以"顺手简化"掉这个类的全部价值
     *    而不被发现 —— 而后果是崩溃时留下半截 YAML，
     *    dsh 按缺省值跑起来，表现是"没有报错、只是行为诡异"。
     */

    @Test
    fun `改名就位之前目标文件仍是旧内容`() {
        val target = File(tmp.root, "dsh/settings.yaml")
        AtomicTextFile.write(target, "旧内容\n")

        var seenWhileStaged: String? = null
        val ok = AtomicTextFile.write(target, "新内容\n") {
            seenWhileStaged = target.readText()
        }

        assertTrue(ok)
        assertEquals("此刻目标必须还是旧内容，否则就是直接覆盖", "旧内容\n", seenWhileStaged)
        assertEquals("新内容\n", target.readText())
    }

    @Test
    fun `改名就位之前临时文件里已经是完整的新内容`() {
        // ⚠️ 这条与上一条互为反向保证。
        //
        //    只有上一条的话，一个"什么都不写、只在最后返回 true"的实现也能过
        //    （回调时目标确实还是旧内容）。这条要求此刻**临时文件已存在且完整**，
        //    于是"先写临时文件"这个动作本身被钉住了。
        val dir = File(tmp.root, "dsh")
        val target = File(dir, "settings.yaml")

        var staged: List<Pair<String, String>> = emptyList()
        AtomicTextFile.write(target, "完整的新内容\n") {
            staged = dir.listFiles().orEmpty().map { it.name to it.readText() }
        }

        val tmpFiles = staged.filter { it.first.contains(".tmp-") }
        assertEquals("此刻应当恰好有一个临时文件：$staged", 1, tmpFiles.size)
        assertEquals("完整的新内容\n", tmpFiles.single().second)
        assertEquals("此刻目标文件还不该存在", 1, staged.size)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 不留痕迹
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `成功之后目录里只剩目标文件`() {
        // ★★ 这条抓的是"临时文件没被改名/清理"。
        //    临时文件泄漏不会报错，但用户会**在配置目录里看见它** ——
        //    一个叫 `settings.yaml.tmp-a1b2c3d4` 的文件，他会以为那是
        //    我们要求的配置文件之一，然后去编辑它。而它下次投递就没了。
        val dir = File(tmp.root, "dsh")

        AtomicTextFile.write(File(dir, "settings.yaml"), "a\n")
        AtomicTextFile.write(File(dir, "settings.yaml"), "b\n")

        assertEquals(listOf("settings.yaml"), namesIn(dir))
    }

    @Test
    fun `写失败之后也不留下临时文件`() {
        // 用"父路径被一个**文件**挡住"来制造失败：mkdirs 会失败。
        val blocker = File(tmp.root, "dsh")
        blocker.writeText("我是一个文件，不是目录")

        val target = File(blocker, "settings.yaml")

        assertFalse("父路径不是目录时应当失败", AtomicTextFile.write(target, "x\n"))
        assertEquals(
            "不该在失败路径上留下临时文件",
            listOf("dsh"),
            namesIn(tmp.root),
        )
    }

    @Test
    fun `父路径被同名文件挡住时返回 false 而不是抛异常`() {
        val blocker = File(tmp.root, "dsh")
        blocker.writeText("挡路")

        val ok = AtomicTextFile.write(File(blocker, "settings.yaml"), "x\n")

        assertFalse(ok)
        // ★ 而且**不能**把挡路的那个文件弄坏或删掉 ——
        //   它是用户的东西，我们只是写不进去
        assertEquals("挡路", blocker.readText())
    }

    @Test
    fun `不碰同目录里的其它文件`() {
        // ★ 真实场景：dsh 的配置目录里还有 `credentials.yaml` 与用户自己的文件。
        //   投递 settings 时把它们删掉/改名，就是数据损坏。
        val dir = File(tmp.root, "dsh")
        dir.mkdirs()
        File(dir, "credentials.yaml").writeText("version: 1\nrefs:\n")
        File(dir, "user-notes.txt").writeText("我的笔记\n")

        AtomicTextFile.write(File(dir, "settings.yaml"), "new\n")

        assertEquals(
            listOf("credentials.yaml", "settings.yaml", "user-notes.txt"),
            namesIn(dir),
        )
        assertEquals("version: 1\nrefs:\n", File(dir, "credentials.yaml").readText())
        assertEquals("我的笔记\n", File(dir, "user-notes.txt").readText())
    }
}
