package com.pocketagent.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.core.database.entity.ModelTierEntity
import com.pocketagent.data.CredentialStoreResult
import com.pocketagent.keymgmt.ModelConfigRepositoryImpl
import com.pocketagent.keymgmt.StoredCredential
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelSetupSummary
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.summarizeModelSetup
import com.pocketagent.ui.keys.StoreState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 模型配置页的状态持有者。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 [com.pocketagent.ui.keys.KeysViewModel] 的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * 两页共用同一把「加密存储」的钥匙，所以**打开失败的三条分支完全一样**，
 * 这里直接复用 [StoreState]（它在 `ui.keys` 包里，是 `public` 的）。
 * 刻意不复制一份 —— 复制出来的那份迟早会跟原版走散，
 * 而两页对"存储打不开"的说法不一致，用户会以为遇到了两种不同的故障。
 *
 * 区别在**打开之后**：
 *   · Key 页关心"有哪些 Key、能不能用"
 *   · 这一页关心"这些 Key 上面挂了哪些模型、谁负责派活"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 依赖用挂起函数注入，不吃 AppContainer
 * ═══════════════════════════════════════════════════════════════
 *
 * 这样这个类不需要任何 Android 依赖就能单测。
 *
 * ⚠️ 但有一个接口是例外的：[ModelConfigRepositoryImpl] 是**具体类**，
 *    因为它的写入方法（add / setTier / setRoles / setEnabled）不在
 *    [com.pocketagent.modelrouter.ModelConfigRepository] 接口上 ——
 *    那个接口只声明了读。用具体类意味着本类**不能被纯逻辑测试覆盖**，
 *    所以这里的逻辑刻意压到最薄：所有判断都在仓储层和 ViewModel 之间
 *    没有第三处，凡是能下沉到仓储的都下沉了。
 */
class ModelsViewModel(
    private val openStore: suspend () -> CredentialStoreResult,
    /** 返回 null = 加密存储还没打开。**不是"没有模型"** —— 两者必须分开处理 */
    private val modelRepository: () -> ModelConfigRepositoryImpl?,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private var repository: ModelConfigRepositoryImpl? = null
    private var modelsJob: Job? = null
    private var credentialsJob: Job? = null

    init {
        open()
    }

    // ─────────────────────────────────────────────────────────────
    //  打开
    // ─────────────────────────────────────────────────────────────

    /**
     * 打开加密存储并把模型列表接上。
     *
     * ⚠️ 这里同时驱动了 Key 页的 [openStore] —— 两页操作的是同一个单例，
     *    所以从 Key 页进来时它几乎必然立刻返回 `Ready`（缓存命中）。
     */
    fun open() {
        viewModelScope.launch {
            _state.update { it.copy(store = StoreState.Opening) }

            when (val result = openStore()) {
                is CredentialStoreResult.Ready -> {
                    observeCredentials(result.repository.credentialsByPurpose(CredentialPurpose.LLM))

                    // ⚠️ 存储刚打开，模型仓储可能还没建好（见 AppContainer：
                    //    两者在同一个 storeLock 里一起建，所以这里必然已经就绪）。
                    //    仍然判一次 null —— 不假设上游做对了事。
                    val modelRepo = modelRepository()
                    if (modelRepo == null) {
                        _state.update {
                            it.copy(store = StoreState.Retryable("模型配置仓储尚未就绪，请重试。"))
                        }
                        return@launch
                    }

                    repository = modelRepo
                    _state.update { it.copy(store = StoreState.Ready) }
                    observeModels(modelRepo)
                }

                is CredentialStoreResult.Unrecoverable ->
                    _state.update { it.copy(store = StoreState.Unrecoverable(result.reason)) }

                is CredentialStoreResult.Retryable ->
                    _state.update { it.copy(store = StoreState.Retryable(result.reason)) }
            }
        }
    }

    /**
     * 订阅模型列表。
     *
     * ⚠️ 必须 `catch`。查询失败会**终止** Flow，而 Flow 的终止是静默的 ——
     *    界面会永远停在最后一个列表上，用户完全不知道发生了什么。
     */
    private fun observeModels(repo: ModelConfigRepositoryImpl) {
        modelsJob?.cancel()
        modelsJob = viewModelScope.launch {
            repo.models
                .catch { e ->
                    _state.update {
                        it.copy(store = StoreState.Retryable("读取模型列表失败：${describe(e)}"))
                    }
                }
                .collect { list ->
                    // ★ 体检结论在这里算，界面不自己 filter。
                    //   界面上散落的 `models.filter { it.enabled }` 改一处漏一处，
                    //   而且**错了不报错，只是显示得很正常**。
                    _state.update { it.copy(models = list, summary = summarizeModelSetup(list)) }
                }
        }
    }

    /**
     * 订阅可用的 LLM 凭据 —— "添加模型"表单里要选一条 Key。
     *
     * ⚠️ **只订阅 LLM 用途的**。仓储的 `add()` 会拒绝 TTS 的 Key，
     *    若这里把 TTS 的也列出来，用户选了之后才被拒 ——
     *    而他完全不知道为什么，因为那条 Key 在语音页明明好着。
     *    把拦截前移到选择阶段：**列表里根本不存在的东西，不会被选中。**
     */
    private fun observeCredentials(flow: kotlinx.coroutines.flow.Flow<List<StoredCredential>>) {
        credentialsJob?.cancel()
        credentialsJob = viewModelScope.launch {
            flow
                .catch { /* 凭据列表读不到只影响表单可选项，不该把整页判死 */ }
                .collect { list ->
                    _state.update { it.copy(llmCredentials = list) }
                }
        }
    }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    // ─────────────────────────────────────────────────────────────
    //  添加表单
    // ─────────────────────────────────────────────────────────────

    fun openEditor() {
        _state.update {
            it.copy(
                // 默认选中默认凭据，没有就选第一条 —— 让用户少做一次选择。
                // 一条都没有时留 null，表单会提示他先去配 Key。
                editor = EditorState(
                    credentialId = it.llmCredentials
                        .firstOrNull { c -> c.isDefault }?.id
                        ?: it.llmCredentials.firstOrNull()?.id,
                ),
                problem = null,
            )
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun editCredential(id: String) = updateEditor { it.copy(credentialId = id, error = null) }
    fun editModelId(value: String) = updateEditor { it.copy(modelId = value, error = null) }
    fun editLabel(value: String) = updateEditor { it.copy(label = value, error = null) }
    fun editTier(tier: ModelTier) = updateEditor { it.copy(tier = tier, error = null) }

    /**
     * 切换一个角色。
     *
     * ⚠️ 允许空集合 —— 不在界面层拦。见仓储 `add()` 的注释：
     *    用户可能先建条目再配用途，靠 `ModelSetupSummary.noRole` 提示他。
     *    在这里拦会让"我想先建好再决定"这个正常流程走不通。
     */
    fun toggleRole(role: ModelRoleEntity) = updateEditor { current ->
        val next = if (role in current.roles) current.roles - role else current.roles + role
        current.copy(roles = next, error = null)
    }

    /**
     * 保存表单里的模型配置。
     *
     * 校验错误（[com.pocketagent.keymgmt.AddModelResult.Rejected]）显示在
     * **表单内**而不是页面顶部 —— 用户要改的是输入框里的内容，
     * 提示必须出现在他要改的地方。
     */
    fun save() {
        val editor = _state.value.editor ?: return
        val repo = repository ?: return

        val credentialId = editor.credentialId
        if (credentialId == null) {
            updateEditor { it.copy(error = "先选一条用于调用模型的 API Key。") }
            return
        }

        viewModelScope.launch {
            updateEditor { it.copy(saving = true, error = null) }

            val result = repo.add(
                credentialId = credentialId,
                modelId = editor.modelId,
                label = editor.label,
                tier = editor.tier.toEntity(),
                roles = editor.roles,
                inputPriceOverride = editor.parsePrice(editor.inputPrice),
                outputPriceOverride = editor.parsePrice(editor.outputPrice),
            )

            when (result) {
                is com.pocketagent.keymgmt.AddModelResult.Added ->
                    _state.update { it.copy(editor = null) }

                is com.pocketagent.keymgmt.AddModelResult.Rejected ->
                    updateEditor { it.copy(saving = false, error = result.reason) }

                is com.pocketagent.keymgmt.AddModelResult.Failed ->
                    updateEditor { it.copy(saving = false, error = result.reason) }
            }
        }
    }

    fun editInputPrice(value: String) = updateEditor { it.copy(inputPrice = value, error = null) }
    fun editOutputPrice(value: String) = updateEditor { it.copy(outputPrice = value, error = null) }

    // ─────────────────────────────────────────────────────────────
    //  列表上的动作
    // ─────────────────────────────────────────────────────────────

    fun setEnabled(id: String, enabled: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.setEnabled(id, enabled) }.onFailure { e ->
                _state.update { it.copy(problem = "切换启用状态失败：${describe(e)}") }
            }
        }
    }

    fun setTier(id: String, tier: ModelTier) {
        val repo = repository ?: return
        viewModelScope.launch {
            runCatching { repo.setTier(id, tier.toEntity()) }.onFailure { e ->
                _state.update { it.copy(problem = "改档位失败：${describe(e)}") }
            }
        }
    }

    /**
     * 切换某个模型已经保存的用途。
     *
     * ⚠️ 与表单里的 [toggleRole] 是两套东西：这里改的是**库里那条记录**，
     *    所以要把当前角色集合完整传下去（仓储的 `setRoleMask` 是整体覆盖，
     *    不是增量）。算错一次就会把另一个角色抹掉。
     */
    fun toggleRoleOf(model: ModelConfig, role: ModelRoleEntity) {
        val repo = repository ?: return
        // ⚠️ 界面领域模型 (ModelRole) 与数据库模型 (ModelRoleEntity) 是两个枚举。
        //    先把库里那组角色整体转成数据库枚举再求差集 ——
        //    混着用会得到一个"看起来算对了、类型却对不上"的编译错误。
        val current = model.roles.toEntityRoles()
        val next = if (role in current) current - role else current + role

        viewModelScope.launch {
            runCatching { repo.setRoles(model.id, next) }.onFailure { e ->
                _state.update { it.copy(problem = "改用途失败：${describe(e)}") }
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

    // ─────────────────────────────────────────────────────────────

    private fun updateEditor(block: (EditorState) -> EditorState) {
        _state.update { current ->
            current.editor?.let { current.copy(editor = block(it)) } ?: current
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e::class.simpleName ?: "未知错误"
}

/**
 * 页面状态。
 *
 * ⚠️ [store] 与 [models] 是**正交**的两件事，不能合成一个"加载中/完成"。
 *    存储没打开时 [models] 是空列表，但那**不代表**用户没配模型 ——
 *    把这两种情况显示成同一句话，用户就会去重新配一遍（而他配过的东西还在库里）。
 *    这正是 `AppContainer.modelRepository()` 注释里点名要求区分的那件事。
 */
data class ModelsUiState(
    val store: StoreState = StoreState.Opening,

    /** 全部模型配置（含禁用）。**不过滤** —— 见 ModelConfigRepository 的注释 */
    val models: List<ModelConfig> = emptyList(),

    /** 体检结论。由 `summarizeModelSetup` 算出，界面直接用 */
    val summary: ModelSetupSummary = summarizeModelSetup(emptyList()),

    /**
     * 可用于挂模型的凭据（**只含 LLM 用途**）。
     *
     * 见 [ModelsViewModel.observeCredentials]：TTS 的 Key 不进这个列表，
     * 因为仓储层会拒绝它，而让用户先选中再被拒是很差的体验。
     */
    val llmCredentials: List<StoredCredential> = emptyList(),

    /** null 表示表单关闭 */
    val editor: EditorState? = null,

    /**
     * 出错时才有值。
     *
     * ⚠️ 刻意**只在出错时**设置：成功不做提示 ——
     *    列表本身的变化（多了一行 / 档位变了 / 徽章变了）就是反馈。
     */
    val problem: String? = null,
)

/**
 * 添加模型的表单。
 *
 * ⚠️ 价格是**字符串**而不是 Double —— 用户可能刚敲了一个 `0.`，
 *    那不是一个合法数字，但也不该被强行变成 0。
 *    `parsePrice` 在提交时才解析，非法输入回落到 null（= 未知）。
 *
 * 注意这里**没有 Key 明文**，所以不需要 `KeysViewModel.EditorState`
 * 那套"绝不 rememberSaveable"的约束 —— 表单里全是无害信息。
 */
data class EditorState(
    val credentialId: String? = null,
    val modelId: String = "",
    val label: String = "",
    val tier: ModelTier = ModelTier.STANDARD,
    val roles: Set<ModelRoleEntity> = setOf(ModelRoleEntity.WORKER),
    val inputPrice: String = "",
    val outputPrice: String = "",
    val error: String? = null,
    val saving: Boolean = false,
) {
    /**
     * 解析用户填的价格。
     *
     * 空串 / 非法输入 → null（= 未知）。
     *
     * ⚠️ **绝不是 0**。把"未知"当成"免费"会让预算熔断完全失效 ——
     *    用户会以为自己在省钱，实际在烧钱。
     *    用户真想表达免费，得明确填 `0`，那才变成 0.0。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 为什么不能直接用 `toDoubleOrNull()`
     * ═══════════════════════════════════════════════════════════
     *
     * Kotlin 的 `"0.".toDoubleOrNull()` 返回的是 **0.0 而不是 null** ——
     * 它接受"小数点后什么都没有"这种写法。同样地 `"1."` → 1.0。
     *
     * 而 `"0."` 恰恰是**用户正在敲 "0.27" 时必然经过的中间态**。
     * 若此时恰好触发了保存（或者用户敲完 "0." 就切走了），
     * 这个模型会被记成**免费** —— 然后预算熔断对它永不生效。
     *
     * 所以这里显式要求：小数点在时，后面必须有数字。
     * 宁可让用户多敲一个字符时看到"无效"，也不要静默地记成 0。
     */
    fun parsePrice(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 只接受：可选前导 0~多个数字 + 可选（小数点 + 至少一位数字）
        // 刻意不用正则：这段规则的意图用代码写出来比正则更好审查。
        val dotIndex = trimmed.indexOf('.')
        if (dotIndex >= 0) {
            // 不止一个小数点 → 非法
            if (trimmed.indexOf('.', dotIndex + 1) >= 0) return null
            // 小数点后必须有数字（"0." / "1." 都是用户没敲完）
            if (dotIndex == trimmed.length - 1) return null
        }

        return trimmed.toDoubleOrNull()?.takeIf { it >= 0.0 }
    }
}

/** 界面档位 → 数据库档位。穷举 `when` 而非 `valueOf`：编译期逼两边同步 */
internal fun ModelTier.toEntity(): ModelTierEntity = when (this) {
    ModelTier.LIGHT -> ModelTierEntity.LIGHT
    ModelTier.STANDARD -> ModelTierEntity.STANDARD
    ModelTier.HEAVY -> ModelTierEntity.HEAVY
}

/**
 * 数据库角色 → 界面角色。
 *
 * ⚠️ 用 `when` 映射而不是 `ModelRole.valueOf(name)`。
 *    两边同名只是巧合，`valueOf` 在两边的枚举常量被改名时
 *    **会编译通过、运行时抛异常** —— 那是能过 CI 的 bug。
 *    返回 `null` 而不是抛异常：认不出的角色（未来版本写进来的）
 *    应该被忽略，而不是让整页崩掉。
 */
internal fun ModelRoleEntity.toRoleOrNull(): com.pocketagent.modelrouter.ModelRole? = when (this) {
    ModelRoleEntity.SCHEDULER -> com.pocketagent.modelrouter.ModelRole.SCHEDULER
    ModelRoleEntity.WORKER -> com.pocketagent.modelrouter.ModelRole.WORKER
}

/** 界面角色 → 数据库角色。同上，穷举 `when` 而非 `valueOf` */
internal fun com.pocketagent.modelrouter.ModelRole.toEntity(): ModelRoleEntity = when (this) {
    com.pocketagent.modelrouter.ModelRole.SCHEDULER -> ModelRoleEntity.SCHEDULER
    com.pocketagent.modelrouter.ModelRole.WORKER -> ModelRoleEntity.WORKER
}

/**
 * 界面角色集合 → 数据库角色集合。**认不出的角色被丢弃**。
 *
 * ⚠️ 这个转换只该在**写入**路径上用（把界面上的勾选存进库）。
 *    读取路径（判断某个勾选框是否选中）也可以用，但要明白：
 *    若库里存了一个我们不认识的角色位，它在这里被静默丢掉，
 *    用户下一次保存就会把它抹掉。这是刻意的取舍 ——
 *    比"整个界面崩掉"或"显示一个点不动的勾选框"都好。
 */
internal fun Iterable<com.pocketagent.modelrouter.ModelRole>.toEntityRoles(): Set<ModelRoleEntity> =
    map { it.toEntity() }.toSet()

/** 数据库角色集合 → 界面角色集合。认不出的位同样被丢弃 */
internal fun Iterable<ModelRoleEntity>.toDomainRoles(): Set<com.pocketagent.modelrouter.ModelRole> =
    mapNotNull { it.toRoleOrNull() }.toSet()
