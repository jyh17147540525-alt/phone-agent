package com.pocketagent.ui.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.plugin.api.ImportRiskNotice
import com.pocketagent.plugin.api.ValidationIssue
import com.pocketagent.ui.theme.RiskColors
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
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入本地插件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (state.stage) {
                ImportUiState.Stage.IDLE -> IdleContent(
                    onPick = { picker.launch(arrayOf("*/*")) },
                )

                ImportUiState.Stage.INSPECTING -> CenterBlock {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在检查 ${state.fileName.orEmpty()}…")
                }

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

                ImportUiState.Stage.MALFORMED -> MalformedContent(
                    message = state.malformed.orEmpty(),
                    onRetry = viewModel::reset,
                )

                ImportUiState.Stage.DONE -> DoneContent(
                    message = state.doneMessage.orEmpty(),
                    onFinish = onBack,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit) {
    Text("导入未经验证的插件", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "插件包（.pagent）是一个 zip 文件，里面至少要有 plugin.json 和它引用的规则或脚本文件。\n\n" +
            "⚠️ 从本地导入的插件没有经过任何审核，来源也无法验证。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onPick) { Text("选择插件包") }
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
    Text(notice.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(notice.summary, style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(16.dp))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            notice.bullets.forEach { bullet ->
                Text(
                    bullet,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }

    if (notice.warnings.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Surface(
            color = RiskColors.medium.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("检查中发现的问题", fontWeight = FontWeight.SemiBold)
                notice.warnings.forEach {
                    Text("· ${it.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    if (notice.requireTypedConfirmation) {
        Text(
            "这个插件申请了高风险能力。请手动输入下面的六个字以继续：",
            style = MaterialTheme.typography.bodyMedium,
            color = RiskColors.high,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ImportRiskNotice.CONFIRMATION_PHRASE,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = onTypedChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("在此输入确认词") },
        )
        Spacer(Modifier.height(16.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onCancel, enabled = !installing) { Text("取消") }
        Button(
            onClick = onConfirm,
            enabled = canConfirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (notice.requireTypedConfirmation) {
                    RiskColors.high
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
        ) {
            if (installing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("我已知晓风险，仍要安装")
        }
    }
}

@Composable
private fun RejectedContent(errors: List<ValidationIssue>, onRetry: () -> Unit) {
    Text(
        "这个插件已被拒绝",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "这不是格式问题。它申请了本应用永不开放的能力 —— " +
            "这类能力涉及资金、密钥与系统权限，本体不提供。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    errors.forEach {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(it.field, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                Text(it.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "建议：换一个来源。一个会申请禁止能力的插件，即使删掉那几行，" +
            "它的作者也不值得你信任。",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text("重新选择文件") }
}

@Composable
private fun MalformedContent(message: String, onRetry: () -> Unit) {
    Text("无法导入这个文件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetry) { Text("重新选择文件") }
}

@Composable
private fun DoneContent(message: String, onFinish: () -> Unit) {
    Text("安装完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(20.dp))
    Button(onClick = onFinish) { Text("完成") }
}

@Composable
private fun CenterBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
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
