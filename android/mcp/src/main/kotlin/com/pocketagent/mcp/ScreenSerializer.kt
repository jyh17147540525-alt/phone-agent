package com.pocketagent.mcp

import com.pocketagent.agentlogic.RedactRegion
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 把 [ScreenCaptureDto] 序列化成两种形态：
 *
 * - **文本**（进模型上下文）：紧凑的节点列表，供模型做决策；
 * - **结构化**（`structuredContent`，透传给程序化调用方）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条不许违反的纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **截断必须如实上报。** 上限 [MAX_NODES] 之外的部分会被丢掉，但那**不是**
 *    静默的：文本里有"已截断：共 N 个节点"，结构化里有 `truncated` 与 `nodeCount`。
 *    静默截断会让模型对着半个界面做决策，而它以为看到的是全部。
 * 2. **遮蔽照计划执行，不重新发明判断。** [maskRegions] 来自 `PrivacyFilter`
 *    （上传路径的唯一出口），本类只负责"把计划落到文本上"。
 * 3. **坐标给出的是中心点**（与 `UiNode.describe()` 的约定一致）——点击类工具
 *    将来直接用这个坐标，换算法（bounds → 中心）只允许出现在这一个地方。
 */
object ScreenSerializer {

    /**
     * 一次序列化最多列出的节点数。
     *
     * 取值依据：真机实测桌面 123 个节点、本项目 App 30 个 —— 300 对常见界面
     * 是"永远够用"，对异常界面是"文字量可控"。超出必须报截断（见纪律 1）。
     */
    const val MAX_NODES: Int = 300

    /** 单个文本字段的截断长度（`text` / `contentDescription`）。 */
    const val MAX_FIELD_CHARS: Int = 80

    /** 被遮蔽的输入框文本的占位符。选中文短词，模型一眼能懂。 */
    const val MASKED_TEXT: String = "[已遮蔽]"

    private const val ELLIPSIS = "…"

    // ─────────────────────────────────────────────────────────────
    //  文本形态（进模型上下文）
    // ─────────────────────────────────────────────────────────────

    fun toText(dto: ScreenCaptureDto, maskRegions: List<RedactRegion>): String {
        val listed = dto.nodes.take(MAX_NODES)
        return buildString {
            append("【屏幕快照】")
            append(dto.packageName.ifBlank { "(未知应用)" })
            append(' ').append(dto.width).append('×').append(dto.height)
            append(" · 节点 ").append(dto.nodes.size)
            append(" · 来源=").append(sourceLabel(dto.source))
            // ⚠️ 置信度必须出现在头部：树不含视觉信息（图标、图片按钮、自绘文字），
            //    模型据此决定"要不要请用户补充信息"，而不是默认看到的就是全部。
            append(" · 置信度 ").append(formatConfidence(dto.confidence))
            append('\n')

            listed.forEachIndexed { index, node ->
                append(nodeLine(index + 1, node, maskRegions)).append('\n')
            }

            if (dto.nodes.size > listed.size) {
                append("（已截断：共 ").append(dto.nodes.size)
                    .append(" 个节点，仅列出前 ").append(listed.size).append(" 个）")
            }
        }.trimEnd('\n')
    }

    /** 单行格式：`1. Button text="发送" clickable @(1223,2299) id=com.x:id/send` */
    private fun nodeLine(index: Int, node: UiNodeDto, maskRegions: List<RedactRegion>): String =
        buildString {
            append(index).append(". ")
            append(node.className.substringAfterLast('.').ifBlank { "(无类型)" })

            val text = node.text?.takeIf { it.isNotBlank() }
            if (text != null) {
                append(" text=\"")
                append(if (isMasked(node, maskRegions)) MASKED_TEXT else truncate(text))
                append('"')
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                append(" desc=\"").append(truncate(it)).append('"')
            }

            if (node.clickable) append(" clickable")
            if (node.editable) append(" [可输入]")
            if (node.scrollable) append(" [可滚动]")

            append(" @(").append(node.bounds.centerX).append(',').append(node.bounds.centerY).append(')')
            node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
        }

    // ─────────────────────────────────────────────────────────────
    //  结构化形态（structuredContent）
    // ─────────────────────────────────────────────────────────────

    fun toStructured(dto: ScreenCaptureDto, maskRegions: List<RedactRegion>): JsonObject =
        buildJsonObject {
            put("package", dto.packageName)
            dto.activityName?.let { put("activity", it) }
            put("width", dto.width)
            put("height", dto.height)
            put("source", dto.source.name)
            put("confidence", dto.confidence)
            put("capturedAtMs", dto.capturedAtMs)
            put("nodeCount", dto.nodes.size)
            put("listedCount", minOf(dto.nodes.size, MAX_NODES))
            put("truncated", dto.nodes.size > MAX_NODES)
            putJsonArray("nodes") {
                for (node in dto.nodes.take(MAX_NODES)) {
                    addJsonObject { nodeJson(node, maskRegions) }
                }
            }
        }

    /**
     * 把一个节点写进 builder。
     *
     * ⚠️ 必须是**扩展函数、直接 put** —— 不能写成"返回一个 buildJsonObject、
     *    再被 `addJsonObject { … }` 收下"：lambda 的返回值会被丢弃，
     *    数组里留下一串**空对象**（计数对、内容没了，全程静默）。
     *    这是被 `结构化输出里同样被遮蔽` 那条测试当场抓住过的形态。
     */
    private fun JsonObjectBuilder.nodeJson(node: UiNodeDto, maskRegions: List<RedactRegion>) {
        put("nodeId", node.nodeId)
        put("class", node.className)
        node.text?.takeIf { it.isNotBlank() }?.let {
            put("text", if (isMasked(node, maskRegions)) MASKED_TEXT else it)
        }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { put("desc", it) }
        putJsonArray("bounds") {
            add(node.bounds.left)
            add(node.bounds.top)
            add(node.bounds.right)
            add(node.bounds.bottom)
        }
        put("clickable", node.clickable)
        put("editable", node.editable)
        put("scrollable", node.scrollable)
        put("enabled", node.enabled)
        // 密码框标记对程序化调用方有用（"这个别碰"）；它只暴露"字段类型"，不暴露内容。
        put("password", node.password)
        put("depth", node.depth)
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { put("id", it) }
    }

    // ─────────────────────────────────────────────────────────────
    //  遮蔽与格式化
    // ─────────────────────────────────────────────────────────────

    /**
     * 这个节点的文本该不该遮蔽。
     *
     * 规则：**可输入的节点** ∩ 命中任一遮蔽区域。非可输入节点留在区域内的是标签
     * 文字（"搜索""手机号"），不是用户输入 —— 照抄 `PrivacyFilter` 对树路径的语义。
     */
    private fun isMasked(node: UiNodeDto, maskRegions: List<RedactRegion>): Boolean {
        if (!node.editable) return false
        return maskRegions.any { region ->
            region.left < node.bounds.right && node.bounds.left < region.right &&
                region.top < node.bounds.bottom && node.bounds.top < region.bottom
        }
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_FIELD_CHARS) s else s.take(MAX_FIELD_CHARS) + ELLIPSIS

    private fun sourceLabel(source: CaptureSource): String = when (source) {
        CaptureSource.ACCESSIBILITY -> "无障碍树"
        CaptureSource.VISION -> "截图"
        CaptureSource.HYBRID -> "树+截图"
        CaptureSource.OCR_ONLY -> "OCR"
    }

    /**
     * 置信度格式化。
     *
     * ⚠️ 显式 `Locale.US` —— `"%.1f".format(x)` 走默认 Locale，在某些区域设置下
     *    会把小数点写成逗号（"0,7"），而它出现在**给模型看的文本**里。
     *    这类 bug 只在特定 Locale 的机器上复现，属于最难查的一类。
     */
    private fun formatConfidence(value: Float): String =
        String.format(Locale.US, "%.1f", value)
}
