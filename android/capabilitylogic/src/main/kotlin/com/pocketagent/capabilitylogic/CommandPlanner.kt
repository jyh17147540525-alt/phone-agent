package com.pocketagent.capabilitylogic

/**
 * 规划器 —— 把「一条已校验的能力调用」展开成「一次具体的执行」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它是一个**纯函数**，而且刻意没有任何判定
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里最容易走偏的写法，是在展开的过程中顺手做点"防御性检查"：
 *
 * ```kotlin
 * if (argv.first() != "cmd") throw ...        // ✗ 判定跑到了两处
 * ```
 *
 * 那样一来，"什么允许、什么不允许"就同时存在于 [CapabilityGuard] 和这里，
 * 而两份规则迟早会漂移 —— 漂移的方向永远是**松的那一份生效**，
 * 因为调用方会走到它先通过的那条路上。
 *
 * 所以本类的职责边界是一条直线：
 *
 * ```
 * 调用方保证（由 CapabilityGuard 保证）：
 *   参数完整、取值合法、能力未被硬拒绝、目标未被硬拒绝
 * 本类保证：
 *   产出 [PlannedExecution]，且 argv 的每一项都来自"字面量或已校验的参数"
 * ```
 *
 * ⚠️ 所以本类的 `require` **不是**安全判定，是**契约断言**：
 *    它们触发时说明 [CapabilityGuard] 被绕过了（有人直接调了 `plan`），
 *    那是代码 bug，不是用户输入问题。因此它们抛异常、且消息面向开发者。
 *    —— 这与 `CapabilityParams` 的注释里"宁可严到误伤"是不同性质的严格。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"能力怎么执行"要写成配方，而不是这里的 `when`
 * ═══════════════════════════════════════════════════════════════
 *
 * 见 [ExecutionRecipe] 的类注释（那里有完整论证）。一句话版：
 * 配方放在 [Capability] 里，[Capability] 的 `init` 就能在**加载时**
 * 检查"声明与实现是否一致"；写成这里的 `when` 分支，一致性只能在
 * **被调用的那一刻**才发现，而且"这条能力能做什么"被拆到两个文件里，
 * 安全审查最怕的正是看不全。
 */
object CommandPlanner {

    /**
     * 展开一次执行。
     *
     * @param capability 目录里的能力声明
     * @param args 参数。**必须完整**（含全部被配方引用的参数）
     * @throws IllegalArgumentException 参数不完整 —— 见类注释，这是契约违约
     */
    fun plan(capability: Capability, args: Map<String, String>): PlannedExecution =
        when (val recipe = capability.recipe) {

            is ExecutionRecipe.Shell -> PlannedExecution.ShellArgv(
                argv = recipe.segments.map { segment ->
                    when (segment) {
                        is ExecutionRecipe.Shell.Segment.Literal -> segment.text

                        is ExecutionRecipe.Shell.Segment.Param -> requireParam(
                            capability = capability,
                            args = args,
                            name = segment.name,
                        )
                    }
                },
                summary = shellSummary(capability, recipe, args),
            )

            is ExecutionRecipe.Setting -> PlannedExecution.SettingWrite(
                namespace = recipe.namespace,
                key = recipe.key,
                value = requireParam(
                    capability = capability,
                    args = args,
                    name = recipe.valueParam,
                ),
                summary = settingSummary(recipe, requireParam(capability, args, recipe.valueParam)),
            )

            is ExecutionRecipe.File -> PlannedExecution.FileIntent(
                op = recipe.op,
                path = requireParam(capability, args, recipe.pathParam),
                content = recipe.contentParam?.let { requireParam(capability, args, it) },
                destinationPath = recipe.destinationParam?.let {
                    requireParam(capability, args, it)
                },
                summary = fileSummary(recipe, args),
            )
        }

    /**
     * 取参数，取不到就违约。
     *
     * ⚠️ 不用 `args.getValue(name)` —— 它抛 `NoSuchElementException`，
     *    而"参数缺失"是一个**可预期**的契约违约，应该抛
     *    `IllegalArgumentException` 并带上能力 id。
     *    用 `getValue` 的后果是错误信息只有 `Key xxx`，
     *    排查时还得反查是哪条能力、哪次调用。
     */
    private fun requireParam(capability: Capability, args: Map<String, String>, name: String): String {
        // ⚠️ 不用 `args[name] ?: throw`：那会把"值为空字符串"和"没有这个键"
        //    混成一种情况。虽然 [CapabilityParams] 已经拒绝了空值，
        //    但契约断言应当把两者分开 —— 否则将来参数规则放松时会静默出错。
        require(args.containsKey(name)) {
            "能力「${capability.id}」的配方引用了参数「$name」，但调用时没有传。" +
                "plan() 只接受已经过 CapabilityGuard 校验的调用。"
        }
        return args.getValue(name)
    }

    /**
     * shell 命令的展示文案。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 它**故意是具体的**，而不是一句漂亮话
     * ═══════════════════════════════════════════════════════════
     *
     * [Capability.summary] 是静态的（"开关 Wi-Fi。关掉之后手机会断开无线网络……"），
     * 它回答"这件事是什么"。而用户在确认框里真正要确认的，是
     * **这一次的参数**。两者缺一不可：
     *
     * ```
     * 要执行「开关 Wi-Fi。关掉之后手机会断开无线网络，可能收不到消息。」
     * 这一次具体是：cmd wifi set-wifi-enabled disabled
     * ```
     *
     * ⚠️ 所以这里**会包含参数原值**（`notification.post` 的通知正文就在其中）。
     *    这是刻意的 —— 用户要确认的正是"我要发出去的这句话"。
     *    **但这意味着 [PlannedExecution.summary] 绝不能进审计日志**
     *    （见 `CapabilityAuditEvents` 里用 `targetDigest` 而不是 `summary`）。
     *
     * ⚠️ 另外：这个字符串是**给人看的**，不是给 shell 用的。
     *    实现方若把它当命令执行，`ExecutionRecipe.Shell` 关于
     *    "argv 数组不经过 shell"的整个论证就作废了。
     */
    private fun shellSummary(
        capability: Capability,
        recipe: ExecutionRecipe.Shell,
        args: Map<String, String>,
    ): String = recipe.segments.joinToString(separator = " ") { segment ->
        when (segment) {
            is ExecutionRecipe.Shell.Segment.Literal -> segment.text
            is ExecutionRecipe.Shell.Segment.Param -> args[segment.name] ?: "{${segment.name}}"
        }
    }.ifBlank { capability.id }

    /**
     * 设置写入的展示文案。
     *
     * ⚠️ 用的是 `命名空间/键 = 值` 这种**能对照**的形式，而不是
     *    "把亮度设为 120"。后者好看，但用户在"为什么我的亮度变了"
     *    这类事后排查里，需要的是能直接和系统设置对上号的写法。
     */
    private fun settingSummary(recipe: ExecutionRecipe.Setting, value: String): String =
        "${recipe.namespace.wireName}/${recipe.key} = $value"

    /**
     * 文件操作的展示文案。
     *
     * ⚠️ 格式是 `读取 /sdcard/Documents/报告.md` 这种「动作 + 路径」，
     *    而不是 `read(/sdcard/...)`。理由同 [settingSummary]：
     *    用户在确认框里要读懂的是"它要动**哪个文件**"。
     *
     * ⚠️ 写入的内容**不进文案**，只报字符数。设置与 shell 的文案可以带
     *    参数原值（用户要确认的正是那句话），但文件内容可能是一整篇文档 ——
     *    塞进确认框会淹没"要动哪个文件"这个关键信息。
     */
    private fun fileSummary(recipe: ExecutionRecipe.File, args: Map<String, String>): String {
        val path = args[recipe.pathParam] ?: "{${recipe.pathParam}}"
        val head = "${recipe.op.userLabel} $path"
        val destination = recipe.destinationParam?.let { args[it] }
        val contentLength = recipe.contentParam?.let { args[it] }?.length

        return when {
            destination != null -> "$head → $destination"
            contentLength != null -> "$head（$contentLength 字）"
            else -> head
        }
    }
}

