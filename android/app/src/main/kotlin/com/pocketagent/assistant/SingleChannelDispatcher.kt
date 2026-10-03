package com.pocketagent.assistant

import com.pocketagent.action.ActionDispatcher
import com.pocketagent.action.ActionExecutor
import com.pocketagent.action.ActionResult
import com.pocketagent.action.ChannelStatus
import com.pocketagent.action.ExecutorChannel
import com.pocketagent.action.UiAction
import timber.log.Timber

/**
 * 动作调度器 —— **当前只有 `ACCESSIBILITY` 一条通道**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么现在只做单通道，而不是把四条通道的降级链一次做完
 * ═══════════════════════════════════════════════════════════════
 *
 * 降级链的价值是"这条不行换那条"。但**现在只有一条是通的**
 * （Shizuku / IME / OVERLAY_PROMPT 三个通道都还没有实现）。
 *
 * 写一条只有一条腿的降级链，等于写一堆**永远不会执行的分支** ——
 * 而那些分支不会被任何测试覆盖，只会在将来某天被人当成"已经支持了"。
 * **这正是本项目反复出现的「安静地少做一件事」的另一种形态：
 * 不是少做了，是多了些看起来做过的空壳。**
 *
 * ⇒ **接口按多通道设计**（`ActionDispatcher` 本来就是），**实现先单通道**。
 *    加第二条通道时，改的是这个类，不是接口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 全失败时返回 [ActionResult.NeedUserIntervention]，不是 Failed
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者对用户的含义完全不同：
 * - `Failed` = "出错了，可以重试"
 * - `NeedUserIntervention` = **"这件事需要你来做"**
 *
 * 通道不可用属于后者：重试一万次也不会变，用户去开个权限就好了。
 * 把它们混成一个，会让模型一直重试一个它改不了的状态。
 */
class SingleChannelDispatcher(
    private val executor: ActionExecutor,
) : ActionDispatcher {

    override suspend fun dispatch(action: UiAction): ActionResult {
        if (!executor.isAvailable()) {
            return ActionResult.NeedUserIntervention(
                com.pocketagent.action.UserInterventionReason.PERMISSION_DENIED,
                "无障碍服务未连接 —— 请到系统设置的「辅助功能」里开启本应用，然后重试。",
            )
        }

        return try {
            executor.perform(action)
        } catch (e: Exception) {
            // ⚠️ `ActionExecutor` 的契约要求"异常不得外抛"，但**契约是给实现方的**，
            //    而这里要防的是"某个实现违反了契约"。调度器是最后一道，
            //    它不该因为下面某条通道没守规矩就把整条链路炸掉。
            Timber.w(e, "通道 ${executor.channel} 执行时抛异常（违反契约）")
            ActionResult.Failed(executor.channel, "动作执行时发生内部错误。")
        }
    }

    override fun channelStatus(): Map<ExecutorChannel, ChannelStatus> {
        val available = executor.isAvailable()
        return mapOf(
            executor.channel to ChannelStatus(
                available = available,
                detail = if (available) {
                    "已连接（优先级 ${executor.priority()}，支持 ${executor.supportedActions().size} 种动作）"
                } else {
                    "未连接 —— 需要在系统设置中开启辅助功能"
                },
            ),
        )
    }
}
