package com.pocketagent.perception

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * **读屏幕上写了什么** —— 图片 → 带坐标的文字行。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有它（用户的原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * "要让模型能看清虚拟屏里的内容，能看懂虚拟屏里的内容。"
 * "模型一直反馈无法看清虚拟屏里的内容。"
 *
 * 之前 `shot` 给的是一张**亮度布局图**（哪里亮哪里暗）。那能告诉模型
 * "这里有一块列表"，但**一个字都读不出来** —— 于是它只能猜，
 * 猜错就点错，用户看到的就是"它根本看不清"。
 *
 * ⇒ 真正的解法是**把像素变成文字**：OCR。它有三个好处：
 *  1. **任何模型都能用** —— 不需要多模态，纯文本模型一样能读；
 *  2. **带坐标** —— 模型能直接说"点 (x,y)"，而不是瞎猜位置；
 *  3. **不需要网络、不需要 root** —— ML Kit 端侧识别，图不出设备。
 *
 * ⚠️ 用的是**中文识别器**：它同时认中英混排（QQ/微信的界面就是这种）。
 *    只挂英文识别器会把整屏中文读成空。
 *
 * ⚠️ OCR 会失败、会读错、会漏行。所以 [Line.text] 原样返回识别结果，
 *    **不做任何"猜测性补全"** —— 编出来的文字比读不到更糟：
 *    模型会照着编的内容去点。
 */
class OcrReader {

    /** 一行识别结果。[Line.centerX]/[Line.centerY] 就是"点这里"的坐标。 */
    data class Line(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    private val zhRecognizer by lazy {
        // ⚠️ 中文识别器同时支持中英混排；只用英文识别器会把中文全读成空。
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 备用识别器。
     *
     * ★ 真机实测（2026-10-28，Redmi K60 / 无 GMS）：中文包会抛
     *   `MlKitException: Failed to run text recognizer text-recognition-chinese`。
     *   两个语言包是**各自独立的模型资源**，一个起不来另一个仍可能可用 ——
     *   所以留一条退路，而不是整条"看屏幕"的能力一起失效。
     */
    private val enRecognizer by lazy {
        TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 识别一张图。
     *
     * @return 逐行文字（按 ML Kit 的阅读顺序）；识别失败返回空表，
     *   **不抛异常** —— 读不出字不该让"看一眼"这个动作整体失败。
     */
    suspend fun recognize(bitmap: Bitmap): List<Line> {
        val zh = run(bitmap, zhRecognizer, "中文")
        if (zh.isNotEmpty()) return zh
        // ★ 中文包起不来时退到英文包 —— 至少能把数字、英文界面读出来。
        return run(bitmap, enRecognizer, "英文")
    }

    private suspend fun run(bitmap: Bitmap, engine: com.google.mlkit.vision.text.TextRecognizer, label: String): List<Line> =
        suspendCancellableCoroutine { cont ->
            val started = android.os.SystemClock.uptimeMillis()
            val image = InputImage.fromBitmap(bitmap, 0)
            engine.process(image)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            val text = line.text.trim()
                            if (text.isEmpty()) return@mapNotNull null
                            Line(
                                text = text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                            )
                        }
                    }
                    // ★ 必须记：OCR 悄悄返回空是最难查的一种"功能不好用"——
                    //   界面上一切正常，只是模型说"我读不到字"。
                    android.util.Log.i(
                        TAG,
                        "OCR[$label] ${bitmap.width}x${bitmap.height}：块=${result.textBlocks.size} " +
                            "行=${lines.size} 耗时=${android.os.SystemClock.uptimeMillis() - started}ms",
                    )
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener {
                    android.util.Log.w(TAG, "OCR[$label] 失败", it)
                    // ⚠️ 失败时返回空表（而不是抛出）—— 由调用方如实说"读不出"。
                    if (cont.isActive) cont.resume(emptyList())
                }
        }

    private companion object {
        const val TAG = "OcrReader"
    }
}
