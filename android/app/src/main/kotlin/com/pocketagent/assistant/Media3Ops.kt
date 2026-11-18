package com.pocketagent.assistant

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.pocketagent.mcp.MediaAvailability
import com.pocketagent.mcp.MediaOpsPort
import com.pocketagent.mcp.MediaResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import timber.log.Timber

/**
 * 媒体后端的真实现：Media3 Transformer + `MediaMetadataRetriever`。
 *
 * ## 为什么不是 ffmpeg
 *
 * ffmpeg-kit 已停止维护、Android 二进制全部下架，拿它当主路径等于把功能搭在一个
 * 不再发版的东西上。Media3 能做的事更少，但它跟着 AndroidX 走。
 *
 * ## 能力边界（必须对用户说清）
 *
 * 能：抽音轨（m4a）、转成 mp4/webm、截一帧、看时长与分辨率。
 * 不能：任意滤镜、任意编码参数、字幕、拼接 —— 这些是 ffmpeg 的领域，我们**不假装**支持。
 *
 * ## 路径
 *
 * 工具层只认 `/work/xxx`；这里把它映射到应用私有目录下的真实文件
 * （与 `BuiltinLinuxOps` 用同一个 `linux/work`）。**不接受**任何 `/work` 之外的路径 ——
 * 工具层已经挡过一次，这里再挡一次：两条入口（将来还会多）不该各自决定边界。
 */
class Media3Ops(
    private val context: Context,
    private val workDir: File,
    /** 与内置引擎共用一个开关：两者都是"本机算力"，用户关掉就该都停。 */
    private val enabled: () -> Boolean,
) : MediaOpsPort {

    override suspend fun availability(): MediaAvailability {
        if (!enabled()) {
            return MediaAvailability(
                ready = false,
                explain = "媒体处理跟着「内置 Linux 引擎」的开关走，现在是关着的。" +
                    "去 设置 → Linux 工作区 → 内置 Linux 引擎 打开它。",
            )
        }
        return MediaAvailability(true, "内置媒体引擎可用：抽音轨、转码、截帧，输出写在 /work。")
    }

    override suspend fun probe(input: String): MediaResult = withContext(Dispatchers.IO) {
        val file = resolve(input) ?: return@withContext bad(input)
        if (!file.isFile) return@withContext MediaResult(false, "文件不存在：$input")

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val info = buildMap {
                retriever.durationSeconds()?.let { put("durationMs", it.toString()) }
                retriever.width()?.let { put("width", it.toString()) }
                retriever.height()?.let { put("height", it.toString()) }
                put("hasAudio", (retriever.hasAudio() == true).toString())
                put("hasVideo", (retriever.hasVideo() == true).toString())
                retriever.mime()?.let { put("mime", it) }
            }
            MediaResult(true, "「$input」：${describe(info)}", info)
        } catch (e: Exception) {
            Timber.w(e, "媒体 probe 失败：%s", input)
            MediaResult(false, "读不出这个文件的媒体信息（${e.javaClass.simpleName}）。它可能不是音视频，或者已损坏。")
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun extractAudio(input: String, output: String): MediaResult =
        transform(input, output, removeVideo = true, keepAudio = true)

    override suspend fun transcode(input: String, output: String): MediaResult =
        transform(input, output, removeVideo = false, keepAudio = true)

    override suspend fun snapshot(input: String, atMs: Long, output: String): MediaResult =
        withContext(Dispatchers.IO) {
            val source = resolve(input) ?: return@withContext bad(input)
            val target = resolve(output) ?: return@withContext bad(output)
            if (!source.isFile) return@withContext MediaResult(false, "文件不存在：$input")

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val frame = retriever.getFrameAtTime(
                    atMs * 1_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@withContext MediaResult(false, "这个时间点取不到画面（可能是纯音频文件）。")

                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out)
                }
                frame.recycle()
                MediaResult(true, "已截帧到「$output」（${target.length() / 1024} KB）")
            } catch (e: Exception) {
                Timber.w(e, "媒体截帧失败：%s", input)
                MediaResult(false, "截帧失败（${e.javaClass.simpleName}）。")
            } finally {
                runCatching { retriever.release() }
            }
        }

    /**
     * 跑一次 Transformer。
     *
     * ⚠️ Transformer 必须在**有 Looper 的线程**上启动，所以这里切到 Main；
     *    等待结果用 [CompletableDeferred]，不在主线程上阻塞。
     */
    private suspend fun transform(
        input: String,
        output: String,
        removeVideo: Boolean,
        keepAudio: Boolean,
    ): MediaResult = withContext(Dispatchers.Main) {
        val source = resolve(input) ?: return@withContext bad(input)
        val target = resolve(output) ?: return@withContext bad(output)
        if (!source.isFile) return@withContext MediaResult(false, "文件不存在：$input")

        // Transformer 拒绝覆盖已存在的文件；先删掉，语义上是"重新生成"
        if (target.exists() && !target.delete()) {
            return@withContext MediaResult(false, "输出文件已存在且删不掉：$output")
        }
        target.parentFile?.mkdirs()

        val done = CompletableDeferred<Pair<Boolean, String>>()
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                done.complete(true to "已生成「$output」（${target.length() / 1024} KB）")
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exception: ExportException) {
                done.complete(false to "转码失败（${exception.javaClass.simpleName}）：${exception.message ?: "没有更多信息"}")
            }
        }

        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setRemoveVideo(removeVideo)
            .setRemoveAudio(!keepAudio)
            .build()

        val transformer = Transformer.Builder(context).addListener(listener).build()
        runCatching { transformer.start(item, target.absolutePath) }
            .onFailure { return@withContext MediaResult(false, "转码没能开始（${it.javaClass.simpleName}）：${it.message ?: ""}") }

        val finished = withTimeoutOrNull(TRANSFORM_TIMEOUT_MS) { done.await() }
            ?: run {
                runCatching { transformer.cancel() }
                return@withContext MediaResult(false, "转码超过 ${TRANSFORM_TIMEOUT_MS / 1000} 秒没有完成，已取消。")
            }

        if (finished.first) MediaResult(true, finished.second) else MediaResult(false, finished.second)
    }

    /** `/work/x` → 真实文件；越界一律 null（工具层已挡，这里再挡一次）。 */
    private fun resolve(containerPath: String): File? {
        if (!containerPath.startsWith("/work/")) return null
        val relative = containerPath.removePrefix("/work/")
        if (relative.isBlank() || relative.split('/').any { it == ".." }) return null
        val file = File(workDir, relative)
        return if (file.canonicalPath.startsWith(workDir.canonicalPath)) file else null
    }

    private fun bad(path: String): MediaResult =
        MediaResult(false, "路径不被接受：$path（只能是 /work 下的文件）")

    private fun describe(info: Map<String, String>): String = buildString {
        info["durationMs"]?.toLongOrNull()?.let { append("时长 ").append(it / 1000.0).append("s，") }
        if (info["width"] != null && info["height"] != null) {
            append("画面 ").append(info["width"]).append("x").append(info["height"]).append("，")
        }
        append(if (info["hasAudio"] == "true") "有音轨" else "没有音轨")
        append(if (info["hasVideo"] == "true") "、有画面" else "、没有画面")
    }

    private companion object {
        const val TRANSFORM_TIMEOUT_MS = 120_000L
    }
}

private fun MediaMetadataRetriever.durationSeconds(): Long? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

private fun MediaMetadataRetriever.width(): Int? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()

private fun MediaMetadataRetriever.height(): Int? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()

private fun MediaMetadataRetriever.hasAudio(): Boolean? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { it == "yes" }

private fun MediaMetadataRetriever.hasVideo(): Boolean? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)?.let { it == "yes" }

private fun MediaMetadataRetriever.mime(): String? =
    extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
