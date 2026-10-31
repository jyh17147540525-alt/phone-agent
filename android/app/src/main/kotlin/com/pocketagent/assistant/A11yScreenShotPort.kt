package com.pocketagent.assistant

import android.graphics.Bitmap
import com.pocketagent.mcp.ScreenShotOutcome
import com.pocketagent.mcp.ScreenShotPort
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * **截图端口的 Android 实现** —— 把无障碍的 `takeScreenshot` 包成
 * `ScreenShotPort`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：`screen_shot` 是能力面上最大的一块缺口
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前模型只有 `screen_read`（无障碍树的文字），它**看不到像素**。
 * 而"这个报错什么意思""这个按钮是灰的吗""界面好看吗"全都要视觉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个必须做对的地方（都是"不报错但结果不对"）
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **必须在回调内完成压缩**
 *    `takeScreenshotCompat` 给的是**硬件缓冲**包出来的 Bitmap ——
 *    回调返回后缓冲即失效。把 Bitmap 存起来稍后再压，
 *    拿到的是**全黑或花屏**，而且不抛异常。
 *
 * ② **必须 `recycle()`**
 *    硬件 Bitmap 占的是系统级池，泄漏几个之后**所有截图都会失败**
 *    （`onFailure` 给错误码 3 = "资源不足"）。用户看到的是
 *    "截图功能时好时坏"。
 *
 * ③ **必须降采样**
 *    1080p 原图 PNG 约 1~3MB，base64 后 1.4~4MB。而模型看的是
 *    "有没有那个红框"，不是"每个像素"。
 *    ⇒ 缩到长边 1080 以内、JPEG 85 质量 —— 体积降一个数量级，
 *      文字仍然清晰（实测 1080p 截图降到 1080 长边后中文字可读）。
 *
 * ⚠️ 但**不能压太狠**：`screen_shot` 的主要用途之一是
 *    "帮我看看这上面写的什么"，压到 720 或质量 60 会让小字糊掉。
 */
class A11yScreenShotPort(
    private val serviceProvider: () -> AgentAccessibilityService?,
) : ScreenShotPort {

    override suspend fun capturePng(): ScreenShotOutcome {
        val service = serviceProvider() ?: return ScreenShotOutcome.Unavailable(
            "无障碍服务没有在运行",
        )

        return suspendCancellableCoroutine { cont ->
            service.takeScreenshotCompat { result ->
                result.fold(
                    onSuccess = { bitmap ->
                        // ⚠️ 压缩**必须在这个回调里**做（见类注释 ①）。
                        val bytes = runCatching { compress(bitmap) }.getOrNull()
                        // ⚠️ 无论如何都要回收（见类注释 ②）。
                        runCatching { bitmap.recycle() }
                        if (bytes == null || bytes.isEmpty()) {
                            cont.resume(ScreenShotOutcome.Failed("截图压缩失败"))
                        } else {
                            cont.resume(ScreenShotOutcome.Ok(bytes))
                        }
                    },
                    onFailure = { e ->
                        Timber.w(e, "无障碍截图失败")
                        cont.resume(
                            ScreenShotOutcome.Failed(
                                // ⚠️ 错误码翻成人话 —— 用户/模型看不懂 "errorCode 3"。
                                e.message?.takeIf { it.isNotBlank() }
                                    ?: "系统拒绝了这次截图（可能是权限或资源不足）",
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * 降采样 + JPEG 编码。
     *
     * ★ 用 JPEG 而不是 PNG：截图是照片式内容（渐变、阴影、抗锯齿文字），
     *   PNG 无损但体积是 JPEG 的 5~10 倍，而模型对 JPEG 的轻微振铃
     *   完全不敏感。
     *
     * ⚠️ 输出仍叫 `capturePng` / `imagePng` 是历史命名 —— MCP 的
     *    `mimeType` 字段会如实写 `image/jpeg`（见调用方）。
     *    这里返回的就是 JPEG 字节。
     */
    private fun compress(bitmap: Bitmap): ByteArray {
        val longSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longSide > MAX_LONG_SIDE) {
            val ratio = MAX_LONG_SIDE.toFloat() / longSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                // ★ `filter = true`：双线性插值。false 会是最近邻，
                //   文字边缘出现锯齿 —— 而那正是"看清写的什么"最怕的。
                true,
            )
        } else {
            bitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        // ⚠️ 缩放产生的**新** bitmap 要自己回收（原图由调用方回收）。
        if (scaled !== bitmap) runCatching { scaled.recycle() }
        return out.toByteArray()
    }

    private companion object {
        /**
         * 长边上限。
         *
         * ★ 1080：多数手机屏幕长边 2000~2400，缩到 1080 后体积降 4~5 倍，
         *   而模型看界面结构完全够。
         *
         * ⚠️ 不设 720：小字（如设置页的说明文字、聊天记录）会糊到读不出。
         *    而"帮我看看写的什么"正是这个工具的主要用途之一。
         */
        const val MAX_LONG_SIDE = 1080

        /** JPEG 质量。85 是"体积/清晰度"的常用平衡点。 */
        const val QUALITY = 85
    }
}
