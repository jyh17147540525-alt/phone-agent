package com.pocketagent.assistant

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * **应用托管可行性探针** —— 只用来"问系统要答案"，不实现任何功能。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它要回答的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求：角色能操作用户授权过的 QQ / 微信 / 抖音账号，
 * 且**必须在虚拟屏里跑、不占用户的屏幕、要快**。
 *
 * 之前的两条路都不通：
 *  ① freeform + `am task resize` —— 窗口**看得见**，会挡住用户；
 *  ② `am start --display N`（root）—— `SecurityException`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一条已经排除的路：`ActivityView`
 * ═══════════════════════════════════════════════════════════════
 *
 * `android.app.ActivityView` 本来是最省事的答案（它自己带虚拟屏，
 * 输入靠 `dispatchTouchEvent` 直投，不需要任何权限）。
 * 但它**不是公开 API**（`@SystemApi`），compileSdk 36 下直接
 * `Unresolved reference` —— 也就是说，这条路过不了编译器，
 * 更不用谈上架。所以不试了。
 *
 * ═══════════════════════════════════════════════════════════════
 *  所以本探针只测剩下的几种组合
 * ═══════════════════════════════════════════════════════════════
 *
 *  | 通道 | 屏幕读取 | 输入注入 |
 *  |---|---|---|
 *  | B. 自建 VirtualDisplay + `setLaunchDisplayId` | ImageReader 直接拿帧 | 无（要另找，见 C） |
 *  | C. 同上 + 无障碍注入 | ImageReader | AccessibilityService |
 *  | D. 同上 + root `input --display` | ImageReader | root |
 *
 * 本探针只测 **B 能不能把第三方 App 拉进自己的虚拟屏**，
 * 因为那是整条链路的第一环 —— 它不通，后面两种都不用谈。
 *
 * ⚠️ 结论必须来自 logcat，不能来自推测。
 *
 * 用法：
 * ```
 * adb shell am start -n com.pocketagent.debug/.assistant.AppHostProbeActivity \
 *     --es target com.tencent.mobileqq
 * ```
 */
class AppHostProbeActivity : Activity() {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val log = StringBuilder()
    private var banner: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent?.getStringExtra(EXTRA_TARGET) ?: DEFAULT_TARGET
        Log.i(TAG, "════ 探针开始 target=$target ════")

        banner = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            text = "托管探针：$target\n等待结论…"
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    banner,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).also { /* 左上角对齐由 FrameLayout 默认 */ },
                )
                (banner?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
                    Gravity.TOP or Gravity.START
            },
        )

        dumpDisplayBaseline()
        probeOwnVirtualDisplay(target)
        window.decorView.postDelayed({ verify(target) }, VERIFY_DELAY_MS)
    }

    // ── 基线：现在系统里有几个 Display ──────────────────────────

    private fun dumpDisplayBaseline() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        line("基线: Display = " + dm.displays.joinToString { "${it.displayId}(${it.name})" })
    }

    // ── 通道 B：自建虚拟屏，把第三方 App 拉进去 ─────────────────

    private fun probeOwnVirtualDisplay(target: String) {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val reader = try {
            ImageReader.newInstance(HD_W, HD_H, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            line("B: ImageReader 创建失败 ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        val vd = try {
            // ★★ 2026-10-27 真机：只写 `PUBLIC` 时系统这样回答 ——
            //
            //    "Requires CAPTURE_VIDEO_OUTPUT or CAPTURE_SECURE_VIDEO_OUTPUT
            //     permission, or an appropriate MediaProjection token in order to
            //     create a screen sharing virtual display. In order to create a
            //     virtual display that does not perform screen sharing (mirroring),
            //     please use the flag VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY."
            //
            //    ⇒ 系统**自己**给出了正确做法。没有 OWN_CONTENT_ONLY 的 PUBLIC
            //      虚拟屏会被理解成"屏幕录制/镜像"，那是要 CAPTURE_VIDEO_OUTPUT 的；
            //      加上它，语义变成"这是我 App 自己要往里渲染内容的屏"，
            //      第三方 App 不需要录屏权限。
            //
            // ⚠️ `VIRTUAL_DISPLAY_FLAG_TRUSTED` / `SUPPORTS_TOUCH` 仍是 @SystemApi，
            //    编不过。所以输入注入必须另找通道（无障碍或 root），见类注释。
            dm.createVirtualDisplay(
                "pocketagent-host-probe",
                HD_W,
                HD_H,
                DENSITY,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null,
                null,
            )
        } catch (t: Throwable) {
            line("B: createVirtualDisplay 失败 ${t.javaClass.simpleName}: ${t.message}")
            runCatching { reader.close() }
            return
        }
        virtualDisplay = vd
        imageReader = reader
        line("B: createVirtualDisplay 成功 id=${vd.display.displayId} 尺寸=${HD_W}x$HD_H")

        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) {
            line("B: 拿不到 $target 的启动 Intent（包可见性被挡？）")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId)
            startActivity(launch, opts.toBundle())
            line("B: startActivity(displayId=${vd.display.displayId}) 没有抛异常")
        } catch (t: Throwable) {
            line("B: startActivity 失败 ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── 验证：App 到底进没进去 ──────────────────────────────────

    private fun verify(target: String) {
        val dm = getSystemService(DisplayManager::class.java)
        line("验证: Display = " + dm?.displays.orEmpty().joinToString { "${it.displayId}(${it.name})" })

        val reader = imageReader
        if (reader == null) {
            line("验证: 没有 ImageReader，跳过取帧")
        } else {
            // ★ 取帧是"虚拟屏上真的有画面"的唯一硬证据。
            //   窗口建起来但没内容时，acquireLatestImage 会返回 null。
            val img = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (img == null) {
                line("验证: 取帧 = null（虚拟屏上没有画面）")
            } else {
                val buf = img.planes.firstOrNull()?.buffer
                var nonZero = 0
                if (buf != null) {
                    val copy = ByteArray(minOf(buf.remaining(), 1 shl 20))
                    buf.get(copy)
                    // 每隔 4096 字节看一个点，统计非零像素比例（近似"有没有内容"）
                    var i = 0
                    while (i < copy.size) {
                        if (copy[i] != 0.toByte()) nonZero++
                        i += 4096
                    }
                    line("验证: 取帧 尺寸=${img.width}x${img.height} 非零采样=$nonZero")
                } else {
                    line("验证: 取帧 尺寸=${img.width}x${img.height} 但没有 buffer")
                }
                img.close()
            }
        }

        line("验证: $target 进程在跑 = ${isProcessRunning(target)}")
        paint()
    }

    private fun paint() {
        banner?.text = "托管探针结论\n\n" + log.toString()
        Log.i(TAG, "──── 探针结论 ────")
        log.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
        Log.i(TAG, "════ 探针结束 ════")
    }

    private fun line(s: String) {
        log.appendLine(s)
        Log.i(TAG, s)
        banner?.text = log.toString()
    }

    /** 目标包有没有在跑（读 `/proc` 比 `ActivityManager` 诚实：后者会把"最近用过"算成在跑）。 */
    private fun isProcessRunning(pkg: String): Boolean = runCatching {
        java.io.File("/proc").listFiles()
            ?.asSequence()
            ?.filter { f -> f.name.all { it.isDigit() } }
            ?.mapNotNull { dir ->
                val cmd = java.io.File(dir, "cmdline")
                if (!cmd.isFile) null else runCatching { cmd.readText() }.getOrNull()
            }
            ?.map { it.trim('\u0000', '\n', ' ') }
            ?.any { it == pkg || it.startsWith("$pkg:") }
            ?: false
    }.getOrDefault(false)

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppHostProbe"
        const val EXTRA_TARGET = "target"
        private const val DEFAULT_TARGET = "com.tencent.mobileqq"
        private const val HD_W = 720
        private const val HD_H = 1280
        private const val DENSITY = 320
        private const val VERIFY_DELAY_MS = 5_000L

        /** 供 `dumpsys` 之外的手工排查用。 */
        val startedAt: Long = SystemClock.uptimeMillis()
    }
}
