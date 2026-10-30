package com.pocketagent.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **滚动边缘渐隐** —— 内容滚到边界处"化开"，而不是被硬切。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这是 iOS 观感里最不显眼、但最影响"高级感"的一处
 * ═══════════════════════════════════════════════════════════════
 *
 * 对比一下同一段长内容在两种实现下的样子：
 *
 *  · **硬切**：内容滚到页面顶部，一行字被拦腰切成一半。
 *    用户看到的是"这里有个边界"，而边界会让人想到"容器"。
 *  · **渐隐**：顶部 24dp 内内容逐渐化进底色。
 *    用户看到的是"内容从上面流下来"，感觉不到容器存在。
 *
 * ⇒ 前者是"一个框里装着内容"，后者是"内容本身"。差别就这一点。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个技术要点
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **用渐变**而不是 `blur`。渐变是零成本的（一次 drawRect），
 *    blur 需要 RenderEffect 图层，滚动时逐帧重建会掉帧。
 *
 * 2. **渐变色必须取自当前主题的底色**，不能写死白色/黑色。
 *    写死的话浅色主题下会出现一条白雾、深色下出现一条黑雾 ——
 *    两种都是"一眼看出是补丁"。
 *
 * 3. **只在真的还有内容时显示**。滚到顶了顶部就不该有雾；
 *    否则用户会以为"上面还有东西"，往上拉却什么都没有。
 *    这就是 [canScrollUp] / [canScrollDown] 的用途。
 *
 * ⚠️ 本组件**不接管滚动**。它只是一层覆盖在内容之上的装饰，
 *    由调用方根据 `LazyListState` 告诉它"两个方向还有没有内容"。
 */
@Composable
fun ScrollingEdgeFade(
    modifier: Modifier = Modifier,
    canScrollUp: Boolean = true,
    canScrollDown: Boolean = true,
    /** 渐隐带的高度。20dp 是"能感觉到但说不出哪里变了"的量级。 */
    size: Dp = 24.dp,
    /** 渐隐的底色 —— 通常是页面的 Canvas 色。 */
    background: Color = PaColor.Canvas,
) {
    // ⚠️ 颜色必须在组合期读出（DrawScope 里读不到 CompositionLocal）。
    //    这里 background 已经是参数，所以直接可用。

    Canvas(modifier = modifier.fillMaxSize()) {
        val h = size.toPx()

        if (canScrollUp && h > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(background, background.copy(alpha = 0f)),
                    startY = 0f,
                    endY = h,
                ),
                topLeft = Offset.Zero,
                size = Size(this.size.width, h),
            )
        }

        if (canScrollDown && h > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(background.copy(alpha = 0f), background),
                    startY = this.size.height - h,
                    endY = this.size.height,
                ),
                topLeft = Offset(0f, this.size.height - h),
                size = Size(this.size.width, h),
            )
        }
    }
}

/**
 * 把一个可滚动内容包起来，并自动给上下两端加渐隐。
 *
 * ★ 与直接调 [ScrollingEdgeFade] 的区别：这个把"两个方向还有没有内容"
 *   的判断也包了，调用方只要喂 `LazyListState`。
 *
 * 用法：
 * ```
 * val listState = rememberLazyListState()
 * ScrollingEdge(content = { LazyColumn(state = listState) { ... } },
 *               state = listState)
 * ```
 */
@Composable
fun ScrollingEdge(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    background: Color = PaColor.Canvas,
    content: @Composable BoxScope.() -> Unit,
) {
    // ⚠️ 用 `derivedStateOf` 而不是每次重组都读 `canScrollBackward`：
    //    `LazyListState` 在滚动时**每一帧**都会变，直接读会让整个
    //    ScrollingEdge 每帧重组 —— 那是"加了个装饰结果变卡"的典型。
    //    derivedStateOf 只在布尔值**真的翻转**时才触发重组。
    val canUp = androidx.compose.runtime.derivedStateOf {
        state.canScrollBackward
    }
    val canDown = androidx.compose.runtime.derivedStateOf {
        state.canScrollForward
    }

    Box(modifier = modifier) {
        content()

        // 这一层本身**不接受触摸**：它只是装饰，
        // 抢走手势会让"在顶部那条雾上滑动"失效。
        ScrollingEdgeFade(
            modifier = Modifier.fillMaxSize(),
            canScrollUp = canUp.value,
            canScrollDown = canDown.value,
            size = size,
            background = background,
        )
    }
}
