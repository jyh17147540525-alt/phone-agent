package com.pocketagent.modelrouter

/**
 * 模型配置仓储的**接口**（由 `app` / `agent` 侧的实现提供，本模块只声明契约）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么接口在这里，实现不在
 * ═══════════════════════════════════════════════════════════════
 *
 * 真正的实现要读 Room 数据库（`ModelConfigDao`），而 `modelrouter` 是
 * **纯 Kotlin 模块**（无任何 `android.*` 依赖）—— 这是刻意的，它让本模块
 * 的全部逻辑（路由、难度评估、配置体检）能在一台没有手机的机器上被离线钉住。
 *
 * 一旦本模块 `implementation(project(":core:database"))`，`run_logic_tests.py`
 * 就再也编不过它（Room 注解 + SQLCipher 工厂类都要写桩，而桩与真实现有偏差时
 * 测试比没有更不可信 —— 见那个脚本的模块准入注释）。
 *
 * 所以：**契约留在本模块，实现搬去能碰 Room 的那一侧**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一个必须说清的语义：`models` 里**含禁用项**
 * ═══════════════════════════════════════════════════════════════
 *
 * 订阅方拿到的列表**不过滤 `enabled`**。过滤是 [ModelRouter] 和
 * [summarizeModelSetup] 的职责 —— 它们得先看到"被禁用的那些"才能
 * 给出"你有 N 个模型被禁用了"这种有用的提示。
 *
 * 如果这里就滤掉了，配置页会显示一个空列表，而用户明明配过东西 ——
 * 他会以为数据丢了。**这是"提前优化"最典型的反噬。**
 */
interface ModelConfigRepository {

    /**
     * 订阅全部模型配置（含禁用），按添加时间升序。
     *
     * 配置页订阅这个。列表**不会因为启用状态变化而重排** ——
     * 用户刚点完一个开关，列表却跳了顺序，他会找不到自己刚操作的那一行。
     */
    val models: kotlinx.coroutines.flow.Flow<List<ModelConfig>>

    /**
     * 一次性取全部。给"发起任务时读一次"这类场景用，不必订阅。
     *
     * 含禁用项，理由同 [models]。
     */
    suspend fun all(): List<ModelConfig>

    /** 按 id 取单条。找不到返回 null —— 不抛异常，"刚被删了"是正常状态 */
    suspend fun byId(id: String): ModelConfig?
}

/**
 * 把 [ModelConfigRepository] 与 [ModelRouter] 接起来：读配置 → 路由 → 结论。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它解决的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 光有 [ModelRouter] 没人能用 —— 它要调用方自己把 `List<ModelConfig>` 递进去，
 * 而在 agent 真正要发请求的那一刻，没人去数据库读这个列表。
 * 结果是"调度器写得很完整，但线上永远只有一个模型"。
 *
 * 这个类就是那一根线：**读一次，路由一次，返回结论**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它刻意不做的事
 * ═══════════════════════════════════════════════════════════════
 *
 * - **不缓存**。每次路由都重新读。听起来浪费，但模型配置是"读极多、写极少"
 *   的数据（用户配一次用几个月），而 SQLite 那次查询是内存级开销。
 *   缓存反而会引入"用户在配置页改了档位，但正在跑的任务还按旧配置走"
 *   这种**看不出原因的诡异行为**。
 *
 * - **不解析凭据**。它只回答"用哪个 modelConfigId"，至于那个配置挂的
 *   `credentialId` 怎么取明文 Key，是 `LlmGateway` 的事。这条边界让
 *   本类可以完全不碰加密存储。
 *
 * - **不决定 RoutingMode**。模式是用户的选择（单模型 / 调度），
 *   由调用方传入。本类不猜 —— 猜错的后果是"我只配了一个模型，
 *   它却按调度模式到处找 worker"。
 */
class ModelRouteCoordinator(
    private val repository: ModelConfigRepository,
    private val router: ModelRouter = ModelRouter(),
) {

    /**
     * 按当前模式为一条指令选模型。
     *
     * @param mode 当前路由模式（用户的选择，不由本类推导）
     * @param instruction 用户的原始指令，用于难度评估
     * @param context 任务上下文（步数、是否已失败过等）
     * @param tierOverride 调度模型给出的难度判定；null = 没调或调用失败，
     *        由本地启发式兜底。**传 null 不是降级，是默认路径** ——
     *        绝大多数用户不配调度模型，本地评估零成本零延迟。
     * @return 路由结论。失败时返回 [RoutingOutcome.Failure] 而非抛异常 ——
     *         "用户还没配模型"是正常业务状态，见 [RoutingFailure] 的注释。
     */
    suspend fun route(
        mode: RoutingMode,
        instruction: String,
        context: TaskContext = TaskContext(),
        tierOverride: ModelTier? = null,
    ): RoutingOutcome =
        router.route(
            mode = mode,
            models = repository.all(),
            instruction = instruction,
            context = context,
            tierOverride = tierOverride,
        )

    /**
     * 对当前配置做一次体检。配置页顶部那条提示由它驱动。
     *
     * 空 [ModelSetupSummary.blockers] = 不该显示任何提示（**不要显示"一切正常"**，
     * 那种永远挂着的绿条会被用户无视）。
     */
    suspend fun setupSummary(): ModelSetupSummary =
        summarizeModelSetup(repository.all())

    /**
     * 推断当前**最合理的**路由模式。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 只有在"用户从没做过选择"时才该调用它
     * ═══════════════════════════════════════════════════════════
     *
     * 用户一旦显式选了模式（存在偏好设置里），就必须用他的选择 ——
     * 每次启动都重新推断会把他的设定悄悄改回去，而他找不到地方改回来。
     *
     * 规则（按优先级）：
     * 1. 没有可用 worker → null（跑不起来，调用方该引导去配置）
     * 2. 只有一个 worker → [RoutingMode.Single]（**默认路径**，零认知负担）
     * 3. 有多个 worker 且有调度者 → [RoutingMode.Scheduled]，用调度者
     * 4. 有多个 worker 但没调度者 → [RoutingMode.Scheduled]，调度者位留空
     *    （难度走本地启发式）—— **仍然返回 Scheduled**，因为多个 worker
     *    意味着用户想要按档位派发，这是他的意图
     *
     * @return null 表示"还没配好，无法推断"。调用方应引导用户去配置页，
     *         而不是抛异常。
     */
    suspend fun inferMode(): RoutingMode? {
        val summary = setupSummary()

        if (!summary.runnable) return null

        // ⚠️ 单 worker 是最常见的情况（绝大多数用户只配一个模型）。
        //    这条必须排在调度模式之前 —— 否则一个误标了 SCHEDULER 角色的
        //    单模型用户会掉进调度模式，然后因为 tierModels 只有一项而
        //    每次都被判定为"降级"，界面上一直挂着他看不懂的提示。
        if (summary.workers.size == 1) {
            return RoutingMode.Single(summary.workers.first().id)
        }

        // 多 worker：按档位建表。同一档位有多个时取**第一个**（添加顺序靠前的），
        // ⚠️ 刻意不"按价格挑最便宜的"—— 那会让用户在配置页看到自己排第一的模型
        //    从不被使用，而他没有任何线索知道为什么。选哪个是用户排的顺序说了算，
        //    省钱与否他会自己调整顺序。（智能选择属于后续优化，且必须让用户可见）
        val tierModels = summary.workers
            .groupBy { it.tier }
            .mapValues { (_, list) -> list.first().id }

        return RoutingMode.Scheduled(
            // 没有调度者时给空串 —— ModelRouter 只把它当标识传下去，
            // 不解析它。空串在这里表示"手动指定了模式，但没有调度模型"。
            schedulerModelConfigId = summary.schedulers.firstOrNull()?.id.orEmpty(),
            tierModels = tierModels,
        )
    }
}
