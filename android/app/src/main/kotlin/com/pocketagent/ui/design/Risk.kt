package com.pocketagent.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketagent.plugin.api.PluginCapability
import com.pocketagent.plugin.api.label

/**
 * 风险相关的公共组件。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这几个组件必须共用，不能各页面自己写一套
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户判断一个插件危不危险，靠的是**颜色条件反射**：在市场页看到"红色 = 会读屏"，
 * 转头到管理页看到同一个插件，如果红色变成了橙色，这个反射就废了 ——
 * 而它恰恰是本应用最重要的一道人工防线。
 *
 * 所以颜色不是装饰，是**语义**。语义在两处不一致，等于没有语义。
 *
 * ⚠️ 正因如此，本文件的颜色取自 [PaColor.RiskLow] 等**不走主题切换**的常量 ——
 *    那四档刻意没有浅色版本，理由见 Tokens.kt 里的注释。
 */

/**
 * 风险等级 → 语义色。
 *
 * [level] 为 null 表示"未知"，用中性灰而不是绿色 ——
 * 把一个没解析出风险等级的东西画成"安全色"，是在替用户做他没授权的判断。
 */
fun riskColor(level: PluginCapability.RiskLevel?): Color = when (level) {
    PluginCapability.RiskLevel.LOW -> PaColor.RiskLow
    PluginCapability.RiskLevel.MEDIUM -> PaColor.RiskMedium
    PluginCapability.RiskLevel.HIGH -> PaColor.RiskHigh
    PluginCapability.RiskLevel.CRITICAL -> PaColor.RiskCritical
    null -> PaColor.TextTertiary
}

/**
 * 风险徽章。
 *
 * ⚠️ 文案里带"风险：低"这几个字，不只是色块。
 *    男性中约 8% 有红绿色觉障碍 —— 只靠颜色传递这道防线的信息，
 *    等于对他们隐藏了它。
 */
@Composable
fun RiskBadge(
    level: PluginCapability.RiskLevel?,
    modifier: Modifier = Modifier,
) {
    val color = riskColor(level)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = PaSpace.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "风险：${level?.label ?: "未知"}",
            style = PaType.label,
            color = color,
        )
    }
}

/**
 * 能力标签。
 *
 * 显示**大白话**而不是能力 id（`screen.read` → "读取当前屏幕上的文字和按钮"）。
 * 用户不需要知道本体的内部命名，需要知道的是"这东西能干什么"。
 */
@Composable
fun CapabilityChip(
    cap: PluginCapability,
    modifier: Modifier = Modifier,
) {
    val color = riskColor(cap.risk)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(PaColor.SurfaceHigh)
            .padding(horizontal = PaSpace.xs, vertical = 3.dp),
    ) {
        Text(
            text = cap.userFacingDescription,
            style = PaType.label,
            color = color,
        )
    }
}
