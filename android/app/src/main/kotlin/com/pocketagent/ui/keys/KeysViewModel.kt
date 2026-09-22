package com.pocketagent.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.data.CredentialStoreResult
import com.pocketagent.keymgmt.AddCredentialResult
import com.pocketagent.keymgmt.CredentialRepository
import com.pocketagent.keymgmt.StoredCredential
import com.pocketagent.provider.api.LlmProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 加密存储的打开状态。
 *
 * ⚠️ 四个状态**不能合并**。它们对用户意味着四件不同的事，
 *    而其中"重试"和"重建"的代价差了一个数量级 ——
 *    选错就等于让用户为一次临时故障丢掉全部 Key，或者
 *    让他对着一个永远打不开的存储反复点重试。
 */
sealed interface StoreState {
    data object Opening : StoreState
    data object Ready : StoreState

    /** 钥匙或数据库已经失效，已保存的 Key 读不回来了。唯一出路是重建 */
    data class Unrecoverable(val reason: String) : StoreState

    /** 打不开，但可能只是暂时的 */
    data class Retryable(val reason: String) : StoreState
}

/**
 * 新增 Key 的表单。
 *
 * ⚠️ [keyInput] 是 `String` —— 这是本产品**唯一**无法消除的明文暴露点。
 *    原因见 `CredentialRepository` 的类注释：Compose 的文本输入只能用
 *    String，而 String 进了 JVM 字符串池就擦不掉。
 *
 * ⚠️ 由此推出一条硬规则：**绝对不要用 `rememberSaveable` 承载这个字段。**
 *    `rememberSaveable` 会把值写进 saved instance state 的 Bundle，
 *    而那个 Bundle 在进程被回收时**会落盘**（`savedInstanceState`）。
 *    那等于把明文 Key 写进了磁盘 —— 直接违反"Key 永不落明文"。
 *
 *    表单状态因此放在 ViewModel 里：能扛住屏幕旋转（ViewModel 会被保留），
 *    但扛不住进程死亡 —— 后者是可接受的，重填一次即可。
 *    **"多填一次"远比"明文落盘"便宜。**
 */
data class EditorState(
    /**
     * 这条 Key 是给谁用的。
     *
     * ⚠️ 必须由界面明确传入，**不能靠推断**。见 [KeysViewModel.openEditor]：
     *    用途决定了候选服务商、"默认凭据"的竞争范围、以及校验走哪条通道，
     *    推断错一个就会让用户存出一条永远用不上的 Key。
     */
    val purpose: CredentialPurpose,
    val providerId: String? = null,
    val keyInput: String = "",
    val label: String = "",
    val baseUrl: String = "",
    val error: String? = null,
    val saving: Boolean = false,
)

data class KeysUiState(
    val store: StoreState = StoreState.Opening,
    val credentials: List<StoredCredential> = emptyList(),
    val providers: List<LlmProvider> = emptyList(),
    /** 正在校验的凭据 id。用来让那一行的按钮变成"校验中" */
    val validatingIds: Set<String> = emptySet(),
    /** null 表示表单关闭 */
    val editor: EditorState? = null,
    /**
     * 出错时才有值。
     *
     * ⚠️ 刻意**只在出错时**设置：成功不做提示。
     *    列表本身的变化（多了一行 / 少了一行 / 徽章变了）就是反馈，
     *    再弹一条"保存成功"只是把同一件事说两遍。
     *    反过来，失败必须说出来 —— 而且要说到底为什么。
     */
    val problem: String? = null,
) {
    /**
     * 按用途取一组凭据。
     *
     * ⚠️ 这里**派生而不是各存一份**。存两份就要维护两个 Flow、
     *    两处 `catch`、两处更新 —— 而"某一处忘了更新"的表现是
     *    **删除后列表里还留着那一行**，且不会报任何错。
     *    单一数据源 + 派生视图没有这个失败模式。
     *
     *    代价是每次重组都要 filter 一遍，而这里的 N 是个位数。
     *
     * ⚠️ 顺序由 [credentials] 决定。仓储两个 Flow 各自按 `createdAtMillis`
     *    升序，而这里按 purpose 筛，等于什么都没打乱。
     */
    fun credentialsOf(purpose: CredentialPurpose): List<StoredCredential> =
        credentials.filter { it.purpose == purpose }
}

/**
 * API Key 管理页的状态持有者。
 *
 * 依赖用两个挂起函数注入，而不是直接吃 `AppContainer` ——
 * 这样这个类不需要任何 Android 依赖就能单测，
 * 而"存储打不开"的三条分支恰恰是最需要被测到的路径。
 */
class KeysViewModel(
    private val openStore: suspend () -> CredentialStoreResult,
    private val resetStore: suspend () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(KeysUiState())
    val state: StateFlow<KeysUiState> = _state.asStateFlow()

    private var repository: CredentialRepository? = null
    private var credentialsJob: Job? = null

    init {
        open()
    }

    // ─────────────────────────────────────────────────────────────
    //  打开 / 重建
    // ─────────────────────────────────────────────────────────────

    /** 打开加密存储。失败后用户可以点"重试"再次调用 */
    fun open() {
        viewModelScope.launch {
            _state.update { it.copy(store = StoreState.Opening) }

            when (val result = openStore()) {
                is CredentialStoreResult.Ready -> {
                    repository = result.repository
                    _state.update {
                        it.copy(
                            store = StoreState.Ready,
                            providers = result.repository.availableProviders,
                        )
                    }
                    observe(result.repository)
                }

                is CredentialStoreResult.Unrecoverable ->
                    _state.update { it.copy(store = StoreState.Unrecoverable(result.reason)) }

                is CredentialStoreResult.Retryable ->
                    _state.update { it.copy(store = StoreState.Retryable(result.reason)) }
            }
        }
    }

    /**
     * 重建加密存储。仅在用户明确确认后调用。
     *
     * ⚠️ 重建前必须**先停掉列表订阅**。否则旧订阅还挂在已经关闭的数据库上，
     *    它抛出的异常会盖掉"正在重建"的状态，用户看到的是一个
     *    与当前操作无关的报错。
     */
    fun resetStore() {
        viewModelScope.launch {
            credentialsJob?.cancel()
            credentialsJob = null
            repository = null

            _state.update {
                it.copy(
                    store = StoreState.Opening,
                    credentials = emptyList(),
                    editor = null,
                    problem = null,
                )
            }

            runCatching { resetStore() }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            store = StoreState.Retryable(
                                "重建失败：${e.message ?: e::class.simpleName}"
                            )
                        )
                    }
                    return@launch
                }

            open()
        }
    }

    /**
     * 订阅凭据列表。
     *
     * ⚠️ **两个用途各订阅一次，最后 merge 成一条流。**
     *
     *    单订阅 `repository.credentials` 也能拿到全量数据，但那是"把所有
     *    Key 混在一个列表里"的读法 —— 而这一页现在要**分组展示**，分组依据
     *    是 `purpose`。用两条按用途收窄的查询，等于让数据库来保证
     *    "这一组里只可能有这一种用途的 Key"。
     *
     *    `merge` 而不是 `combine`：任一列表变化都应该立刻反映到界面。
     *    `combine` 会等两边都至少发过一次值 —— 用户只配了 LLM Key 时，
     *    TTS 那条查询若一声不响，`combine` 就永远不发射，界面停在空列表上。
     *    而 `merge` 配合 `_state.update { it.copy(credentials = list) }` 是错的
     *    —— 见下。
     *
     * ⚠️ 先按用途拆开收集，再在收集里各自**替换自己那一段**。
     *    直接 `merge(...).collect { credentials = it }` 会让两条流互相覆盖：
     *    先到的那组被后到的那组整个顶掉，表现是**某一组凭据随机消失**。
     *
     * ⚠️ 两个 `catch` 都必须有。查询失败会**终止** Flow，而 Flow 的终止是
     *    静默的：界面会永远停在最后一个列表上，用户完全不知道发生了什么。
     *    开库时已经探过一次，但那只能覆盖"开库"这一刻 ——
     *    磁盘中途写满、文件被外部改坏，都只会在查询时才炸。
     *
     * ⚠️ `catch` 里**不换成另一个 Flow**（不用 `emitAll` 兜一个空列表）：
     *    那等于把"读失败了"伪装成"一个都没有"，而这两件事的用户动作
     *    差得很远 —— 前者该重试，后者该去添加。
     */
    private fun observe(repository: CredentialRepository) {
        credentialsJob?.cancel()
        credentialsJob = viewModelScope.launch {
            val collected = mutableMapOf<CredentialPurpose, List<StoredCredential>>()

            fun publish() {
                _state.update { it.copy(credentials = collected.values.flatten()) }
            }

            CredentialPurpose.entries.forEach { purpose ->
                launch {
                    repository.credentialsByPurpose(purpose)
                        .catch { e ->
                            _state.update {
                                it.copy(
                                    store = StoreState.Retryable(
                                        "读取凭据列表失败：${describe(e)}"
                                    )
                                )
                            }
                        }
                        .collect { list ->
                            collected[purpose] = list
                            publish()
                        }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  表单
    // ─────────────────────────────────────────────────────────────

    /**
     * 打开表单。
     *
     * ⚠️ [purpose] 是**必填参数，不给默认值**。默认值会让"给语音页加 Key"
     *    这条路径悄悄退化成"又加了一条模型 Key"，而**存的时候不会报错** ——
     *    用户要到语音功能不工作时才发现，且完全看不出是哪一步错了。
     *    不给默认值，编译器就会逼着每个调用点说清楚。
     *
     * ⚠️ 候选服务商按用途区分。见 [KeysUiState.editorProviders]：
     *    现在的 provider 清单里**一个 TTS 服务商都没有**，
     *    所以语音那组会拿到空列表，表单会转成"暂不可用"的说明。
     */
    fun openEditor(purpose: CredentialPurpose) {
        _state.update {
            it.copy(
                // 默认选中第一个服务商。让用户少做一次选择 ——
                // 而"忘了选服务商"是个会被校验拦下的无谓错误
                editor = EditorState(
                    purpose = purpose,
                    providerId = it.providers.firstOrNull()?.id,
                ),
                problem = null,
            )
        }
    }

    /**
     * 关闭表单。
     *
     * ⚠️ 连同 [EditorState.keyInput] 一起丢掉 —— 用户取消之后，
     *    那个明文 String 没有任何理由继续留在内存里。
     */
    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun editProvider(id: String) = updateEditor { it.copy(providerId = id, error = null) }
    fun editKey(value: String) = updateEditor { it.copy(keyInput = value, error = null) }
    fun editLabel(value: String) = updateEditor { it.copy(label = value, error = null) }
    fun editBaseUrl(value: String) = updateEditor { it.copy(baseUrl = value, error = null) }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    /**
     * 保存表单里的凭据。
     *
     * 校验错误（[AddCredentialResult.Rejected]）显示在**表单内**而不是
     * 页面顶部 —— 用户要改的是输入框里的内容，提示必须出现在他要改的地方。
     */
    fun save() {
        val editor = _state.value.editor ?: return
        val repo = repository ?: return

        val providerId = editor.providerId
        if (providerId == null) {
            updateEditor { it.copy(error = "先选一个服务商。") }
            return
        }

        viewModelScope.launch {
            updateEditor { it.copy(saving = true, error = null) }

            val result = repo.add(
                providerId = providerId,
                rawKey = editor.keyInput,
                label = editor.label,
                baseUrlOverride = editor.baseUrl.ifBlank { null },
                // ★ 用途必须透传。仓储的 `add()` 靠它决定"默认凭据"的
                //   竞争范围（`countByPurpose`），漏传就会让一条语音 Key
                //   把模型那组的默认标记顶掉。
                purpose = editor.purpose,
            )

            when (result) {
                is AddCredentialResult.Added -> {
                    // 表单连同那个明文 String 一起丢掉
                    _state.update { it.copy(editor = null) }

                    // 立刻校验一次：用户刚粘完 Key，最想知道的就是"它能用吗"。
                    //
                    // ⚠️ 校验失败**不影响保存**（见 AddCredentialResult 的注释）。
                    //    离线时这里会得到 UNREACHABLE，文案会明确说
                    //    "这不代表 Key 有问题" —— 而不是让用户以为白填了。
                    validate(result.credential.id)
                }

                is AddCredentialResult.Rejected ->
                    updateEditor { it.copy(saving = false, error = result.reason) }

                is AddCredentialResult.Failed ->
                    updateEditor { it.copy(saving = false, error = result.reason) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  列表上的动作
    // ─────────────────────────────────────────────────────────────

    fun validate(id: String) {
        val repo = repository ?: return
        if (id in _state.value.validatingIds) return

        viewModelScope.launch {
            _state.update { it.copy(validatingIds = it.validatingIds + id) }
            val outcome = repo.validate(id)
            _state.update { it.copy(validatingIds = it.validatingIds - id) }

            // 结论已经由 repo 写进数据库，列表会跟着更新（徽章 + 原因）。
            // 这里只在"连状态都没落上"时兜一句 —— 那种情况下界面会
            // 看起来什么都没发生，用户会以为按钮坏了。
            val updated = _state.value.credentials.firstOrNull { it.id == id }
            if (updated != null && updated.status != outcome.status) {
                _state.update { it.copy(problem = outcome.detail) }
            }
        }
    }

    fun delete(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.remove(id) }.onFailure { e ->
                _state.update { it.copy(problem = "删除失败：${describe(e)}") }
            }
        }
    }

    fun setDefault(id: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.setDefault(id) }.onFailure { e ->
                _state.update { it.copy(problem = "切换默认凭据失败：${describe(e)}") }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────

    private fun updateEditor(block: (EditorState) -> EditorState) {
        _state.update { current ->
            current.editor?.let { current.copy(editor = block(it)) } ?: current
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e::class.simpleName ?: "未知错误"
}
