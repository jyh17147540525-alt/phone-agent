package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Compose 稳定性规则**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这条规则来自本项目**两次靠直觉躲过的坑**
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. `pendingImages` 用 `Map<taskId, ByteArray>` 而没塞进 `AgentTaskUi`
 *    （因为 `ByteArray.equals` 是引用比较）
 * 2. `Attachment` 里带含 `ByteArray` 的 `PreparedImage`
 *
 * 两次都躲过了，但**没有东西阻止第三个人再写一次**。
 * 本文件把经验变成可检查的规则。
 */
class ComposeStabilityTest {

    // ═══════════════════════════════════════════════════════════
    //  类型判定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `集合类型不稳定`() {
        for (t in listOf("List", "MutableList", "Set", "Map", "Collection", "Iterable")) {
            assertTrue("「$t」应当被判为不稳定", ComposeStability.isUnstableType(t))
        }
    }

    @Test
    fun `数组类型不稳定`() {
        for (t in listOf("ByteArray", "IntArray", "LongArray", "Array")) {
            assertTrue("「$t」应当被判为不稳定", ComposeStability.isUnstableType(t))
        }
    }

    @Test
    fun `可空不影响判定`() {
        assertTrue(ComposeStability.isUnstableType("List?"))
        assertTrue(ComposeStability.isUnstableType("  List  "))
    }

    @Test
    fun `稳定类型不误报`() {
        // ══════════════════════════════════════════════════════
        //  ★ 假警报会让规则被忽略 —— 那比没有规则更糟
        // ══════════════════════════════════════════════════════
        for (t in listOf("String", "Int", "Long", "Boolean", "Float", "Double")) {
            assertFalse("「$t」是稳定的，不该报", ComposeStability.isUnstableType(t))
        }
    }

    @Test
    fun `枚举与自定义类默认不报`() {
        // ⚠️ 自定义类型只在调用方显式声明时才报 —— 见下一条测试。
        assertFalse(ComposeStability.isUnstableType("TaskState"))
        assertFalse(ComposeStability.isUnstableType("Attachment"))
    }

    @Test
    fun `自定义不稳定类型要显式声明`() {
        // ★ 含 ByteArray 的 data class 在 Compose 眼里也是不稳定的。
        //   但规则层不做传递推导（需要解析类型图），
        //   所以由调用方列出来。
        assertTrue(
            ComposeStability.isUnstableType(
                "PreparedImage",
                customUnstable = setOf("PreparedImage"),
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  类审计
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `带 List 字段的类被报出来`() {
        val issues = ComposeStability.auditClass(
            className = "InstalledPluginsUiState",
            fields = mapOf(
                "plugins" to "List",
                "loading" to "Boolean",
            ),
            hasImmutableAnnotation = false,
        )
        assertEquals("应当只报 List 那一个", 1, issues.size)
        assertEquals("plugins", issues.first().name)
    }

    @Test
    fun `标了 Immutable 就不再报`() {
        // ⚠️ `@Immutable` 是调用方的**显式承诺**，规则不该推翻它 ——
        //    否则规则会和人的判断打架，然后人就把规则关掉了。
        val issues = ComposeStability.auditClass(
            className = "SomeState",
            fields = mapOf("items" to "List"),
            hasImmutableAnnotation = true,
        )
        assertTrue("已标 @Immutable 的类不该再报", issues.isEmpty())
    }

    @Test
    fun `纯稳定字段的类不报`() {
        val issues = ComposeStability.auditClass(
            className = "DraftState",
            fields = mapOf(
                "text" to "String",
                "count" to "Int",
                "enabled" to "Boolean",
            ),
            hasImmutableAnnotation = false,
        )
        assertTrue("没有不稳定字段就不该报", issues.isEmpty())
    }

    @Test
    fun `每个问题都带理由和建议`() {
        // ★ 规则的价值在于"能被行动"。
        //   只说"不稳定"没人知道该做什么。
        val issues = ComposeStability.auditClass(
            className = "X",
            fields = mapOf("items" to "List"),
            hasImmutableAnnotation = false,
        )
        val issue = issues.first()
        assertTrue("必须有理由", issue.reason.isNotBlank())
        assertTrue("必须有建议", issue.suggestion.isNotBlank())
        assertTrue("理由要提到字段名", "items" in issue.reason)
        assertTrue("建议要提到 @Immutable", "@Immutable" in issue.suggestion)
    }

    @Test
    fun `多个不稳定字段都报`() {
        val issues = ComposeStability.auditClass(
            className = "Multi",
            fields = mapOf(
                "a" to "List",
                "b" to "Set",
                "c" to "Map",
                "d" to "String",
            ),
            hasImmutableAnnotation = false,
        )
        assertEquals(3, issues.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ 能不能安全标 @Immutable
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `全 val 的类可以安全承诺不可变`() {
        assertTrue(
            ComposeStability.isSafeImmutableCandidate(
                mapOf("a" to true, "b" to true),
            ),
        )
    }

    @Test
    fun `有 var 的类不能承诺不可变`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条防的是"标了 @Immutable 但界面永不更新"
        // ══════════════════════════════════════════════════════
        // `@Immutable` 是对编译器的**承诺**，不是运行时检查。
        // 标了之后 Compose **不再比较**这个参数。
        // 如果那个 List 之后被 `add` 了，界面**永远不更新** ——
        // 那不是"慢"，是"坏了"，而且极难查（代码看起来完全正常）。
        assertFalse(
            ComposeStability.isSafeImmutableCandidate(
                mapOf("a" to true, "b" to false),
            ),
        )
    }

    @Test
    fun `空字段列表视为安全`() {
        // 无字段的类天然不可变（比如 object 或空 data class）。
        assertTrue(ComposeStability.isSafeImmutableCandidate(emptyMap()))
    }

    // ═══════════════════════════════════════════════════════════
    //  摘要
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `干净时说无需处理`() {
        val s = ComposeStability.summarize("Clean", emptyList())
        assertTrue("要明说无需处理：$s", "稳定" in s || "无需" in s)
    }

    @Test
    fun `有问题时说清几个字段`() {
        val issues = ComposeStability.auditClass(
            className = "Bad",
            fields = mapOf("items" to "List", "ids" to "Set"),
            hasImmutableAnnotation = false,
        )
        val s = ComposeStability.summarize("Bad", issues)
        assertTrue("要给出数量：$s", "2" in s)
        assertTrue("要列出字段名：$s", "items" in s && "ids" in s)
    }

    // ═══════════════════════════════════════════════════════════
    //  真实形状（复刻本项目里的类）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `本项目的 InstalledPluginsUiState 形状`() {
        // 复刻真实的类：`data class UiState(plugins: List<InstalledPlugin>, ...)`
        val issues = ComposeStability.auditClass(
            className = "InstalledPluginsUiState",
            fields = mapOf(
                "plugins" to "List",
                "loading" to "Boolean",
                "pendingUninstall" to "InstalledPlugin",
                "message" to "String",
            ),
            hasImmutableAnnotation = false,
        )
        assertEquals("只有 plugins 不稳定", 1, issues.size)
        assertEquals("plugins", issues.first().name)
    }

    @Test
    fun `本项目的 Attachment 形状`() {
        // 复刻：`data class Attachment(uri, kind, image: PreparedImage?, prepareError)`
        // ⚠️ `PreparedImage` 含 ByteArray ⇒ 不稳定。
        //    而它**没有被放进 AgentTaskUi** 正是靠直觉避开的 ——
        //    现在这条规则能把它变成可检查的。
        val issues = ComposeStability.auditClass(
            className = "Attachment",
            fields = mapOf(
                "uri" to "String",
                "kind" to "String",
                "image" to "PreparedImage?",
                "prepareError" to "String?",
            ),
            hasImmutableAnnotation = false,
            customUnstable = setOf("PreparedImage"),
        )
        assertEquals("image 字段该被报出来", 1, issues.size)
        assertEquals("image", issues.first().name)
    }
}
