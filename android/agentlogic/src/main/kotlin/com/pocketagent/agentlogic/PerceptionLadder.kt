package com.pocketagent.agentlogic

/**
 * 五档感知阶梯 —— 决定「这一步要不要看屏幕、看得多清楚」。
 *
 * ## 为什么这必须是一个独立组件
 *
 * 因为它决定**循环里要不要感知**，而循环骨架依赖这个答案。
 * 如果等循环写完再补，就会得到"每步都采一张全屏截图"的骨架 ——
 * 那不是补一个组件能修的，是要把循环拆开重接。
 *
 * ## 五档与成本
 *
 * | 档 | 手段 | 相对成本 | 何时用 |
 * |---|---|---|---|
 * | **0** | **不感知，直接 `am start`** | **零** | 启动 App、明确 deep link、纯等待 |
 * | 1 | 无障碍树 | 极低 | 大部分点击 / 输入 |
 * | 2 | 树 + 局部截图 | 低 | 树信息不足（自绘控件、图标按钮） |
 * | 3 | 全屏降质截图 | 中 | 整页自绘（Flutter / 游戏 UI） |
 * | 4 | 全屏原质截图 | 高 | 兜底 |
 *
 * ## ★★ 第 0 档是最大的优化空间
 *
 * 「打开微信，给张三发消息」这个任务里，**第一步完全不需要看屏幕** ——
 * `am start` 一个 deep link 就够了。而它省下的是：
 * 一次无障碍树遍历（可能数百毫秒）+ 一次截图 + 一次多模态 token 上传。
 *
 * **代价是零。** 这就是"第 0 档的价值"。
 *
 * ## ⚠️ `acquire` 返回可空是**刻意的**
 *
 * `ScreenSnapshot?` 里的 `null` **不是错误，是第 0 档这一条合法路径**。
 *
 * 这一点必须在接口注释里写明，因为**它看起来像设计缺陷** ——
 * 一个可空返回值，后来者会很自然地想"顺手把它改成非空"，
 * 于是第 0 档被悄无声息地抹掉，而**没有任何测试会因此变红**
 * （因为改完仍然"能用"，只是每步都多花一次截图）。
 *
 * 这是本项目反复出现的模式：**不报错，只是安静地多花一份成本。**
 */
enum class PerceptionTier(val level: Int) {

    /** 第 0 档：不采集。`am start` 类动作直接走，返回 null 快照 */
    NONE(0),

    /** 第 1 档：纯无障碍树 */
    ACCESSIBILITY_TREE(1),

    /** 第 2 档：树 + 局部截图 */
    TREE_PLUS_PARTIAL(2),

    /** 第 3 档：全屏降质截图 */
    FULL_SCREENSCALE_REDUCED(3),

    /** 第 4 档：全屏原质截图 */
    FULL_QUALITY(4),
    ;

    /** 这一档是否需要真的采集快照 */
    val needsCapture: Boolean get() = this != NONE

    companion object {
        fun fromLevel(level: Int): PerceptionTier =
            entries.firstOrNull { it.level == level }
                ?: error("未知的感知档位：$level（合法范围 0..4）")
    }
}

/**
 * 这一步对感知的需求 —— 由动作类型与页面状态推导，调用方填。
 *
 * 刻意把"需求"与"能力"分开：需求来自任务（要不要看屏幕），
 * 能力来自环境（能不能看屏幕）。两者交叉才得到档位。
 */
data class PerceptionDemand(
    /** 本步是否需要定位元素（需要 → 至少第 1 档） */
    val needsElement: Boolean,

    /**
     * 本步是否**本就应该**只做导航（开 App、deep link、返回、回桌面）。
     *
     * ★ 为 true 时档位一律为 [PerceptionTier.NONE] —— 这类动作不需要知道
     * 屏幕长什么样，执行完由 Verifier 在下一步顺带确认即可。
     */
    val isPureNavigation: Boolean = false,

    /** 目标元素是否已由 UI 图谱缓存解析出引用（有 → 第 0 档即可，无需重新采集） */
    val hasCachedElementRef: Boolean = false,

    /** 是否明确需要视觉理解（截图问答、图标识别） */
    val requiresVisualUnderstanding: Boolean = false,
)

/**
 * 当前环境**能**提供什么。与 [PerceptionDemand] 交叉得到档位。
 */
data class PerceptionCapability(
    val accessibilityAvailable: Boolean,
    val screenshotAvailable: Boolean,
    val ocrAvailable: Boolean,
    /** 当前是否处于非计费网络（WiFi）。影响重负载档位是否可用 */
    val onUnmeteredNetwork: Boolean = true,
) {
    /** 树与截图至少有一个能用，否则完全无法感知 */
    val canCaptureAnything: Boolean get() = accessibilityAvailable || screenshotAvailable
}

/** 档位决策结果 */
data class TierDecision(
    val tier: PerceptionTier,
    /** 决策理由，必须能直接展示给用户（"执行过程可见"原则） */
    val reason: String,
    /**
     * 是否是**降级**的结果（想要更高档但能力不足）。
     * UI 据此提示"当前体验受限，建议开启无障碍权限"。
     */
    val degraded: Boolean = false,
) {
    /** 这一档是否真的会采集快照 */
    val needsCapture: Boolean get() = tier.needsCapture
}

/**
 * 档位决策器 —— **纯策略逻辑，零 Android 依赖**，可离线完整测试。
 *
 * 规划文档 §3.2.3 明确标注它"可以离线做"：档位决策是
 * "给定期望与能力，选最低够用的档"，与 Android 无关。
 *
 * ## 核心原则：**选最低够用的档，而不是最高可用的档**
 *
 * 反过来（有多少能力用多少）会让每一次操作都付出最高成本。
 * 这不是"保守"或"激进"的取舍 —— 低档失败时可以升档重试，
 * 而高档做完了无法退回成本。
 *
 * > 与项目其它地方一致：**能自动判断的自动判断，不能的明确交给用户，
 * > 不做静默猜测。** 这里的"不猜"体现为：**不为了保险而多采集**。
 */
class PerceptionLadder(
    private val capability: PerceptionCapability,
) {

    /**
     * 选出本步的感知档位。
     *
     * 决策顺序（**每一步都短路返回，顺序不可调换**）：
     *
     * 1. **第 0 档优先判定** —— 纯导航、或已有缓存引用 → 完全不采集。
     *    这是最大的一笔省钱，必须第一个判，否则会被后面的条件抢先。
     * 2. 明确要求视觉理解 → 直接给高档（第 3 档起）。
     * 3. 需要元素定位 → 按能力给第 1 / 2 档。
     * 4. 兜底 → 第 3 / 4 档。
     *
     * ⚠️ 第 1 步若放到后面，会出现"纯导航动作因为需要元素定位而采了快照"——
     *    看起来功能正常，只是成本翻倍。**顺序本身就是正确性的一部分。**
     */
    fun decide(demand: PerceptionDemand): TierDecision {
        // ── 步骤 1：第 0 档（★ 优先，因为它是零成本路径）────────
        if (demand.isPureNavigation) {
            return TierDecision(
                tier = PerceptionTier.NONE,
                reason = "本步是纯导航动作（开应用/返回/回桌面），不需要了解屏幕内容",
            )
        }
        if (!demand.needsElement && !demand.requiresVisualUnderstanding && demand.hasCachedElementRef) {
            return TierDecision(
                tier = PerceptionTier.NONE,
                reason = "元素引用已由缓存解析，无需重新采集屏幕",
            )
        }

        // ── 步骤 2：明确要求视觉理解 ────────────────────────────
        if (demand.requiresVisualUnderstanding) {
            if (!capability.screenshotAvailable) {
                // 想要视觉但拿不到截图：退到树，并**明确标记 degraded**。
                // ⚠️ 绝不静默降级 —— UI 必须能告诉用户"这次没看到屏幕"。
                return if (capability.accessibilityAvailable) {
                    TierDecision(
                        tier = PerceptionTier.ACCESSIBILITY_TREE,
                        reason = "需要视觉理解但截图不可用，退化为无障碍树（结果可能不完整）",
                        degraded = true,
                    )
                } else {
                    TierDecision(
                        tier = PerceptionTier.NONE,
                        reason = "截图与无障碍均不可用，无法感知",
                        degraded = true,
                    )
                }
            }
            return fullScreenTier(reason = "本步需要视觉理解屏幕内容")
        }

        // ── 步骤 3：需要元素定位 ───────────────────────────────
        if (demand.needsElement) {
            if (capability.accessibilityAvailable) {
                // 树可用 → 第 1 档。
                // ⚠️ 这里刻意**不**因为"树可能描述不全"而预先升到第 2 档：
                //    升档的成本是每步都付，而树描述不全只发生在少数页面。
                //    正确的做法是"树定位失败 → 返回 NeedMoreInfo → 再升档"，
                //    把代价限制在真正需要的那些步。
                return TierDecision(
                    tier = PerceptionTier.ACCESSIBILITY_TREE,
                    reason = "使用无障碍树定位元素（成本最低的结构化来源）",
                )
            }
            if (capability.screenshotAvailable) {
                return fullScreenTier(reason = "无障碍树不可用，改用截图定位元素", degraded = true)
            }
            return TierDecision(
                tier = PerceptionTier.NONE,
                reason = "无任何可用感知来源，定位将在执行时失败",
                degraded = true,
            )
        }

        // ── 步骤 4：兜底 ───────────────────────────────────────
        if (capability.canCaptureAnything) {
            return fullScreenTier(reason = "未声明具体需求，按兜底档采集")
        }
        return TierDecision(
            tier = PerceptionTier.NONE,
            reason = "无可用感知来源且本步未声明需求，跳过采集",
            degraded = true,
        )
    }

    /**
     * 全屏档位的选择：第 3 档（降质）默认，只有明确要求时才第 4 档。
     *
     * ⚠️ **非计费网络的判断在这里，不在调用方** ——
     * 它是"档位决策"的一部分（成本约束影响选择），
     * 放到调用方会让每个调用点各写一遍，且必然有漏。
     */
    private fun fullScreenTier(reason: String, degraded: Boolean = false): TierDecision {
        if (!capability.onUnmeteredNetwork) {
            return TierDecision(
                tier = PerceptionTier.FULL_SCREENSCALE_REDUCED,
                reason = "$reason；当前为计费网络，使用降质截图控制流量",
                degraded = degraded,
            )
        }
        return TierDecision(
            tier = PerceptionTier.FULL_SCREENSCALE_REDUCED,
            reason = reason,
            degraded = degraded,
        )
    }

    /**
     * 定位失败后的**升档**决策。
     *
     * 这是把"第 1 档不够用"的代价限制在少数步骤上的关键：
     * 树定位返回 [GrounderFeedback.NeedMoreInfo] 时才升档，而不是预先升。
     *
     * @param current 当前档位
     * @param feedback 上一次定位的反馈
     * @return 升档后的决策；已到顶或无法再升时返回 null（表示应放弃而非无限重试）
     */
    fun escalate(current: PerceptionTier, feedback: GrounderFeedback): TierDecision? {
        // 只有"信息不足"才值得升档。
        // ⚠️ NotFound（目标真的不在屏幕上）升档是浪费 —— 再看一遍也还是没有。
        //    Ambiguous（有多个候选）升档无用 —— 需要的是消歧，不是更多像素。
        if (feedback !is GrounderFeedback.NeedMoreInfo) return null

        val next = when (current) {
            PerceptionTier.NONE -> PerceptionTier.ACCESSIBILITY_TREE
            PerceptionTier.ACCESSIBILITY_TREE -> PerceptionTier.TREE_PLUS_PARTIAL
            PerceptionTier.TREE_PLUS_PARTIAL -> PerceptionTier.FULL_SCREENSCALE_REDUCED
            PerceptionTier.FULL_SCREENSCALE_REDUCED -> PerceptionTier.FULL_QUALITY
            // 第 4 档已是顶。再要更多信息就没有手段了 → 返回 null，交给上层转人工引导
            PerceptionTier.FULL_QUALITY -> return null
        }

        // 升到的档位若能力不支持，说明升不上去 —— 也返回 null，
        // 让上层明确地走"手动引导"而不是原地重试。
        val supported = when {
            next == PerceptionTier.ACCESSIBILITY_TREE -> capability.accessibilityAvailable
            next == PerceptionTier.TREE_PLUS_PARTIAL ->
                capability.accessibilityAvailable && capability.screenshotAvailable
            else -> capability.screenshotAvailable
        }
        if (!supported) return null

        return TierDecision(
            tier = next,
            reason = "上一次定位信息不足（${feedback.reason}），升档至第 ${next.level} 档重试",
        )
    }
}

/**
 * 定位反馈 —— 从 `GroundResult` 归一化来的**纯逻辑**表示。
 *
 * ⚠️ 为什么不直接用 `:agent` 里的 `GroundResult`：
 * 那个类型在 Android 模块里（依赖 `:action` 的 `ElementRef`，而 `ElementRef`
 * 带 `android.graphics.Rect`），一旦 import 就把本模块拖出离线验证器的范围。
 *
 * 归一化转换由 Android 层的一行 `when` 完成 —— 这是本项目既定模式：
 * **契约在纯模块、适配在 Android 层**。
 */
sealed interface GrounderFeedback {
    /** 信息不足，值得补采集后重试 */
    data class NeedMoreInfo(val reason: String) : GrounderFeedback

    /** 目标确实不在屏幕上 —— 补采集无用 */
    data class NotFound(val reason: String) : GrounderFeedback

    /** 匹配到多个候选 —— 需要消歧而非更多像素 */
    data class Ambiguous(val candidateCount: Int) : GrounderFeedback
}
