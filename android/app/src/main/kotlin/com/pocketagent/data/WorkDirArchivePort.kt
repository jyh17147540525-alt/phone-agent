package com.pocketagent.data

import com.pocketagent.filelogic.ArchiveLimits
import com.pocketagent.filelogic.ZipSafety
import com.pocketagent.mcp.ArchivePort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 压缩包在**应用自己的工作区**里干活。
 *
 * 为什么不用授权目录（SAF）：那边一个文件要一次往返，递归几十个文件能慢到
 * 用户以为死机；而且解包需要"建目录 + 写很多文件"，SAF 的 `createDocument`
 * 一次只能建一个。这里直接用 `File`，速度是毫秒级。
 *
 * 安全边界在 [ZipSafety]：任何 `..`、绝对路径、盘符都过不去；这里再叠一层
 * `canonicalPath.startsWith(root)`，防止符号链接把路径带出去。
 */
class WorkDirArchivePort(
    private val root: File,
    private val limits: ArchiveLimits = ArchiveLimits(),
) : ArchivePort {

    override suspend fun read(relativePath: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolve(relativePath)
        if (!file.isFile) throw java.io.FileNotFoundException("工作区里没有 $relativePath")
        if (file.length() > MAX_READ_BYTES) {
            throw IllegalStateException("这个文件太大了（${file.length()} 字节），压缩包工具打不开超过 $MAX_READ_BYTES 字节的文件")
        }
        file.readBytes()
    }

    override suspend fun write(relativePath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        Unit
    }

    override suspend fun collect(relativePaths: List<String>): List<Pair<String, ByteArray>> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<String, ByteArray>>()
            var total = 0L
            for (raw in relativePaths) {
                val file = resolve(raw)
                if (!file.exists()) throw java.io.FileNotFoundException("工作区里没有 $raw")
                if (file.isFile) {
                    if (file.length() > limits.maxFileBytes) continue
                    if (out.size >= limits.maxFiles) break
                    if (total + file.length() > limits.maxTotalBytes) break
                    out += normaliseName(raw) to file.readBytes()
                    total += file.length()
                } else {
                    val base = file.absolutePath
                    val walk = file.walkTopDown().maxDepth(ZipSafety.MAX_NAME_LENGTH)
                        .filter { it.isFile }
                        .sortedBy { it.absolutePath }
                    for (child in walk) {
                        if (out.size >= limits.maxFiles) break
                        if (child.length() > limits.maxFileBytes) continue
                        if (total + child.length() > limits.maxTotalBytes) break
                        val name = child.absolutePath.removePrefix("$base/").replace('\\', '/')
                        out += normaliseName(name) to child.readBytes()
                        total += child.length()
                    }
                }
            }
            out
        }

    override suspend fun countUnder(relativeDir: String): Int = withContext(Dispatchers.IO) {
        val dir = resolve(relativeDir)
        if (!dir.isDirectory) 0 else dir.walkTopDown().count { it.isFile }
    }

    /** 相对路径 → 真文件。任何不合规或越界都抛异常，绝不"尽力而为"。 */
    private fun resolve(relativePath: String): File {
        val clean = ZipSafety.normalize(relativePath)
            ?: throw IllegalArgumentException("路径不合规：$relativePath")
        val file = File(root, clean)
        val rootPath = root.canonicalPath
        val target = file.canonicalPath
        if (target != rootPath && !target.startsWith("$rootPath/")) {
            throw IllegalArgumentException("路径越界：$relativePath")
        }
        return file
    }

    private fun normaliseName(raw: String): String =
        ZipSafety.normalize(raw) ?: raw.replace('\\', '/').trimStart('/')

    private companion object {
        /** 读压缩包本身的上限：比单文件上限宽，仍然有顶。 */
        const val MAX_READ_BYTES = 96L * 1024 * 1024
    }
}
