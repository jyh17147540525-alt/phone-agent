package com.pocketagent.assistant

import android.app.Activity
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
import java.util.concurrent.TimeUnit

/**
 * **虚拟屏托管探针** —— 只用来"问系统要答案"，不实现任何功能。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它要回答的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求：角色能操作用户授权过的 QQ / 微信 / 抖音账号，
 * 且**必须在虚拟屏里跑、不能占用也不能打扰用户的主屏幕**。
 *
 * 四条硬指标（本探针逐条给证据）：
 *  1. 目标 App 的窗口**不在用户屏幕上** —— 它在 `mDisplayId=<虚拟屏>`
 *  2. 能读到它的画面 —— `ImageReader` 直接拿帧
 *  3. 能点它 —— `input -d <id> tap x y`（root）
 *  4. 用户的屏幕**不受影响** —— 主屏焦点不变
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条已经排除的路（都有真机原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * - 只带 `PUBLIC` 的 VirtualDisplay → 要 `CAPTURE_VIDEO_OUTPUT` 权限
 * - `ActivityView` → `@SystemApi`，compileSdk 36 下 `Unresolved reference`
 * - freeform + `am task resize` → 窗口**看得见**，违反指标 1
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 走通的组合（2026-10-27 真机 Redmi K60 / Android 15）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * ① App 建屏（不需要 root）：
 *    createVirtualDisplay(PUBLIC | OWN_CONTENT_ONLY, ImageReader.surface)
 *    → id=4, 720x1280
 * ② root 把目标 App 拉进这块屏：
 *    su -c "am start --display 4 -n com.tencent.mobileqq/.activity.SplashActivity"
 *    → dumpsys: Display: mDisplayId=4 里出现了 QQ 的 Task
 * ③ 读画面：ImageReader 在本进程直接拿帧
 * ④ 点它：su -c "input -d 4 tap 360 640"
 * ```
 *
 * ⚠️ ②是唯一需要 root 的一步。之前 `su -c am start --display` 失败，
 *    是因为当时**那块屏还不存在**，系统把"往不存在的 display 启动"
 *    报成了权限问题 —— 那不是真的没权限。
 *
 * ⚠️ `screencap -d <id>` 对 OWN_CONTENT_ONLY 的屏返回失败（Status -2），
 *    所以外部截图不行：读画面只能靠宿主进程自己的 ImageReader。
 *
 * 用法：
 * ```
 * adb shell am start -n com.pocketagent.debug/.assistant.VirtualScreenProbeActivity \
 *     --es target com.tencent.mobileqq
 * ```
 */
class VirtualScreenProbeActivity : Activity() {

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val log = StringBuilder()
    private var banner: TextView? = null
    private var target: String = DEFAULT_TARGET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent?.getStringExtra(EXTRA_TARGET) ?: DEFAULT_TARGET
        Log.i(TAG, "════ 探针开始 target=$target ════")

        banner = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            text = "虚拟屏探针：$target\n等待结论…"
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    banner,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                (banner?.layoutParams as? FrameLayout.LayoutParams)?.gravity =
                    Gravity.TOP or Gravity.START
            },
        )

        dumpDisplayBaseline()
        createVirtualScreen()

        // ★ 三段检查，每段之间留出时间让外部命令（adb）插进来。
        window.decorView.postDelayed({ verify(tag = "5s") }, 5_000L)
        window.decorView.postDelayed({ verify(tag = "20s") }, 20_000L)
        window.decorView.postDelayed({ tryInject() }, 24_000L)
        window.decorView.postDelayed({ checkUserScreenUntouched() }, 28_000L)
    }

    // ── ① 建屏（不需要 root）──────────────────────────────────

    private fun createVirtualScreen() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val reader = try {
            ImageReader.newInstance(HD_W, HD_H, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            line("建屏: ImageReader 创建失败 ${t.javaClass.simpleName}: ${t.message}")
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
            //      虚拟屏会被理解成"屏幕录制/镜像"，那要 CAPTURE_VIDEO_OUTPUT；
            //      加上它，语义变成"这是我 App 自己要往里渲染内容的屏"。
            //
            // ⚠️ `VIRTUAL_DISPLAY_FLAG_TRUSTED` / `SUPPORTS_TOUCH` 仍是 @SystemApi，
            //    编不过 —— 所以"这块屏自己收触摸"做不到，输入必须从外部注入。
            dm.createVirtualDisplay(
                "pocketagent-host",
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
            line("建屏: 失败 ${t.javaClass.simpleName}: ${t.message}")
            runCatching { reader.close() }
            return
        }
        virtualDisplay = vd
        imageReader = reader
        line("建屏: 成功 id=${vd.display.displayId} 尺寸=${HD_W}x$HD_H")
        // ★ 这一行是给外部实验用的：拿到 id 之后，从 root shell 用
        //   `am start --display <id> -n <component>` 把别的 App 拉进来。
        Log.i(TAG, "DISPLAY_ID=${vd.display.displayId}")
    }

    // ── ② 检查这块屏上有没有画面 ───────────────────────────────

    private fun verify(tag: String) {
        val dm = getSystemService(DisplayManager::class.java)
        line("[$tag] Display = " + dm?.displays.orEmpty().joinToString { "${it.displayId}" })

        val reader = imageReader
        if (reader == null) {
            line("[$tag] 没有 ImageReader，跳过取帧")
        } else {
            // ★ 取帧是"虚拟屏上真的有画面"的唯一硬证据。
            //   屏建起来但没人渲染时，acquireLatestImage 返回 null。
            val img = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (img == null) {
                line("[$tag] 取帧 = null（这块屏上没有画面）")
            } else {
                val buf = img.planes.firstOrNull()?.buffer
                if (buf != null) {
                    val copy = ByteArray(minOf(buf.remaining(), 1 shl 20))
                    buf.get(copy)
                    // 每 4096 字节采样一个点：近似判断"是不是全黑"
                    var nonZero = 0
                    var i = 0
                    while (i < copy.size) {
                        if (copy[i] != 0.toByte()) nonZero++
                        i += 4096
                    }
                    line("[$tag] 取帧 尺寸=${img.width}x${img.height} 非零采样=$nonZero")
                } else {
                    line("[$tag] 取帧 尺寸=${img.width}x${img.height} 但没有 buffer")
                }
                img.close()
            }
        }

        line("[$tag] $target 进程在跑 = ${isProcessRunning(target)}")
        paint()
    }

    // ── ③ 输入注入 ─────────────────────────────────────────────

    /**
     * 试一次输入注入。
     *
     * ⚠️ `input -d <DISPLAY_ID>` 的坐标是**那块屏自己的坐标系**，
     *    不是主屏的。混用会"点到完全不相干的地方"。
     */
    private fun tryInject() {
        val id = virtualDisplay?.display?.displayId ?: return
        val cmd = "input -d $id tap ${HD_W / 2} ${HD_H / 2}"
        val out = runCatching {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            p.waitFor(12, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText()
        }.getOrNull()
        val shown = out?.trim()?.take(200).orEmpty()
        line("注入: `$cmd` → ${if (shown.isBlank()) "（无输出 = 成功）" else shown}")
        paint()
    }

    // ── ④ 用户的主屏幕有没有被打扰 ─────────────────────────────

    /**
     * 检查主屏（display 0）是否**没有被我们打扰**。
     *
     * ★ 这是用户最在意的一条：他要的是"角色在后台干活，我该干嘛干嘛"。
     *   判据不能是"我觉得应该没打扰"，必须是可观测的事实：
     *   主屏当前焦点窗口**不是**目标 App，也**不是**本应用。
     */
    private fun checkUserScreenUntouched() {
        val out = runCatching {
            val p = ProcessBuilder("sh", "-c", "dumpsys window | grep mCurrentFocus").start()
            p.waitFor(10, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText()
        }.getOrNull()
        line("主屏焦点: ${out?.trim()?.take(200) ?: "读不到"}")
        val disturbed = out?.contains(target) == true || out?.contains(ME_PACKAGE) == true
        line(if (disturbed) "主屏被打扰了 —— 焦点跑到了不该在的应用上" else "主屏未被打扰")
        paint()
    }

    private fun paint() {
        banner?.text = "虚拟屏探针结论\n\n" + log.toString()
        Log.i(TAG, "──── 探针结论 ────")
        log.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
        Log.i(TAG, "════ 探针结束 ════")
    }

    private fun line(s: String) {
        log.appendLine(s)
        Log.i(TAG, s)
        banner?.text = log.toString()
    }

    private fun dumpDisplayBaseline() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        line("基线: Display = " + dm.displays.joinToString { "${it.displayId}(${it.name})" })
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
        private const val ME_PACKAGE = "com.pocketagent"
        private const val HD_W = 720
        private const val HD_H = 1280
        private const val DENSITY = 320

        /** 供 `dumpsys` 之外的手工排查用。 */
        val startedAt: Long = SystemClock.uptimeMillis()
    }
}
