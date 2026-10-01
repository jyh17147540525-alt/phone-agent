package com.pocketagent.perception

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍数据的**来源** —— 由 Android 层注入，本模块不反向依赖 `:app`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要做成接口而不是直接引用 `AgentAccessibilityService`
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **依赖方向**。`:app` 依赖 `:perception`（它要用感知层），
 *    反向依赖就成环了。
 *
 * 2. **可测**。本接口的实现可以是一个返回**构造好的树**的假件，
 *    于是"遍历逻辑 / 超时 / 降级判定"全都能离线断言 ——
 *    而那些恰恰是最容易写错的部分（边界、空树、超深树）。
 *    真机只负责验证"平台行为是否符合假设"，不负责验证我们的遍历逻辑。
 *
 * 3. **抗风险**。Android 17+ 收紧无障碍后，换掉这个接口的实现即可，
 *    `:perception` 的其余部分零改动。这是 `ScreenSnapshot` 注释里
 *    写明的三条理由之一。
 *
 * ⚠️ **所有方法都必须在主线程调用** —— 底层 `AccessibilityService`
 *    的三个 API 都要求主线程，而在别的线程调用的表现不是抛异常，
 *    是**返回 null 或永远不回调**，看起来像"这个能力不可用"。
 */
interface A11ySource {

    /**
     * 当前活动窗口的根节点。
     *
     * ⚠️ **调用方负责 recycle**（在实现层 recycle 会让返回的对象立刻失效）。
     *
     * 返回 null 的三种情况**无法区分**（Android API 的限制）：
     * 服务没连上 / 窗口不提供节点（游戏、DRM、部分自绘界面）/ 主线程被阻塞。
     * ⇒ 所以 null 只能当作"这次读不到"，**不能**当作"这个界面是空的"。
     *   前者该走截图兜底，后者该报告"没有可操作元素"。
     */
    fun rootNode(): AccessibilityNodeInfo?

    /** 当前前台应用的包名。取不到时返回 null。 */
    fun foregroundPackage(): String?

    /**
     * 截图。回调在**主线程**。
     *
     * ⚠️ 失败的原因要能被区分 —— "没有截图权限"与"两次截图间隔太短"
     *    给用户的下一步完全不同（前者去开权限，后者只需等一会儿）。
     */
    fun screenshot(onResult: (Result<Bitmap>) -> Unit)
}

/**
 * [A11ySource] 的**空实现** —— 无障碍完全不可用时使用。
 *
 * ⚠️ 它**不返回假数据**（不返回一个空树），而是如实报告"读不到"。
 *    区别很重要：空树会被上层解读成"这个界面没有可操作元素"，
 *    而真实情况是"我们压根没有读的能力" —— 两者要采取的行动完全相反
 *    （前者报告用户，后者引导用户去开权限）。
 */
object UnavailableA11ySource : A11ySource {

    override fun rootNode(): AccessibilityNodeInfo? = null

    override fun foregroundPackage(): String? = null

    override fun screenshot(onResult: (Result<Bitmap>) -> Unit) {
        onResult(Result.failure(IllegalStateException("无障碍服务未连接，无法截图")))
    }
}
