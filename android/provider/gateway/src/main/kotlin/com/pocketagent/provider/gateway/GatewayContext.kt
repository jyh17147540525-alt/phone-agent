package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.TaskContext

/**
 * 一次网关调用的上下文。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个类型必须存在，而不是散成参数
 * ═══════════════════════════════════════════════════════════════
 *
 * 网关要做四件事：**路由、熔断、解密、计量**。前两件都需要知道
 * "这一轮是为谁、为什么任务服务的"，而这些信息来自**调用方**，
 * 网关自己推不出来：
 *
 * - 路由需要 [instruction] 与 [taskContext]（难度评估的输入）
 * - 熔断需要 [budget]（谁是这一轮的预算主体）
 * - 计量需要 [consumer]（这条用量记在"agent 循环"还是"dsh"名下）
 *
 * 如果把它们散成一个 5 参数的 `complete(req, a, b, c, d)`，加一个字段
 * 就要改所有调用点与所有测试的 fake。收成一个对象后，字段的默认值
 * 可以表达"大多数调用方不需要关心它"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ [modelOverride] 与路由的关系（这是一处容易搞混的地方）
 * ═══════════════════════════════════════════════════════════════
 *
 * 默认走路由决策（[RoutingBridge]），但有两种情况需要绕开它：
 *
 * 1. **重试**：路由选中的模型连续失败，调用方要指定下一个候选。
 *    此时重新路由会得到同一个结论（输入没变），原地打转。
 * 2. **`ChatRequest.model` 是虚拟模型名**：dsh 之类的客户端会传
 *    `"gpt-4o"` 这种它自己以为的模型名，而我们不可能也不该按它路由
 *    （用户配的可能是 DeepSeek）。**忽略客户端给的 model 字段是刻意的**，
 *    见 [GatewayCore] 的类注释。
 *
 * 所以 [modelOverride] 的语义是"**调用方明确指定，跳过路由**"，
 * 而不是"客户端想用什么"。它由我们自己的代码填，不由请求体填。
 */
data class GatewayContext(
    /** 谁在调 —— 只影响计量归属，不影响鉴权（鉴权走 token，见 HttpGatewayServer） */
    val consumer: Consumer = Consumer.AgentLoop,

    /**
     * 本次任务的原始指令。**只用于难度评估**，不参与提示词构造。
     *
     * ⚠️ 允许为空 —— 多轮对话里某一轮可能只有工具结果没有新指令，
     *    空串会让难度评估落到 LIGHT（默认最低档），这是**正确的默认**：
     *    没有新指令的一轮通常就是"继续执行"，不值得为它升级模型。
     */
    val instruction: String = "",

    /**
     * 任务上下文（已执行步数、是否失败过等）。难度评估的第二组输入。
     *
     * ⚠️ 刻意复用 `:modelrouter` 的 `TaskContext` 而不是自定义一个 ——
     *    自己定义的话 `:modelrouter` 那边一加字段，这里就是"编译通过、
     *    但新字段永远拿到默认值"，而默认值往往意味着"评估得偏低"。
     */
    val taskContext: TaskContext = TaskContext(),

    /**
     * 调度模型给出的难度判定；null = 没调或调用失败，由本地启发式兜底。
     *
     * ⚠️ **传 null 不是降级，是默认路径** —— 绝大多数用户不配调度模型。
     */
    val tierOverride: ModelTier? = null,

    /**
     * 跳过路由、直接指定模型配置 id。
     *
     * 见类注释：这是**重试**与**它用途**的入口，不是"客户端指定模型"的入口。
     * 为 null 时走正常路由。
     */
    val modelOverride: String? = null,

    /**
     * 本轮预算。null = 无预算约束（用于配置页那种"试一下能不能通"的探测）。
     *
     * ⚠️ **默认 null 而不是默认一个"很小的预算"** —— 后者会让
     *    "忘了传预算"这件事表现得像"预算被瞬间打爆"，排查方向完全错。
     */
    val budget: RequestBudget? = null,
)

/**
 * 调用方身份。只影响**计量归属**（用量记在谁名下），不影响鉴权。
 *
 * ⚠️ 鉴权与计量必须是两件事：鉴权回答"你有没有权限调模型"（走 token），
 *    计量回答"这条用量算谁的"（走这里）。合并成一个字段的后果是
 *    "换了一种调用方式，历史用量就散了"，而用户看到的只是统计数字变了。
 */
enum class Consumer(val displayName: String) {
    /** PocketAgent 自己的 agent 循环（直接函数调用，不经过 HTTP） */
    AgentLoop("任务执行"),

    /** dsh（Node 进程，走 loopback HTTP + SSE） */
    Dsh("深度会话"),

    /** 配置页的"测试连接"之类的探测请求 */
    Probe("连接测试"),
}

/**
 * 单次请求（一轮对话）的预算约束。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这是「单轮」预算，不是「单任务」预算
 * ═══════════════════════════════════════════════════════════════
 *
 * 单任务预算（`AgentBudget`：8 轮 / 5 分钟 / 0.1% 电量）属于 agent 循环，
 * 由循环自己持有与检查 —— 那个类型在 P5 阶段才落地（见规划 §3.1），
 * 且它管的是"整个任务"，不是"一次模型调用"。
 *
 * 这里管的是**一次 HTTP/函数调用**的上限，是更细的一层。两者是嵌套关系：
 * 循环的每一轮都会带着自己的 `RequestBudget` 进来，而循环自己那一层
 * 负责"已经用了多少轮"的累计判断。
 *
 * 分层的好处：`GatewayCore` 不需要知道"任务"是什么概念 ——
 * 它只回答"这次调用超没超"。这让它保持可离线测试。
 */
data class RequestBudget(
    /**
     * 本次调用允许的最大 token 数（输入 + 输出）。
     *
     * ⚠️ **输入超限是拒绝，输出超限是截断** —— 这两个方向完全不同：
     *    输入已经在手上了，超了只能拒绝（截断输入会丢上下文，模型给出
     *    错误答案且不报错）；输出是模型生成的，超了可以停。
     *    见 [BudgetGuard]。
     */
    val maxTokens: Int = 64_000,

    /**
     * 本次调用允许的最大费用（美元）。
     *
     * ⚠️ 为 null 表示"不限制"而不是"限制为 0"。
     */
    val maxCostUsd: Double? = null,

    /**
     * 价格未知时的策略。
     *
     * ⚠️ **这个字段存在的理由**：`ModelConfig.estimatedCost` 在价格未知时
     *    返回 null（不是 0），所以我们**算不出**这次调用要花多少。
     *    此时有两种都说得通的做法，而选错的那个是静默的：
     *    - [CostFallback.Allow]：放行。适合"本地模型不要钱"的场景
     *    - [CostFallback.Deny]：拒绝。适合"我只想用便宜模型"的场景
     *
     *    默认 [CostFallback.Allow]，因为"用户配了个没标价的模型"
     *    是常见情况（本地 Ollama、自建端点），拒绝会让它完全不能用，
     *    而用户看到的只是"怎么都跑不起来"。
     */
    val costFallback: CostFallback = CostFallback.Allow,
) {
    init {
        require(maxTokens > 0) { "maxTokens 必须为正数，当前为 $maxTokens" }
        require(maxCostUsd == null || maxCostUsd >= 0) {
            "maxCostUsd 不能为负数，当前为 $maxCostUsd"
        }
    }

    enum class CostFallback { Allow, Deny }
}

/**
 * 网关的失败原因。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 与 `RoutingFailure` 一样：失败是业务状态，不是异常
 * ═══════════════════════════════════════════════════════════════
 *
 * 这些失败**全部**有明确的用户可执行的补救动作，而异常类型表达不了
 * "用户下一步该干什么"：
 *
 * | 失败 | 用户要做的 |
 * |---|---|
 * | [NoModelConfigured] | 去配置页加一个模型 |
 * | [ModelDisabled] | 把那个模型重新启用 |
 * | [ProviderNotRegistered] | 这是个 bug 或用了未安装的 Provider，去反馈 |
 * | [CredentialMissing] | 那条模型挂的 Key 被删了，去重新填 |
 * | [CredentialUndecryptable] | Key 解不开（换机/清数据后主密钥变了），重填 |
 * | [BudgetExceeded] | 任务太贵，去调预算或换便宜模型 |
 * | [UpstreamError] | Provider 那边的问题，重试或换模型 |
 *
 * ⚠️ **这些失败里没有任何一个携带 Key 或请求体** —— 枚举成员刻意
 *    不带 payload（除了上游消息，而它必须已经过 `LogSanitizer`）。
 *    带上 Key 的失败类型迟早会被打进日志。
 */
sealed interface GatewayFailure {

    /** 给用户看的、可以直接显示的一句话 */
    val userMessage: String

    /** 一个都没配 */
    data object NoModelConfigured : GatewayFailure {
        override val userMessage: String = "还没有配置可用的模型，请先去「模型」页添加"
    }

    /** 指定/路由到的模型被禁用了 */
    data class ModelDisabled(val modelConfigId: String) : GatewayFailure {
        override val userMessage: String = "选中的模型已被禁用，请在「模型」页重新启用"
    }

    /** 模型配置存在，但没找到对应的 Provider 实现 */
    data class ProviderNotRegistered(val providerId: String) : GatewayFailure {
        override val userMessage: String = "模型服务商「$providerId」不可用（可能是版本问题）"
    }

    /**
     * 模型挂的凭据不存在。
     *
     * ⚠️ 与 [CredentialUndecryptable] 分开：这里的补救动作是"重新填 Key"，
     *    而那种情况用户往往以为"我明明填过" —— 提示语必须能区分开，
     *    否则他会反复去看那个不存在的条目。
     */
    data class CredentialMissing(val credentialId: String) : GatewayFailure {
        override val userMessage: String = "该模型关联的 API Key 已被删除，请重新添加"
    }

    /** 凭据在，但解不开（主密钥变了 / 密文损坏） */
    data object CredentialUndecryptable : GatewayFailure {
        override val userMessage: String = "API Key 无法解密（可能更换过设备或清除了数据），请重新填写"
    }

    /**
     * 超出预算。
     *
     * ⚠️ [reason] 必须说清**是哪一条限制**被触发、**当前值**是多少 ——
     *    "超出预算"这四个字对用户没有任何用处，他没法据此调整。
     */
    data class BudgetExceeded(
        val limitName: String,
        val limitValue: String,
        val actualValue: String,
    ) : GatewayFailure {
        override val userMessage: String =
            "超出$limitName（上限 $limitValue，当前 $actualValue）"
    }

    /**
     * 上游 Provider 报错。
     *
     * ⚠️ [message] **必须已经过脱敏** —— 它在 `ProviderException` 的
     *    message 里，而那些 message 有时会带上请求片段。
     */
    data class UpstreamError(
        val message: String,
        val retryable: Boolean,
    ) : GatewayFailure {
        override val userMessage: String =
            if (retryable) "模型服务暂时不可用，请稍后重试" else "模型服务返回错误：$message"
    }
}

/**
 * 预算熔断的判定结果。
 *
 * ⚠️ 单独成一个类型而不是 `Boolean` —— 熔断**必须能解释自己**。
 *    返回 Boolean 的话调用方只能回一句"超预算了"，而用户完全不知道
 *    该去调哪个数字。这与 `RoutingDecision.reasons` 是同一个理由。
 */
sealed interface BudgetVerdict {

    /** 放行 */
    data object Allowed : BudgetVerdict

    /** 拒绝，附带可展示的原因 */
    data class Exceeded(val failure: GatewayFailure.BudgetExceeded) : BudgetVerdict

    /**
     * 放行，但要提醒。
     *
     * 用例：价格未知（[RequestBudget.CostFallback.Allow] 放行）。
     * ⚠️ **这种情况必须能在 UI 上显示出来** —— 否则用户的"预算熔断"
     *    对没标价的模型是完全失效的，而他会以为它在保护自己。
     */
    data class AllowedWithWarning(val warning: String) : BudgetVerdict
}
