package com.pocketagent.agentui

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round

/**
 * **惯性滚轮** —— 松手之后继续滑，并且停在整格上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户说的"不够 iOS"，很可能有一半在这里
 * ═══════════════════════════════════════════════════════════════
 *
 * Android 的列表 fling 和 iOS 的手感差异，**不在动画曲线，在物理参数**：
 *
 * | | Android 默认 | iOS |
 * |---|---|---|
 * | 松手后滑行距离 | 短 | 长得多 |
 * | 减速 | 快（约 0.998/ms） | 慢（拖尾很长） |
 * | 到底时 | 硬停 / 边缘光 | 弹性回弹 |
 *
 * 用户感觉"不够丝滑"，很多时候是**松手就停**那种"被拽住"的感觉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 但本项目不该只抄参数，要分清两种列表
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **长内容列表**（聊天、设置）：要**长滑行**。用户是在"翻"，
 *    一屏一屏地过，滑得远才省力。
 *
 * 2. **滚轮/选择器**（时长、档位）：要**吸附**。滑到哪就停在哪一格，
 *    不吸附的话用户永远选不准。
 *
 * ⚠️ 把两者混成一套参数，必然有一种场景不对：
 *   · 长列表加吸附 ⇒ 滑到一半被"吸"到某一行，非常难受
 *   · 滚轮加长滑行 ⇒ 转三圈才停，选不准
 *
 * ⇒ 见 [Profile]：两套预设，调用方按用途选。
 *
 * ★ 全段纯 Kotlin，可离线测（见 `WheelFlingTest`）。
 */
object WheelFling {

    /**
     * 手感预设。
     *
     * @param friction 每毫秒的速度衰减系数。越接近 1 滑得越远。
     * @param minVelocity 低于这个速度就停（px/s）。太小会滑很久才停。
     * @param snapToItem 松手后是否吸附到整格
     */
    data class Profile(
        val friction: Double,
        val minVelocity: Float,
        val snapToItem: Boolean,
    ) {
        init {
            require(friction > 0.0 && friction < 1.0) { "摩擦系数必须在 (0,1)：$friction" }
            require(minVelocity > 0f) { "停止阈值必须为正：$minVelocity" }
        }
    }

    /**
     * **长内容列表** —— 接近 iOS 的拖尾。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 2026-10-31：参数是**算出来的**，不是拍脑袋定的
     * ═══════════════════════════════════════════════════════════
     *
     * 第一版写了 `friction=0.9995, minVelocity=40`，看着"很 iOS"。
     * 但离线测试当场算出了一个荒谬的结果：
     *   1000 px/s 的滑动要滑 **6.4 秒**（而 iOS 是 1~1.6 秒）。
     *
     * ⚠️ 这个错误很典型：`friction` 越接近 1，滑行时间**指数级**增长。
     *    0.999 → 0.9995 看起来只差 0.0005，时长却翻了一倍多。
     *    凭"感觉"调这个数必然出错，必须用 `t = ln(minV/v) / ln(f)` 反算。
     *
     * 正确的组合（1000 px/s 下）：
     * ```
     *   friction=0.999, minVelocity=200  ⇒  t=1.6s, d≈1000px
     *   friction=0.999, minVelocity=300  ⇒  t=1.2s, d≈1000px
     * ```
     * ⇒ 取 `friction=0.999, minVelocity=200`：正好压在 1.6 秒上限，
     *   滑行距离约 1000px（一屏的 40%），是"能滑出去但不停不下来"的量级。
     *
     * ⚠️ `minVelocity` 不能再低：它直接决定"什么时候算停了"。
     *   40 那种值会让每一次轻甩都滑好几秒 —— 用户以为失控。
     */
    val CONTENT: Profile = Profile(
        friction = 0.999,
        minVelocity = 200f,
        snapToItem = false,
    )

    /**
     * **滚轮/选择器** —— 短滑行 + 吸附。
     *
     * 摩擦更大（停得快），因为用户的目的地就在附近；滑太远反而要往回找。
     * `0.998` + `minVelocity=400`：1000 px/s 下约 0.9 秒、350px，
     * 正好是"拨几格就停"的手感。
     */
    val WHEEL: Profile = Profile(
        friction = 0.998,
        minVelocity = 400f,
        snapToItem = true,
    )

    /**
     * 算松手后的总滑行距离。
     *
     * 用等比级数求和：`v * (friction / (1 - friction))` 的离散形式。
     *
     * ⚠️ 这是**估算**（假设帧间隔均匀），真实滑行由 Compose 的
     *   `splineBasedDecay` 驱动。它的用途是：
     *     · 单测可断言"两套预设的滑行距离差多少"
     *     · 需要预先知道终点时（比如判断会不会滑到底）
     */
    fun estimateDistance(velocity: Float, profile: Profile = CONTENT): Float {
        val v = abs(velocity)
        if (v <= profile.minVelocity) return 0f
        // 每毫秒速度乘 friction ⇒ 总距离 = v * friction / (1 - friction)
        // 1/1000 是把"每毫秒"换成"每秒"的速度单位补偿
        val d = v * (profile.friction / (1.0 - profile.friction)) / 1000.0
        return d.toFloat()
    }

    /**
     * 松手后理论上要滑多久（毫秒）。
     *
     * ★ 用途：判断"这次 fling 会不会太长"。超过 [MAX_FLING_MS] 时
     *   应当截断速度 —— 用户不想等一个 4 秒的滑行。
     */
    fun estimateDurationMs(velocity: Float, profile: Profile = CONTENT): Long {
        val v = abs(velocity)
        if (v <= profile.minVelocity) return 0L
        // 解 v * f^t = minVelocity ⇒ t = ln(minV/v) / ln(f)
        val t = ln(profile.minVelocity / v.toDouble()) / ln(profile.friction)
        return t.toLong().coerceAtLeast(0L)
    }

    /**
     * fling 时长上限。超过就截断初速度。
     *
     * ★ 1.6 秒：iOS 的 fling 大致在这个量级。再长用户会觉得"停不下来"。
     */
    const val MAX_FLING_MS: Long = 1_600

    /**
     * 按上限截断初速度。
     *
     * ⚠️ 截断而不是"到时间强行停"：后者会让最后的减速突然中断，
     *   看起来像被掐住。截断初速度则是**从一开始就滑得慢一点**，
     *   整条减速曲线仍然连续。
     */
    fun clampVelocity(velocity: Float, profile: Profile = CONTENT): Float {
        val dur = estimateDurationMs(velocity, profile)
        if (dur <= MAX_FLING_MS) return velocity
        // 反解：想让时长正好是 MAX，需要多大的 v
        //
        // ⚠️ `f.pow(-MAX)` 在 f<1 时是**正的大指数**（因为负负得正），
        //    所以它必然大于 1，`target` 也必然大于 `minVelocity` —— 数学上没问题。
        //    第一版把这里写成 `pow(f, -MAX)` 用了静态调用，编译不过是另一回事。
        val f = profile.friction
        val target = profile.minVelocity * f.pow(-MAX_FLING_MS.toDouble())
        val sign = if (velocity < 0) -1f else 1f
        val clampedMag = target.toFloat()
        // ⚠️ 用 min 而不是 coerceIn：这里只需要"不超过原速度"，
        //    `coerceIn(-abs(v), abs(v))` 在 v 为负时会错误地把 -clamped 变成 -clamped
        //    （两者相同，但语义糊）。直接 min 更清楚。
        val mag = kotlin.math.min(clampedMag, abs(velocity))
        return mag * sign
    }

    /**
     * 把位置吸附到最近的整格。
     *
     * @param position 当前滚动位置（px）
     * @param itemSize 一格的尺寸（px）
     * @return 吸附后的位置
     *
     * ⚠️ `itemSize <= 0` 时原样返回：调用方传错不该让界面跳到 0。
     */
    fun snapToNearest(position: Float, itemSize: Float): Float {
        if (itemSize <= 0f) return position
        val idx = round(position / itemSize)
        return idx * itemSize
    }

    /**
     * 吸附要走的距离（给动画用）。
     *
     * ★ 单独给"距离"而不是"目标"：Compose 的 `animateFloatAsState`
     *   要的是目标值，而 `Animatable.snapTo/animateTo` 也要目标 ——
     *   但如果调用方想做"回弹"效果，它需要知道距离来决定弹性。
     */
    fun snapDelta(position: Float, itemSize: Float): Float =
        snapToNearest(position, itemSize) - position

    /**
     * 这一格离中心多远（0 = 正中，1 = 一整格）。
     *
     * ★ 滚轮要做"中间亮、两边淡"就是这个值 —— 它让"选中感"可见。
     */
    fun distanceFromCenter(position: Float, itemSize: Float): Float {
        if (itemSize <= 0f) return 0f
        val d = snapDelta(position, itemSize)
        return (abs(d) / itemSize).coerceIn(0f, 1f)
    }
}
