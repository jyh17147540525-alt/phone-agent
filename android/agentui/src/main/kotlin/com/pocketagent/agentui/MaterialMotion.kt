package com.pocketagent.agentui

import kotlin.math.abs

/**
 * **Material 3 的 motion token 官方值** —— 用来核对本项目的曲线，并防止注释腐烂。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：核对出一个**说错了话**的注释
 * ═══════════════════════════════════════════════════════════════
 *
 * `PaEasing.Emphasized` 的注释原本写着：
 *
 * > 这个值对应 **Material 3 的 `emphasizedDecelerate`**
 * > （`cubic-bezier(0.16, 1, 0.3, 1)`）—— **已交叉核对过** Material 的
 * > motion token 规格，不是随手写的美术数字。
 *
 * ⚠️ 去查了 M3 规范的实现（Flutter `material/motion.dart`，
 *    它逐值实现了 M3 的 easing token）：`emphasizedDecelerate` 是
 *    **`Cubic(0.05, 0.7, 0.1, 1.0)`**，不是 `(0.16, 1, 0.3, 1)`。
 *
 * ⇒ 那句"已交叉核对过"是**错的**。
 *   `(0.16, 1, 0.3, 1)` 是一条广泛使用的 ease-out 曲线，
 *   但它**不是** M3 的任何 token。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这个错误值得单独写一个文件来防
 * ═══════════════════════════════════════════════════════════════
 *
 * 注释里的"已核对"是最有腐蚀性的一类错误：
 *  - 它让后来的人**不再去核对**
 *  - 它一旦错了，会让后续所有基于它的推理一起错
 *  - 而它**不会在编译期、运行时、或任何测试里暴露**
 *
 * ⇒ 把官方值写成常量 + 测试断言"哪个对得上、哪个对不上"。
 *   曲线本身**没有改** —— 它们是视觉调过的，改观感需要真机验证。
 *   这里只做一件事：**停止说假话**。
 *
 * 来源：[M3 easing tokens](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs)，
 *      数值取自 [Flutter `material/motion.dart`](https://github.com/flutter/flutter/blob/main/packages/flutter/lib/src/material/motion.dart)
 *      对规范的实现（逐个 token 可查）。
 */
object MaterialMotion {

    /**
     * 一条三次贝塞尔缓动曲线的控制点。
     *
     * 与 CSS `cubic-bezier(x1, y1, x2, y2)` 同序。
     */
    data class Curve(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {

        /** 控制点上的最大绝对差 —— 用来判断"这两条曲线差多少"。 */
        fun distanceTo(other: Curve): Float = maxOf(
            abs(x1 - other.x1),
            abs(y1 - other.y1),
            abs(x2 - other.x2),
            abs(y2 - other.y2),
        )

        /** 四个控制点是否完全一致。 */
        fun sameAs(other: Curve): Boolean = distanceTo(other) == 0f
    }

    // ─────────────────────────────────────────────────────────────
    //  M3 easing token（官方实现里的值）
    // ─────────────────────────────────────────────────────────────

    /** `easing-standard` —— 温和进出。 */
    val STANDARD = Curve(0.2f, 0.0f, 0.0f, 1.0f)

    /** `easing-standard-decelerate` —— 慢起、匀速收。 */
    val STANDARD_DECELERATE = Curve(0.0f, 0.0f, 0.0f, 1.0f)

    /** `easing-standard-accelerate` —— 慢起、快走。 */
    val STANDARD_ACCELERATE = Curve(0.3f, 0.0f, 1.0f, 1.0f)

    /** `easing-emphasized-accelerate` —— 强调进场起步段。 */
    val EMPHASIZED_ACCELERATE = Curve(0.3f, 0.0f, 0.8f, 0.15f)

    /**
     * `easing-emphasized-decelerate` —— 强调进场收束段。
     *
     * ⚠️ **这个值是本文件最重要的内容**：它证明项目里那条
     *    `(0.16, 1, 0.3, 1)` 不是它。
     */
    val EMPHASIZED_DECELERATE = Curve(0.05f, 0.7f, 0.1f, 1.0f)

    /** 全部 token，便于遍历核对。 */
    val all: Map<String, Curve> = mapOf(
        "standard" to STANDARD,
        "standardDecelerate" to STANDARD_DECELERATE,
        "standardAccelerate" to STANDARD_ACCELERATE,
        "emphasizedAccelerate" to EMPHASIZED_ACCELERATE,
        "emphasizedDecelerate" to EMPHASIZED_DECELERATE,
    )

    /**
     * 找出与给定曲线**最接近**的 M3 token。
     *
     * @return token 名与距离。距离 > [MATCH_TOLERANCE] 时说明它不在 M3 体系里。
     */
    fun closestTokenTo(curve: Curve): Pair<String, Float> =
        all.entries
            .minByOrNull { it.value.distanceTo(curve) }
            ?.let { it.key to it.value.distanceTo(curve) }
            ?: ("" to Float.MAX_VALUE)

    /**
     * 判定"算不算同一条曲线"的容差。
     *
     * ★ 1e-4：控制点是两位小数的定点值，浮点比较必须有容差。
     *   不设容差会让 `0.2f` 与 `0.20000001f` 判成不同 ——
     *   那种测试会随机红。
     */
    const val MATCH_TOLERANCE: Float = 1e-4f

    /** 这条曲线是否**就是**某个 M3 token。 */
    fun isAToken(curve: Curve): Boolean =
        all.values.any { it.distanceTo(curve) <= MATCH_TOLERANCE }

    // ─────────────────────────────────────────────────────────────
    //  M3 duration token（官方值，毫秒）
    // ─────────────────────────────────────────────────────────────

    /**
     * M3 的时长阶梯。
     *
     * ⚠️ 注意它们是 **50ms 为步长的等差数列**：
     *   short1..4 = 50/100/150/200
     *   medium1..4 = 250/300/350/400
     *   long1..4 = 450/500/550/600
     *
     * ⇒ 本项目的 180 / 280 / 380 / 520 **都不在这个阶梯上**。
     *   那不代表它们错 —— 它们是按时长用途命名的自定值，
     *   但**不该**被描述成"遵循 M3 时长"。
     */
    val DURATIONS: Map<String, Int> = mapOf(
        "short1" to 50, "short2" to 100, "short3" to 150, "short4" to 200,
        "medium1" to 250, "medium2" to 300, "medium3" to 350, "medium4" to 400,
        "long1" to 450, "long2" to 500, "long3" to 550, "long4" to 600,
    )

    /** 这个时长是否正好落在 M3 阶梯上。 */
    fun isADurationToken(ms: Int): Boolean = DURATIONS.values.any { it == ms }

    /**
     * 与给定时长最近的 M3 token。
     *
     * @return token 名与相差毫秒数
     */
    fun closestDurationTo(ms: Int): Pair<String, Int> =
        DURATIONS.entries
            .minByOrNull { abs(it.value - ms) }
            ?.let { it.key to abs(it.value - ms) }
            ?: ("" to Int.MAX_VALUE)
}
