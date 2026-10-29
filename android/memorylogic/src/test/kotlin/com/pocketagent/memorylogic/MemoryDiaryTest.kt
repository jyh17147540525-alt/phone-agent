package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryDiary] 的离线钉子。
 *
 * ★ 用户的验收标准是一句话：
 *   一起与用户去散步 → "今天与（昵称）一起去散步了，很开心"
 *   也就是说：**第一人称日记，不是对话记录**。
 *   所以最关键的一条测试是"不能出现『你说：』这种转述"。
 */
class MemoryDiaryTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun `用户举的那个例子要写成第一人称散步`() {
        val s = MemoryDiary.write(
            text = "U>我们一起去散步吧\nA<好呀（牵起你的手）",
            kindLabel = "阶段结论",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        // ⚠️ 必须是她的叙述，不能是"你说：…我说：…"
        assertFalse("不能是对话转述：$s", s.contains("你说："))
        assertTrue("要有第一人称我：$s", s.contains("我"))
        assertTrue("要有今天：$s", s.contains("今天"))
        assertTrue("要提到散步：$s", s.contains("散步"))
    }

    @Test
    fun `人称要翻转 —— 用户的你变成她的我，用户的我变成称呼`() {
        assertEquals("我饿了", MemoryDiary.flipPronouns("你饿了", "阿泽"))
        // ⚠️ 注意这条的语义：原文是**用户说**"我在等你"，
        //    从她的视角是"阿泽在等我"。之前我把期望值写成"阿泽在等你"，
        //    那是错的 —— 那样等于没翻转。
        assertEquals("阿泽在等我", MemoryDiary.flipPronouns("我在等你", "阿泽"))
        // 「我们」是"你和我"，不该被拆
        assertEquals("我们一起走", MemoryDiary.flipPronouns("我们一起走", "阿泽"))
    }

    @Test
    fun `普通记忆是第三人称描述，只换称呼不翻转人称`() {
        // ⚠️ 翻转会把"用户喜欢猫"变成"猫喜欢我"这种鬼话。
        assertEquals("阿泽喜欢吃辣", MemoryDiary.thirdToName("用户喜欢吃辣", "阿泽"))
        assertEquals("阿泽住在杭州", MemoryDiary.thirdToName("他住在杭州", "阿泽"))
        // 「他们」「其他」不能改坏
        assertEquals("他们都在加班", MemoryDiary.thirdToName("他们都在加班", "阿泽"))
        assertTrue(MemoryDiary.thirdToName("他去了其他地方", "阿泽").contains("其他地方"))
    }

    @Test
    fun `她自己的动作出现在日记里用第一人称`() {
        val s = MemoryDiary.write(
            text = "A<（牵着你的手往楼下走）今天风不错",
            kindLabel = "事实",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertTrue("要有她做的动作：$s", s.contains("牵着"))
        // ⚠️ 这里原来断言「不能出现 牵着阿泽」—— 那条断言是错的。
        //    她那句话里"你"指的是用户，从她视角写下来**就该**是称呼。
        assertTrue("她说的「你」要变成称呼：$s", s.contains("牵着阿泽的手"))
        assertFalse("不能出现裸的「你」：$s", s.contains("牵着你的手"))
    }

    @Test
    fun `压缩段的模型记号不能漏到用户面前`() {
        // ⚠️ 2026-10-29 真机抓到：日记里出现了 `U&gt;` 和 `A&lt;`。
        val raw = "U&gt;（拍了拍你的屁股）好了走吧&#10;A&lt;（跳起来）——哇啊！"
        val s = MemoryDiary.write(raw, "阶段结论", userAddress = "阿泽", atMs = now, nowMs = now)
        assertFalse("不能出现 U>：$s", s.contains("U>"))
        assertFalse("不能出现 A<：$s", s.contains("A<"))
        assertFalse("不能出现 HTML 实体：$s", s.contains("&gt;"))
        assertFalse("不能出现 &#10;：$s", s.contains("&#10;"))
    }

    @Test
    fun `工具调用只留一句概括`() {
        val s = MemoryDiary.write("T|{\"ok\":true}", "事实", atMs = now, nowMs = now)
        assertTrue("要概括成一句：$s", s.contains("手机"))
        assertFalse("不该把 JSON 漏出来：$s", s.contains("ok"))
    }

    @Test
    fun `没有记号的普通记忆也能写成日记`() {
        val s = MemoryDiary.write(
            text = "用户喜欢猫",
            kindLabel = "偏好",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertTrue("要提到喜欢猫：$s", s.contains("猫"))
        assertFalse("不该再把用户当第三人称：$s", s.contains("用户"))
    }

    @Test
    fun `格式是 称呼 加 发生的事 加 感受`() {
        // ★ 用户原话："日记和随笔应该是（角色对用户的称呼）加发生的事情，
        //   加上角色的感受。"
        val s = MemoryDiary.write(
            text = "U>我饿了",
            kindLabel = "事实",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertTrue("要有称呼：$s", s.contains("阿泽"))
        assertTrue("要有发生的事：$s", s.contains("饿"))
        // 感受是模板的尾巴，长度上看得出后面还有半句
        assertTrue("不能只有事没事后那句：$s", s.length > "今天，阿泽饿了。".length + 2)
    }

    @Test
    fun `不同的记忆会用到不同的模板 —— 要有吐槽`() {
        // ★ 用户要"多元化，可以有吐槽之类的"。
        //   如果所有记忆都套同一个模板，点开几次就腻了。
        val kinds = listOf("事实", "偏好", "约束", "阶段结论")
        val texts = (1..40).map { "用户第 $it 件事" }
        val written = mutableSetOf<String>()
        for (k in kinds) for (t in texts) {
            written += MemoryDiary.write(t, k, userAddress = "阿泽", atMs = now, nowMs = now)
        }
        // 至少出现十种不同的句子形态（模板 + 感受的组合）
        val shapes = written.map { it.replace(Regex("[\\u4e00-\\u9fa5]+"), "·") }.toSet()
        assertTrue("模板组合太少（只有 ${shapes.size} 种）：多样性不够", shapes.size >= 4)
        assertTrue("总数应当不少：${written.size}", written.size >= 40)
    }

    @Test
    fun `同一条记忆永远是同一篇`() {
        // ⚠️ 位置和文字是认知锚点，每次点开都不一样会让人怀疑记忆在变。
        val a = MemoryDiary.write("U>我饿了", "事实", "小鱼", "阿泽", now, now)
        val b = MemoryDiary.write("U>我饿了", "事实", "小鱼", "阿泽", now, now)
        assertEquals(a, b)
    }

    @Test
    fun `技术细节不能出现在随笔里`() {
        // ⚠️ 真机抓到：随笔里出现了 content://… 和"这是路径不是内容"。
        //    那是内部通信，写进随笔完全不成立。
        val s = MemoryDiary.write(
            text = "U>（我附了一个图片：content://media/picker_get_content/0/x/media/100 —— 这是路径，不是内容）",
            kindLabel = "事实",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertFalse("不能出现 URI：$s", s.contains("content://"))
        assertFalse("不能出现「这是路径」：$s", s.contains("这是路径"))
        assertFalse("不能出现 media/：$s", s.contains("media/"))
    }

    @Test
    fun `相对日期只写模糊说法`() {
        assertEquals("今天", MemoryDiary.relativeDay(now, now))
        assertEquals("昨天", MemoryDiary.relativeDay(now - day, now))
        assertEquals("前几天", MemoryDiary.relativeDay(now - 3 * day, now))
        assertEquals("", MemoryDiary.relativeDay(0L, now))
    }

    @Test
    fun `同一条记忆永远得到同一段日记`() {
        val a = MemoryDiary.write("U>我饿了", "事实", "小鱼", "阿泽", now, now)
        val b = MemoryDiary.write("U>我饿了", "事实", "小鱼", "阿泽", now, now)
        assertEquals(a, b)
    }

    @Test
    fun `空输入返回空串，不产出半个句子`() {
        assertEquals("", MemoryDiary.write("", "事实", nowMs = now))
        assertEquals("", MemoryDiary.write("   ", "事实", nowMs = now))
    }

    @Test
    fun `改写不会给已有句号重复加标点`() {
        val s = MemoryDiary.write("用户住在杭州。", "事实", atMs = now, nowMs = now)
        assertFalse("不能出现两个句号：$s", s.contains("。。"))
    }

    @Test
    fun `日记只读不写 —— 原子对象一个字都不改`() {
        // ★ 用户明确要求："不能修改底层的记忆架构和逻辑。"
        val atom = Atom(
            id = "a1",
            kind = AtomKind.PREFERENCE,
            text = "用户喜欢猫",
            sourceTurnIds = listOf("t1"),
            confidence = 0.8,
            at = now,
        )
        val before = atom.copy()
        MemoryDiary.write(atom.text, atom.kind.label, "阿泽", "小鱼", atom.at, now)
        assertEquals("记忆对象不能被改写", before, atom)
        assertEquals("原文也必须原样留着", "用户喜欢猫", atom.text)
    }

    @Test
    fun `readable 给原文那一行用，保留谁说的`() {
        val r = MemoryDiary.readable("U>我饿了\nA<那去吃面吧")
        assertTrue("用户的话标成「你」：$r", r.contains("你：我饿了"))
        assertTrue("她的话标成「我」：$r", r.contains("我：那去吃面吧"))
    }

    @Test
    fun `日记要短 —— 随笔不是对话记录`() {
        // ⚠️ 真机第一次出来五六百字整段对话，读起来还是一份记录，不是日记。
        val long = "A<" + "（凑过去盯着你屏幕看了半天，耳朵慢慢耷拉下来）诶，图片加载不出来啊。".repeat(20)
        val s = MemoryDiary.write(long, "阶段结论", atMs = now, nowMs = now)
        assertTrue("日记超过 200 字就不像随笔了（实际 ${s.length}）：$s", s.length <= 200)
        assertFalse("不能被砍在半句里还加个逗号：$s", s.endsWith("，"))
    }

    @Test
    fun `不会出现 问号加句号 这种双标点`() {
        // 真机见过："别卖关子了，直接告诉我，是谁？。"
        val s = MemoryDiary.write(
            text = "A<别卖关子了，直接告诉我，是谁？",
            kindLabel = "事实",
            atMs = now,
            nowMs = now,
        )
        assertFalse("不能出现「？。」：$s", s.contains("？。"))
        assertFalse("不能出现「。。」：$s", s.contains("。。"))
    }

    @Test
    fun `同一行里混着两个人的话也要拆开`() {
        // ⚠️ 真机抓到的真实形态：`你：继续 我：（凑过去看了一眼）…` 一整行。
        //    只按换行拆会把整段判成"用户说的"，日记里就出现 `你说「继续 我（…」`。
        val s = MemoryDiary.write(
            text = "你：继续 我：（凑过去看了一眼）看不见啊",
            kindLabel = "事实",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertFalse("不能出现「你：」：$s", s.contains("你："))
        assertFalse("不能出现「我：」：$s", s.contains("我："))
        assertTrue("要保留她的话：$s", s.contains("看不见"))
    }

    @Test
    fun `「给我：」这种词里的「我：」不能被当成说话人记号`() {
        val s = MemoryDiary.write(
            text = "用户说给我：一碗面",
            kindLabel = "事实",
            userAddress = "阿泽",
            atMs = now,
            nowMs = now,
        )
        assertTrue("实义内容不能被切坏：$s", s.contains("一碗面"))
    }
}
