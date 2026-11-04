package com.pocketagent.agentlogic.orchestration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 `screen_read` 的结构化输出翻译成 [ScreenDigest]。
 *
 * ## 为什么单独一层
 *
 * 验收条件（[Expectation.unmetOn]）只认 [ScreenDigest]，而屏幕的**来源**有三种：
 * 无障碍树、root 直读 dump、将来的 SurfaceControl。三种来源的 JSON 形状迟早会分叉，
 * 所以"取哪几个字段"这件事必须集中在一处 —— 否则每个校验点都各写一遍，
 * 某个来源少了一个字段就会有校验**静默通过**（永远判不出"没出现"）。
 *
 * ## nodeCount 用的是「可点节点数」
 *
 * [Expectation.AtLeastNodes] 的语义是"页面渲染完了"，判据是可交互节点的数量，
 * 不是全部节点：一个加载中的列表可能有 30 个容器节点却没有一个按钮。
 * 所以在有 `nodes` 数组时按 `clickable` 统计；拿不到数组时才退回 `nodeCount` 字段。
 */
object ScreenDigestJson {

    fun parse(json: JsonObject): ScreenDigest {
        val nodes = json["nodes"] as? JsonArray
        val texts = mutableListOf<String>()
        val values = mutableMapOf<String, String>()
        var clickable = 0

        nodes?.forEach { element ->
            val node = element as? JsonObject ?: return@forEach
            val text = node.string("text")
            val desc = node.string("desc")
            if (!text.isNullOrBlank()) texts += text
            if (!desc.isNullOrBlank()) texts += desc
            if (node.bool("clickable") == true) clickable++

            if (!text.isNullOrBlank()) {
                // 三种键都放进去：模型可能按 resource-id 全名、短名或 nodeId 说"这里应该是 X"。
                // 多放键没有副作用（查不到只说明那个键没被用到），少放会让验收永远失败。
                node.string("id")?.takeIf { it.isNotBlank() }?.let { id ->
                    values[id] = text
                    values[id.substringAfterLast('/')] = text
                }
                node.string("nodeId")?.takeIf { it.isNotBlank() }?.let { values[it] = text }
            }
        }

        val counted = if (nodes != null && nodes.isNotEmpty()) clickable else (json.int("nodeCount") ?: 0)
        return ScreenDigest(
            packageName = json.string("package")?.takeIf { it.isNotBlank() },
            texts = texts,
            nodeCount = counted,
            values = values,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** 供测试与调试：把任意 [JsonElement] 当对象解析（非对象时给空摘要）。 */
    internal fun parseAny(element: JsonElement): ScreenDigest =
        (element as? JsonObject)?.let(::parse) ?: ScreenDigest.EMPTY
}
