package com.pocketagent.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pocketagent.mcp.ImageFormat
import com.pocketagent.mcp.ImagePort
import com.pocketagent.mcp.PdfPort
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片转换：BitmapFactory 解码 → 等比缩放 → `Bitmap.compress`。
 *
 * 两个坑写在这里：
 * 1. **解码要限流**：手机拍的图动辄 4000×3000，直接 `decodeByteArray` 就能吃掉
 *    几十 MB。所以先 `inJustDecodeBounds` 读尺寸，算好 `inSampleSize` 再真解码。
 * 2. **PNG 不受 quality 影响**，别指望传 100 就更大更清楚；JPEG 传 100 反而会
 *    让文件比 85 大一倍而肉眼看不出差别。
 */
class AndroidImagePort : ImagePort {

    override suspend fun convert(
        src: ByteArray,
        format: ImageFormat,
        maxWidth: Int,
        quality: Int,
    ): ByteArray = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("解不出图片尺寸，可能不是常见图片格式")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxWidth)
        }
        val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, options)
            ?: throw IllegalArgumentException("解码失败")

        val scaled = if (decoded.width > maxWidth) {
            val ratio = maxWidth.toFloat() / decoded.width
            val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, maxWidth, h, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val target = if (format == ImageFormat.JPEG && scaled.hasAlpha()) {
            // JPEG 没有透明通道，带 alpha 的位图直接压会得到全黑块
            val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(scaled, 0f, 0f, null)
            }
            flattened
        } else {
            scaled
        }

        val compressFormat = when (format) {
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP
        }
        val out = ByteArrayOutputStream()
        if (!target.compress(compressFormat, quality.coerceIn(1, 100), out)) {
            throw IllegalStateException("编码失败（这个格式可能不被系统支持）")
        }
        if (target !== scaled) target.recycle()
        scaled.recycle()
        out.toByteArray()
    }

    /** 只缩不放大；采样率是 2 的幂（BitmapFactory 的硬性要求）。 */
    private fun sampleSizeFor(width: Int, height: Int, maxWidth: Int): Int {
        var sample = 1
        var w = width
        while (w / 2 >= maxWidth) {
            sample *= 2
            w /= 2
        }
        return sample
    }
}

/**
 * PDF：`PdfRenderer` 出图，`PdfDocument` 合图。
 *
 * `PdfRenderer` 只能从**文件**打开，所以先把字节落到缓存目录 —— 别忘了用完删掉，
 * 否则用户手机里会慢慢堆出一堆临时 PDF。
 */
class AndroidPdfPort(private val cacheDir: File) : PdfPort {

    override suspend fun renderPages(src: ByteArray, pages: List<Int>, scale: Int): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("pa-pdf-", ".pdf", cacheDir)
            try {
                temp.writeBytes(src)
                val result = ArrayList<ByteArray>()
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (index in pages) {
                            if (index < 0 || index >= renderer.pageCount) {
                                throw IllegalArgumentException("第 ${index + 1} 页不存在（这份 PDF 共 ${renderer.pageCount} 页）")
                            }
                            renderer.openPage(index).use { page ->
                                val w = (page.width * scale).coerceIn(1, 4_000)
                                val h = (page.height * scale).coerceIn(1, 4_000)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                bitmap.recycle()
                                result += out.toByteArray()
                            }
                        }
                    }
                }
                result
            } finally {
                temp.delete()
            }
        }

    override suspend fun buildPdf(images: List<ByteArray>): ByteArray = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            var pageNumber = 1
            for (bytes in images) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("有图片解不开（第 $pageNumber 张）")
                // 按 A4 竖版排版，等比塞进页内并居中
                val pageW = 595
                val pageH = 842
                val ratio = minOf(pageW.toFloat() / bitmap.width, pageH.toFloat() / bitmap.height)
                val drawW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val drawH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create()
                val page = document.startPage(info)
                val dst = android.graphics.Rect(
                    (pageW - drawW) / 2,
                    (pageH - drawH) / 2,
                    (pageW - drawW) / 2 + drawW,
                    (pageH - drawH) / 2 + drawH,
                )
                page.canvas.drawBitmap(bitmap, null, dst, null)
                document.finishPage(page)
                bitmap.recycle()
                pageNumber++
            }
            val out = ByteArrayOutputStream()
            document.writeTo(out)
            out.toByteArray()
        } finally {
            document.close()
        }
    }
}
