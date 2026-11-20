package com.pocketagent.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketagent.filelogic.OfficeInspect
import com.pocketagent.filelogic.ReplyFileKind
import com.pocketagent.filelogic.ReplyFiles
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import java.io.File

/**
 * **在应用里把文件打开看一眼** —— 不跳转别的软件。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "可以文件输出，用户点击后能打开这个文件，最好能做到不需要跳转其他软件"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 各格式的诚实边界
 * ═══════════════════════════════════════════════════════════════
 *
 * | 格式 | 应用内能做的 | 做不到的 |
 * |---|---|---|
 * | 图片 | 真的显示 | —— |
 * | PDF | 逐页渲染成图（前 5 页） | 文字选择、批注 |
 * | 文本/CSV/JSON | 原样显示 | 语法高亮 |
 * | docx/xlsx/pptx | **读出文字** | 排版、表格线、图片 |
 *
 * 表格里最后一行尤其重要：不做"假渲染"，因为一张排错了的文档比一句
 * "这是文字预览"更容易让人误判"文件坏了"。
 */
@Composable
fun FilePreviewDialog(
    path: String,
    kind: ReplyFileKind,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(path) { resolveFile(context.filesDir, path) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PaColor.Canvas)
                .padding(PaSpace.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = path.substringAfterLast('/'),
                        style = PaType.title,
                        color = PaColor.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = if (file == null) "找不到这个文件" else sizeText(file.length()),
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }
                Text(
                    text = "关闭",
                    style = PaType.body,
                    color = PaColor.Accent,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
                )
            }
            Spacer(Modifier.padding(top = PaSpace.s))

            when {
                file == null -> CenterNote("文件不在这里了。可能被移动或删除，也可能在工作区的另一份副本里。")
                kind == ReplyFileKind.IMAGE -> ImagePreview(file)
                kind == ReplyFileKind.PDF -> PdfPreview(file)
                kind == ReplyFileKind.TEXT -> TextPreview(runCatching { file.readText() }.getOrDefault(""))
                kind == ReplyFileKind.OFFICE -> OfficePreview(file, path.substringAfterLast('.', ""))
                else -> CenterNote(ReplyFiles.unsupportedNote(kind))
            }
        }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(PaSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = PaType.body, color = PaColor.TextSecondary)
    }
}

@Composable
private fun ImagePreview(file: File) {
    val bitmap = remember(file.absolutePath) { decodeScaled(file, maxEdge = 2048) }
    if (bitmap == null) {
        CenterNote("这张图打不开（可能不是图片，或已经损坏）。")
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = file.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun PdfPreview(file: File) {
    val pages = remember(file.absolutePath) { renderPdf(file, maxPages = 5) }
    if (pages.isEmpty()) {
        CenterNote("这个 PDF 渲染不出来（可能加密了，或者已经损坏）。")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(PaSpace.s)) {
        items(pages.size) { index ->
            Image(
                bitmap = pages[index].asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun TextPreview(text: String) {
    if (text.isBlank()) {
        CenterNote("这个文件是空的。")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text.take(200_000),
            style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
            color = PaColor.TextPrimary,
        )
    }
}

@Composable
private fun OfficePreview(file: File, extension: String) {
    val result = remember(file.absolutePath, extension) {
        runCatching { OfficeInspect.inspect(file.readBytes(), extension) }.getOrNull()
    }
    if (result == null || result.text.isBlank()) {
        CenterNote("这个文档里没读出文字。可以用别的软件打开看排版。")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "文字预览（排版不还原）",
            style = PaType.caption,
            color = PaColor.TextTertiary,
            modifier = Modifier.padding(bottom = PaSpace.xs),
        )
        Text(
            text = result.text,
            style = PaType.body,
            color = PaColor.TextPrimary,
        )
    }
}

/**
 * 在几个可能的根目录里找这个文件。
 *
 * ⚠️ 必须找**多处**：同一个文件可能只在内置工作区（`filesDir/linux/work`），
 *   也可能已经被同步到 SD 卡的工作区里。只认一处会出现"卡片在、点开说没有"。
 */
private fun resolveFile(filesDir: File, path: String): File? {
    val candidates = buildList {
        add(File(filesDir, "linux/work/$path"))
        add(File(filesDir, path))
        add(File("/sdcard/PocketAgentTest/$path"))
        add(File("/storage/emulated/0/PocketAgentTest/$path"))
        add(File(path))
    }
    return candidates.firstOrNull { it.isFile && it.length() > 0 }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes 字节"
}

private fun decodeScaled(file: File, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.absolutePath, opts)
}.getOrNull()

private fun renderPdf(file: File, maxPages: Int): List<Bitmap> = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            val out = ArrayList<Bitmap>()
            val count = minOf(renderer.pageCount, maxPages)
            for (index in 0 until count) {
                renderer.openPage(index).use { page ->
                    val scale = 1600f / page.width
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // PDF 背景是透明的，不铺白底会变成"黑纸白字"
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bitmap)
                }
            }
            out
        }
    }
}.getOrDefault(emptyList())
