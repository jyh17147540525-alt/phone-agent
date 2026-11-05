package com.pocketagent.agentlogic

/**
 * 检查点里「已完成的那几步分别是什么动作」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么会有这个文件（2026-11-05 真机崩溃）
 * ═══════════════════════════════════════════════════════════════
 *
 * 崩溃栈：
 *
 *   java.lang.IllegalArgumentException: completedSteps(2) 不能超过计划步数(1)
 *     at com.pocketagent.agentlogic.TaskCheckpoint.<init>(TaskCheckpoint.kt:117)
 *     at com.pocketagent.ui.tasks.ContainerChatPort$send$…(ChatPort.kt:753)
 *
 * 调用方当时写的是：
 *
 *   completedSteps = snap.step,
 *   plannedActions = listOf(snap.action),   // ← 恒为 1 条
 *
 * `snap.step` 是"已完成几步"，会一路涨；而 `plannedActions` 永远只有一条。
 * 第二步就撞上 `TaskCheckpoint.init` 里的
 * `require(completedSteps <= plannedActions.size)`。
 *
 * ⚠️ 它**没有**变成"这一轮失败"：`save` 跑在 `launch` 里，
 *    `catch (e: Exception)` 接不到子协程的异常 ⇒ 直接把进程带走。
 *    角色扮演很少真动手（纯聊天根本不进工具循环），所以这颗雷一直没响；
 *    工作模式强制 `Act` 之后，第一次干活就踩了。
 *
 * ⚠️ 修法**不是**放宽那个 `require` —— 它是这条数据结构的真不变式：
 *    "已完成步数不可能超过计划步数"。要修的是**喂进去的数据**。
 *
 * ⇒ 把每一步真跑过的动作名按顺序攒下来，长度与步数对齐。
 *   重规划时 `step` 可能一次跳好几步（1 → 3），用当前动作补齐。
 *
 * 纯函数、无状态：这样"对齐"这件容易写错的小事能被离线测试钉住，
 * 而不是等下一次装机才在崩溃日志里看见它。
 */
object CheckpointActions {

    /**
     * 追加一条已完成动作，返回**新的**列表。
     *
     * @param previous 之前攒下的动作名（老 → 新）
     * @param completedSteps 循环报上来的"已完成步数"
     * @param action 本步的动作枚举名
     *
     * 保证：返回值长度 == `completedSteps`（只多不少 —— `completedSteps`
     * 比记录短时按已有条数返回，因为"退回去"这种事不该由这里发明）。
     */
    fun record(
        previous: List<String>,
        completedSteps: Int,
        action: String,
    ): List<String> {
        if (completedSteps <= 0) return previous
        if (previous.size >= completedSteps) return previous
        val out = ArrayList<String>(completedSteps)
        out.addAll(previous)
        // 重规划会一次跳好几步。补齐用的是**同一步的动作名**：
        // 我们只知道"现在这一步做了什么"，编不出中间那几步做过什么。
        while (out.size < completedSteps) out.add(action)
        return out
    }
}
