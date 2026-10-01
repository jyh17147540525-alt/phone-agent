package com.pocketagent.ui.tasks

import com.pocketagent.data.AppContainer
import com.pocketagent.data.CredentialStoreResult
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest

/**
 * 对话端口 —— 任务页与模型之间的**唯一出口**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这一层，而不是在 ViewModel 里直接调 `LlmProvider`
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为**取凭据这件事有生命周期**：`CredentialRepository.useDefaultKey`
 * 在作用域结束时会把解密后的明文清零。ViewModel 直接持有 provider 和
 * credential 会让"什么时候清零"变成调用方自己的责任 ——
 * 而那是一个**看不见的**责任（漏了不会报错，只是明文在内存里多活一会儿）。
 *
 * 端口把"取用 + 清零"收口在一处，ViewModel 只看到一个 `send`。
 *
 * ⚠️ 与 `:agentlogic` 的 `OfficeLlmPort` 是**两个不同的端口**，不要合并：
 *    - `OfficeLlmPort.complete(prompt): String` —— 给**循环**用，纯字符串进出，
 *      因为循环在离线验证器里跑，不能碰任何 Android 类型
 *    - 本端口 —— 给**界面**用，用 `ChatMessage` / `ChatChunk` 这些真实协议类型
 *    合并的代价：要么循环被拖进 Android 依赖（失去离线可测），
 *    要么界面被迫在字符串和协议对象之间来回转换（丢结构化信息）。
 */
interface ChatPort {

    /**
     * 发一轮对话。
     *
     * ⚠️ **不抛异常**。"还没配 Key"是**正常状态**，不是错误 ——
     *    调用方要能把用户引导到 Key 页面，而不是显示一个堆栈。
     */
    suspend fun send(history: List<ChatMessage>): ChatResult
}

/** 一轮对话的结论。三分支 —— 见 [ChatResult.NoCredential] 的注释。 */
sealed interface ChatResult {

    data class Ok(val text: String) : ChatResult

    /**
     * 还没有可用的默认凭据。
     *
     * ⚠️ 与 [Failed] 分开是刻意的：这是**配置问题**（去 Key 页面加一个就行），
     *    而 [Failed] 是**运行问题**（重试 / 换网络）。合并成一句"失败了"
     *    会让用户对着一个他改不了的问题反复点。
     */
    data object NoCredential : ChatResult

    data class Failed(val reason: String) : ChatResult
}

/**
 * [ChatPort] 的实现 —— 走 [AppContainer] 里已经装配好的 provider 与凭据存储。
 *
 * ⚠️ 它**不碰 Keystore、不碰数据库**，只是把两件已经做好的事接起来：
 *    `openCredentialStore()`（可能失败，见 `CredentialStoreResult`）
 *    + `useDefaultKey { ... }`（作用域内解密、结束清零）。
 */
class ContainerChatPort(private val container: AppContainer) : ChatPort {

    override suspend fun send(history: List<ChatMessage>): ChatResult {
        // ── ① 拿到凭据仓库 ──────────────────────────────────────
        val store = when (val opened = container.openCredentialStore()) {
            is CredentialStoreResult.Ready -> opened.repository
            is CredentialStoreResult.Unrecoverable -> return ChatResult.Failed(opened.reason)
            is CredentialStoreResult.Retryable -> return ChatResult.Failed(opened.reason)
        }

        // ── ② 选一个模型 ────────────────────────────────────────
        //
        // ⚠️ 这里刻意**不**做难度路由（`ModelRepository.route`）——
        //    办公对话的第一步是"能说话"，路由是第二步。
        //    而且路由失败时的降级语义（用哪个兜底）需要单独设计，
        //    混进第一版会让"为什么这次用了贵模型"变得无法解释。
        val model = container.modelRepository()?.all()?.firstOrNull()?.id
            ?: return ChatResult.Failed("还没有配置可用的模型。请到「模型」页面添加一个。")

        // ── ③ 在受控作用域内发请求 ──────────────────────────────
        val text = store.useDefaultKey { provider, credential ->
            val sb = StringBuilder()
            provider.chat(
                request = ChatRequest(model = model, messages = history),
                credential = credential,
            ).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Delta -> sb.append(chunk.text)
                    // 推理过程不进正文 —— 它是模型的"草稿"，用户要的是答复
                    is ChatChunk.Reasoning -> Unit
                    // 工具调用在本版不处理（办公循环走的是另一条线）
                    is ChatChunk.ToolCallDelta -> Unit
                    is ChatChunk.Done -> Unit
                }
            }
            sb.toString()
        }

        return when {
            text == null -> ChatResult.NoCredential
            text.isBlank() -> ChatResult.Failed("模型返回了空内容。可能是 Key 无效、额度用完，或该模型不支持对话。")
            else -> ChatResult.Ok(text)
        }
    }
}
