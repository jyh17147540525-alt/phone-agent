package com.pocketagent.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.SubscriptionRepository
import com.pocketagent.ui.components.CenterHint

/**
 * 订阅源管理。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一页存在的理由
 * ═══════════════════════════════════════════════════════════════
 *
 * 市场没有服务端，内容是若干静态 JSON。所以「源」是这个应用里
 * **唯一的信任入口** —— 用户信任谁，取决于他订阅了谁。
 *
 * 而在这一页做出来之前，用户只能用内置源，且那个地址还没上线。
 * 结果是：打开市场，看到加载失败，然后**毫无办法**。
 * 设计文档里那句"官方源挂了用户还能自己加源"完全落不了地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
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
                title = { Text("订阅源") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { WhyBanner() }

            item {
                AddSourceCard(
                    input = state.input,
                    error = state.inputError,
                    adding = state.adding,
                    canSubmit = state.canSubmit,
                    onInputChange = viewModel::updateInput,
                    onSubmit = viewModel::add,
                )
            }

            if (state.loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }

            if (state.isEmpty) {
                item {
                    EmptySources(
                        onRestoreBuiltin = viewModel::restoreBuiltin,
                    )
                }
            }

            items(state.sources, key = { it }) { url ->
                SourceCard(
                    url = url,
                    isBuiltin = url.equals(SubscriptionRepository.BUILTIN_SOURCE_URL, ignoreCase = true),
                    onRemove = { viewModel.askRemove(url) },
                )
            }

            if (!state.hasBuiltin && !state.isEmpty) {
                item {
                    OutlinedButton(
                        onClick = viewModel::restoreBuiltin,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("恢复内置源")
                    }
                }
            }

            item { HowToHostCard() }
        }
    }

    state.pendingRemove?.let { url ->
        RemoveDialog(
            url = url,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::cancelRemove,
        )
    }
}

@Composable
private fun WhyBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("市场没有服务端", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "你订阅的是一串静态 JSON 地址，应用去把它们拉下来合并成一个列表。" +
                    "没有任何中间人，也没有任何审核。\n\n" +
                    "每张插件卡片都会显示它来自哪个源 —— 因为没人能替你判断哪个源可信，" +
                    "包括我们。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AddSourceCard(
    input: String,
    error: String?,
    adding: Boolean,
    canSubmit: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("添加订阅源", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("订阅地址") },
                placeholder = { Text("https://example.com/plugins/index.json") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "必须是 https 地址。明文 http 会被拒绝 —— 中间人能在那条通道上" +
                    "把整个插件目录换成他自己的，而哈希校验防不住这个：" +
                    "哈希也是被篡改的目录给的。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row {
                Spacer(Modifier.weight(1f))
                Button(onClick = onSubmit, enabled = canSubmit) {
                    if (adding) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("添加")
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    url: String,
    isBuiltin: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (isBuiltin) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "内置",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptySources(onRestoreBuiltin: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("还没有订阅任何源", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "市场页现在会是空的。你可以添加上面的地址，或者把内置源加回来。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRestoreBuiltin) {
                Text("恢复内置源")
            }
        }
    }
}

/**
 * 「怎么自己搭一个源」。
 *
 * 这段说明是这一页真正的价值所在：本应用没有服务端，所以**任何人**都能成为源。
 * 不告诉用户怎么做，这个设计就只对会写代码的人成立。
 */
@Composable
private fun HowToHostCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("怎么自己搭一个源", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "任意支持 https 的静态托管都行 —— GitHub Pages、Cloudflare Pages、" +
                    "Vercel 之类都可以，都不花钱。\n\n" +
                    "在上面放一个 index.json，内容是插件清单的数组，" +
                    "每个条目包含插件的 id、名称、版本、能力、下载地址和 sha256。" +
                    "完整格式见仓库 docs/ 下的插件体系说明第 3.2 节。\n\n" +
                    "搭好之后把地址填到上面即可。你的源只有订阅了它的人能看到。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RemoveDialog(
    url: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除这个订阅源？") },
        text = {
            Text(
                "$url\n\n" +
                    "它的插件会从市场列表里消失。\n\n" +
                    "已经安装的插件不受影响 —— 移除一个源不会卸载任何东西。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
