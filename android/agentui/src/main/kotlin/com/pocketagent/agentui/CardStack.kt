package com.pocketagent.agentui

/**
 * **叠卡展开** —— 一堆卡片摞在一起，点一下散开。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的是"卡片太多，一屏放不下"
 * ═══════════════════════════════════════════════════════════════
 *
 * 一次任务会产生十几张卡（意图/动作/观察/说话）。平铺的话：
 *   · 用户得滚很久才能看完一次任务
 *   · 更要紧的是**看不出这十几张是一次任务**（它们看起来是独立事件）
 *
 * ⇒ 摞起来：叠卡本身表达"这几个是一伙的"，展开才看细节。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条会被用户直接感觉到的规则
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **叠起来时最多露 N 张的边**。露 3 张是"这里有一叠"；
 *    露 10 张就是"一堆卡片往右下角歪"——后者是脏，不是设计。
 *    见 [STACK_VISIBLE]。
 *
 * 2. **展开后不能把整屏顶跑**。20 张卡全展开会把当前阅读位置推走，
 *    用户找不到刚才在看什么。⇒ 展开有上限，超出的折叠成"还有 N 张"。
 *
 * 3. **撤销/收起之后要回到原来的滚动位置**。这条是纯 UI 的，
 *    但状态上要能回答"展开前是哪一张卡在顶部"。
 */
object CardStack {

    /** 叠起来时露出几张卡的边。 */
    const val STACK_VISIBLE: Int = 3

    /** 展开后最多平铺几张，超出的折叠。 */
    const val EXPAND_LIMIT: Int = 8

    /**
     * 一堆卡。
     *
     * @param ids 这一叠里的卡 id，**按时间升序**（最早的在前）
     */
    data class Stack(val ids: List<String>) {
        val size: Int get() = ids.size

        /** 值不值得叠起来。 */
        val worthStacking: Boolean get() = ids.size >= STACK_VISIBLE
    }

    /** 展开后的样子。 */
    data class Expanded(
        /** 真正平铺出来的 id */
        val shown: List<String>,
        /** 因为超限被折叠的条数。0 = 全展开了 */
        val hiddenCount: Int,
    ) {
        val isTruncated: Boolean get() = hiddenCount > 0
    }

    /**
     * 这个 stacks 该不该叠起来。
     *
     * ⚠️ 少于 [STACK_VISIBLE] 张时**不该叠**：两张卡做成叠卡，
     *   用户要多点一次才能看到第二张 —— 那是倒退。
     */
    fun shouldStack(stack: Stack): Boolean = stack.worthStacking

    /**
     * 展开。
     *
     * @param focusId 用户点击的那张卡。★ 它必须**出现在 [Expanded.shown] 里** ——
     *   否则用户点的是第 12 张、而展开只显示前 8 张，他会以为点击没生效。
     */
    fun expand(stack: Stack, focusId: String? = null): Expanded {
        if (stack.size <= EXPAND_LIMIT) {
            return Expanded(shown = stack.ids, hiddenCount = 0)
        }

        // ⚠️ focusId 在超限区时，要**把它换进可见区**，而不是只显示前 N 张。
        //    见上面注释：忽略它等于让用户点了没反应。
        val focusAt = focusId?.let { stack.ids.indexOf(it) }?.takeIf { it >= 0 }
        if (focusAt != null && focusAt >= EXPAND_LIMIT) {
            val head = stack.ids.take(EXPAND_LIMIT - 1)
            return Expanded(
                shown = head + stack.ids[focusAt],
                hiddenCount = stack.size - EXPAND_LIMIT,
            )
        }

        return Expanded(
            shown = stack.ids.take(EXPAND_LIMIT),
            hiddenCount = stack.size - EXPAND_LIMIT,
        )
    }

    /**
     * 展开时第 i 张卡的视觉偏移（叠起来时的错位量）。
     *
     * ★ 纯函数返回比例而不是 dp：dp 换算要 density，那属于 UI 层。
     *   这里给 0..1 的归一化值，UI 层乘上一个基准即可。
     *
     * @return 每张卡相对"基准位置"的偏移比例。超过 [STACK_VISIBLE] 的返回 null（不画）
     */
    fun stackOffset(index: Int): Float? {
        if (index < 0 || index >= STACK_VISIBLE) return null
        // 越靠后的卡偏移越大：露出的"边"逐张下移。
        return index * 0.04f
    }

    /**
     * 叠起来时的透明度（越靠后越淡）。
     *
     * ⚠️ 最后一张不能淡到看不见 —— 用户会以为那一叠只有两张。
     *   下限 0.55 是量出来的：再淡就开始"消失"。
     */
    fun stackAlpha(index: Int): Float? {
        if (index < 0 || index >= STACK_VISIBLE) return null
        val raw = 1f - index * 0.18f
        return raw.coerceAtLeast(0.55f)
    }

    /**
     * 叠起来时的缩放（越靠后越小）。
     *
     * ★ 1.0 → 0.96 → 0.92：每张缩 4%。这个量级刚好能看出层次，
     *   又不会让最后一张看起来是"另一张卡"。
     */
    fun stackScale(index: Int): Float? {
        if (index < 0 || index >= STACK_VISIBLE) return null
        return 1f - index * 0.04f
    }

    /**
     * 把一长串卡片**按"一次任务"分组**。
     *
     * ★ 分组的依据是 `Card.Id` 的会话/任务前缀，而不是时间：
     *   时间相邻的两张卡可能属于不同任务（用户连着跑两件事）。
     *   见 [groupKeyOf]。
     */
    fun groupByTask(ids: List<String>): List<Stack> =
        ids.groupBy { groupKeyOf(it) }
            .values
            .map { Stack(it) }
            .sortedBy { stack -> ids.indexOf(stack.ids.first()) }

    /**
     * 一张卡属于哪一组。
     *
     * ⚠️ `CardId` 的格式是调用方定的，这里只做**最大兼容**的解析：
     *   取最后一个 ':' 之前的部分；没有 ':' 就整串当作组。
     *
     * 为什么这样够用且安全：如果将来 id 格式变了（比如加了更多段），
     * 这段会退化成"每个 id 自己一组" —— 叠卡不生效，但**不会错组**。
     * 错组比不分组糟得多（会把两件事的卡摞在一起）。
     */
    fun groupKeyOf(id: String): String {
        val at = id.lastIndexOf(':')
        return if (at > 0) id.substring(0, at) else id
    }
}
