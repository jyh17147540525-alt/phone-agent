package com.pocketagent.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 列表行组件 —— 设置页与插件页共用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  设计决策：图标为什么是「灰底 + 灰图标」而不是「彩底 + 白图标」
 * ═══════════════════════════════════════════════════════════════
 *
 * 彩色圆角底 + 白色图标是消费级 App 的通用做法（iOS 设置页、各家国产 App）。
 * 它有效的原因是**帮助快速定位**，但代价是：一整屏十几个彩色方块，
 * 视觉噪音极大，而且会抢走真正需要被注意的东西（如风险徽章）的注意力。
 *
 * 本产品选择**只给图标本身着色**，且用次级文字色。
 * 结果是列表看起来很"静"，红色徽章出现时才有分量。
 * 这是"简约"在组件层面的具体含义。
 */
@Composable
fun PaListGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        contentPadding = 0.dp,
    ) {
        content()
    }
}

/** 组内分隔线。左侧缩进 56dp 对齐文字起始位置（32 图标 + 12 间距 + 12 内边距） */
@Composable
fun PaListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 56.dp),
        thickness = 1.dp,
        color = PaColor.GlassBorder.copy(alpha = 0.06f),
    )
}

@Composable
fun PaListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    badgeTone: PaBadgeTone = PaBadgeTone.Neutral,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(horizontal = PaSpace.m, vertical = PaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(PaRadius.xs))
                .background(PaColor.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PaColor.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
        }

        Spacer(Modifier.width(PaSpace.s))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PaType.body,
                color = PaColor.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }
        }

        if (badge != null) {
            Spacer(Modifier.width(PaSpace.xs))
            PaBadge(text = badge, tone = badgeTone)
        }

        if (trailing != null) {
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = trailing,
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }

        if (onClick != null) {
            Spacer(Modifier.width(PaSpace.xxs))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = PaColor.TextDisabled,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
