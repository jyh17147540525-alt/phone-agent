package com.pocketagent.personalogic

/**
 * **关系推进节奏** —— 把 [RelationshipStage] 的轮次阈值按用户的节奏偏好换算。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件存在的直接原因
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户问："这个 app 里真的能与角色深度聊 r18 吗？"
 *
 * 查出来的第二个死结：`AppContainer` 用**写死的轮次**判阶段 ——
 * `ACQUAINTED_TURNS = 30`、`CLOSE_TURNS = 150`、`BONDED_TURNS = 400`。
 * 而 `RelationshipStage` 那一段的注释明确说"关系阶段决定你能说到哪一步"。
 *
 * ⇒ 于是"能深度聊"这件事被换算成了 **150 轮对话**。
 *   150 轮是什么量级：一天聊 10 轮也要半个月，而且中途换会话、
 *   清历史、重装都会归零（这一点刚刚才修，见 `totalUserTurns`）。
 *   用户看到的结果就是：开关开着，但它永远在"我们还不到那一步"。
 *
 * ⚠️ 而 `:personalogic` 里**早就有** `StageSpeed`（慢/标准/快），
 *   有完整实现、有离线测试，**零生产引用** —— 和 `RelationshipStage`
 *   当初"定义完整但没人读"是同一个毛病。
 *
 * ⇒ 这里做的是：让节奏偏好**真的影响阈值**，并且由用户选。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 换算方向：倍率作用在**阈值**上，不是"额外加分"
 * ═══════════════════════════════════════════════════════════════
 *
 * `StageSpeed.apply` 的语义是"每次互动应得的点数乘倍率"（用于亲密度积分）。
 * 而这里的阶段判据是**纯轮次**，没有积分。两者要能互相对应：
 *
 *   快 2.0 倍 ⇒ 走完同样的路只需要**一半**轮次 ⇒ 阈值 ÷ 2
 *   慢 0.5 倍 ⇒ 需要**两倍**轮次               ⇒ 阈值 ÷ 0.5
 *
 * ⚠️ 所以是除法。写成乘法会让"选了快反而更慢" —— 那种 bug 不会报错，
 *   只会让用户觉得"这个设置没用"。
 */
object StagePacing {

    /** 默认按「标准」算。 */
    val DEFAULT_SPEED: StageSpeed = StageSpeed.NORMAL

    /**
     * 换算后的轮次阈值。
     *
     * @param base 原始阈值（标准节奏下的轮次）
     * @param speed 用户选的节奏
     * @return 实际使用的阈值，**至少 1** ——
     *   0 会让"第一轮就到亲近"，那不是调快，那是没有过程。
     */
    fun threshold(base: Int, speed: StageSpeed = DEFAULT_SPEED): Int {
        if (base <= 0) return 0
        val scaled = (base / speed.multiplier).toInt()
        return scaled.coerceAtLeast(1)
    }

    /**
     * 当前进度（0.0..1.0）—— 给界面显示"还差多少"。
     *
     * ⚠️ 为什么界面需要它：这个项目最忌讳"不报错的假动作"。
     *    用户开了开关、聊了几轮，如果界面什么都不显示，
     *    他无法区分"在推进"和"根本没动"。
     *
     * @return 1.0 = 已经到这一档；0.0 = 刚开始
     */
    fun progress(turns: Int, base: Int, speed: StageSpeed = DEFAULT_SPEED): Double {
        val target = threshold(base, speed)
        if (target <= 0) return 1.0
        return (turns.toDouble() / target).coerceIn(0.0, 1.0)
    }

    /**
     * 还差多少轮。
     *
     * @return 0 = 已经够了
     */
    fun remaining(turns: Int, base: Int, speed: StageSpeed = DEFAULT_SPEED): Int =
        (threshold(base, speed) - turns).coerceAtLeast(0)

    /**
     * 给用户看的一句进度说明。
     *
     * ★ 刻意说**具体数字**，不说"多聊聊就好了"：
     *   用户问"为什么它还没跟我熟"时，我得能给出一个他能验证的答案 ——
     *   这也是 `ACQUAINTED_TURNS` 当初选"轮次"而不是"感情分"的理由。
     */
    fun describe(
        turns: Int,
        base: Int,
        speed: StageSpeed = DEFAULT_SPEED,
        nextStageLabel: String,
    ): String {
        val left = remaining(turns, base, speed)
        return if (left == 0) {
            "已经到「$nextStageLabel」了。"
        } else {
            "再有 $left 轮就到「$nextStageLabel」（现在 $turns 轮，标准节奏需要 ${threshold(base, speed)} 轮）。"
        }
    }
}
