package com.pocketagent.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.pocketagent.agentlogic.RedactionPlan
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

    override suspend fun capturePng(plan: RedactionPlan): ScreenShotOutcome {
        val service = serviceProvider() ?: return ScreenShotOutcome.Unavailable(
            "无障碍服务没有在运行",
        )

        return suspendCancellableCoroutine { cont ->
            service.takeScreenshotCompat { result ->
                result.fold(
                    onSuccess = { bitmap ->
                        // ⚠️ 压缩**必须在这个回调里**做（见类注释 ①）。
                        // ⚠️ **先脱敏再压缩**：反过来会让"遮过的区域"重新进入编码器，
                        //    而且遮蔽后体积更小，先压再遮等于白压一次。
                        val bytes = runCatching { redactAndCompress(bitmap, plan) }.getOrNull()
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
     * **执行 `PrivacyFilter` 的计划**，然后降采样 + JPEG 编码。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 2026-10-31：这里原来**完全没执行计划**
     * ═══════════════════════════════════════════════════════════════
     *
     * `ScreenShotTool` 拿到 `FilterOutcome.Allowed` 就往下走了，
     * `plan` 被直接丢弃 —— 于是：
     * · 状态栏（通知标题、未接来电）**原样进图**
     * · 输入框（密码、验证码）**原样进图**
     *
     * 而 `PrivacyFilter` 的注释把这条路径称为"上传路径的唯一出口"。
     * ⇒ 关卡算出了结论，但**没有人执行结论** —— 这比没有关卡更危险，
     *   因为日志里会显示"已通过隐私过滤"。
     *
     * ⚠️ 顺序是**先脱敏、后压缩**：
     *    · 反过来（先压再遮）会让已压缩的像素再解一次，画质二次损失；
     *    · 更重要的是遮蔽后体积更小，先压等于按原尺寸付了一次编码成本。
     */
    private fun redactAndCompress(bitmap: Bitmap, plan: RedactionPlan): ByteArray {
        val redacted = redact(bitmap, plan)
        return try {
            compress(redacted)
        } finally {
            // ⚠️ `redact` 可能返回原图（`isNoOp`），那时**不能**回收 ——
            //    调用方还要回收它。
            if (redacted !== bitmap) runCatching { redacted.recycle() }
        }
    }

    /**
     * 按计划裁剪 + 遮蔽。
     *
     * ⚠️ **`cropOut` 只按"顶部条 / 底部条"处理**：
     *    `SystemUiRegions` 里只有状态栏（顶部）与导航栏（底部），
     *    而 `displayCutout` 也在顶部。所以保留区域就是中间那一条。
     *    如果将来出现"左/右侧栏"（横屏），`SystemUiProbe` 会返回 `unknown`
     *    ⇒ 根本走不到这里。**这是刻意的：宁可截不了，不要裁错。**
     */
    private fun redact(bitmap: Bitmap, plan: RedactionPlan): Bitmap {
        if (plan.isNoOp) return bitmap

        // 硬件 Bitmap 不能直接 createBitmap / 画 Canvas，先转软件位图。
        // ⚠️ 这一步是"必须"而不是"优化"：`takeScreenshot` 给的是 HARDWARE，
        //    对它调 `createBitmap` 在部分 ROM 上直接抛 IllegalArgumentException。
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
        } else {
            bitmap
        }

        var top = 0
        var bottom = software.height
        for (r in plan.cropOut) {
            if (r.top <= 0 && r.bottom in 1 until software.height) {
                top = maxOf(top, r.bottom)
            }
            if (r.bottom >= software.height && r.top in 1 until software.height) {
                bottom = minOf(bottom, r.top)
            }
        }
        // 防呆：两个条叠在一起（极端分屏）⇒ 放弃裁剪而不是产出 0 高度的图
        if (bottom <= top) {
            top = 0
            bottom = software.height
        }

        val keepH = bottom - top
        val cropped = if (top > 0 || bottom < software.height) {
            runCatching { Bitmap.createBitmap(software, 0, top, software.width, keepH) }
                .getOrNull() ?: software
        } else {
            software
        }
        if (software !== bitmap && cropped !== software) runCatching { software.recycle() }

        if (plan.mask.isEmpty()) return cropped

        // 遮蔽：画纯黑。⚠️ 不用模糊 —— 模糊后的文字仍可能被 OCR 认出，
        // 而"纯黑"在视觉上明确告诉用户"这里被遮了"。
        val mutable = runCatching { cropped.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
            ?: return cropped
        val canvas = Canvas(mutable)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        for (r in plan.mask) {
            // mask 是**原图坐标**，裁剪后要减掉 top 并夹在可视范围内
            val t = (r.top - top).coerceIn(0, keepH)
            val b = (r.bottom - top).coerceIn(0, keepH)
            if (b <= t) continue
            canvas.drawRect(
                r.left.coerceIn(0, cropped.width).toFloat(),
                t.toFloat(),
                r.right.coerceIn(0, cropped.width).toFloat(),
                b.toFloat(),
                paint,
            )
        }
        if (cropped !== bitmap) runCatching { cropped.recycle() }
        return mutable
    }

    /**
     * 降采样 + JPEG 编码。
     *
     * ★ 用 JPEG 而不是 PNG：截图是照片式内容（渐变、阴影、抗锯齿文字），
     *   PNG 无损但体积是 JPEG 的 5~10 倍，而模型对 JPEG 的轻微振铃
     *   完全不敏感。
     *
     * ⚠️ 接口方法名仍是 `capturePng`（历史命名），但**返回的是 JPEG 字节**。
     *    `ToolOutcome.Success.imageMimeType` 会如实声明 `image/jpeg` ——
     *    2026-10-31 之前 `McpDispatcher` 硬写 `image/png`，
     *    客户端按 PNG 解 JPEG，**图打不开且不报错**。
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
