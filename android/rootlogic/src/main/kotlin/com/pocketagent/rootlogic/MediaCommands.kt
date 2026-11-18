package com.pocketagent.rootlogic

/**
 * 媒体动作的参数与路径规则 —— 纯逻辑，不碰 Media3、也不碰文件。
 *
 * ## 为什么单独一层
 *
 * 三个动作（抽音轨 / 转码 / 截帧）在真机上走的是完全不同的 API，但它们**共用一套路径规则**：
 * 输入必须已经在 `/work` 里（那是唯一可写目录），输出也只能落在 `/work`。
 * 这套规则写两遍就会漂移，而漂移的表现是"能读不能写"或"写到了别处"。
 *
 * ## 为什么不直接照 ffmpeg 的参数来
 *
 * ffmpeg-kit 已停维（Android 二进制全部下架）。现在用 Media3 Transformer + `MediaMetadataRetriever`，
 * 它们没有"任意滤镜/任意编码器"的能力，所以这里**只暴露三件真能做到的事**，
 * 而不是做一个"ffmpeg 兼容层"—— 后者会让用户以为参数随便写都行。
 */
sealed interface MediaCommand {

    /** 抽出音轨（输出 m4a）。 */
    data class ExtractAudio(val input: String, val output: String) : MediaCommand

    /** 转码（容器/编码统一交给 Media3 决定，不暴露参数）。 */
    data class Transcode(val input: String, val output: String) : MediaCommand

    /** 截一帧（毫秒）。 */
    data class Snapshot(val input: String, val atMs: Long, val output: String) : MediaCommand

    /** 只看信息（时长/分辨率/音轨数）。 */
    data class Probe(val input: String) : MediaCommand
}

/** 参数被拒。**换写法不会成功**，除非改掉被拒的那一点。 */
class MediaRejected(val what: String, val value: String) :
    IllegalArgumentException("$what 不被接受：$value")

object MediaCommands {

    /** 容器内唯一可写目录（与 Linux 工作区同一个）。 */
    const val INNER_WORK = LinuxEnv.INNER_WORK_DIR

    /** 认得的输入扩展名。**刻意短**：不认得就说"不认得"，而不是让 Media3 在运行时抛一句看不懂的话。 */
    val INPUT_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4a", "mp3", "wav", "aac", "flac", "ogg", "3gp")

    val OUTPUT_EXTENSIONS = mapOf(
        "extract_audio" to setOf("m4a", "mp3", "aac"),
        "transcode" to setOf("mp4", "webm"),
        "snapshot" to setOf("png", "jpg", "jpeg"),
    )

    const val MIN_AT_MS = 0L
    const val MAX_AT_MS = 24L * 60 * 60 * 1000

    fun plan(action: String, input: String, output: String?, atMs: Long?): MediaCommand {
        val inputPath = checkedPath("输入", input, INNER_WORK)
        val extension = inputPath.substringAfterLast('.', "").lowercase()
        if (extension !in INPUT_EXTENSIONS) {
            throw MediaRejected("输入格式", "$extension（认得：${INPUT_EXTENSIONS.joinToString("/")}）")
        }

        return when (action) {
            "probe" -> MediaCommand.Probe(inputPath)

            "extract_audio" -> {
                val out = requireOutput(output, "extract_audio", inputPath, defaultExtension = "m4a")
                MediaCommand.ExtractAudio(inputPath, out)
            }

            "transcode" -> {
                val out = requireOutput(output, "transcode", inputPath, defaultExtension = "mp4")
                MediaCommand.Transcode(inputPath, out)
            }

            "snapshot" -> {
                val out = requireOutput(output, "snapshot", inputPath, defaultExtension = "png")
                val at = (atMs ?: 0L).coerceIn(MIN_AT_MS, MAX_AT_MS)
                MediaCommand.Snapshot(inputPath, at, out)
            }

            else -> throw MediaRejected("动作", "$action（只有 probe / extract_audio / transcode / snapshot）")
        }
    }

    /**
     * 输出路径：没给就**按动作生成一个**（放在输入旁边的同名文件）。
     *
     * ⚠️ 自动生成而不是"必须显式给"：模型经常只想着"把音轨提出来"，
     *   逼它编一个路径只会让它编错。生成规则要**确定性**，否则第二次跑会覆盖不了第一次。
     */
    fun outputFor(input: String, action: String): String {
        val extension = OUTPUT_EXTENSIONS[action]?.first() ?: "bin"
        val base = input.substringAfterLast('/').substringBeforeLast('.')
        return "$INNER_WORK/$base.$extension"
    }

    private fun requireOutput(output: String?, action: String, input: String, defaultExtension: String): String {
        val path = if (output.isNullOrBlank()) outputFor(input, action) else checkedPath("输出", output, INNER_WORK)
        val extension = path.substringAfterLast('.', "").lowercase()
        val allowed = OUTPUT_EXTENSIONS[action].orEmpty()
        if (extension !in allowed) {
            throw MediaRejected("输出格式", "$extension（$action 只能写：${allowed.joinToString("/")}）")
        }
        if (path == input) throw MediaRejected("输出", "$path（不能覆盖输入，先换个名字）")
        return path
    }

    /** 路径规则与 Linux 工作区一致：绝对路径、只许 [INNER_WORK]、禁 `..`。 */
    private fun checkedPath(what: String, path: String, root: String): String {
        if (path.isBlank()) throw MediaRejected(what, "空")
        if (path.length > 1_024) throw MediaRejected(what, "过长（${path.length} 字符）")
        if (path.contains('\u0000')) throw MediaRejected(what, "含 NUL")
        if (path.split('/').any { it == ".." }) throw MediaRejected(what, "$path（不许用 .. 跳目录）")
        if (!path.startsWith("$root/")) throw MediaRejected(what, "$path（只能在 $root 下）")
        return path
    }
}
