package com.pocketagent.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.pocketagent.agentui.Attachments
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * **给模型装眼睛** —— 把用户选的图读成可发送的字节。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这个类修的是一个真实 bug（用户原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * "虽然在 app 里能支持图片输入，但是模型还是看不了图片，
 *  因为你没有给模型装上眼睛。"
 *
 * 根因链（真机可复现）：
 *   ① `TasksScreen` 选图 → `vm.attach(uri, "图片")` → 存进 `attachment`
 *   ② `submit()` 把附件拼成**一段文字**塞进 prompt：
 *      `（我附了一个图片：content://... —— 这是路径，不是内容）`
 *   ③ 模型收到一串 URI 字符串，它**当然**看不了
 *
 * 而 provider 层**早就能发图片**（`ContentPart.Image` + `image_url`
 * + data URI 全都实现了，见 `OpenAiCompatProvider` 第 461 行）。
 * 缺的只是"把 URI 读成字节"这一步 —— 就是本类。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么必须压缩，不能不压就发
 * ═══════════════════════════════════════════════════════════════
 *
 * 手机拍的一张照片 4~8MB。base64 之后膨胀 33% ⇒ 5~11MB **塞进一次
 * JSON 请求体**。后果有三个，都是用户能直接感觉到的：
 *
 *   · 多数厂商对请求体有硬上限（常见 10~20MB），超了直接 413
 *   · 就算没超，上传要几十秒 —— 用户以为卡死
 *   · 按 token 计费时图片通常按"分辨率档位"算，原图不省一分钱
 *
 * ⇒ 长边压到 1568px（多数模型的有效上限，再大它也会缩小再看）
 *   + JPEG 85 质量（肉眼无差，体积减半）。
 *
 * ⚠️ 但**有损压缩会毁掉文字边缘** —— 而用户发图很多时候就是
 *   "帮我看看这段截图里写了什么"。所以对**小图不重编码**：
 *   小图原样发（省一次有损），大图才压。
 */
class ImagePrep(private val context: Context) {

    /**
     * 读一个 URI，产出可发送的图片。
     *
     * @return [Result] —— 失败时带一句**给用户看**的原因
     *
     * ★ 为什么不用异常：这不是编程错误，是正常的用户输入问题
     *   （选了个 PDF、选了个损坏的文件）。用 sealed 结果让调用方
     *   必须处理，而不是靠 catch 一个 RuntimeException。
     */
    sealed interface Result {
        data class Ok(val image: Attachments.PreparedImage, val note: String?) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 处理一张用户选的图。
     *
     * @param uri 来自 `ActivityResultContracts.GetContent`
     */
    fun prepare(uri: Uri): Result {
        // ① 先问系统这是什么 —— 不能看扩展名（content:// 不可信）
        val mime = runCatching {
            context.contentResolver.getType(uri)
        }.getOrNull()

        val kind = Attachments.fromMime(mime)
        if (kind != Attachments.Kind.IMAGE) {
            return Result.Failed(
                "这个附件不是图片（${mime ?: "未知类型"}），只能把路径告诉模型",
            )
        }

        // ② 读字节。⚠️ 必须在 IO 线程调用本方法 —— 见类注释末尾。
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

        if (raw == null || raw.isEmpty()) {
            return Result.Failed("读不到这张图（可能已被删除，或没有读取权限）")
        }

        // ③ 解码。⚠️ 用 `inJustDecodeBounds` 先量尺寸，避免把
        //    一张 8000×6000 的原图整个读进内存（那是 192MB，直接 OOM）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight

        if (srcW <= 0 || srcH <= 0) {
            return Result.Failed("这张图无法解析（可能已损坏）")
        }

        val longEdge = maxOf(srcW, srcH)

        // ④ 小图且格式已支持 ⇒ 原样发。
        //    ⚠️ 不做"顺手转 JPEG"：每一次有损编码都会让文字边缘糊一点，
        //       而用户发图常常就是要我们看清字。
        if (longEdge <= Attachments.MAX_EDGE_PX &&
            raw.size <= Attachments.MAX_IMAGE_BYTES &&
            Attachments.isSupportedImageMime(mime)
        ) {
            return Result.Ok(
                Attachments.PreparedImage(raw, mime!!),
                note = null,
            )
        }

        // ⑤ 大图 / 格式不支持 ⇒ 解码 + 缩放 + 重编码为 JPEG
        val decoded = runCatching {
            // ⚠️ `inSampleSize` 是"降采样倍数"，用它先把巨图降到
            //    接近目标尺寸，再做精确缩放 —— 比直接 `createScaledBitmap`
            //    省内存（后者要先持有完整原图）。
            val sample = calcSampleSize(srcW, srcH, Attachments.MAX_EDGE_PX)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        }.getOrNull()

        if (decoded == null) {
            return Result.Failed("这张图无法解码（可能已损坏或不支持的格式）")
        }

        val scaled = runCatching { scaleToMaxEdge(decoded, Attachments.MAX_EDGE_PX) }
            .getOrNull() ?: decoded

        val out = ByteArrayOutputStream()
        val ok = runCatching {
            scaled.compress(Bitmap.CompressFormat.JPEG, Attachments.JPEG_QUALITY, out)
        }.getOrDefault(false)

        // ⚠️ 及时回收：一张 1568px 的 Bitmap 约 10MB，不回收会在
        //    连续发几张图后 OOM。
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        if (!ok) {
            return Result.Failed("图片压缩失败")
        }

        val bytes = out.toByteArray()
        if (bytes.size > Attachments.MAX_IMAGE_BYTES) {
            // ⚠️ 压缩后仍超限只可能是极端情况（纯噪点图）。
            //    这时**如实说**，不要偷偷降质量 —— 降了用户也不知道。
            return Result.Failed(
                "这张图压到 ${bytes.size / 1024}KB 仍然太大，换一张或先裁剪",
            )
        }

        val note = when {
            !Attachments.isSupportedImageMime(mime) ->
                "已把 ${mime ?: "原格式"} 转成 JPEG（多数模型不支持原格式）"
            longEdge > Attachments.MAX_EDGE_PX ->
                "已把 ${srcW}×${srcH} 缩到长边 ${Attachments.MAX_EDGE_PX}（模型看不出一半以上的像素差别）"
            else -> null
        }

        Timber.i(
            "图片准备完成：%d×%d → %dKB（%s）",
            srcW, srcH, bytes.size / 1024, note ?: "原样",
        )
        return Result.Ok(Attachments.PreparedImage(bytes, "image/jpeg"), note)
    }

    /** 按长边缩放到不超过 [maxEdge]。 */
    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longEdge = maxOf(w, h)
        if (longEdge <= maxEdge) return src

        val ratio = maxEdge.toFloat() / longEdge
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    companion object {
        /**
         * 算降采样倍数。
         *
         * ⚠️ `inSampleSize` 只能是 2 的幂（Android 会向下取整到最近的 2^n），
         *    传 3 会被当成 2。所以这里自己算成 2 的幂，避免"以为降了 3 倍
         *    实际只降了 2 倍"导致内存估算错。
         */
        fun calcSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
            if (maxEdge <= 0) return 1
            var sample = 1
            var w = srcW
            var h = srcH
            while (maxOf(w, h) / 2 >= maxEdge) {
                w /= 2
                h /= 2
                sample *= 2
            }
            return sample
        }
    }
}
