package com.pocketagent.ui.tasks

import com.pocketagent.agentlogic.AgentBudget
import com.pocketagent.agentlogic.AgentMessage
import com.pocketagent.agentlogic.BudgetGuard
import com.pocketagent.agentlogic.budgetStepMisconfiguration
import com.pocketagent.agentlogic.PauseReason
import com.pocketagent.agentlogic.PhoneAgentLoop
import com.pocketagent.agentlogic.PhoneRunResult
import com.pocketagent.agentlogic.PhoneTask
import com.pocketagent.assistant.AgentAdapters
import com.pocketagent.data.AppContainer
import com.pocketagent.data.CredentialStoreResult
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.rootlogic.IntentClassifier
import com.pocketagent.rootlogic.TurnIntent
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.modelrouter.ModelResolution
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.resolveForPurpose
import timber.log.Timber

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
     *
     * @param onStep 每完成一步工具调用就回调一次（步数，做了什么）。
     *   **默认空实现** —— 不关心进度的调用方不用改。
     *
     *   ★ 2026-10-19 真机加：在此之前界面上的「轮次 0 / 8」**全程不动**，
     *     因为 `TasksScreen.runChat` 只在开始设 0、结束设 1，中间从不更新。
     *     用户看到的是一个永远 0 的进度条 + 一句「正在等模型回复…」，
     *     于是无法判断"它到底在动还是卡住了" —— 这正是本项目最忌讳的
     *     「用户看不见它在干什么」。进度必须从循环里真实地流出来，
     *     界面自己猜不出来。
     */
    suspend fun send(
        history: List<ChatMessage>,
        onStep: (step: Int, description: String) -> Unit = { _, _ -> },
    ): ChatResult
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

    override suspend fun send(
        history: List<ChatMessage>,
        onStep: (step: Int, description: String) -> Unit,
    ): ChatResult {
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
        // ⚠️ 这里曾经有一个**只在真机上才暴露**的 bug，两个错叠在一起：
        //
        //   ① 取的是 `ModelConfig.id` —— 那是**数据库行 id（UUID）**；
        //      而 `ChatRequest.model` 要的是 `ModelConfig.modelId`（如 "deepseek-chat"）。
        //      `ModelConfig.id` 的源码注释本来就写着「对应数据库 model_config.id」，
        //      是这里读错了字段 —— **编译期不会有任何提示**。
        //   ② 发请求时用的是 `useDefaultKey`（默认那条 Key），
        //      而**不看这个模型自己绑的 `credentialId`**。
        //      两者不一致时，A 厂商的 Key 打到 B 厂商的端点，B 当然不认识这个模型。
        //
        //   两个错的表现**完全一样**：一说话就报「模型不存在」。
        //   而用户的 Key 和余额都没问题 —— 于是他只会去查钱包，
        //   排查方向完全错。这是本项目头号 bug 形态「安静地少做一件事」的又一例。
        // ★ 2026-10-14：改成**按用途**挑（`ModelRole.CHAT`），不再是
        //   `firstOrNull { it.enabled }`（那等于"永远用最早添加的那条"）。
        //   三级回落在 `resolveForPurpose` 里，每一级都带得出"靠哪一步选中的"。
        val resolution = container.modelRepository()?.all().orEmpty()
            .resolveForPurpose(ModelRole.CHAT)
        val config = when (resolution) {
            is ModelResolution.Found -> resolution.config
            ModelResolution.None -> return ChatResult.Failed(
                "还没有可用的模型。请到「设置 → 模型配置」添加一个，并确认它处于启用状态。",
            )
        }
        // ⚠️ 回落时记一笔 —— 界面上也有提示（模型页会标出"对话用它"），
        //    但日志是排查时唯一的线索：用户说"回答变笨了"时，
        //    第一件要确认的事就是"它到底用了哪个模型"。
        if (resolution is ModelResolution.Found && resolution.via.isFallback) {
            Timber.i("聊天模型是回落选中的（%s）：%s", resolution.via.name, config.modelId)
        }
        val model = config.modelId

        // ── ③ 组装系统提示词 ────────────────────────────────────
        //
        // ★ 这是「助理是谁」的**唯一入口**：人格装配器的输出 + 能力边界声明。
        //   在此之前它是一条写死在界面里的常量 —— 用户改口癖、改称呼，
        //   模型那边一个字都不会变，而且**没有任何地方会报错**。
        //
        // ⚠️ 刻意**不检查** `history` 里有没有 SYSTEM 消息 ——
        //    "检查并跳过"会让"我塞了但它没生效"变成一种静默失败。
        //    约定写在这里：**调用方只给 USER / ASSISTANT 消息。**
        //
        // ⚠️ 组装失败时**如实报错**，不退化成"没有人格也能聊"：
        //    后者正是本项目最怕的那种失败 —— 用户以为人格生效了，其实没有。
        val system = try {
            container.assistantSystemPrompt()
        } catch (e: Exception) {
            return ChatResult.Failed(
                "人格提示词组装失败（${e::class.simpleName ?: "未知"}）：" +
                    "${e.message ?: "无详细信息"}。为避免在「没有人格」的状态下继续对话，本次已中止。",
            )
        }
        // ⚠️ 这里**不再自己拼 `ChatMessage` 列表**：系统提示词与历史都由
        //    `PhoneTask` 带进循环（`priorMessages`），循环负责把三者
        //    （系统 / 历史 / 本轮）按正确顺序组装。
        //    在这里再拼一次会出现**两份系统提示词** —— 见 `AgentAdapters.llmPort` 的注释。

        // ── ④ 在受控作用域内跑 agent 循环 ────────────────────────
        // ⚠️ 用**这个模型自己绑的那条 Key**，不是「默认那条」—— 见上面 ② 的注释。
        //
        // ═══════════════════════════════════════════════════════
        //  ★★ 2026-10-19：聊天第一次真的能「动手」
        // ═══════════════════════════════════════════════════════
        //
        // 在此之前这里是"发一次请求，收完整段文本就结束" ——
        // 于是应用内聊天**永远不会调用任何工具**：`ChatRequest` 不带 `tools`，
        // 而 `ChatChunk.ToolCallDelta` 被直接丢掉（旧代码在这里写着
        // "工具调用在本版不处理"）。用户说"帮我打开微信"，模型只能回答
        // "我做不到" —— 而它明明有一套能读屏、能点击的工具。
        //
        // 现在改成走 `PhoneAgentLoop`：它会带上工具清单、接住模型的
        // function calling、真的去执行、把结果喂回去，直到模型给出答复。
        //
        // ⚠️ 两个不变的东西：
        //   · **不流式**（`ChatResult.Ok` 仍是整段返回）—— 流式是另一刀，
        //     见 `StreamPump`（数据层已备好，接线未做）；
        //   · 循环的所有保护（预算 / 步数 / 重复检测 / 安全拒绝不许重试）
        //     都在 `:agentlogic` 里，有 24 条离线测试钉着。
        val text = try {
            // ══════════════════════════════════════════════════════
            //  ★ 先判意图：默认**纯对话**，只有要办事才挂工具
            // ══════════════════════════════════════════════════════
            //
            // 改之前：每一次对话都把全部 11 个工具挂上去。三个真问题：
            //  1. **模型被工具带偏** —— 问「今天几号」它可能先 screen_read
            //     看一眼再答，用户只是随口一问，却付出读屏的代价和延迟。
            //  2. **每轮都在为用不到的东西付费** —— 11 个工具的 JSON Schema
            //     每次请求都要发，纯聊天根本不需要。
            //  3. **安全面被无必要地放大** —— 能调工具就有调错的可能，
            //     而纯聊天是**零风险**的。
            //
            // ⚠️ 判错的代价**不对称**：
            //   该 Act 判成 Chat → 用户再说一遍，损失一轮
            //   该 Chat 判成 Act → **助理真的去操作了用户的手机**，不可撤销
            //   ⇒ 所以 `IntentClassifier` 一律偏保守（拿不准就当 Chat）。
            //
            // ⚠️ 纯对话**不经过 agent 循环**：不挂工具就该直接问模型要文字，
            //   走循环只会让 `PhoneAgentLoop` 拿到一个空工具列表然后
            //   多跑一圈 —— 那圈唯一的产出是"确认没有工具可调"。
            val userText = history.lastOrNull()?.plainText() ?: ""
            val decision = IntentClassifier.classify(userText)

            Timber.i("意图判定：%s（%s）—— 输入 %d 字", decision.intent, decision.reason, userText.length)

            store.useKey(config.credentialId) { provider, credential ->
                // ── 纯对话：一次性请求，零工具 ────────────────────
                if (decision.intent == TurnIntent.Chat) {
                    return@useKey chatOnly(provider, credential, model, history, system)
                }

                // ★ 按**实际生效的模式**取工具集，不是全量：
                //   root 不可用时 `root_exec` 根本不出现在清单里 ——
                //   模型看不到它，也就不会去调它。
                //   （见 `AppContainer.toolsForCurrentMode` 的长注释：
                //     root 工具刻意**不**进 `agentTools`，否则 dsh 那条链路
                //     也会看到它，等于绕过了用户选的模式。）
                val tools = container.toolsForCurrentMode()
                Timber.i(
                    "本次工具集 %d 个（模式=%s）：%s",
                    tools.size,
                    container.effectivePrivilegeMode(),
                    tools.joinToString("、") { it.name },
                )
                // ⚠️ 预算按**聊天**场景放宽：用户在聊天框里说一句
                //    "给妈妈发消息"，背后可能是 4~5 次工具调用，
                //    而"打开微信发朋友圈"要开应用 → 找入口 → 写内容 → 发布，
                //    光找入口就要好几轮。原来的 12 轮真机上不够用。
                //
                // ★ 这两个数的关系由 `budgetStepMisconfiguration` 把关
                //   （见它的长注释：相等会让 StepLimitReached 变成死代码）。
                //   配置错了就在**这里**炸，而不是让用户看到一串"预算超限"。
                budgetStepMisconfiguration(
                    maxTurns = CHAT_MAX_TURNS,
                    maxSteps = CHAT_MAX_STEPS,
                )?.let { throw IllegalStateException("聊天预算配置非法：$it") }

                val loop = PhoneAgentLoop(
                    llm = AgentAdapters.llmPort(
                        provider = provider,
                        credential = credential,
                        model = model,
                    ),
                    runner = AgentAdapters.toolRunner(tools),
                    budgetGuard = BudgetGuard(AgentBudget(maxTurns = CHAT_MAX_TURNS)),
                )
                val result = loop.run(
                    PhoneTask(
                        id = "chat",
                        userInput = history.lastOrNull()?.plainText() ?: "",
                        systemPrompt = system,
                        tools = AgentAdapters.toolSpecs(tools),
                        // ⚠️ 只把**用户与助理**的发言带进去（不含系统提示词，
                        //    也不含本轮输入）—— `PhoneTask` 会把系统与本轮
                        //    各接一次（见它自己的注释）。
                        priorMessages = history.dropLast(1).map { it.toAgentMessage() },
                        maxSteps = CHAT_MAX_STEPS,
                    ),
                    // ★ 把循环的真实进度透给界面。
                    //   界面自己猜不出来它走到第几步 —— 2026-10-19 真机上
                    //   进度条全程停在「0 / 8」，用户无法区分"在动"和"卡死"。
                    onStep = onStep,
                )
                // ★ 四种结局都要翻成**用户能理解**的一句话（见 explainRun）。
                explainRun(result)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消必须原样上抛，不许吞
        } catch (e: Exception) {
            return ChatResult.Failed(explain(e))
        }

        return when {
            text == null -> ChatResult.NoCredential
            text.isBlank() -> ChatResult.Failed("模型返回了空内容。可能是 Key 无效、额度用完，或该模型不支持对话。")
            else -> ChatResult.Ok(text)
        }
    }

    /**
     * **纯对话**路径 —— 一次性请求，**零工具**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么它必须独立于 agent 循环
     * ═══════════════════════════════════════════════════════════════
     *
     * 把空工具列表塞进 `PhoneAgentLoop` 也能跑，但产出的是**同一个答复
     * 加一整圈循环开销**：每轮都要判预算、判步数、判重复、记 trace。
     * 而纯对话的全部需求只有一句"发出去、收文字"。
     *
     * ⚠️ 更重要的是**语义**：走循环意味着"这次要办事"，
     *    于是任何未来的循环级副作用（审计、进度上报、接管条）
     *    都会在纯聊天时被触发。用户问「今天几号」不该产生一条任务记录。
     *
     * @return 答复文本；null 表示模型没给出内容
     */
    private suspend fun chatOnly(
        provider: LlmProvider,
        credential: ProviderCredential,
        model: String,
        history: List<ChatMessage>,
        system: String,
    ): String? {
        // ⚠️ tools 传空列表（不是 null）：`ChatRequest` 的默认值就是 emptyList，
        //    而传 null 需要它可空。空列表在这里的语义是"明确不提供工具"。
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage.system(system)) + history,
            tools = emptyList(),
        )

        val sb = StringBuilder()
        provider.chat(request = request, credential = credential).collect { chunk ->
            when (chunk) {
                is ChatChunk.Delta -> sb.append(chunk.text)
                // 推理过程不进正文（同 `AgentAdapters` 的既定纪律）
                is ChatChunk.Reasoning -> Unit
                // 纯对话不该有工具调用 —— 有的话说明模型忽略了空 tools，
                // 直接丢弃：执行它等于绕过意图判定。
                is ChatChunk.ToolCallDelta -> {
                    Timber.w("纯对话路径收到工具调用分片（%s），已忽略", chunk.name)
                }
                is ChatChunk.Done -> Unit
            }
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * 把循环的四种结局翻成**用户能理解的一句话**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么四种都要分开说，不能统一成"出错了"
     * ═══════════════════════════════════════════════════════════════
     *
     * 因为这四种的**下一步完全不同**：
     *
     * | 结局 | 用户该做什么 |
     * |---|---|
     * | 被安全拦下 | **他自己来**（重试必然再被拦，那是撞墙） |
     * | 步数用完 | 说"接着来"，可以继续 |
     * | 预算超限 | 同上，且要知道是哪种资源用完了 |
     * | 重复/卡死 | 换个说法重说一遍 |
     * | 模型调用失败 | 查网络 / 查 Key |
     *
     * 统一成"任务失败"会让用户对着一个他没法判断的问题反复点。
     * ★ 尤其是 [PauseReason.SAFETY_BLOCKED]：它**不是故障**，是安全边界
     *   在起作用 —— 文案必须让用户知道"这件事得你自己做"，
     *   而不是"它坏了，再试一次"。
     */
    private fun explainRun(result: PhoneRunResult): String = when (result) {
        is PhoneRunResult.Answered -> result.text

        is PhoneRunResult.StepLimitReached ->
            "这件事还没做完（已经做了 ${result.steps} 步）。如果还想继续，跟我说一声「接着来」。"

        is PhoneRunResult.Stopped -> when (result.pause) {
            PauseReason.SAFETY_BLOCKED ->
                "这一步被安全策略拦下了，我没有继续尝试。\n\n" +
                    "${result.reason}\n\n" +
                    "这类操作（支付、密码、验证码等）需要你自己完成 —— 我可以把步骤讲给你，但不会代做。"

            PauseReason.BUDGET ->
                "先停一下：${result.reason}\n\n可以跟我说「接着来」，也可以先告诉我换个做法。"

            PauseReason.LOOP_DETECTED ->
                "我好像卡住了（${result.reason}）。\n\n换个说法或者告诉我换个做法，我再来一次。"

            PauseReason.MODEL_ERROR ->
                "联系模型的时候出错了：${result.reason}"
        }
    }
}

/**
 * 聊天场景的预算与步数上限。
 *
 * ⚠️ 刻意与 `PhoneTask.DEFAULT_MAX_STEPS`（12）分开，且**更大**：
 *    那里的 12 是"替用户操作手机"（每一步都是真实点击，代价不可逆）；
 *    这里是"聊天框里说一句"（用户全程看着，随时能喊停）。
 *    两者共用一个数字会让其中一个必然不合适。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-10-19 真机实测后调大，顺带修掉一处**注释与实现不符**
 * ═══════════════════════════════════════════════════════════════
 *
 * 原来的值是 `TURNS = 12` 与 `STEPS = 12` —— 而这恰好等于
 * `PhoneTask.DEFAULT_MAX_STEPS`（12），于是上面那句"刻意更大"
 * **是不成立的**：它并没有更大，只是**一样大**。
 *
 * 后果在真机上很具体：用户说「打开微信，发一条朋友圈，自我介绍」，
 * 12 轮用完了连朋友圈编辑页都没走到，卡片显示
 * 「预算超限（TURNS）：轮次已达上限 12」。
 * 多步任务（开应用 → 找入口 → 写内容 → 确认发布）光"找入口"就要好几轮。
 *
 * ⚠️ **两个数不能相等**（这一条比数值本身更重要）：
 *    `PhoneAgentLoop` 里预算检查在步数检查**之前**，
 *    两者相等时永远是 `BUDGET` 先中止，`StepLimitReached` 这条
 *    分支**永远不会被执行** —— 那是一段死代码，而它的文案
 *    （"已经做了 N 步，说「接着来」"）本来是更友好的那个。
 *    ⇒ 步数必须**小于**预算，让"做完了该做的事"先于"资源用尽"触发。
 */
internal const val CHAT_MAX_TURNS = 20

/**
 * 聊天链路的步数上限。
 *
 * ⚠️ `internal` 而不是 `private`：**界面要拿它当进度条的分母**。
 *    2026-10-19 真机上进度条写着「0 / 8」而实际额度是 16 —— 分母是另一个数。
 *    显示的数字与实际用的数字来自两处，是"用户觉得软件有毛病"的经典来源。
 *    ⇒ 唯一事实来源在这里，界面必须引用它，不要另写一个。
 */
internal const val CHAT_MAX_STEPS = 16

/** 取一段消息里的纯文本（工具调用等非文本片段忽略）。 */
private fun ChatMessage.plainText(): String =
    content.filterIsInstance<com.pocketagent.provider.api.ContentPart.Text>()
        .joinToString("") { it.text }

/**
 * `provider.api` 的消息 → `:agentlogic` 的消息。
 *
 * ⚠️ **只翻 USER / ASSISTANT 的文本**。工具调用与工具结果**不翻** ——
 *    因为 `ChatPort` 的入参 `history` 约定只含 USER / ASSISTANT
 *    （见它自己的注释），而循环内部自己维护工具那一轮的历史。
 *    把历史里的工具消息也翻过去会让模型看到**上一次会话**的工具调用，
 *    而它对应的那次执行早已结束 —— 那是编故事。
 */
private fun ChatMessage.toAgentMessage(): AgentMessage = when (role) {
    ChatMessage.Role.USER -> AgentMessage.User(plainText())
    else -> AgentMessage.Assistant(plainText())
}

    /**
     * 把服务端的报错翻译成「用户下一步该做什么」。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么必须翻译：厂商那句话会把排查方向带偏
     * ═══════════════════════════════════════════════════════════════
     *
     * 真实案例（内测用户，2026-10-12）：配了 `glm5.3flash`，服务端回
     * 「模型不存在，请检查模型代码」。用户看到这句话的反应是
     * **去查钱包余额、查 Key 有没有过期** —— 而真正的答案是
     * **厂商的模型代码带连字符**（`glm-5.3-flash`）。
     *
     * 这不是用户的错：内置清单里没有这个模型，他只能手打；
     * 而手打丢连字符是最自然的错误。⇒ 治本是把 id 做成可选项（已做），
     * 治标是**在这里把方向纠正过来**。
     *
     * ⚠️ 刻意只在命中「模型不存在」时追加提示，其它报错原样透传 ——
     *    猜错了方向再给一条错误提示，比不给更糟。
     */
    private fun explain(e: Exception): String {
        val raw = e.message ?: e::class.simpleName ?: "未知错误"
        val modelMissing = "模型不存在" in raw ||
            ("model" in raw.lowercase() && "not found" in raw.lowercase())
        return if (modelMissing) {
            "$raw\n\n" +
                "提示：这句话几乎总是「模型 id 抄错了」。厂商的模型代码**带连字符**，" +
                "例如智谱是 glm-5.3-flash 而不是 glm5.3flash。" +
                "请到「设置 → 模型配置」核对 id 是否与厂商文档逐字符一致 —— " +
                "它与你的 Key 和余额无关。"
        } else {
            raw
        }
    }
