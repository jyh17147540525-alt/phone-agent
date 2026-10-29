package com.pocketagent.memorylogic

import kotlin.math.sqrt

/**
 * **星云绽放** —— 记忆团"从中心初始节点向外扩散"的动画节律。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "用户打开角色记忆星云页面，每次 app 启动，记忆星云会从中间初始节点
 *  开始向外扩散。给用户一种记忆加载出来的感觉。"
 *
 * 三件事拆开：
 *  ① **有一个"初始节点"** —— 不是所有点同时出现
 *  ② **从中间向外** —— 扩散有方向，不是随机弹出
 *  ③ **加载感** —— 需要一个短暂的"起手"再铺开
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这段要住在纯 Kotlin 模块里
 * ═══════════════════════════════════════════════════════════════
 *
 * 动画曲线最容易出的错是**看起来能跑但节奏错了**：
 * 一开始就全部出现（等于没动画）、走得太慢（像卡住）、
 * 或者最外圈永远到不了 1（最后几颗星永远在飞）。
 *
 * 这些全都能用数字判出来，所以它必须在 `:memorylogic` 里、
 * 进得了离线验证器 —— 而不是散在 `Canvas` 的 draw 里靠肉眼看。
 *
 * ⚠️ 与 `MemoryGraph` 的职责分工：
 *    那边回答"每个点在哪"（**确定性**，同一个输入永远同一张图）；
 *    这边回答"它什么时候出现在那个位置"（**时间**）。
 *    两者都不碰 Android，所以都能离线断言。
 */
object NebulaBloom {

    /**
     * 种子点亮之后，**先停一下**再铺开。
     *
     * ★ 这个"起手"就是加载感的来源：一颗星先在中间亮起来，
     *   停一瞬，然后整团往外开。没有这一停，就只是"点同时变多"。
     *
     * ⚠️ 120ms 是刻意的短：再长（如 400ms）会让用户觉得卡了。
     *   人在等"东西出来"时，200ms 内没有变化就会开始怀疑没点到。
     */
    const val SEED_HOLD_MS: Long = 120L

    /**
     * 最外圈比种子晚多少开始。
     *
     * ★ 这就是"由内向外"的全部实现 —— 延迟与**离种子的距离**成正比。
     *   于是同一时刻亮起来的那一圈点，是真的排成一个壳层往外推，
     *   而不是看起来像随机打点。
     */
    const val MAX_STAGGER_MS: Long = 520L

    /**
     * 单个点自己的生长时长。
     *
     * ⚠️ 它比 [MAX_STAGGER_MS] 短：让"后开始的点"能追上"先开始的点"，
     *   整团在尾声时几乎同时就位。反过来（单点生长比错峰还长）会得到
     *   "最后一颗星孤零零地飞很久"，收尾很难看。
     */
    const val GROW_MS: Long = 600L

    /** 整段动画总时长。到它就一切都该完全就位。 */
    const val TOTAL_MS: Long = SEED_HOLD_MS + MAX_STAGGER_MS + GROW_MS

    /**
     * 淡入占单个点生长的前几成。
     *
     * ★ 为什么不让 alpha 跟满整个生长：点从中心飞出来的过程中
     *   如果一直是半透明，看起来像"幽灵"。前 35% 淡入、之后全不透明，
     *   才是"逐渐实化"。
     */
    const val FADE_PORTION: Float = 0.35f

    /** 生长起始时的尺寸比例 —— 不是 0，否则第一帧完全看不见，像闪一下。 */
    const val SIZE_AT_BIRTH: Float = 0.55f

    /**
     * 归一化距离。
     *
     * ⚠️ 除以 2：世界坐标归一化在**单位球内**，所以两点最大距离是直径 2。
     *    不除的话"最远的那一圈"只有 0.5 的进度，永远到不了错峰上限 ——
     *    表现为外圈的点几乎是同时冒出来的，扩散感消失。
     */
    fun normalizedDistance(a: MemoryGraph.Node, b: MemoryGraph.Node): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        return (d / 2.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 这个点**什么时候开始**生长。
     *
     * @param isSeed 是不是那个"初始节点"。
     *   它是唯一在 0 时刻就出现的点 —— 这是"从中间初始节点开始"的落地。
     * @param distanceFromSeed 归一化距离 0..1（见 [normalizedDistance]）
     */
    fun startDelayMs(isSeed: Boolean, distanceFromSeed: Float): Long {
        if (isSeed) return 0L
        val d = distanceFromSeed.coerceIn(0f, 1f)
        return SEED_HOLD_MS + (d * MAX_STAGGER_MS).toLong()
    }

    /**
     * 某个点此刻的生长进度。
     *
     * @param elapsedMs 从动画开始到现在
     * @return 0 = 还没出现；1 = 完全就位
     */
    fun progressAt(elapsedMs: Long, startDelayMs: Long): Float {
        val local = elapsedMs - startDelayMs
        if (local <= 0L) return 0f
        return (local.toFloat() / GROW_MS).coerceIn(0f, 1f)
    }

    /**
     * 整段动画是否结束。
     *
     * ★ 用 >= 而不是 ==：帧回调的间隔不固定，正好落在 `TOTAL_MS` 上的
     *   概率极低 —— 判相等会让动画**永远不结束**（一直每帧重绘）。
     */
    fun isFinished(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_MS

    /**
     * 缓出曲线（ease-out cubic）。
     *
     * ★ 为什么是缓出而不是缓入：从中心飞出去的过程要"启动快、末尾稳"。
     *   缓入会得到"先是慢慢挪、然后猛地撞到位"，像物理引擎没调好。
     */
    fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val inv = 1f - x
        return 1f - inv * inv * inv
    }

    /**
     * 位置半径系数：**从中心（0）长到它自己的位置（1）**。
     *
     * ★ 之所以乘在**世界坐标**上而不是在屏幕空间偏移：
     *   世界坐标乘 0 就是球心 —— 于是所有点天然从正中间出发，
     *   而且旋转/缩放同时生效。在屏幕空间做就得自己推投影，两套逻辑必然漂移。
     */
    fun radiusFactor(progress: Float): Float = easeOutCubic(progress)

    /** 点的尺寸系数：出生时略小，就位时是全尺寸。 */
    fun sizeFactor(progress: Float): Float =
        SIZE_AT_BIRTH + (1f - SIZE_AT_BIRTH) * easeOutCubic(progress)

    /** 不透明度：前 [FADE_PORTION] 淡入，之后全不透明。 */
    fun alpha(progress: Float): Float = (progress / FADE_PORTION).coerceIn(0f, 1f)

    /**
     * 这个过程点**能不能被点中**。
     *
     * ⚠️ 不能拿 `progress > 0` 判：刚开始飞、还在中心那一小团的点
     *    会和别的点叠在一起，用户点到的永远不是他看见的那颗。
     *    ⇒ 过半才算"落位可点"。
     */
    fun isTappable(progress: Float): Boolean = progress >= 0.6f
}
