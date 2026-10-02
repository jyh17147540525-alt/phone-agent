package com.pocketagent.ui.dsh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.assistant.McpBridgeStatus
import com.pocketagent.data.DshIntegrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dsh 集成页的状态持有者 —— 现在管**两条链路**：
 * 模型网关（把用户配的模型给 dsh）与 MCP 能力桥（把手机能力给 dsh）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它**不持有**两条链路各自的开关状态 —— 只镜像容器里的那两份
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者的生命周期都长于本页面：用户开启之后会离开，而 dsh 还要继续用。
 * 若这里自己维护 `isRunning`，用户离开再回来（ViewModel 被重建）就会
 * 看到"未开启"，而服务器其实还在监听 —— 界面开始说假话。
 *
 * 所以唯一的事实来源是 `AppContainer` 的两个状态函数，
 * 本类每次动作之后**重新读它们**，而不是自己推断。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么每个动作都要 `withContext(Dispatchers.IO)`
 * ═══════════════════════════════════════════════════════════════
 *
 * 两个 `start` 都是**同步阻塞**的（读文件、写文件、建 ServerSocket），
 * 而且都不是 suspend 函数 —— 这一点很容易看漏，
 * 看漏的后果是点击那一帧直接卡住（严重时 ANR）。
 */
class DshIntegrationViewModel(
    private val readStatus: () -> DshIntegrationStatus,
    private val startIntegration: () -> DshIntegrationStatus,
    private val stopIntegration: () -> DshIntegrationStatus,
    private val readDraftSettings: () -> String?,
    /** 网关草稿文件的绝对路径，展示给用户（他要照着找，或贴给我们排查）。 */
    val draftSettingsPath: String,

    // ── MCP 能力桥（P2）—— 与网关同款"收函数"的纪律 ─────────────
    private val readMcpStatus: () -> McpBridgeStatus,
    private val startMcp: () -> McpBridgeStatus,
    private val stopMcp: () -> McpBridgeStatus,
    private val readMcpDraft: () -> String?,
    /** 能力桥草稿文件的绝对路径。 */
    val mcpDraftPath: String,
) : ViewModel() {

    sealed interface UiState {

        /** 正在开/关。四个动作都可能在慢设备上花几百毫秒，必须有这个态。 */
        data object Working : UiState

        /**
         * @param status 网关的真实状态
         * @param draftSettings 网关草稿全文；`null` = 还没读出来**或**文件不存在
         * @param mcpStatus 能力桥的真实状态
         * @param mcpDraft 能力桥草稿全文；同上
         */
        data class Ready(
            val status: DshIntegrationStatus,
            val draftSettings: String?,
            val mcpStatus: McpBridgeStatus,
            val mcpDraft: String?,
        ) : UiState
    }

    // 初始值**同步**读两条链路的状态 —— 它们只是两次 volatile 读，很便宜，
    // 而且能让页面第一帧就说真话（不会闪一下"未开启"）。
    // 两份草稿全文要读文件，所以先给 null，由 refresh() 异步补上。
    private val _ui = MutableStateFlow<UiState>(
        UiState.Ready(
            status = readStatus(),
            draftSettings = null,
            mcpStatus = readMcpStatus(),
            mcpDraft = null,
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun start() = act { startIntegration() }

    fun stop() = act { stopIntegration() }

    fun startBridge() = act { startMcp() }

    fun stopBridge() = act { stopMcp() }

    /**
     * 重新读真实状态（并顺带刷新两份草稿全文）。
     *
     * 存在两个理由：
     * 1. 进页面时补齐草稿全文
     * 2. 用户从「API Key」页补完 Key 之后回来 —— 此时容器状态可能已经
     *    从 `Blocked` 变成可开启，界面必须反映出来
     */
    fun refresh() {
        viewModelScope.launch {
            _ui.value = loadReady()
        }
    }

    /**
     * 统一的动作封装：Working → IO 线程执行 → 重新读真实状态。
     *
     * ⚠️ 已经在跑就**不重入**：连点两次会让 `UiState.Working` 被覆盖，
     *    而两次动作的结果谁后到谁说话 —— 用户看到的状态与真实状态可能不一致。
     */
    private fun act(op: () -> Unit) {
        if (_ui.value is UiState.Working) return

        _ui.value = UiState.Working
        viewModelScope.launch {
            withContext(Dispatchers.IO) { op() }
            _ui.value = loadReady()
        }
    }

    private suspend fun loadReady(): UiState.Ready = withContext(Dispatchers.IO) {
        UiState.Ready(
            status = readStatus(),
            draftSettings = readDraftSettings(),
            mcpStatus = readMcpStatus(),
            mcpDraft = readMcpDraft(),
        )
    }
}
