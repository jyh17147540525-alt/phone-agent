package com.pocketagent.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 玻璃底部导航。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么用「滑动指示器」而不是「每项各自的背景」
 * ═══════════════════════════════════════════════════════════════
 *
 * 让每个 tab 自己淡入一个背景，切换时是"这边灭、那边亮"——
 * 视觉上是**两个独立事件**。
 *
 * 用一个横跨全宽的指示器滑动过去，切换是**一个连续事件**——
 * 用户能"看见"自己从哪一页去了哪一页。这就是"流畅"与"能用"的差别，
 * 而且成本极低（一个 animateDpAsState）。
 *
 * 指示器用 [PaColor.AccentSoft]（20% 透明度的强调色）而不是实心色 ——
 * 实心色会把玻璃底栏"打穿"一个洞，破坏质感。
 */
data class PaNavItem(
    val label: String,
    val icon: ImageVector,
)

@Composable
fun PaBottomBar(
    items: List<PaNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(PaColor.GlassTintStrong)
            // 上边缘亮线 —— "面板从下方托住内容"的暗示
            .glassEdgeHorizontal(RoundedCornerShape(0.dp), highlightOnTop = true)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(PaSpace.bottomBarHeight),
        ) {
            val itemWidth = maxWidth / items.size

            // 指示器滑动的动画。用 standard 而不是 bouncy ——
            // 底栏是高频操作，回弹会让人烦；bouncy 只留给低频的"惊喜"时刻
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = PaMotion.standard(),
                label = "navIndicatorOffset",
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = PaSpace.xs, vertical = PaSpace.xs),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(PaRadius.s))
                        .background(PaColor.AccentSoft),
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    NavItemView(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItemView(
    item: PaNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }

    val tint by animateColorAsState(
        targetValue = if (selected) PaColor.Accent else PaColor.TextTertiary,
        animationSpec = PaMotion.standard(),
        label = "navItemTint",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = item.label,
            style = PaType.label,
            color = tint,
        )
    }
}
