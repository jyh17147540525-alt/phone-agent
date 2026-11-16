package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关于你」那一页的提示词段。
 *
 * ★ 这个测试守着的东西，比"字段能拼出来"重要得多：
 *   ① 空的不许出现（不许「喜欢：未填」）—— 角色看到空槽只会开始盘问，
 *      而这一页存在的唯一理由就是**省掉**那些提问；
 *   ② 超预算要整行删，不许截半句 —— 半句会被模型当真；
 *   ③ 雷区永远不裁 —— 说错话的代价比"少知道一点"高得多；
 *   ④ 排版要过 PromptVoice 体检 —— 提示词的形状也是提示词。
 */
class UserProfileTest {

    private fun full(): UserProfile = UserProfile(
        callName = "小陈",
        gender = UserGender.MALE,
        ageBand = UserAgeBand.TWENTIES,
        livingWith = LivingWith.ALONE,
        hardHistory = listOf("外婆走的那年他一直在加班", "上一份工作撑到抑郁"),
        bigMoments = listOf("在北方待过四年", "学过两年吉他"),
        proudOf = listOf("一个人把房子装修完了"),
        chatStyle = ChatStyle.TERSE,
        likes = listOf("冰美式", "老港片", "下雨天不出门"),
        dislikes = listOf("香菜"),
        taboos = listOf("他父亲", "换工作"),
        bodyNotes = "花粉过敏，最近睡得不太好",
        sleepHour = 2,
        wakeHour = 8,
        knownFacts = listOf("他搬了新家", "养了一只猫叫豆子"),
        extra = "怕吵，晚上别开外放",
    )

    @Test
    fun 什么都没填时不产出任何提示词() {
        val empty = UserProfile()
        assertEquals("", empty.promptSection())
        assertTrue(empty.isEmpty())
    }

    @Test
    fun 只填了雷区时只出现雷区那句话() {
        val p = UserProfile(taboos = listOf("他父亲")).promptSection()
        assertTrue(p.contains("别提他父亲。"))
        assertFalse(p.contains("未填"))
        // 没填的槽一个字都不该冒出来。
        assertFalse(p.contains("很少提"))
        assertFalse(p.contains("喜欢"))
    }

    @Test
    fun 角色没有填称呼时档案里的称呼才出现() {
        val p = UserProfile(callName = "小陈", ageBand = UserAgeBand.TWENTIES).promptSection()
        assertTrue(p.contains("他叫「小陈」"))

        val without = UserProfile(callName = "小陈", ageBand = UserAgeBand.TWENTIES)
            .promptSection(callNameFromCharacter = "宝贝")
        // 两份称呼并存模型会挑错，所以角色那条优先时这里整行不出现称呼。
        assertFalse(without.contains("小陈"))
    }

    @Test
    fun 性别决定这一页用他还是她() {
        val she = UserProfile(gender = UserGender.FEMALE, bigMoments = listOf("当过护士"))
            .promptSection()
        assertTrue(she.startsWith("## 关于她"))
        assertTrue(she.contains("这些是她自己填的"))
        // 过往那行也要跟着换代词，否则会出现「关于她」里夹「他经历过」。
        assertTrue(she.contains("她经历过：当过护士。"))

        val he = UserProfile(gender = UserGender.MALE, bigMoments = listOf("当过护士"))
            .promptSection()
        assertTrue(he.startsWith("## 关于他"))
        assertTrue(he.contains("他经历过：当过护士。"))
    }

    @Test
    fun 首尾各有一句框定() {
        val p = full().promptSection()
        assertTrue(p.contains("这些是他自己填的，都是真的。"))
        assertTrue(p.contains("这些你已经知道，别再从头问一遍。"))
    }

    @Test
    fun 填了的每一栏都真的进了提示词() {
        val p = full().promptSection()
        assertTrue(p.contains("他有些事很少提：外婆走的那年他一直在加班；上一份工作撑到抑郁。"))
        assertTrue(p.contains("你知道就好，别主动问，也别把这几件事当话题。"))
        assertTrue(p.contains("他经历过：在北方待过四年；学过两年吉他。"))
        assertTrue(p.contains("提起这些他会高兴：一个人把房子装修完了。"))
        assertTrue(p.contains("他喜欢冰美式、老港片、下雨天不出门。"))
        assertTrue(p.contains("他不喜欢香菜。"))
        assertTrue(p.contains("别提他父亲，也别提换工作。"))
        assertTrue(p.contains("身体：花粉过敏，最近睡得不太好。"))
        assertTrue(p.contains("他一般 2 点睡，8 点起。"))
        assertTrue(p.contains("你们之间已经发生过的：他搬了新家；养了一只猫叫豆子。"))
        assertTrue(p.contains("他还想让你知道：怕吵，晚上别开外放。"))
        assertTrue(p.contains("话少"))
    }

    @Test
    fun 全填满时也守得住预算() {
        val p = full().promptSection(maxChars = 200)
        assertTrue("实际 ${p.length} 字", p.length <= 200 + 120) // 首尾框定不计入裁剪，留出余量
        // 雷区优先级最高，再紧也不许走。
        assertTrue(p.contains("别提"))
    }

    @Test
    fun 预算极紧时只留下不许裁的那几行() {
        val p = full().promptSection(maxChars = 0)
        assertTrue(p.contains("别提他父亲"))
        assertTrue(p.contains("他叫「小陈」"))
        // 可以裁的那些全走了。
        assertFalse(p.contains("他经历过"))
        assertFalse(p.contains("喜欢冰美式"))
        assertFalse(p.contains("还想让你知道"))
    }

    @Test
    fun 裁剪是整行删掉而不是截断半句() {
        val p = full().promptSection(maxChars = 260)
        // 每一行正文都必须以句号收尾 —— 出现没收尾的行就说明被切断了。
        val body = p.lines()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("##") }
            .filterNot { it.contains("自己填的") || it.contains("别再问一遍") }
        assertTrue(body.isNotEmpty())
        body.forEach { line ->
            assertTrue("这一行没有收尾：$line", line.endsWith("。"))
        }
    }

    @Test
    fun 裁剪不会把某一行切成半句() {
        // 上面那条只看"留下来的行以句号收尾" —— 预算够、根本没裁的时候它恒真。
        // 这一条盯的是「被切出来的半句」：留下来的每一行都必须原样存在于完整档案里。
        fun bodyOf(maxChars: Int): List<String> = full().promptSection(maxChars = maxChars).lines()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("##") }
            .filterNot { it.contains("自己填的") || it.contains("别再从头问一遍") }

        val wholeBody = bodyOf(10_000)
        val tightBody = bodyOf(60)
        assertTrue(tightBody.isNotEmpty())
        assertTrue("60 字预算下必须真的裁掉几行，否则这条断言是空的", tightBody.size < wholeBody.size)
        tightBody.forEach { line ->
            assertTrue("这一行是截断出来的半句：$line", wholeBody.contains(line))
        }
    }

    @Test
    fun 列表最多八条() {
        val many = (1..12).map { "爱好$it" }
        val p = UserProfile(likes = many).promptSection()
        assertTrue(p.contains("爱好8"))
        assertFalse("第九条不该出现", p.contains("爱好9"))
    }

    @Test
    fun 自由文本单栏最多一百二十字() {
        val p = UserProfile(bodyNotes = "啊".repeat(300)).promptSection()
        val line = p.lines().first { it.startsWith("身体：") }
        assertTrue("实际 ${line.length} 字", line.length <= UserProfile.MAX_FIELD_CHARS + 4)
    }

    // ── 过往 ────────────────────────────────────────────────

    @Test
    fun 难受过的事后面必须跟着别主动问() {
        // 缺了这句，这一栏就是一颗雷：模型会把创伤当话题往上凑。
        val p = UserProfile(hardHistory = listOf("外婆走了")).promptSection()
        assertTrue(p.contains("他有些事很少提：外婆走了。"))
        assertTrue(p.contains("别主动问"))
    }

    @Test
    fun 过往比喜欢更容易活下来() {
        // 预算紧的时候先丢「喜欢」，因为「懂他」主要靠过往那几行。
        val p = UserProfile(
            hardHistory = listOf("外婆走了"),
            likes = listOf("冰美式"),
        ).promptSection(maxChars = 40)
        assertTrue(p.contains("外婆走了"))
        assertFalse("喜欢这行优先级更低，该被裁掉：$p", p.contains("冰美式"))
    }

    @Test
    fun 三条过往各说各的互不串台() {
        val moments = UserProfile(bigMoments = listOf("在北方待过四年")).promptSection()
        assertFalse("只填经历时不该冒出「很少提」", moments.contains("很少提"))
        assertFalse(moments.contains("会高兴"))

        val proud = UserProfile(proudOf = listOf("装修完了")).promptSection()
        assertFalse(proud.contains("很少提"))
        assertFalse(proud.contains("经历过"))
    }

    @Test
    fun 只填了睡觉时间也能说清楚() {
        assertTrue(UserProfile(sleepHour = 3).promptSection().contains("他一般 3 点才睡。"))
        assertTrue(UserProfile(wakeHour = 9).promptSection().contains("他一般 9 点起。"))
        assertFalse(UserProfile(sleepHour = 3).promptSection().contains("点起"))
    }

    @Test
    fun 只填年龄时那一行是可以被裁掉的() {
        // 称呼不在里面，所以它不该占着"永不裁"的位置。
        val p = UserProfile(ageBand = UserAgeBand.FORTIES).promptSection(maxChars = 0)
        assertEquals("姓名和年龄都不该在极紧预算里活下来", "", p.lines().filter { it.isNotBlank() && !it.startsWith("##") && !it.contains("自己填的") && !it.contains("别再问一遍") }.joinToString(""))
    }

    @Test
    fun 提示词段过得了_排版体检() {
        val p = full().promptSection()
        val report = PromptVoice.audit(p)
        assertTrue("有刺眼的毛病：${report.logLine()}", report.loud == 0)
        assertTrue("有腔调上的毛病：${report.logLine()}", report.tone == 0)
    }

    @Test
    fun 极紧预算裁出来的片段也过得了体检() {
        val p = full().promptSection(maxChars = 260)
        val report = PromptVoice.audit(p)
        assertTrue(report.logLine(), report.loud == 0)
        assertTrue(report.logLine(), report.tone == 0)
    }
}
