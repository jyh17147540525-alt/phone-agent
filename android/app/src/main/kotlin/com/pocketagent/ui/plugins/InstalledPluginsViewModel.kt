package com.pocketagent.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.InstalledPlugin
import com.pocketagent.data.PluginInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledPluginsUiState(
    val loading: Boolean = true,
    val plugins: List<InstalledPlugin> = emptyList(),

    /**
     * 等待二次确认的卸载目标。
     *
     * 卸载**不是**一个点一下就完成的动作 —— 它删的是用户自己的东西，
     * 而且删完不可恢复。所以点"卸载"只是把目标挂在这里，
     * 真正的删除要等对话框里的第二次确认。
     */
    val pendingUninstall: InstalledPlugin? = null,

    val message: String? = null,
) {
    val isEmpty: Boolean get() = !loading && plugins.isEmpty()
}

class InstalledPluginsViewModel(
    private val installer: PluginInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(InstalledPluginsUiState())
    val state: StateFlow<InstalledPluginsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * 重新扫描磁盘。
     *
     * 扫描放在 IO 线程：插件目录在 `filesDir` 下，读清单要碰磁盘。
     * 而且用户可能装了十几个插件 —— 主线程读十几个文件足够让界面卡一下，
     * 那个卡顿会被理解成"这应用很慢"，而不是"它在读磁盘"。
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val list = withContext(Dispatchers.IO) { installer.installedPlugins() }
            _state.update { it.copy(loading = false, plugins = list) }
        }
    }

    fun askUninstall(plugin: InstalledPlugin) {
        _state.update { it.copy(pendingUninstall = plugin) }
    }

    fun cancelUninstall() {
        _state.update { it.copy(pendingUninstall = null) }
    }

    fun confirmUninstall() {
        val target = _state.value.pendingUninstall ?: return
        _state.update { it.copy(pendingUninstall = null) }

        viewModelScope.launch {
            // ⚠️ uninstall 收的是 id，而坏目录没有 id（manifest 解析不出来）。
            //    InstalledPlugin.id 在那种情况下会退化成 dirName，正好是对的 ——
            //    sanitizeDirName 对已净化的名字是幂等的。
            val ok = withContext(Dispatchers.IO) { installer.uninstall(target.id) }

            if (ok) {
                _state.update { it.copy(message = "已卸载「${target.displayName}」") }
                refresh()
            } else {
                // deleteRecursively 返回 false 通常意味着有文件删不掉。
                // 不说"未知错误"，是因为用户能据此做判断（重启再试 / 手动清理）
                _state.update {
                    it.copy(message = "卸载失败：有文件没能删除，可能被占用。请重启应用后再试。")
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
