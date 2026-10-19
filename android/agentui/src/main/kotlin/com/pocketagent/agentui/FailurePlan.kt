package com.pocketagent.agentui

/**
 * 失败之后给什么。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 判据：出口 ≥ 2，且**至少一个出口当下就能走**
 * ═══════════════════════════════════════════════════════════════
 *
 * 只给一句报错原文，用户唯一能做的是关掉它 —— 而"关掉"不是出口，是放弃。
 * 更糟的是三个出口都写着"请稍后重试"：那不是三个出口，是同一个出口说三遍。
 */
object FailurePlan {

    /**
     * 按门给出出口。
     *
     * ★ 三条铁律：
     *  1. 安全拒绝**没有**"重试" —— 重试必然再次被拒，给了就是在骗用户去撞墙；
     *  2. 用户拒绝**必须**有"重新授权" —— 决定权在他手上，他得能改；
     *  3. 执行失败**必须**有"我来" —— 技术问题不该让用户干等。
     */
    fun exitsFor(gate: Gate): List<Exit> = when (gate) {
        Gate.SAFETY -> listOf(
            Exit.Alternative("换一种不需要这一步的做法"),
            Exit.TakeOver,
        )
        Gate.PERMISSION -> listOf(
            Exit.Alternative("重新授权这一步"),
            Exit.Alternative("换一种不需要这一步的做法"),
            Exit.TakeOver,
        )
        Gate.EXECUTION -> listOf(
            Exit.Retry,
            Exit.Alternative("换一种定位方式（用 node_id 或坐标）"),
            Exit.TakeOver,
        )
    }

    /**
     * 失败卡标题。
     *
     * ★ 三种门的话术**必须不同**，因为它们指向三个完全不同的下一步：
     *   规则不让做（找他没用）/ 你自己没让做（你能改）/ 没做成（可以重试）。
     */
    fun headline(gate: Gate): String = when (gate) {
        Gate.SAFETY -> "这一步我不能做"
        Gate.PERMISSION -> "你刚才没有允许这一步"
        Gate.EXECUTION -> "没成功"
    }

    /**
     * 可能原因（最多三条）。
     *
     * ★ 空列表是**合法**的：宁可不猜，也不要列一堆与本次无关的可能性 ——
     *   猜错方向再给一条错误提示，比不给更糟。
     */
    fun likelyCauses(gate: Gate): List<String> = when (gate) {
        Gate.SAFETY -> listOf("当前页面属于敏感场景（支付 / 密码 / 验证码）")
        Gate.PERMISSION -> listOf("这一步需要你同意后才能做")
        Gate.EXECUTION -> listOf("网络断了", "被弹窗挡住了", "按钮位置变了")
    }

    /** 接管时给用户的操作提示 —— "我来"不能是甩手。 */
    fun takeoverHint(gate: Gate): String = when (gate) {
        Gate.SAFETY -> "这一步需要你自己完成。做完告诉我，我接着来。"
        Gate.PERMISSION -> "你可以手动完成这一步，或者回到设置里允许它。"
        Gate.EXECUTION -> "你先手动弄一下，弄完告诉我结果，我接着往下走。"
    }
}
