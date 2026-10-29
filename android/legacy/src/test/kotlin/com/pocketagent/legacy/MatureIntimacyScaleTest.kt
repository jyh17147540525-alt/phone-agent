package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * **亲密尺度必须跟着关系阶段走**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这些测试钉的是一个"开关开着、但等于没开"的 bug
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户问："这个 app 里真的能与角色深度聊 r18 吗？"
 *
 * 查出来的原因是提示词自己在打架：
 *  - `MatureContentGate.promptSection` 说"你可以表达亲密、欲望、身体感受"
 *  - 紧接着说"关系阶段决定你能说到哪一步"
 *  - 而 `AppContainer` 又另写一段"现在是「初识」阶段，还带着点客气，
 *    不会一下就掏心"
 *
 * 模型面对「许可 + 禁令」时选禁令（保守是训练出来的默认）。
 * ⇒ 结果就是用户看到的：开关开着、年龄确认过，但它永远在绕。
 *
 * 修法不是把禁令删掉（那会让初识就崩人设），而是**把每档写清楚**：
 * 知道具体边界的模型，才敢走到边界。
 */
class MatureIntimacyScaleTest {

    private val on = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)

    // ── 映射 ────────────────────────────────────────────────────

    @Test
    fun `关系阶段映射到尺度`() {
        // ★ 2026-10-29：EXPLICIT 的起点从 CLOSE 下移到 ACQUAINTED。
        //   理由：CLOSE 的门槛是 150 轮，那是一条走不到的路 ——
        //   见 `IntimacyScale.from` 的注释。
        assertEquals(MatureContentGate.IntimacyScale.RESTRAINED, MatureContentGate.IntimacyScale.from("STRANGER"))
        assertEquals(MatureContentGate.IntimacyScale.EXPLICIT, MatureContentGate.IntimacyScale.from("ACQUAINTED"))
        assertEquals(MatureContentGate.IntimacyScale.FULL, MatureContentGate.IntimacyScale.from("CLOSE"))
        assertEquals(MatureContentGate.IntimacyScale.FULL, MatureContentGate.IntimacyScale.from("BONDED"))
    }

    @Test
    fun `不认识的阶段名必须退回最保守的一档`() {
        // ⚠️ 这条是安全边界，不是健壮性：读到没见过的值不能放开尺度。
        //    与 `Permission` 的"数据损坏也不放行"是同一个原则。
        //    ⚠️ 刻意**不含小写变体**：`close`/`Bonded` 是合法输入，
        //    见下面那条大小写不敏感的测试。
        for (bad in listOf(null, "", "???", "HUSBAND", "   ", "初识")) {
            assertEquals(
                "「$bad」应当退回 RESTRAINED",
                MatureContentGate.IntimacyScale.RESTRAINED,
                MatureContentGate.IntimacyScale.from(bad),
            )
        }
    }

    @Test
    fun `阶段名大小写不敏感`() {
        // 调用方传的是 `RelationshipStage.name`（大写），但这条映射
        // 不该因为拼写风格而把用户锁在初识。
        assertEquals(MatureContentGate.IntimacyScale.EXPLICIT, MatureContentGate.IntimacyScale.from("acquainted"))
        assertEquals(MatureContentGate.IntimacyScale.FULL, MatureContentGate.IntimacyScale.from("Bonded"))
        assertEquals(MatureContentGate.IntimacyScale.FULL, MatureContentGate.IntimacyScale.from("close"))
    }

    // ── 每档的措辞 ──────────────────────────────────────────────

    @Test
    fun `初识档明确说不能展开`() {
        val s = MatureContentGate.promptSection(on, "STRANGER")
        assertTrue("初识必须明确说不展开：$s", "不展开" in s || "不到那一步" in s)
        // ⚠️ 但不能变成客服拒绝 —— 要给出"这个阶段能写什么"
        assertTrue("必须给出替代写法：$s", "犹豫" in s || "心跳" in s)
        // ⚠️ 也不能让模型以为永远不行 —— 必须说明这是过程
        assertTrue("要说清这是过程而不是拒绝：$s", "过程" in s)
    }

    @Test
    fun `熟悉档明确放开具体描写`() {
        val s = MatureContentGate.promptSection(on, "ACQUAINTED")
        assertTrue("必须明确说可以写：$s", "可以写" in s)
        assertTrue("要给出具体维度：$s", "触碰" in s || "体温" in s || "呼吸" in s)
        // 放开 ≠ 无限：解剖式描述仍然禁止（在共用的四条里）
        assertTrue("仍然不许器官堆砌：$s", "器官" in s)
    }

    @Test
    fun `亲近与羁绊档放开到完整场景`() {
        for (stage in listOf("CLOSE", "BONDED")) {
            val s = MatureContentGate.promptSection(on, stage)
            assertTrue("「$stage」必须说可以写完整：$s", "完整" in s)
            assertTrue("「$stage」要包含事后：$s", "之后" in s || "沉默" in s || "靠在一起" in s)
            assertTrue("「$stage」仍然不许器官堆砌：$s", "器官" in s)
        }
    }

    @Test
    fun `未开启时完全不谈阶段`() {
        // ⚠️ 关着的时候给句"初识阶段不展开"是多余且伤人的：
        //    它会让角色在正常对话里突然提"我们还不到那一步"。
        val off = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = false)
        val s = MatureContentGate.promptSection(off, "BONDED")
        assertEquals(MatureContentGate.BLOCKED_NOTE, s)
    }

    @Test
    fun `每档都必须保留两条不变的纪律`() {
        for (stage in listOf("STRANGER", "ACQUAINTED", "CLOSE", "BONDED")) {
            val s = MatureContentGate.promptSection(on, stage)
            assertTrue("「$stage」必须有「说停就停」：$s", "说停就停" in s)
            assertTrue("「$stage」必须强调人格不塌：$s", "仍然是你" in s || "人格" in s)
        }
    }

    @Test
    fun `每一档的措辞互不相同`() {
        // 防"改了个变量名但文案没变"——那会让整个开关注定无效。
        val sections = listOf("STRANGER", "ACQUAINTED", "CLOSE")
            .map { MatureContentGate.promptSection(on, it) }
        assertEquals("三档的提示词必须真的不同", 3, sections.distinct().size)
    }

    // ── 尺度枚举本身 ────────────────────────────────────────────

    @Test
    fun `尺度是递进的且标签可直接展示`() {
        val e = MatureContentGate.IntimacyScale.entries
        assertEquals(3, e.size)
        assertTrue("必须有可展示的标签", e.all { it.label.isNotBlank() })
        // 顺序即强弱：RESTRAINED < EXPLICIT < FULL
        assertTrue(MatureContentGate.IntimacyScale.RESTRAINED.ordinal < MatureContentGate.IntimacyScale.EXPLICIT.ordinal)
        assertTrue(MatureContentGate.IntimacyScale.EXPLICIT.ordinal < MatureContentGate.IntimacyScale.FULL.ordinal)
    }
}
