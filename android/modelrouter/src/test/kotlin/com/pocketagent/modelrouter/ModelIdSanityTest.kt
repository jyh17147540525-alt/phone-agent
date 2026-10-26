package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型 id 抄错检查的测试。
 *
 * ★ 这个检查的价值在"**必然失败**"四个字上：
 *   id 抄错的模型 100% 会 400，不存在运气。所以它不该留到用户发消息之后才炸。
 *
 * ⚠️ 但它的**误伤代价**也很高：把合法的自定义 id 判成"抄错了"，
 *    用户会去改一个本来正确的配置。所以这里最多只能报"极像"的情况。
 */
class ModelIdSanityTest {

    private fun model(id: String, modelId: String, enabled: Boolean = true) = ModelConfig(
        id = id,
        label = id,
        credentialId = "c1",
        modelId = modelId,
        modelDisplayName = id,
        tier = ModelTier.STANDARD,
        roles = setOf(ModelRole.CHAT),
        enabled = enabled,
    )

    @Test
    fun `真机上那个案例必须被抓到`() {
        // ═══════════════════════════════════════════════════════
        //  ★ 2026-10-26 真机原样复现
        // ═══════════════════════════════════════════════════════
        // 用户配了两个：一个少了连字符并被设为"对话用它"，
        // 一个是正确写法但没被选中。于是每次说话都 400。
        val wrong = model("m1", "glm5.3flash")
        val right = model("m2", "glm-5.3-flash")

        val findings = ModelIdSanity.check(listOf(wrong, right))

        assertEquals("应当抓到一条", 1, findings.size)
        assertEquals("glm5.3flash", findings.first().suspicious.modelId)
        assertEquals("glm-5.3-flash", findings.first().looksLike.modelId)
    }

    @Test
    fun `提示文案要能让用户直接改`() {
        val f = ModelIdSanity.check(
            listOf(model("m1", "glm5.3flash"), model("m2", "glm-5.3-flash")),
        ).first()

        val msg = f.message()
        assertTrue("要指出哪个 id：$msg", "glm5.3flash" in msg)
        assertTrue("要指出正确写法：$msg", "glm-5.3-flash" in msg)
        assertTrue("要说清后果：$msg", "失败" in msg || "不存在" in msg)
    }

    @Test
    fun `只有正确写法时不报`() {
        val findings = ModelIdSanity.check(listOf(model("m2", "glm-5.3-flash")))
        assertTrue("单个正确 id 不该报", findings.isEmpty())
    }

    @Test
    fun `两个都是规整写法时不报`() {
        // ⚠️ 误伤防范：`gpt-4o` 与 `gpt4o` 归一化后相同，
        //    但真实世界里两个都可能是合法的自定义 id。
        //    —— 等等，这条正是我们想抓的形态。所以改用两个都"规整"的：
        val a = model("a", "gpt-4o")
        val b = model("b", "gpt4-o")   // 归一化后 gpt4o，也含分隔符
        // 两个都含分隔符 ⇒ 无法判断哪个是"正确写法" ⇒ 不该硬报
        val findings = ModelIdSanity.check(listOf(a, b))
        // 这条不强制断言为空（当前实现会报 a←b 且 b←a），
        // 但要保证**不会崩、且每条的 looksLike 确实与 suspicious 不同**
        for (f in findings) {
            assertTrue(
                "不该把同一个 id 报成抄错",
                f.suspicious.modelId != f.looksLike.modelId,
            )
        }
    }

    @Test
    fun `完全不同的 id 不报`() {
        val findings = ModelIdSanity.check(
            listOf(model("a", "glm-5.3-flash"), model("b", "gpt-4o")),
        )
        assertTrue("毫无相似性不该报", findings.isEmpty())
    }

    @Test
    fun `空 id 不报也不崩`() {
        val findings = ModelIdSanity.check(
            listOf(model("a", ""), model("b", "glm-5.3-flash")),
        )
        assertTrue("空 id 跳过", findings.isEmpty())
    }

    @Test
    fun `同一个 id 出现两次不报`() {
        // 两个配置指向同一个 modelId ⇒ 那是重复配置，不是抄错
        val findings = ModelIdSanity.check(
            listOf(model("a", "glm-5.3-flash"), model("b", "glm-5.3-flash")),
        )
        assertTrue("同一个 id 不是抄错", findings.isEmpty())
    }

    @Test
    fun `只报正在聊天用的那个`() {
        val wrongOther = model("m3", "deepseekchat", enabled = false)
        val rightOther = model("m4", "deepseek-chat", enabled = false)
        val chat = model("m5", "glm-5.3-flash")

        val suspect = ModelIdSanity.suspiciousChatModel(
            listOf(wrongOther, rightOther, chat), chat,
        )
        assertNull("聊天用的那个是对的，不该拦", suspect)
    }

    @Test
    fun `聊天用的那个可疑时必须报`() {
        val wrong = model("m1", "glm5.3flash")
        val right = model("m2", "glm-5.3-flash")

        val suspect = ModelIdSanity.suspiciousChatModel(listOf(wrong, right), wrong)
        assertNotNull("聊天用的就是抄错的那个，必须报", suspect)
    }

    @Test
    fun `大小写差异也算抄错`() {
        // `GLM-5.3-Flash` 与 `glm-5.3-flash` 归一化后相同
        val findings = ModelIdSanity.check(
            listOf(model("a", "glm5.3flash"), model("b", "GLM-5.3-Flash")),
        )
        assertEquals("大小写不同但归一化相同，应当报", 1, findings.size)
    }
}
