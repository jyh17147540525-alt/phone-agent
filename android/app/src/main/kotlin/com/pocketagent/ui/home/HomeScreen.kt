package com.pocketagent.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * 主页。
 *
 * M0 阶段这一页刻意做得**很空**。理由不是没时间，而是：
 * 这个应用真正的入口不是"和我聊天"，是**用户配置好能力之后去别的 App 里干活**。
 * 把它做成一个对话框首页，等于暗示用户"这是个聊天软件"，
 * 而那恰恰是它和豆包手机助手最大的区别。
 *
 * 所以首页只放两件事：装插件（拓展能力），和看状态（当前能不能干活）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMarket: () -> Unit,
    onOpenImport: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("PocketAgent") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("当前状态", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "尚未接入模型与无障碍服务。这两个是干活的必要条件 —— " +
                            "没有模型，它不知道该做什么；没有无障碍，它做不了。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            EntryCard(
                icon = Icons.Default.Star,
                title = "插件市场",
                subtitle = "浏览、搜索并安装社区插件。没有审核，来源自己判断。",
                onClick = onOpenMarket,
            )

            EntryCard(
                icon = Icons.Default.Add,
                title = "导入本地插件",
                subtitle = "从文件管理器选一个 .pagent 包。风险由你自行承担。",
                onClick = onOpenImport,
            )

            EntryCard(
                icon = Icons.Default.Settings,
                title = "已安装的插件",
                subtitle = "授权、停用或卸载。授权是插件真正能干活的前提。",
                onClick = { /* M1：插件管理页 */ },
                enabled = false,
            )
        }
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
