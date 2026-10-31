package com.pocketagent.assistant

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.pocketagent.agentlogic.RedactRegion
import com.pocketagent.agentlogic.SystemUiRegions
import timber.log.Timber

/**
 * **量出状态栏 / 导航栏在屏幕上的真实位置** —— 给 `PrivacyFilter` 用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有这个类（2026-10-31 修的一个「功能完全不可用」）
 * ═══════════════════════════════════════════════════════════════
 *
 * `screen_shot` 上线时 `PrivacyFilter` 收到的是 `SystemUiRegions.unknown`，
 * 而它的语义是"**我们不知道**哪些地方是系统 UI" ⇒ `PrivacyFilter`
 * 在步骤 4 直接**丢弃整张图**（`stripSystemUi = true` 是默认值）。
 *
 * 结果：**这个工具从接上那天起，一张图都发不出去。**
 * 每次调用都返回「无法确定屏幕上的系统 UI 区域…已放弃本次截图」——
 * 文案很客气，但功能是零。
 *
 * ⚠️ 这类 bug 的形态是"接了一个永远走不通的参数"：
 *    编译过、测试绿、日志正常，只有真跑一次才会发现它 100% 拒绝。
 *    与「装好了没接线」是同一族的，只是更难看出来 ——
 *    因为它**有线**，只是线上是断路。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么每次调用都重新量，而不是启动时量一次
 * ═══════════════════════════════════════════════════════════════
 *
 * `SystemUiRegions` 是**屏幕坐标**。用户把手机转成横屏后：
 * · 状态栏跑到侧边（"高度"这个量根本不适用）
 * · 导航栏可能还在底部，也可能跟着转
 *
 * ⇒ 启动时量一次、之后一直用，**转屏后裁剪矩形就是错的**，
 *   而错的方向是"该裁的没裁掉" —— 静默泄漏。
 *
 * ⇒ [detect] 在每次 `screen_shot` 调用时跑（几微秒，无 IO）。
 */
object SystemUiProbe {

    /**
     * 量一次。
     *
     * 返回 [SystemUiRegions.unknown] 的三种情况（都是**保守**，不是"无需处理"）：
     * 1. 横屏 —— 状态栏在侧边，本方法只处理竖屏；
     * 2. `WindowManager` 拿不到；
     * 3. 状态栏与导航栏**两个都没量到**。
     */
    fun detect(context: Context): SystemUiRegions {
        // ── 横屏：状态栏在侧边，"高度"这个量不适用 ──────────────
        //
        // ⚠️ 刻意不做横屏的侧栏计算：
        //    横屏时状态栏宽度取决于刘海/挖孔，且不同 ROM 差异大。
        //    算错的后果是**该遮的没遮**（静默泄漏），而返回 unknown
        //    的后果只是"这次截不了图"（用户看得见、可以竖屏重试）。
        //    ⇒ 宁可少一个能力，不要一个可能漏的遮罩。
        if (context.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            Timber.i("系统 UI 探测：当前是横屏，返回 unknown（本次截图会被保守丢弃）")
            return SystemUiRegions.unknown
        }

        val wm = context.getSystemService(WindowManager::class.java)
            ?: return SystemUiRegions.unknown.also {
                Timber.w("系统 UI 探测：拿不到 WindowManager")
            }

        val screen: Rect = screenBounds(wm) ?: return SystemUiRegions.unknown.also {
            Timber.w("系统 UI 探测：拿不到屏幕尺寸")
        }

        var statusBar: RedactRegion? = null
        var navigationBar: RedactRegion? = null

        // ── 首选：WindowInsets（API 30+，能正确处理刘海与手势条）──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val insets = wm.currentWindowMetrics.windowInsets
                val sb = insets.getInsets(WindowInsets.Type.statusBars())
                val nb = insets.getInsets(WindowInsets.Type.navigationBars())
                // 手势条模式下 navigationBars 的 bottom 可能是 0，
                // 而 systemGestures 才是那根横条的真实高度。
                val gesture = insets.getInsets(WindowInsets.Type.systemGestures())
                val navBottom = maxOf(nb.bottom, gesture.bottom)

                if (sb.top > 0) {
                    statusBar = RedactRegion(0, 0, screen.width(), sb.top)
                }
                if (navBottom > 0) {
                    navigationBar = RedactRegion(
                        left = 0,
                        top = screen.height() - navBottom,
                        right = screen.width(),
                        bottom = screen.height(),
                    )
                }
            }.onFailure { Timber.w(it, "系统 UI 探测：WindowInsets 读取失败，将回落资源尺寸") }
        }

        // ── 回落：framework 资源里的高度 ────────────────────────
        //
        // ⚠️ `status_bar_height` 是 **Android 的隐藏资源**，`getIdentifier`
        //    能拿到，但不是公开 API。部分定制 ROM 可能改名或返回 0 ——
        //    所以它是**回落**而不是首选。
        if (statusBar == null) {
            dimenPx(context, "status_bar_height")?.let {
                statusBar = RedactRegion(0, 0, screen.width(), it)
            }
        }
        if (navigationBar == null) {
            dimenPx(context, "navigation_bar_height")?.let {
                navigationBar = RedactRegion(
                    left = 0,
                    top = screen.height() - it,
                    right = screen.width(),
                    bottom = screen.height(),
                )
            }
        }

        if (statusBar == null && navigationBar == null) {
            Timber.w("系统 UI 探测：状态栏与导航栏都没量到，返回 unknown")
            return SystemUiRegions.unknown
        }

        Timber.i(
            "系统 UI 探测：screen=%dx%d statusBar=%s navBar=%s",
            screen.width(), screen.height(), statusBar, navigationBar,
        )
        return SystemUiRegions(statusBar = statusBar, navigationBar = navigationBar)
    }

    /** 屏幕的**真实**像素尺寸（含系统栏，不是可用区域）。 */
    private fun screenBounds(wm: WindowManager): Rect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            if (b != null && b.width() > 0 && b.height() > 0) {
                return Rect(0, 0, b.width(), b.height())
            }
        }
        // API < 30（以及上面失败时）的回落
        return runCatching {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            if (p.x > 0 && p.y > 0) Rect(0, 0, p.x, p.y) else null
        }.getOrNull()
    }

    private fun dimenPx(context: Context, name: String): Int? {
        val res = context.resources
        val id = res.getIdentifier(name, "dimen", "android")
        if (id == 0) return null
        return runCatching { res.getDimensionPixelSize(id) }.getOrNull()?.takeIf { it > 0 }
    }
}
