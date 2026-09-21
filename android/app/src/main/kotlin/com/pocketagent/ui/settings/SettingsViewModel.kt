package com.pocketagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.CredentialStoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 设置页上的状态汇总。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么值得为一行徽章建一个 ViewModel
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为那个徽章原先写死成"未配置"。功能上线之后它就会**变成一句假话** ——
 * 用户明明配好了 Key，设置页还告诉他"未配置"。他不会去怀疑文案，
 * 他会怀疑自己是不是没保存成功，然后再配一遍。
 *
 * 一个说错话的界面比一个不说这话的界面更糟。
 *
 * ⚠️ 代价说清楚：进设置页会触发一次加密存储的打开（Keystore + SQLCipher）。
 *    这是**有意的**，因为设置页正是"这台设备现在什么状态"的所在。
 *    打开结果在 AppContainer 里缓存，所以之后进 Key 页不会再付一次。
 *    但它**不会**在应用启动时发生 —— 冷启动路径上不该有 Keystore。
 */
class SettingsViewModel(
    openStore: suspend () -> CredentialStoreResult,
) : ViewModel() {

    /**
     * 凭据状态。
     *
     * ⚠️ [Broken] 与 `Configured(0, 0)` 必须分开。两者在界面上都是
     *    "没有可用的 Key"，但用户要做的事完全不同：
     *    前者是存储坏了（要重建），后者是还没配（要去添加）。
     *    合并成一个状态，就会把"去添加一个 Key"的建议给到一个
     *    存储已经打不开的人 —— 他照着做，然后失败，然后不知道还能做什么。
     */
    sealed interface KeyStatus {
        data object Loading : KeyStatus

        /** [usable] 把"配了但都不可用"和"配了且能用"分开 —— 前者需要用户处理 */
        data class Configured(val total: Int, val usable: Int) : KeyStatus

        /** 加密存储打不开 */
        data object Broken : KeyStatus
    }

    private val _keyStatus = MutableStateFlow<KeyStatus>(KeyStatus.Loading)
    val keyStatus: StateFlow<KeyStatus> = _keyStatus.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = openStore()) {
                is CredentialStoreResult.Ready ->
                    result.repository.credentials
                        .catch { _keyStatus.value = KeyStatus.Broken }
                        .collect { list ->
                            _keyStatus.value = KeyStatus.Configured(
                                total = list.size,
                                usable = list.count { it.usable },
                            )
                        }

                is CredentialStoreResult.Unrecoverable,
                is CredentialStoreResult.Retryable,
                -> _keyStatus.value = KeyStatus.Broken
            }
        }
    }
}
