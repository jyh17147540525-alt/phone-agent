package com.pocketagent.memory

import com.pocketagent.memorylogic.AtomStore
import com.pocketagent.memorylogic.Fidelity
import com.pocketagent.memorylogic.MemoryDecay
import com.pocketagent.memorylogic.MemoryRecall
import com.pocketagent.personalogic.MemoryFidelity
import com.pocketagent.personalogic.MemoryItem
import timber.log.Timber

/**
 * **记忆的读取端** —— 把原子库变成"这一轮该想起的几句话"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：在这之前，助理**什么都不记得**
 * ═══════════════════════════════════════════════════════════════
 *
 * `MemoryWriter` 一直在写（`AtomExtractor` → `MemoryGate` → `FileAtomStore`），
 * 但**没有任何人读回来** —— `AssistantPromptBuilder` 里固定是
 * `memories = emptyList()`。
 *
 * ⇒ 后果很具体：换个会话，它连你叫什么都不知道。用户花了很久建立的东西，
 *   在提示词里一个字都不存在。
 *
 * ⚠️ 这比"忘记"严重得多：**忘不掉的前提是记得住**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它只做三件事，判断全在 `:memorylogic`
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. 从库里读全部原子
 * 2. 按**这一句话**排序（`MemoryRecall.rank`）
 * 3. 给每条标上"有多清楚"（`MemoryDecay.fidelityOf`）
 *
 * ⚠️ 所有排序/衰减/阈值逻辑都在 `:memorylogic` 里，因为那边能被离线
 *    验证器钉住。本文件在 `:app`，**一行判断都不该有** ——
 *    写在这里的判断没人能测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 必须不抛
 * ═══════════════════════════════════════════════════════════════
 *
 * 记忆读失败不该让对话起不来。读不出来就是"这一轮什么都没想起来"，
 * 而那是**用户能理解的状态**；抛异常让整轮对话失败，他只会以为应用坏了。
 */
class MemoryReader(
    private val store: AtomStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 一轮最多想起几条。⚠️ 太多会让提示词变成流水账。 */
    private val limit: Int = DEFAULT_LIMIT,
) {

    /**
     * 这一轮该想起什么。
     *
     * @param query 用户这一句。空串 = 没有线索（此时只有"约束"类会冒出来）
     */
    fun recall(query: String): List<MemoryItem> = runCatching {
        val atoms = store.loadAll()
        if (atoms.isEmpty()) return emptyList()

        val now = clock()
        val ranked = MemoryRecall.rank(query = query, atoms = atoms, nowMs = now, limit = limit)

        val items = ranked.mapNotNull { atom ->
            when (val f = MemoryDecay.fidelityOf(atom, now)) {
                // ⚠️ 已经忘了的**不出现**。留着它会让提示词越来越长，
                //    而模型会把"其实想不起来的事"当成事实说出来。
                Fidelity.GONE -> null
                Fidelity.EXACT -> MemoryItem(
                    id = atom.id,
                    text = atom.text,
                    score = atom.confidence,
                    fidelity = MemoryFidelity.EXACT,
                )
                Fidelity.HEDGED -> MemoryItem(
                    id = atom.id,
                    text = atom.text,
                    score = atom.confidence,
                    fidelity = MemoryFidelity.HEDGED,
                )
            }
        }

        if (items.isNotEmpty()) {
            val hedged = items.count { it.fidelity == MemoryFidelity.HEDGED }
            Timber.i("记忆召回：%d 条（其中 %d 条模糊）", items.size, hedged)
        }
        items
    }.getOrElse { e ->
        // 读失败 = 这一轮什么都没想起来。**不抛** —— 见类注释。
        Timber.w(e, "记忆召回失败（本轮按无记忆处理）")
        emptyList()
    }

    companion object {
        /** 一轮最多想起几条。5 条大约是"说得出但不会喧宾夺主"的量。 */
        const val DEFAULT_LIMIT = 5
    }
}
