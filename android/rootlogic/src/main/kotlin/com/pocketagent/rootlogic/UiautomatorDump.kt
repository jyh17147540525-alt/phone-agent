package com.pocketagent.rootlogic

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * **`uiautomator dump` 的解析器** —— root 档"不依赖无障碍读屏"的底座。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要它（两档能力的真正分水岭）
 * ═══════════════════════════════════════════════════════════════
 *
 * 普通档读屏 = 无障碍服务（`AccessibilityService.rootInActiveWindow`）：
 * 要用户去设置里开权限，且**服务连不上时什么都读不到**。
 *
 * root 档读屏 = `su -c uiautomator dump <file>` → 一份 XML。
 * 它的价值不在"更准"，在**不需要无障碍**：
 * 用户把无障碍关掉（很多人出于省电或隐私会关）时，root 用户仍然能读屏。
 *
 * 这就是「满血」与「占屏」的实际差别之一 —— 不是"能不能"，是"依赖谁"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这个解析器只做**搬运**，不做判断
 * ═══════════════════════════════════════════════════════════════
 *
 * 敏感判定、可点击性推断、坐标合法性全在别的层（`:mcp` / `:safety`）。
 * 在这里"顺手判一下"会造出第二份迟早漂移的规则。
 *
 * ⚠️ 解析失败**必须区分三种情况**（调用方的动作完全不同）：
 *   · 空串 / 没有 `<hierarchy>` → 这不是 dump 的输出（su 报错混进了 stdout？）
 *   · XML 结构坏 → 系统版本差异或输出被截断
 *   · 有 hierarchy 但零节点 → 真的没有可读界面（比如锁屏、DRM）
 * 全归成"读不到"会让排查从"看一眼就知道"变成"猜"。
 */
object UiautomatorDump {

    /** 一个节点的矩形，与 `[x1,y1][x2,y2]` 一一对应。 */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)

        /** 退化成一个点或负面积 —— 这种节点点不中，但要**保留**（它是布局的一部分）。 */
        val isEmpty: Boolean get() = width <= 0 || height <= 0

        val centerX: Int get() = left + width / 2
        val centerY: Int get() = top + height / 2
    }

    /**
     * dump 里的一个节点。
     *
     * [nodeId] 是**索引路径**（`"0/2/1"`），与无障碍侧的 `nodeId` 语义一致：
     * 它只在本次 dump 内有效，跨 dump 必须重新解析（与 `ResolvedNodeRef` 的
     * "防跨快照漂移"同一条纪律）。
     */
    data class DumpNode(
        val nodeId: String,
        val className: String,
        val packageName: String,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
        val selected: Boolean,
        val password: Boolean,
        val depth: Int,
    ) {
        /** 元素上"有没有字" —— 空文本节点既不可读也不可搜，但常常可以点（图标按钮）。 */
        val hasLabel: Boolean get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()
    }

    /** 一次 dump 的全貌。 */
    data class Tree(
        val packageName: String,
        val width: Int,
        val height: Int,
        val nodes: List<DumpNode>,
    ) {
        /** 可点击且有点"面积"的节点 —— 模型真正能操作的那些。 */
        val tappable: List<DumpNode> get() = nodes.filter { it.clickable && !it.bounds.isEmpty }

        /** 有文字描述的节点 —— 读屏摘要的来源。 */
        val labeled: List<DumpNode> get() = nodes.filter { it.hasLabel }
    }

    /** 解析失败的原因。分成三类，因为调用方的下一步完全不同。 */
    sealed interface Failure {
        /** 里面根本没有 `<hierarchy>` —— 多半是 `su` 的报错混进了 stdout。 */
        data class NotADump(val preview: String) : Failure

        /** 有 hierarchy，但 XML 坏了（被截断 / 系统版本不兼容）。 */
        data class Malformed(val reason: String) : Failure

        /** XML 正常，但一个节点都没有 —— 真的没东西可读。 */
        data object Empty : Failure
    }

    /**
     * 解析一份 dump。
     *
     * @return 成功给 [Tree]；失败给 [Failure]（不抛异常 —— 这是"读屏"路径，
     *   抛异常会让一次界面变化升级成一次任务失败）。
     */
    fun parse(xml: String): Result<Tree> {
        val trimmed = xml.trim()
        if (trimmed.isEmpty() || !trimmed.contains("<hierarchy")) {
            return Result.failure(DumpException(Failure.NotADump(trimmed.take(120))))
        }
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // ★ 关掉外部实体：dump 是我们自己跑出来的，但这条路径将来可能
                //   喂进别的 XML（比如用户给的），提前关掉是零成本的。
                isExpandEntityReferences = false
                isNamespaceAware = false
            }
            // 关 DTD / 外部实体（XXE）—— 标准做法，别省。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.newDocumentBuilder().parse(trimmed.byteInputStream())
        } catch (e: Exception) {
            return Result.failure(DumpException(Failure.Malformed(e.message ?: e.javaClass.simpleName)))
        }

        val root = document.documentElement
            ?: return Result.failure(DumpException(Failure.Malformed("没有根元素")))
        if (root.tagName != "hierarchy") {
            return Result.failure(DumpException(Failure.Malformed("根元素是 <${root.tagName}>，不是 <hierarchy>")))
        }

        // ① 先收集所有 <node>，按出现顺序编号，再算索引路径
        val flat = mutableListOf<DumpNode>()
        collect(root, path = "", depth = 0, into = flat)

        if (flat.isEmpty()) return Result.failure(DumpException(Failure.Empty))

        // ② 屏幕尺寸：dump 的 hierarchy 本身不带宽高，只能从节点包围盒反推。
        //   ★ 取**所有节点的并集**而不是"第一个节点"：第一个节点的 bounds 常常是
        //     一个底部导航条的局部区域，用它当屏幕尺寸会让上层算出的坐标全偏。
        val width = flat.maxOfOrNull { it.bounds.right } ?: 0
        val height = flat.maxOfOrNull { it.bounds.bottom } ?: 0
        val pkg = flat.firstOrNull { it.packageName.isNotEmpty() }?.packageName.orEmpty()

        return Result.success(Tree(packageName = pkg, width = width, height = height, nodes = flat))
    }

    /** 解析失败时抛的异常 —— 只为带上 [Failure]，调用方读 `reason` 判定分支。 */
    class DumpException(val reason: Failure) : Exception(
        when (reason) {
            is Failure.NotADump -> "输出里没有 <hierarchy>：${reason.preview}"
            is Failure.Malformed -> "XML 解析失败：${reason.reason}"
            Failure.Empty -> "dump 里没有任何节点"
        },
    )

    // ── 内部 ──────────────────────────────────────────────────

    private fun collect(parent: Element, path: String, depth: Int, into: MutableList<DumpNode>) {
        var childIndex = 0
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && (child as Element).tagName == "node") {
                val id = if (path.isEmpty()) "$childIndex" else "$path/$childIndex"
                into += child.toNode(id, depth)
                collect(child, id, depth + 1, into)
                childIndex++
            }
            child = child.nextSibling
        }
    }

    private fun Element.toNode(nodeId: String, depth: Int): DumpNode = DumpNode(
        nodeId = nodeId,
        className = attr("class"),
        packageName = attr("package"),
        text = attr("text").ifBlank { null },
        contentDescription = attr("content-desc").ifBlank { null },
        resourceId = attr("resource-id").ifBlank { null },
        bounds = parseBounds(attr("bounds")),
        // ★ 这些布尔值 dump 用的是字符串 "true"/"false"；
        //   缺失时按 false 处理（宁可少报一个可点，也不要凭空造一个可点）。
        clickable = attr("clickable").toBool(),
        longClickable = attr("long-clickable").toBool(),
        scrollable = attr("scrollable").toBool(),
        checkable = attr("checkable").toBool(),
        checked = attr("checked").toBool(),
        // ★ enabled 例外：dump 里缺失**不等于**禁用。默认 false 会把整棵可用的树
        //   判成"全部禁用"，那比不知道更糟。所以缺失按 true。
        enabled = attr("enabled").toBool(default = true),
        focused = attr("focused").toBool(),
        selected = attr("selected").toBool(),
        password = attr("password").toBool(),
        depth = depth,
    )

    private fun Element.attr(name: String): String = getAttribute(name)?.trim().orEmpty()

    private fun String.toBool(default: Boolean = false): Boolean = when (lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    /**
     * 解析 `[x1,y1][x2,y2]`。
     *
     * ⚠️ 解析不出来时返回**退化矩形**（全 0），不是抛异常：
     *   dump 里偶发出现 `bounds="[0,0][0,0]"` 是正常的（隐藏或零尺寸的节点），
     *   为一个节点丢掉整棵树是最差的选择。
     */
    fun parseBounds(raw: String): Rect {
        val nums = raw.split('[', ']', ',')
            .mapNotNull { it.trim().toIntOrNull() }
        return if (nums.size >= 4) {
            Rect(
                left = minOf(nums[0], nums[2]),
                top = minOf(nums[1], nums[3]),
                right = maxOf(nums[0], nums[2]),
                bottom = maxOf(nums[1], nums[3]),
            )
        } else {
            Rect(0, 0, 0, 0)
        }
    }
}
