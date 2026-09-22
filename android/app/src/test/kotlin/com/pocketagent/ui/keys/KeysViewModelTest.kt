package com.pocketagent.ui.keys

import com.google.common.truth.Truth.assertThat
import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.keymgmt.StoredCredential
import com.pocketagent.provider.api.AudioChunk
import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.SpeechRequest
import com.pocketagent.provider.api.TokenUsage
import com.pocketagent.provider.api.TtsCapability
import com.pocketagent.provider.api.TtsProvider
import com.pocketagent.provider.api.VoiceInfo
import org.junit.Test

/**
 * API Key 页的纯逻辑测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里测的是"写错了不会报错"的那几个地方
 * ═══════════════════════════════════════════════════════════════
 *
 * 这一页真正的存储与校验逻辑全在 `keymgmt`（那里有 96 个用例），
 * 留在这里的只有分组的派生规则和表单状态的默认值 ——
 * 而这两处的共同点是：**错了不报错，只是安静地给一个看起来对的答案**。
 *
 * 最典型的是「用途透传」。`EditorState.purpose` 若在某条路径上没被正确赋值，
 * 保存依然成功、列表依然多出一行、界面毫无异样 ——
 * 但那条 Key 从此永远发不出它该发的请求。
 */
class KeysViewModelTest {

    // ═══════════════════════════════════════════════════════════
    //  分组派生
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `按用途取凭据只返回该用途的`() {
        val state = KeysUiState(
            credentials = listOf(
                credential("llm-1", CredentialPurpose.LLM),
                credential("tts-1", CredentialPurpose.TTS),
                credential("llm-2", CredentialPurpose.LLM),
            )
        )

        assertThat(state.credentialsOf(CredentialPurpose.LLM).map { it.id })
            .containsExactly("llm-1", "llm-2").inOrder()
        assertThat(state.credentialsOf(CredentialPurpose.TTS).map { it.id })
            .containsExactly("tts-1")
    }

    @Test
    fun `某个用途一个都没有时返回空列表而不是全部`() {
        // ★ 这是分组派生最容易写错的一条。
        //
        //   若实现写成 `if (list.any { it.purpose == p }) list else emptyList()`
        //   之类的反向逻辑，或者干脆 filter 写漏，最坏的结果是
        //   **语音那一栏显示出模型 Key** —— 而用户会以为它是给语音用的。
        //
        //   最常见的数据状态恰恰就是这个：配了 3 个模型 Key，语音一条没有。
        val state = KeysUiState(
            credentials = listOf(
                credential("llm-1", CredentialPurpose.LLM),
                credential("llm-2", CredentialPurpose.LLM),
            )
        )

        assertThat(state.credentialsOf(CredentialPurpose.TTS)).isEmpty()
        assertThat(state.credentialsOf(CredentialPurpose.LLM)).hasSize(2)
    }

    @Test
    fun `空列表在两种用途上都是空的`() {
        val state = KeysUiState()

        CredentialPurpose.entries.forEach { purpose ->
            assertThat(state.credentialsOf(purpose)).isEmpty()
        }
    }

    @Test
    fun `每个用途都有对应的分组`() {
        // 穷举 —— 将来给 CredentialPurpose 加第三个用途（比如嵌入模型）时，
        // 这条会逼着实现者确认"界面上它归哪一栏"。
        // 顺带钉死一件事：`entries` 里的每个值都必须能被 filter 出来，
        // 不会被某个写成 `!=` 的分支悄悄漏掉。
        val all = CredentialPurpose.entries.map { purpose ->
            credential("id-${purpose.name}", purpose)
        }

        val state = KeysUiState(credentials = all)

        CredentialPurpose.entries.forEach { purpose ->
            assertThat(state.credentialsOf(purpose).map { it.id })
                .containsExactly("id-${purpose.name}")
        }
    }

    @Test
    fun `分组不改变凭据的原有顺序`() {
        // ★ 仓储两个 Flow 都按 `createdAtMillis` 升序。分组是**筛选**，
        //   不该顺便排序 —— 一旦这里冒出个 sortedBy，用户会看到
        //   每次重组列表都在跳。
        val state = KeysUiState(
            credentials = listOf(
                credential("a", CredentialPurpose.LLM, createdAtMillis = 3_000),
                credential("b", CredentialPurpose.LLM, createdAtMillis = 1_000),
                credential("c", CredentialPurpose.LLM, createdAtMillis = 2_000),
            )
        )

        assertThat(state.credentialsOf(CredentialPurpose.LLM).map { it.id })
            .containsExactly("a", "b", "c").inOrder()
    }

    // ═══════════════════════════════════════════════════════════
    //  表单的用途
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `新表单默认不选中服务商之外的任何东西`() {
        // 用途是构造函数的必填参数（没有默认值）—— 这条测试存在的意义是
        // **把这件事钉住**：一旦有人给它加了默认值，
        // "给语音页加 Key"就会静默退化成"又加了一条模型 Key"。
        val editor = EditorState(purpose = CredentialPurpose.TTS)

        assertThat(editor.purpose).isEqualTo(CredentialPurpose.TTS)
        assertThat(editor.providerId).isNull()
        assertThat(editor.keyInput).isEmpty()
        assertThat(editor.saving).isFalse()
    }

    @Test
    fun `表单的用途可以独立于服务商存在`() {
        // 两者都是独立字段，不是从彼此推出来的。
        // 曾经的设想是"从服务商推断用途"，但那要求 providerId → purpose
        // 是一一映射 —— 而同一家服务商将来完全可能同时提供模型和语音。
        val editor = EditorState(
            purpose = CredentialPurpose.TTS,
            providerId = "some-asr-vendor",
        )

        assertThat(editor.purpose).isEqualTo(CredentialPurpose.TTS)
        assertThat(editor.providerId).isEqualTo("some-asr-vendor")
    }

    // ═══════════════════════════════════════════════════════════
    //  跨组查找
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `跨组的 id 查找仍然命中`() {
        // ★ `KeysViewModel.validate()` 靠这个在主列表里回查那一条。
        //   分组之后查的是**扁平的那一份**（`credentials`）而不是某一栏，
        //   所以无论那条 Key 属于哪一组都能查到 ——
        //   若哪天改成在某一栏里查，TTS 的校验结论会永远回填不上，
        //   而界面上只是"点了校验没反应"。
        val state = KeysUiState(
            credentials = listOf(
                credential("llm-1", CredentialPurpose.LLM),
                credential("tts-1", CredentialPurpose.TTS),
            )
        )

        assertThat(state.credentials.firstOrNull { it.id == "tts-1" }
            ?.purpose).isEqualTo(CredentialPurpose.TTS)
        assertThat(state.credentials.firstOrNull { it.id == "llm-1" }
            ?.purpose).isEqualTo(CredentialPurpose.LLM)
    }

    // ═══════════════════════════════════════════════════════════
    //  初始状态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `初始状态是正在打开存储`() {
        // 不能默认成 Ready —— 那会让界面在存储还没打开时先渲染出一句
        // "还没有配置 API Key"，然后过一秒变成 3 条。
        // 用户看到的是"我的 Key 消失了一下"。
        val state = KeysUiState()

        assertThat(state.store).isEqualTo(StoreState.Opening)
        assertThat(state.credentials).isEmpty()
        assertThat(state.editor).isNull()
        assertThat(state.problem).isNull()
    }

    @Test
    fun `problem 默认为空即成功不提示`() {
        // 见 KeysUiState.problem 的注释：列表本身的变化就是反馈。
        // 这条测试防的是"顺手加一句保存成功提示"。
        val state = KeysUiState(credentials = listOf(credential("a", CredentialPurpose.LLM)))

        assertThat(state.problem).isNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  候选服务商按用途区分
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `模型用途只列出模型服务商`() {
        // ★ 这条防的是"两个列表混了"。
        //
        //   混了之后用户会在语音栏看到 11 家模型厂商，选中、填 Key、保存 ——
        //   然后被仓储拒绝（"不认识的服务商"）。而他会以为是自己填错了，
        //   反复重试。**拦截必须前移到选择阶段。**
        val state = KeysUiState(
            providers = listOf(fakeLlmProvider("openai"), fakeLlmProvider("deepseek")),
            ttsProviders = listOf(fakeTtsProvider("siliconflow-tts")),
        )

        val choices = state.providerChoicesFor(CredentialPurpose.LLM)

        assertThat(choices.map { it.first }).containsExactly("openai", "deepseek").inOrder()
        assertThat(choices.map { it.first }).doesNotContain("siliconflow-tts")
    }

    @Test
    fun `语音用途只列出语音服务商`() {
        val state = KeysUiState(
            providers = listOf(fakeLlmProvider("openai"), fakeLlmProvider("deepseek")),
            ttsProviders = listOf(fakeTtsProvider("siliconflow-tts")),
        )

        val choices = state.providerChoicesFor(CredentialPurpose.TTS)

        assertThat(choices.map { it.first }).containsExactly("siliconflow-tts")
        assertThat(choices.map { it.first }).doesNotContain("openai")
    }

    @Test
    fun `同一家厂商在两条线上是两个不同的 id`() {
        // ★ OpenAI 既有对话也有语音，但 id 是 `openai` 和 `openai-tts`。
        //
        //   这不是重复，是刻意的：两条线的协议、端点、计费口径都不一样。
        //   共用一个 id 会让"这条 Key 该走哪条校验路径"变成猜谜 ——
        //   而猜错的表现是"Key 明明好着却校验失败"。
        val state = KeysUiState(
            providers = listOf(fakeLlmProvider("openai")),
            ttsProviders = listOf(fakeTtsProvider("openai-tts")),
        )

        assertThat(state.providerChoicesFor(CredentialPurpose.LLM).single().first)
            .isEqualTo("openai")
        assertThat(state.providerChoicesFor(CredentialPurpose.TTS).single().first)
            .isEqualTo("openai-tts")
    }

    @Test
    fun `候选携带展示名而不只是 id`() {
        // 界面直接拿它渲染 chip。只给 id 会让用户看到 `siliconflow-tts`
        // 这种内部标识符 —— 那是这一页唯一一处不该出现的东西。
        val state = KeysUiState(ttsProviders = listOf(fakeTtsProvider("siliconflow-tts")))

        assertThat(state.providerChoicesFor(CredentialPurpose.TTS).single())
            .isEqualTo("siliconflow-tts" to "语音服务商 siliconflow-tts")
    }

    @Test
    fun `没有语音服务商时返回空列表`() {
        // ⚠️ 这个状态必须能被表达 —— 界面靠它显示"还没接进来"。
        //    返回非空（比如回落到模型服务商）会让语音栏列出 11 家模型厂商。
        val state = KeysUiState(providers = listOf(fakeLlmProvider("openai")))

        assertThat(state.providerChoicesFor(CredentialPurpose.TTS)).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助
    // ═══════════════════════════════════════════════════════════

    private fun fakeLlmProvider(id: String): LlmProvider = object : LlmProvider {
        override val id: String = id
        override val displayName: String = "模型服务商 $id"
        override val authScheme: AuthScheme = AuthScheme.Bearer
        override val defaultBaseUrl: String = "https://example.com/v1"
        override fun capabilities(): Set<Capability> = emptySet()
        override suspend fun validateKey(credential: ProviderCredential) =
            KeyValidationResult.Invalid

        override suspend fun listModels(credential: ProviderCredential): List<ModelInfo>? = null
        override fun chat(request: ChatRequest, credential: ProviderCredential) =
            kotlinx.coroutines.flow.emptyFlow<ChatChunk>()

        override fun countTokens(messages: List<ChatMessage>, model: String): Int = 0
        override fun estimateCost(usage: TokenUsage, model: String) = Cost(0.0)
    }

    private fun fakeTtsProvider(id: String): TtsProvider = object : TtsProvider {
        override val id: String = id
        override val displayName: String = "语音服务商 $id"
        override val authScheme: AuthScheme = AuthScheme.Bearer
        override val defaultBaseUrl: String = "https://example.com/v1"
        override fun capabilities(): Set<TtsCapability> = emptySet()
        override suspend fun validateKey(credential: ProviderCredential) =
            KeyValidationResult.Invalid

        override suspend fun listVoices(credential: ProviderCredential): List<VoiceInfo>? = null
        override fun synthesize(request: SpeechRequest, credential: ProviderCredential) =
            kotlinx.coroutines.flow.emptyFlow<AudioChunk>()

        override fun estimateCost(request: SpeechRequest) = Cost(0.0)
    }

    private fun credential(
        id: String,
        purpose: CredentialPurpose,
        createdAtMillis: Long = 0L,
    ) = StoredCredential(
        id = id,
        providerId = "provider-$id",
        purpose = purpose,
        providerDisplayName = "服务商 $id",
        label = "",
        keyLength = 32,
        baseUrlOverride = null,
        createdAtMillis = createdAtMillis,
        lastCheckedAtMillis = null,
        status = CredentialCheckStatus.UNCHECKED,
        statusDetail = null,
        modelCount = null,
        isDefault = false,
    )
}
