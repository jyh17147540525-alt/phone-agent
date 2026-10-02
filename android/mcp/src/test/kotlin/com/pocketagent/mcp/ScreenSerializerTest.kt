package com.pocketagent.mcp

import com.pocketagent.agentlogic.RedactRegion
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化：文本行长什么样、遮蔽照计划执行、截断如实上报。
 *
 * 这一层是**模型看到的全部世界** —— 它错了，模型就会对着错误的世界做决策，
 * 而没有任何一处会报错。
 */
class ScreenSerializerTest {

    // ── fixtures ───────────────────────────────────────────────

    private fun node(
        id: String = "path:0",
        cls: String = "android.widget.Button",
        text: String? = null,
        desc: String? = null,
        bounds: ScreenRect = ScreenRect(100, 50, 340, 90),
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        visible: Boolean = true,
        password: Boolean = false,
        resource: String? = null,
    ) = UiNodeDto(
        nodeId = id, className = cls, text = text, contentDescription = desc, hintText = null,
        bounds = bounds, clickable = clickable, longClickable = false, editable = editable,
        scrollable = scrollable, checkable = false, checked = false, enabled = true,
        focused = false, visible = visible, selected = false, viewIdResourceName = resource,
        depth = 1, password = password,
    )

    private fun dto(nodes: List<UiNodeDto>) = ScreenCaptureDto(
        packageName = "com.example.app",
        activityName = null,
        width = 1440, height = 3200,
        source = CaptureSource.ACCESSIBILITY,
        confidence = 0.7f,
        capturedAtMs = 123L,
        nodes = nodes,
    )

    // ── 文本：头部与单行格式 ───────────────────────────────────

    @Test
    fun `头部包含包名 尺寸 节点数 来源与置信度`() {
        val text = ScreenSerializer.toText(dto(listOf(node(text = "发送"))), emptyList())
        assertTrue(text.startsWith("【屏幕快照】com.example.app 1440×3200 · 节点 1 · 来源=无障碍树 · 置信度 0.7"))
    }

    @Test
    fun `单行格式逐字正确`() {
        val text = ScreenSerializer.toText(
            dto(listOf(node(text = "发送", clickable = true, resource = "com.x:id/send"))),
            emptyList(),
        )
        assertTrue(text.contains("1. Button text=\"发送\" clickable @(220,70) id=com.x:id/send"))
    }

    @Test
    fun `无文本的节点仍然列出（结构信息也是信息）`() {
        val text = ScreenSerializer.toText(dto(listOf(node())), emptyList())
        assertTrue(text.contains("1. Button @(220,70)"))
    }

    @Test
    fun `非回环置信度的取值来源仍照实展示`() {
        val lowConfidence = dto(listOf(node())).copy(confidence = 0.4f, source = CaptureSource.VISION)
        val text = ScreenSerializer.toText(lowConfidence, emptyList())
        assertTrue(text.contains("来源=截图"))
        assertTrue(text.contains("置信度 0.4"))
    }

    // ── 遮蔽：照计划执行 ───────────────────────────────────────

    @Test
    fun `命中的可输入节点文本被替换为遮蔽占位符`() {
        val input = node(cls = "android.widget.EditText", text = "13800138000", editable = true)
        val region = RedactRegion(100, 50, 340, 90) // 与输入框同区

        val text = ScreenSerializer.toText(dto(listOf(input)), listOf(region))
        assertTrue(text.contains("text=\"[已遮蔽]\""))
        assertFalse("原始内容不许出现", text.contains("13800138000"))
    }

    @Test
    fun `结构化输出里同样被遮蔽`() {
        val input = node(text = "13800138000", editable = true)
        val structured = ScreenSerializer.toStructured(dto(listOf(input)), listOf(RedactRegion(100, 50, 340, 90)))
        val text = structured["nodes"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("[已遮蔽]", text)
    }

    @Test
    fun `不可输入节点留在区域内不被遮蔽（那是标签不是输入）`() {
        val label = node(cls = "android.widget.TextView", text = "手机号", editable = false)
        val text = ScreenSerializer.toText(dto(listOf(label)), listOf(RedactRegion(100, 50, 340, 90)))
        assertTrue(text.contains("text=\"手机号\""))
    }

    @Test
    fun `计划为空时不遮蔽（序列化器信任关卡的计划）`() {
        // 遮蔽的"决策"在 PrivacyFilter；序列化器只执行计划。
        // 这条测试把这个分工写死在断言里，防止将来有人把判断悄悄搬进来。
        val input = node(text = "13800138000", editable = true)
        val text = ScreenSerializer.toText(dto(listOf(input)), emptyList())
        assertTrue(text.contains("text=\"13800138000\""))
    }

    // ── 截断：如实上报 ─────────────────────────────────────────

    @Test
    fun `超长字段截断到上限并带省略号`() {
        val long = "字".repeat(200)
        val text = ScreenSerializer.toText(dto(listOf(node(text = long))), emptyList())
        assertTrue(text.contains("字".repeat(ScreenSerializer.MAX_FIELD_CHARS) + "…"))
        assertFalse(text.contains("字".repeat(200)))
    }

    @Test
    fun `节点数超上限时如实报截断`() {
        val nodes = List(ScreenSerializer.MAX_NODES + 5) { node(id = "n$it", text = "行$it") }
        val text = ScreenSerializer.toText(dto(nodes), emptyList())

        assertTrue(
            text.contains("（已截断：共 305 个节点，仅列出前 300 个）"),
        )
        val structured = ScreenSerializer.toStructured(dto(nodes), emptyList())
        assertEquals(305, structured["nodeCount"]!!.jsonPrimitive.int)
        assertEquals(300, structured["listedCount"]!!.jsonPrimitive.int)
        assertEquals(true, structured["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `节点恰好在上限时不报截断`() {
        val nodes = List(ScreenSerializer.MAX_NODES) { node(id = "n$it") }
        val text = ScreenSerializer.toText(dto(nodes), emptyList())
        assertFalse(text.contains("已截断"))
        val structured = ScreenSerializer.toStructured(dto(nodes), emptyList())
        assertEquals(false, structured["truncated"]!!.jsonPrimitive.boolean)
    }

    // ── 结构化细节 ─────────────────────────────────────────────

    @Test
    fun `结构化输出带边界数组与 password 标记`() {
        val input = node(text = "x", editable = true, password = true)
        val json = ScreenSerializer.toStructured(dto(listOf(input)), emptyList())
        val nodeJson = json["nodes"]!!.jsonArray[0].jsonObject

        val bounds = nodeJson["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(100, 50, 340, 90), bounds)
        assertEquals(true, nodeJson["password"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `空节点列表不崩且如实报告为零`() {
        val text = ScreenSerializer.toText(dto(emptyList()), emptyList())
        assertTrue(text.contains("节点 0"))
    }
}
