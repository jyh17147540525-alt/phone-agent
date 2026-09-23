package com.pocketagent.capabilitylogic

/**
 * 一次能力调用的结果 —— 给界面用的三分支模型。
 *
 * ⚠️ 它与 [CapabilityVerdict] 的区别是「**已经走完了**」还是「**只走到半路**」：
 *    [CapabilityVerdict.RequireConfirmation] 是"停下来等用户"，
 *    而本类型的三个分支都是终点（要么做了、要么没做、要么等用户回答）。
 */
sealed interface CapabilityOutcome {

    /** 被拒绝。**没有任何用户操作能让这一次通过** —— 要改的是调用方或配置。 */
    data class Blocked(val verdict: CapabilityVerdict.Blocked) : CapabilityOutcome

    /**
     * 需要用户确认，**还没有执行**。
     *
     * ⚠️ 调用方拿到它之后必须：展示 [CapabilityVerdict.RequireConfirmation.userMessage]、
     *    等用户回答、把回答写回 `CapabilityCall.confirmedByUser`、
     *    **再调一次** [CapabilityRuntime.execute]。
     *    不能缓存这个结论然后直接执行 —— 见 [CapabilityVerdict.RequireConfirmation]。
     */
    data class NeedsConfirmation(
        val verdict: CapabilityVerdict.RequireConfirmation,
    ) : CapabilityOutcome

    /** 走完了（成功或失败都在 [result] 里）。 */
    data class Executed(
        val verdict: CapabilityVerdict.Allowed,
        val result: ChannelResult,
    ) : CapabilityOutcome
}

/**
 * 第 0 档能力的运行时 —— 把「裁决 → 执行 → 记账」串成一条链。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它在**纯模块**里，而不是 Android 侧
 * ═══════════════════════════════════════════════════════════════
 *
 * 它一行 Android 代码都没有：三个依赖（裁决器、通道、审计日志）全是本模块
 * 定义的**纯逻辑对象**，系统交互已经被 [SettingsAccess] / [ShellRunner] 两个端口
 * 挡在外面了。所以它能进离线验证器 —— 而"三个组件**串起来的顺序**"恰恰是
 * 最该被测试钉住的东西（单看每一个都是对的，连错顺序也不报错）。
 *
 * ⚠️ 顺序：**裁决 → 执行 → 记账**。把记账提到执行之前，日志会写"执行了"
 *    而实际没执行；把裁决挪到执行之后，那就是先做再判。
 *    两者都不会让任何一条"正向测试"变红。
 */
class CapabilityRuntime(
    private val guard: CapabilityGuard,
    private val runner: CapabilityRunner,
    private val auditLog: CapabilityAuditLog,

    /** 取当前时间。注入是为了让测试能断言时间戳，不是为了"支持别的时间源"。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 跑一次能力调用。
     *
     * ⚠️ 本方法**不抛**业务异常：所有结论都在 [CapabilityOutcome] 里。
     *    唯一会穿出去的是 [kotlinx.coroutines.CancellationException] —— 那是取消，
     *    不是失败，必须原样抛（见 [CapabilityRunner.readPreviousValue] 的注释）。
     */
    suspend fun execute(call: CapabilityCall): CapabilityOutcome =
        when (val verdict = guard.decide(call)) {

            is CapabilityVerdict.Blocked -> {
                auditLog.record(
                    CapabilityAuditEvents.denied(
                        timestamp = clock(),
                        capabilityId = call.capabilityId,
                        origin = call.origin,
                        reason = verdict.reason,
                        // ⚠️ 第 5/6 步的拒绝已经规划过了，带上它才有 targetDigest。
                        //    第 0~3 步的拒绝这里是 null —— 那是对的，不是漏了。
                        execution = verdict.execution,
                    )
                )
                CapabilityOutcome.Blocked(verdict)
            }

            // ⚠️ 这里**不记账**。日志里没有"请求确认"这个事件，
            //    只有 `confirmationResolved`（用户答了 / 超时了）。
            //    理由：一条"问了但没人答"的记录没有任何可操作信息，
            //    而它会在用户每次打开确认框时占掉一格缓冲。
            //    代价是：应用在等待期间被杀，日志里看不到痕迹 —— 这是已知的取舍。
            is CapabilityVerdict.RequireConfirmation -> CapabilityOutcome.NeedsConfirmation(verdict)

            is CapabilityVerdict.Allowed -> {
                // ★★ 传下去的是 `operationConfirmedByUser`，**不是** `confirmedByUser`。
                //
                //    ⚠️ 这一处曾经传错，而它是本轮最难发现的一个 bug：
                //       `confirmedByUser` 答的是"你允许这个能力执行吗"（能力闸），
                //       而文件裁决问的是"这个文件会被覆盖，你确定吗"（文件闸）。
                //       把前者传下去 ⇒ 用户点掉能力闸之后，文件闸**静默通过**，
                //       于是"原内容会被整体替换掉"这句话从来没机会显示。
                //
                //    ⚠️ 反过来"忘了传"也不行 —— 那会让文件闸**每次都重新问**，
                //       现象是"点了确定没反应"（因为下一轮又回到同一个确认）。
                //       两个方向都会坏，只是坏法不同：一个是问了没问成，
                //       一个是**没问就做了**。
                val result = runner.run(verdict.execution, call.operationConfirmedByUser)

                // ★ 注意这里的形状：**每个分支各自产出结论**，没有"落到最后一行"
                //    的隐含路径。
                //
                //    ⚠️ 这一点是刻意的，不是风格问题。`execute()` 是表达式函数体
                //    （`= when (...)`），本来就写不出 `return` —— 而编译器把这个限制
                //    变成了好事：它逼着每个分支自己表态，于是**不可能**出现
                //    "新加的分支忘了处理、静默掉进默认结局"这种情况。
                //
                //    如果当初写成"先 `when` 记日志、再统一返回 Executed"，
                //    那么 NeedsConfirmation 这个分支就会**静默变成"执行成功"** ——
                //    界面看到"走完了"，既不弹确认框也不重试，用户看到的是
                //    "点了删除，什么都没发生"，而日志里连一条记录都没有
                //    （RequireConfirmation 不记账）。
                when (result) {
                    is ChannelResult.Succeeded -> {
                        auditLog.record(
                            CapabilityAuditEvents.executed(
                                timestamp = clock(),
                                capabilityId = verdict.capability.id,
                                origin = call.origin,
                                execution = verdict.execution,
                                wasConfirmed = verdict.wasConfirmed,
                            )
                        )
                        CapabilityOutcome.Executed(verdict, result)
                    }

                    is ChannelResult.Unavailable,
                    is ChannelResult.Failed,
                    -> {
                        recordChannelFailure(call, verdict, result)
                        CapabilityOutcome.Executed(verdict, result)
                    }

                    // ★ 这一次**没有执行** —— 文件裁决说"要用户先确认"。
                    //
                    // ⚠️ 包成 [CapabilityVerdict.RequireConfirmation] 而不是新的
                    //    顶层结局，是为了让界面层**照旧弹框、重新走一遍 `execute()`** ——
                    //    它不需要知道底下有几套执行通道。
                    //
                    // ★★ 但界面层**必须知道这是哪一闸**，因为两闸的答复要回填到
                    //    两个**不同**的字段（见 [ConfirmReason]）。判据就是这里给的
                    //    `reason`：`OPERATION_AFFECTS_FILES` ⇒ 回填
                    //    `operationConfirmedByUser`；否则 ⇒ 回填 `confirmedByUser`。
                    //
                    //    ⚠️ 这段注释曾经写着"界面层不需要知道有两种确认"，而界面层
                    //       当时确实只回填了 `confirmedByUser` —— 于是文件闸被顺带
                    //       答掉。**那句话本身就是 bug 的一部分。**
                    is ChannelResult.NeedsConfirmation -> CapabilityOutcome.NeedsConfirmation(
                        CapabilityVerdict.RequireConfirmation(
                            capability = verdict.capability,
                            execution = verdict.execution,
                            reason = ConfirmReason.OPERATION_AFFECTS_FILES,
                            userMessage = result.userMessage,
                            timeoutMs = result.timeoutMs,
                        ),
                    )
                }
            }
        }

    /**
     * 记一条通道失败 —— **连续同因只记第一条**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么合并放在这里，而不是 [CapabilityAuditLog] 里
     * ═══════════════════════════════════════════════════════════════
     *
     * [CapabilityAuditEvents.channelFailed] 的注释写着这条纪律，并且明确说
     * "调用方注意" —— 因为日志类**不该知道"什么算重复"**：对它来说，
     * 两条事件就是两条事件。而"同一件事重复发生"这个概念只有编排层有。
     *
     * 不合并的后果是具体的：`WRITE_SECURE_SETTINGS` 没给的时候，
     * **每一次**设置类调用都产生一条 [ChannelResult.Unavailable]。
     * 用户开了个连续做 20 步的任务 → 20 条一模一样的记录 →
     * 500 格的环形缓冲里剩下的全被同一句话填满，真正的 DENIED 被挤出去。
     * ⇒ 那份日志是用户判断"这个插件想干什么"的唯一线索，
     *   而被填满的日志**看起来和"什么都没发生"一样**。
     *
     * ⚠️ 只在**完全相同**（同能力 + 同原因文案）时合并。不同能力即使原因相同
     *    也不合并 —— 它们的 `targetDigest` 不同，"想动哪个目标"是那份日志
     *    最主要的用途，不能为了省格子把它丢掉。
     *
     * ⚠️ 也**不**对拒绝做合并：反复出现的 DENIED 正是最该看到的信号。
     */
    private fun recordChannelFailure(
        call: CapabilityCall,
        verdict: CapabilityVerdict.Allowed,
        result: ChannelResult,
    ) {
        val event = CapabilityAuditEvents.channelFailed(
            timestamp = clock(),
            capabilityId = verdict.capability.id,
            origin = call.origin,
            execution = verdict.execution,
            result = result,
        )

        val last = auditLog.events().lastOrNull()
        val isRepeat = last != null &&
            last.outcome == CapabilityAuditOutcome.FAILED &&
            last.capabilityId == event.capabilityId &&
            last.detail == event.detail

        if (!isRepeat) auditLog.record(event)
    }
}
