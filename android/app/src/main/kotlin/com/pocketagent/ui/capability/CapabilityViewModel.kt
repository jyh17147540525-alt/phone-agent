package com.pocketagent.ui.capability

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.capability.AndroidSettingsAccess
import com.pocketagent.capability.PendingShellRunner
import com.pocketagent.capability.SafDirectoryGrants
import com.pocketagent.capability.SafFileChannel
import com.pocketagent.capabilitylogic.CallOrigin
import com.pocketagent.capabilitylogic.Capability
import com.pocketagent.capabilitylogic.CapabilityAuditEvent
import com.pocketagent.capabilitylogic.CapabilityAuditEvents
import com.pocketagent.capabilitylogic.CapabilityAuditLog
import com.pocketagent.capabilitylogic.CapabilityCall
import com.pocketagent.capabilitylogic.CapabilityCatalog
import com.pocketagent.capabilitylogic.CapabilityGuard
import com.pocketagent.capabilitylogic.CapabilityOutcome
import com.pocketagent.capabilitylogic.CapabilityRisk
import com.pocketagent.capabilitylogic.CapabilityRunner
import com.pocketagent.capabilitylogic.CapabilityRuntime
import com.pocketagent.capabilitylogic.CapabilityVerdict
import com.pocketagent.filelogic.FileScope
import com.pocketagent.filelogic.ScopeRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「第 0 档能力」页的状态持有者。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一页的全部意义：**让用户真的能执行一次**
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，裁决 / 规划 / 通道 / 编排四层都已经就位并有测试，但**没有任何
 * 界面能调用它们** —— 也就是说整套东西对用户来说等于不存在。
 * 这一页是那个缺口。
 *
 * ⚠️ 它**不做任何判定** —— 判定全在 [CapabilityGuard]（纯模块、有测试）。
 *    这里只做三件事：收集参数、调用 `execute`、把结论放进 StateFlow。
 *    任何"我觉得这个应该放行"的写法都会把安全模型从这一层拆掉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 放行记录**只在本次运行内有效**（刻意）
 * ═══════════════════════════════════════════════════════════════
 *
 * 授权记录本该落库（"可撤销、有记录"是项目原则 8 的要求），但持久化那一步
 * 还没做。这里的处理是**不假装**：界面上明确写着"重启应用后需要重新放行"。
 *
 * 反过来做（比如写进内存后界面上不说）会更糟：用户放行过一次、
 * 第二天发现要重新放行，而**没有任何东西解释为什么**。
 *
 * ⚠️ 也不因为"反正不持久化"就把 [isGranted] 放宽成永远 `true` ——
 *    那样这一页就变成了一个绕过第 6 步的后门，而它看起来只是在"方便调试"。
 */
class CapabilityViewModel(
    settingsAccess: AndroidSettingsAccess,

    /**
     * SAF 授权。**它是这一页状态的一部分**，不是下层的一个细节 ——
     * 用户刚选完目录，这一页必须立刻知道（否则"选完点了却说还没授权"）。
     *
     * ⚠️ 真相来源是系统的 `persistedUriPermissions`，见 [SafDirectoryGrants]。
     *    所以 [refreshGrants] 是"重新问系统一遍"，不是"读我们自己的缓存"。
     */
    private val safGrants: SafDirectoryGrants,

    /** T0-D 文件通道。见 [SafFileChannel] 的类注释（含它够不到什么）。 */
    fileChannel: SafFileChannel,
) : ViewModel() {

    private val catalog = CapabilityCatalog()

    /**
     * 审计日志。**不传 sink** —— 落库出口还没接。
     *
     * ⚠️ 这一页必须把它显示出来：它是用户判断"这个能力刚才到底动了什么"
     *    的唯一线索，而 [CapabilityRuntime] 已经保证同一句话不会刷屏。
     */
    private val auditLog = CapabilityAuditLog()

    /** 本次运行内被放行的能力 id。 */
    private val granted = MutableStateFlow<Set<String>>(emptySet())

    /**
     * ⚠️ [isGranted] 读的是 `granted.value`，所以它在**每次裁决时**重新取值 ——
     *    这正是"放行之后必须重新裁决一次"能成立的前提（见 `CapabilityGuard`）。
     *    SAFE 默认放行由 `CapabilityGuard` 的默认实现给出，这里显式写出来
     *    只是为了让"默认放行"这件事在这一页上也读得见。
     */
    private val guard = CapabilityGuard(
        catalog = catalog,
        isGranted = { capability ->
            capability.risk == CapabilityRisk.SAFE || capability.id in granted.value
        },
    )

    private val runtime = CapabilityRuntime(
        guard = guard,
        runner = CapabilityRunner(
            settings = settingsAccess,
            // ⚠️ shell 通道**没有传 null**：传 null 会让判定层说"没有接上通道"
            //    （指向一个用户找不到的开关），而事实是"我们还没实现"。
            //    见 PendingShellRunner 的类注释。
            shell = PendingShellRunner(),

            // ★ T0-D：文件通道 + 用户当前的授权范围。
            //
            // ⚠️ `fileScope` 传的是 **lambda** 而不是一个算好的 `FileScope`：
            //    用户随时可能在这一页上授权或撤销一个目录，而这个 ViewModel
            //    活得比任何一次操作都长。传值的后果是「刚撤销的目录仍然可写」，
            //    一直持续到下次重建 ViewModel —— 而那种现象看起来
            //    像"撤销按钮没生效"。见 CapabilityRunner 里那个参数的注释。
            files = fileChannel,
            fileScope = { FileScope(roots = safGrants.grantedRoots()) },
        ),
        auditLog = auditLog,
    )

    data class UiState(
        /** 默认放行、可以直接跑的那些 */
        val safe: List<Capability>,
        /** 需要用户逐项放行的那些 */
        val guarded: List<Capability>,
        val grantedIds: Set<String>,
        /** 最近一次调用的入参 —— 「放行后再来一次」要用它。 */
        val lastCall: CapabilityCall?,
        val last: CapabilityOutcome?,
        /** 非空时界面弹确认框。⚠️ 确认后**必须重新裁决**，见 [confirm] */
        val confirm: CapabilityVerdict.RequireConfirmation?,
        val events: List<CapabilityAuditEvent>,

        /** 用户已经授权给我们的目录。文件类能力只能在这些目录里动东西。 */
        val grantedDirs: List<ScopeRoot>,

        /**
         * 授权了、但**我们用不了**的目录。
         *
         * ⚠️ 必须显示出来。默默忽略的后果是：用户明明授权了一个网盘目录，
         *    界面上却什么都没有 —— 他会以为按钮坏了，然后反复授权。
         *    而真相是"只支持手机存储里的目录"，那是一句能说清的话。
         */
        val unsupportedDirs: List<Uri>,

        /**
         * 目录授权本身出问题时的说明。
         *
         * ★ 刻意**不**复用 [last]（那是 [CapabilityOutcome]）：
         *   那个类型的每个字段都在描述**裁决**（被谁拦下、能不能降级），
         *   而这里发生的事跟裁决无关。硬塞进去会让色调、下一步按钮
         *   全按"被拒绝"渲染 —— 而用户其实只是要再选一次目录。
         */
        val grantError: String? = null,
    )

    private val _ui = MutableStateFlow(readState())

    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 参数对话框的目标能力。`null` = 不显示。 */
    private val _editing = MutableStateFlow<Capability?>(null)

    val editing: StateFlow<Capability?> = _editing.asStateFlow()

    fun edit(capability: Capability) {
        _editing.value = capability
    }

    fun cancelEdit() {
        _editing.value = null
    }

    /**
     * 执行一次。
     *
     * ⚠️ `origin` 是 [CallOrigin.USER]：这一条是**用户在界面上亲手点的**。
     *    它不是 AGENT（不是模型决定的），也不是 SCHEDULE ——
     *    而审计日志要靠这个字段回答"这条操作是凌晨自动做的，还是有人看着做的"。
     */
    fun run(capability: Capability, args: Map<String, String>) {
        _editing.value = null
        execute(CapabilityCall(capabilityId = capability.id, args = args, origin = CallOrigin.USER))
    }

    /**
     * 用户点了确认。
     *
     * ⚠️⚠️ **重新裁决一次**，而不是拿上次那个 `Allowed` 直接执行。
     *    把裁决结果缓存下来、确认后直接执行，等于把"确认"变成提前返回的理由 ——
     *    正好绕过了 `CapabilityGuard` 要防的东西（用户确认的是他看到的那个操作，
     *    不是这个函数收到的那个操作）。
     */
    fun confirm() {
        val pending = _ui.value.confirm ?: return
        val call = _ui.value.lastCall ?: return

        // 重新走一遍完整裁决：所有前置判定（硬拒绝、来源、参数、规划后校验）都会再跑。
        execute(call.copy(confirmedByUser = true), keepPending = pending)
    }

    /**
     * 用户放弃了这次确认。
     *
     * ⚠️ **必须记账**，而且必须与"用户主动点不要"分开记。
     *    `CapabilityRuntime` 只在"放行"那条路上写审计（`EXECUTED_AFTER_CONFIRM`），
     *    "用户拒绝了"这条结论只有调用方知道 —— 不写的话，
     *    审计里会出现一次**没有下文的确认请求**，而"我明明没点过同意"这种事
     *    事后完全无从查起。
     */
    fun dismissConfirm() {
        val pending = _ui.value.confirm ?: return
        val call = _ui.value.lastCall ?: return

        auditLog.record(
            CapabilityAuditEvents.confirmationResolved(
                timestamp = System.currentTimeMillis(),
                // 用判定层给出的 capability —— 而不是从 call 上再取一次：
                // 两者应当一致，但只有前者是**用户看到的那个**。
                capabilityId = pending.capability.id,
                origin = call.origin,
                execution = pending.execution,
                confirmReason = pending.reason,
                accepted = false,
            ),
        )
        _ui.value = _ui.value.copy(confirm = null, events = auditLog.events())
    }

    /**
     * 放行这一条能力，并立刻把刚才那次调用重来一遍。
     *
     * ⚠️ 放行之后**必须重新裁决**（`CapabilityGuard` 的注释里写死了这条）——
     *    所以这里走的是 [execute]，不是"记住结论然后执行"。
     */
    fun grantAndRetry() {
        val call = _ui.value.lastCall ?: return
        granted.value = granted.value + call.capabilityId
        execute(call)
    }

    /**
     * 原样再来一次。
     *
     * ⚠️ 与 [grantAndRetry] 是两件事：这一条用于"通道可用但这次没成"
     *    （`NextStep.RETRY`）。把它和"去配置"合成一个按钮的后果是
     *    用户在权限没给的情况下反复点，每次都得到同一句话。
     */
    fun retry() {
        val call = _ui.value.lastCall ?: return
        execute(call)
    }

    /** 收起结果横幅。 */
    fun dismissLast() {
        _ui.value = _ui.value.copy(last = null)
    }

    /** 通道没准备好时用户点了「知道了」—— 只是收起横幅，指引已经在正文里看过了。 */
    fun acknowledge() = dismissLast()

    fun clearAudit() {
        auditLog.clear()
        _ui.value = _ui.value.copy(events = emptyList())
    }

    private fun execute(
        call: CapabilityCall,
        keepPending: CapabilityVerdict.RequireConfirmation? = null,
    ) {
        viewModelScope.launch {
            val outcome = runtime.execute(call)

            _ui.value = when (outcome) {
                // ⚠️ 需要确认时**不写 last** —— 那会让界面上同时出现
                //    "需要你确认"的对话框和一条"没有执行"的红字，
                //    而用户还没做任何决定。
                is CapabilityOutcome.NeedsConfirmation -> _ui.value.copy(
                    lastCall = call,
                    confirm = outcome.verdict,
                    events = auditLog.events(),
                    // ⚠️⚠️ 这一行不能省。`copy` 保留的是**上一次**的 `grantedIds`，
                    //    而 [grantAndRetry] 刚刚改过 `granted.value`。
                    //
                    //    漏掉它的表现（2026-09-23 真机目击）：用户点了
                    //    「放行这一条，再来一次」，能力**确实执行了**（裁决读的是
                    //    `granted.value`，那是新的），但徽章还写着「未放行」、
                    //    按钮还写着「放行后执行」—— 于是他以为没生效，反复点。
                    //
                    //    ⇒ 同一个状态被两处读，就必须让两处都拿到新值。
                    grantedIds = granted.value,
                )

                else -> _ui.value.copy(
                    lastCall = call,
                    last = outcome,
                    confirm = null,
                    events = auditLog.events(),
                    grantedIds = granted.value,
                )
            }

            // `keepPending` 只在"确认"这条路径上非空；它的用途是让
            // "确认后又被拦下"这种情况在审计里读得出来（见 CapabilityRuntime）。
            require(keepPending == null || outcome !is CapabilityOutcome.NeedsConfirmation) {
                "确认之后不该再要求确认一次 —— 那说明确认标志没有生效"
            }
        }
    }

    private fun readState(): UiState {
        // ⚠️ 用 `zeroScreen` 而**不是** `all`。
        //
        //    目录里还留着 14 条设备控制能力（亮度、熄屏、深色模式、Wi-Fi、
        //    媒体控制…）。它们**技术上同样零占屏**，但不属于第 0 档的产品范围 ——
        //    而且全部走尚未实现的 shell 通道，显示在这一页上，用户点了
        //    只会得到「还没实现」。
        //
        //    见 `CapabilityGroup` 里「技术判据 vs 产品范围」那一段。
        val all = catalog.zeroScreen
        return UiState(
            safe = all.filter { it.risk == CapabilityRisk.SAFE },
            guarded = all.filter { it.risk == CapabilityRisk.GUARDED },
            grantedIds = granted.value,
            lastCall = null,
            last = null,
            confirm = null,
            events = auditLog.events(),
            grantedDirs = safGrants.grantedRoots(),
            unsupportedDirs = safGrants.unsupported(),
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  目录授权
    // ══════════════════════════════════════════════════════════════

    /**
     * 用户刚在系统文件选择器里选了一个目录。
     *
     * ⚠️ 这里必须**立刻把权限持久化**（`takePersistableUriPermission`），
     *    否则这次授权只在本次进程内有效 —— 表现是"今天能用，
     *    明天打开说还没授权"，而用户完全不知道自己做错了什么。
     *
     * ⚠️ 失败时**不能静默**：那会让界面显示出"已授权"，
     *    而下一次操作报"还没有授权任何目录"，两条信息互相矛盾。
     */
    fun grantDirectory(uri: Uri) {
        val taken = safGrants.take(uri)
        refreshGrants(
            error = if (taken) {
                null
            } else {
                "这个目录没能记住授权，所以它现在用不了。" +
                    "请再选一次；如果一直这样，可能是系统限制了这个位置。"
            },
        )
    }

    /**
     * 撤销一条授权。
     *
     * ⚠️ 撤销之后**必须重新问一遍系统**，而不是从界面的列表里划掉 ——
     *    划掉的后果是"界面上没了，但实际还能写"，而那正是撤销最不该有的样子。
     */
    fun revokeDirectory(uri: Uri) {
        val released = safGrants.release(uri)
        refreshGrants(
            error = if (released) {
                null
            } else {
                "这条授权没能撤销掉，它现在仍然有效。请到系统设置里检查一下。"
            },
        )
    }

    /** 收起授权出错的提示。 */
    fun dismissGrantError() {
        _ui.value = _ui.value.copy(grantError = null)
    }

    /**
     * 重新问系统一遍"我们现在有哪些授权"。
     *
     * ⚠️ 它读的是 `persistedUriPermissions`（系统维护的那份），
     *    不是我们自己记的列表 —— 见 [SafDirectoryGrants] 的类注释。
     *    自己记一份的后果是：用户在系统设置里撤销了权限，
     *    而这一页**照样显示已授权**，直到某次操作莫名其妙失败。
     */
    private fun refreshGrants(error: String? = null) {
        _ui.value = _ui.value.copy(
            grantedDirs = safGrants.grantedRoots(),
            unsupportedDirs = safGrants.unsupported(),
            grantError = error,
        )
    }
}
