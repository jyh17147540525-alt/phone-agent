package com.pocketagent.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

/**
 * 雾化玻璃组件库。
 *
 * ═══════════════════════════════════════════════════════════════
 *  技术说明：为什么不用 `Modifier.blur()` 模糊背后的内容
 * ═══════════════════════════════════════════════════════════════
 *
 * Compose 的 `Modifier.blur()` **只能模糊该 composable 自身绘制的内容**，
 * 无法读取并模糊它"背后"的兄弟节点。要做出 iOS 那种"透过玻璃看到模糊背景"，
 * 需要：
 *   1. 用 `rememberGraphicsLayer()` 把背景内容捕获成 layer
 *   2. 在玻璃层里 `drawLayer(layer)` + `blur` + `clip` 到玻璃形状
 *   3. 背景每次变化都要重新捕获（滚动时每帧一次）
 *
 * 这是 Haze 库的做法。它**可行但昂贵**，且在滚动列表上会明显掉帧。
 *
 * **本项目的方案：用「彩色光斑背景 + 半透明玻璃面板」实现等效观感。**
 *
 * 原理：玻璃之所以看起来"雾"，是因为**透过它看到的东西被模糊了**。
 * 如果背景本身就是柔和的彩色光斑（径向渐变，天然没有硬边），
 * 那么"半透明面板 + 边框高光"叠上去之后，视觉结果与真模糊**几乎无法区分** ——
 * 因为光斑本来就是"模糊的"，再模糊一次还是那个样子。
 *
 * 代价是：玻璃面板下面**不能是锐利的内容**（如文字列表）。
 * 所以本库的玻璃只用于**背景层**（底部导航、顶部栏、悬浮卡片），
 * 不用于覆盖在滚动内容之上。这个约束是有意的。
 *
 * 收益：零额外绘制开销、无第三方依赖、无 API 版本问题。
 */

/**
 * 极光背景 —— 雾化玻璃的"底"。
 *
 * 三个大半径径向渐变光斑，叠在近黑底色上。
 * 用 `radialGradient` 而不是"实心圆 + blur"，因为渐变天然无硬边，
 * 是**零成本**的柔和效果（blur 需要 RenderEffect 图层，滚动时会掉帧）。
 *
 * ⚠️ 光斑位置是**固定比例**而不是固定 dp，这样在任何屏幕尺寸下构图都一致。
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // ★ 颜色必须在**组合期**读出，再传进 DrawScope。
    //   `Canvas { }` 的 lambda 是 DrawScope 上下文，**不能**读 CompositionLocal
    //   （主题切换时它也不会自动重组）—— 所以在这里先取值。
    val canvasColor = PaColor.Canvas
    val blue = PaColor.AuroraBlue
    val violet = PaColor.AuroraViolet
    val teal = PaColor.AuroraTeal

    Box(modifier = modifier.fillMaxSize().background(canvasColor)) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            // 三团光：左上偏暖、右上偏粉、底部偏绿灰。
            // 位置用屏幕尺寸的比例，保证构图在任何尺寸下都不散。
            //
            // ★★ 2026-10-25：alpha 整体下调到 1/4 左右（0.38→0.10 等）。
            //
            //   那两个值是为**深色底**调的：黑底吃得住高饱和光斑，
            //   光斑越浓越有"极光"感。
            //   但换到暖米色浅底之后，同样的 alpha 会让整屏变成
            //   **脏黄色块** —— 因为浅底本身亮度已经接近光斑，
            //   叠加只会污染而不是发光。
            //
            //   浅底上正确的手法是"水彩在纸上化开"：颜色极淡、
            //   范围极大、几乎看不出边界。所以这里 alpha 压到 0.10 以下，
            //   效果是"纸张有一点点色温倾向"，而不是"屏幕上有光"。
            auroraBlob(
                center = Offset(x = size.width * 0.18f, y = size.height * 0.08f),
                radius = size.minDimension * 0.95f,
                color = blue,
                alpha = 0.10f,
            )
            auroraBlob(
                center = Offset(x = size.width * 0.92f, y = size.height * 0.22f),
                radius = size.minDimension * 0.80f,
                color = violet,
                alpha = 0.08f,
            )
            auroraBlob(
                center = Offset(x = size.width * 0.35f, y = size.height * 0.92f),
                radius = size.minDimension * 1.00f,
                color = teal,
                alpha = 0.06f,
            )
        }

        content()
    }
}

/**
 * 画一团极光。
 *
 * 渐变的 alpha 曲线用**非线性**停靠点（0.0 → 0.55 就衰减到接近透明），
 * 这样中心有一小块较实的颜色，外圈迅速化开 ——
 * 线性衰减会让整团光看起来像"磨砂圆片"而不是"光"。
 */
private fun DrawScope.auroraBlob(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to color.copy(alpha = alpha),
                0.35f to color.copy(alpha = alpha * 0.55f),
                0.70f to color.copy(alpha = alpha * 0.16f),
                1.0f to Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * 玻璃面板。
 *
 * 三个要素缺一不可，去掉任何一个都会"塌"成普通半透明块：
 *  1. **半透明填充**（[PaColor.GlassTint]）
 *  2. **渐变描边**（顶亮 → 中淡 → 底暗，见 [glassEdge]）—— 玻璃的"厚度"
 *  3. **圆角**（[PaRadius.m] 起步）
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(PaRadius.m),
    tint: Color = PaColor.GlassTint,
    contentPadding: Dp = PaSpace.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(tint)
            .glassEdge(shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 可点击的玻璃卡片。
 *
 * 按压反馈用**缩放**而不是涟漪 —— 涟漪是 Material 的语言，
 * 在玻璃质感上会显得突兀。缩放 + 透明度变化更贴合"实体面板被按下"的直觉。
 */
@Composable
fun GlassCard(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(PaRadius.m),
    tint: Color = PaColor.GlassTint,
    contentPadding: Dp = PaSpace.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按压时轻微变亮并缩放。0.985 是"能感觉到但看不出在缩放"的量级
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = PaMotion.fast(),
        label = "glassCardScale",
    )
    val currentTint = if (pressed) PaColor.GlassTintStrong else tint

    Column(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(currentTint)
            .glassEdge(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,   // 关掉涟漪，用缩放代替
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 玻璃描边 —— 玻璃质感的**关键**。
 *
 * 用垂直渐变模拟"光从上方打过来"：
 *  - 顶边亮（[PaColor.GlassHighlight]）
 *  - 中部淡（[PaColor.GlassBorder]）
 *  - 底边暗（[PaColor.GlassShadow]）
 *
 * ⚠️ 停靠点不是均匀的：顶部高光集中在最上面 8%，
 *    因为真实玻璃的高光就是一条窄带，均匀分布会变成"上下都有边"的塑料感。
 */
@Composable
fun Modifier.glassEdge(
    shape: Shape,
    width: Dp = 1.dp,
): Modifier = this.border(
    width = width,
    brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to PaColor.GlassHighlight,
            0.08f to PaColor.GlassBorder,
            0.55f to PaColor.GlassBorder.copy(alpha = PaColor.GlassBorder.alpha * 0.6f),
            1.00f to PaColor.GlassShadow,
        ),
    ),
    shape = shape,
)

/**
 * 玻璃水平描边 —— 用于**边缘接触屏幕**的玻璃条（顶栏、底栏）。
 *
 * 与 [glassEdge] 的区别：只有一条边可见。
 * 顶栏要"下边亮"（光从下方来，因为下面是内容），
 * 底栏要"上边亮"。这个方向感是"面板贴着屏幕边缘"的关键暗示。
 */
@Composable
fun Modifier.glassEdgeHorizontal(
    shape: Shape,
    highlightOnTop: Boolean,
    width: Dp = 1.dp,
): Modifier = this.border(
    width = width,
    brush = Brush.verticalGradient(
        colorStops = if (highlightOnTop) {
            arrayOf(
                0.00f to PaColor.GlassHighlight,
                0.04f to PaColor.GlassBorder,
                1.00f to Color.Transparent,
            )
        } else {
            arrayOf(
                0.00f to Color.Transparent,
                0.96f to PaColor.GlassBorder,
                1.00f to PaColor.GlassHighlight,
            )
        },
    ),
    shape = shape,
)
