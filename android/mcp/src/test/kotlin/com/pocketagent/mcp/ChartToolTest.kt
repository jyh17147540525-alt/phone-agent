package com.pocketagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `chart_make` 的对外行为（渲染细节在 `:filelogic` 的 ChartRenderTest 里）。 */
class ChartToolTest {

    private class FakeFiles : WorkFilePort {
        val files = LinkedHashMap<String, ByteArray>()
        override suspend fun read(relativePath: String): ByteArray =
            files[relativePath] ?: throw java.io.FileNotFoundException(relativePath)
        override suspend fun write(relativePath: String, bytes: ByteArray) {
            files[relativePath] = bytes
        }
    }

    private fun series(label: String, vararg values: Number): JsonObject = buildJsonObject {
        put("label", label)
        put("values", JsonArray(values.map { JsonPrimitive(it) }))
    }

    @Test
    fun `柱状图画好并回一段可读的汇总`() = runBlocking {
        val port = FakeFiles()
        val out = ChartTool(port).call(
            buildJsonObject {
                put("path", "输出/趋势.png")
                put("title", "季度销量")
                put("categories", JsonArray(listOf("Q1", "Q2").map { JsonPrimitive(it) }))
                put("series", JsonArray(listOf(series("销量", 10, 20))))
            },
        )
        assertTrue(out is ToolOutcome.Success)
        val text = (out as ToolOutcome.Success).text
        assertTrue(text.contains("柱状图"))
        assertTrue(text.contains("输出/趋势.png"))
        assertTrue(text.contains("销量=10"))
        val png = port.files["输出/趋势.png"]!!
        assertEquals(0x89.toByte(), png[0])
    }

    @Test
    fun `折线图按 kind 走`() = runBlocking {
        val port = FakeFiles()
        val out = ChartTool(port).call(
            buildJsonObject {
                put("path", "a.png")
                put("kind", "line")
                put("categories", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
                put("series", JsonArray(listOf(series("s", 1, 2))))
            },
        )
        assertTrue((out as ToolOutcome.Success).text.contains("折线图"))
    }

    @Test
    fun `不认识的 kind 会说清楚`() = runBlocking {
        val out = ChartTool(FakeFiles()).call(
            buildJsonObject {
                put("path", "a.png")
                put("kind", "饼图")
                put("categories", JsonArray(listOf(JsonPrimitive("1"))))
                put("series", JsonArray(listOf(series("s", 1))))
            },
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("只认 bar 或 line"))
    }

    @Test
    fun `数值不是数字时不会静默丢掉`() = runBlocking {
        val out = ChartTool(FakeFiles()).call(
            buildJsonObject {
                put("path", "a.png")
                put("categories", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
                put("series", JsonArray(listOf(buildJsonObject {
                    put("label", "s")
                    put("values", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("很多"))))
                })))
            },
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("不是数字"))
    }

    @Test
    fun `缺 path 或类别都提示怎么给`() = runBlocking {
        val tool = ChartTool(FakeFiles())
        val noPath = tool.call(buildJsonObject {
            put("categories", JsonArray(listOf(JsonPrimitive("1"))))
            put("series", JsonArray(listOf(series("s", 1))))
        })
        assertTrue((noPath as ToolOutcome.Failed).reason.contains("存哪里"))

        val noCats = tool.call(buildJsonObject {
            put("path", "a.png")
            put("series", JsonArray(listOf(series("s", 1))))
        })
        assertTrue((noCats as ToolOutcome.Failed).reason.contains("categories"))
    }

    @Test
    fun `类别数与数值数不一致时拦住`() = runBlocking {
        val out = ChartTool(FakeFiles()).call(
            buildJsonObject {
                put("path", "a.png")
                put("categories", JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2"))))
                put("series", JsonArray(listOf(series("s", 1))))
            },
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("对不上"))
    }
}
