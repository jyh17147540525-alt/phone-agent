package com.pocketagent.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.SubscriptionRepository
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
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType

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
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {

        PaScreen(
            title = "订阅源",
            subtitle = if (state.loading) null else "${state.sources.size} 个已订阅",
            onBack = onBack,
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

                // ── 为什么要有这一页 ────────────────────────
                item {
                    PaBanner(
                        title = "市场没有服务端",
                        tone = PaBannerTone.Info,
                        description = "你订阅的是一串静态 JSON 地址，应用去把它们拉下来合并成一个列表。" +
                            "没有任何中间人，也没有任何审核。\n\n" +
                            "每张插件卡片都会显示它来自哪个源 —— 因为没人能替你判断哪个源可信，" +
                            "包括我们。",
                    )
                }

                // ── 添加 ────────────────────────────────────
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

                // ── 已有源 ──────────────────────────────────
                if (!state.loading) {
                    item {
                        PaSectionTitle(if (state.sources.isEmpty()) "已订阅" else "已订阅的源")
                    }
                }

                if (state.loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = PaSpace.l),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = PaColor.Accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                } else if (state.isEmpty) {
                    item {
                        PaEmptyState(
                            title = "还没有订阅任何源",
                            description = "市场页现在会是空的。\n" +
                                "你可以添加上面的地址，或者把内置源加回来。",
                            actionText = "恢复内置源",
                            onAction = viewModel::restoreBuiltin,
                        )
                    }
                } else {
                    item {
                        PaListGroup {
                            state.sources.forEachIndexed { index, url ->
                                if (index > 0) PaListDivider()
                                SourceRow(
                                    url = url,
                                    isBuiltin = url.equals(
                                        SubscriptionRepository.BUILTIN_SOURCE_URL,
                                        ignoreCase = true,
                                    ),
                                    onRemove = { viewModel.askRemove(url) },
                                )
                            }
                        }
                    }

                    if (!state.hasBuiltin) {
                        item {
                            Spacer(Modifier.height(PaSpace.xxs))
                            PaButton(
                                text = "恢复内置源",
                                onClick = viewModel::restoreBuiltin,
                                style = PaButtonStyle.Glass,
                                fillWidth = true,
                            )
                        }
                    }
                }

                // ── 怎么自己搭一个源 ────────────────────────
                item {
                    Spacer(Modifier.height(PaSpace.xs))
                    HowToHostCard()
                }
            }
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

// ═══════════════════════════════════════════════════════════════
//  添加订阅源
// ═══════════════════════════════════════════════════════════════

/**
 * ⚠️ 输入错误**不走 Snackbar**，就挂在输入框下面。
 *
 * 用户在这个界面上的动作是"改地址 → 再试"，而 Snackbar 两秒就消失，
 * 用户得靠记忆复述刚才那句话 —— 这等于让他改错。
 * 错误信息必须和输入框同生共死。
 */
@Composable
private fun AddSourceCard(
    input: String,
    error: String?,
    adding: Boolean,
    canSubmit: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {

        Text(
            text = "添加订阅源",
            style = PaType.headline,
            color = PaColor.TextPrimary,
        )

        Spacer(Modifier.height(PaSpace.s))

        PaTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "https://example.com/plugins/index.json",
            leadingIcon = Icons.Default.Link,
            enabled = !adding,
            isError = error != null,
        )

        if (error != null) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = error,
                style = PaType.caption,
                color = PaColor.Danger,
            )
        }

        Spacer(Modifier.height(PaSpace.xs))

        Text(
            text = "必须是 https 地址。明文 http 会被拒绝 —— 中间人能在那条通道上" +
                "把整个插件目录换成他自己的，而哈希校验防不住这个：" +
                "哈希也是被篡改的目录给的。",
            style = PaType.label,
            color = PaColor.TextTertiary,
        )

        Spacer(Modifier.height(PaSpace.s))

        Row {
            Spacer(Modifier.weight(1f))
            PaButton(
                text = if (adding) "添加中…" else "添加",
                onClick = onSubmit,
                enabled = canSubmit,
                style = PaButtonStyle.Primary,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  已订阅的源
// ═══════════════════════════════════════════════════════════════

/**
 * 单条源。
 *
 * ⚠️ 没有用 [com.pocketagent.ui.design.PaListRow] —— 那个组件的布局是
 *    「图标 + 单行标题 + 副标题 + 尾部箭头」，而这里的 URL 是**主要信息**，
 *    必须能折行显示（长地址折三行是常态），且尾部要放删除按钮而不是箭头。
 *    硬套只会把 URL 挤成一行省略号，用户就分不清自己订的是哪个源了。
 */
@Composable
private fun SourceRow(
    url: String,
    isBuiltin: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PaSpace.m,
                end = PaSpace.xs,
                top = PaSpace.s,
                bottom = PaSpace.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (isBuiltin) {
                PaBadge(text = "内置", tone = PaBadgeTone.Accent)
                Spacer(Modifier.height(PaSpace.xxs))
            }
            Text(
                text = url,
                style = PaType.caption,
                color = if (isBuiltin) PaColor.TextSecondary else PaColor.TextPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(PaSpace.xs))

        // 删除是破坏性动作，用危险色。但**不做二次确认之外的处理** ——
        // 移除源不卸载任何插件，损失是可逆的（重新加回来即可）
        PaIconButton(
            icon = Icons.Default.DeleteOutline,
            onClick = onRemove,
            contentDescription = "移除",
            tint = PaColor.Danger,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  怎么自己搭一个源
// ═══════════════════════════════════════════════════════════════

/**
 * 这段说明是这一页真正的价值所在：本应用没有服务端，所以**任何人**都能成为源。
 * 不告诉用户怎么做，这个设计就只对会写代码的人成立。
 */
@Composable
private fun HowToHostCard() {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {

        Text(
            text = "怎么自己搭一个源",
            style = PaType.headline,
            color = PaColor.TextPrimary,
        )

        Spacer(Modifier.height(PaSpace.xs))

        Text(
            text = "任意支持 https 的静态托管都行 —— GitHub Pages、Cloudflare Pages、" +
                "Vercel 之类都可以，都不花钱。\n\n" +
                "在上面放一个 index.json，内容是插件清单的数组，" +
                "每个条目包含插件的 id、名称、版本、能力、下载地址和 sha256。" +
                "完整格式见仓库 docs/ 下的插件体系说明第 3.2 节。\n\n" +
                "搭好之后把地址填到上面即可。你的源只有订阅了它的人能看到。",
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  移除确认
// ═══════════════════════════════════════════════════════════════

@Composable
private fun RemoveDialog(
    url: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "移除这个订阅源？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Text(
                text = "$url\n\n" +
                    "它的插件会从市场列表里消失。\n\n" +
                    "已经安装的插件不受影响 —— 移除一个源不会卸载任何东西。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "移除", style = PaType.label, color = PaColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = PaType.label, color = PaColor.TextSecondary)
            }
        },
    )
}
