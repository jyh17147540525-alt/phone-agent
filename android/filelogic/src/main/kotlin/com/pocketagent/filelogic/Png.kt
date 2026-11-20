package com.pocketagent.filelogic

import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 最小 PNG 编码器（8 位 RGB，无依赖）。
 *
 * 为什么不引第三方库：项目是 GPL-3.0 且要求离线可测，图省事引一个绘图库
 * 会把 `:filelogic` 变成"只能上机测"的模块。这里只做**够用**的事：
 * 填矩形、画线、放数字，压出标准 PNG。
 *
 * 三条硬规则：
 * 1. 所有像素都走 [Canvas.px]，越界直接丢 —— 图表参数是模型给的，越界是常态。
 * 2. 每行第一个字节是 filter type（这里恒 0），忘了就是"图裂了"。
 * 3. chunk 的 CRC 按 **type+data** 算，不含长度字段。
 */
object Png {

    fun encode(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "尺寸必须为正" }
        require(rgb.size == width * height * 3) { "像素字节数与尺寸不符" }

        val raw = ByteArray(height * (1 + width * 3))
        var src = 0
        var dst = 0
        for (y in 0 until height) {
            raw[dst++] = 0 // filter: None
            System.arraycopy(rgb, src, raw, dst, width * 3)
            src += width * 3
            dst += width * 3
        }

        val out = java.io.ByteArrayOutputStream(raw.size / 3 + 128)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 2 // color type: truecolor
        ihdr[10] = 0 // deflate
        ihdr[11] = 0 // filter method
        ihdr[12] = 0 // no interlace
        writeChunk(out, "IHDR", ihdr)
        writeChunk(out, "IDAT", deflate(raw))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArray(raw.size / 2 + 1024)
            val acc = java.io.ByteArrayOutputStream(raw.size / 2 + 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                acc.write(buf, 0, n)
            }
            acc.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val c = ByteArray(4)
        putInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

/** 一块可以画东西的 RGB 画布。坐标越界一律丢弃，不抛异常。 */
class Canvas(val width: Int, val height: Int) {

    val rgb: ByteArray = ByteArray(width * height * 3)

    fun fill(color: Int) {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        var i = 0
        while (i < rgb.size) {
            rgb[i++] = r.toByte()
            rgb[i++] = g.toByte()
            rgb[i++] = b.toByte()
        }
    }

    fun px(x: Int, y: Int, color: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val i = (y * width + x) * 3
        rgb[i] = ((color ushr 16) and 0xFF).toByte()
        rgb[i + 1] = ((color ushr 8) and 0xFF).toByte()
        rgb[i + 2] = (color and 0xFF).toByte()
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        for (yy in y until y + h) for (xx in x until x + w) px(xx, yy, color)
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, thick: Int = 1) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (thick <= 1) px(x, y, color) else rect(x, y, thick, thick, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    fun encode(): ByteArray = Png.encode(width, height, rgb)
}

/**
 * 5×7 点阵数字与少量符号。
 *
 * 只做数字、小数点、负号和百分号：图表里真正需要"被读出来"的是刻度和数值，
 * 类别名放在工具返回的文字里（PNG 里塞中文要点阵字库，代价远超收益）。
 */
internal object TinyFont {

    private val GLYPHS: Map<Char, Array<String>> = mapOf(
        '0' to arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '1' to arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        '2' to arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '3' to arrayOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        '4' to arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        '5' to arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        '6' to arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        '7' to arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        '8' to arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        '9' to arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
        '.' to arrayOf("00000", "00000", "00000", "00000", "00000", "01100", "01100"),
        '-' to arrayOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
        '%' to arrayOf("11001", "11010", "00010", "00100", "01000", "01011", "10011"),
        '/' to arrayOf("00001", "00010", "00010", "00100", "01000", "01000", "10000"),
        ' ' to arrayOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
    )

    /** 这段文字里有几个字能画出来（用来给"标签放不下"打折扣）。 */
    fun supported(text: String): Boolean = text.all { it in GLYPHS }

    fun draw(canvas: Canvas, text: String, x: Int, y: Int, color: Int, scale: Int = 1) {
        var cx = x
        for (ch in text) {
            val glyph = GLYPHS[ch] ?: GLYPHS[' ']!!
            for (row in 0 until 7) {
                val bits = glyph[row]
                for (col in 0 until 5) {
                    if (bits[col] == '1') {
                        canvas.rect(cx + col * scale, y + row * scale, scale, scale, color)
                    }
                }
            }
            cx += 6 * scale
        }
    }

    fun width(text: String, scale: Int = 1): Int = text.length * 6 * scale
}
