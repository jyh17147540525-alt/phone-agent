package com.pocketagent.ui.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.plugin.api.ImportRiskNotice
import com.pocketagent.plugin.api.ValidationIssue
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType
import java.io.ByteArrayOutputStream

/**
 * 本地插件导入页。
 *
 * ═══════════════════════════════════════════════════════════════
 *  "风险由用户自行承担"要落到实处
 * ═══════════════════════════════════════════════════════════════
 *
 * 一句"风险自负"写在角落的小字里，等于没写。用户点"同意"的成本太低，
 * 低到不构成一个决定。所以这一页做了三件事：
 *
 *  1. **把插件能干什么逐条摊开**，而不是笼统一句"插件可能有风险"
 *  2. **说清本体管不了什么** —— 用户得知道"没有签名校验"具体意味着什么
 *  3. **高危插件要求手动打出「我已知晓风险」**，把点一下变成读一遍
 *
 * 这不是为了给用户添堵。这个应用没有账号体系、没有服务端、没有客服，
 * 一旦出事**我们连追责对象都提供不了** —— 那么至少要让用户在按下按钮的
 * 那一刻，是真的知道自己在做什么。
 */
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.displayNameOf(uri) ?: "plugin.pagent"
        val bytes = resolver.readCapped(uri, MAX_BUNDLE_BYTES)

        if (bytes == null) {
            viewModel.inspect("$name.无法读取", ByteArray(0))
        } else {
            viewModel.inspect(name, bytes)
        }
    }

    PaScreen(title = "导入本地插件", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PaSpace.screenH,
                    end = PaSpace.screenH,
                    top = PaSpace.xs,
                    bottom = PaSpace.l,
                ),
        ) {
            when (state.stage) {
                ImportUiState.Stage.IDLE -> IdleContent(
                    onPick = { picker.launch(arrayOf("*/*")) },
                )

                ImportUiState.Stage.INSPECTING -> PaEmptyState(
                    title = "正在检查…",
                    description = state.fileName.orEmpty(),
                    modifier = Modifier.padding(top = PaSpace.xxl),
                )

                ImportUiState.Stage.READY -> state.notice?.let { notice ->
                    NoticeContent(
                        notice = notice,
                        typed = state.typed,
                        onTypedChange = viewModel::updateTyped,
                        canConfirm = state.canConfirm,
                        installing = state.installing,
                        onConfirm = viewModel::confirmInstall,
                        onCancel = viewModel::reset,
                    )
                }

                ImportUiState.Stage.REJECTED -> RejectedContent(
                    errors = state.errors,
                    onRetry = viewModel::reset,
                )

                ImportUiState.Stage.MALFORMED -> SimpleOutcome(
                    title = "无法导入这个文件",
                    message = state.malformed.orEmpty(),
                    actionText = "重新选择文件",
                    onAction = viewModel::reset,
                )

                ImportUiState.Stage.DONE -> SimpleOutcome(
                    title = "安装完成",
                    message = state.doneMessage.orEmpty(),
                    actionText = "完成",
                    onAction = onBack,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit) {
    Text(
        text = "导入未经验证的插件",
        style = PaType.title,
        color = PaColor.TextPrimary,
    )

    Spacer(Modifier.height(PaSpace.s))

    Text(
        text = "插件包（.pagent）是一个 zip 文件，里面至少要有 plugin.json " +
            "和它引用的规则或脚本文件。",
        style = PaType.body,
        color = PaColor.TextSecondary,
    )

    Spacer(Modifier.height(PaSpace.s))

    PaBanner(
        title = "从本地导入的插件没有任何审核",
        tone = PaBannerTone.Warning,
        description = "来源无法验证，签名可有可无。装之前请确认你信任给出这个文件的人。",
    )

    Spacer(Modifier.height(PaSpace.l))

    PaButton(
        text = "选择插件包",
        onClick = onPick,
        style = PaButtonStyle.Primary,
        fillWidth = true,
    )
}

@Composable
private fun NoticeContent(
    notice: ImportRiskNotice,
    typed: String,
    onTypedChange: (String) -> Unit,
    canConfirm: Boolean,
    installing: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = notice.title,
        style = PaType.title,
        color = PaColor.TextPrimary,
    )

    Spacer(Modifier.height(PaSpace.xs))

    Text(
        text = notice.summary,
        style = PaType.body,
        color = PaColor.TextSecondary,
    )

    Spacer(Modifier.height(PaSpace.m))

    // 逐条摊开这个插件能干什么。用玻璃卡片而不是折叠面板 ——
    // 折叠意味着"可以不看"，而这里的内容是用户做决定的前提
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        notice.bullets.forEachIndexed { index, bullet ->
            if (index > 0) Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = bullet,
                style = PaType.caption,
                color = PaColor.TextPrimary,
            )
        }
    }

    if (notice.warnings.isNotEmpty()) {
        Spacer(Modifier.height(PaSpace.s))
        PaBanner(
            title = "检查中发现的问题",
            tone = PaBannerTone.Warning,
            details = notice.warnings.map { it.message },
        )
    }

    Spacer(Modifier.height(PaSpace.l))

    if (notice.requireTypedConfirmation) {
        Text(
            text = "这个插件申请了高风险能力。请手动输入下面的六个字以继续：",
            style = PaType.body,
            color = PaColor.RiskHigh,
        )

        Spacer(Modifier.height(PaSpace.xs))

        // 确认词用等宽字体。用户要照着打，就不能让他猜
        // 那是「我已知晓风险」还是「我已知晓风险 」—— 多一个空格都不行
        Text(
            text = ImportRiskNotice.CONFIRMATION_PHRASE,
            style = PaType.headline,
            fontFamily = FontFamily.Monospace,
            color = PaColor.TextPrimary,
        )

        Spacer(Modifier.height(PaSpace.xs))

        PaTextField(
            value = typed,
            onValueChange = onTypedChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "在此输入确认词",
            singleLine = true,
            isError = typed.isNotEmpty() && typed.trim() != ImportRiskNotice.CONFIRMATION_PHRASE,
        )

        Spacer(Modifier.height(PaSpace.m))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        PaButton(
            text = "取消",
            onClick = onCancel,
            enabled = !installing,
            style = PaButtonStyle.Glass,
        )
        PaButton(
            text = if (installing) "安装中…" else "我已知晓风险，仍要安装",
            onClick = onConfirm,
            enabled = canConfirm,
            // 高危插件用 Danger 实心按钮：按下去之前手会停一下，这正是目的
            style = if (notice.requireTypedConfirmation) {
                PaButtonStyle.Danger
            } else {
                PaButtonStyle.Primary
            },
        )
    }
}

@Composable
private fun RejectedContent(errors: List<ValidationIssue>, onRetry: () -> Unit) {
    Text(
        text = "这个插件已被拒绝",
        style = PaType.title,
        color = PaColor.Danger,
    )

    Spacer(Modifier.height(PaSpace.xs))

    Text(
        text = "这不是格式问题。它申请了本应用永不开放的能力 —— " +
            "这类能力涉及资金、密钥与系统权限，本体不提供。",
        style = PaType.body,
        color = PaColor.TextSecondary,
    )

    Spacer(Modifier.height(PaSpace.m))

    errors.forEach { issue ->
        PaBanner(
            title = issue.field,
            tone = PaBannerTone.Danger,
            description = issue.message,
            modifier = Modifier.padding(bottom = PaSpace.xs),
        )
    }

    Spacer(Modifier.height(PaSpace.xs))

    Text(
        text = "建议：换一个来源。一个会申请禁止能力的插件，即使删掉那几行，" +
            "它的作者也不值得你信任。",
        style = PaType.caption,
        color = PaColor.TextTertiary,
    )

    Spacer(Modifier.height(PaSpace.l))

    PaButton(
        text = "重新选择文件",
        onClick = onRetry,
        style = PaButtonStyle.Glass,
        fillWidth = true,
    )
}

/**
 * 两种"终局"共用一套排版：文件坏了、装好了。
 *
 * 它们都不需要展示风险细节，只需要一句话 + 一个出口。
 * 分成两个函数只会让改文案时漏掉一个。
 */
@Composable
private fun SimpleOutcome(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = PaSpace.xl)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = PaType.title,
                color = PaColor.TextPrimary,
            )

            Spacer(Modifier.height(PaSpace.s))

            Text(
                text = message,
                style = PaType.body,
                color = PaColor.TextSecondary,
            )

            Spacer(Modifier.height(PaSpace.l))

            PaButton(
                text = actionText,
                onClick = onAction,
                style = PaButtonStyle.Primary,
                fillWidth = true,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  文件读取
// ═══════════════════════════════════════════════════════════════

/** 插件包大小上限，与 PluginBundle 的解压预算同量级 */
private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024

private fun ContentResolver.displayNameOf(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }

/**
 * 带上限的读取。
 *
 * ⚠️ 不能直接 `readBytes()`：用户选中的文件由他控制，一个 2 GB 的文件
 *    足以把应用打爆内存 —— 而崩掉的应用会丢掉无障碍权限，用户得重新
 *    走一遍引导。宁可报"文件过大"。
 */
private fun ContentResolver.readCapped(uri: Uri, maxBytes: Int): ByteArray? =
    openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
