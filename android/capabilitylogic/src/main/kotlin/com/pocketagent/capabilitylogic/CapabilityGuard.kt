package com.pocketagent.capabilitylogic

/**
 * 能力调用的裁决器 —— **把"能不能做"从"怎么做"里切出来**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  判定顺序（短路返回，**顺序不可调换**）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * 0. 目录里查得到吗            ← 查不到 → 拒绝（这不是"没实现"，是"从没被允许过"）
 * 1. 硬拒绝：能力 id 前缀      ← 命中 → 永远拒绝
 * 2. 来源限制                  ← 插件 × GUARDED → 拒绝
 * 3. 参数校验                  ← 有问题 → 拒绝（附具体问题）
 * 4. 规划（纯函数，此时必然成功）
 * 5. 对**规划结果**再做硬校验  ← 设置键 / 目标包名命中 → 永远拒绝
 * 6. 用户放行了吗              ← 没放行 → 拒绝（可引导去放行）
 * 7. 风险级别 + 确认标志       ← GUARDED 且未确认 → RequireConfirmation
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 第 5 步为什么要在"规划之后"再查一次
 * ═══════════════════════════════════════════════════════════════
 *
 * 第 1 步查的是**声明**（能力 id），第 5 步查的是**将要执行的东西**（argv / 设置键）。
 * 两者看起来重复，但它们防的是不同的东西：
 *
 * - 第 1 步防"有人往目录里加了一条危险能力"；
 * - 第 5 步防"某条**看起来安全**的能力，被某组参数变成了危险操作"。
 *
 * 具体例子：`app.set_enabled(action=disable, packageName=…)` 本身完全正当。
 * 但 `packageName = com.android.systemui` 会让用户**连状态栏和导航都没有了** ——
 * 而 `com.android.systemui` 是**格式合法的包名**，第 3 步的参数校验拦不住它。
 *
 * 所以第 5 步检查的是**最终产物**，而不是"我们以为调用方会传什么"。
 * 这个位置也让**将来新增的能力自动获得这层保护** —— 不需要每条能力各写一遍。
 *
 * ⚠️ 第 5 步的包名检查是**精确匹配**（集合成员判断），不是前缀或模糊匹配。
 *    精确匹配在这里够用：命令里出现的包名是完整的一项，不会被截断。
 *    而模糊匹配会误伤（`com.android.settingshelper` 不是系统设置）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么"确认"排在最后，而且**绝不能**跳过前面几步
 * ═══════════════════════════════════════════════════════════════
 *
 * 最自然的写法是在开头加一句：
 *
 * ```kotlin
 * if (call.confirmedByUser) return Allowed(...)   // ✗ 危险
 * ```
 *
 * 它读起来很合理 —— 用户都点过确认了，还检查什么？**但那样一来，
 * 确认就变成了万能钥匙**：只要界面上任何一个确认框被点过一次，
 * 任何能力、任何参数都能通过。
 *
 * 具体的路径很现实：
 *
 * ```
 * 1. agent 请求「切换深色模式」，用户看了一眼，点了确认
 * 2. 在真正执行之前，agent 把 capabilityId 换成 app.set_enabled
 *    （packageName = com.android.systemui）
 * 3. 裁决器看到 confirmedByUser = true → 直接放行
 * ```
 *
 * 第 2 步不需要恶意 —— 它可以是模型的一次"修正"、一次重试。
 * **用户确认的是他看到的那个操作，不是这个函数收到的那个操作。**
 *
 * 所以纪律是：**硬拒绝、来源限制、参数校验、规划后校验在每一次 `decide`
 * 里都重新跑一遍**；`confirmedByUser` 只是一个"如果结论是
 * [CapabilityVerdict.RequireConfirmation]，就把它降级为
 * [CapabilityVerdict.Allowed]"的开关。它不改变任何前置判定。
 * 这与 `filelogic` 的 `FileAccessDecider` 完全同构。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么"有没有放行"（第 6 步）排在"参数对不对"（第 3 步）之后
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者都是拒绝，谁先谁后不影响安全性，只影响**用户看到哪句话**。
 * 把参数校验放前面，是因为"参数不合法"是**发起方的 bug**，
 * 而"没放行"是**用户的配置状态**。前者的信息价值更高 ——
 * 一个参数写错的调用，即使放行了也执行不了，早一步报出来能省一轮排查。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 放行策略是**注入**的，不是硬编码
 * ═══════════════════════════════════════════════════════════════
 *
 * [isGranted] 是构造参数，默认实现是"只有 [CapabilityRisk.SAFE] 默认放行"。
 * 真实的授权记录来自数据库，本模块不认识它 —— 这是 domain 层零 Android 依赖
 * 的必然结果，也让"用户放行了哪些能力"这件事只有**一个**权威来源。
 *
 * ⚠️ 注入点也是**收紧**的地方：把 [isGranted] 实现成永远返回 `false`，
 *    就得到一个"连 SAFE 能力都要逐项放行"的最严模式。
 *    但**没有任何注入方式能让第 0~5 步失效** —— 它们不读这个函数。
 */
class CapabilityGuard(

    /** 能力目录。见 [CapabilityCatalog] —— 内置 15 条，可被插件扩展（但不能覆盖）。 */
    private val catalog: CapabilityCatalog,

    /**
     * 用户是否已放行这条能力。
     *
     * 默认只放行 [CapabilityRisk.SAFE]。⚠️ 它**只**影响第 6 步，
     * 不参与任何硬拒绝判定 —— 见类注释最后一节。
     */
    private val isGranted: (Capability) -> Boolean = { it.risk == CapabilityRisk.SAFE },
) {

    fun decide(call: CapabilityCall): CapabilityVerdict {

        // ── 步骤 0：目录里查得到吗 ────────────────────────────────
        //
        // ⚠️ 这里的措辞很重要。查不到**不是**"功能还没实现"，
        //    而是"这个操作从来没有被允许过"。两者对用户的意义完全不同：
        //    前者他会等一个更新，后者他应当立刻意识到"有东西在乱调"。
        val capability = catalog.byId(call.capabilityId)
            ?: return CapabilityVerdict.Blocked(
                reason = BlockReason.UNKNOWN_CAPABILITY,
                userMessage = "我不认识「${sanitizeForDisplay(call.capabilityId, 64)}」这个操作，所以没有执行。" +
                    "这通常说明发起方（插件或模型）用了一个不存在的名字。",
                canFallbackToManual = false,
            )

        // ── 步骤 1：硬拒绝（能力 id）──────────────────────────────
        //
        // 这是一张单向阀：它不参与任何覆盖逻辑，只会拒绝。
        // 用户把授权划到最大、插件往目录里塞任意能力，都越不过它。
        if (CapabilityDenyRules.isCapabilityDenied(capability.id)) {
            return CapabilityVerdict.Blocked(
                reason = BlockReason.DENIED_BY_POLICY,
                userMessage = "「${capability.id}」属于固定不允许的操作。" +
                    "这一条不受任何授权或插件影响 —— 它不在可配置范围内。",
                canFallbackToManual = false,
            )
        }

        // ── 步骤 2：来源限制 ──────────────────────────────────────
        //
        // 插件碰不到 GUARDED。理由不是"不信任插件作者"，而是
        // **插件是可以被替换的**：用户装了一个插件、给了它权限，
        // 之后那个插件更新成了另一个东西 —— 用户没有任何机会察觉。
        // 而 GUARDED 能力影响的是整台设备（断网、停用 App、改亮度），
        // 出了问题的表现是"手机不太对劲"，没人会想到是三天前更新的插件。
        if (call.origin == CallOrigin.PLUGIN && capability.risk == CapabilityRisk.GUARDED) {
            return CapabilityVerdict.Blocked(
                reason = BlockReason.PLUGIN_NOT_ALLOWED_GUARDED,
                userMessage = "插件不能执行会影响整台设备的操作（${capability.summary}）。" +
                    "如果你确实需要，请自己在界面上做一次。",
                canFallbackToManual = true,
            )
        }

        // ── 步骤 3：参数校验 ──────────────────────────────────────
        val problems = CapabilityParams.validate(capability, call.args)
        if (problems.isNotEmpty()) {
            return CapabilityVerdict.Blocked(
                reason = BlockReason.INVALID_ARGS,
                userMessage = "这次调用的参数不合法，所以没有执行：" +
                    problems.joinToString("；") { describeProblem(it) },
                canFallbackToManual = false,
                problems = problems,
            )
        }

        // ── 步骤 4：规划 ──────────────────────────────────────────
        //
        // 此时参数必然完整且合法，所以这一步不会失败。
        // 规划是纯函数，它**不做任何判定** —— 判定已经做完了。
        val execution = CommandPlanner.plan(capability, call.args)

        // ── 步骤 5：对规划结果再做硬校验 ──────────────────────────
        //
        // 见类注释：这一步查的是"将要执行的东西"，能拦住
        // "安全的能力 + 危险的参数"这种组合。
        when (execution) {
            is PlannedExecution.SettingWrite -> {
                if (CapabilityDenyRules.isSettingKeyDenied(execution.namespace, execution.key)) {
                    return CapabilityVerdict.Blocked(
                        reason = BlockReason.DENIED_SETTING_KEY,
                        userMessage = "这次操作要改写系统设置「${execution.namespace.wireName}/${execution.key}」，" +
                            "它属于固定不可改写的一项 —— 改写它等于把 PocketAgent 的安全限制本身拆掉。" +
                            "所以没有执行。",
                        canFallbackToManual = false,
                        execution = execution,
                    )
                }
            }

            is PlannedExecution.ShellArgv -> {
                // ⚠️ 精确匹配。见类注释里"为什么不用模糊匹配"。
                val hit = execution.argv.firstOrNull { CapabilityDenyRules.isPackageDenied(it) }
                if (hit != null) {
                    return CapabilityVerdict.Blocked(
                        reason = BlockReason.DENIED_TARGET_PACKAGE,
                        userMessage = "这次操作会作用到系统组件「$hit」。停用它之后你可能连桌面或状态栏都没有了，" +
                            "所以没有执行。",
                        canFallbackToManual = false,
                        execution = execution,
                    )
                }
            }

            // ⚠️⚠️ 这一支**是空的，而且刻意是空的** —— 不是漏了。
            //
            //    步骤 5 查的是「将要执行的东西」里那些**不依赖文件系统就能判**的
            //    结构性危险。文件操作里所有这一类的判据都已经在**别处**拦掉了：
            //
            //      · 路径为空 / 含 NUL / **是相对路径** / `..` 越过根
            //        → `:filelogic` 的 `PathNormalizer.normalize()` 直接 `Rejected`，
            //          由裁决器翻译成 `MALFORMED_PATH`。上面那个 `$raw` 插值
            //          之所以能给出"收到的是「foo/bar」"这种具体的话，就是因为
            //          归一化器保留了原始输入。
            //      · 路径是否落在授权目录内、是否命中黑名单
            //        → `FileAccessDecider`（需要 `FileScope`，本类没有）
            //      · 内容长度上限（`file.write` 的 `content`）
            //        → 步骤 3 的 `CapabilityParams.validate()` 已经挡过
            //
            //    ★ 而**真正的文件裁决放不到这里**，原因是一条硬约束：
            //      `decide()` 是**同步**的（这是它能进离线跑器的前提），
            //      而文件裁决必须先 `stat` 一次拿到"在不在、是不是目录、多大"
            //      —— 那是 suspend + IO。
            //
            //    ⚠️ 所以这里**不要**为了"看起来更安全"而塞一条重复检查。
            //      重复的后果不是双保险，而是"什么允许"同时存在于两处 ——
            //      而两处漂移的方向永远是**松的那一份生效**（调用方会走到
            //      它先通过的那条路上）。文件裁决的唯一权威是
            //      `FileAccessDecider`，入口在 `CapabilityRunner.runFile()`。
            is PlannedExecution.FileIntent -> Unit
        }

        // ── 步骤 6：用户放行了吗 ──────────────────────────────────
        //
        // ⚠️ `canFallbackToManual = true`：这不是"被拒绝"，是"还没配置"。
        //    界面上应当给出"去放行"的按钮，而不是一个红色的失败提示。
        //    与 `FileAccessDecider` 里 NO_SCOPE 的处理是同一个判断。
        if (!isGranted(capability)) {
            return CapabilityVerdict.Blocked(
                reason = BlockReason.NOT_GRANTED,
                userMessage = "「${capability.summary}」还没有被你放行，所以我没有执行。" +
                    "要使用它，请到「能力授权」里单独打开这一条。",
                canFallbackToManual = true,
                execution = execution,
            )
        }

        // ── 步骤 7：风险级别 + 确认标志 ───────────────────────────
        if (capability.risk == CapabilityRisk.GUARDED) {

            // ★ 定时任务的特殊处理：**常驻授权即预声明**。
            //
            // 无人值守时弹确认框是没有意义的 —— 没人会点，超时之后
            // 按"视为拒绝"处理，用户第二天看到的是一次**静默失败**，
            // 而他还以为自己昨晚设的任务跑过了。那比直接不做更糟。
            //
            // 所以这里的规则是：用户既然已经**逐项放行**过这条 GUARDED 能力，
            // 那次放行就同时覆盖了"夜间自动执行"这个场景。
            // 代价是它不再逐次确认 —— 所以 [CapabilityCall.origin] 必须
            // 如实标成 SCHEDULE，审计日志要靠它回答
            // "这条操作是凌晨自动做的，还是白天有人看着做的"。
            //
            // ⚠️ 更细的"夜间任务白名单"（任务级、而非能力级）是下一层的事，
            //    它活在本模块之上。本模块只保证：**放行是用户做的、可撤销、有记录**。
            if (call.origin == CallOrigin.SCHEDULE) {
                return CapabilityVerdict.Allowed(
                    capability = capability,
                    execution = execution,
                    wasConfirmed = false,
                )
            }

            if (!call.confirmedByUser) {
                return CapabilityVerdict.RequireConfirmation(
                    capability = capability,
                    execution = execution,
                    reason = ConfirmReason.GUARDED_CAPABILITY,
                    userMessage = "要执行「${capability.summary}」。" +
                        "这一次具体是：${execution.summary}。" +
                        "如果不确定，选「不要」不会有任何影响。",
                )
            }

            // 用户确认过 —— 注意这里**记的是"这次放行是确认换来的"**，
            // 不是"这个操作危不危险"。审计日志要靠它回答
            // "用户一共确认过多少次这类操作"。
            return CapabilityVerdict.Allowed(
                capability = capability,
                execution = execution,
                wasConfirmed = true,
            )
        }

        return CapabilityVerdict.Allowed(
            capability = capability,
            execution = execution,
            wasConfirmed = false,
        )
    }

    /**
     * 把参数问题翻译成用户能看懂的一句话。
     *
     * ⚠️ 会**回显用户传入的值** —— 这是刻意的：用户要判断的是
     *    "我传的那个值到底哪里不对"，不回显他无从对照。
     *    所以值必须消毒（控制字符转义 + 截断），否则一个含换行的
     *    参数值就能在界面上伪造出第二行说明。
     */
    private fun describeProblem(problem: ParamProblem): String = when (problem) {
        is ParamProblem.Missing ->
            "缺少参数「${problem.name}」（${problem.summary}）"

        is ParamProblem.Unknown ->
            "不认识参数「${problem.name}」，这个操作只接受：${
                problem.known.sorted().joinToString("、")
            }"

        is ParamProblem.NotAllowed ->
            "「${problem.name}」的值「${sanitizeForDisplay(problem.value, 40)}」不在允许范围内（只能是 ${
                problem.allowed.joinToString("、")
            }）"

        is ParamProblem.OutOfRange ->
            "「${problem.name}」的值「${sanitizeForDisplay(problem.value, 40)}」超出范围（" +
                "${problem.range.first} 到 ${problem.range.last}）"

        is ParamProblem.NotPackageName ->
            "「${problem.name}」的值「${sanitizeForDisplay(problem.value, 64)}」不是合法的应用包名"

        is ParamProblem.BadText ->
            "「${problem.name}」不合法：${problem.reason}"
    }
}

/**
 * 裁决结论。
 *
 * ⚠️ [Allowed] 与 [RequireConfirmation] 都**带着 [PlannedExecution]**。
 *    这不是顺手，而是设计的一部分：**从"裁决通过"到"拿去执行"之间
 *    没有任何自由度** —— 执行方拿到的就是裁决时校验过的那一个对象，
 *    它没有机会（也没有必要）自己再拼一次命令。
 *
 *    如果这里只返回一个 `Boolean`，那么"裁决"和"执行"之间就会重新出现
 *    一段无人校验的代码，而漏洞正是长在那种地方的。
 *
 * ⚠️ [Blocked] **也可能**带着 [Blocked.execution]，但两者的用途完全不同：
 *    · [Allowed] / [RequireConfirmation] 带着它是为了**拿去执行**；
 *    · [Blocked] 带着它是**只为记日志** —— 见 [Blocked.execution] 的注释。
 *    所以判断"能不能执行"时**不要**写成 `verdict is Blocked && verdict.execution != null`，
 *    那正好把两个语义搞反了。能执行的判据只有一个：`is Allowed`。
 */
sealed interface CapabilityVerdict {

    /** 便利判据。用于界面分流，不用于安全判定。 */
    val isBlocked: Boolean get() = this is Blocked

    /**
     * 放行。
     *
     * ⚠️ 拿到它就**直接执行 [execution]**，不要再做"我要不要换个参数"的调整。
     */
    data class Allowed(
        val capability: Capability,
        val execution: PlannedExecution,

        /**
         * 这次放行是不是"用户点了确认"换来的。
         *
         * ⚠️ 语义是"**这次**放行经过了确认"，不是"这个操作危险"。
         *    [CapabilityRisk.SAFE] 的能力永远是 `false`。
         */
        val wasConfirmed: Boolean,
    ) : CapabilityVerdict

    /**
     * 需要用户确认。
     *
     * ⚠️ 它**不是**拒绝：调用方应当把 [userMessage] 展示出来、等用户决定，
     *    然后把结果通过 [CapabilityCall.confirmedByUser] 回传，
     *    再走一次 [CapabilityGuard.decide]。
     *
     *    ⚠️ **不要**把这次裁决的结果缓存下来、用户点确认后直接执行 ——
     *    那就等于把"确认"变成了提前返回的理由，正好绕过了本类要防的东西。
     *    必须**重新裁决一次**，让所有前置判定再跑一遍。
     */
    data class RequireConfirmation(
        val capability: Capability,
        val execution: PlannedExecution,
        val reason: ConfirmReason,
        val userMessage: String,

        /**
         * 等多久算放弃。
         *
         * ⚠️ 超时**一律视为拒绝**，且必须在审计日志里与"用户主动点不要"
         *    分开记 —— 合并成一条会让"我明明没点过同意，怎么执行了"无从查起。
         */
        val timeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    ) : CapabilityVerdict

    /**
     * 拒绝。
     *
     * ⚠️ 与 [RequireConfirmation] 的区别是：**没有任何用户操作能让它通过。**
     *    除了 [BlockReason.NOT_GRANTED] —— 那一条用户可以去放行，
     *    但**放行之后仍然要重新裁决**，而不是"记下这个结论然后放行"。
     */
    data class Blocked(
        val reason: BlockReason,
        val userMessage: String,

        /**
         * 用户手动做一遍是否可行。
         *
         * ⚠️ 它只影响**界面怎么引导**，不改变裁决结果。
         *    `true` 时界面应给出"手动去做"或"去放行"的入口，
         *    而不是一个无从下手的失败提示。
         */
        val canFallbackToManual: Boolean,

        /** 参数问题明细。只有 [BlockReason.INVALID_ARGS] 时非空。 */
        val problems: List<ParamProblem> = emptyList(),

        /**
         * 判定发生时**已经规划出来的东西**。判定发生在规划之前时为 null。
         *
         * ⚠️ 它只有一个用途：让审计日志记下「这次想动什么」
         *    （见 [CapabilityAuditEvents.denied] 的 `targetDigest`）。
         *    第 5/6 步的拒绝**明明已经规划过**，不带上它就只能记空串 ——
         *    而「反复请求停用某个系统组件」正是最需要能查出来的那一类。
         *
         * ⚠️ **绝不**为了记日志再调一次 [CommandPlanner.plan]：第 3 步的拒绝
         *    发生在参数非法时，重规划要么抛异常、要么产出一个**没被校验过**
         *    的命令 —— 那正是本模块要避免的那道缝。
         */
        val execution: PlannedExecution? = null,
    ) : CapabilityVerdict

    companion object {
        /**
         * 确认框的等待上限。
         *
         * ⚠️ 60 秒这个数的依据：短于它，用户去倒杯水回来就超时了，
         *    而超时等于拒绝 —— 一次"我明明点了确认"的体验；
         *    长于它，agent 循环会一直挂着，而后台任务被系统回收的风险
         *    随时间上升（这个等待期间不能省电、不能降频）。
         */
        const val DEFAULT_CONFIRM_TIMEOUT_MS = 60_000L
    }
}

/**
 * 拒绝的原因码。
 *
 * ⚠️ 它是**给审计日志用的稳定标识**，不是文案 ——
 *    文案会随版本改，原因码不会。所以审计里记原因码，不记 [CapabilityVerdict.Blocked.userMessage]。
 */
enum class BlockReason {
    /** 目录里没有这个 id。**不是"没实现"，是"从没被允许过"** */
    UNKNOWN_CAPABILITY,

    /** 命中 [CapabilityDenyRules.DENIED_CAPABILITY_PREFIXES] —— 单向阀，永不可覆盖 */
    DENIED_BY_POLICY,

    /** 插件发起了 [CapabilityRisk.GUARDED] 能力 */
    PLUGIN_NOT_ALLOWED_GUARDED,

    /** 参数校验失败。明细见 [CapabilityVerdict.Blocked.problems] */
    INVALID_ARGS,

    /** 要写 [CapabilityDenyRules.DENIED_SETTING_KEYS] 里的设置键 */
    DENIED_SETTING_KEY,

    /** 命令里出现 [CapabilityDenyRules.DENIED_PACKAGES] 里的包名 */
    DENIED_TARGET_PACKAGE,

    /** 用户还没放行这条能力。**这是唯一一个用户操作能改变的原因码** */
    NOT_GRANTED,
}

/**
 * 需要确认的原因码。
 *
 * ⚠️ 它**不是一个 `Boolean`**，这是刻意的 ——
 *    确认文案、超时策略、审计归类都要按原因分叉。
 *
 * ★ 2026-09-23 补上了第二种原因（[OPERATION_AFFECTS_FILES]）。
 *    那次改动**只动了这个枚举与它的两个消费点**，没有波及任何调用方 ——
 *    这正是当初把它做成枚举、而不是布尔值的收益。
 */
enum class ConfirmReason {
    /**
     * 这条能力是 [CapabilityRisk.GUARDED]，且当前不是定时任务上下文。
     *
     * 判据是它**影响用户会持续感知的东西**（连接、其他 App 的运行、外观），
     * 所以每次执行都值得问一次 —— 用户放行的是"你可以做这类事"，
     * 不是"你可以随时做这件事"。
     */
    GUARDED_CAPABILITY,

    /**
     * 这一次具体的操作会动到用户的文件（覆盖 / 删除 / 移动）。
     *
     * ⚠️ 与 [GUARDED_CAPABILITY] **分开是刻意的**：
     *    那个问的是「你允许 agent 做这类事吗」（一次性放行）；
     *    这个问的是「这一次要动的那个文件，你确定吗」（**每次都问**）。
     *
     *    合成一个的后果不是"少问一次"，而是**用户分不清这两个问题** ——
     *    而分不清的下一步就是"看见确认框就点同意"，
     *    那正是确认门彻底失效的方式。
     *
     * 它由 `:filelogic` 的 `FileAccessDecider` 产出，经由
     * [CapabilityRunner] 的 `ChannelResult.NeedsConfirmation` 冒泡上来，
     * 再由 [CapabilityRuntime] 包成 [CapabilityVerdict.RequireConfirmation]。
     *
     * ★★ 界面层**必须**按本字段分叉：答复要回填到
     *    [CapabilityCall.operationConfirmedByUser]（本原因），
     *    而不是 [CapabilityCall.confirmedByUser]（[GUARDED_CAPABILITY] 那个）。
     *
     *    ⚠️ 这里曾经写着"界面层不需要知道有两种确认，它照旧弹框、回填、
     *       重新裁决" —— 而界面层当时只回填了 `confirmedByUser`，
     *       于是这一闸被上一闸的答复**顺带答掉**，永远不弹。
     *       2026-09-23 真机实证。**那句断言本身就是 bug 的一半。**
     */
    OPERATION_AFFECTS_FILES,
}
