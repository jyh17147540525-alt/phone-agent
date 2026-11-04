package com.pocketagent.rootlogic

/**
 * 把 [TierPlan] 摊平成「一页能直接画出来」的形状。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这件事要放在纯模块里
 * ═══════════════════════════════════════════════════════════════
 *
 * 档位页最容易被写坏的地方不是布局，是**措辞与状态的一致性**：
 * 一个能力明明是「缺无障碍」，被画成「失败」；或者显示「可用」而实际
 * 走的是要占屏的降级路径。这类 bug 在 Compose 里几乎测不到，
 * 所以判定与文案全部留在这里（纯 Kotlin、可跑单元测试），
 * 界面文件只做「读 [TierReport] → 画」。
 *
 * ⚠️ 这里**不重新判断能力** —— 判定永远来自 [TierRouter]。
 *    本文件只做三件事：套标题、选文案、排顺序。
 */
enum class TierRowState {
    /** 这一趟真的能执行。 */
    READY,

    /** 缺一个用户点几下就能给的授权（无障碍 / 悬浮窗 / 录屏 / Magisk）。 */
    NEED_PERMISSION,

    /** 只有 root 能给，而现在是普通档 —— 不是错误，是档位差异。 */
    NEED_ROOT,

    /** 两条路都还没实现（跟权限无关）。 */
    NOT_IMPLEMENTED,
}

/**
 * 一行能力。
 *
 * [pathLabel] 与 [occupiesScreen] 是这一页**最该被看见**的两列 ——
 * 用户选档位时真正关心的就是「它要不要占我的屏幕」。
 */
data class TierRow(
    val capabilityId: String,
    val title: String,
    val state: TierRowState,
    val pathLabel: String,
    val occupiesScreen: Boolean,
    /** 缺什么 / 为什么不行的**一句话**。 */
    val hint: String?,
    /** 怎么开。只对 NEED_PERMISSION / NEED_ROOT 有值。 */
    val guide: String?,
)

/** 页面上能直接点的动作。界面层负责把 [target] 映射成 Intent。 */
data class TierAction(
    val label: String,
    val target: TierActionTarget,
)

enum class TierActionTarget {
    OPEN_ACCESSIBILITY,
    OPEN_OVERLAY,
    OPEN_ROOT_MANAGER,
    RE_PROBE,
}

/**
 * 整页内容。
 *
 * [headline] 说「你现在是哪一档」，[subline] 说「这一档下还有什么是不能做的」——
 * 两句话分开是因为它们回答的是不同问题，合并成一句会变成谁都不想读的长句。
 */
data class TierReport(
    val headline: String,
    val subline: String,
    val rows: List<TierRow>,
    val actions: List<TierAction>,
) {
    val readyCount: Int get() = rows.count { it.state == TierRowState.READY }
    val blockedCount: Int get() = rows.size - readyCount
}

object TierReports {

    /**
     * @param mode 用户**选择**的档位（不是实际生效的 —— 实际生效由
     *   [TierRouter] 在每条能力上单独算，这里只用来选中/普通两套文案）。
     * @param plan 由 `TierPlanner.plan(...)` 算出来的装配结果。
     * @param probe 最近一次 root 探测；null = 还没探测过。
     */
    fun build(mode: PrivilegeMode, plan: TierPlan, probe: RootProbeResult?): TierReport {
        val rows = CapabilityMatrix.all.map { capability ->
            rowOf(capability, plan)
        }

        return TierReport(
            headline = headlineOf(mode, probe),
            subline = sublineOf(mode, rows),
            rows = rows,
            actions = actionsOf(mode, plan, probe),
        )
    }

    private fun rowOf(capability: ModeGatedCapability, plan: TierPlan): TierRow {
        val mounted = plan.mounted.firstOrNull { it.capabilityId == capability.id }
        if (mounted != null) {
            return TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.READY,
                pathLabel = pathLabelOf(mounted.path),
                occupiesScreen = mounted.occupancy != Occupancy.NONE,
                // 降级时那句 note 必须原样带出来：它说的正是「这一趟要占屏」。
                hint = mounted.note.takeIf { mounted.degraded },
                guide = null,
            )
        }

        val blocked = plan.blocked.firstOrNull { it.capabilityId == capability.id }
            ?: return TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.NOT_IMPLEMENTED,
                pathLabel = NO_PATH,
                occupiesScreen = false,
                // ★ 两种"没有实现"差得很远，不能写同一句话：
                //   · 连 import 都没有入口（能力层可能早就写好了，只是没有工具绑定）
                //   · 工具在，但两条路都不通（那才是真的没实现）
                //   把前者说成"还没实现"，会让人以为要重写一遍能力层。
                hint = if (ToolBindings.all.any { it.capabilityId == capability.id }) {
                    "这一条还没有可用的实现"
                } else {
                    "能力层已就绪，但还没有工具入口 —— agent 暂时调用不到"
                },
                guide = null,
            )

        return when (val verdict = blocked.verdict) {
            is Verdict.NeedPermission -> TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.NEED_PERMISSION,
                pathLabel = NO_PATH,
                occupiesScreen = false,
                hint = "缺「${verdict.permission.label}」",
                guide = verdict.permission.guide,
            )

            is Verdict.NeedRoot -> TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.NEED_ROOT,
                pathLabel = NO_PATH,
                occupiesScreen = false,
                hint = "需要 root",
                guide = verdict.why,
            )

            Verdict.NotImplemented -> TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.NOT_IMPLEMENTED,
                pathLabel = NO_PATH,
                occupiesScreen = false,
                hint = "这一条还没有可用的实现",
                guide = null,
            )

            is Verdict.Ready -> TierRow(
                capabilityId = capability.id,
                title = capability.title,
                state = TierRowState.READY,
                pathLabel = pathLabelOf(verdict.path),
                occupiesScreen = verdict.occupancy != Occupancy.NONE,
                hint = verdict.note.takeIf { verdict.degraded },
                guide = null,
            )
        }
    }

    /**
     * ⚠️ 三句话对应三种**不同**的处境，不能合并：
     * · 选了 root 且 su 通 → 用户拿到的是他想的那个东西；
     * · 选了 root 但 su 不通 → 他以为自己在满血，其实在降级（必须点明）；
     * · 普通档 → 大部分功能能用，但每次动手都要占屏（这是代价，不是故障）。
     */
    private fun headlineOf(mode: PrivilegeMode, probe: RootProbeResult?): String = when (mode) {
        PrivilegeMode.ROOT -> if (probe is RootProbeResult.Available) {
            "root 档：满血"
        } else {
            "root 档：root 没拿到"
        }

        PrivilegeMode.NON_ROOT -> "普通档"
    }

    private fun sublineOf(mode: PrivilegeMode, rows: List<TierRow>): String {
        val ready = rows.count { it.state == TierRowState.READY }
        if (ready == rows.size) return "全部 ${rows.size} 条能力都能用。"

        val clauses = buildList {
            val needsPermission = rows.count { it.state == TierRowState.NEED_PERMISSION }
            val needsRoot = rows.count { it.state == TierRowState.NEED_ROOT }
            val noEntry = rows.count { it.state == TierRowState.NOT_IMPLEMENTED }

            if (needsPermission > 0) {
                add("另有 $needsPermission 条只差你点一下授权")
            }
            if (needsRoot > 0) {
                add(
                    if (mode == PrivilegeMode.NON_ROOT) {
                        "还有 $needsRoot 条要 root 才解锁 —— 它们不是出错，是这一档没有"
                    } else {
                        "还有 $needsRoot 条要先把 root 授权给它"
                    },
                )
            }
            if (noEntry > 0) {
                add("另有 $noEntry 条还没有工具入口（能力层已就绪，agent 暂时调用不到）")
            }
        }

        return "${rows.size} 条里 $ready 条能用；${clauses.joinToString("；")}。"
    }

    /**
     * 只给「用户此刻能做的下一步」。
     *
     * ⚠️ 「重新探测」永远排在最后：它不是解决授权的手段，只是刷新一次结论。
     */
    private fun actionsOf(
        mode: PrivilegeMode,
        plan: TierPlan,
        probe: RootProbeResult?,
    ): List<TierAction> {
        val missing = plan.blocked
            .mapNotNull { (it.verdict as? Verdict.NeedPermission)?.permission }
            .toSet()

        val actions = buildList {
            if (Permission.ACCESSIBILITY in missing) {
                add(TierAction("打开无障碍设置", TierActionTarget.OPEN_ACCESSIBILITY))
            }
            if (Permission.OVERLAY in missing) {
                add(TierAction("允许显示在其他应用上层", TierActionTarget.OPEN_OVERLAY))
            }
            if (mode == PrivilegeMode.ROOT && probe !is RootProbeResult.Available) {
                // 选了 root 但没通：点这里去 Magisk/root 管理器放行。
                add(TierAction("去 root 管理器里放行", TierActionTarget.OPEN_ROOT_MANAGER))
            }
            add(TierAction("重新检测", TierActionTarget.RE_PROBE))
        }
        return actions
    }

    private const val NO_PATH = "—"

    private fun pathLabelOf(path: ExecPath): String = path.label
}
