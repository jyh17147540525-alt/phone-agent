package com.pocketagent.ui.tasks

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * **极简 Markdown** —— 只处理模型输出里最常见的那几种标记。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不用完整 Markdown 库
 * ═══════════════════════════════════════════════════════════════
 *
 * 现成方案（`compose-markdown` 等）会带来三样代价：
 *  - 一个几十 KB 的依赖 + 它的传递依赖
 *  - 它自己的 Markdown 方言（有些会渲染表格、引用块，视觉风格与本应用不一致）
 *  - **可以解析任意链接** —— 模型输出里的 URL 点击即打开浏览器，
 *    而模型输出**不可信**（它可能是被页面内容诱导出来的）
 *
 * 而真机上看到的实际问题只有一个：`**35**` 这种加粗标记**原样露出来**了。
 * 用户看到的是 `SDK 版本是 **35**`，那**明显是没渲染好的机器输出**。
 *
 * ⇒ 只做"够用"的那一点：加粗、行内代码、换行。
 *   其余标记原样保留 —— 显示成星号，也远比"渲染出奇怪的东西"安全。
 *
 * ⚠️ 刻意**不支持链接**：模型给出的 URL 不自动变成可点击的。
 *    用户真想打开就自己复制，那是他的决定。
 */
object SimpleMarkdown {

    /**
     * 把一段模型输出转成 [AnnotatedString]。
     *
     * 支持的语法（全部是**成对**出现才生效，落单的原样保留）：
     *  - `**加粗**`
     *  - `` `行内代码` ``
     */
    fun render(raw: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        val n = raw.length
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                append(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < n) {
            // ── 加粗：`**...**` ──────────────────────────────
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", startIndex = i + 2)
                // ⚠️ `end > i + 2` 而不是 `>=`：`****` 这种空内容不当作加粗，
                //    否则会出现"两个星号消失、中间什么都没有"的怪现象。
                if (end > i + 2) {
                    flush()
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(raw.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            // ── 行内代码：`` `...` `` ────────────────────────
            if (raw[i] == '`') {
                val end = raw.indexOf('`', startIndex = i + 1)
                if (end > i + 1) {
                    flush()
                    // 等宽 + 轻微底色会更好看，但那要读 MaterialTheme（在 Composable 里），
                    // 而本函数刻意是**纯函数**（可离线测）。样式由调用方决定。
                    append(raw.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            plain.append(raw[i])
            i++
        }
        flush()
    }
}
