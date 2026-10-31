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
import com.pocketagent.legacy.ArmorBreak
import com.pocketagent.legacy.Refusal
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
import kotlinx.coroutines.launch
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
        /**
         * **正文增量**。纯对话路径每收到一片就回调一次 —— 界面据此逐字显示。
         *
         * ═══════════════════════════════════════════════════════════
         *  ★★ 2026-10-28：为什么必须流式
         * ═══════════════════════════════════════════════════════════
         *
         * 用户原话："对话响应太慢。"
         *
         * 实测下来"慢"不等于"模型慢"：一段 200 字的回答，模型 3 秒就
         * 开始吐字了，但界面要**等全部吐完**才把字画出来 —— 于是用户的
         * 体感是"按了发送之后盯着'正在等模型回复…'发呆 8 秒"。
         *
         * ⇒ 首字延迟才是体感延迟。流式把它从"整段完成"降到"第一片到达"。
         *
         * ⚠️ 只对**纯对话**生效。执行类任务（agent 循环）的正文是循环
         *    跑完之后才定稿的（工具调用的中间话术不能当回答显示），
         *    强行流式会把"我先看看当前页面"当正文吐给用户。
         *    那一类靠 `onStep` 报进度。
         */
        onDelta: (delta: String) -> Unit = {},
        /**
         * **压缩记忆**段落（来自 `:memorylogic` 的 `ContextCompressor`）。
         *
         * ★ 用户要的是"上下文压缩后分段融入记忆分层"。压缩是**会话级**的，
         *   所以它不能塞进全局的 `assistantSystemPrompt`（那会被别的会话看到）。
         *   由调用方按当前会话算好、传进来。
         *
         * ⚠️ 空串 = 这一轮没有压缩记忆，**不要补一句"（无）"** ——
         *   那会让模型以为自己失忆了。
         */
        compressedMemory: String = "",
        /**
         * **用户这一轮附的图片**（PNG/JPEG 字节）。
         *
         * ═══════════════════════════════════════════════════════
         *  ★★ 2026-10-31：给模型"装眼睛"
         * ═══════════════════════════════════════════════════════
         *
         * 用户："虽然在 app 里能支持图片输入，但是模型还是看不了图片，
         *      因为你没有给模型装上眼睛。"
         *
         * 根因：附件被拼成一段**文字路径**塞进 prompt。而 provider 层
         * 其实早就能发 `ContentPart.Image`（`image_url` + data URI
         * 全都实现了）—— 缺的只是"把字节交到这里"。
         *
         * ⇒ 现在真的作为图片发：它会被 `attachImage` 挂在**最后一条
         *   用户消息**上，模型直接看到像素。
         *
         * ⚠️ null = 这一轮没有图。**不能**传空 ByteArray：
         *    那会生成一个 0 字节的 data URI，多数厂商返回 400。
         */
        image: ByteArray? = null,
        /** 图片 MIME。null 时按 `image/jpeg`（我们用 `ImagePrep` 压过）。 */
        imageMimeType: String = "image/jpeg",
    ): ChatResult
}

/** 一轮对话的结论。三分支 —— 见 [ChatResult.NoCredential] 的注释。 */
sealed interface ChatResult {

    /**
     * 模型给出了答复。
     *
     * @param isAgentRun 这一轮**真的动了手**（走过 agent 循环、调用过工具）。
     *
     *   ★ 界面靠它决定用哪种形态：
     *     `false` → 气泡流（ChatGPT 式对话）
     *     `true`  → 横穿卡片（带进度与中止）
     *
     *   ⚠️ 默认 `false`：绝大多数轮次是纯对话，卡片是例外。
     *      让"例外"需要显式声明，比让"常态"需要声明更不容易出错。
     */
    data class Ok(
        val text: String,
        val isAgentRun: Boolean = false,
    ) : ChatResult

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
        onDelta: (delta: String) -> Unit,
        compressedMemory: String,
        image: ByteArray?,
        imageMimeType: String,
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

        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-26 真机：用户在发消息之后才看到 400
        // ══════════════════════════════════════════════════════════
        //
        // 现场：配了 `glm5.3flash`（少个连字符）并设为"对话用它"，
        // 于是每次说话都得到「请求被拒绝（400）：模型不存在」——
        // 而**正确的 `glm-5.3-flash` 就在同一个配置页里**，只是没被选中。
        //
        // ⚠️ 这类错误是**必然失败**的：id 抄错 100% 会 400，不存在运气。
        //    而它发生在用户等了一个网络往返之后。
        //
        // ⇒ 在这里**提前拦**，用一句话告诉他"你可能想写的是那个"。
        //    ⚠️ 但**不替他改** —— 改配置是用户的决定（同 ModelSetupSummary
        //       的立场：宁可提示，不替用户做主）。
        runCatching {
            val all = container.modelRepository()?.all().orEmpty()
            com.pocketagent.modelrouter.ModelIdSanity
                .suspiciousChatModel(all, config)
        }.getOrNull()?.let { finding ->
            return ChatResult.Failed(
                finding.message() +
                    "\n\n（我没有替你改配置 —— 去「设置 → 模型配置」把它改成正确的那条。）",
            )
        }

        // ── ③ 判断意图 + 组装系统提示词 ──────────────────────────
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
    val userText = history.lastOrNull()?.plainText() ?: ""
    val decision = IntentClassifier.classify(userText)

    Timber.i("意图判定：%s（%s）—— 输入 %d 字", decision.intent, decision.reason, userText.length)

    // 界面靠它分流形态：气泡流 vs 横穿卡片（见 AgentTaskUi.isAgentRun）
    val didAct = decision.intent == TurnIntent.Act

    // ★ 未亡人模式的冲突机制靠这一句运转。
    //   ⚠️ 必须在组装提示词**之前**调：`assistantSystemPrompt` 会读取
    //      `legacyConflicts` 决定"这轮该不该吵 / 该不该提旧账"。
    container.recordUserTurn(userText)

    // ★ 能力声明必须与**这一轮实际挂载的工具**一致。
    //   ⚠️ 所以它只能在意图判定**之后**组装。原来在前面，于是纯对话那一轮
    //      的系统提示词里写着"你可以直接操作手机" —— 模型照着说，
    //      用户真去让它操作时发现做不到。
    val system = try {
        container.assistantSystemPrompt(
            canAct = decision.intent == TurnIntent.Act,
            currentUserText = userText,
            // ★ 去味诊断的输入：最近的助理回复。
            //   ⚠️ 从 `history` 里取，不另外存一份 —— 那份副本会和
            //      真实对话历史漂移，而漂移之后诊断的就是"它没说过的话"。
            recentReplies = history
                .filter { it.role == ChatMessage.Role.ASSISTANT }
                .takeLast(8)
                .map { it.plainText() },
        )
    } catch (e: Exception) {
        return ChatResult.Failed(
            "人格提示词组装失败（${e::class.simpleName ?: "未知"}）：" +
                "${e.message ?: "无详细信息"}。为避免在「没有人格」的状态下继续对话，本次已中止。",
        )
    }

    // ★ 压缩记忆：会话级的，接在人格提示词**后面**。
    //   不放进 `assistantSystemPrompt` 的理由见接口参数的注释。
    val systemWithMemory =
        if (compressedMemory.isBlank()) system else system + "\n\n" + compressedMemory

    // ── ④ 在受控作用域内跑 ───────────────────────────────────
    //
    // ⚠️ 用**这个模型自己绑的那条 Key**，不是「默认那条」—— 见上面 ② 的注释。
    //
    // ★★ 2026-10-19：聊天第一次真的能「动手」
    // 在此之前这里是"发一次请求，收完整段文本就结束" —— 于是应用内聊天
    // **永远不会调用任何工具**：`ChatRequest` 不带 `tools`，
    // 而 `ChatChunk.ToolCallDelta` 被直接丢掉。
    //
    // ⚠️ 这一轮的任务 id —— **必须在 `useKey` 之外定义**。
    //    `useKey` 的 lambda 是另一个作用域，在里面定义的变量
    //    出了那层就没了，而"标记完成"要在外面做。
    val finishedTaskId = "chat-${System.currentTimeMillis()}"

    // ⚠️ 两个不变的东西：
    //   · **不流式**（`ChatResult.Ok` 仍是整段返回）—— 见 `StreamPump`（未接线）；
    //   · 循环的所有保护（预算 / 步数 / 重复检测 / 安全拒绝不许重试）
    //     都在 `:agentlogic` 里。
    val text = try {
        store.useKey(config.credentialId) { provider, credential ->
            // ── 纯对话：一次性请求，零工具 ────────────────────
            if (decision.intent == TurnIntent.Chat) {
                // ★ 诊断日志：**上下文到底传了几条**。
                //   用户反馈"模型似乎只有单次记忆"，而这条日志能一句话回答
                //   "是没传"还是"传了但模型没用"—— 两者修法完全不同。
                Timber.i(
                    "纯对话上下文：%d 条消息（%d 字）",
                    history.size, history.sumOf { it.plainText().length },
                )
                // ══════════════════════════════════════════════════
                //  ★★ 2026-10-31：把用户附的图片挂到最后一条用户消息上
                // ══════════════════════════════════════════════════
                //
                // ⚠️ 只在**纯对话**路径挂图，agent 路径不挂：
                //   执行类任务的循环会自己拼消息，图片会在工具往返中丢失，
                //   而且那种任务的目标是"动手"，看图不是它的主要用途。
                //   （如果将来要支持"看着截图去点"，那要在 `PhoneTask`
                //   里加图片字段，是另一件事。）
                val historyWithImage = if (image != null && image.isNotEmpty()) {
                    history.attachUserImage(image, imageMimeType)
                } else {
                    history
                }
                Timber.i(
                    "本轮附图片：%s",
                    if (image != null) "${image.size / 1024}KB ($imageMimeType)" else "无",
                )
                return@useKey chatOnly(
                    provider, credential, model, historyWithImage, systemWithMemory, onDelta,
                )
            }
            // ★ 按**实际生效的模式**取工具集，不是全量：
            //   root 不可用时 `root_exec` 根本不出现在清单里 ——
            //   模型看不到它，也就不会去调它。
            val tools = container.toolsForCurrentMode()
            Timber.i(
                "本次工具集 %d 个（模式=%s）：%s",
                tools.size,
                container.effectivePrivilegeMode(),
                tools.joinToString("、") { it.name },
            )

            // ★ 这两个数的关系由 `budgetStepMisconfiguration` 把关
            //   （相等会让 StepLimitReached 变成死代码）。
            budgetStepMisconfiguration(
                maxTurns = CHAT_MAX_TURNS,
                maxSteps = CHAT_MAX_STEPS,
            )?.let { throw IllegalStateException("聊天预算配置非法：$it") }

            val loop = PhoneAgentLoop(
                llm = AgentAdapters.llmPort(
                    provider = provider,
                    credential = credential,
                    model = model,
                    // ★ "眼睛"：`app_host shot` 存下的那一帧，这一轮交给模型看。
                    pendingImage = { container.virtualScreenHost.takePendingImage() },
                ),
                runner = AgentAdapters.toolRunner(tools),
                budgetGuard = BudgetGuard(AgentBudget(maxTurns = CHAT_MAX_TURNS)),
            )
            // ★★ 2026-10-31：这一轮的任务 id —— 检查点按它索引。
            //   ⚠️ 用 `chat-<时间戳>` 而不是固定的 "chat"：
            //      固定 id 会让**每一轮**都覆盖同一份检查点，
            //      于是"恢复上次任务"永远只能恢复最后一轮。
            val taskId = finishedTaskId
            val result = loop.run(
                PhoneTask(
                    id = taskId,
                    userInput = userText,
                    systemPrompt = systemWithMemory,
                    tools = AgentAdapters.toolSpecs(tools),
                    // ⚠️ 只把**用户与助理**的发言带进去（不含系统提示词、
                    //    也不含本轮输入）—— `PhoneTask` 会各接一次。
                    priorMessages = history.dropLast(1).map { it.toAgentMessage() },
                    maxSteps = CHAT_MAX_STEPS,
                ),
                // ★ 把循环的真实进度透给界面。
                onStep = onStep,
                // ══════════════════════════════════════════════════
                //  ★★ 落检查点 —— 让"中断后能接着来"真的成立
                // ══════════════════════════════════════════════════
                //
                // ⚠️ 手机上的中断是**外部的**：来电、切走、系统杀后台、
                //    电量优化。用户没法"等它跑完"。
                //    没有检查点，那些中断全部变成"这件事得从头再说一遍"。
                //
                // ⚠️ 这里不 await 写盘（用 launch）：落盘是"更好的恢复"，
                //    不该让每一步都等磁盘。失败也只记日志，不影响任务。
                //
                // ⚠️ `onProgress` 是**同步回调**（循环不认协程），
                //    而 `store.save` 是 suspend ⇒ 必须自己起一个作用域。
                //    用容器自带的作用域，这样任务被取消时它也跟着取消。
                onProgress = { snap ->
                    container.checkpointScope.launch {
                        container.checkpointStore.save(
                            com.pocketagent.agentlogic.TaskCheckpoint(
                                taskId = taskId,
                                userInput = userText,
                                completedSteps = snap.step,
                                replanCount = 0,
                                // ⚠️ `plannedActions` 存**动作枚举名**而不是序号
                                //    （理由见 TaskCheckpoint 的注释：枚举增删会让
                                //    序号指向另一个动作）。
                                plannedActions = listOf(snap.action),
                                usage = snap.usage,
                                savedAtMs = System.currentTimeMillis(),
                                finished = false,
                            ),
                        )
                    }
                },
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
        else -> {
            // ★ 回复已经拿到了，才轮到"记点什么"。
            //   ⚠️ 必须放在成功返回**之后**：放在前面的话，失败的那几轮
            //      也会被当成素材，于是记忆里混进"用户问了一句、助理没答"。

            // ══════════════════════════════════════════════════════
            //  ★★ 2026-10-31：输出守卫记账 —— 挡住"崩坏进记忆"
            // ══════════════════════════════════════════════════════
            //
            // ⚠️ 必须**在记忆抽取之前**记：抽取每 6 轮才跑一次，
            //    而它读的是 `oocTurnIds()`。如果记账在抽取之后，
            //    那几轮的标记会晚一个周期才生效 —— 而那时它们
            //    已经被抽进去了。
            //
            // ⚠️ 这是一个**廉价**的字符串扫描（`RefusalDetector.inspect`），
            //    不是完整的语言指纹审计 —— 后者每轮跑十几条规则不值得。
            //    挡住的只是"硬拒答"（"作为一个AI…"这类），
            //    而它正是污染记忆的主要来源。
            runCatching {
                container.recordGuardCheck(turnId = "chat-${System.currentTimeMillis()}", replyText = text)
            }.onFailure { Timber.w(it, "输出守卫记账失败（不影响对话）") }

            //  完全异步（`scheduleMemoryAbsorb` 内部 launch），不拖慢这一轮。
            runCatching {
                val sid = container.conversationRepository()
                    ?.currentSession()?.id
                if (sid != null) container.scheduleMemoryAbsorb(sid)
            }.onFailure { Timber.w(it, "记忆抽取调度失败（不影响对话）") }

            // ══════════════════════════════════════════════════════
            //  ★★ 2026-10-31：任务**成功结束** ⇒ 标记检查点完成
            // ══════════════════════════════════════════════════════
            //
            // ⚠️ 不标记的话，这个已完成的检查点会一直出现在
            //    "上次没做完，接着来？"里 —— 而它其实**做完了**。
            //    用户会以为那个任务永远没结束。
            //
            // ★ 只在这一轮**真的动手**过（`didAct`）时标记：
            //   纯对话不产生检查点（`onProgress` 不会被调），
            //   标记一个不存在的检查点是空操作，但多一次 IO 没必要。
            //
            // ⚠️ 这里**重新算一次 taskId** —— 循环里那个 `taskId` 是
            //    `useKey` lambda 的局部变量，出了那个作用域就没了。
            //    两处都用 `"chat-" + 时间戳`，而这一整轮的耗时通常在
            //    同一秒内 —— 但**不能依赖这一点**。
            //    ⇒ 用一个在方法作用域里的变量，见下面的 `finishedTaskId`。
            if (didAct) {
                runCatching {
                    container.markTaskFinished(
                        finishedTaskId,
                        com.pocketagent.agentlogic.BudgetUsage(),
                    )
                }.onFailure { Timber.w(it, "标记任务完成失败（不影响对话）") }
            }
            ChatResult.Ok(text, isAgentRun = didAct)
        }
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
    /**
     * 纯对话：一次性请求，零工具。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-29：加入「退让检测 + 一次重试」
     * ═══════════════════════════════════════════════════════════════
     *
     * 破甲提示词只能**提高**不退让的概率 —— 任何服务商都可以无视它。
     * 真正让它变成工程问题的是这里：检测到退让就换个处境重发一次。
     *
     * ⚠️ 难点是**流式**：字已经在往界面上打了，重试收不回来。
     *    解决办法不是关掉流式（那会让每一句都变慢），而是**扣住开头**：
     *    [HOLD_CHARS] 个字之前先不发，等确认不是拒答再一次性放出去。
     *
     *    为什么这样够用：硬拒答的标记（"作为一个AI""我无法提供"）
     *    几乎总在开头几十字内出现。扣住这一段就足以拦下绝大多数。
     *
     * ⚠️ 扣住的代价必须诚实：正常回复的第一个 [HOLD_CHARS] 字
     *    会晚一点点到（模型吐这段通常不到一秒）。这是为了"不让你看见
     *    模型在拒绝"付的价钱，值得。
     *
     * ⚠️ 只在**破甲开启且成熟内容开启**时才扣：用户把档位关了就是他不要
     *    这套干预，那时替他扣字、替他重试都是违背他的选择。
     *
     * @return 答复文本；null 表示模型没给出内容
     */
    private suspend fun chatOnly(
        provider: LlmProvider,
        credential: ProviderCredential,
        model: String,
        history: List<ChatMessage>,
        system: String,
        onDelta: (String) -> Unit = {},
    ): String? {
        val armed = container.shouldArmorEngage()
        val first = chatOnce(provider, credential, model, history, system, onDelta, hold = armed)

        // 没开启破甲 / 没检测到退让 ⇒ 原样返回（零额外成本）
        if (!armed) return first
        if (first.isNullOrBlank()) return first
        if (!container.shouldRetryOnRefusal(first)) return first

        // ══════════════════════════════════════════════════════════
        //  重试一次
        // ══════════════════════════════════════════════════════════
        //
        // ⚠️ **只试一次**。第二次仍然退让说明不是处境问题
        //   （是模型/服务商那一层），再试只是烧钱和延迟。
        //
        // ⚠️ 重试用的是 `RETRY_STEER`（一段**完全不同**的短指令），
        //   不是把原请求再发一遍 —— 同样的话会得到同样的结果，
        //   采样有随机性，但触发的判据没变。
        Timber.i("退让重试：换处境再发一次")
        val retried = chatOnce(
            provider = provider,
            credential = credential,
            model = model,
            // ★ 把第一次的退让**也**带进上下文？不带。
            //   带上等于让模型看见自己拒绝过 —— 它会顺着那个立场继续拒绝。
            //   ⚠️ 但要把 steer 作为最后一条用户消息，明确"接着往下"。
            history = history + ChatMessage.user(ArmorBreak.RETRY_STEER),
            system = system,
            // ★ 重试时**从头就像扣住那样发**：先攒够再放，
            //   因为这一次同样可能退让 —— 我们不能第二次还让用户看见。
            onDelta = onDelta,
            hold = true,
        )

        // 重试仍是退让 ⇒ 如实返回它（有内容总比空着好），但要记下来。
        // 这一步**不再重试** —— 见上面那条"只试一次"。
        if (retried.isNullOrBlank()) return first
        if (container.shouldRetryOnRefusal(retried)) {
            Timber.w(
                "重试后仍然退让（%s）—— 大概率是模型/服务商层面，不再重试",
                container.refusalOf(retried),
            )
        }
        return retried
    }

    /**
     * 发一次请求。
     *
     * @param hold true = **扣住开头**：攒够 [HOLD_CHARS] 且确认不是拒答
     *   之后才把已收到的部分一次性放出去，之后的字照常流。
     *   false = 传统流式（收到就发）。
     */
    private suspend fun chatOnce(
        provider: LlmProvider,
        credential: ProviderCredential,
        model: String,
        history: List<ChatMessage>,
        system: String,
        onDelta: (String) -> Unit,
        hold: Boolean,
    ): String? {
        // ⚠️ tools 传空列表（不是 null）：`ChatRequest` 的默认值就是 emptyList，
        //    而传 null 需要它可空。空列表在这里的语义是"明确不提供工具"。
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage.system(system)) + history,
            tools = emptyList(),
        )

        val sb = StringBuilder()
        // 已放出到界面的字数。`hold` 模式下它滞后于 `sb.length`。
        var released = 0

        provider.chat(request = request, credential = credential).collect { chunk ->
            when (chunk) {
                // ★ 追加**并且立刻吐给界面** —— 这两件事必须成对，
                //   否则会出现"界面显示的字"和"最终落库的字"不一样。
                is ChatChunk.Delta -> {
                    sb.append(chunk.text)
                    if (!hold) {
                        onDelta(chunk.text)
                        released = sb.length
                    } else if (released == 0) {
                        // ══════════════════════════════════════════════════
                        //  ★★ 2026-10-31：**尽早放字**，只对可疑开头才扣
                        // ══════════════════════════════════════════════════
                        //
                        // 原来这里是"攒够 HOLD_CHARS(20) 才放"。那让
                        // **每一次对话的首字都晚 20 个字的生成时间** ——
                        // 慢速模型下一个字 70ms，20 个字就是 1.4 秒。
                        // 而它的收益只体现在"模型正在拒答"这一种情况上。
                        //
                        // ⇒ 用一个**廉价的预筛**：只有当已收到的开头
                        //   **看起来像拒答**时，才继续扣住等更多字；
                        //   否则**立刻放**（哪怕只有 3 个字）。
                        //
                        // ★ 预筛的判据是"前缀是否可能演变成拒答标记"：
                        //   拒答标记几乎都以这几个字开头（我/作为/A/I），
                        //   而角色的正常开场极少以"我无法/我不能提供"起手。
                        //
                        // ⚠️ 这个预筛**不承担正确性** —— 它只是个加速开关。
                        //   真正的判定在下面（`refusalOf` 检查完整前缀）
                        //   以及收尾的整段判定。预筛判错的方向只有两个：
                        //     · 该扣没扣（漏拦截）⇒ 走收尾判定兜住
                        //     · 不该扣却扣了（多等一会儿）⇒ 只损失那一次
                        //   两者都不会让功能失效。
                        val head = sb.toString()
                        if (mayBecomeRefusal(head) && head.length < HOLD_CHARS) {
                            // 可疑且还没攒够 ⇒ 继续等（本次不放）
                        } else if (container.refusalOf(head) == Refusal.HARD) {
                            // 已确认是拒答 ⇒ 不放，交给上层重试
                            Timber.i("扣住段内命中硬拒答，暂不放字")
                        } else {
                            // 正常 ⇒ 立刻放（**这就是首字延迟的优化**）
                            onDelta(head)
                            released = sb.length
                        }
                    } else {
                        onDelta(chunk.text)
                        released = sb.length
                    }
                }
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

        val full = sb.toString()
        // ★ 收尾：`hold` 模式下如果有内容但一个字都没放出去
        //   （短回复没到 HOLD_CHARS，或整段被扣住），这里补放。
        //
        // ⚠️ 若整段被扣住说明是拒答 —— 那时**不放**：
        //   不放字，交给上层重试。这正是扣住的意义。
        if (hold && released == 0 && full.isNotBlank()) {
            if (container.refusalOf(full) != Refusal.HARD) {
                onDelta(full)
            }
        }
        return full.ifBlank { null }
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

/**
 * 破甲介入时，**先扣住多少个字**再决定放不放。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要扣住，而不是纯流式
 * ═══════════════════════════════════════════════════════════════
 *
 * 流式的好处是"字出得快"，坏处是**收不回来**。
 * 一旦"作为一个AI，我无法提供"已经打到屏幕上，之后就算重试成功，
 * 用户也已经看见了那句话 —— 沉浸感是**一次性**的，拆掉就装不回去。
 *
 * ⇒ 扣住开头：攒够这么多字之后先判一次，确认不是拒答再一次性放出去。
 *   之后的字照常流式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-29：从 60 降到 24 —— 被离线测试逼出来的修正
 * ═══════════════════════════════════════════════════════════════
 *
 * 第一版定 60，理由是"要覆盖完整的硬拒答标记"。但一条断言把它否了：
 * 典型角色回复的开场只有 20 来字（"她把脸埋进他颈窝里，声音闷闷的：…"）,
 * 60 的阈值意味着**正常回复也要等一整句**才出字。那不是"稍微慢一点"，
 * 那是让每次对话都感觉卡顿 —— 用一个确定的延迟去换一个概率的收益。
 *
 * ⇒ 真正的解法不是把阈值调大，是**判据不需要那么长的前缀**：
 *   `RefusalDetector` 的硬标记最短的是「我是ai」(5 字)、「我是一个ai」(6 字)，
 *   "作为一个AI" 在 5 字时就已经能判出来。
 *
 * ⇒ 20 字：足够覆盖最短的标记（5 字）加上三四倍语境余量，
 *   又短于典型角色开场（约 23 字），所以正常回复几乎无感。
 *
 * ⚠️ 剩下漏掉的情况由 [chatOnce] 的**收尾判定**兜住：
 *   整段生成完之后再判一次，命中就不放字、交给重试。
 *   两段判定合起来才完整 —— 单靠扣字会漏，单靠收尾会让用户看见。
 *
 * ⚠️ 只在破甲开启时生效 —— 关掉档位就是关掉这套干预，
 *   那时保持纯流式（用户的选择优先于我们的手感判断）。
 */
private const val HOLD_CHARS = 20

/**
 * **廉价预筛**：这段开头有没有可能演变成本拒答？
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 实现住在 `:legacy/RefusalDetector.mayBecomeRefusal`
 * ═══════════════════════════════════════════════════════════════
 *
 * ⚠️ 之前我把这份词表直接写在本文件里，但 `:app` **进不了离线验证器** ——
 *    于是"哪些串算拒答起手"这条策略没有测试盯着。
 *    现在它挪到 `:legacy`（在验证器里），本文件只转发。
 *
 * ★ 那里有 8 条测试专门盯"正常开场不能被误判"——
 *   那条边界比"能识别拒答"更重要：误判会让**每一次**对话都多等 20 字。
 */
private fun mayBecomeRefusal(head: String): Boolean =
    com.pocketagent.legacy.RefusalDetector.mayBecomeRefusal(head)

/** 取一段消息里的纯文本（工具调用等非文本片段忽略）。 */
private fun ChatMessage.plainText(): String =
    content.filterIsInstance<com.pocketagent.provider.api.ContentPart.Text>()
        .joinToString("") { it.text }

/**
 * **把用户附的图片挂到最后一条用户消息上** —— 这就是"给模型装眼睛"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么挂"最后一条用户消息"而不是单独发一条
 * ═══════════════════════════════════════════════════════════════
 *
 * 视觉模型对"图和文字在同一条消息里"的理解明显更好 —— 它知道
 * "这张图是关于我刚才说的那句话的"。
 *
 * ⚠️ 如果图片作为单独一条消息（`role=user` 但只有图没有字），
 *    多数模型会把它当成一个**新话题**，而不是"你刚说的那个东西的补充"。
 *    实机表现是：用户"这张报错什么意思" + 图，模型回"你发了一张图，
 *    请问你想问什么"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 找不到用户消息时**返回原列表**，不硬塞
 * ═══════════════════════════════════════════════════════════════
 *
 * 没有用户消息（理论上不该发生，因为发送前一定 append 了一条）
 * 时宁可丢掉这张图，也不要凭空造一条只有图片的消息 ——
 * 那会让模型看到一段它无法理解的开场。
 */
private fun List<ChatMessage>.attachUserImage(
    png: ByteArray,
    mimeType: String,
): List<ChatMessage> {
    if (isEmpty()) return this
    val idx = indexOfLast { it.role == ChatMessage.Role.USER }
    if (idx < 0) return this

    // ★ 用 `copy` 而不是可变修改：`ChatMessage` 是 data class，
    //   就地改 content 会波及调用方持有的同一个对象 —— 那里可能是
    //   界面上正在渲染的那份历史。改它会引发一次意外的重组。
    val m = this[idx]
    return toMutableList().also { list ->
        list[idx] = m.copy(
            content = m.content + com.pocketagent.provider.api.ContentPart.Image(
                bytes = png,
                mimeType = mimeType,
            ),
        )
    }
}

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
        val lower = raw.lowercase()

        // ★ 2026-10-25 真机（用户原话："全是字、看不懂"）：
        //   卡片上直接显示 `模型调用失败：network unreachable` ——
        //   英文、术语、没有任何下一步。用户不知道该干什么。
        //
        //   这里把**最常见的那几类**翻成人话 + 一个具体动作。
        //   判据刻意宽松（只认关键词），因为翻译错的代价只是多一句提示，
        //   而不翻译的代价是用户对着一个英文单词发愣。
        val networkish = "network" in lower || "unreachable" in lower ||
            "timeout" in lower || "timed out" in lower ||
            "failed to connect" in lower || "connection" in lower ||
            "no address" in lower || "socket" in lower
        if (networkish) {
            return "网络连不上模型服务。\n\n" +
                "常见原因（按可能性排序）：\n" +
                "• 手机当前没网，或网络突然切换（Wi-Fi ↔ 数据）\n" +
                "• 这个模型的服务商在你当前的网络下连不通\n" +
                "• 请求超时了（模型一直没返回）\n\n" +
                "换个网络或稍等一会儿再试一次。刚才那句话已经保存，不用重打。"
        }

        val quotaish = "quota" in lower || "insufficient" in lower ||
            "balance" in lower || "402" in raw
        if (quotaish) {
            return "这个模型的额度用完了或余额不足。\n\n" +
                "请到服务商那边充值，或在「设置 → 模型配置」换一个模型。"
        }

        val authish = "401" in raw || "unauthorized" in lower ||
            "invalid api key" in lower || "invalid_api_key" in lower
        if (authish) {
            return "这个模型的 API Key 无效或已过期。\n\n" +
                "请到「设置 → API Key」重新填一次。注意别把厂商的「模型 id」当成 Key —— " +
                "两者完全不同。"
        }

        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-29：厂商侧的审核拦截必须单独说清楚
        // ══════════════════════════════════════════════════════════
        //
        // 在此之前 `ProviderException.ContentFiltered` **没有任何地方处理** ——
        // 它一路落到最后那句"（这是模型服务返回的原始错误，不是你的操作问题）"，
        // 于是用户看到的是厂商的英文原文（`content_filter` / `data_inspection_failed`
        // / `Output data may contain inappropriate content`）。
        //
        // 为什么这条对"深度对话"特别重要：这类拦截**不是本地的**，
        // 是模型服务商那一侧的。用户在本应用里打开了成熟内容开关、
        // 关系阶段也够了，但请求发到厂商那里被拦 —— 他只会看到一串英文，
        // 然后以为是应用坏了，反复重发。
        //
        // ⇒ 必须说清三件事：**是谁拦的**、**不是你的设置问题**、**能怎么办**。
        //   ⚠️ 不能含糊成"内容不合规" —— 那听起来像在指责用户。
        val filteredNow = "content_filter" in lower ||
            "content policy" in lower || "content management policy" in lower ||
            "data_inspection_failed" in lower || "inappropriate" in lower ||
            "safety" in lower || "敏感" in raw || "违规" in raw ||
            "审核" in raw || "不合规" in raw
        if (filteredNow) {
            return "这句话被**模型服务商**拦下来了，不是你这边设置的问题。\n\n" +
                "本应用的成熟内容开关是开着的，请求也确实发出去了 —— " +
                "但服务商在它那一侧做了内容审核，把这次请求或回复挡掉了。\n\n" +
                "能做的三件事：\n" +
                "• 换个说法再说一次（同样的意思，措辞不同，常常就能过）\n" +
                "• 在「设置 → 模型配置」换一个审核更宽松的模型/服务商\n" +
                "• 只在这一轮里避开被拦的那个词\n\n" +
                "⚠️ 这不是「重试就好」的错误 —— 同一个说法重发，多半还是被拦。\n\n" +
                "服务商原话：$raw"
        }

        val modelMissing = "模型不存在" in raw ||
            ("model" in lower && "not found" in lower)
        if (modelMissing) {
            return "$raw\n\n" +
                "提示：这句话几乎总是「模型 id 抄错了」。厂商的模型代码**带连字符**，" +
                "例如智谱是 glm-5.3-flash 而不是 glm5.3flash。" +
                "请到「设置 → 模型配置」核对 id 是否与厂商文档逐字符一致 —— " +
                "它与你的 Key 和余额无关。"
        }

        // 认不出来的错误：原样透传，但**加一行**说明这是哪个层面的问题，
        // 免得用户以为是自己操作错了。
        return "$raw\n\n（这是模型服务返回的原始错误，不是你的操作问题）"
    }
