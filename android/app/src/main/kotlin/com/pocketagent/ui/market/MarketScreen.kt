package com.pocketagent.ui.market

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.plugin.api.MarketCatalog
import com.pocketagent.plugin.api.MarketEntry
import com.pocketagent.plugin.api.MarketSearch
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
import com.pocketagent.ui.design.PaFilterChip
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.RiskBadge

/**
 * 插件市场。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个界面在"骗"用户，所以要说实话
 * ═══════════════════════════════════════════════════════════════
 *
 * 没有服务端、没有审核、没有下架能力。所以界面上**不能**出现
 * "官方认证""安全插件"这类措辞 —— 我们担保不了。
 *
 * 能做的只有三件事，界面就是围绕它们组织的：
 *
 *  1. **把来源摊开**：每个卡片都显示"来自哪个源"。
 *     源不可信是用户自己的选择，但他有权知道自己在信任谁。
 *  2. **把权限摊开**：直接列出插件申请的能力，不折叠、不藏在小字里。
 *  3. **把异常摊开**：被拒绝的条目、加载失败的源、用不了的未知能力，
 *     全部显示在顶部横幅里。悄悄过滤等于替用户做决定。
 */
@Composable
fun MarketScreen(
    viewModel: MarketViewModel,
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 搜索结果按 catalog + query 缓存。这两者任一变化才重算 ——
    // 否则每次重组都会遍历一遍整个目录
    val results = remember(state.catalog, state.query) {
        MarketSearch.search(state.catalog?.entries.orEmpty(), state.query)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        PaScreen(
            title = "插件市场",
            onBack = onBack,
            subtitle = if (results.isEmpty()) null else "${results.size} 个插件",
            actions = {
                // 订阅源入口放在刷新左边。这个位置是刻意的：
                // 当所有源都加载不出来时，用户唯一能做的就是改源，
                // 而他此刻正盯着这条顶部栏
                PaIconButton(
                    icon = Icons.Default.RssFeed,
                    onClick = onOpenSources,
                    contentDescription = "订阅源",
                )
                PaIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = viewModel::refresh,
                    contentDescription = "刷新",
                )
            },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                SearchAndFilters(
                    state = state,
                    onKeywordChange = { kw -> viewModel.updateQuery { it.copy(keyword = kw) } },
                    onToggleLevel = viewModel::toggleLevel,
                    onToggleHighRisk = viewModel::toggleExcludeHighRisk,
                )

                // ── 异常横幅 ────────────────────────────────────
                // 放在列表**之上**而不是列表里 —— 它是全局状态，
                // 不该跟着搜索结果一起被过滤掉
                state.catalog?.let { catalog ->
                    if (catalog.hasRejected) {
                        RejectedBanner(catalog)
                    }
                    if (catalog.sources.any { !it.ok }) {
                        SourceFailureBanner(catalog)
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.loading && state.catalog == null -> PaEmptyState(
                            title = "正在加载订阅源…",
                            modifier = Modifier.align(Alignment.Center),
                        )

                        state.fatalError != null -> PaEmptyState(
                            title = "加载失败",
                            description = state.fatalError,
                            actionText = "重试",
                            onAction = viewModel::refresh,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        results.isEmpty() -> if (state.query.keyword.isBlank()) {
                            // 空列表有两种成因：源是好的但没内容，和源根本没加载上。
                            // 用户分不清，所以两种出路都要给
                            PaEmptyState(
                                title = "这个源里还没有插件",
                                description = "如果所有源都加载失败，说明地址不可用，" +
                                    "或者它还没有内容。你可以添加自己的源。",
                                actionText = "管理订阅源",
                                onAction = onOpenSources,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            PaEmptyState(
                                title = "没有匹配的插件",
                                description = "换个关键词，或者放宽筛选条件。",
                                actionText = "从本地导入",
                                onAction = onOpenImport,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = PaSpace.screenH,
                                end = PaSpace.screenH,
                                top = PaSpace.xs,
                                bottom = PaSpace.l,
                            ),
                            verticalArrangement = Arrangement.spacedBy(PaSpace.s),
                        ) {
                            items(items = results, key = { it.id }) { entry ->
                                PluginCard(
                                    entry = entry,
                                    installed = entry.id in state.installedIds,
                                    installing = entry.id in state.installing,
                                    onInstall = { viewModel.install(entry) },
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(PaSpace.m),
        )
    }
}

@Composable
private fun SearchAndFilters(
    state: MarketUiState,
    onKeywordChange: (String) -> Unit,
    onToggleLevel: (PluginLevel) -> Unit,
    onToggleHighRisk: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            top = PaSpace.xs,
            bottom = PaSpace.s,
        ),
    ) {
        PaTextField(
            value = state.query.keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索插件名、作者或能力",
            leadingIcon = Icons.Default.Search,
        )

        Spacer(Modifier.height(PaSpace.xs))

        Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
            PaFilterChip(
                text = "规则包",
                selected = PluginLevel.L1_RULES in state.query.levels,
                onClick = { onToggleLevel(PluginLevel.L1_RULES) },
            )
            PaFilterChip(
                text = "脚本",
                selected = PluginLevel.L2_SCRIPT in state.query.levels,
                onClick = { onToggleLevel(PluginLevel.L2_SCRIPT) },
            )
            PaFilterChip(
                text = "只看低风险",
                selected = state.query.excludeHighRisk,
                onClick = onToggleHighRisk,
            )
        }
    }
}

/**
 * 被拒绝的条目必须让用户看见 —— 这是"这个源在分发什么"的唯一信号。
 *
 * ⚠️ 用 Danger 而不是 Warning：分发申请支付/密钥能力的插件，
 *    不是"需要注意"，是"这个源可能有问题"。
 */
@Composable
private fun RejectedBanner(catalog: MarketCatalog) {
    PaBanner(
        title = "有 ${catalog.rejected.size} 个条目已被拒绝",
        tone = PaBannerTone.Danger,
        description = "你订阅的源正在分发申请禁止能力（支付、密钥、系统权限）的插件。" +
            "本应用不会安装它们，但你可能需要重新考虑是否继续信任这个源。",
        details = catalog.rejected.take(3).map { "${it.name}（${it.sourceName}）" },
        modifier = Modifier.padding(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            bottom = PaSpace.xs,
        ),
    )
}

/**
 * 源加载失败用 Warning 而不是 Danger —— 单个源挂了是常态
 * （网络抖动、地址过期），把它渲染成红色会让用户以为整个应用出问题了。
 */
@Composable
private fun SourceFailureBanner(catalog: MarketCatalog) {
    val failed = catalog.sources.filter { !it.ok }
    PaBanner(
        title = "${failed.size} 个订阅源加载失败",
        tone = PaBannerTone.Warning,
        description = "其他源不受影响。如果这个源是你自己加的，检查一下地址是否正确。",
        details = failed.map { "${it.name}：${it.message}" },
        modifier = Modifier.padding(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            bottom = PaSpace.xs,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    entry: MarketEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = entry.version,
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            RiskBadge(entry.highestRisk)
        }

        entry.description?.let {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = it,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        // 能力直接铺开，不折叠。用户要判断这个插件能不能信，
        // 就得先知道它能干什么
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xxs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xxs),
        ) {
            entry.capabilities.forEach { cap ->
                CapabilityChip(cap)
            }
        }

        Spacer(Modifier.height(PaSpace.s))

        Text(
            text = buildString {
                append("来自：${entry.sourceName.ifBlank { "未知源" }}")
                entry.author?.let { append("　·　作者：$it") }
                append("　·　${entry.trustLevel.displayName}")
            },
            style = PaType.label,
            color = PaColor.TextTertiary,
        )

        // 用不了的能力要说出来，否则用户会以为是插件坏了
        if (entry.unsupportedCapabilityIds.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = "它声明了本版本不支持的能力（" +
                    "${entry.unsupportedCapabilityIds.joinToString("、")}），" +
                    "可能无法正常工作。请升级本应用后再试。",
                style = PaType.label,
                color = PaColor.RiskMedium,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            if (installed) {
                PaBadge(text = "已安装", tone = PaBadgeTone.Success)
            } else {
                PaButton(
                    text = if (installing) "安装中…" else "安装",
                    onClick = onInstall,
                    enabled = !installing,
                    style = PaButtonStyle.Primary,
                )
            }
        }
    }
}
