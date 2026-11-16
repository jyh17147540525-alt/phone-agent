package com.pocketagent.personalogic

/**
 * **表演指导的持有者**：过闸、保留、过期。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么单独一个类（而不是继续放在 `AppContainer` 里）
 * ═══════════════════════════════════════════════════════════════
 *
 * 这段逻辑原本是 `AppContainer.runBackgroundTask` 里的十来行：
 * "过闸 → 接受就存 → 拒了就留旧的 → 读的时候判有效期"。
 *
 * 问题不在代码长短，在**它测不了** —— `AppContainer` 要 `Context`，
 * 而 `:app` 的单测跑不起真 Android 运行时。于是这条链里最容易错的
 * 三件事（拒了要不要清空、过期算不算数、能不能重新生效）**零覆盖**。
 *
 * ⇒ 抽成纯 Kotlin 类，时钟注入，8 条测试钉住，`AppContainer` 只剩一句
 *   `performanceSlot.offer(...)`。
 *
 * @param clock 时钟（毫秒）。测试里注入，生产用 `System.currentTimeMillis`。
 */
class PerformanceSlot(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 最近一次被接受的那条。
     *
     * ⚠️ 它**可能就是过期的** —— 读的人必须走 [current]，别直接看这个字段。
     *    保留过期值是为了两件事：日志里能说"上一条是什么"，以及
     *    [raw] 的排查用途。
     */
    @Volatile
    private var value: Performance? = null

    /** 当前**有效**的那条；过期返回 null（调用方据此回退到旧文案）。 */
    fun current(): Performance? = value?.takeIf { it.isFresh(clock()) }

    /** 上一轮那句 —— 给导演看，避免"换个说法重说一遍"。过期就不给了。 */
    fun lastLine(): String? = current()?.line

    /**
     * 收一条新的。
     *
     * ⚠️ 三条规则（都各自有测试）：
     * 1. **接受才替换**；被拒时保留旧的 —— 「拒了」不等于「清空」，
     *    否则模型偶尔写一句垃圾就把这一轮的表演指导弄没了。
     * 2. 防抖基准只用**未过期**的旧值。过期之后模型重新写出同一句
     *    （状态确实没变）必须能重新生效，否则一条指导在 20 分钟后会永久失效。
     * 3. 空值走的是"拒"，同样不清空 —— 大多数轮次本来就没有指导。
     *
     * @param raw 模型给的 `performance` 字段（可能 null / 空 / 很长）
     * @param hormonesKey 当前行为档位（[Hormones.portrait]）
     */
    fun offer(raw: String?, hormonesKey: String): PerformanceVerdict {
        val now = clock()
        val live = value?.takeIf { it.isFresh(now) }
        val verdict = PerformanceGate.accept(
            raw = raw,
            previous = live,
            nowMs = now,
            hormonesKey = hormonesKey,
        )
        if (verdict is PerformanceVerdict.Accepted) value = verdict.performance
        return verdict
    }

    /** 未判有效期的原始持有值。**只给日志与测试用。** */
    fun raw(): Performance? = value

    /** 清空（例如换人格 / 换会话）。 */
    fun clear() {
        value = null
    }
}
