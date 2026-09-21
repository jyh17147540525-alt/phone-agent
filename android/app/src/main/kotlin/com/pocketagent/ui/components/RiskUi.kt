package com.pocketagent.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.plugin.api.PluginCapability
import com.pocketagent.plugin.api.label
import com.pocketagent.ui.theme.RiskColors

/**
 * 风险相关的公共 UI 组件。
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
 */

/** 风险语义色，不随浅色/深色主题漂移 —— 用户靠它形成条件反射 */
fun riskColor(level: PluginCapability.RiskLevel?): Color = when (level) {
    PluginCapability.RiskLevel.LOW -> RiskColors.low
    PluginCapability.RiskLevel.MEDIUM -> RiskColors.medium
    PluginCapability.RiskLevel.HIGH -> RiskColors.high
    PluginCapability.RiskLevel.CRITICAL -> RiskColors.critical
    null -> Color(0xFF8A8A8A)
}

@Composable
fun RiskBadge(level: PluginCapability.RiskLevel?) {
    val color = riskColor(level)
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
        Text(
            "风险：${level?.label ?: "未知"}",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun CapabilityChip(cap: PluginCapability) {
    val color = riskColor(cap.risk)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            cap.userFacingDescription,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 空状态 / 提示信息的居中容器 */
@Composable
fun CenterHint(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
