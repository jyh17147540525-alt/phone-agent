package com.pocketagent.ui.memory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.pocketagent.memorylogic.Atom
import com.pocketagent.memorylogic.MemoryGraph
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.theme.LocalIsDark
import com.pocketagent.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * **长期记忆 · 记忆星图** —— 可任意角度旋转、可缩放的 3D 记忆团。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "点开后会出现一个 3D 的记忆节点可交互记忆团……重点事件由大的异色光点
 *  标注，用户点击对应的光点会出现相关的记忆事件。"
 * "记得是可任意角度旋转、可随意放大缩小的。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 几处刻意的取舍
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **位置来自纯函数，不是每帧随机**。
 *    力导向如果带随机抖动，用户每次打开看到的都是不同的星空 ——
 *    而位置在他心里是锚点（"那条重要的事在右上角"）。见 `MemoryGraph`。
 *
 * 2. **旋转是自由的（yaw 不设限），俯仰有限位**。
 *    yaw 转多少圈都行；pitch 卡在 ±80° —— 超过就会翻过去，
 *    星空上下颠倒之后人立刻失去方向感。
 *
 * 3. **双指缩放 + 双指也能转**。
 *    用 `detectTransformGestures` 一次拿到 pan/zoom/rotation：
 *    单指拖动 = 转；双指张开 = 放大。这和用户对 3D 查看器的既有习惯一致。
 *
 * 4. **一上手就停自转**。用户想看的那个点，不能被自己慢慢转走。
 *
 * 5. **重点事件用"光晕 + 大点 + 异色"三重区分**。
 *    在密集图里只把点画大一点根本看不出来；光晕是暗底上唯一能
 *    把注意力拉过去的手法。
 *
 * 6. **点中之后给原文，不给摘要**。
 *    用户来这里是为了确认"它到底记住了什么"，给摘要等于让他猜。
 */
@Composable
fun MemoryGraphScreen(
    atoms: List<Atom>,
    modifier: Modifier = Modifier,
    /** 返回上一页。**必须传** —— 二级页面没有底部导航，不给返回就没法出去。 */
    onBack: (() -> Unit)? = null,
    /**
     * 角色怎么称呼用户（用于日记体里的"与他/她" → "与你"）。
     *
     * ⚠️ 为空时退到"你"：日记里出现"用户"两个字，读起来立刻像数据库导出。
     */
    userName: String = "",
    /** 角色自己的名字（随笔落款用；空 = 不落款）。 */
    personaName: String = "",
    /**
     * 取一条记忆的**生成版随笔**（由角色模型写）。
     *
     * ★ 做成 `suspend` 而不是同步读：第一次要真的调模型（秒级）。
     *   界面先显示规则版兜底，生成完成后再换过来 —— 不能空着，也不能转圈。
     *
     * ⚠️ 为 null 时（预览/测试）只用规则版，绝不崩。
     */
    generateDiary: (suspend (Atom) -> String?)? = null,
    /** 同步读已缓存的随笔，避免每次点开都等。 */
    cachedDiary: (Atom) -> String? = { null },
) {
    // ⚠️ 布局很贵（O(n²) × 90 轮），只在记忆真的变了时重算。
    val graph = remember(atoms.size, atoms.firstOrNull()?.id, atoms.lastOrNull()?.id) {
        MemoryGraph.build(atoms)
    }

    var yaw by remember { mutableFloatStateOf(0.4f) }
    var pitch by remember { mutableFloatStateOf(-0.18f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var autoRotate by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<MemoryGraph.Node?>(null) }
    val measurer = rememberTextMeasurer()

    // 极慢自转：看得出是 3D，又不至于晕。
    LaunchedEffect(autoRotate, graph) {
        while (autoRotate) {
            delay(16)
            yaw += 0.002f
        }
    }

    PaScreen(title = "长期记忆", modifier = modifier, onBack = onBack) {
        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-29 用户："记忆星云在浅色主题下会很模糊"
        // ══════════════════════════════════════════════════════════
        //
        // 根因很直接：星图的配色是**给暗底写的** ——
        // 浅蓝 `B9D4FF` / 荧光绿 `7CFFB2` 在奶油色背景上
        // 对比度只有 1.2:1 左右，基本等于看不见。
        //
        // ⚠️ 关键判断：换成"浅色版配色"**不等于统一减淡**。
        //   暗底靠"亮"来吸引注意力，浅底要靠"深"。
        //   所以这里不是把颜色调亮，而是把整套色板按主题换掉：
        //     · 点   → 深靛蓝（在奶油底上清晰）
        //     · 重点 → 深青绿（异色，但够暗）
        //     · 选中 → 赤陶（与主题强调色同族）
        //     · 边   → 深灰蓝
        //   而且浅色下光晕要**加大不透明度**才看得出来 ——
        //   在浅底上淡淡的一层白光等于没有。
        val colors = MemoryGraphPalette.forPalette(
            LocalPalette.current,
            isDark = LocalIsDark.current,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(graph) {
                        // ★ 一次拿到 pan + zoom：单指拖动 = 旋转，双指 = 缩放。
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            autoRotate = false
                            // 手势的 zoom 是**增量倍数**，乘上去再夹住范围。
                            zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            yaw += pan.x * 0.006f
                            pitch = (pitch + pan.y * 0.004f).coerceIn(-MAX_PITCH, MAX_PITCH)
                        }
                    }
                    .pointerInput(graph) {
                        detectTapGestures(
                            // 双击复位：用户把图转丢之后最容易想要的动作。
                            onDoubleTap = {
                                yaw = 0.4f
                                pitch = -0.18f
                                zoom = 1f
                                selected = null
                                autoRotate = true
                            },
                            onTap = { tap ->
                                // ★ 点中判定在**屏幕空间**做（44px 内取最近）。
                                //   在 3D 空间里做会把"看起来挨着的两个点"判错。
                                val hit = hitTest(
                                    graph, tap, yaw, pitch, zoom,
                                    size.width.toFloat(), size.height.toFloat(),
                                )
                                selected = hit
                            },
                        )
                    },
            ) {
                drawGraph(graph, yaw, pitch, zoom, selected?.atom?.id, colors)
                // 重点事件的名字：**只标最重的几条**，全标会糊成一片。
                // ⚠️ 真机第一次全标，结果 27 个重点事件的名字叠在一起，
                //    整块画布变成一段读不了的字墙。
                val w = size.width
                val h = size.height
                val drawn = mutableListOf<Offset>()
                for (n in graph.keyEvents.sortedByDescending { it.weight }.take(MAX_LABELS)) {
                    val p = project(n, yaw, pitch, zoom, w, h) ?: continue
                    if (p.alpha < 0.35f) continue
                    val at = Offset(p.x + 10f, p.y - 8f)
                    // 位置挨太近就跳过：宁可少标一个，也不要叠成一团
                    if (drawn.any { hypot(it.x - at.x, it.y - at.y) < LABEL_MIN_GAP_PX }) continue
                    drawn += at
                    drawText(
                        textMeasurer = measurer,
                        text = n.atom.text.take(12),
                        topLeft = at,
                        style = TextStyle(
                            // ⚠️ `drawText` 没有 alpha 参数 —— 透明度只能写进颜色，
                            //    传 alpha= 会直接编译不过。
                            //
                            // ★ 用主题色而不是写死的浅蓝：写死的那套在浅色主题下
                            //   几乎看不见（用户报的"模糊"就是这个）。
                            color = colors.label.copy(alpha = 0.9f * p.alpha),
                            fontSize = 10.sp,
                        ),
                    )
                }
            }

            // 左上角统计（照效果图那种"信息角标"）
            Column(modifier = Modifier.align(Alignment.TopStart).padding(PaSpace.m)) {
                Text(
                    text = "记忆 ${graph.nodes.size}",
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
                Text(
                    text = "重点 ${graph.keyEvents.size}",
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
                Text(
                    text = "×%.1f".format(zoom),
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }

            // 底部：选中的那条记忆
            selected?.let { node ->
                // ══════════════════════════════════════════════════
                //  ★ 先给缓存（有就立刻显示），没有再让模型去写
                // ══════════════════════════════════════════════════
                //
                // ⚠️ key 用 atom.id：切换节点时必须重新触发，
                //    否则第二篇永远显示第一篇的内容（这类 bug 很隐蔽 ——
                //    界面看起来"正常工作"，只是内容不对）。
                var generated by remember(node.atom.id) {
                    mutableStateOf(cachedDiary(node.atom))
                }
                var generating by remember(node.atom.id) { mutableStateOf(false) }
                LaunchedEffect(node.atom.id) {
                    if (generated != null) return@LaunchedEffect
                    val gen = generateDiary ?: return@LaunchedEffect
                    generating = true
                    generated = runCatching { gen(node.atom) }.getOrNull()
                    generating = false
                }
                MemoryDetailCard(
                    node = node,
                    userName = userName,
                    generated = generated,
                    generating = generating,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(PaSpace.m),
                )
            }

            if (graph.isEmpty) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有记忆", style = PaType.body, color = PaColor.TextPrimary)
                    Spacer(Modifier.height(PaSpace.xxs))
                    Text(
                        "聊得多一些之后，这里会长出第一颗星。",
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * 选中后弹出的那条记忆 —— **她亲手写的随笔**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-29 用户两次纠正后的最终形态
 * ═══════════════════════════════════════════════════════════════
 *
 * 第一次："要用角色自己的口吻，第一人称" → 我做了规则改写。
 * 第二次："每（个）节点里呈现给用户的内容，都应该由 **api 里的角色模型
 *   生成**的，就像安排角色写了一篇日记和随笔一样。全是以角色第一人称的
 *   陈述呀，要带有**总结性质**的。"
 *
 * ⇒ 主路径是**模型生成**（走用户配的对话模型，所以口吻就是那个角色的）。
 *   规则版 `MemoryDiary` 只作为"生成中/生成失败"时的**兜底** ——
 *   界面不能空着，也不能显示"正在生成"一直转。
 *
 * ⚠️ 生成结果只缓存在展示层（`cachedDiary` / `generateDiary`），
 *    **不回写原子库** —— 用户明确要求"底层内核该是什么样还是什么样"。
 */
@Composable
private fun MemoryDetailCard(
    node: MemoryGraph.Node,
    userName: String,
    /** 已生成的随笔（由外层负责去取/生成）。null = 还没好，用规则版顶一下。 */
    generated: String?,
    /** 正在生成 —— 显示一个很轻的提示，而不是转圈。 */
    generating: Boolean,
    modifier: Modifier = Modifier,
) {
    val atom = node.atom
    val palette = MemoryGraphPalette.forPalette(LocalPalette.current, isDark = LocalIsDark.current)
    // 兜底：规则版。只在模型还没写出来时出现。
    val fallback = com.pocketagent.memorylogic.MemoryDiary.write(
        text = atom.text,
        kindLabel = atom.kind.label,
        userAddress = userName,
        atMs = atom.at,
        nowMs = System.currentTimeMillis(),
    )
    val body = generated?.takeIf { it.isNotBlank() } ?: fallback.ifBlank { atom.text }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // ⚠️ `background(color, shape)` 的第二个参数是 **Shape**，不是圆角 Dp。
            //    传 `PaRadius.m` 会编译不过（"None of the following candidates"）。
            .background(
                color = PaColor.GlassTintStrong,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(PaRadius.m),
            )
            .padding(PaSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = atom.kind.label,
                style = PaType.caption.copy(fontWeight = FontWeight.Medium),
                // ★ 也用主题色：写死的浅蓝在浅色主题下看不清。
                color = if (node.isKeyEvent) palette.key else palette.dot,
            )
            Text(
                text = if (node.isKeyEvent) "  ·  重点事件" else "",
                style = PaType.caption,
                color = palette.key,
            )
            if (generating && generated.isNullOrBlank()) {
                Text(
                    text = "  ·  正在写…",
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(PaSpace.xs))
        // ★★ 主视觉：**她写的随笔**。第一人称、总结性。
        Text(
            text = body,
            style = PaType.body,
            color = PaColor.TextPrimary,
        )
        Spacer(Modifier.height(PaSpace.xs))
        // ★ 原文仍然可达 —— 但降成小字。
        //   用户偶尔要确认"它记的到底是什么"，不能只给文学化的版本；
        //   而那不该是主视觉。
        if (body != atom.text) {
            Text(
                // ⚠️ 原文也要过 `readable`：压缩段带 `U>` / `A<` 那种模型专用记号，
                //    原样显示等于把内部编码摊在用户面前。
                text = "原文：" + com.pocketagent.memorylogic.MemoryDiary.readable(atom.text),
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.height(PaSpace.xxs))
        }
        Text(
            text = "置信度 ${(atom.confidence * 100).toInt()}% · 来自 ${atom.sourceTurnIds.size} 轮对话",
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
    }
}

// ── 投影与命中 ────────────────────────────────────────────────

private data class Projected(val x: Float, val y: Float, val scale: Float, val alpha: Float)

/**
 * 3D → 2D。
 *
 * ★ 先绕 Y 转、再绕 X 转，然后做**透视除法**（远的点小）。
 *   没有透视的"3D"看起来就是一张抖动的平面图，转起来也不像球。
 *
 * @param zoom 用户缩放倍数（1 = 默认）
 */
private fun project(
    node: MemoryGraph.Node,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    w: Float,
    h: Float,
): Projected? {
    if (w <= 0f || h <= 0f) return null
    val cy = cos(yaw); val sy = sin(yaw)
    val cp = cos(pitch); val sp = sin(pitch)

    // 绕 Y 轴
    val x1 = (node.x * cy - node.z * sy).toFloat()
    val z1 = (node.x * sy + node.z * cy).toFloat()
    val y1 = node.y.toFloat()
    // 绕 X 轴
    val y2 = y1 * cp - z1 * sp
    val z2 = y1 * sp + z1 * cp

    val depth = z2 + CAMERA_Z
    if (depth <= 0.05f) return null // 在相机背后，不画
    val scale = CAMERA_Z / depth
    val radius = (minOf(w, h) / 2f) * WORLD_TO_SCREEN * zoom
    return Projected(
        x = w / 2f + x1 * radius * scale,
        y = h / 2f + y2 * radius * scale,
        // ★ 点的**像素大小**也要跟着缩放走，否则放大后点还是那么小，
        //   看起来像"图放大了但星点没放大"。
        scale = scale * zoom.coerceAtLeast(0.35f),
        // 远的点更暗：这是"深度"的视觉线索
        alpha = (0.35f + 0.65f * (scale / 1.4f).coerceIn(0f, 1f)).coerceIn(0f, 1f),
    )
}

private fun hitTest(
    graph: MemoryGraph.Graph,
    tap: Offset,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    w: Float,
    h: Float,
): MemoryGraph.Node? {
    var best: MemoryGraph.Node? = null
    var bestDist = HIT_RADIUS_PX
    for (n in graph.nodes) {
        val p = project(n, yaw, pitch, zoom, w, h) ?: continue
        val d = hypot(p.x - tap.x, p.y - tap.y)
        if (d < bestDist) {
            bestDist = d
            best = n
        }
    }
    return best
}

// ── 绘制 ──────────────────────────────────────────────────────

private fun DrawScope.drawGraph(
    graph: MemoryGraph.Graph,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    selectedId: String?,
    colors: MemoryGraphPalette,
) {
    val w = size.width
    val h = size.height

    val projected = graph.nodes.associate { it.atom.id to project(it, yaw, pitch, zoom, w, h) }

    // 先画边（在点下面）
    for (e in graph.edges) {
        val a = projected[e.fromAtomId] ?: continue
        val b = projected[e.toAtomId] ?: continue
        drawLine(
            color = colors.edge.copy(alpha = 0.10f + 0.22f * e.strength.toFloat()),
            start = Offset(a.x, a.y),
            end = Offset(b.x, b.y),
            strokeWidth = 0.9f,
        )
    }

    // 再画点：远的先画（画家算法），近的压在上面
    for (n in graph.nodes.sortedBy { projected[it.atom.id]?.scale ?: 0f }) {
        val p = projected[n.atom.id] ?: continue
        val isSelected = n.atom.id == selectedId
        val baseR = if (n.isKeyEvent) KEY_GLOW_R else DOT_R
        val r = baseR * p.scale

        // 光晕（重点事件 + 选中）
        if (n.isKeyEvent || isSelected) {
            val glow = if (n.isKeyEvent) colors.key else colors.selected
            // ⚠️ 不透明度来自主题：浅底上淡淡一层"亮晕"等于没有，
            //    所以浅色版用的是更浓的**深色晕**。见 MemoryGraphPalette。
            drawCircle(
                color = glow.copy(alpha = colors.glowOuterAlpha * p.alpha),
                radius = r * 3.2f,
                center = Offset(p.x, p.y),
            )
            drawCircle(
                color = glow.copy(alpha = colors.glowInnerAlpha * p.alpha),
                radius = r * 1.9f,
                center = Offset(p.x, p.y),
            )
        }
        drawCircle(
            color = when {
                isSelected -> colors.selected
                n.isKeyEvent -> colors.key
                else -> colors.dot
            }.copy(alpha = p.alpha),
            radius = r,
            center = Offset(p.x, p.y),
        )
        // 重点事件的核心再亮一层 —— 暗底上才有"光点"感。
        // ⚠️ 浅底不用白色（在奶油底上等于没有），用同色更深一档。
        if (n.isKeyEvent) {
            drawCircle(
                color = colors.keyCore.copy(alpha = 0.85f * p.alpha),
                radius = r * 0.45f,
                center = Offset(p.x, p.y),
            )
        }
    }
}

// ── 常数 ──────────────────────────────────────────────────────

private const val CAMERA_Z = 3.2f

/**
 * 世界坐标（单位球，半径 = 1）→ 屏幕半径的系数。
 *
 * ⚠️ 真机调过两次：
 *   0.42 → 整团只有屏幕中间一小撮，几乎看不清；
 *   0.86 → 直径约占屏幕短边 86%，既填得满又不会被裁掉。
 *   归一化在 `MemoryGraph` 里做，所以这个数是**稳定**的，
 *   不会因为记忆变多而需要跟着改。
 */
private const val WORLD_TO_SCREEN = 0.86f
private const val DOT_R = 2.2f
private const val KEY_GLOW_R = 3.6f
private const val HIT_RADIUS_PX = 44f

/** 俯仰限位：超过 ±80° 就翻过去了，翻过去之后人会立刻失去方向感。 */
private const val MAX_PITCH = 1.4f

/** 缩放范围。放太小看不见，放太大只剩几个点。 */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

/** 最多标几个名字。再多就叠成一堵字墙。
 *
 * ★ 真机从 10 降到 5：一眼能读的名字不超过五六个，
 *   剩下那些点靠"点一下看详情"去读，比挤在画布上有效得多。 */
private const val MAX_LABELS = 5

/** 两个名字至少隔这么远，否则跳过（宁可少标一个也不要叠）。 */
private const val LABEL_MIN_GAP_PX = 118f

// ⚠️ 这里原来有三个写死的颜色常量（DOT/KEY/SELECT）。
//    它们只在暗色主题下成立 —— 浅色主题下浅蓝浅绿在奶油底上
//    对比度约 1.2:1，就是用户报的"模糊"。
//    ⇒ 已全部移进 `MemoryGraphPalette`（两套色板），这里不再有颜色常量。
