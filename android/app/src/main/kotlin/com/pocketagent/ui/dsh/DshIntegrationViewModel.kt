package com.pocketagent.ui.dsh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.DshIntegrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dsh 集成页的状态持有者。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它**不持有**开关状态 —— 只镜像容器里的那一份
 * ═══════════════════════════════════════════════════════════════
 *
 * 网关的生命周期长于本页面：用户开启之后会离开，而 dsh 还要继续用它。
 * 若这里自己维护一份 `isRunning`，用户离开再回来（ViewModel 被重建）就会
 * 看到"未开启"，而网关其实还在监听 —— 界面开始说假话。
 *
 * 所以唯一的事实来源是 `AppContainer.dshIntegrationStatus()`，
 * 本类每次动作之后**重新读它**，而不是自己推断。
 * （与 `SettingsViewModel` 不自己算 Key 数量、而是订阅仓储是同一个立场。）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么每个动作都要 `withContext(Dispatchers.IO)`
 * ═══════════════════════════════════════════════════════════════
 *
 * `AppContainer.startDshIntegration()` 是**同步阻塞**的：读两个文件、写两个文件、
 * 建 `ServerSocket`。而 `DshGatewaySession.start()` 不是 suspend 函数 ——
 * 这一点很容易看漏，看漏的后果是点击那一帧直接卡住（严重时 ANR）。
 */
class DshIntegrationViewModel(
    private val readStatus: () -> DshIntegrationStatus,
    private val startIntegration: () -> DshIntegrationStatus,
    private val stopIntegration: () -> DshIntegrationStatus,
    private val readDraftSettings: () -> String?,
    /** 草稿文件的绝对路径，展示给用户（他要照着找，或贴给我们排查）。 */
    val draftSettingsPath: String,
) : ViewModel() {

    sealed interface UiState {

        /** 正在开/关。两个动作都可能在慢设备上花几百毫秒，必须有这个态。 */
        data object Working : UiState

        /**
         * @param status 容器里的真实状态
         * @param draftSettings 草稿配置全文；`null` = 还没读出来**或**文件不存在。
         *        ⚠️ 只有 [DshIntegrationStatus.On] 时才有展示意义。
         */
        data class Ready(
            val status: DshIntegrationStatus,
            val draftSettings: String?,
        ) : UiState
    }

    // 初始值**同步**读容器状态 —— 它只是一次 volatile 读，很便宜，
    // 而且能让页面第一帧就说真话（不会闪一下"未开启"）。
    // 草稿全文要读文件，所以先给 null，由 refresh() 异步补上。
    private val _ui = MutableStateFlow<UiState>(UiState.Ready(readStatus(), null))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun start() = act(startIntegration)

    fun stop() = act(stopIntegration)

    /**
     * 重新读一次真实状态（并顺带刷新草稿全文）。
     *
     * 存在两个理由：
     * 1. 进页面时补齐草稿全文
     * 2. 用户从「API Key」页补完 Key 之后回来 —— 此时容器状态可能已经
     *    从 `Blocked` 变成可开启，界面必须反映出来
     */
    fun refresh() {
        viewModelScope.launch {
            val status = readStatus()
            val text = withContext(Dispatchers.IO) { readDraftSettings() }
            _ui.value = UiState.Ready(status, text)
        }
    }

    private fun act(op: () -> DshIntegrationStatus) {
        // ⚠️ 已经在跑就不重入：连点两次会让 `UiState.Working` 被覆盖，
        //    而两次动作的结果谁后到谁说话 —— 用户看到的状态与真实状态可能不一致。
        if (_ui.value is UiState.Working) return

        _ui.value = UiState.Working
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { op() }
            val text = withContext(Dispatchers.IO) { readDraftSettings() }
            _ui.value = UiState.Ready(status, text)
        }
    }
}
