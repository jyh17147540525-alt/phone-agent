package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * **表单 → 角色** 的映射测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这些测试是给一个真实 bug 上封条
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的报告（说了五次）：填完角色设定、点保存，设定**变空了**。
 *
 * 根因不是存储坏了，是**映射会失败**：`PersonaScreen` 在组合期用
 * `CustomPersona(...)` 造预览对象，而它的 `init` 有两条 `require`
 * （名字 ≤ 24 字、口癖 ≤ 8 条）。输入越过任一条 ⇒ 抛异常 ⇒
 * `runCatching` 兜底成一个空壳 ⇒ **空壳被保存进盘**。
 *
 * ⇒ 修法是让映射**永不失败**（超长截断、超量取前几条、乱填夹到合法区间）。
 *   下面每一条测试对应一个曾经的失败点，模糊测试是最后那道封条：
 *   **任意输入都不许抛异常**。
 */
class PersonaInputTest {

    // ── 名字 ────────────────────────────────────────────────────

    @Test
    fun `名字超长时截断而不是抛异常`() {
        val long = "长".repeat(CustomPersona.MAX_NAME + 20)
        val p = PersonaInput.toPersona(id = "c1", name = long)
        assertEquals(CustomPersona.MAX_NAME, p.name.length)
    }

    @Test
    fun `名字截断按字符切，不会切出半个 emoji`() {
        // 一个 emoji 在 UTF-16 里占两个单元；`take(n)` 会把它切成半个代理对。
        val raw = "🐱".repeat(CustomPersona.MAX_NAME + 5)
        val p = PersonaInput.toPersona(id = "c1", name = raw)
        val last = p.name.last()
        // 半个代理对是"未配对的高代理"或"未配对的低代理"，两者都不是合法字符。
        assertFalse("截断不能留下半个代理对：${p.name}", Character.isHighSurrogate(last))
        assertEquals(CustomPersona.MAX_NAME, p.name.codePointCount(0, p.name.length))
    }

    @Test
    fun `名字两端空白会被去掉`() {
        assertEquals("老王", PersonaInput.toPersona(id = "c", name = "  老王  ").name)
    }

    @Test
    fun `没填名字时得到占位名，且占位被识别出来`() {
        val p = PersonaInput.toPersona(id = "c", name = "")
        assertEquals(PersonaInput.PLACEHOLDER_NAME, p.name)
        assertTrue("占位名必须被识别 —— 它是「不许保存」的唯一依据", PersonaInput.isPlaceholder(p.name))
    }

    @Test
    fun `占位名和空白都算占位`() {
        assertTrue(PersonaInput.isPlaceholder(""))
        assertTrue(PersonaInput.isPlaceholder("   "))
        assertTrue(PersonaInput.isPlaceholder(PersonaInput.PLACEHOLDER_NAME))
        assertTrue(PersonaInput.isPlaceholder(" ${PersonaInput.PLACEHOLDER_NAME} "))
        assertFalse(PersonaInput.isPlaceholder("老王"))
    }

    // ── 口癖：唯一有上限的栏位 ──────────────────────────────────

    @Test
    fun `口癖超过上限时只取前几条，不抛异常`() {
        val many = (1..CustomPersona.MAX_CATCHPHRASES + 4).joinToString("、") { "话$it" }
        val p = PersonaInput.toPersona(id = "c", name = "老王", catchphrases = many)
        assertEquals(CustomPersona.MAX_CATCHPHRASES, p.catchphrases.size)
        assertEquals("话1", p.catchphrases.first())
    }

    @Test
    fun `口癖认得顿号逗号分号和换行`() {
        val p = PersonaInput.toPersona(
            id = "c", name = "老王",
            catchphrases = "这事儿啊、慢慢来,别急；真的\n算了",
        )
        assertEquals(listOf("这事儿啊", "慢慢来", "别急", "真的", "算了"), p.catchphrases)
    }

    @Test
    fun `口癖里的空项被丢掉`() {
        val p = PersonaInput.toPersona(id = "c", name = "老王", catchphrases = "、 、哈哈、、")
        assertEquals(listOf("哈哈"), p.catchphrases)
    }

    // ── 毛病与其余多值栏 ────────────────────────────────────────

    @Test
    fun `毛病按换行解析，保留冒号原文`() {
        val p = PersonaInput.toPersona(
            id = "c", name = "老王",
            flaws = "爱较真：一说到我工作就数落我\n\n嘴硬：明明关心却说随便\n",
        )
        assertEquals(2, p.flawsRaw.size)
        assertEquals("爱较真：一说到我工作就数落我", p.flawsRaw.first())
    }

    @Test
    fun `兴趣雷点共同经历都能解析`() {
        val p = PersonaInput.toPersona(
            id = "c", name = "老王",
            interests = "老电影、游戏",
            taboos = "催婚，体重",
            sharedReferences = "那天那家店、第三排",
        )
        assertEquals(listOf("老电影", "游戏"), p.interests)
        assertEquals(listOf("催婚", "体重"), p.taboos)
        assertEquals(listOf("那天那家店", "第三排"), p.sharedReferences)
    }

    // ── 作息 ────────────────────────────────────────────────────

    @Test
    fun `作息乱填不抛异常并夹到 0 到 23`() {
        assertEquals(0, PersonaInput.hour("0", 23))
        assertEquals(23, PersonaInput.hour("99", 7))
        assertEquals(23, PersonaInput.hour("", 23))
        assertEquals(7, PersonaInput.hour("abc", 7))
        assertEquals(9, PersonaInput.hour("9", 23))
    }

    @Test
    fun `作息只在数字里取两位`() {
        // 用户可能把"23点"整句粘进来
        assertEquals(23, PersonaInput.hour("23点", 7))
        assertEquals(12, PersonaInput.hour("1a2b3", 7))
    }

    // ── 透传：表单没展示的字段不能被顺手清掉 ────────────────────

    @Test
    fun `表单没展示的字段从初始角色透传`() {
        val initial = CustomPersona(
            id = "keep",
            name = "老王",
            addressSelf = "我",
            dialect = "带点四川口音",
            memories = listOf("他爱吃辣"),
            avatarRes = "a1",
        )
        // 模拟用户只改了名字（表单里没有自称/方言/记忆/头像这几栏）
        val edited = PersonaInput.toPersona(
            id = initial.id,
            name = "老王头",
            addressSelf = initial.addressSelf,
            dialect = initial.dialect,
            memories = initial.memories,
            avatarRes = initial.avatarRes,
        )
        assertEquals("keep", edited.id)
        assertEquals("老王头", edited.name)
        assertEquals("我", edited.addressSelf)
        assertEquals("带点四川口音", edited.dialect)
        assertEquals(listOf("他爱吃辣"), edited.memories)
        assertEquals("a1", edited.avatarRes)
    }

    // ── 有效栏数：让"已保存"能当场核对 ─────────────────────────

    @Test
    fun `有效栏数只数真的填了东西的栏`() {
        val empty = PersonaInput.toPersona(id = "c", name = "老王")
        assertEquals("只有名字算 1 栏", 1, PersonaInput.filledFields(empty))

        val full = PersonaInput.toPersona(
            id = "c", name = "老王",
            identity = "退休语文老师", relationTo = "我爸", addressUser = "小陈",
            catchphrases = "慢慢来", flaws = "嘴硬：偏不说想你",
            dailyLife = "每天早上去公园", interests = "下棋", taboos = "体检",
            sharedReferences = "第三排",
        )
        assertEquals(10, PersonaInput.filledFields(full))
    }

    @Test
    fun `只有空格的栏不算填了`() {
        val p = PersonaInput.toPersona(id = "c", name = "老王", identity = "   ")
        assertEquals(1, PersonaInput.filledFields(p))
    }

    // ── 封条 ────────────────────────────────────────────────────

    @Test
    fun `任意输入都不会让组装失败`() {
        // 这个测试就是那个 bug 的封印：只要组装可能抛异常，
        // 界面上就可能出现"兜底空壳被当成用户设定存进盘"。
        val rnd = Random(20261029)
        val alphabet = "名字老王、,，;；\n\r \t🐱👨‍👩‍👧abc123：-_（）「」" +
            "😀".repeat(1) + "𐍈"
        repeat(500) { i ->
            fun junk() = buildString {
                repeat(rnd.nextInt(0, 60)) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
            val p = PersonaInput.toPersona(
                id = if (i % 7 == 0) "" else junk(),
                name = junk(),
                identity = junk(),
                addressUser = junk(),
                relationTo = junk(),
                catchphrases = junk(),
                flaws = junk(),
                interests = junk(),
                taboos = junk(),
                sharedReferences = junk(),
                sleepHour = junk(),
                wakeHour = junk(),
            )
            assertNotNull(p)
            assertTrue("id 不能为空", p.id.isNotBlank())
            assertTrue("名字必须有值（可以是占位）", p.name.isNotBlank())
            assertTrue(
                "口癖数量必须守住上限：${p.catchphrases.size}",
                p.catchphrases.size <= CustomPersona.MAX_CATCHPHRASES,
            )
            // ⚠️ 数**字符**，不是 `String.length` —— 一个 emoji 占两个 UTF-16 单元，
            //    用 length 断言会把"24 个 emoji 的名字"误判为超限。
            //    校验口径只有一处（`CustomPersona.init` 用的也是 codePointCount），
            //    测试也必须用同一把尺子。
            assertTrue(
                "名字字符数必须守住上限：${p.name.codePointCount(0, p.name.length)}",
                p.name.codePointCount(0, p.name.length) <= CustomPersona.MAX_NAME,
            )
            assertTrue("作息必须在 0..23", p.sleepHour in 0..23 && p.wakeHour in 0..23)
        }
    }
}
