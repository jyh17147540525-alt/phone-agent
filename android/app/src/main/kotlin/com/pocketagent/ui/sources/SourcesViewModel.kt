package com.pocketagent.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.SubscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourcesUiState(
    val loading: Boolean = true,
    val sources: List<String> = emptyList(),

    /** 输入框内容 */
    val input: String = "",

    /** 输入框下方的错误提示。**不走 Snackbar** —— 添加失败时要让用户
     *  一边看着错误一边改地址，弹一下就没的提示做不到这件事 */
    val inputError: String? = null,

    val adding: Boolean = false,

    /** 等待确认移除的地址 */
    val pendingRemove: String? = null,

    val message: String? = null,
) {
    val hasBuiltin: Boolean
        get() = sources.any { it.equals(SubscriptionRepository.BUILTIN_SOURCE_URL, ignoreCase = true) }

    val isEmpty: Boolean get() = !loading && sources.isEmpty()

    /** 能否提交：非空、不是在提交中 */
    val canSubmit: Boolean get() = input.isNotBlank() && !adding
}

class SourcesViewModel(
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        // 订阅 Flow 而不是读一次快照：增删之后列表要自己更新，
        // 否则用户会看到一个"点完没反应"的界面
        viewModelScope.launch {
            subscriptions.sourceUrls.collect { urls ->
                _state.update { it.copy(loading = false, sources = urls) }
            }
        }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(input = text, inputError = null) }
    }

    fun add() {
        if (!_state.value.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(adding = true) }

            when (val result = subscriptions.addSource(_state.value.input)) {
                is SubscriptionRepository.AddResult.Added -> _state.update {
                    it.copy(
                        adding = false,
                        input = "",
                        inputError = null,
                        message = "已添加订阅源。回到市场页刷新一下就能看到内容。",
                    )
                }

                is SubscriptionRepository.AddResult.Rejected -> _state.update {
                    it.copy(adding = false, inputError = result.reason)
                }
            }
        }
    }

    fun restoreBuiltin() {
        viewModelScope.launch {
            val result = subscriptions.restoreBuiltinSource()
            _state.update {
                it.copy(
                    message = when (result) {
                        is SubscriptionRepository.AddResult.Added -> "已恢复内置源。"
                        is SubscriptionRepository.AddResult.Rejected -> result.reason
                    }
                )
            }
        }
    }

    fun askRemove(url: String) {
        _state.update { it.copy(pendingRemove = url) }
    }

    fun cancelRemove() {
        _state.update { it.copy(pendingRemove = null) }
    }

    fun confirmRemove() {
        val url = _state.value.pendingRemove ?: return
        _state.update { it.copy(pendingRemove = null) }

        viewModelScope.launch {
            subscriptions.removeSource(url)
            _state.update { it.copy(message = "已移除该订阅源。") }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
