package com.pocketagent.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.InstalledPlugin
import com.pocketagent.plugin.api.PluginCapability
import com.pocketagent.plugin.api.PluginLevel
import com.pocketagent.ui.components.CapabilityChip
import com.pocketagent.ui.components.CenterHint
import com.pocketagent.ui.components.RiskBadge
import com.pocketagent.ui.theme.RiskColors

/**
 * 已安装的插件。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一页最重要的东西是一句实话
 * ═══════════════════════════════════════════════════════════════
 *
 * M0 阶段这些插件**一个都不会动** —— 授权流程和执行引擎都还没落地。
 *
 * 最省事的做法是摆一个"启用"开关，让它看起来能用。但那是在骗用户：
 * 他打开开关、回到微信、等了三分钟什么也没发生，然后会认为**这个应用是坏的**。
 * 一次这样的体验，比"还没有这个功能"糟糕得多。
 *
 * 所以顶部横幅必须把状态说清楚，页面上也不放任何点了没反应的控件。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstalledPluginsScreen(
    viewModel: InstalledPluginsViewModel,
    onBack: () -> Unit,
    onOpenMarket: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("已安装的插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> CenterHint { CircularProgressIndicator() }

            state.isEmpty -> EmptyContent(
                modifier = Modifier.padding(padding),
                onOpenMarket = onOpenMarket,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { NotActiveBanner() }

                items(state.plugins, key = { it.dirName }) { plugin ->
                    InstalledCard(
                        plugin = plugin,
                        onUninstall = { viewModel.askUninstall(plugin) },
                    )
                }
            }
        }
    }

    // 卸载确认。删的是用户自己的东西，所以必须有这一步
    state.pendingUninstall?.let { target ->
        UninstallDialog(
            target = target,
            onConfirm = viewModel::confirmUninstall,
            onDismiss = viewModel::cancelUninstall,
        )
    }
}

/**
 * "装了但没生效"横幅。
 *
 * 措辞刻意不写"敬请期待"之类的软话 —— 那听起来像营销。
 * 这里要交代的是**具体缺什么**，以及**现在处于什么状态**（安全的那种状态）。
 */
@Composable
private fun NotActiveBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("这些插件还没有生效", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "让插件真正干活需要两样东西：一是授权（你逐项批准它申请的能力），" +
                    "二是执行引擎（读取屏幕、模拟点击）。两者都会在 M1 落地。\n\n" +
                    "在那之前，下面这些插件不会读取任何内容，也不会操作任何应用 —— " +
                    "它们只是躺在磁盘上的文件。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledCard(
    plugin: InstalledPlugin,
    onUninstall: () -> Unit,
) {
    val manifest = plugin.manifest

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {

            // ── 损坏的目录：不装成正常插件，也不藏起来 ──────────
            if (plugin.isBroken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plugin.dirName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        color = RiskColors.medium.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            "无法识别",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = RiskColors.medium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "这个目录的清单读不出来：${plugin.brokenReason.orEmpty()}\n\n" +
                        "它可能是安装途中被打断留下的残留，也可能是清单被改坏了。" +
                        "你可以直接卸载掉它。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                UninstallRow(displayName = plugin.dirName, onUninstall = onUninstall)
                return@Column
            }

            // ── 正常插件 ────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    manifest?.version.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                RiskBadge(plugin.highestRisk())
            }

            manifest?.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // 能力照旧全部铺开。这一页是用户**回头看自己装了什么**的地方，
            // 更不该折叠 —— 装的时候看过一眼，不代表现在还记得
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                manifest?.capabilities.orEmpty().forEach { CapabilityChip(it) }
            }

            if (manifest?.capabilities.isNullOrEmpty()) {
                Text(
                    "这个插件没有申请任何能力，因此它做不了任何事。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                buildString {
                    manifest?.let { append("${it.level.displayName()}　·　") }
                    append("安装于 ${formatInstallTime(plugin.installedAtMillis)}")
                    manifest?.author?.let { append("　·　作者：$it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 不限定目标应用的插件风险更高 —— 这条警告在市场页出现过，
            // 在"回头看"的页面里同样要出现
            if (manifest?.targetApps.isNullOrEmpty() && !manifest?.capabilities.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "它没有限定作用于哪些应用，也就是说任何界面都可能被它操作。",
                    style = MaterialTheme.typography.labelSmall,
                    color = RiskColors.medium,
                )
            }

            Spacer(Modifier.height(10.dp))
            UninstallRow(displayName = plugin.displayName, onUninstall = onUninstall)
        }
    }
}

@Composable
private fun UninstallRow(displayName: String, onUninstall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onUninstall) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(6.dp))
            Text("卸载", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 卸载确认。
 *
 * 文案里特意区分了"从市场装的"和"自己导入的"两种后续路径 ——
 * 因为卸载之后能不能找回来，这两者完全不同。用户有权在删之前知道这件事。
 */
@Composable
private fun UninstallDialog(
    target: InstalledPlugin,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("卸载「${target.displayName}」？") },
        text = {
            Text(
                "这会删除插件的全部文件，无法撤销。\n\n" +
                    "如果它是从市场装的，之后还能重新装回来；" +
                    "如果是你自己导入的，就需要重新找到那个 .pagent 文件。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("卸载", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun EmptyContent(
    modifier: Modifier = Modifier,
    onOpenMarket: () -> Unit,
) {
    CenterHint {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有装任何插件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "插件是本应用扩展能力的唯一方式。本体只提供能力（读屏、点击、输入），" +
                    "具体「用这些能力做什么」由插件决定。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenMarket) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("去插件市场看看")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  辅助
// ═══════════════════════════════════════════════════════════════

private fun InstalledPlugin.highestRisk(): PluginCapability.RiskLevel? =
    manifest?.capabilities?.maxByOrNull { it.risk.ordinal }?.risk

private fun PluginLevel.displayName(): String = when (this) {
    PluginLevel.L1_RULES -> "规则包"
    PluginLevel.L2_SCRIPT -> "脚本"
    PluginLevel.L3_NATIVE -> "原生插件"
}

private val INSTALL_TIME_FORMAT =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatInstallTime(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(INSTALL_TIME_FORMAT)
