package com.pocketagent.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.PluginInstaller
import com.pocketagent.data.SubscriptionRepository
import com.pocketagent.plugin.api.MarketCatalog
import com.pocketagent.plugin.api.MarketQuery
import com.pocketagent.plugin.api.PluginLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 市场页状态。
 *
 * [catalog] 与 [loading] 是两个独立维度，不能合并成一个 `Loading/Ready/Error` 枚举：
 * 刷新时旧目录**必须继续显示**。把它清空会让列表在每次刷新时闪一下白屏，
 * 而用户可能正在读某个插件的权限说明。
 */
data class MarketUiState(
    val loading: Boolean = false,
    val catalog: MarketCatalog? = null,
    val query: MarketQuery = MarketQuery(),
    val installedIds: Set<String> = emptySet(),
    val installing: Set<String> = emptySet(),
    val fatalError: String? = null,
    val message: String? = null,
)

class MarketViewModel(
    private val subscriptions: SubscriptionRepository,
    private val installer: PluginInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(MarketUiState())
    val state: StateFlow<MarketUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            subscriptions.ensureBuiltinSource()
            refreshInstalled()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, fatalError = null) }
            try {
                val catalog = subscriptions.loadCatalog()
                _state.update { it.copy(loading = false, catalog = catalog) }
            } catch (e: Exception) {
                // 理论上 loadCatalog 内部已经把每个源的异常都吞掉了，
                // 走到这里说明是订阅列表本身读取失败（比如 DataStore 坏了）
                Timber.w(e, "刷新插件市场失败")
                _state.update { it.copy(loading = false, fatalError = e.message ?: "未知错误") }
            }
        }
    }

    fun updateQuery(transform: (MarketQuery) -> MarketQuery) {
        _state.update { it.copy(query = transform(it.query)) }
    }

    fun toggleLevel(level: PluginLevel) {
        _state.update { current ->
            val levels = current.query.levels
            val next = if (level in levels) levels - level else levels + level
            current.copy(query = current.query.copy(levels = next))
        }
    }

    fun toggleExcludeHighRisk() {
        _state.update {
            it.copy(query = it.query.copy(excludeHighRisk = !it.query.excludeHighRisk))
        }
    }

    fun install(entry: com.pocketagent.plugin.api.MarketEntry) {
        if (entry.id in _state.value.installing) return

        viewModelScope.launch {
            _state.update { it.copy(installing = it.installing + entry.id) }
            val result = installer.install(entry)

            _state.update { current ->
                when (result) {
                    is PluginInstaller.Result.Installed -> current.copy(
                        installing = current.installing - entry.id,
                        installedIds = current.installedIds + entry.id,
                        // 说清"装好了"和"能用了"是两回事 —— 否则用户会以为插件在后台跑
                        message = "已安装「${result.manifest.name}」${result.manifest.version}。" +
                            "还需要在插件页里授权能力，它才会开始工作。",
                    )

                    is PluginInstaller.Result.Failed -> current.copy(
                        installing = current.installing - entry.id,
                        message = result.reason,
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private suspend fun refreshInstalled() {
        _state.update { it.copy(installedIds = installer.installedIds().toSet()) }
    }
}
