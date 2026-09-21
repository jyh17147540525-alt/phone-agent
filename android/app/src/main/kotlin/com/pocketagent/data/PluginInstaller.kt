package com.pocketagent.data

import android.content.Context
import com.pocketagent.plugin.api.MarketEntry
import com.pocketagent.plugin.api.PluginBundle
import com.pocketagent.plugin.api.PluginIntegrity
import com.pocketagent.plugin.api.PluginManifest
import com.pocketagent.plugin.api.PluginValidationResult
import com.pocketagent.plugin.api.PluginValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 插件安装器：下载 → 校验 → 解压 → 落盘。
 *
 * ═══════════════════════════════════════════════════════════════
 *  顺序即安全
 * ═══════════════════════════════════════════════════════════════
 *
 * 五步，每一步都在为下一步"减少不确定性"：
 *
 * ```
 *  ① 下载到内存        —— 不落盘。落盘再校验，中间有一段时间是"脏文件在盘上"
 *  ② 哈希校验          —— 必须在解压之前。解压一个未校验的包 = 直接执行攻击载荷
 *  ③ 安全解压到暂存区   —— zip slip / zip bomb 防护，见 PluginBundle
 *  ④ 清单校验          —— 复用 PluginImporter 那套规则
 *  ⑤ 原子替换到正式目录 —— 先写暂存、再改名。中途失败不会留下半个插件
 * ```
 *
 * 最容易被后来的人"优化"掉的是 ②→③ 的顺序：先解压再校验看起来更合理
 * （"解压出来才能看清单"），但那等于把攻击面提前了一整步。
 *
 * ⚠️ 安装**不等于启用**。落盘只是让插件出现在列表里；授权是另一件事，
 *    走 ImportRiskNotice 那套确认流程。别把两者合并。
 */
class PluginInstaller(
    private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {

    sealed interface Result {
        data class Installed(val manifest: PluginManifest, val dir: File) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun install(entry: MarketEntry): Result = withContext(Dispatchers.IO) {
        // ① 下载到内存
        val bundle = download(entry.downloadUrl)
            ?: return@withContext Result.Failed("下载失败，请检查网络后重试。")

        // ② 哈希校验。这一步不能挪到解压之后
        if (!PluginIntegrity.verify(bundle, entry.sha256)) {
            return@withContext Result.Failed(
                "文件内容与市场声明的哈希不一致，已丢弃。\n" +
                    "可能是下载途中被替换，也可能这个源本身不可信。"
            )
        }

        installVerifiedBundle(PendingBundle(bundle, entry.id))
    }

    /**
     * 安装一个来源不是市场的插件包（本地导入）。
     *
     * 与 [install] 的区别是**少了下载与哈希校验** —— 不是偷懒，而是本地文件
     * 没有"可信的声明哈希"可言。清单里那个 `sha256` 是作者自己写的，
     * 拿它自证等于让嫌疑人出示自己开的无罪证明。
     *
     * 所以本地导入的风险**不靠校验兜底，靠用户知情**：调用方必须先走
     * [com.pocketagent.plugin.api.ImportRiskNotice] 的确认流程，
     * 不要直接调这个方法。
     */
    suspend fun installFromBytes(bundle: ByteArray, idHint: String): Result =
        withContext(Dispatchers.IO) { installVerifiedBundle(PendingBundle(bundle, idHint)) }

    /** 已经过了来源环节、进入落盘阶段的插件包 */
    private class PendingBundle(val bytes: ByteArray, val id: String)

    private suspend fun installVerifiedBundle(entry: PendingBundle): Result = withContext(Dispatchers.IO) {
        // ③ 安全解压到暂存区
        val staging = File(stagingRoot(), sanitizeDirName(entry.id))
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return@withContext Result.Failed("无法创建插件暂存目录，请检查存储空间。")
        }

        val extractError = extractSafely(bundle, staging)
        if (extractError != null) {
            staging.deleteRecursively()
            return@withContext Result.Failed(extractError)
        }

        // ④ 清单校验
        val manifestFile = File(staging, PluginBundle.MANIFEST_NAME)
        if (!manifestFile.isFile) {
            staging.deleteRecursively()
            return@withContext Result.Failed(
                "插件包里没有找到 ${PluginBundle.MANIFEST_NAME}，这不是一个合法的插件包。"
            )
        }

        val manifest = try {
            json.decodeFromString(PluginManifest.serializer(), manifestFile.readText())
        } catch (e: Exception) {
            staging.deleteRecursively()
            return@withContext Result.Failed("插件清单无法解析：${e.message.orEmpty().take(200)}")
        }

        when (val v = PluginValidator.validate(manifest)) {
            is PluginValidationResult.Rejected -> {
                staging.deleteRecursively()
                return@withContext Result.Failed(
                    v.errors.joinToString("\n") { it.message }
                )
            }
            is PluginValidationResult.Accepted -> {
                // 警告不拦安装，但要记日志 —— 界面上由安装确认页展示
                v.warnings.forEach { Timber.i("插件 %s 校验警告[%s]：%s", manifest.id, it.field, it.message) }
            }
        }

        // ⑤ 原子替换：先把旧目录挪走，再把暂存改名过去
        val target = File(pluginRoot(), sanitizeDirName(entry.id))
        target.parentFile?.mkdirs()

        val backup = File(stagingRoot(), "${sanitizeDirName(entry.id)}.old")
        backup.deleteRecursively()
        if (target.exists() && !target.renameTo(backup)) {
            staging.deleteRecursively()
            return@withContext Result.Failed("无法替换已安装的旧版本，请先卸载它再试。")
        }

        if (!staging.renameTo(target)) {
            // 回滚，别把用户已装好的插件弄丢
            backup.renameTo(target)
            staging.deleteRecursively()
            return@withContext Result.Failed("安装失败，已回滚到原版本。")
        }

        backup.deleteRecursively()
        Timber.i("插件已安装：%s @ %s", manifest.id, manifest.version)
        Result.Installed(manifest, target)
    }

    /** 已安装插件的目录名清单 */
    fun installedIds(): List<String> =
        pluginRoot().listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    /**
     * 从插件包里取出清单**原文**，不解压、不落盘。
     *
     * 存在的理由：安装确认页需要先展示"这个插件申请了哪些能力"，而清单在 zip 里。
     * 若为此先把整个包解压出来，就等于**在用户点确认之前，先把攻击载荷铺到了
     * 磁盘上** —— 顺序反了。
     *
     * 所以这里只读一个条目，而且：
     *  - 条目名仍走 [PluginBundle.isSafeEntryName] 检查
     *  - 读取有体积上限，防止用一个超大 `plugin.json` 把内存打爆
     *
     * 返回 null 表示包里没有可用的清单，调用方应报错而不是继续。
     */
    fun peekManifestJson(bundle: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                if (!PluginBundle.isSafeEntryName(entry.name)) return null

                if (!entry.isDirectory &&
                    entry.name.substringAfterLast('/') == PluginBundle.MANIFEST_NAME
                ) {
                    return zip.readCurrentEntryCapped(MAX_MANIFEST_BYTES)
                }

                zip.closeEntry()
            }
        }
        return null
    }

    /** 读取当前 zip 条目的内容，超过上限返回 null */
    private fun ZipInputStream.readCurrentEntryCapped(maxBytes: Int): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun installedDir(id: String): File = File(pluginRoot(), sanitizeDirName(id))

    fun uninstall(id: String): Boolean = installedDir(id).deleteRecursively()

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    private fun download(url: String): ByteArray? = try {
        if (!url.startsWith("https://", ignoreCase = true)) {
            // 插件包绝不能走明文下载 —— 明文通道上哈希校验毫无意义，
            // 攻击者可以同时改文件和清单里的哈希
            Timber.w("拒绝非 https 的插件下载地址：%s", url)
            null
        } else {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("插件下载失败 %d：%s", response.code, url)
                    null
                } else {
                    response.body?.bytes()
                }
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "插件下载异常：%s", url)
        null
    }

    /**
     * 安全解压。返回 null 表示成功，否则返回中止原因。
     *
     * 三层防护，缺一不可：
     *
     *  1. **条目名白名单检查**（[PluginBundle.isSafeEntryName]）——
     *     拦掉 `../`、绝对路径、盘符、反斜杠、NUL。
     *  2. **规范化路径复核** —— 字符串检查过完之后，把目标路径 `canonicalPath`
     *     再和根目录比一次。防的是"字符串看着干净、解析后却跑到外面"的情况。
     *  3. **边解压边算预算** —— 不在开头一次性检查。zip 头里声明的 size 是可以
     *     撒谎的，只有累计的真实字节数不会。所以检查放在读循环里。
     */
    private fun extractSafely(bundle: ByteArray, destDir: File): String? {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalPath + File.separator

        var entryCount = 0
        var uncompressed = 0L
        var compressed = 0L
        val buffer = ByteArray(8 * 1024)

        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break

                if (!PluginBundle.isSafeEntryName(entry.name)) {
                    return "插件包里含有不安全的路径「${entry.name}」，已中止。" +
                        "这通常意味着这个包是恶意构造的。"
                }

                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                entryCount++
                if (entry.compressedSize > 0) compressed += entry.compressedSize

                val target = File(destDir, entry.name)
                if (!target.canonicalPath.startsWith(destCanonical)) {
                    return "插件包条目「${entry.name}」解析后落在插件目录之外，已中止。"
                }

                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        uncompressed += read

                        // 边写边算：单个巨型条目也能被及时掐断
                        PluginBundle.exceedsBudget(entryCount, uncompressed, compressed)?.let {
                            return it
                        }
                        out.write(buffer, 0, read)
                    }
                }

                zip.closeEntry()
            }
        }

        return PluginBundle.exceedsBudget(entryCount, uncompressed, compressed)
    }

    /**
     * 目录名净化。
     *
     * 插件 ID 已经过 [PluginValidator] 的严格校验（只允许小写字母、数字、
     * 连字符和点），理论上不会出现危险字符。但**"理论上"不是安全边界** ——
     * 安装路径是从网络数据推导出来的，这里再兜一次底，成本近乎为零。
     */
    private fun sanitizeDirName(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(128)
            .ifBlank { "unknown" }

    private fun pluginRoot(): File = File(context.filesDir, "plugins")

    private fun stagingRoot(): File = File(context.cacheDir, "plugin-staging")

    private companion object {
        /** 清单原文的读取上限。正常清单不到 10 KB */
        const val MAX_MANIFEST_BYTES = 512 * 1024
    }
}
