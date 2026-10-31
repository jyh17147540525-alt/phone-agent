package com.pocketagent.memory

import com.pocketagent.memorylogic.Atom
import com.pocketagent.memorylogic.AtomExtractor
import com.pocketagent.memorylogic.AtomStore
import com.pocketagent.memorylogic.MemoryGate
import com.pocketagent.memorylogic.MemoryLlmPort
import com.pocketagent.memorylogic.Turn
import timber.log.Timber

/**
 * **记忆的写入端** —— 把一段对话变成几条原子记忆。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 接线之前的状态：三段都写好了，但没有一段被调用
 * ═══════════════════════════════════════════════════════════════
 *
 * `AtomExtractor`（写）、`MemoryGate`（筛）、`MemoryRecall`（读）全部实现完毕
 * 且都有离线测试，但**没有任何一行生产代码引用它们** ——
 * 于是助理至今零记忆：换个会话，它连"你叫什么"都不记得。
 *
 * 本文件是这三段之间的**唯一那条线**。它只做编排，不做判断 ——
 * 所有判断（该不该记、记成什么类、怎么合并）都在 `:memorylogic` 里，
 * 因为那边能被离线验证器钉住。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条不能省的约束
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不阻塞对话**。抽取是一次额外的模型调用（秒级）。放在回复路径里
 *    等于让用户多等一次 —— 而它产出的东西这一轮用不上。
 * 2. **失败不影响对话**。抽取挂了就是"这次没记住"，不该让刚才那句回复变红。
 * 3. **幂等**。同一段对话被抽两次不会变成两条 —— `Atom.idOf` 是
 *    [类别 + 归一化文本] 的哈希，`AtomStore.mergeIn` 按 id 合并。
 *    这条很重要：它让我们**不需要**维护"已经抽到哪了"的水位线，
 *    而水位线一旦漂移就是"某几轮永远抽不到"。
 */
class MemoryWriter(
    private val llm: MemoryLlmPort,
    private val store: AtomStore,
    private val gate: MemoryGate = MemoryGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 一次抽取的结果。**带回来给日志用** —— 静默的成功和静默的失败一样糟。 */
    data class Result(
        val kept: Int,
        val dropped: Int,
        val mergedIn: Int,
        val total: Int,
    ) {
        /** 这一轮什么都不用记：模型说没有值得记的事。 */
        val nothing: Boolean get() = kept == 0 && mergedIn == 0
    }

    /**
     * 从这些轮次里抽记忆并落库。
     *
     * @param turns 建议传**当前会话的当前路径**（不是全库）——
     *   被用户放弃的分支不该变成记忆，那等于"记得用户否掉的那个版本"。
     * @throws Exception 交给调用方兜（见类注释第 2 条）
     */
    fun absorb(
        turns: List<Turn>,
        /**
         * 被输出守标记为**破环**的轮次 id（来自 `:personalogic/GuardLedger`）。
         *
         * ═══════════════════════════════════════════════════════════
         *  ★★ 2026-10-31：这个参数从"永远空集"变成真的能用
         * ═══════════════════════════════════════════════════════════
         *
         * 原来这里硬编码 `emptySet()`，注释写着"如实传空，而不是假装
         * 检查过了" —— 那个诚实是对的，但它意味着**记忆闸门的第一道闸
         * 从来没起过作用**。
         *
         * ⚠️ 而 `MemoryGate` 的类注释把这道闸称为"四道防线里
         *    **最容易被忽略、后果最重**的一道"：
         *    一次 OOC 被写进长期记忆，下次会被当作**先例引用** ——
         *    崩坏就从"这一次说错了"固化成"它本来就是这样的"。
         *
         * ⇒ 改成参数传入。默认空集保持向后兼容（老调用方不用改），
         *   但真正的调用点会传 `GuardLedger.oocTurnIds()`。
         */
        oocTurnIds: Set<String> = emptySet(),
    ): Result {
        // ── 第一道闸：筛轮次 ──────────────────────────────────
        val screened = gate.screenTurns(turns, oocTurnIds = oocTurnIds)
        if (screened.admitted.size < MIN_TURNS) {
            Timber.d("记忆抽取跳过：可用轮次 %d < %d", screened.admitted.size, MIN_TURNS)
            return Result(kept = 0, dropped = 0, mergedIn = 0, total = store.loadAll().size)
        }

        val known: List<Atom> = store.loadAll()
        val now = clock()

        // ── 抽取（一次模型调用）────────────────────────────────
        val report = AtomExtractor(llm).extract(screened.admitted, known, now)

        // ── 第二道闸：筛原子 ──────────────────────────────────
        val gated = gate.screenAtoms(report.atoms)

        val merged = store.mergeIn(gated.admitted)

        Timber.i(
            "记忆抽取：轮次 %d → 原子 %d（被闸拦下 %d，与已有重复 %d）→ 库里共 %d 条",
            screened.admitted.size,
            gated.admitted.size,
            gated.rejected.size,
            report.mergedCount,
            merged.size,
        )
        // ⚠️ 被丢掉的原因必须进日志：模型返回了一个不认识的类别、
        //    或者一句话没有溯源，这些都不是"没记到"而是"记错了/没敢记"。
        report.dropped.take(5).forEach { Timber.d("丢弃原子：%s —— %s", it.reason.label, it.snippet) }
        gated.rejected.take(5).forEach { Timber.d("闸门拒绝：%s", it.reason) }

        return Result(
            kept = gated.admitted.size,
            dropped = report.dropped.size + gated.rejected.size,
            mergedIn = merged.size,
            total = merged.size,
        )
    }

    companion object {
        /**
         * 少于这么多轮就不值得发一次模型请求。
         *
         * ★ 定 4 的理由：轮次太少时"值得记的事"几乎必然没有，
         *   而每一次抽取都是一次**真实计费**的调用。
         */
        const val MIN_TURNS: Int = 4
    }
}
