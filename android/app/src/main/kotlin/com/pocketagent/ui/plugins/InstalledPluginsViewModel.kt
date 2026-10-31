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

/**
 * 插件页的界面状态。
 *
 * ★★ 2026-10-31：加 `@Immutable`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个标注能让界面更快
 * ═══════════════════════════════════════════════════════════════
 *
 * `plugins: List<InstalledPlugin>` 是 Compose 认为**不稳定**的类型
 * （`List` 是接口，它无法确认"值相等就真的相等"）。
 *
 * ⇒ 不加标注时，父级每次重组都会**重新组合整页**，哪怕 `plugins`
 *   的内容一个字都没变。而插件页在安装过程中会频繁更新 state
 *   （loading 翻转、进度刷新）—— 每次都整页重组。
 *
 * ★ 安全性：本类的所有字段都是 `val`，且**没有任何一处**会去
 *   `plugins.add(...)`（每次都是 `copy(plugins = 新列表)` 整体替换）。
 *   所以标 `@Immutable` 是**陈述事实**，不是骗编译器。
 *
 * ⚠️ 如果将来有人改成原地修改列表，界面会**永远不更新** ——
 *   那不是"慢"，是"坏了"，而且极难查。规则见
 *   `:agentui/ComposeStability.isSafeImmutableCandidate`。
 */
@androidx.compose.runtime.Immutable
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
            // ══════════════════════════════════════════════════════
            //  ★★ 2026-10-30：**已有数据时不进 loading 态**
            // ══════════════════════════════════════════════════════
            //
            // 用户："各个页面之间的切换有种突兀感"。
            //
            // 这里原本无条件 `loading = true`。而 `PluginsScreen` 每次
            // 进入页面都会调 refresh（那是**必要**的 —— 从市场装完插件
            // 返回时必须重新扫盘，否则用户看不到刚装的）。
            //
            // 于是每次「插件 → 市场 → 返回」都会闪一下：
            //   有数据 → 清空成 loading → 数据回来
            // 那个闪就是突兀感。它和动画曲线无关，是**内容消失了一瞬**。
            //
            // ⇒ 区分"首次加载"与"后台刷新"：
            //   · 没有数据  ⇒ 进 loading（此时本来就该显示加载态）
            //   · 已有数据  ⇒ **不动 UI**，扫完直接替换
            //
            // ⚠️ 仍然要扫盘（不能因为"有数据"就跳过），
            //   否则刚装的插件永远不出现。变的只是"要不要闪那一下"。
            val hasData = _state.value.plugins.isNotEmpty()
            if (!hasData) _state.update { it.copy(loading = true) }
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
