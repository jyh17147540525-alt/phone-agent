package com.pocketagent.provider.gateway.dsh

import com.pocketagent.provider.gateway.GatewayEndpoint

/**
 * 一次「把网关接进 dsh」的完整会话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它把三件已经各自完备、但一直没人连起来的事串上
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   GatewayEndpoint   →  起一个 loopback 网关，拿到 (baseUrl, token)
 *   DshConfigPatch    →  把 (baseUrl, token) 渲染成 dsh 能读的 YAML
 *   DshConfigSink     →  读回既有配置（保住用户的东西）→ 合并 → 写回
 * ```
 *
 * 本项目已经吃过一次「两段各自完备、中间无人」的亏（`ModelConfigDao` 与
 * `ModelRouter` 之间长期没有仓储层 → 调度器写得很完整，线上永远只有一个模型）。
 * 所以这一层必须显式存在，而不是让 App 层临场拼。
 *
 * ⚠️ **它刻意不认识 Android**：网关走 [GatewayEndpoint] 窄接口，
 *    落盘走 [DshConfigSink] 窄接口。于是"起网关 → 渲染 → 合并 → 投递"
 *    整条链路都能进 `run_logic_tests.py` 离线验证 —— 而这条链路里
 *    每一步错的后果都是静默的（多一个重复段、少一个凭据、token 没换）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ `start()` 是**幂等**的，这不是巧合
 * ═══════════════════════════════════════════════════════════════
 *
 * 三件事各自幂等，串起来才幂等：
 * - [GatewayEndpoint.start] 已启动时返回 `true` 且**不重新分配端口与 token**
 * - [DshConfigPatch.mergeIntoSettingsYaml] 同一份 patch 合并两次结果相同
 * - [DshConfigPatch.mergeIntoCredentialsYaml] 按**解析出的键名**替换那一行
 *   （踩过一次坑：按字符串前缀匹配永远匹配不上 → 每次合并追加一条重复键，
 *   见那个方法的注释）
 *
 * 幂等很重要：用户多点一次按钮、或应用重启后重新进入页面，
 * 都不该让配置文件长出第二份 `llm-deepseek:` 段。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 结果刻意分成两层，不合并
 * ═══════════════════════════════════════════════════════════════
 *
 * 「网关起来了」与「配置送达了」是**两件独立的事实**：
 * 前者失败 → 用户的重试动作是"检查端口 / 重启应用"；
 * 后者失败 → 用户的动作是"去看那个文件、手工补一行"。
 * 合成一个 `Boolean` 会让其中一种情况**说不出话**。
 */
class DshGatewaySession(
    private val endpoint: GatewayEndpoint,
    private val sink: DshConfigSink,

    /**
     * 日志出口。
     *
     * ⚠️ **实现方绝不能把 token 传进来** —— 见 [start] 里的调用点。
     *    刻意不用 Timber（它不在离线验证器的 classpath 里）。
     */
    private val log: (String) -> Unit = {},
) {

    /**
     * 启动网关，并把配置渲染好投递出去。
     *
     * @return 见 [DshGatewayStartResult]。**不抛异常** —— 失败是业务状态。
     */
    fun start(): DshGatewayStartResult {
        if (!endpoint.start()) {
            return DshGatewayStartResult.NotStarted(
                "本机网关没能在 127.0.0.1 上启动。可能是端口被占用、或系统限制了" +
                    "本地监听。重启应用后重试；若一直失败，请把这条信息反馈给我们。"
            )
        }

        val patch = DshConfigPatch(
            baseUrl = endpoint.baseUrl,
            token = endpoint.token,
        )

        // ⚠️ 读回既有内容再合并 —— 不能整文件覆盖。
        //    真实落点里用户手写过 `# ---- user patch layer ----` 这类注释，
        //    而 dsh 升级还会带来我们不认识的新键；覆盖会把它们全删掉。
        //    `?: ""` 是"文件不存在"的情形：对 settings.yaml 而言，
        //    凭空造一份是安全的（我们的段是唯一内容）。
        val existingSettings = sink.read(sink.settingsPath) ?: ""
        val settingsYaml = patch.mergeIntoSettingsYaml(existingSettings)

        // ⚠️ `null` 是"文件**不存在**"，`""` 是"文件存在但是空的" —— 两者不能合并。
        //    合并成同一个值，会让"文件不存在"被当成"文件是空的"，
        //    于是我们会对一个不存在的文件做读-改-写，而结果无人察觉。
        val credentialsYaml = when (val existing = sink.read(sink.credentialsPath)) {
            // 文件不存在：能不能新建由落点自己决定 —— 写 dsh **真实**目录的
            // sink 必须禁止（凭空造一份可能让 dsh 整个读不动配置），
            // 写我们自己的草稿区的 sink 允许（那里没有东西可毁）。
            null -> if (sink.mayCreateCredentialsFile) patch.credentialsDocument() else null

            // 文件在：只做"逐行手术"（保住用户的真 Key 与注释）。
            // 结构不认识时它会返回 null —— 那是"放弃并如实报告"，不是"我来补一个"。
            else -> patch.mergeIntoCredentialsYaml(existing)
        }

        val delivery = sink.write(settingsYaml, credentialsYaml)

        // ⚠️ **只打结果类型，不打内容，更不打 token**。
        //    日志会被应用内日志页收集，token 进了日志就等于泄露给任何
        //    能读日志的东西 —— 包括我们要防的同机其它 App 的辅助功能服务。
        log("dsh 配置投递结果：${delivery::class.simpleName}")

        return DshGatewayStartResult.Running(
            baseUrl = endpoint.baseUrl,
            delivery = delivery.withActionableReason(),
        )
    }

    /**
     * 把落点给出的 [DshWriteResult.Partial] 理由补全到"用户能照着做"的程度。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 为什么补在这一层，而不是要求每个落点自己写好
     * ═══════════════════════════════════════════════════════════
     *
     * 落点会有**多个**实现（应用私有目录、将来经 Shizuku 写 Termux 私有目录、
     * 让用户手工粘贴），每个实现都自己组织措辞，就有 N 次漏掉变量名的机会。
     * 而"凭据文件里缺的是**哪一行**"是用户唯一真正需要知道的信息 ——
     * 只说"凭据写入失败"等于没说，用户只能来问我们。
     *
     * 这条链路里错的后果是**静默**的（配置写好了、凭据没写 → dsh 报
     * `MISSING_CREDENTIAL` → 用户去查"是不是 token 过期了"，方向全错），
     * 所以把不变量放在**唯一粘合点**上，比寄希望于每个实现都记得更可靠。
     *
     * 幂等：理由里已经点到变量名就不重复追加（真实落点
     * `AppPrivateDshConfigSink` 是带变量名的，这里不能给它加第二遍）。
     */
    private fun DshWriteResult.withActionableReason(): DshWriteResult {
        if (this !is DshWriteResult.Partial) return this
        val ref = DshConfigPatch.DEFAULT_CREDENTIAL_REF
        if (reason.contains(ref)) return this
        return DshWriteResult.Partial(
            reason = "$reason 需要在 dsh 凭据文件的 `refs:` 段下补一行：`$ref: <本机网关 token>`。",
        )
    }

    /**
     * 停掉网关。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 刻意**不去清理已投递的配置**，这是一条要记住的取舍
     * ═══════════════════════════════════════════════════════════
     *
     * 停下之后那份配置还指着 `127.0.0.1:<port>`，而端口已经释放 ——
     * 于是存在一个（很窄的）窗口：**别的本地服务拿到同一个端口**，
     * 而 dsh 会把带旧 token 的请求发过去。
     *
     * 不清理的理由：
     * 1. 清理需要对 dsh 真实目录的写权限，而那个权限**正是本任务还没验证的东西**
     * 2. 即使能清，把配置删掉会让 dsh 进入"完全没配过"的状态 ——
     *    比"配了但连不上"更难解释（用户会以为我们把他原来的配置弄丢了）
     *
     * 所以正确的用法是：**把网关的生命周期挂在"用户启用 dsh 集成"上，
     * 而不是挂在一个临时开关上**。停止是给"用户主动关掉"用的，
     * 不是一个频繁动作。
     */
    fun stop() {
        endpoint.stop()
        log("dsh 网关已停止（已投递的配置保留不动）")
    }
}

/**
 * [DshGatewaySession.start] 的结果。
 *
 * ⚠️ 两层分开的理由见会话类注释：**"网关没起来"与"配置没送达"
 *    需要用户做不同的事**，合成一个布尔值会让其中一种说不出话。
 */
sealed interface DshGatewayStartResult {

    /**
     * 网关**在跑**。
     *
     * ⚠️ 注意它**不代表配置送达成功** —— 那要看 [delivery]。
     *    网关起来但配置写不进去是完全可能的状态（存储满、权限不足），
     *    而它必须能被如实报告。
     */
    data class Running(
        val baseUrl: String,
        val delivery: DshWriteResult,
    ) : DshGatewayStartResult

    /**
     * 网关**没起来**。此时什么都没投递 —— 不能把一份指向不存在端口的
     * 配置写出去，那会让 dsh 每次请求都失败，而用户会以为是我们改坏了他的配置。
     */
    data class NotStarted(val reason: String) : DshGatewayStartResult
}
