package com.pocketagent.agentlogic.orchestration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `screen_read` 结构化输出 → [ScreenDigest]。
 *
 * 这里的样例是照着 `ScreenSerializer.toStructured` 的真实键抄的
 * （`package` / `nodes[].text` / `nodes[].desc` / `nodes[].clickable` / `nodes[].id` / `nodes[].nodeId`）。
 * 键名一旦在 `:mcp` 侧改掉而这里没跟，验收会**静默失败**（永远判不出"没出现"），
 * 所以宁可把形状写死在测试里。
 */
class ScreenDigestJsonTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private val real = obj(
        """
        {
          "package": "com.tencent.mm",
          "activity": ".ui.LauncherUI",
          "width": 1080, "height": 2400,
          "source": "ACCESSIBILITY", "confidence": 0.9,
          "capturedAtMs": 1730000000000,
          "nodeCount": 4, "listedCount": 4, "truncated": false,
          "nodes": [
            {"nodeId":"0/0","class":"android.widget.TextView","text":"微信","bounds":[0,0,100,100],"clickable":false},
            {"nodeId":"0/1","class":"android.widget.Button","text":"发送","desc":"发送消息",
             "bounds":[900,2200,1080,2320],"clickable":true,"id":"com.tencent.mm:id/send_btn"},
            {"nodeId":"0/2","class":"android.widget.EditText","text":"周报","bounds":[0,2000,900,2100],"clickable":true,
             "id":"com.tencent.mm:id/input","editable":true}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `真机形状能被翻译成摘要`() {
        val digest = ScreenDigestJson.parse(real)

        assertEquals("com.tencent.mm", digest.packageName)
        assertTrue(digest.texts.contains("发送"))
        assertTrue(digest.texts.contains("发送消息"))
        assertTrue(digest.texts.contains("周报"))
        // 只数可点节点：微信那个标题不算
        assertEquals(2, digest.nodeCount)
    }

    @Test
    fun `没有 nodes 数组时退回 nodeCount 字段`() {
        val digest = ScreenDigestJson.parse(obj("""{"package":"com.x","nodeCount":7}"""))
        assertEquals(7, digest.nodeCount)
    }

    @Test
    fun `值可以用 resource-id 全名 短名或 nodeId 命中`() {
        val digest = ScreenDigestJson.parse(real)

        assertEquals("周报", digest.values["com.tencent.mm:id/input"])
        assertEquals("周报", digest.values["input"])
        assertEquals("周报", digest.values["0/2"])
    }

    @Test
    fun `空白文本不进摘要`() {
        val digest = ScreenDigestJson.parse(obj("""{"nodes":[{"text":"  "},{"desc":""},{"text":"好"}]}"""))
        assertEquals(listOf("好"), digest.texts)
        assertFalse(digest.contains(" "))
    }

    @Test
    fun `文本包含关系是子串而非整段相等`() {
        val digest = ScreenDigestJson.parse(real)
        assertTrue(digest.contains("发送"))
        assertTrue(digest.contains("周"))
        assertFalse(digest.contains("撤回"))
    }

    @Test
    fun `验收条件与摘要配合的三种口径`() {
        val digest = ScreenDigestJson.parse(real)

        assertNull(Expectation.TextVisible("周报").unmetOn(digest))
        assertNull(Expectation.PackageIs("com.tencent.mm").unmetOn(digest))
        assertNull(Expectation.AtLeastNodes(2).unmetOn(digest))
        assertNull(Expectation.ValueIs("input", "周报").unmetOn(digest))

        assertTrue(Expectation.TextVisible("已发送").unmetOn(digest)!!.contains("没有"))
        assertTrue(Expectation.AtLeastNodes(5).unmetOn(digest)!!.contains("加载"))
        assertTrue(Expectation.ValueIs("input", "月报").unmetOn(digest)!!.contains("不是"))
    }

    @Test
    fun `摘要缺失时一律不通过且说的是没读到`() {
        val reason = Expectation.TextVisible("发送").unmetOn(null)
        assertTrue(reason!!.contains("没读到"))
    }

    @Test
    fun `非对象输入退化成空摘要`() {
        val digest = ScreenDigestJson.parseAny(Json.parseToJsonElement("\"oops\""))
        assertEquals(ScreenDigest.EMPTY, digest)
        assertFalse(digest.contains("oops"))
    }
}
