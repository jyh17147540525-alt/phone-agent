package com.pocketagent.filelogic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 压缩包这一档的**纯逻辑**：打包、列表、解包。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么解包要单独写一层，而不是直接 `unzip`
 * ═══════════════════════════════════════════════════════════════
 *
 * 压缩包里可以写 `../../databases/app.db`、可以写绝对路径、可以是
 * "一万个文件的 4GB 炸弹"。模型只是照着用户一句话去解一个包，
 * **它不会先看一眼里面有什么**。所以：
 *  · 入口名先过 [ZipSafety]（穿越、绝对路径、Windows 盘符、控制字符）；
 *  · 解出来的东西有**条数/单文件/总量**三道上限（[ArchiveLimits]）；
 *  · 越界的条目不抛异常，进 `skipped` 让人看见，而不是静默丢掉。
 *
 * ⚠️ 打包时间戳固定成 1980-01-01（和 [OoxmlPackage] 同一个理由）：
 *    同样的输入必须给出**同样的字节**，否则离线测试没法比对，
 *    "改了一行没变" 也说不清。
 */

/** 包里的一个条目。`size` 是解压后大小（压缩包里自带的值，可能不准）。 */
data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
)

/** 三道上限。默认值按"手机上一次对话能承受的量"定。 */
data class ArchiveLimits(
    val maxFiles: Int = 200,
    val maxTotalBytes: Long = 64L * 1024 * 1024,
    val maxFileBytes: Long = 16L * 1024 * 1024,
) {
    fun explain(skipped: Int, totalBytes: Long): String {
        if (skipped == 0 && totalBytes <= maxTotalBytes) return ""
        return "有 $skipped 个条目没解（上限：$maxFiles 个、单个 ${mb(maxFileBytes)}、总共 ${mb(maxTotalBytes)}）"
    }

    private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)}MB"
}

/** 解包结果。`skipped` 是"看到了但没解"的原因，按条目记。 */
data class ArchiveExtract(
    val files: Map<String, ByteArray>,
    val totalBytes: Long,
    val skipped: List<String>,
) {
    fun summary(): String {
        if (files.isEmpty()) {
            return if (skipped.isEmpty()) "这个压缩包是空的。" else "一个都没解出来：" + skipped.take(5).joinToString("；")
        }
        val head = files.entries.take(8).joinToString("\n") { (name, bytes) -> "· $name（${bytes.size} 字节）" }
        val more = if (files.size > 8) "\n…另有 ${files.size - 8} 个" else ""
        val skip = if (skipped.isEmpty()) "" else "\n跳过 ${skipped.size} 个：" + skipped.take(3).joinToString("；")
        return "解出 ${files.size} 个文件，共 $totalBytes 字节：\n$head$more$skip"
    }
}

object ZipSafety {

    /** 太长的名字直接拒：某些文件系统会在 255 字节处截断，截断后可能变成另一个路径。 */
    const val MAX_NAME_LENGTH = 240

    /**
     * 入口名过关。**通过时返回规范化后的相对路径**，不通过返回 null。
     *
     * 拒的东西：绝对路径、`..` 段、Windows 盘符、反斜杠、控制字符、
     * 空名、超长名。`a//b`、`./a` 这种只是不规整，规范化后放行。
     */
    fun normalize(name: String): String? {
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        if (name.any { it.code < 0x20 || it == '\u007f' }) return null
        if (name.contains('\\')) return null
        if (name.startsWith("/")) return null
        if (name.length >= 2 && name[1] == ':') return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * 解包落盘前再判一次：不能越过目标目录。
     *
     * ⚠️ 先 [normalize] 再比前缀 —— 只比前缀的话 `工作区/x/../../evil` 会通过，
     *    因为它字面上确实以 `工作区/` 开头。这类 bug 在真机上表现是"莫名其妙
     *    多出一个文件"，而不是报错。
     */
    fun isInside(baseDir: String, name: String): Boolean {
        val base = baseDir.trimEnd('/').trimStart('/')
        val clean = normalize(name) ?: return false
        return clean == base || clean.startsWith("$base/")
    }
}

object ZipWriter {

    /** 固定时间戳，保证同输入同字节。 */
    private const val FIXED_TIME_MS = 315_532_800_000L // 1980-01-01T00:00:00Z

    /**
     * 打一个 zip。
     *
     * @param entries 相对路径 → 内容；名字会先过 [ZipSafety.normalize]，不合规的**抛异常**
     *                （这里是"我们自己要打包"，名字错了是调用方的 bug，不该静默改名）。
     */
    fun create(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((rawName, bytes) in entries) {
                val name = ZipSafety.normalize(rawName)
                    ?: throw IllegalArgumentException("压缩包里的路径不合规：$rawName")
                val entry = ZipEntry(name).apply { time = FIXED_TIME_MS }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

object ZipReader {

    /** 只列表，不解压——先让模型看清里面有什么。 */
    fun list(bytes: ByteArray, limit: Int = 200): List<ArchiveEntry> {
        val result = ArrayList<ArchiveEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (result.size < limit) {
                val entry = zip.nextEntry ?: break
                result += ArchiveEntry(
                    name = ZipSafety.normalize(entry.name) ?: entry.name,
                    size = entry.size.coerceAtLeast(0L),
                    isDirectory = entry.isDirectory,
                )
                zip.closeEntry()
            }
        }
        return result
    }

    /**
     * 解压到内存（落盘由调用方做）。
     *
     * ⚠️ 一边读一边数：`entry.size` 是压缩包**自己声明**的值，可以撒谎，
     *    所以真正的判据是**实际读出来的字节数**。
     */
    fun extract(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits()): ArchiveExtract {
        val files = LinkedHashMap<String, ByteArray>()
        val skipped = ArrayList<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = ZipSafety.normalize(entry.name)
                if (name == null) {
                    skipped += "${entry.name}（路径不合规）"
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (files.size >= limits.maxFiles) {
                    skipped += "$name（超过 ${limits.maxFiles} 个）"
                    zip.closeEntry()
                    continue
                }
                val chunk = zip.readNBytes(limits.maxFileBytes.toInt() + 1)
                if (chunk.size > limits.maxFileBytes) {
                    skipped += "$name（单个超过 ${limits.maxFileBytes / (1024 * 1024)}MB）"
                    zip.closeEntry()
                    continue
                }
                if (total + chunk.size > limits.maxTotalBytes) {
                    skipped += "$name（总量超过 ${limits.maxTotalBytes / (1024 * 1024)}MB）"
                    zip.closeEntry()
                    continue
                }
                total += chunk.size
                files[name] = chunk
                zip.closeEntry()
            }
        }
        return ArchiveExtract(files = files, totalBytes = total, skipped = skipped)
    }
}
