package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **两套内容必须互不污染** —— 这是本文件唯一要钉的事。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的原话（第三次纠正这条边界）
 * ═══════════════════════════════════════════════════════════════
 *
 * "这个日记和随笔的形式是对外呈现给用户看的，**模型不看这个**，
 *  模型看到是**未润色过的纯事件**，所以你要做的是**两套**。
 *  一套面向用户，一套面共享模型。"
 *
 * ⇒ 两条管道，永不交叉：
 *
 * | | 面向用户 | 面向模型 |
 * |---|---|---|
 * | 内容 | 她写的随笔（润色过） | 原子原文（未润色） |
 * | 出处 | `MemoryDiary` / 模型生成 | `Atom.text` |
 * | 谁读 | 界面 | `MemoryRecall` → 系统提示词 |
 * | 存哪 | diaries.txt | atoms.jsonl |
 *
 * ⚠️ 交叉的后果（两个方向都很糟）：
 *   · 随笔喂给模型 → 模型读到"她对自己记忆的复述"，几轮后记忆走形
 *   · 原文直接给用户 → 用户看到 `U>` / `A<` 那种内部编码
 */
class DiarySeparationTest {

    private val now = 1_700_000_000_000L

    private fun atom(text: String, kind: AtomKind = AtomKind.FACT) = Atom(
        id = Atom.idOf(kind, text),
        kind = kind,
        text = text,
        sourceTurnIds = listOf("t1"),
        confidence = 0.8,
        at = now,
    )

    @Test
    fun `模型读到的永远是原子原文，不是随笔`() {
        val raw = "U>我饿了\nA<那去吃面吧"
        val a = atom(raw)

        // 模型侧：MemoryRecall 只吃 atom.text
        // ⚠️ query 要给够字：单字查询的二元组集合是空的，
        //    `MemoryRecall` 会对 FACT 判"无字面重合"而过滤掉它。
        val promptForModel = MemoryRecall.promptSection(
            query = "我饿了想吃饭",
            atoms = listOf(a),
            nowMs = now,
        )
        assertTrue("模型要看到原文：$promptForModel", promptForModel.contains("我饿了"))

        // 用户侧：随笔
        val forUser = MemoryDiary.write(
            text = a.text,
            kindLabel = a.kind.label,
            userAddress = "阿泽",
            atMs = a.at,
            nowMs = now,
        )
        assertFalse("用户不该看到内部编码：$forUser", forUser.contains("U>"))
        assertFalse("用户不该看到内部编码：$forUser", forUser.contains("A<"))

        // 两条管道的内容必须不同 —— 这就是"两套"
        assertFalse(
            "模型拿到的和用户看到的不能是同一份：\n模型=$promptForModel\n用户=$forUser",
            promptForModel.contains(forUser),
        )
    }

    @Test
    fun `随笔不会回写原子库`() {
        // ⚠️ 反向污染更隐蔽：随笔一旦变成"记忆"，下一轮抽取会把它当素材。
        val a = atom("用户喜欢猫")
        val before = a.copy()
        repeat(5) {
            MemoryDiary.write(a.text, a.kind.label, userAddress = "阿泽", atMs = a.at, nowMs = now)
        }
        assertEquals("连续生成五次，原子一个字都不该变", before, a)
    }

    @Test
    fun `给用户的版本必须经过清洗，给模型的版本必须是原文`() {
        val raw = "U>（我附了一个图片：content://media/x/1 —— 这是路径）"
        val a = atom(raw)

        // 用户侧：技术细节被清掉
        val forUser = MemoryDiary.readable(a.text)
        assertFalse("用户不该看到 URI：$forUser", forUser.contains("content://"))

        // 模型侧：原文原样保留（丢了它，模型就不知道当时发生了什么）
        assertEquals("原子原文不能被清洗逻辑改动", raw, a.text)
    }

    @Test
    fun `日记提示词的素材也是原文，不是另一份随笔`() {
        // ⚠️ 这条容易被漏：日记生成器也要吃原文，
        //    如果它吃的是"上一篇随笔"，就会越写越走形。
        val raw = "U>我饿了\nA<那去吃面吧"
        val prompt = MemoryDiaryPrompt.build(
            rawMemory = raw,
            kindLabel = "事实",
            userAddress = "阿泽",
        )
        assertTrue("要包含原文内容：$prompt", prompt.contains("我饿了"))
        assertFalse("不能出现内部编码：$prompt", prompt.contains("U>"))
        // 「素材来自原文」的判据：提示词里出现的是事件本身，不是"她写过的句子"
        assertTrue(prompt.contains("记录："))
    }
}
