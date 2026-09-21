package com.pocketagent.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.data.PluginInstaller
import com.pocketagent.plugin.api.ImportRiskNotice
import com.pocketagent.plugin.api.PluginBundle
import com.pocketagent.plugin.api.PluginImporter
import com.pocketagent.plugin.api.PluginManifest
import com.pocketagent.plugin.api.ValidationIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 本地导入的状态机。
 *
 * ```
 *   IDLE ──选中文件──▶ INSPECTING ──┬─▶ READY ──确认──▶ DONE
 *                                  ├─▶ REJECTED    （申请了禁止能力）
 *                                  └─▶ MALFORMED   （文件根本不是插件包）
 * ```
 *
 * 三种失败结果**刻意分开**：用户看到"插件有问题"时的下一步动作完全不同 ——
 * REJECTED 该换来源（这个作者不老实），MALFORMED 该检查自己是不是选错了文件。
 * 合成一个错误码，用户就只能瞎试。
 */
data class ImportUiState(
    val stage: Stage = Stage.IDLE,
    val fileName: String? = null,
    val notice: ImportRiskNotice? = null,
    val manifest: PluginManifest? = null,
    val errors: List<ValidationIssue> = emptyList(),
    val malformed: String? = null,
    /** 用户手动输入的确认词 */
    val typed: String = "",
    val installing: Boolean = false,
    val doneMessage: String? = null,
) {
    enum class Stage { IDLE, INSPECTING, READY, REJECTED, MALFORMED, DONE }

    val requiresTypedConfirmation: Boolean get() = notice?.requireTypedConfirmation == true

    /**
     * 能否点"安装"。
     *
     * 高危插件必须把确认词**一字不差**地打出来。这不是防误触 ——
     * 是逼用户把"我已知晓风险"这六个字读一遍。点击同意太廉价了。
     */
    val canConfirm: Boolean
        get() = stage == Stage.READY &&
            !installing &&
            (!requiresTypedConfirmation ||
                typed.trim() == ImportRiskNotice.CONFIRMATION_PHRASE)
}

class ImportViewModel(
    private val installer: PluginInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 待安装的插件包字节。走完确认流程前只留在内存里，不落盘 */
    private var pendingBundle: ByteArray? = null

    /**
     * 检查用户选中的文件。
     *
     * ⚠️ 这里**只检查、不安装**。整个流程里最关键的一步是"把风险讲清楚，
     *    然后等用户自己决定"，所以检查和安装必须是两次独立的用户动作。
     */
    fun inspect(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            _state.value = ImportUiState(stage = Stage.INSPECTING, fileName = fileName)

            if (!looksLikeZip(bytes)) {
                _state.value = ImportUiState(
                    stage = Stage.MALFORMED,
                    fileName = fileName,
                    malformed = "这个文件不是插件包。\n\n" +
                        "插件包（.pagent）是一个 zip 文件，里面至少要有 " +
                        "${PluginBundle.MANIFEST_NAME} 和它引用的规则/脚本文件。\n" +
                        "如果你手上只有一份 plugin.json，说明还缺它的入口文件 —— " +
                        "把它们一起打包成 zip 并改名为 .pagent 即可。",
                )
                return@launch
            }

            // 只读清单，不解压。在用户点确认之前，任何内容都不该落到磁盘上
            val manifestJson = installer.peekManifestJson(bytes)
            if (manifestJson == null) {
                _state.value = ImportUiState(
                    stage = Stage.MALFORMED,
                    fileName = fileName,
                    malformed = "这个包里没有找到可用的 ${PluginBundle.MANIFEST_NAME}，" +
                        "或者包内含有不安全的路径。",
                )
                return@launch
            }

            when (val outcome = PluginImporter.inspect(manifestJson)) {
                is PluginImporter.Outcome.Ready -> {
                    pendingBundle = bytes
                    _state.value = ImportUiState(
                        stage = Stage.READY,
                        fileName = fileName,
                        notice = outcome.notice,
                        manifest = outcome.manifest,
                    )
                }

                is PluginImporter.Outcome.Rejected -> _state.value = ImportUiState(
                    stage = Stage.REJECTED,
                    fileName = fileName,
                    errors = outcome.errors,
                )

                is PluginImporter.Outcome.Malformed -> _state.value = ImportUiState(
                    stage = Stage.MALFORMED,
                    fileName = fileName,
                    malformed = outcome.message,
                )
            }
        }
    }

    fun updateTyped(text: String) {
        _state.update { it.copy(typed = text) }
    }

    fun confirmInstall() {
        val current = _state.value
        val manifest = current.manifest ?: return
        val bundle = pendingBundle ?: return
        if (!current.canConfirm) return

        viewModelScope.launch {
            _state.update { it.copy(installing = true) }

            val result = installer.installFromBytes(bundle, manifest.id)
            pendingBundle = null

            _state.update {
                when (result) {
                    is PluginInstaller.Result.Installed -> it.copy(
                        stage = Stage.DONE,
                        installing = false,
                        doneMessage = "已安装「${result.manifest.name}」${result.manifest.version}。\n\n" +
                            "它还没有被启用 —— 你刚才授权的只是「允许安装」。" +
                            "要到插件页里再确认一次，它才会开始读取屏幕或模拟操作。",
                    )

                    is PluginInstaller.Result.Failed -> it.copy(
                        stage = Stage.MALFORMED,
                        installing = false,
                        malformed = result.reason,
                    )
                }
            }
        }
    }

    fun reset() {
        pendingBundle = null
        _state.value = ImportUiState()
    }

    /** zip 的魔数：PK\x03\x04 */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
}
