package com.pocketagent.ui.keys

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.keymgmt.StoredCredential
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
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType

/**
 * API Key 管理。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一页是整个 BYOK 模型的入口
 * ═══════════════════════════════════════════════════════════════
 *
 * 本应用不提供模型、不提供 Key、也没有自有服务端。用户填进来的这个 Key
 * 就是他为此付的唯一代价，所以这一页必须把三件事说清楚：
 *
 *   1. **Key 存在哪** —— 用系统密钥库加密后留在本机，不上传
 *   2. **请求从哪发** —— 从这台手机直接发往服务商，中间没有我们的服务器
 *   3. **Key 出问题怎么办** —— 校验结论分"Key 坏了"和"连不上"两种，
 *      因为这两种的用户动作完全不同（换 Key / 检查网络）
 *
 * 第 3 点是这一页最容易做错的地方。把网络故障显示成"Key 无效"，
 * 用户会去删一个完好的 Key，然后永远修不好 —— 而他会以为是应用有问题。
 */
@Composable
fun KeysScreen(
    viewModel: KeysViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ⚠️ 这一页会承载用户粘贴的明文 Key，也可能显示他的余额状态。
    //    必须防截屏 —— 见 CryptoManager 的类注释（安全红线第 4 条）。
    SecureScreen()

    PaScreen(
        title = "API Key",
        subtitle = "加密存在本机 · 请求直连服务商",
        onBack = onBack,
        modifier = modifier,
    ) {
        when (val store = state.store) {
            StoreState.Opening -> OpeningState()

            StoreState.Ready -> ReadyContent(state = state, viewModel = viewModel)

            is StoreState.Unrecoverable -> StoreBroken(
                title = "加密存储已经打不开了",
                reason = store.reason,
                hint = "这不是你的 Key 有问题，而是保护它们的系统密钥失效了。" +
                    "已保存的 Key 无法再读取，只能重新添加。",
                onRetry = viewModel::open,
                onReset = viewModel::resetStore,
            )

            is StoreState.Retryable -> StoreBroken(
                title = "暂时打不开加密存储",
                reason = store.reason,
                hint = "这通常只是暂时的问题。先重试一次 —— " +
                    "**只有在重试无效时才应该考虑重建**，因为重建会清掉已保存的 Key。",
                onRetry = viewModel::open,
                onReset = viewModel::resetStore,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  防截屏
// ═══════════════════════════════════════════════════════════════

/**
 * 在进入这一页期间打开 `FLAG_SECURE`，离开时关掉。
 *
 * ⚠️ 作用域**只限本页**，不是整个应用。任务页、插件页没有秘密，
 *    而 FLAG_SECURE 会让用户无法截图、也会让部分机型的录屏变黑 ——
 *    滥用它等于给用户制造无谓的麻烦，然后他就会去找绕过它的办法。
 *    只在真正需要的地方用，它才守得住。
 */
@Composable
private fun SecureScreen() {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(view, context) {
        val window = context.findActivity()?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * 从 Compose 的 `LocalContext` 里挖出 Activity。
 *
 * ⚠️ 必须递归解包装：Compose 的 context 常常是
 *    `ContextThemeWrapper` / `ContextWrapper` 套了好几层，
 *    直接 `as Activity` 会抛 ClassCastException —— 而且只在**部分**
 *    入口路径下抛（从 Activity 直接 setContent 时是最内层）。
 *    这种"有时崩有时不崩"的写法最难查。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ═══════════════════════════════════════════════════════════════
//  存储打不开
// ═══════════════════════════════════════════════════════════════

@Composable
private fun OpeningState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = PaColor.Accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 打不开时的降级界面。
 *
 * ⚠️ 两条出路都给出来，但**重试排在前面**。
 *    在不确定能不能恢复的时候，把破坏性的那个选项放在顺手的位置，
 *    迟早会有人在手滑时用它。
 */
@Composable
private fun StoreBroken(
    title: String,
    reason: String,
    hint: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    var confirmingReset by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PaSpace.screenH, vertical = PaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) {
        PaBanner(
            title = title,
            tone = PaBannerTone.Danger,
            description = reason,
        )

        Text(
            text = hint,
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )

        PaButton(
            text = "重试",
            onClick = onRetry,
            style = PaButtonStyle.Primary,
            fillWidth = true,
        )

        PaButton(
            text = "重建加密存储",
            onClick = { confirmingReset = true },
            style = PaButtonStyle.Glass,
            fillWidth = true,
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            containerColor = PaColor.Surface,
            shape = RoundedCornerShape(PaRadius.l),
            title = {
                Text(
                    text = "重建加密存储？",
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                )
            },
            text = {
                Text(
                    text = "这会删掉本机保存的全部 API Key，无法撤销。\n\n" +
                        "服务商那边不受影响 —— 你的 Key 还在，只是需要重新粘贴进来。\n\n" +
                        "如果重试就能打开，就不要走这一步。",
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) {
                    Text(text = "重建", style = PaType.label, color = PaColor.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(text = "取消", style = PaType.label, color = PaColor.TextSecondary)
                }
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  正常内容
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ReadyContent(state: KeysUiState, viewModel: KeysViewModel) {
    var pendingDelete by remember { mutableStateOf<StoredCredential?>(null) }

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
        state.problem?.let { problem ->
            item(key = "problem") {
                ProblemBanner(problem = problem, onDismiss = viewModel::dismissProblem)
            }
        }

        val editor = state.editor
        if (editor != null) {
            item(key = "editor") {
                EditorCard(
                    editor = editor,
                    // ⚠️ 候选按**表单自己的用途**取，不是取当前栏的。
                    //    用户在语音栏点"添加"，表单可能还开着上一次的
                    //    模型用途 —— 那样候选列表会和标题说的不是一回事。
                    providerChoices = state.providerChoicesFor(editor.purpose),
                    onProvider = viewModel::editProvider,
                    onKey = viewModel::editKey,
                    onLabel = viewModel::editLabel,
                    onBaseUrl = viewModel::editBaseUrl,
                    onSave = viewModel::save,
                    onCancel = viewModel::closeEditor,
                )
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  两组凭据
        // ═══════════════════════════════════════════════════════════
        //
        // 按用途分栏，而不是拉一个平铺列表。理由见 CredentialPurpose 的注释：
        // 一个混合了十来个 Key 的列表里，用户看不出"哪个给模型用、哪个给语音用"，
        // 而这两者**不能混用** —— 混用的后果是发出去 401，且用户无法自救。
        //
        // ⚠️ 每一栏**各自判断自己的空**。总的判空会漏掉
        //    "配了 3 个模型 Key、语音那条一个都没有"这个最常见的状态 ——
        //    而语音那一栏恰恰最需要一句"这里为什么是空的"。

        val llmCredentials = state.credentialsOf(CredentialPurpose.LLM)
        val ttsCredentials = state.credentialsOf(CredentialPurpose.TTS)

        if (llmCredentials.isNotEmpty()) {
            item(key = "listTitle.LLM") {
                PaSectionTitle(
                    text = if (editor != null) {
                        CredentialPurpose.LLM.displayName
                    } else {
                        "${CredentialPurpose.LLM.displayName} ${llmCredentials.size} 个"
                    }
                )
            }

            items(items = llmCredentials, key = { it.id }) { credential ->
                CredentialCard(
                    credential = credential,
                    validating = credential.id in state.validatingIds,
                    onSetDefault = { viewModel.setDefault(credential.id) },
                    onValidate = { viewModel.validate(credential.id) },
                    onDelete = { pendingDelete = credential },
                )
            }

            if (editor == null) {
                item(key = "addButton.LLM") {
                    PaButton(
                        text = "添加模型 Key",
                        onClick = { viewModel.openEditor(CredentialPurpose.LLM) },
                        style = PaButtonStyle.Text,
                        icon = Icons.Default.Add,
                    )
                }
            }
        }

        // ⚠️ 语音这栏**即使为空也要画**（只要该用途有候选服务商）。
        //
        //    "配了 3 个模型 Key、语音一条没有"是最常见的状态 ——
        //    而它恰恰是唯一需要解释的状态。总判空会把它整个跳过，
        //    用户于是永远不知道这一栏存在、也不知道自己该做什么。
        val ttsAvailable = state.providerChoicesFor(CredentialPurpose.TTS).isNotEmpty()

        if (ttsCredentials.isNotEmpty() || (ttsAvailable && editor == null && state.credentials.isNotEmpty())) {
            item(key = "listTitle.TTS") {
                PaSectionTitle(
                    text = if (editor != null) {
                        CredentialPurpose.TTS.displayName
                    } else {
                        "${CredentialPurpose.TTS.displayName} ${ttsCredentials.size} 个"
                    }
                )
            }

            items(items = ttsCredentials, key = { it.id }) { credential ->
                CredentialCard(
                    credential = credential,
                    validating = credential.id in state.validatingIds,
                    onSetDefault = { viewModel.setDefault(credential.id) },
                    onValidate = { viewModel.validate(credential.id) },
                    onDelete = { pendingDelete = credential },
                )
            }

            if (ttsCredentials.isEmpty() && editor == null) {
                item(key = "ttsHint") {
                    Text(
                        text = "语音合成是可选的。配一条之后，agent 说话才会用你自己的" +
                            "语音额度 —— 不配也能用，只是没有声音。",
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }
            }

            if (editor == null) {
                item(key = "addButton.TTS") {
                    PaButton(
                        text = "添加语音 Key",
                        onClick = { viewModel.openEditor(CredentialPurpose.TTS) },
                        style = PaButtonStyle.Text,
                        icon = Icons.Default.Add,
                    )
                }
            }
        }

        if (state.credentials.isEmpty() && editor == null) {
            item(key = "empty") {
                PaEmptyState(
                    title = "还没有配置 API Key",
                    description = "本应用不提供模型。填入你自己的 Key，请求就从这台手机" +
                        "直接发往服务商 —— 中间没有我们的服务器，也没有任何中转。",
                    actionText = "添加 Key",
                    onAction = { viewModel.openEditor(CredentialPurpose.LLM) },
                )
            }
        }
    }

    pendingDelete?.let { credential ->
        DeleteDialog(
            credential = credential,
            onConfirm = {
                viewModel.delete(credential.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ProblemBanner(problem: String, onDismiss: () -> Unit) {
    Column {
        PaBanner(title = "出错了", tone = PaBannerTone.Danger, description = problem)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = "知道了", style = PaType.label, color = PaColor.TextSecondary)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  添加表单
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorCard(
    editor: EditorState,
    /** `id to 展示名`。见 `KeysUiState.providerChoicesFor` 为何不是 Provider 对象 */
    providerChoices: List<Pair<String, String>>,
    onProvider: (String) -> Unit,
    onKey: (String) -> Unit,
    onLabel: (String) -> Unit,
    onBaseUrl: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    // ⚠️ 只能是 `remember`，绝不能是 `rememberSaveable`。
    //    后者会把值写进 saved instance state，而那个 Bundle 在进程被回收时
    //    会落盘 —— 那等于把明文 Key 写进磁盘。见 EditorState 的注释。
    var revealed by remember { mutableStateOf(false) }

    // ⚠️ 用途写进标题，而不是只留在状态里。
    //    用户是从两个不同的"添加"按钮进来的，如果表单长得一模一样，
    //    他没有任何办法确认自己点对了哪一个 —— 而存错用途不会报错。
    val purposeText = editor.purpose.displayName

    GlassSurface(contentPadding = PaSpace.m) {
        Text(text = "添加$purposeText Key", style = PaType.headline, color = PaColor.TextPrimary)
        Spacer(Modifier.height(PaSpace.s))

        if (providerChoices.isEmpty()) {
            // ⚠️ 这里**不放输入框**。
            //
            //    没有候选服务商意味着"本版本没有任何能收这种 Key 的地方"。
            //    让用户填一个存下来也没人读、校验也过不了的 Key，比拦住他更糟：
            //    他会以为自己填错了，然后一遍遍重试。
            //
            //    说清楚"还没接"、并给出出路，才是此刻唯一诚实的做法。
            //
            //    ⚠️ 这个分支在**两种用途下都可达**：TTS 是"还没接进来"，
            //       LLM 则会在"所有模型服务商都被裁掉"时落到这里。
            //       所以文案不能写死成"语音还没接" —— 它会变成谎话。
            PaBanner(
                title = "$purposeText 暂时没有可选的服务商",
                tone = PaBannerTone.Info,
                description = "本版本没有可用的$purposeText 服务商，" +
                    "所以这里先不让你填。等对应模块上线后，这一栏会开放。",
            )
            Spacer(Modifier.height(PaSpace.s))
            PaButton(
                text = "知道了",
                onClick = onCancel,
                style = PaButtonStyle.Glass,
                fillWidth = true,
            )
            return@GlassSurface
        }

        Text(text = "服务商", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            providerChoices.forEach { (id, displayName) ->
                PaFilterChip(
                    text = displayName,
                    selected = id == editor.providerId,
                    onClick = { onProvider(id) },
                )
            }
        }

        Spacer(Modifier.height(PaSpace.s))
        PaTextField(
            value = editor.keyInput,
            onValueChange = onKey,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "粘贴你的 API Key",
            isError = editor.error != null,
            visualTransformation = if (revealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                PaIconButton(
                    icon = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    onClick = { revealed = !revealed },
                    contentDescription = if (revealed) "隐藏" else "显示",
                    tint = PaColor.TextTertiary,
                )
            },
        )

        Spacer(Modifier.height(PaSpace.xs))
        PaTextField(
            value = editor.label,
            onValueChange = onLabel,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "备注名（可选）",
        )

        Spacer(Modifier.height(PaSpace.xs))
        PaTextField(
            value = editor.baseUrl,
            onValueChange = onBaseUrl,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "自定义接口地址（可选，留空用默认）",
        )

        if (editor.error != null) {
            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = editor.error,
                style = PaType.caption,
                color = PaColor.Danger,
            )
        }

        Spacer(Modifier.height(PaSpace.s))
        Text(
            text = "Key 会用系统密钥库加密后保存在本机，不会上传到任何地方。" +
                "保存后会自动校验一次 —— 校验不通过也不影响保存。",
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )

        Spacer(Modifier.height(PaSpace.s))
        Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
            PaButton(
                text = "取消",
                onClick = onCancel,
                style = PaButtonStyle.Glass,
                enabled = !editor.saving,
                modifier = Modifier.weight(1f),
            )
            PaButton(
                text = if (editor.saving) "保存中…" else "保存",
                onClick = onSave,
                style = PaButtonStyle.Primary,
                enabled = !editor.saving && editor.keyInput.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  凭据卡片
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CredentialCard(
    credential: StoredCredential,
    validating: Boolean,
    onSetDefault: () -> Unit,
    onValidate: () -> Unit,
    onDelete: () -> Unit,
) {
    val (badgeText, badgeTone) = statusBadge(credential.status)

    GlassSurface(contentPadding = PaSpace.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = credential.effectiveLabel,
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 用户没起名时 effectiveLabel 就是服务商名，
                    // 这时再显示一遍服务商名是冗余的
                    text = if (credential.label.isBlank()) {
                        "长度 ${credential.keyLength} 字符"
                    } else {
                        "${credential.providerDisplayName} · 长度 ${credential.keyLength} 字符"
                    },
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }

            if (credential.isDefault) {
                PaBadge(text = "使用中", tone = PaBadgeTone.Accent)
                Spacer(Modifier.width(PaSpace.xxs))
            }
            PaBadge(text = badgeText, tone = badgeTone)
        }

        // 校验结论里那句人话。有就显示 —— 它解释的是"现在该干什么"，
        // 而徽章上的两个字说不清楚这件事。
        credential.statusDetail?.let { detail ->
            Spacer(Modifier.height(PaSpace.xs))
            Text(text = detail, style = PaType.caption, color = PaColor.TextSecondary)
        }

        credential.lastCheckedAtMillis?.let { at ->
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "上次校验：${formatCheckedAt(at)}",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }

        Spacer(Modifier.height(PaSpace.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!credential.isDefault) {
                PaButton(
                    text = "设为默认",
                    onClick = onSetDefault,
                    style = PaButtonStyle.Text,
                )
            }
            Spacer(Modifier.weight(1f))
            PaButton(
                text = if (validating) "校验中…" else "校验",
                onClick = onValidate,
                style = PaButtonStyle.Text,
                enabled = !validating,
            )
            PaIconButton(
                icon = Icons.Default.DeleteOutline,
                onClick = onDelete,
                contentDescription = "删除",
                tint = PaColor.TextTertiary,
            )
        }
    }
}

@Composable
private fun DeleteDialog(
    credential: StoredCredential,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "删除「${credential.effectiveLabel}」？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Text(
                text = "这会从本机移除这个 Key，无法撤销。\n\n" +
                    "服务商那边不受影响 —— Key 本身还在，只是以后要重新粘贴进来。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", style = PaType.label, color = PaColor.Danger)
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
 * 校验状态 → 徽章。
 *
 * ⚠️ `UNREACHABLE` 用**中性色而不是红色**，这是有意的。
 *
 *    红色在用户心里等于"这个 Key 坏了"。而"连不上服务商"根本不说明
 *    Key 有问题 —— 可能是断网、可能是服务商在维护。把它标红，
 *    用户就会去删一个完好的 Key，然后永远修不好。
 *
 *    这条颜色规则和 `CredentialMapping` 里的映射规则是同一件事的两面：
 *    **只有 `AuthFailed` 能证明 Key 是坏的**，界面上就不该有第二种红色。
 */
private fun statusBadge(status: CredentialCheckStatus): Pair<String, PaBadgeTone> =
    when (status) {
        CredentialCheckStatus.UNCHECKED -> "未校验" to PaBadgeTone.Neutral
        CredentialCheckStatus.VALID -> "可用" to PaBadgeTone.Success
        CredentialCheckStatus.INVALID -> "无效" to PaBadgeTone.Danger
        CredentialCheckStatus.NO_BALANCE -> "余额不足" to PaBadgeTone.Warning
        CredentialCheckStatus.RATE_LIMITED -> "被限流" to PaBadgeTone.Warning
        CredentialCheckStatus.UNREACHABLE -> "连不上" to PaBadgeTone.Neutral
    }

/**
 * 相对时间。
 *
 * 用相对时间而不是绝对时间戳，是因为用户看这一行想知道的只有一件事：
 * "这个结论是刚拿到的，还是上周的"。`2026-09-21 14:03` 需要他自己做减法。
 *
 * ⚠️ 未来时间（设备时钟被改过）一律显示"刚刚"，不显示负数 ——
 *    也不显示"设备时间不对"之类的告警。用户改时钟有自己的理由，
 *    为这个弹提示是越界。
 */
private fun formatCheckedAt(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        else -> "${delta / 86_400_000L} 天前"
    }
}
