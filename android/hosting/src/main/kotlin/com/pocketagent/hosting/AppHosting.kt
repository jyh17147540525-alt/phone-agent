package com.pocketagent.hosting

/**
 * **应用托管** —— 让角色在**不占用用户主屏**的前提下操作用户授权的应用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-27：真机实测后的技术选择（不是设计想象）
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求："角色操作用户授予的账号（QQ/微信/抖音），
 * 必须在虚拟屏里，不能对用户使用手机造成干扰，要让用户无感。"
 *
 * 我在真机上**逐条试过**三条路，结论如下：
 *
 * | 方案 | 实测结果 | 可行性 |
 * |---|---|---|
 * | `VirtualDisplay`（真虚拟屏） | 需要 `CAPTURE_VIDEO_OUTPUT` 或 Shizuku。**Shizuku 未运行** | ✗ 当前不可用 |
 * | `am start --display N` | root 也抛 `SecurityException: SafeActivityOptions.checkPermissions` | ✗ Android 12+ 硬限制 |
 * | **freeform + task resize** | **实测成功**：`am task resize 24735 0 0 600 900` → `bounds=[0,0][600,900]` | ✓ 可用 |
 *
 * ⇒ 所以本模块走**第三条路**，并诚实标注它的边界（见 [HostingLimitation]）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ "无感"能达到什么程度 —— 必须说清
 * ═══════════════════════════════════════════════════════════════
 *
 * freeform 小窗**仍然可见**：它会被缩到角落，但用户看得见。
 *
 * 这**不等于**"完全无感"。能做到的是：
 *  - 不遮挡用户正在用的应用（小窗在角上）
 *  - 不抢焦点（`FLAG_NOT_FOCUSABLE` 由系统控制，实测小窗不抢 IME）
 *  - 操作可被无障碍读到（实测 `uiautomator dump` 拿到 22KB 界面）
 *
 * 做不到的是：
 *  - 完全隐藏（负坐标被拒绝：`Error: bad left arg: -2000`）
 *  - 主屏前台完全不受影响（freeform 窗口仍在前台任务栈里）
 *
 * ⚠️ 把这个区别藏着是最坏的做法：用户点了"托管"却发现微信在角上跳，
 *    会以为功能坏了。**如实说清，比假装做到更有用。**
 */

/**
 * 一块托管窗口的几何。
 *
 * ⚠️ 为什么是"区域"而不是"全屏/隐藏"二选一：
 *    真机上 `am task resize` 接受任意**非负**矩形，而负坐标被拒。
 *    所以"藏到屏幕外"这条路是死的 —— 只能选一个**用户不太会看**的角落。
 */
data class HostBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "左上角不能为负（系统不允许）：($left,$top)" }
        require(right > left) { "右边界必须大于左边界" }
        require(bottom > top) { "下边界必须大于上边界" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toShellArgs(): String = "$left $top $right $bottom"

    companion object {
        /**
         * 默认托管区域 —— 屏幕**右上角**的一个小窗。
         *
         * ★ 为什么是右上角：
         *  - 左下角是手势区（会被系统手势抢）
         *  - 右下角同上
         *  - 左上角常有返回按钮
         *  - **右上角**在多数应用里是空白区（状态栏下方、标题右侧）
         *
         * ⚠️ 尺寸取屏幕的 42% 宽 × 30% 高：
         *    更小会让应用重排成"平板布局"，按钮位置全变；
         *    更大就会盖住用户正在看的内容。
         */
        fun cornerOf(screenWidth: Int, screenHeight: Int): HostBounds {
            val w = (screenWidth * 0.42f).toInt()
            val h = (screenHeight * 0.30f).toInt()
            return HostBounds(
                left = screenWidth - w,
                top = (screenHeight * 0.06f).toInt(),
                right = screenWidth,
                bottom = (screenHeight * 0.06f).toInt() + h,
            )
        }
    }
}

/**
 * 一次托管的生命周期命令。
 *
 * ⚠️ 全部是**幂等**的：重复调用不报错。
 *    因为"用户点了两次托管"或"上一次没清理干净"都是常态。
 */
sealed interface HostingCommand {

    /** 把某个应用启动（或拉到前台）并缩到指定区域。 */
    data class Host(
        val packageName: String,
        val bounds: HostBounds,
    ) : HostingCommand

    /** 把托管的应用**恢复全屏**（用户要接管时）。 */
    data class Release(val packageName: String) : HostingCommand

    /** 在托管窗口里点一下。坐标是**窗口内**坐标，不是屏幕坐标。 */
    data class TapInside(val packageName: String, val x: Int, val y: Int) : HostingCommand

    /** 在托管窗口里输入文字。 */
    data class TypeInside(val packageName: String, val text: String) : HostingCommand
}

/** 执行结果。 */
sealed interface HostingResult {
    data class Ok(val detail: String) : HostingResult
    data class Failed(val reason: String) : HostingResult

    /**
     * 这条命令**需要 root**，而当前没有。
     *
     * ⚠️ 单独一档而不是并进 Failed：
     *    "需要 root"用户有明确动作可做（去开），而 Failed 是"试过了没成"。
     *    合并会让他去排查一个根本不该他排查的问题。
     */
    data class NeedsRoot(val reason: String) : HostingResult
}

/**
 * 能力边界 —— **给用户看的**，不是内部注释。
 *
 * ★ 这份清单的产品意义大于技术意义：用户点"应用托管"之前，
 *   应该知道自己会看到什么。看到了并接受，比事后抱怨好。
 */
object HostingLimitation {

    data class Item(val title: String, val detail: String)

    val items: List<Item> = listOf(
        Item(
            "窗口会被缩到右上角，但**看得见**",
            "系统不允许把窗口移到屏幕外（负坐标被拒）。所以托管时你会看到" +
                "一个小窗在右上角。它不会挡住你正在用的应用，但也不是完全隐藏。",
        ),
        Item(
            "需要 root 权限",
            "启动其他应用到自由窗口、调整窗口大小，这两件事都需要 root。" +
                "你的手机已 root，但请在 root 管理器里给本应用授权。",
        ),
        Item(
            "部分应用不支持自由窗口",
            "没有声明支持多窗口的应用（尤其金融类、部分老应用）会被系统" +
                "强制全屏 —— 那时托管的就退化成普通启动。",
        ),
        Item(
            "真正的虚拟屏这一版没有",
            "Android 10+ 禁止普通应用把别家应用启动到虚拟屏，只有 Shizuku " +
                "或系统签名可以。当前 Shizuku 未运行，所以没有那条路。",
        ),
    )
}
