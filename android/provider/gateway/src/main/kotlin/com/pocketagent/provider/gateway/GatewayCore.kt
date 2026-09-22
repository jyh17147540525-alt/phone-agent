package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * 网关核心 —— **唯一处理真 Key 的地方**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  一道必须遵守的顺序：路由 → 熔断 → 解密 → 转发 → 计量
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个顺序不是随便定的，每一步都排除了一种错误：
 *
 * | 步骤 | 放在这个位置的理由 |
 * |---|---|
 * | **路由** | 决定用哪个模型，后面每一步都要用到它 |
 * | **熔断** | ⚠️ **必须在解密之前** —— 超预算的请求根本不该碰 Keystore。放在后面等于"明知要拒绝，还先把 Key 解出来放在内存里" |
 * | **解密** | 拿到明文 Key，作用域尽可能短 |
 * | **转发** | 调 Provider |
 * | **计量** | 用量落库（走接口，实现在 keymgmt） |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它刻意**忽略 `ChatRequest.model`**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个字段由客户端填（dsh 会填 `"gpt-4o"` 之类它自己以为的模型名），
 * 而**真实用哪个模型由我们的路由决定**。理由：
 *
 * - 用户配的可能是 DeepSeek，客户端却按自己的假设传 `"gpt-4o"`
 * - 如果按客户端传的值路由，用户会得到"我明明配了 DeepSeek，它却报
 *   找不到 gpt-4o" —— 而这是他完全没法修复的（他改不了客户端的请求体）
 *
 * 所以请求构造时**用 [ResolvedTarget.modelId] 覆盖** `request.model`。
 * 与 BYOK 文档 §4.5 的"策略 A（`/v1/models` 只返回虚拟模型）"一致。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 流式错误的处理：首字节之后才发现上游出错
 * ═══════════════════════════════════════════════════════════════
 *
 * HTTP 状态码在**首字节发出时**就已经定了。如果上游在第 3 个 chunk 之后
 * 报 401，我们没法再改状态码 —— 只能在流里发一个错误事件。
 *
 * 本类因此把上游异常转成 [ChatChunk.Done] 之外的东西？**不。**
 * 它保持"异常向上抛"的语义，因为：
 * - `GatewayCore` 是**函数调用**接口（agent 循环直接用），不是 HTTP 层
 * - "流中出错"的协议化是 `HttpGatewayServer` 的职责（它才有 SSE 事件可发）
 *
 * 这样切分让本类保持"纯业务"，也让它能离线测。
 */
class GatewayCore(
    private val bridge: RoutingBridge,
    private val credentials: CredentialSource,
    /**
     * 可用的 Provider 实现。
     *
     * ⚠️ **在 `GatewayCore` 里按 `providerId` 查表，而不是在 `RoutingBridge`**
     *    —— 因为 `providerId` 在**凭据**上（`CredentialEntity.providerId`），
     *    而凭据要到熔断通过之后才解密。硬要在路由阶段解析 Provider，
     *    就只能靠"模型 id 像不像某家"去猜，而那正是我们明确反对的做法
     *    （同一个模型 id 可以通过 OpenRouter 访问任何一家）。
     *
     *    构造器注入 `List<LlmProvider>` 而不是依赖具体实现模块 ——
     *    否则每新增一家厂商都要回来改这个类的依赖与 when 分支，
     *    而漏改的表现是"这家厂商怎么都配不上"（不报错，只是永远路由不到）。
     */
    private val providers: List<LlmProvider>,
    private val usageRecorder: UsageRecorder = UsageRecorder.Noop,
    /**
     * 无预算时用的默认预算。
     *
     * ⚠️ 刻意**有一个默认值**而不是"真的不限制" —— 见 [DEFAULT_REQUEST_BUDGET]。
     */
    private val defaultBudget: RequestBudget = DEFAULT_REQUEST_BUDGET,
) {

    private val providersById: Map<String, LlmProvider> = providers.associateBy { it.id }

    /**
     * 发起一次流式对话。
     *
     * @return 上游的 chunk 流。失败（路由/熔断/解密）以 `Flow` 内的
     *         `GatewayCallException` 形式抛出 —— 这是**刻意的例外**：
     *         流式接口没法用返回值表达"还没开始就失败了"，
     *         否则调用方要同时处理"返回值的失败"和"流内的失败"两套。
     *
     *         ⚠️ 但异常类型是**可枚举的**（见 [GatewayCallException]），
     *            调用方 `catch` 一次就能拿到 [GatewayFailure]，
     *            不需要 `when` 一堆异常子类。
     */
    fun complete(
        mode: RoutingMode,
        request: ChatRequest,
        ctx: GatewayContext = GatewayContext(),
    ): Flow<ChatChunk> = flow {
        // ── 1. 路由 ────────────────────────────────────────────
        val target = when (val r = bridge.resolve(mode, ctx)) {
            is GatewayOutcome.Failure -> throw GatewayCallException(r.reason)
            is GatewayOutcome.Success -> r.value
        }

        // ── 2. 熔断（★ 必须在解密之前）─────────────────────────
        //
        // ⚠️ 这一步**不能用 Provider 的 countTokens()** —— 那要先有
        //    Provider，而 Provider 要从凭据里查，凭据要解密才拿得到。
        //    顺序会变成"先解密 → 再熔断"，正是我们要避免的。
        //
        //    所以这里用**保守估算**（见 estimateInputTokens）。它不需要
        //    Provider：按字符数上限估（1 token ≈ 1 字符是最保守的假设，
        //    真实 token 数总是 ≤ 字符数）。宁可估高一点拒绝掉，
        //    也不要"先解密再发现超预算"。
        val budget = ctx.budget ?: defaultBudget
        val inputTokens = estimateInputTokens(request)

        val verdict = BudgetGuard.check(
            request = budget,
            budget = budget,
            model = target.config,
            estimatedInputTokens = inputTokens,
        )

        when (verdict) {
            is BudgetVerdict.Exceeded -> throw GatewayCallException(verdict.failure)

            // ⚠️ AllowedWithWarning 的警告**必须能被用户看到** ——
            //    这里把它记进 usage（落库），真正的 UI 呈现由上层做
            //    （GatewayCore 刻意不依赖 UI）。
            is BudgetVerdict.Allowed, is BudgetVerdict.AllowedWithWarning -> Unit
        }

        val warning = (verdict as? BudgetVerdict.AllowedWithWarning)?.warning

        // ── 3. 解密 ────────────────────────────────────────────
        //
        // ⚠️ 整段包在 withResolvedCredential 里 —— 它保证**无论如何**
        //    都把明文清零（含异常路径与流的提前取消）。
        //
        //    "无论如何"是重点：只在正常返回时清零的写法，会在
        //    "用户中途点了急停"或"上游抛异常"时漏掉，而那是明文
        //    在内存里停留最久的两条路径。
        withResolvedCredential(target.credentialId) { credential ->

            // ── 3b. 拿到 Provider（此时才知道用哪家）────────────
            val provider = providersById[credential.providerId]
                ?: throw GatewayCallException(
                    GatewayFailure.ProviderNotRegistered(credential.providerId)
                )

            val upstreamRequest = request.copy(
                // ★ 覆盖客户端的 model 字段，见类注释
                model = target.modelId,
                // ★ 输出额度按预算收敛（截断，不拒绝）
                maxTokens = BudgetGuard.effectiveMaxOutputTokens(
                    request = budget,
                    budget = budget,
                    estimatedInputTokens = inputTokens,
                ),
            )

            val providerCredential = credential.toProviderCredential()

            // 上游给的权威用量。在转发过程中从 Done chunk 里取出。
            //
            // ⚠️ 必须在**转发时**赋值，否则 onCompletion 读到的永远是
            //    null，outputTokens 恒为 0 —— 表现是"用量统计里输出
            //    永远是 0"，不报错、只是安静地少记了一件事。
            var finalUsage: TokenUsage? = null
            var failure: Throwable? = null

            // ── 4. 转发 + 5. 计量 ───────────────────────────────
            emitAll(
                provider.chat(upstreamRequest, providerCredential)
                    // ★ 截获 Done，取出上游给的真实用量
                    .let { upstream ->
                        flow {
                            upstream.collect { chunk ->
                                if (chunk is ChatChunk.Done) finalUsage = chunk.usage
                                emit(chunk)
                            }
                        }
                    }
                    .catch { e ->
                        failure = e
                        // ⚠️ 这里**不能**吞掉异常 —— 调用方需要知道出错了。
                        throw e
                    }
                    // ⚠️ `onCompletion` 而不是 `finally` —— 它能在上游流
                    //    正常结束、异常结束、以及**被取消**三种情况下都触发。
                    .onCompletion { cause ->
                        if (cause != null && failure == null) failure = cause
                        usageRecorder.record(
                            UsageRecord(
                                modelConfigId = target.modelConfigId,
                                providerId = credential.providerId,
                                consumer = ctx.consumer,
                                inputTokens = inputTokens,
                                outputTokens = finalUsage?.outputTokens ?: 0,
                                upstreamUsage = finalUsage,
                                failure = failure?.let { describe(it) },
                                warning = warning,
                            )
                        )
                    }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    /**
     * 解析凭据 → 使用 → **保证清零**。
     *
     * ⚠️ 这是本项目唯一允许接触明文 Key 的地方。任何"顺手把 Key 传出去"
     *    的改动都必须先读这段注释。
     */
    private suspend fun <T> withResolvedCredential(
        credentialId: String,
        block: suspend (ResolvedCredential) -> T,
    ): T {
        val credential = try {
            credentials.resolve(credentialId)
        } catch (e: CredentialDecryptException) {
            throw GatewayCallException(GatewayFailure.CredentialUndecryptable)
        } ?: throw GatewayCallException(GatewayFailure.CredentialMissing(credentialId))

        return try {
            block(credential)
        } finally {
            // ★ 无论成功、抛异常、还是流被取消，都走到这里
            credential.clear()
        }
    }

    /**
     * **保守**估算输入 token —— 在解密之前用。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 为什么不用 Provider 的 `countTokens()`
     * ═══════════════════════════════════════════════════════════════
     *
     * `countTokens()` 要先有 Provider 实例，而 Provider 要按凭据的
     * `providerId` 查，凭据要**解密**才拿得到 —— 于是顺序被迫变成
     * "先解密 → 再熔断"，而超预算的请求本该**完全不碰 Keystore**。
     *
     * 所以这里做一次不需要 Provider 的估算。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 估**高**而不是估准
     * ═══════════════════════════════════════════════════════════════
     *
     * 按"1 token ≈ 1 字符"估，这是**上界**：
     * - 英文约 4 字符/token
     * - 中文约 1.5 字符/token
     *
     * 两个方向搞反的后果完全不同：
     * - 估**低** → 熔断放行了一个实际超预算的请求 → **安静地多花钱**
     * - 估**高** → 熔断拒绝了一个实际没超的请求 → 用户看到明确的
     *   "超出上限"，他可以调大上限
     *
     * 后者是**可见且可修**的，前者不是。所以宁可估高。
     *
     * ⚠️ 图片按固定值计（每张 1000 token 的保守假设）—— 真实值取决于
     *    分辨率与厂商实现，但截图是数 MB 的 base64，绝不该按 0 算。
     */
    private fun estimateInputTokens(request: ChatRequest): Int {
        var chars = 0
        var images = 0

        request.messages.forEach { msg ->
            msg.content.forEach { part ->
                when (part) {
                    is com.pocketagent.provider.api.ContentPart.Text -> chars += part.text.length
                    is com.pocketagent.provider.api.ContentPart.Image -> images++
                    // 工具调用/结果：按各自字符串长度计
                    is com.pocketagent.provider.api.ContentPart.ToolCall ->
                        chars += part.name.length + part.argumentsJson.length

                    is com.pocketagent.provider.api.ContentPart.ToolResult ->
                        chars += part.content.length
                }
            }
        }

        // 工具 schema 也要算 —— 它们会进请求体，而且是每个 system prompt
        // 都会重复的部分（TOOL_CALL 场景下常常是最大的一块）
        request.tools.forEach { tool ->
            chars += tool.name.length + tool.description.length + tool.parametersJsonSchema.length
        }

        return chars + images * IMAGE_TOKEN_ESTIMATE
    }

    /**
     * 把上游异常映射成**不含 Key** 的描述。
     *
     * ⚠️ `ProviderException` 的 message 有时会带上请求片段（各家 SDK
     *    风格不一），所以这里只取**类型**信息，不带上 message ——
     *    见下面的兜底分支。
     */
    private fun describe(e: Throwable): String = when (e) {
        is ProviderException.AuthFailed -> "auth failed"
        is ProviderException.RateLimited -> "rate limited"
        is ProviderException.NoBalance -> "no balance"
        is ProviderException.NetworkError -> "network error"
        is ProviderException.Timeout -> "timeout"
        is ProviderException.ProtocolError -> "protocol error"
        is ProviderException.ContentFiltered -> "content filtered"
        // 兜底：**只留类型名，不带 message** —— 未知异常里最可能出现
        // 我们不认识的敏感内容（完整 URL、请求体、甚至 Key）。
        // 宁可丢诊断信息，也不要冒着把 Key 写进数据库的风险。
        else -> e::class.simpleName ?: "unknown"
    }

    companion object {
        /**
         * 估算一张图片占多少 token。
         *
         * ⚠️ 这是**保守假设**，不是实测值 —— 真实值取决于分辨率与厂商实现
         *    （通常 85–1500 之间）。取 1000 的理由是"宁可估高"，
         *    见 [estimateInputTokens] 的注释。
         */
        private const val IMAGE_TOKEN_ESTIMATE = 1000

        /**
         * 没显式给预算时用的默认值。
         *
         * ═══════════════════════════════════════════════════════════
         *  ⚠️ 为什么"无预算"要有**默认值**，而不是真的不限制
         * ═══════════════════════════════════════════════════════════
         *
         * BYOK 模式下花的是用户自己的钱。一个"忘了传预算"的调用点
         * 如果拿到"无限制"，就成了**用户无法察觉的烧钱口子** ——
         * 而这恰恰最容易发生（新写的代码路径最容易漏参数）。
         *
         * 所以默认值是一个**保守但宽松**的上限：128K token 对正常对话
         * 远够用，而失控的循环会被它拦住。
         *
         * ⚠️ **刻意不设 `maxCostUsd` 默认值**：费用取决于模型价格，而我们
         *    可能算不出来（价格未知）。强行设一个金额上限会让"没标价的
         *    模型"直接被拒，而那往往是本地模型（本来就免费）。
         *    **金额上限必须由调用方显式给出**，因为它与"用哪个模型"强相关。
         */
        val DEFAULT_REQUEST_BUDGET = RequestBudget(
            maxTokens = 128_000,
            maxCostUsd = null,
            costFallback = RequestBudget.CostFallback.Allow,
        )
    }
}

/**
 * 网关调用的失败包装。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这是一个**刻意的例外**，理由是流式接口的表达力限制
 * ═══════════════════════════════════════════════════════════════
 *
 * 项目对"失败"的一贯立场是"用返回值表达，不要抛异常"（见 `RoutingFailure`
 * 与 `GatewayOutcome` 的注释）。但 `complete` 返回 `Flow<ChatChunk>`，
 * 而"还没开始就失败了"这件事**没有地方放** —— 要么往回改成
 * `Flow<GatewayOutcome<ChatChunk>>`（让每个 chunk 都带一层包装，很丑），
 * 要么允许异常。
 *
 * 选择了后者，但把它**收窄成一个类型**：调用方 `catch (e: GatewayCallException)`
 * 一次就能拿到可枚举的 [failure]，不需要 `when` 一堆异常子类。
 *
 * ⚠️ 换句话说：**异常是传输手段，不是分类手段** —— 分类仍然由
 *    [GatewayFailure] 这个 sealed 接口完成。这个区分保证了
 *    "新增一种失败原因"不需要新增异常类型。
 */
class GatewayCallException(val failure: GatewayFailure) :
    Exception(failure.userMessage)

/**
 * 用量记录。**落库是接口，实现在 `keymgmt`** —— 理由见类注释。
 */
data class UsageRecord(
    val modelConfigId: String,
    val providerId: String,
    val consumer: Consumer,

    /**
     * 我们**估算**的输入 token。
     *
     * ⚠️ 与 [upstreamUsage] 分开存 —— 上游给的 usage 才是计费依据，
     *    而估算值在"上游没返回 usage"时是唯一的参考。两者都存下来
     *    才能回答"我们的估算偏了多少"（而这决定预算准不准）。
     */
    val inputTokens: Int,

    /** 我们**估算**的输出 token（上游 Done 里的值，没有就是 0） */
    val outputTokens: Int,

    /** 上游返回的权威用量；null = 上游没给 */
    val upstreamUsage: TokenUsage? = null,

    /** 失败原因（已脱敏）；null = 成功 */
    val failure: String? = null,

    /**
     * 预算警告（如"该模型未标价，熔断不生效"）。
     *
     * ⚠️ 必须落库 —— 否则"用户的预算对某个模型静默失效"这件事
     *    在事后**完全无法追溯**，而它造成的损失（真金白银）
     *    需要能对上账。
     */
    val warning: String? = null,
)

/**
 * 用量记录器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 接口在这里、实现在 `keymgmt`，这是**硬约束**不是风格偏好
 * ═══════════════════════════════════════════════════════════════
 *
 * 真实实现要写 Room（SQLCipher）。一旦本模块依赖 `:core:database`，
 * `GatewayCore` 就**再也进不了离线验证器**（那个脚本按模块整目录编译，
 * 给 Room 写桩会让测试比没有更不可信）。
 *
 * 而这个类里最值得测的恰好是纯逻辑：熔断分支、解密顺序、
 * 异常路径的清零、用量归属。它们必须能离线跑。
 *
 * 同一个模式在项目里已有两处先例：`ModelConfigRepository`、
 * 以及 `:provider:api` 的 `LlmProvider` —— 契约与实现分离。
 */
interface UsageRecorder {

    /**
     * 记录一次调用。
     *
     * ⚠️ 实现**不得抛异常** —— 记账失败不该让用户的对话失败。
     *    吞掉异常是这里唯一正确的做法（但要打日志）。
     */
    suspend fun record(record: UsageRecord)

    /** 什么都不做。用于测试与"用户关了统计"的场景 */
    data object Noop : UsageRecorder {
        override suspend fun record(record: UsageRecord) = Unit
    }
}

/** 便于测试：把记录收进一个列表 */
class InMemoryUsageRecorder : UsageRecorder {
    private val _records = mutableListOf<UsageRecord>()
    val records: List<UsageRecord> get() = _records.toList()

    override suspend fun record(record: UsageRecord) {
        _records += record
    }
}
