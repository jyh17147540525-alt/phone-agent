package com.pocketagent.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.plugin.api.MarketEntry
import com.pocketagent.plugin.api.MarketSearch
import com.pocketagent.plugin.api.PluginCapability
import com.pocketagent.plugin.api.PluginLevel
import com.pocketagent.ui.components.CapabilityChip
import com.pocketagent.ui.components.CenterHint
import com.pocketagent.ui.components.RiskBadge
import com.pocketagent.ui.theme.RiskColors

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    val results = remember(state.catalog, state.query) {
        MarketSearch.search(state.catalog?.entries.orEmpty(), state.query)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("插件市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 订阅源入口放在刷新左边。这个位置是刻意的：
                    // 当所有源都加载不出来时，用户唯一能做的就是改源，
                    // 而他此刻正盯着这条顶部栏
                    IconButton(onClick = onOpenSources) {
                        Icon(Icons.Default.RssFeed, contentDescription = "订阅源")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            SearchAndFilters(
                state = state,
                onKeywordChange = { kw -> viewModel.updateQuery { it.copy(keyword = kw) } },
                onToggleLevel = { level -> viewModel.toggleLevel(level) },
                onToggleHighRisk = { viewModel.toggleExcludeHighRisk() },
            )

            // 异常横幅。放在列表**之上**而不是列表里 ——
            // 它是全局状态，不该跟着搜索结果一起被过滤掉
            state.catalog?.let { catalog ->
                if (catalog.hasRejected) RejectedBanner(catalog)
                if (catalog.sources.any { !it.ok }) SourceFailureBanner(catalog)
            }

            when {
                state.loading && state.catalog == null -> CenterHint {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在加载订阅源…")
                }

                state.fatalError != null -> CenterHint {
                    Text("加载失败：${state.fatalError}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::refresh) { Text("重试") }
                }

                results.isEmpty() -> CenterHint {
                    if (state.query.keyword.isBlank()) {
                        Text("这个源里还没有插件")
                        Spacer(Modifier.height(6.dp))
                        // 空列表有两种成因：源是好的但没内容，和源根本没加载上。
                        // 用户分不清，所以两种出路都要给
                        Text(
                            "如果所有源都加载失败，说明地址不可用，或者它还没有内容。" +
                                "你可以添加自己的源。",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text("没有匹配的插件")
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenSources) { Text("管理订阅源") }
                        Button(onClick = onOpenImport) { Text("从本地导入") }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.id }) { entry ->
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

@Composable
private fun SearchAndFilters(
    state: MarketUiState,
    onKeywordChange: (String) -> Unit,
    onToggleLevel: (PluginLevel) -> Unit,
    onToggleHighRisk: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query.keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索插件名、作者或能力") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = PluginLevel.L1_RULES in state.query.levels,
                onClick = { onToggleLevel(PluginLevel.L1_RULES) },
                label = { Text("规则包") },
            )
            FilterChip(
                selected = PluginLevel.L2_SCRIPT in state.query.levels,
                onClick = { onToggleLevel(PluginLevel.L2_SCRIPT) },
                label = { Text("脚本") },
            )
            FilterChip(
                selected = state.query.excludeHighRisk,
                onClick = onToggleHighRisk,
                label = { Text("只看低风险") },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 被拒绝的条目必须让用户看见 —— 这是"源在分发什么"的唯一信号 */
@Composable
private fun RejectedBanner(catalog: com.pocketagent.plugin.api.MarketCatalog) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "有 ${catalog.rejected.size} 个条目已被拒绝",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "你订阅的源正在分发申请禁止能力（支付、密钥、系统权限）的插件。" +
                    "本应用不会安装它们，但你可能需要重新考虑是否继续信任这个源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            catalog.rejected.take(3).forEach {
                Text(
                    "· ${it.name}（${it.sourceName}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SourceFailureBanner(catalog: com.pocketagent.plugin.api.MarketCatalog) {
    val failed = catalog.sources.filter { !it.ok }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("${failed.size} 个订阅源加载失败", fontWeight = FontWeight.SemiBold)
            failed.forEach {
                Text("· ${it.name}：${it.message}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "其他源不受影响。如果这个源是你自己加的，检查一下地址是否正确。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginCard(
    entry: MarketEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.version,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                RiskBadge(entry.highestRisk)
            }

            entry.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // 能力直接铺开，不折叠。用户要判断这个插件能不能信，就得先知道它能干什么
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                entry.capabilities.forEach { cap ->
                    CapabilityChip(cap)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                buildString {
                    append("来自：${entry.sourceName.ifBlank { "未知源" }}")
                    entry.author?.let { append("　·　作者：$it") }
                    append("　·　${entry.trustLevel.displayName}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 用不了的能力要说出来，否则用户会以为是插件坏了
            if (entry.unsupportedCapabilityIds.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠️ 它声明了本版本不支持的能力（${entry.unsupportedCapabilityIds.joinToString("、")}），" +
                        "可能无法正常工作。请升级本应用后再试。",
                    style = MaterialTheme.typography.labelSmall,
                    color = RiskColors.medium,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (installed) {
                    Text(
                        "已安装",
                        style = MaterialTheme.typography.labelLarge,
                        color = RiskColors.low,
                    )
                } else {
                    Button(onClick = onInstall, enabled = !installing) {
                        if (installing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("安装")
                    }
                }
            }
        }
    }
}

// RiskBadge / CapabilityChip / CenterHint / riskColor 已提取到
// com.pocketagent.ui.components（见 RiskUi.kt）。
//
// 提取的理由不是"少写几行"，而是：**同一个风险等级在不同页面必须长得一模一样**。
// 用户是靠在市场页形成的颜色条件反射去判断管理页里那个插件的 ——
// 两处配色一旦漂移，这道人工防线就废了。
