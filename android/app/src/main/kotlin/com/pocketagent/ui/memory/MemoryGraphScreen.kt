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
import com.pocketagent.memorylogic.NebulaBloom
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
 * **记忆星云** —— 可任意角度旋转、可缩放的 3D 记忆团。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "点开后会出现一个 3D 的记忆节点可交互记忆团……重点事件由大的异色光点
 *  标注，用户点击对应的光点会出现相关的记忆事件。"
 * "记得是可任意角度旋转、可随意放大缩小的。"
 * "每次 app 启动，记忆星云会从中间初始节点开始向外扩散。
 *  给用户一种记忆加载出来的感觉。"
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
 *
 * 7. **★★ 绽放动画只在"进页面"时跑一次，不是每次重组都跑**。
 *    见 `bloom` 的 `remember` 键：它绑在图上。
 *    绑错键的后果是每次点选节点都重新绽放一遍 —— 那会让人想砸手机。
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

    // ══════════════════════════════════════════════════════════════
    //  ★ 画布标签的文本来源：**只认随笔，不认抽取原文**
    // ══════════════════════════════════════════════════════════════
    //
    // ⚠️ 一次性把已有随笔读进内存（同步、极快），
    //    这样画布绘制时不用每次回调 —— Canvas 的 draw 每帧都在跑。
    val cachedDiaryById = remember(graph) {
        graph.nodes.associate { it.atom.id to runCatching { cachedDiary(it.atom) }.getOrNull() }
            .filterValues { !it.isNullOrBlank() }
            .mapValues { it.value.orEmpty() }
    }

    /**
     * 画布上那个标签的文本。
     *
     * ⚠️ **只能取自随笔**，绝不用 `atom.text` —— 那是喂给模型的抽取原文。
     *   随笔还没生成就返回 null（不标），宁可少一个标签。
     */
    fun labelOf(atomId: String): String? {
        val diary = cachedDiaryById[atomId] ?: return null
        val head = diary.trim().take(14)
        return head.ifBlank { null }
    }

    var yaw by remember { mutableFloatStateOf(0.4f) }
    var pitch by remember { mutableFloatStateOf(-0.18f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var autoRotate by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<MemoryGraph.Node?>(null) }
    val measurer = rememberTextMeasurer()

    // ══════════════════════════════════════════════════════════════
    //  ★★ 2026-10-29 用户："每次 app 启动，记忆星云会从中间初始节点
    //    开始向外扩散。给用户一种记忆加载出来的感觉。"
    // ══════════════════════════════════════════════════════════════
    //
    // 实现要点（都在 `:memorylogic/NebulaBloom` 里，有 22 条离线测试）：
    //   · 种子点第 0 毫秒就出现，先停 120ms 立住（这一停就是"加载感"）
    //   · 其余点的延迟 **正比于它离种子的距离** ⇒ 同一时刻亮起来的是
    //     一个壳层，看起来是真的从内向外推，而不是随机打点
    //   · 位置用"世界坐标 × 半径系数"实现，所以天然从球心出发，
    //     而且旋转/缩放同时生效（在屏幕空间做就得自己推投影，两套逻辑必然漂移）
    //
    // ⚠️ `remember` 的键是 **graph**：动画只跟"这张图"绑定一次。
    //    键错成 `selected` 或什么都不写，会让每次点选节点都重新绽放 ——
    //    那是会让人想砸手机的体验。
    var bloom by remember(graph) { mutableFloatStateOf(0f) }
    // 摊平成 id → 进度，避免在 draw 里每次做线性查找。
    var bloomById by remember(graph) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // 这些标记直接读 `NebulaBloom` 的常量 —— 界面不该另写一套时间。
    LaunchedEffect(graph) {
        val seed = graph.seed
        if (seed == null) {
            // 空图：没有可绽放的东西，直接标成结束（否则会一直重绘）。
            bloom = 1f
            bloomById = emptyMap()
            return@LaunchedEffect
        }

        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-29 真机量出来的坑：**转场会吃掉绽放的前半段**
        // ══════════════════════════════════════════════════════════
        //
        // NavHost 的进入转场是 380ms（`PaMotion.Page`）的
        // fadeIn + slide + scale。绽放若在这一帧就开始计时，
        // 用户看到的第一眼已经是**400ms 之后**的画面 —— 种子点那一段
        // 「先在中间亮起来、停一下」正好整个落在转场里，谁都看不见。
        //
        // 真机证据（screencap 连拍 + 亮点计数）：
        //   bloom2 = 789 个亮点（已不是"只有种子"）
        //   bloom3 = 3041（几乎就位）
        //   bloom4~6 = 3080/3075/3166（完全稳定）
        // ⇒ 绽放确实在跑，但**在页面可见之前就跑完了**。
        //
        // ⇒ 先等转场结束再起表。这不是为了好看 ——
        //    "从中间初始节点开始向外扩散"是用户明确要的东西，
        //    它被转场吃掉就等于没做。
        //
        // ══════════════════════════════════════════════════════════
        //  ★★ 等"页面真的可见"再起表 —— 两段延迟，缺一不可
        // ══════════════════════════════════════════════════════════
        //
        // 真机量了两次，第一次只等转场（`PaDuration.Page` = 380ms）**不够**：
        //   连拍 10 帧，帧2 的亮点最小距中心 292px、最远 1276px
        //   ⇒ 那一帧里能看见的全是**上一页残留的文字**，一颗星都没有。
        //   帧3 就已经 3383 个亮点（几乎就位）。
        //
        // 说明动画在"页面可见"之前就跑完了。原因有两层：
        //   ① NavHost 的 enterTransition（380ms，fade+slide+scale）
        //   ② 之后还有一次**首帧组合 + 测量 + 布局**（不可忽略，实测 200ms+）
        //
        // ⇒ 等转场 + 再等一帧以上，让"第一帧真的画出来"之后再起表。
        //
        // ⚠️ 这里用两段 `withFrameNanos()` 而不是写死毫秒数：
        //    "等到真的画过一帧"只有 frame 时钟能回答，
        //    用固定毫秒数去猜必然在慢设备上又错位。
        delay(com.pocketagent.ui.design.PaDuration.Page.toLong())
        // 让出两帧：第一帧把这一页画出来，第二帧确保它已经在屏幕上。
        //
        // ⚠️ 用 `withFrameNanos`（Compose 的帧时钟）而不是再 `delay`：
        //    "等到真的画过一帧"只有帧时钟能回答，猜毫秒数在慢设备上必然又错位。
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }

        // ══════════════════════════════════════════════════════════
        //  ★★ 用**真实时钟**推进，不用"每帧加 16"
        // ══════════════════════════════════════════════════════════
        //
        // ⚠️ 第一版是 `delay(16); elapsed += 16`，真机上表现成"跳"：
        //    亮点计数 290 → 3068 → 3997，中间没有过渡帧。
        //
        // 原因有两层：
        //  ① `delay(16)` 只是"至少等 16ms"。合成一帧忙的时候它可能 50ms 才回来，
        //     而 `elapsed` 只加 16 ⇒ **动画实际比时钟慢三倍**，
        //     看起来像卡顿后突然跳到位。
        //  ② 更隐蔽的：循环里那个 `val delay = ...` **遮蔽了 `delay()` 函数**，
        //     在那个作用域里 `delay(16)` 解析到的是 Long 变量 —— 这行其实
        //     靠 Kotlin 的重载才没崩，但可读性已经完全坏了。
        //
        // ⇒ 现在用 `System.nanoTime()` 的真实经过时间驱动。
        //   掉帧时动画会**自己追上**进度，而不是越落越远。
        //   这也顺带让"总时长"成为对用户的承诺（1.24 秒就是 1.24 秒）。
        val startedNs = System.nanoTime()
        while (true) {
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L)
            // ★ 每帧算一次每个点的进度。200 个点的 map 构造足够快，
            //   而且它是 draw 的唯一输入 —— 不去在 draw 里调函数。
            val progressById = HashMap<String, Float>(graph.nodes.size)
            for (n in graph.nodes) {
                val startMs = if (n.atom.id == seed.atom.id) {
                    0L
                } else {
                    NebulaBloom.startDelayMs(
                        isSeed = false,
                        distanceFromSeed = NebulaBloom.normalizedDistance(seed, n),
                    )
                }
                progressById[n.atom.id] = NebulaBloom.progressAt(elapsedMs, startMs)
            }
            bloomById = progressById
            bloom = (elapsedMs.toFloat() / NebulaBloom.TOTAL_MS).coerceIn(0f, 1f)
            if (NebulaBloom.isFinished(elapsedMs)) break
            delay(16)
        }
    }

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
                                    bloomById = bloomById,
                                )
                                selected = hit
                            },
                        )
                    },
            ) {
                drawGraph(graph, yaw, pitch, zoom, selected?.atom?.id, colors, bloomById)
                // ══════════════════════════════════════════════════
                //  ★★ 2026-10-29 用户："在节点附近会出现一些记忆，
                //  但这个是给模型看的，不是给用户看的，麻烦修改一下"
                // ══════════════════════════════════════════════════
                //
                // 之前这里画的是 `n.atom.text.take(12)` —— 那是**抽取原文**，
                // 是喂给 `MemoryRecall` 的机器素材（还带着 `U>` / `A<` 的味道）。
                // 摊在画布上等于把内核字段直接印在界面上。
                //
                // ⇒ 改用 `labelOf`：优先显示**她写的那篇随笔**的头几个字。
                //   随笔还没有的话就**不标**（宁可少一个标签，
                //   也不要把机器素材露出去）。
                val w = size.width
                val h = size.height
                val drawn = mutableListOf<Offset>()
                for (n in graph.keyEvents.sortedByDescending { it.weight }.take(MAX_LABELS)) {
                    // ★ 标签跟着它那个点一起绽放：点还没落位就不标字，
                    //   否则会出现"文字先出来、星还在飞"的错位。
                    val nodeBloom = bloomById[n.atom.id] ?: 1f
                    if (nodeBloom <= 0f) continue
                    val p = project(n, yaw, pitch, zoom, w, h, nodeBloom) ?: continue
                    if (p.alpha < 0.35f) continue
                    val label = labelOf(n.atom.id) ?: continue
                    val at = Offset(p.x + 10f, p.y - 8f)
                    // 位置挨太近就跳过：宁可少标一个，也不要叠成一团
                    if (drawn.any { hypot(it.x - at.x, it.y - at.y) < LABEL_MIN_GAP_PX }) continue
                    drawn += at
                    drawText(
                        textMeasurer = measurer,
                        text = label,
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
    // ⚠️ 最后一层兜底**不是** `atom.text`。
    //
    // 用户的要求是"原文对用户隐藏" —— 那是一条**没有例外**的规则。
    // 之前写的是 `fallback.ifBlank { atom.text }`：规则版随笔理论上不会返回空，
    // 但它一旦返回空，屏幕上出现的就会是抽取原文（连 `U>` / `A<` 一起）。
    // 「理论上不会发生」不是理由 —— 兜底必须也守同一条边界。
    val body = generated?.takeIf { it.isNotBlank() }
        ?: fallback.takeIf { it.isNotBlank() }
        ?: "（这段还留在记忆里，她还没写下来）"
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
        // ★★ 2026-10-29 用户："原文需要对用户隐藏一下"
        //
        // 之前这里显示了一行小字「原文：…」。理由当时是"用户偶尔要确认
        // 它记的到底是什么"—— 但那是个**错的理由**：用户点开记忆，
        // 想看的是"她记得这件事"，不是数据库里那行冰冷的抽取结果。
        // 原文里还夹着 `U>` / `A<` 那套内部编码的味道，露出来就出戏。
        //
        // ⇒ 整行移除。原文仍然完整存在（原子库一个字没动），
        //   只是不再给用户看 —— 那是内核对内核对的东西，不是产品界面。
        Spacer(Modifier.height(PaSpace.xxs))
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
    /**
     * 绽放进度 0..1。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 为什么乘在**世界坐标**上而不是屏幕偏移
     * ═══════════════════════════════════════════════════════════
     *
     * 世界坐标乘 0 就是球心 —— 于是：
     *   · 所有点天然从正中间出发（用户要的"从中间初始节点开始"）
     *   · 旋转和缩放**同时生效**（它本来就是同一条投影链路）
     *
     * ⚠️ 如果在屏幕空间做"从中心偏移"：得自己再推一遍投影，
     *    两套投影逻辑必然漂移 —— 表现出来是"转起来之后，飞出来的点
     *    走的不是直线"。
     *
     * ⚠️ 默认 1f = 已就位：这样旧调用点和测试都不用改，
     *    而且语义正确（不传进度就是"没有动画"）。
     */
    bloom: Float = 1f,
): Projected? {
    if (w <= 0f || h <= 0f) return null
    // ⚠️ 进度为 0 = 还没出现。返回 null 让调用方跳过 ——
    //    不能靠 alpha=0 硬画，那会让 hitTest 仍然点得到看不见的点。
    if (bloom <= 0f) return null

    val factor = NebulaBloom.radiusFactor(bloom)
    val cx = node.x * factor
    val cyw = node.y * factor
    val cz = node.z * factor

    val cy = cos(yaw); val sy = sin(yaw)
    val cp = cos(pitch); val sp = sin(pitch)

    // 绕 Y 轴
    val x1 = (cx * cy - cz * sy).toFloat()
    val z1 = (cx * sy + cz * cy).toFloat()
    val y1 = cyw.toFloat()
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
        //
        // ★ 再乘上出生尺寸：点从"略小"长到全尺寸，是"长出来"而不是"弹出来"。
        scale = scale * zoom.coerceAtLeast(0.35f) * NebulaBloom.sizeFactor(bloom),
        // 远的点更暗：这是"深度"的视觉线索。
        // ★ 再乘上绽放淡入：前 35% 淡入，之后不透明（全程半透明像幽灵）。
        alpha = (0.35f + 0.65f * (scale / 1.4f).coerceIn(0f, 1f)).coerceIn(0f, 1f) *
            NebulaBloom.alpha(bloom),
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
    /** 每个点的绽放进度。**飞行途中的点不可点** —— 见下面的注释。 */
    bloomById: Map<String, Float> = emptyMap(),
): MemoryGraph.Node? {
    var best: MemoryGraph.Node? = null
    var bestDist = HIT_RADIUS_PX
    for (n in graph.nodes) {
        // ══════════════════════════════════════════════════════════
        //  ★★ 为什么飞行途中不许点
        // ══════════════════════════════════════════════════════════
        // 绽放动画开头，所有点都挤在球心那一小团里。
        // 那时如果允许点，用户点到的**永远不是他看见的那颗** ——
        // 他会点"中间偏左"，然后弹出一条完全不相干的记忆，
        // 而界面上看起来一切正常。这类 bug 极难自查。
        //
        // ⇒ 过半（`NebulaBloom.isTappable`）才算落位可点。
        val bloom = bloomById[n.atom.id] ?: 1f
        if (!NebulaBloom.isTappable(bloom)) continue
        val p = project(n, yaw, pitch, zoom, w, h, bloom) ?: continue
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
    /** 每个点的绽放进度（id → 0..1）。不在表里的按"已就位"处理。 */
    bloomById: Map<String, Float> = emptyMap(),
) {
    val w = size.width
    val h = size.height

    fun bloomOf(id: String): Float = bloomById[id] ?: 1f

    val projected = graph.nodes.associate {
        it.atom.id to project(it, yaw, pitch, zoom, w, h, bloomOf(it.atom.id))
    }

    // 先画边（在点下面）
    for (e in graph.edges) {
        val a = projected[e.fromAtomId] ?: continue
        val b = projected[e.toAtomId] ?: continue
        // ★ 边的可见度取两端进度的**较小值**：一条边要等两头都出现才成立。
        //   取下限而不是上限，是为了让"边"也参与扩散 ——
        //   否则点还没出来，线已经连好了，看着像先有网后有星。
        val edgeBloom = minOf(bloomOf(e.fromAtomId), bloomOf(e.toAtomId))
        if (edgeBloom <= 0f) continue
        drawLine(
            color = colors.edge.copy(
                alpha = (0.10f + 0.22f * e.strength.toFloat()) * NebulaBloom.alpha(edgeBloom),
            ),
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

        // ⚠️ 光晕也用点的 alpha —— 它已经包含"绽放淡入"了。
        //    如果这里另算一份，会出现"点还没出来、光晕先亮"的鬼影。
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
