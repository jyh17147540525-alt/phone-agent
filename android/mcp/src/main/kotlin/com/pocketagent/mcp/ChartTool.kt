package com.pocketagent.mcp

import com.pocketagent.filelogic.ChartKind
import com.pocketagent.filelogic.ChartRender
import com.pocketagent.filelogic.ChartSeries
import com.pocketagent.filelogic.ChartSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 工作区里的一个文件（读写）。压缩包与图表共用这一层。 */
interface WorkFilePort {
    suspend fun read(relativePath: String): ByteArray
    suspend fun write(relativePath: String, bytes: ByteArray)
}

/**
 * `chart_make`：把一组数字画成 PNG。
 *
 * 为什么要有：让模型"自己写 matplotlib"必须有 root 容器 + 装包，而画一张
 * 折线图不该以装环境为前提。这里用 `:filelogic` 的纯 Kotlin PNG 编码器，
 * 手机上离线就能出图。
 *
 * 图上只有刻度和图例色块（点阵字只做数字），类别名与数值在返回文字里给全，
 * 模型会照样复述 —— 这点写在工具描述里，免得它以为图上有字。
 */
class ChartTool(private val port: WorkFilePort) : McpTool {

    override val name: String = "chart_make"

    override val description: String =
        "把数据画成柱状图或折线图，存成工作区里的 PNG。图上只标数字刻度（类别名在返回的文字里），" +
            "适合放进幻灯片或直接发给用户看趋势。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "存哪里，例如 输出/季度趋势.png")
            }
            putJsonObject("kind") {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive("bar"), JsonPrimitive("line"))))
                put("description", "柱状图 bar 或折线图 line，默认 bar")
            }
            putJsonObject("title") {
                put("type", "string")
                put("description", "图表标题（只记在返回文字里，不画进图）")
            }
            putJsonObject("categories") {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "横轴的类别，例如 [\"Q1\",\"Q2\",\"Q3\",\"Q4\"]")
            }
            putJsonObject("series") {
                put("type", "array")
                put("description", "一组或多组数据，每组 {label, values}；values 个数必须与 categories 一致")
                put("items", buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("label") { put("type", "string") }
                        putJsonObject("values") {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "number") })
                        }
                    }
                })
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("categories"))
            add(JsonPrimitive("series"))
        }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val path = args["path"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (path.isEmpty()) return ToolOutcome.Failed("要给我图片存哪里，例如 输出/趋势.png。")
        val categories = (args["categories"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNullSafe()?.trim() }
            ?.filter { it.isNotEmpty() }.orEmpty()
        if (categories.isEmpty()) return ToolOutcome.Failed("要给 categories（横轴的类别），例如 [\"Q1\",\"Q2\"]。")

        val rawSeries = args["series"] as? JsonArray
        if (rawSeries == null || rawSeries.isEmpty()) {
            return ToolOutcome.Failed("要给 series，形状是 [{\"label\":\"销量\",\"values\":[1,2,3]}]。")
        }
        val series = ArrayList<ChartSeries>()
        for ((i, element) in rawSeries.withIndex()) {
            val obj = element as? JsonObject
                ?: return ToolOutcome.Failed("series 第 ${i + 1} 项应该是对象，里面放 label 和 values。")
            val label = obj["label"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
                .ifEmpty { "第 ${i + 1} 组" }
            val valuesRaw = obj["values"] as? JsonArray
                ?: return ToolOutcome.Failed("series 第 ${i + 1} 项「$label」缺 values。")
            val values = valuesRaw.mapNotNull { el ->
                (el as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.toDoubleOrNull()
            }
            if (values.size != valuesRaw.size) {
                return ToolOutcome.Failed("series 第 ${i + 1} 项「$label」里有不是数字的值。")
            }
            series += ChartSeries(label, values)
        }

        val kindText = args["kind"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.lowercase().orEmpty()
        val kind = when (kindText) {
            "", "bar", "柱", "柱状", "柱状图" -> ChartKind.BAR
            "line", "折线", "折线图", "曲线" -> ChartKind.LINE
            else -> return ToolOutcome.Failed("kind 只认 bar 或 line，收到的是「$kindText」。")
        }
        val title = args["title"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()

        val spec = ChartSpec(title = title, kind = kind, categories = categories, series = series)
        ChartRender.validate(spec)?.let { return ToolOutcome.Failed(it) }

        val png = runCatching { ChartRender.render(spec) }.getOrElse { error ->
            return ToolOutcome.Failed("画图失败：${error.message ?: "参数超出范围"}")
        }
        runCatching { port.write(path, png) }.getOrElse {
            return ToolOutcome.Failed("写不进「$path」，检查一下工作区里这个位置能不能写。")
        }
        val kindName = if (spec.kind == ChartKind.BAR) "柱状图" else "折线图"
        val head = "$kindName 画好了：$path（${png.size} 字节，${categories.size} 个类别，${series.size} 组数据）。"
        val note = "图上只有数字刻度，类别与数值在这里："
        return ToolOutcome.Success("$head\n$note\n${ChartRender.summary(spec)}")
    }
}

/** `JsonPrimitive.contentOrNull` 在 `JsonNull` 上会给出字面量 "null"，必须自己判类型。 */
internal fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
