package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 图片与 PDF：`image_convert` / `pdf_pages` / `pdf_from_images`。
 *
 * 真干活的是 `:app` 的 Bitmap / PdfRenderer（Android 平台类，测不了），
 * 这里只管参数、上限与"说清楚"。三条边界是硬性的：
 * - 图片最大边长夹在 32–4096：再大手机内存直接 OOM，且没人需要那么大的图；
 * - PDF 一次最多转 20 页：整本转出来是几分钟的 CPU，用户会以为卡死；
 * - 页号从 1 开始（用户视角），内部减 1 —— 这个 off-by-one 必须只在一处换算。
 */
interface ImagePort {
    /** 把任意图片字节转成指定格式，必要时等比缩到 [maxWidth]。 */
    suspend fun convert(src: ByteArray, format: ImageFormat, maxWidth: Int, quality: Int): ByteArray
}

interface PdfPort {
    /** 把 PDF 的指定页（0 基）渲染成 PNG 字节。 */
    suspend fun renderPages(src: ByteArray, pages: List<Int>, scale: Int): List<ByteArray>

    /** 把多张图片合成一个 PDF。 */
    suspend fun buildPdf(images: List<ByteArray>): ByteArray
}

enum class ImageFormat(val wire: String, val ext: String, val mime: String) {
    PNG("png", "png", "image/png"),
    JPEG("jpeg", "jpg", "image/jpeg"),
    WEBP("webp", "webp", "image/webp");

    companion object {
        /**
         * 认扩展名与常见别名。
         *
         * ⚠️ `jpg` 不是 `jpeg` 的前缀匹配 —— 早期只比 `wire` 时，用户给 `a.jpg`
         *    会被判成"认不出"，于是退回源文件格式（png），文件名写着 .jpg 内容却是 PNG。
         */
        fun of(raw: String): ImageFormat? = when (raw.lowercase().trimStart('.')) {
            "png" -> PNG
            "jpg", "jpeg", "jpe" -> JPEG
            "webp" -> WEBP
            else -> null
        }
    }
}

class ImageConvertTool(
    private val files: WorkFilePort,
    private val images: ImagePort,
) : McpTool {

    override val name: String = "image_convert"

    override val description: String =
        "把图片转格式（png/jpeg/webp），可以顺便等比缩小。适合把大图压小再发出去或塞进文档。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("src") { put("type", "string"); put("description", "工作区里的图片路径") }
            putJsonObject("dst") { put("type", "string"); put("description", "存到哪里；不给就替换扩展名后存同目录") }
            putJsonObject("format") {
                put("type", "string")
                put("description", "png / jpeg / webp，默认按 dst 的扩展名，认不出就当 jpeg")
            }
            putJsonObject("maxWidth") {
                put("type", "number")
                put("description", "最大边长（像素），夹在 ${MIN_EDGE}–${MAX_EDGE}，默认 $DEFAULT_EDGE"
                )
            }
            putJsonObject("quality") {
                put("type", "number")
                put("description", "jpeg/webp 质量 1–100，默认 85；png 会忽略它"
                )
            }
        }
        putJsonArray("required") { add(JsonPrimitive("src")) }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val src = args["src"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (src.isEmpty()) return ToolOutcome.Failed("要给我原图路径。")
        val bytes = runCatching { files.read(src) }.getOrElse {
            return ToolOutcome.Failed("工作区里没有「$src」。")
        }
        if (bytes.isEmpty()) return ToolOutcome.Failed("「$src」是空文件，不像图片。")

        val dstRaw = args["dst"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        val formatHint = args["format"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        val format = ImageFormat.of(formatHint)
            ?: ImageFormat.of(extensionOf(dstRaw))
            ?: ImageFormat.of(extensionOf(src))
            ?: ImageFormat.JPEG
        val dst = if (dstRaw.isEmpty()) replaceExtension(src, format.ext) else ensureExtension(dstRaw, format.ext)

        val edge = clampNumber(args["maxWidth"], DEFAULT_EDGE, MIN_EDGE, MAX_EDGE)
        val quality = clampNumber(args["quality"], 85, 1, 100)

        val out = runCatching { images.convert(bytes, format, edge, quality) }.getOrElse { error ->
            return ToolOutcome.Failed("转换失败：${error.message ?: "这张图可能不是常见格式"}")
        }
        runCatching { files.write(dst, out) }.getOrElse {
            return ToolOutcome.Failed("写不进「$dst」。")
        }
        return ToolOutcome.Success(
            "转好了：$dst（${format.mime}，${out.size} 字节；原图 ${bytes.size} 字节，最长边上限 $edge）。",
        )
    }

    companion object {
        const val MIN_EDGE = 32
        const val MAX_EDGE = 4096
        const val DEFAULT_EDGE = 1600
    }
}

class PdfPagesTool(
    private val files: WorkFilePort,
    private val pdf: PdfPort,
) : McpTool {

    override val name: String = "pdf_pages"

    override val description: String =
        "把 PDF 的指定页转成 PNG 图片（一次最多 $MAX_PAGES 页），方便看内容或塞进幻灯片。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("src") { put("type", "string"); put("description", "工作区里的 PDF 路径") }
            putJsonObject("pages") {
                put("type", "array")
                put("items", buildJsonObject { put("type", "number") })
                put("description", "页号，从 1 开始；不给就转第 1 页"
                )
            }
            putJsonObject("outDir") {
                put("type", "string")
                put("description", "图片存到哪个目录，默认与 PDF 同目录下的「<文件名>-pages」"
                )
            }
            putJsonObject("scale") {
                put("type", "number")
                put("description", "清晰度倍数 1–3，默认 2"
                )
            }
        }
        putJsonArray("required") { add(JsonPrimitive("src")) }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val src = args["src"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (src.isEmpty()) return ToolOutcome.Failed("要给我 PDF 路径。")
        val bytes = runCatching { files.read(src) }.getOrElse {
            return ToolOutcome.Failed("工作区里没有「$src」。")
        }
        if (bytes.size < 5 || String(bytes, 0, 4, Charsets.US_ASCII) != "%PDF") {
            return ToolOutcome.Failed("「$src」不是 PDF（文件头不是 %PDF）。")
        }

        val requested = (args["pages"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.toDoubleOrNull()?.toInt() }
            .orEmpty()
        val pages = if (requested.isEmpty()) listOf(1) else requested
        if (pages.size > MAX_PAGES) return ToolOutcome.Failed("一次最多转 $MAX_PAGES 页，现在要 ${pages.size} 页。分批来。")
        if (pages.any { it < 1 }) return ToolOutcome.Failed("页号从 1 开始。")

        val scale = clampNumber(args["scale"], 2, 1, 3)
        val outDir = args["outDir"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
            .ifEmpty { replaceExtension(src, "pages") }

        val rendered = runCatching { pdf.renderPages(bytes, pages.map { it - 1 }, scale) }.getOrElse { error ->
            return ToolOutcome.Failed("渲染失败：${error.message ?: "这个 PDF 可能加密了或页号越界"}")
        }
        if (rendered.isEmpty()) return ToolOutcome.Failed("没有渲染出任何页，检查一下页号。")

        val written = ArrayList<String>()
        for ((index, png) in rendered.withIndex()) {
            val page = pages.getOrElse(index) { index + 1 }
            val relative = "$outDir/page-$page.png"
            runCatching { files.write(relative, png) }.onSuccess { written += relative }
        }
        if (written.isEmpty()) return ToolOutcome.Failed("一页都没写出去，检查目录权限。")
        return ToolOutcome.Success("转好了 ${written.size} 页：\n" + written.joinToString("\n") { "· $it" })
    }

    companion object {
        const val MAX_PAGES = 20
    }
}

class PdfFromImagesTool(
    private val files: WorkFilePort,
    private val pdf: PdfPort,
) : McpTool {

    override val name: String = "pdf_from_images"

    override val description: String =
        "把工作区里的几张图片合成一个 PDF（每张一页）。适合把截图或照片整理成一份文件。一次最多 $MAX_IMAGES 张。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("images") {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "图片路径，按顺序每张一页"
                )
            }
            putJsonObject("dst") { put("type", "string"); put("description", "存成哪个 PDF") }
        }
        putJsonArray("required") {
            add(JsonPrimitive("images"))
            add(JsonPrimitive("dst"))
        }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val dst = args["dst"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (dst.isEmpty()) return ToolOutcome.Failed("要给我 PDF 存哪里。")
        val paths = (args["images"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.trim() }
            ?.filter { it.isNotEmpty() }.orEmpty()
        if (paths.isEmpty()) return ToolOutcome.Failed("要给我至少一张图片的路径。")
        if (paths.size > MAX_IMAGES) return ToolOutcome.Failed("一次最多 $MAX_IMAGES 张，现在 ${paths.size} 张。")

        val images = ArrayList<ByteArray>()
        for (p in paths) {
            val bytes = runCatching { files.read(p) }.getOrElse {
                return ToolOutcome.Failed("工作区里没有「$p」。")
            }
            if (bytes.isEmpty()) return ToolOutcome.Failed("「$p」是空文件。")
            images += bytes
        }
        val out = runCatching { pdf.buildPdf(images) }.getOrElse { error ->
            return ToolOutcome.Failed("合成失败：${error.message ?: "有图片格式不被支持"}")
        }
        val target = ensureExtension(dst, "pdf")
        runCatching { files.write(target, out) }.getOrElse {
            return ToolOutcome.Failed("写不进「$target」。")
        }
        return ToolOutcome.Success("合成好了：$target（${images.size} 页，${out.size} 字节）。")
    }

    companion object {
        const val MAX_IMAGES = 20
    }
}

// ── 小工具 ──────────────────────────────────────────────────

internal fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase()

internal fun replaceExtension(path: String, ext: String): String {
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    return if (dot > slash) "${path.substring(0, dot)}.$ext" else "$path.$ext"
}

internal fun ensureExtension(path: String, ext: String): String =
    if (extensionOf(path) == ext) path else replaceExtension(path, ext)

internal fun clampNumber(element: kotlinx.serialization.json.JsonElement?, fallback: Int, lo: Int, hi: Int): Int {
    val raw = (element as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.toDoubleOrNull() ?: return fallback
    if (!raw.isFinite()) return fallback
    return raw.toInt().coerceIn(lo, hi)
}
