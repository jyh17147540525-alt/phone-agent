package com.pocketagent.modelrouter

/**
 * 对用户当前模型配置的**体检结论**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它回答的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户在模型配置页配完一堆东西之后，最需要知道的是：
 *
 * 1. **"我现在这套配置能不能跑起来？"** → [runnable]
 * 2. **"如果现在来一个简单任务 / 复杂任务，会派给谁？"** → [byTier]
 * 3. **"我配了这么多，有没有白配的？"** → [disabled] / [noRole]
 *
 * ⚠️ 这三件事**必须由纯逻辑算出来**，不能让界面自己去 `filter` ——
 *    界面上那种 `models.filter { it.enabled && it.canWork }` 散落各处，
 *    改一处漏一处，而且**错了不报错，只是显示得很正常**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 一个刻意的措辞立场
 * ═══════════════════════════════════════════════════════════════
 *
 * [blockers] 里的文案写成「**还不能**跑任务」，而不是「配置错误」。
 *
 * 理由：用户配了一半就去装插件是**正常行为**，不是错误状态。
 * 把它叫"错误"会让用户以为我们坏了；叫"还不能"才是实话 ——
 * 差一个模型而已。
 */
data class ModelSetupSummary(
    /** 全部配置（含禁用的、不含角色的），顺序与传入一致 */
    val all: List<ModelConfig>,

    /** 可被调度去干活的：启用 且 有 WORKER 角色 */
    val workers: List<ModelConfig>,

    /** 可当调度者的：启用 且 有 SCHEDULER 角色 */
    val schedulers: List<ModelConfig>,

    /** 每个档位能用的 worker（已按档位分组，缺的档位不在 map 里） */
    val byTier: Map<ModelTier, List<ModelConfig>>,

    /** 被禁用但存在的配置 —— 界面要能提示"你有 N 个模型被禁用了" */
    val disabled: List<ModelConfig>,

    /**
     * 启用了但**一个角色都没有**的配置。
     *
     * 这是最容易让人困惑的情况：界面显示"已启用"，但它既不能调度也不能干活，
     * 调度时会被静默跳过。**必须显式告诉用户**，否则他会以为配好了。
     *
     * （正常路径上不该出现 —— `roleMask` 为 0 时保存按钮应拦住。
     *   但这个摘要函数不假设上游做对了事。）
     */
    val noRole: List<ModelConfig>,
) {

    /**
     * 现在能不能真的跑起一个任务。
     *
     * 判据是"至少有一个可用的 worker" —— 调度者是**可选**的
     * （见 [ModelSetupSummary] 的设计：难度判断走本地启发式，零成本）。
     */
    val runnable: Boolean get() = workers.isNotEmpty()

    /** 是否有调度者可参与；没有也能跑，只是全部走本地启发式 */
    val hasScheduler: Boolean get() = schedulers.isNotEmpty()

    /**
     * 用户看不到、但会真实影响体验的**缺口**文案。
     *
     * ⚠️ 刻意**不做"自动修复"** —— 不替用户把一个模型标成 WORKER。
     *    我们自己猜的配置，用户下次打开界面会不认识，
     *    而且他会以为自己配过。**宁可显示"还不能跑"，也不替他做主。**
     *
     * 返回空列表 = 没有问题，界面不该显示任何提示（**不要显示"一切正常"**，
     * 那种永远挂在界面上的绿条会被用户无视，和没有一样）。
     */
    val blockers: List<String>
        get() = buildList {
            if (all.isEmpty()) {
                add("还没有配置任何模型")
                return@buildList
            }

            if (workers.isEmpty()) {
                // ⚠️ 分支顺序就是"具体程度"的顺序：越具体的说法越能指路。
                //    最不具体的兜底放最后 —— 说"还没有能执行任务的模型"
                //    虽然永远正确，但用户看着满屏"已启用"的模型会不知所措。
                when {
                    // 启用的都只挂了 SCHEDULER → 这不是"缺模型"，是"角色标错了"
                    schedulers.isNotEmpty() ->
                        add("所有模型都只被设为调度者了，还没有能执行任务的模型")

                    // 有配置但全关着 → 用户自己关的，提醒他去开
                    disabled.isNotEmpty() ->
                        add("模型都被禁用了，至少启用一个才能跑任务")

                    // 启用了但一个角色都没挂 → 配置没填完
                    noRole.isNotEmpty() ->
                        add("有模型还没设置用途，去指定它用来执行任务")

                    else ->
                        add("还没有能执行任务的模型")
                }
            }

            // 只在"确实在用调度模式"时才提醒档位缺口 ——
            // 单模型用户看到"你缺轻量档"只会困惑
            if (workers.size > 1) {
                val missing = ModelTier.values().toList() - byTier.keys
                if (missing.isNotEmpty()) {
                    val names = missing.joinToString("、") { it.displayName }
                    add("没有「$names」档的模型，这些难度的任务会派给相近档位")
                }
            }
        }
}

/**
 * 对模型配置做一次体检（纯逻辑，零 I/O）。
 *
 * ⚠️ 输入若含**重复 id**，按后者覆盖前者 —— 数据库主键决定了不会发生，
 *    但这里不假设上游做对了事（同 [ModelSetupSummary.noRole] 的立场）。
 */
fun summarizeModelSetup(models: List<ModelConfig>): ModelSetupSummary {
    // 去重但**保留首次出现的顺序**：界面排列依赖用户添加顺序，
    // 而 distinctBy 恰好是 stable 的
    val unique = models.distinctBy { it.id }

    val workers = unique.filter { it.canWork }
    val schedulers = unique.filter { it.canSchedule }

    return ModelSetupSummary(
        all = unique,
        workers = workers,
        schedulers = schedulers,
        // groupBy 的键序是首次出现序，不是枚举序 —— 这里显式按档位 rank 排序，
        // 因为界面要按"轻量→均衡→重型"排，而不是按"用户先配了哪个"
        byTier = workers.groupBy { it.tier }
            .toSortedMap(compareBy { it.rank }),
        disabled = unique.filter { !it.enabled },
        noRole = unique.filter { it.enabled && it.roles.isEmpty() },
    )
}

/**
 * 在已配置的模型里，找出**最便宜的那个可用 worker**。
 *
 * 用途：任务难度判为 LIGHT 且有多个轻量档候选时的二次选择，
 * 以及界面上"你这套配置最省能到多少钱"的展示。
 *
 * ⚠️ **缺价格的模型不参与比较**（`estimatedCost` 返回 null）。
 *    如果所有候选都缺价格，返回 null —— **绝不返回列表里的第一个充数**，
 *    那会让界面显示一个假的最低价。
 *
 * @param sampleInputTokens / [sampleOutputTokens] 用于估算的样本量。
 *        默认取 1000/500，约等于一次短指令 + 一段回答。
 */
fun List<ModelConfig>.cheapestFor(
    sampleInputTokens: Int = 1_000,
    sampleOutputTokens: Int = 500,
): ModelConfig? =
    mapNotNull { model ->
        model.estimatedCost(sampleInputTokens, sampleOutputTokens)?.let { model to it }
    }.minByOrNull { it.second }?.first
