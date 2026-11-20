package com.pocketagent.filelogic

/**
 * 柱状图 / 折线图，直接产出 PNG。
 *
 * 为什么要自己算：模型能给出数字，但给不出像素。把它交给"写一段 Python 画图"
 * 意味着要么有 root 容器、要么装了 matplotlib —— 而这两样都不该是画一张图的前提。
 *
 * 取舍写在这里，免得以后有人以为是漏了：
 * - **图上只画数字刻度与数值**，类别名不画（PNG 里塞中文字库 = 几 MB 的负担）；
 *   类别与数值的对应关系由 [summary] 用文字给模型，模型自己会复述。
 * - 不做饼图：手机上饼图的分区标签一定糊，柱状图更好读。
 */
enum class ChartKind { BAR, LINE }

data class ChartSeries(val label: String, val values: List<Double>)

data class ChartSpec(
    val title: String,
    val kind: ChartKind,
    val categories: List<String>,
    val series: List<ChartSeries>,
)

object ChartRender {

    const val MAX_SERIES = 6
    const val MAX_POINTS = 60
    const val MAX_VALUE = 1_000_000_000_000.0

    private val PALETTE = intArrayOf(
        0x2F6FEB, // 蓝
        0xE8590C, // 橙
        0x2F9E44, // 绿
        0x9C36B5, // 紫
        0xF08C00, // 黄
        0x495057, // 深灰
    )

    private const val BG = 0xFFFFFF
    private const val AXIS = 0x495057
    private const val GRID = 0xE9ECEF
    private const val TEXT = 0x212529

    /** 合法就返回 `null`，否则返回一句给模型看的话（模型会照着改）。 */
    fun validate(spec: ChartSpec): String? {
        if (spec.categories.isEmpty()) return "至少要给一个类别（横轴的项）。"
        if (spec.categories.size > MAX_POINTS) return "类别最多 $MAX_POINTS 个，现在有 ${spec.categories.size} 个。"
        if (spec.series.isEmpty()) return "至少要有一组数据。"
        if (spec.series.size > MAX_SERIES) return "最多 $MAX_SERIES 组数据，现在有 ${spec.series.size} 组。"
        spec.series.forEachIndexed { i, s ->
            if (s.values.size != spec.categories.size) {
                return "第 ${i + 1} 组「${s.label}」有 ${s.values.size} 个数，但类别有 ${spec.categories.size} 个，对不上。"
            }
            s.values.forEachIndexed { j, v ->
                if (!v.isFinite()) return "第 ${i + 1} 组第 ${j + 1} 个值不是有效数字。"
                if (kotlin.math.abs(v) > MAX_VALUE) return "第 ${i + 1} 组第 ${j + 1} 个值太大了，图表画不下。"
            }
        }
        return null
    }

    fun render(spec: ChartSpec, width: Int = 960, height: Int = 540): ByteArray {
        require(width >= 320 && height >= 240) { "图太小了" }
        val canvas = Canvas(width, height)
        canvas.fill(BG)

        val left = 76
        val right = width - 24
        val top = 28
        val bottom = height - 46

        // 纵轴范围：始终包含 0，负值也画得出来
        var lo = 0.0
        var hi = 0.0
        for (s in spec.series) for (v in s.values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi == lo) hi = lo + 1.0
        val pad = (hi - lo) * 0.08
        hi += pad
        if (lo < 0) lo -= pad

        fun yOf(v: Double): Int {
            val t = (v - lo) / (hi - lo)
            return bottom - (t * (bottom - top)).toInt()
        }

        // 网格与刻度（0/25/50/75/100%）
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4.0
            val y = yOf(v)
            canvas.line(left, y, right, y, if (i == 0) AXIS else GRID)
            val label = formatTick(v)
            TinyFont.draw(canvas, label, left - 8 - TinyFont.width(label, 1), y - 3, TEXT, 1)
        }
        canvas.line(left, top, left, bottom, AXIS)

        val n = spec.categories.size
        val slot = (right - left).toDouble() / n

        when (spec.kind) {
            ChartKind.BAR -> {
                val groupPad = slot * 0.18
                val usable = slot - groupPad * 2
                val barW = (usable / spec.series.size).toInt().coerceAtLeast(2)
                spec.series.forEachIndexed { si, s ->
                    val color = PALETTE[si % PALETTE.size]
                    for (i in 0 until n) {
                        val cx = left + slot * i + groupPad + barW * si
                        val y0 = yOf(0.0)
                        val y1 = yOf(s.values[i])
                        val x = cx.toInt()
                        if (y1 <= y0) {
                            canvas.rect(x, y1, barW, y0 - y1, color)
                        } else {
                            canvas.rect(x, y0, barW, y1 - y0, color)
                        }
                    }
                }
            }

            ChartKind.LINE -> {
                spec.series.forEachIndexed { si, s ->
                    val color = PALETTE[si % PALETTE.size]
                    var prevX = 0
                    var prevY = 0
                    for (i in 0 until n) {
                        val x = (left + slot * i + slot / 2).toInt()
                        val y = yOf(s.values[i])
                        if (i > 0) canvas.line(prevX, prevY, x, y, color, thick = 3)
                        canvas.rect(x - 3, y - 3, 7, 7, color)
                        prevX = x
                        prevY = y
                    }
                }
            }
        }

        // 图例：用色块 + 组号（组名在文字里给，画不出中文）
        var lx = left
        spec.series.forEachIndexed { si, _ ->
            canvas.rect(lx, height - 26, 12, 12, PALETTE[si % PALETTE.size])
            TinyFont.draw(canvas, "${si + 1}", lx + 16, height - 24, TEXT, 1)
            lx += 40
        }
        return canvas.encode()
    }

    /** 给模型的文字版：类别 → 各组数值。图上没字，全靠这段。 */
    fun summary(spec: ChartSpec): String {
        val sb = StringBuilder()
        for (i in spec.categories.indices) {
            sb.append("· ").append(spec.categories[i])
            spec.series.forEach { s ->
                sb.append("  ").append(s.label).append('=').append(trim(s.values[i]))
            }
            if (i < spec.categories.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }

    private fun trim(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100
        return if (r == kotlin.math.floor(r) && kotlin.math.abs(r) < 1e15) {
            r.toLong().toString()
        } else {
            r.toString()
        }
    }

    /** 刻度文字：短一点，点阵字放得下。 */
    private fun formatTick(v: Double): String {
        val a = kotlin.math.abs(v)
        if (a >= 1_000_000) return trim(v / 1_000_000) + "M"
        if (a >= 10_000) return trim(v / 1000) + "k"
        return trim(v)
    }
}
