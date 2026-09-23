package com.pocketagent.ui.permissions

import android.content.Intent
import androidx.lifecycle.ViewModel
import com.pocketagent.assistant.HostPermissionReader
import com.pocketagent.core.common.permission.HostPermissions
import com.pocketagent.core.common.permission.PermissionAction
import com.pocketagent.core.common.permission.PermissionSummary
import com.pocketagent.core.common.permission.PermissionVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限状态页的状态持有者。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 读的是**系统**，不是缓存 —— 所以每次回到前台都要重读
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户在这个页面上点"去开启"，跳到系统设置，开完再回来。
 * 如果回来看到的是**旧**状态，他会得出"开了也没用"的结论，
 * 然后再去开一遍 —— 而这个失败**没有任何东西会报错**。
 *
 * 所以 [refresh] 必须挂在 `ON_RESUME` 上，不能只在 `init` 里读一次。
 *
 * ⚠️ 判定逻辑不在这里，在 `:core:common` 的 `HostPermissions`（纯 Kotlin、有测试）。
 *    这里只负责"什么时候读"和"把结果放进 StateFlow"。
 */
class PermissionsViewModel(
    private val reader: HostPermissionReader,
) : ViewModel() {

    data class UiState(
        val verdicts: List<PermissionVerdict>,
        val summary: PermissionSummary,
    )

    // 初始值**同步**读一次：只是一次系统查询，让页面第一帧就说真话，
    // 而不是先闪一下空列表。（同 `DshIntegrationViewModel` 的做法）
    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun refresh() {
        _ui.value = read()
    }

    /** 动作对应的系统页面；`null` 表示这个动作要走运行时权限请求，不走 Intent。 */
    fun settingsIntent(action: PermissionAction): Intent? = reader.settingsIntent(action)

    private fun read(): UiState {
        val verdicts = HostPermissions.verdicts(reader.read())
        return UiState(verdicts = verdicts, summary = HostPermissions.summarize(verdicts))
    }
}
