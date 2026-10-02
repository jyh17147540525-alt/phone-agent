package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh 补丁层配置的渲染与合并。
 *
 * 这一层错的后果全是静默的：渲染出一个 dsh 读不懂的 YAML（插件加载失败，
 * 报错指向"另一个 mcp-client 实例"）、合并时把用户的手工条目吞掉、
 * 或者反复投递长出第二个 `insert` 块（serverName 冲突）。
 */
class DshMcpConfigPatchTest {

    private fun patch(token: String = "tok123") = DshMcpConfigPatch(
        url = "http://127.0.0.1:12345/mcp",
        token = token,
    )

    /** 本块在文本里出现的次数（哨兵是它的唯一标记）。 */
    private fun blockCount(text: String): Int = text.split(DshMcpConfigPatch.SENTINEL).size - 1

    // ── 渲染 ───────────────────────────────────────────────────

    @Test
    fun `entryBlock 与设计稿逐字一致`() {
        val expected = """
            # ─── PocketAgent MCP 能力桥（由应用生成，请勿手改本块）───
            - insert:
                - id: mcp-pocketagent
                  name: '@deepseek-ai/dsh-mcp-client'
                  config:
                    serverName: "phone"
                    transport: streamable-http
                    url: "http://127.0.0.1:12345/mcp"
                    headers:
                      Authorization: "Bearer tok123"
                    toolCallTimeoutMs: 30000
                    failOnStartupError: false
        """.trimIndent()
        assertEquals(expected, patch().entryBlock())
    }

    @Test
    fun `token 里的 yaml 雷区字符被引号与转义保护`() {
        // token 是随机串，可能含 `:`、`#`、前导 `*`、甚至引号本身 ——
        // 裸标量下每一个都有自己的解析规则。yamlScalar 无条件加双引号。
        val line = patch(token = "a:b#c\"d").entryBlock()
            .lineSequence()
            .first { it.trimStart().startsWith("Authorization:") }
        assertTrue("引号包裹", line.endsWith("\""))
        assertTrue("原值保留", line.contains("a:b#c"))
        assertTrue("内部引号被转义", line.contains("\\\""))
    }

    @Test
    fun `draftDocument 带用法说明且包含本块`() {
        val doc = patch().draftDocument()
        assertTrue(doc.startsWith("# PocketAgent MCP 能力桥"))
        assertTrue(doc.contains(patch().entryBlock()))
    }

    // ── 合并：四种情形 ─────────────────────────────────────────

    @Test
    fun `合并进不存在的文件`() {
        assertEquals(
            McpPatchMerge.Merged(patch().entryBlock() + "\n"),
            patch().mergeIntoPatchFile(null),
        )
    }

    @Test
    fun `合并进出厂状态（注释 + 空数组）`() {
        // 真机上 headless profile 的现状就是这个形状（2026-10-02 实地读取）。
        val existing = """
            # Your patch layer for this dsh profile, applied after every bundle layer:
            # a top-level YAML array of loader patch entries (… insert lists; !!js …)
            []
        """.trimIndent()

        val merged = (patch().mergeIntoPatchFile(existing) as McpPatchMerge.Merged).text

        assertTrue("用户的注释必须原样保留", merged.startsWith("# Your patch layer"))
        assertFalse("[] 占位行应被替换", merged.lineSequence().any { it.trim() == "[]" })
        assertEquals("本块恰好一份", 1, blockCount(merged))
    }

    @Test
    fun `合并进已有其它条目的文件是追加且保序`() {
        val existing = """
            # 用户手写的注释
            - insert:
                - id: mcp-other
                  name: 'some-other-plugin'
        """.trimIndent()

        val merged = (patch().mergeIntoPatchFile(existing) as McpPatchMerge.Merged).text

        val otherIndex = merged.indexOf("mcp-other")
        val ourIndex = merged.indexOf(DshMcpConfigPatch.SENTINEL)
        assertTrue("其它条目必须保留", otherIndex >= 0)
        assertTrue("我们的块追加在它之后", ourIndex > otherIndex)
        assertEquals(1, blockCount(merged))
    }

    @Test
    fun `已有本块时原位替换 —— 幂等且不产生第二份`() {
        val first = (patch().mergeIntoPatchFile(null) as McpPatchMerge.Merged).text
        val second = (patch().mergeIntoPatchFile(first) as McpPatchMerge.Merged).text

        assertEquals("对同一文件合并两次结果相同", first, second)
        assertEquals("插件 id 只有一份", 1, Regex("id: mcp-pocketagent").findAll(second).count())
    }

    @Test
    fun `哨兵替换是原位的（块不会被移到末尾）`() {
        val before = "# 注释\n- insert:\n    - id: before\n" +
            patch().entryBlock() + "\n" +
            "- insert:\n    - id: after\n"

        val merged = (patch().mergeIntoPatchFile(before) as McpPatchMerge.Merged).text

        val iBefore = merged.indexOf("before")
        val iOurs = merged.indexOf(DshMcpConfigPatch.SENTINEL)
        val iAfter = merged.indexOf("after")
        assertTrue("前条目在前", iBefore < iOurs)
        assertTrue("后条目在后", iOurs < iAfter)
        assertEquals(1, blockCount(merged))
        // 旧块内容不得残留（那一版正是在这里出过错：新旧块并存）
        assertEquals(1, Regex("id: mcp-pocketagent").findAll(merged).count())
    }

    // ── 拒绝合并（看不懂就不动手）─────────────────────────────

    @Test
    fun `顶层非数组时拒绝合并`() {
        assertTrue(patch().mergeIntoPatchFile("{}") is McpPatchMerge.CannotMerge)
        assertTrue(patch().mergeIntoPatchFile("foo: bar\n") is McpPatchMerge.CannotMerge)
        assertTrue(patch().mergeIntoPatchFile("[a, b]\n") is McpPatchMerge.CannotMerge)
    }

    @Test
    fun `含 YAML 文档分隔符时拒绝合并`() {
        // 追加到 `---` 之后会变成一份**新文档**，语义不是数组追加 —— 静默且破坏。
        val result = patch().mergeIntoPatchFile("---\n- insert:\n    - id: x\n")
        assertTrue(result is McpPatchMerge.CannotMerge)
    }

    // ── 构造参数校验 ───────────────────────────────────────────

    @Test
    fun `非回环 url 被拒`() {
        val e = runCatching { DshMcpConfigPatch(url = "http://10.0.0.8:12345/mcp", token = "t") }
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `空 token 被拒`() {
        val e = runCatching { DshMcpConfigPatch(url = "http://127.0.0.1:1/mcp", token = " ") }
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `非法 serverName 被拒`() {
        val e = runCatching {
            DshMcpConfigPatch(url = "http://127.0.0.1:1/mcp", token = "t", serverName = "有中文")
        }
        assertTrue(e.exceptionOrNull() is IllegalArgumentException)
    }
}
