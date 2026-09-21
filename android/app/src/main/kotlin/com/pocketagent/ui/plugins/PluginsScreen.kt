package com.pocketagent.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.InstalledPlugin
import com.pocketagent.plugin.api.PluginLevel
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaListRow
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace

/**
 * 插件页 —— 底部导航第二个 tab。
 *
 * ═══════════════════════════════════════════════════════════════
 *  信息架构：为什么"已安装"和"获取更多"在同一页
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是用户在装完插件后**必然回到**的页面。把它做成"只有列表"，
 * 用户想再装一个就得先猜"从哪儿进市场"。
 *
 * 所以页面结构固定为两段：
 *   1. **已安装** —— 我现在有什么（空的时候给出行动入口，而不是干瘪的"暂无数据"）
 *   2. **获取更多** —— 我还能从哪儿拿（市场 / 本地导入）
 *
 * ⚠️ 与旧的 [InstalledPluginsScreen] 的区别：那是带返回箭头的二级页面，
 *    本页是顶层 tab，**没有返回箭头**（顶栏右边是"重新扫描"）。
 */
@Composable
fun PluginsScreen(
    viewModel: InstalledPluginsViewModel,
    modifier: Modifier = Modifier,
    onOpenMarket: () -> Unit = {},
    onOpenImport: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // ⚠️ 每次进入页面都重新扫盘。
    //
    //    典型路径：插件页 → 市场 → 装一个 → 返回。
    //    返回时 ViewModel 是**复用**的（NavBackStackEntry 还在栈里，
    //    它的 init 不会重跑），所以必须由 UI 侧触发刷新 ——
    //    否则用户看不到刚装好的插件，会以为安装失败了。
    //
    //    代价是首次进入会扫两次盘（init 一次 + 这里一次）。
    //    插件目录只有十几个文件，这点开销远小于"装了却看不见"的代价。
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        PaScreen(
            title = "插件",
            subtitle = if (state.plugins.isEmpty()) null else "${state.plugins.size} 个已安装",
            actions = {
                PaIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = viewModel::refresh,
                    contentDescription = "重新扫描",
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = PaSpace.screenH,
                    end = PaSpace.screenH,
                    top = PaSpace.xs,
                    bottom = PaSpace.l,
                ),
                verticalArrangement = Arrangement.spacedBy(PaSpace.s),
            ) {

                // ── 已安装 ──────────────────────────────────
                item { PaSectionTitle("已安装") }

                if (state.isEmpty) {
                    item {
                        PaEmptyState(
                            title = "还没有插件",
                            description = "插件给本体加上具体能力：\n某个 App 的自动化流程、某个页面的信息提取。",
                            actionText = "去插件市场看看",
                            onAction = onOpenMarket,
                        )
                    }
                } else {
                    item {
                        PaListGroup {
                            state.plugins.forEachIndexed { index, plugin ->
                                if (index > 0) PaListDivider()
                                PluginRow(
                                    plugin = plugin,
                                    onClick = { viewModel.askUninstall(plugin) },
                                )
                            }
                        }
                    }
                }

                // ── 获取更多 ────────────────────────────────
                item {
                    Spacer(Modifier.height(PaSpace.xs))
                    PaSectionTitle("获取更多")
                }
                item {
                    PaListGroup {
                        PaListRow(
                            icon = Icons.Default.Storefront,
                            title = "插件市场",
                            subtitle = "浏览订阅源里的插件",
                            onClick = onOpenMarket,
                        )
                        PaListDivider()
                        PaListRow(
                            icon = Icons.Default.FileDownload,
                            title = "本地导入",
                            subtitle = "从文件安装，风险自担",
                            onClick = onOpenImport,
                        )
                    }
                }
            }
        }

        // Snackbar 停在内容区底部（底栏由外壳负责，这里不会重叠）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(PaSpace.m),
        )
    }

    // ── 卸载确认 ────────────────────────────────────────
    // 卸载删的是用户自己的东西且不可恢复，所以点一下不算数，必须二次确认。
    state.pendingUninstall?.let { target ->
        UninstallDialog(
            plugin = target,
            onConfirm = viewModel::confirmUninstall,
            onDismiss = viewModel::cancelUninstall,
        )
    }
}

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    onClick: () -> Unit,
) {
    val manifest = plugin.manifest

    PaListRow(
        icon = Icons.Default.Extension,
        title = plugin.displayName,
        subtitle = if (manifest != null) {
            "v${manifest.version} · ${levelLabel(manifest.level)}"
        } else {
            // 损坏的目录照样列出来。让用户能看见它、能卸载它 ——
            // "东西不见了"比"东西坏了"严重得多（见 InstalledPlugin 的注释）
            plugin.brokenReason ?: "清单无法解析"
        },
        badge = if (plugin.isBroken) "损坏" else null,
        badgeTone = if (plugin.isBroken) PaBadgeTone.Danger else PaBadgeTone.Neutral,
        onClick = onClick,
    )
}

/**
 * 插件级别的大白话。
 *
 * 不显示 `L1_RULES` 这种枚举名 —— 用户不需要知道本体的内部分级，
 * 需要知道的是"这东西会不会执行代码"。
 */
private fun levelLabel(level: PluginLevel): String = when (level) {
    PluginLevel.L1_RULES -> "规则包"
    PluginLevel.L2_SCRIPT -> "脚本"
    PluginLevel.L3_NATIVE -> "原生"
}

@Composable
private fun UninstallDialog(
    plugin: InstalledPlugin,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "卸载「${plugin.displayName}」？") },
        text = {
            Text(
                text = "插件的文件会从本机删除，此操作不可撤销。\n" +
                    "如果你在插件里存过数据，那些数据也会一起消失。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "卸载", color = PaColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = PaColor.TextSecondary)
            }
        },
    )
}
