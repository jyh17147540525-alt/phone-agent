package com.pocketagent.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.InstalledPlugin
import com.pocketagent.plugin.api.PluginCapability
import com.pocketagent.plugin.api.PluginLevel
import com.pocketagent.ui.design.CapabilityChip
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaListRow
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.RiskBadge

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
 * ⚠️ 本页合并了旧的 [InstalledPluginsScreen]（那是个带返回箭头的二级页面）。
 *    合并时**一条信息都没敢丢** —— 尤其是"插件还没生效"那条横幅和每张卡片上
 *    铺开的能力清单。理由见各自的注释。
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
                            description = "插件是本应用扩展能力的唯一方式。本体只提供能力" +
                                "（读屏、点击、输入），具体「用这些能力做什么」由插件决定。",
                            actionText = "去插件市场看看",
                            onAction = onOpenMarket,
                        )
                    }
                } else {
                    // 这条横幅必须排在所有卡片**前面**，不能挪到后面 ——
                    // 它说的是一件"看完才知道该不该往下看"的事
                    item { NotActiveBanner() }

                    items(items = state.plugins, key = { it.dirName }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onUninstall = { viewModel.askUninstall(plugin) },
                        )
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

// ═══════════════════════════════════════════════════════════════
//  "装了但没生效"横幅
// ═══════════════════════════════════════════════════════════════

/**
 * 这一页最重要的东西是一句实话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  M0 阶段这些插件**一个都不会动** —— 授权流程和执行引擎都还没落地。
 * ═══════════════════════════════════════════════════════════════
 *
 * 最省事的做法是摆一个"启用"开关，让它看起来能用。但那是在骗用户：
 * 他打开开关、回到微信、等了三分钟什么也没发生，然后会认为**这个应用是坏的**。
 * 一次这样的体验，比"还没有这个功能"糟糕得多。
 *
 * ⚠️ 措辞刻意不写"敬请期待"之类的软话 —— 那听起来像营销。
 *    这里要交代的是**具体缺什么**，以及**现在处于什么状态**（安全的那种状态）。
 */
@Composable
private fun NotActiveBanner() {
    PaBanner(
        title = "这些插件还没有生效",
        tone = PaBannerTone.Warning,
        description = "让插件真正干活需要两样东西：一是授权（你逐项批准它申请的能力），" +
            "二是执行引擎（读取屏幕、模拟点击）。两者都会在 M1 落地。\n\n" +
            "在那之前，下面这些插件不会读取任何内容，也不会操作任何应用 —— " +
            "它们只是躺在磁盘上的文件。",
    )
}

// ═══════════════════════════════════════════════════════════════
//  已安装插件卡片
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    onUninstall: () -> Unit,
) {
    val manifest = plugin.manifest

    GlassSurface(modifier = Modifier.fillMaxWidth()) {

        // ── 损坏的目录：不装成正常插件，也不藏起来 ──────────
        //
        // 为什么坏目录也要渲染：让用户能看见它、能卸载它。
        // "东西不见了"比"东西坏了"严重得多 —— 前者会让人怀疑应用在偷删数据。
        if (plugin.isBroken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.dirName,
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                PaBadge(text = "无法识别", tone = PaBadgeTone.Warning)
            }

            Spacer(Modifier.height(PaSpace.xs))

            Text(
                text = "这个目录的清单读不出来：${plugin.brokenReason.orEmpty()}\n\n" +
                    "它可能是安装途中被打断留下的残留，也可能是清单被改坏了。" +
                    "你可以直接卸载掉它。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )

            Spacer(Modifier.height(PaSpace.s))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                PaButton(
                    text = "卸载",
                    onClick = onUninstall,
                    style = PaButtonStyle.Danger,
                    icon = Icons.Default.DeleteOutline,
                )
            }
            return@GlassSurface
        }

        // ── 正常插件 ────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = plugin.displayName,
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = manifest?.version.orEmpty(),
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            RiskBadge(plugin.highestRisk())
        }

        manifest?.description?.let {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = it,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        // 能力照旧全部铺开。这一页是用户**回头看自己装了什么**的地方，
        // 更不该折叠 —— 装的时候看过一眼，不代表现在还记得
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xxs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xxs),
        ) {
            manifest?.capabilities.orEmpty().forEach { CapabilityChip(it) }
        }

        if (manifest?.capabilities.isNullOrEmpty()) {
            Text(
                text = "这个插件没有申请任何能力，因此它做不了任何事。",
                style = PaType.label,
                color = PaColor.TextTertiary,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        Text(
            text = buildString {
                manifest?.let { append("${it.level.displayName()}　·　") }
                append("安装于 ${formatInstallTime(plugin.installedAtMillis)}")
                manifest?.author?.let { append("　·　作者：$it") }
            },
            style = PaType.label,
            color = PaColor.TextTertiary,
        )

        // 不限定目标应用的插件风险更高 —— 这条警告在市场页出现过，
        // 在"回头看"的页面里同样要出现
        if (manifest?.targetApps.isNullOrEmpty() && !manifest?.capabilities.isNullOrEmpty()) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "它没有限定作用于哪些应用，也就是说任何界面都可能被它操作。",
                style = PaType.label,
                color = PaColor.RiskMedium,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PaButton(
                text = "卸载",
                onClick = onUninstall,
                style = PaButtonStyle.Danger,
                icon = Icons.Default.DeleteOutline,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  卸载确认
// ═══════════════════════════════════════════════════════════════

/**
 * 卸载确认。
 *
 * 文案里特意区分了"从市场装的"和"自己导入的"两种后续路径 ——
 * 因为卸载之后能不能找回来，这两者完全不同。用户有权在删之前知道这件事。
 */
@Composable
private fun UninstallDialog(
    plugin: InstalledPlugin,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "卸载「${plugin.displayName}」？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Text(
                text = "这会删除插件的全部文件，无法撤销。\n\n" +
                    "如果它是从市场装的，之后还能重新装回来；" +
                    "如果是你自己导入的，就需要重新找到那个 .pagent 文件。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "卸载", style = PaType.label, color = PaColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = PaType.label, color = PaColor.TextSecondary)
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════
//  辅助
// ═══════════════════════════════════════════════════════════════

/**
 * 插件里最高的风险等级。
 *
 * 取 `maxByOrNull { it.risk.ordinal }` 而不是"有 CRITICAL 就 CRITICAL"的硬编码链 ——
 * [PluginCapability.RiskLevel] 将来加档位时，这里自动跟上，不会漏。
 * 枚举声明顺序即严重程度顺序，这个前提由枚举本身保证。
 */
private fun InstalledPlugin.highestRisk(): PluginCapability.RiskLevel? =
    manifest?.capabilities?.maxByOrNull { it.risk.ordinal }?.risk

/**
 * 插件级别的大白话。
 *
 * 不显示 `L1_RULES` 这种枚举名 —— 用户不需要知道本体的内部分级，
 * 需要知道的是"这东西会不会执行代码"。
 */
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
