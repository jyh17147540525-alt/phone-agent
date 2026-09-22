package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.modelrouter.RoutingFailure
import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.modelrouter.RoutingOutcome
import com.pocketagent.provider.api.LlmProvider

/**
 * 解密后的凭据 + 它属于哪个 Provider。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么 `provider` 在这里而不在 [ResolvedTarget] 里
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是实施时发现的一个**真实结构事实**，值得写下来：
 *
 * ```
 *   ModelConfigEntity  →  credentialId          （模型挂在哪把 Key 上）
 *   CredentialEntity   →  providerId            （这把 Key 属于哪家）
 * ```
 *
 * **`providerId` 在凭据上，不在模型配置上。** 所以"用哪家 Provider"
 * 只能等到凭据解析出来才知道 —— 它不是一个可以在路由阶段回答的问题。
 *
 * 第一版 [RoutingBridge] 曾试图在路由阶段就把 Provider 解析出来，
 * 结果是只能靠"这个模型 id 像不像某家的"去猜，而那正是我们明确
 * 反对的做法（同一个模型 id 可以通过 OpenRouter 访问任何一家）。
 * 现在改成：**路由只回答"用哪条模型配置"，Provider 由凭据决定**。
 *
 * 这反而更正确 —— 用户完全可以给同一家配两把 Key（一个便宜渠道、
 * 一个稳定渠道），"哪把 Key"才是决定"走哪个端点"的东西。
 */
class ResolvedCredential(
    /** 明文 Key。用 ByteArray 而非 String —— String 进了 JVM 字符串池就擦不掉 */
    val apiKey: ByteArray,

    /**
     * 这把 Key 属于哪家 Provider（`CredentialEntity.providerId`）。
     *
     * ⚠️ 单独带出来而不是让调用方再去查一次 —— 那会多一次数据库往返，
     *    且两次查询之间凭据可能被删，出现"Key 拿到了但 providerId 查不到"
     *    的中间态。
     */
    val providerId: String,

    /** 用户覆盖的 BaseUrl；null = 用 Provider 默认值 */
    val baseUrlOverride: String? = null,

    /** 额外请求头（自定义 Provider 场景） */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    /** 用完立即清零。放在 `finally` 里。 */
    fun clear() {
        apiKey.fill(0)
    }

    /** 禁止打印内容 */
    override fun toString(): String =
        "ResolvedCredential(***, provider=$providerId, baseUrl=$baseUrlOverride)"

    /**
     * 转成 Provider 层的凭据对象。
     *
     * ⚠️ **转出来的对象与 `this` 共享同一个 `apiKey` 数组**（刻意，不复制）——
     *    复制会让"清零"只清掉其中一份，另一份明文留在堆上。
     *    代价：调用方**只能 clear 一次**，且必须在两者都用完之后。
     */
    fun toProviderCredential(): com.pocketagent.provider.api.ProviderCredential =
        com.pocketagent.provider.api.ProviderCredential(
            apiKey = apiKey,
            baseUrlOverride = baseUrlOverride,
            extraHeaders = extraHeaders,
        )
}

/**
 * 凭据来源 —— 把"取出密文 / 解密 / 查它的 providerId"抽成接口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这个接口是 `GatewayCore` 能进离线验证器的**唯一前提**
 * ═══════════════════════════════════════════════════════════════
 *
 * 真实实现要读 Room（`CredentialRepository`）并调 `CryptoManager`
 * （Android Keystore）—— 两者都会让本模块带上 Android 依赖，而
 * `tools/verify/run_logic_tests.py` 按模块**整目录**编译 `src/main/kotlin`，
 * 没有"只挑纯逻辑文件"的能力。
 *
 * 所以：**契约在这里，实现搬去 `keymgmt`**（那里本来就有 Room 与 Keystore）。
 * 这与 `ModelConfigRepository` 完全一致 —— 那个接口在 `:modelrouter`、
 * 实现在 `:keymgmt`，理由相同。
 *
 * ⚠️ **返回可空而不是抛异常**：凭据被删是**正常状态**（用户在配置页删了
 *    一条 Key，而某个模型还挂着它）。抛异常会让"用户删了个东西"表现得
 *    像"程序出错了"，而调用方还得写 try/catch 才能给出正确提示。
 */
interface CredentialSource {

    /**
     * 取出一条凭据并**解密**。
     *
     * @return null = 凭据不存在（可能刚被删）。**不是异常**。
     * @throws CredentialDecryptException 密文在但解不开（主密钥变了 / 密文损坏）。
     *         必须与"不存在"分开 —— 两者的用户补救动作不同。
     */
    suspend fun resolve(credentialId: String): ResolvedCredential?
}

/**
 * 密文存在但解不开。
 *
 * ⚠️ 刻意做成异常而不是 null 返回 —— 它不是"正常业务状态"，而是
 *    "存储层出了状况"（换机、清了应用数据、密文损坏）。用 null 表示会
 *    让它与"凭据被删"混为一谈，而用户会去找那个根本不存在的条目。
 */
class CredentialDecryptException(cause: Throwable? = null) :
    Exception("credential cannot be decrypted", cause)

/**
 * 一次路由的落点：**用哪条模型配置**。
 *
 * ⚠️ 刻意**不含 Provider，也不含明文凭据**：
 *
 * - 不含 Provider —— 理由见 [ResolvedCredential]（它在凭据上，此时还不知道）
 * - 不含明文凭据 —— 拿到它之后还要过预算熔断，熔断可能拒绝。若已经解密，
 *   就白做了一次 Keystore 调用，且明文在内存里多待一段。
 *   **解密必须放在预算检查之后**（[GatewayCore] 负责这个顺序）。
 */
data class ResolvedTarget(
    /** 数据库 `model_config.id`，用于计量归属 */
    val modelConfigId: String,
    /** 路由用的模型配置（含价格与 modelId，给熔断与请求构造用） */
    val config: ModelConfig,
    /** 发给 Provider 的模型 id（如 `"deepseek-chat"`） */
    val modelId: String,
    /** 凭据 id，解密时用 */
    val credentialId: String,
    /** 路由决策说明，给用户看 */
    val reasons: List<String>,
    /** 是否降级（用户没配该档位） */
    val degraded: Boolean,
)

/**
 * 路由桥。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它把两段各自完备、但没人连起来的东西接上
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ModelRouteCoordinator  →  选出一个 modelConfigId
 *   ModelConfigRepository  →  把 id 变成 ModelConfig（含 credentialId / modelId）
 * ```
 *
 * 项目已经吃过一次"两段各自完备、中间无人"的亏：`ModelConfigDao` 与
 * `ModelRouter` 之间长期没有仓储层，结果是"调度器写得很完整，线上
 * 永远只有一个模型"。所以这一层必须显式存在，而不是让调用方临场拼。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它刻意不做的事
 * ═══════════════════════════════════════════════════════════════
 *
 * - **不解析凭据**（只把 `credentialId` 传下去）
 * - **不解析 Provider**（理由见 [ResolvedCredential]）
 * - **不决定 RoutingMode**（那是用户的选择，由调用方传入）
 * - **不缓存模型列表**（`ModelRouteCoordinator` 刻意不缓存，理由见它的注释）
 */
class RoutingBridge(
    private val coordinator: ModelRouteCoordinator,
    /**
     * 按 id 取模型配置。
     *
     * ⚠️ 刻意收成一个**函数**而不是收 `ModelConfigRepository` 对象：
     *    `ModelRouteCoordinator` 已经把那个仓储包在里面了，再收一个会
     *    出现"同一个仓储注入两次"，而两者在测试里很容易被换成不同的
     *    fake —— 表现是"路由用的是这份配置、取值用的是另一份"，
     *    测试却过（因为两份 fake 返回一样）。
     *
     *    收函数还有个好处：测试里一行 lambda 就够了，不需要再造一个 fake 类。
     */
    private val modelById: suspend (String) -> ModelConfig?,
) {

    /**
     * 为一次调用选出目标。
     *
     * @param mode 用户选择的路由模式（不由本类推断）
     * @param ctx 网关上下文
     * @return 成功给出 [ResolvedTarget]，失败给出 [GatewayFailure]。
     *         **失败不抛异常** —— 见 `RoutingFailure` 的注释。
     */
    suspend fun resolve(
        mode: RoutingMode,
        ctx: GatewayContext,
    ): GatewayOutcome<ResolvedTarget> {
        val outcome = coordinator.route(
            mode = mode,
            instruction = ctx.instruction,
            context = ctx.taskContext,
            tierOverride = ctx.tierOverride,
        )

        val decision = when (outcome) {
            is RoutingOutcome.Failure -> return GatewayOutcome.Failure(
                outcome.reason.toGatewayFailure()
            )

            is RoutingOutcome.Success -> outcome.decision
        }

        // ⚠️ `modelOverride` 的语义是"**调用方**明确指定，跳过路由"，
        //    不是"客户端想用什么"。ChatRequest.model 由客户端填，
        //    而那个字段被 GatewayCore 刻意忽略（理由见它的类注释）。
        //
        //    ⚠️ 覆写之后**照样走完整校验** —— 直接信 override 会绕过
        //       `ModelRouter` 里的存在性与启用检查，表现是
        //       "指定一个被禁用的模型，请求照样发出去"。
        val targetId = ctx.modelOverride ?: decision.modelConfigId

        val config = modelById(targetId)
            // override 指了一个不存在的 id → 与"没配模型"同类失败。
            // ⚠️ 不区分这两者：对用户来说补救动作都是"去配置页看看"，
            //    而多一个失败类型意味着多一处需要维护的提示文案。
            ?: return GatewayOutcome.Failure(GatewayFailure.NoModelConfigured)

        if (!config.enabled) {
            return GatewayOutcome.Failure(GatewayFailure.ModelDisabled(config.id))
        }

        // ⚠️ credentialId 为空说明这条配置是坏的（历史数据 / 手改过库）。
        //    静默放行会让它一路走到解密那一步，然后报一个"凭据不存在"，
        //    而用户会去找一条不存在的 Key —— 排查方向完全错。
        if (config.credentialId.isBlank()) {
            return GatewayOutcome.Failure(GatewayFailure.CredentialMissing(""))
        }

        return GatewayOutcome.Success(
            ResolvedTarget(
                modelConfigId = config.id,
                config = config,
                modelId = config.modelId,
                credentialId = config.credentialId,
                // ⚠️ override 生效时，路由给的 reasons 描述的是"路由为什么
                //    选它"，而实际用的不是那个 —— 必须说清，否则用户看到
                //    "判为轻量档"却用着重型模型，会以为调度坏了。
                reasons = if (ctx.modelOverride != null) {
                    decision.reasons + "（本次由调用方指定模型，已跳过调度）"
                } else {
                    decision.reasons
                },
                degraded = if (ctx.modelOverride != null) false else decision.degraded,
            )
        )
    }
}

/**
 * 网关内部的两态结果。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 与 `RoutingOutcome` 一样刻意不用 Kotlin 的 `Result<T>`
 * ═══════════════════════════════════════════════════════════════
 *
 * `Result.failure` 要求失败侧是 `Throwable`，而这里的失败**全部**是
 * 可枚举的业务状态（没配模型、Key 被删、超预算）。用 `Result<T>` 会让
 * 类型系统诱导调用方把正常分支写成 `try/catch`，而 catch 到的又是
 * 我们为了满足签名而伪造的异常 —— 那是纯粹的噪声。
 *
 * 立场与 `:modelrouter` 完全一致（见那边的 `RoutingFailure` 注释）。
 */
sealed interface GatewayOutcome<out T> {
    data class Success<T>(val value: T) : GatewayOutcome<T>
    data class Failure(val reason: GatewayFailure) : GatewayOutcome<Nothing>
}

/** 便捷取值，语义同 `Result.getOrNull()` */
fun <T> GatewayOutcome<T>.valueOrNull(): T? = (this as? GatewayOutcome.Success)?.value

/** 便捷取值，语义同 `Result.exceptionOrNull()` */
fun <T> GatewayOutcome<T>.failureOrNull(): GatewayFailure? =
    (this as? GatewayOutcome.Failure)?.reason

/**
 * `RoutingFailure` → `GatewayFailure` 的映射。
 *
 * ⚠️ **必须是穷举 `when`，不能带 `else` 分支** ——
 *    `:modelrouter` 将来加一个 `RoutingFailure` 子类时，穷举 `when` 会
 *    **编译失败**，逼我们回来补映射；带 `else` 的版本会静默把它归到
 *    else 那一类，表现是"某种配置错误给出了不相关的提示语"。
 *
 * ⚠️ 同样**不用 `valueOf` / `ordinal`**：枚举改名时 `valueOf` 编译通过、
 *    运行时抛异常（MEMORY 里 `ModelTierEntity` 那条记的就是这个）。
 */
private fun RoutingFailure.toGatewayFailure(): GatewayFailure = when (this) {

    // 一个都没配 → 引导去配置页
    RoutingFailure.NoModelsConfigured -> GatewayFailure.NoModelConfigured

    // 指定的那条被删了 → 补救动作同样是"去配置页看看"，
    // ⚠️ 刻意不复用 ModelDisabled：那个的补救是"启用"，这个是"重建"
    is RoutingFailure.ModelNotFound -> GatewayFailure.NoModelConfigured

    // 有配置但被禁用 → 补救是"启用"，与上面不同，必须分开
    is RoutingFailure.ModelDisabled -> GatewayFailure.ModelDisabled(modelConfigId)

    // 调度模式下没指定任何工作模型 → 同样去配置页
    RoutingFailure.NoWorkerModels -> GatewayFailure.NoModelConfigured
}
