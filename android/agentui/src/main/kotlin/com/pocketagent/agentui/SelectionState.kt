package com.pocketagent.agentui

/**
 * **勾选联动** —— 多选列表里"全选 / 半选 / 单选"的一致性。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个逻辑单独成文件，是因为它在界面上有 5 种错法
 * ═══════════════════════════════════════════════════════════════
 *
 * 看起来只是"几个 checkbox 同步一下"，实际每一处都能错：
 *
 *  1. **半选态画成全选。** 选了两项中的一项，全选框打了勾 ——
 *     用户以为全选了，然后删掉了不该删的。
 *  2. **半选态画成未选。** 反过来同样糟：明明选了，方块是空的。
 *  3. **全选后又取消一项，全选框不更新。** 用户看到"全选仍勾着"，
 *     但他刚才取消了一项。
 *  4. **列表变了（增删项）之后全选态不重算。** 勾全选 → 删一项 →
 *     全选框还是勾的，但实际已经不全选了。
 *  5. **空列表时全选是"全选"还是"未选"。** 空列表全选没有意义，
 *     必须显示未选，否则点一下"全选"什么都没发生。
 *
 * ⇒ 这些全部是**纯判断**，所以能离线测（见 `SelectionStateTest`）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么用 Set<String> 而不是 List<Boolean>
 * ═══════════════════════════════════════════════════════════════
 *
 * 列表会增删。用下标对齐的布尔数组，删一项之后所有下标错位 ——
 * 用户的勾选会**跑到别的项上**，而他不会注意到，直到删错东西。
 *
 * ★ 用 id 集合：增删项时勾选跟着 id 走，天然正确。
 *   代价是要在渲染时 `id in selected`（一次哈希查找，可忽略）。
 */
object SelectionState {

    /**
     * 一个多选列表的勾选快照。
     *
     * @param selected 已勾选的 id。**只装当前列表里真实存在的 id** ——
     *   见 [normalize]。
     * @param total 当前列表的总项数
     */
    data class Snapshot(
        val selected: Set<String>,
        val total: Int,
    ) {
        init {
            require(total >= 0) { "总数不能为负：$total" }
        }

        /** 勾了几项。 */
        val count: Int get() = selected.size

        /**
         * 全选框该画成什么样。
         *
         * ⚠️ 空列表必须是 [TriState.OFF] 而不是 [TriState.ON]：
         *   "全部 0 项都选中了"在数学上成立（空真），但在界面上毫无意义，
         *   而且点一下"全选"什么都没发生 —— 那看起来就是坏了。
         */
        val allState: TriState
            get() = when {
                total == 0 -> TriState.OFF
                selected.size >= total -> TriState.ON
                selected.isEmpty() -> TriState.OFF
                else -> TriState.PARTIAL
            }

        /** 能不能"全选"（空列表不能）。 */
        val canSelectAll: Boolean get() = total > 0

        /** 能不能"取消全选"（一项都没选时不能）。 */
        val canClearAll: Boolean get() = selected.isNotEmpty()
    }

    /**
     * 复选框的三态。
     *
     * ⚠️ 必须是三个值，不能用 `Boolean?`：Compose 的 `TriStateCheckbox`
     *   确实用 `ToggleableState?`，但那个 null 的语义在调用点读起来
     *   像"未知"，而这里表达的是"部分选中"。名字不同，含义差很远。
     */
    enum class TriState { OFF, PARTIAL, ON }

    fun empty(total: Int = 0): Snapshot = Snapshot(emptySet(), total)

    /**
     * 勾/取消一项。
     *
     * ⚠️ 不在列表里的 id 会被**忽略**，不是报错：
     *   用户点了某一个 item 的复选框，而在那一帧之后列表刚好刷新掉了它 ——
     *   这是正常竞态，不该让界面崩。
     */
    fun toggle(current: Snapshot, id: String, presentIds: Collection<String>): Snapshot {
        if (id !in presentIds) return current
        val next = if (id in current.selected) current.selected - id else current.selected + id
        return Snapshot(next, current.total)
    }

    /**
     * 点全选框。
     *
     * @param presentIds 当前列表里所有项的 id（不是 [Snapshot.total]）
     * @return 全选态 ⇒ 全部选中；否则 ⇒ 全部清空
     *
     * ★ 为什么"半选"时点它变成**全选**而不是清空：
     *   这是各家系统的通行做法（半选 → 全选 → 清空 → 全选…）。
     *   反过来（半选 → 清空）会让用户连点两次才达到目的。
     */
    fun toggleAll(current: Snapshot, presentIds: Collection<String>): Snapshot = when (current.allState) {
        TriState.ON -> Snapshot(emptySet(), presentIds.size)
        else -> Snapshot(presentIds.toSet(), presentIds.size)
    }

    /**
     * 列表变了之后重算快照。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 这个方法防的是第 4 种错法（最容易漏的那一种）
     * ═══════════════════════════════════════════════════════════
     *
     * 勾选全选 → 列表里**删掉一项** → 如果不重算：
     *   · `selected` 里还留着那个已删除的 id（幽灵）
     *   · `total` 变小了，但 `selected.size` 没变 ⇒ 仍显示"全选"
     *   · 而实际可见的项里有一项没被勾（因为总数和选中数都各少了一个）
     *
     * 更糟的是幽灵 id：它会被当成"用户选中了一项看不见的东西"，
     * 传给下一步（删除/导出）时出错。
     *
     * ⇒ 每次列表变化都调它：**裁掉不存在的 id**，同时更新 total。
     *
     * ⚠️ 裁掉之后 `selected.size` 会变小 —— 这是对的，因为那些项
     *   确实已经不在了。"全选态"随之自动重算（见 [Snapshot.allState]）。
     */
    fun normalize(current: Snapshot, presentIds: Collection<String>): Snapshot {
        val alive = presentIds.toSet()
        val kept = current.selected.filter { it in alive }.toSet()
        return Snapshot(kept, presentIds.size)
    }

    /**
     * 给界面用的一句话摘要（"已选 3 / 12"）。
     *
     * ★ 空列表要说"没有可选的"，不是"已选 0 / 0" ——
     *   后者读起来像故障。
     */
    fun summary(snapshot: Snapshot): String = when {
        snapshot.total == 0 -> "没有可选的"
        snapshot.count == 0 -> "共 ${snapshot.total} 项"
        else -> "已选 ${snapshot.count} / ${snapshot.total}"
    }
}
