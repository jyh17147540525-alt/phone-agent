package com.pocketagent.capabilitylogic

/**
 * 一次能力调用对用户说的那句话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这段映射在纯模块里，而不在界面里
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为它是**判定结果 → 用户看到的那句话**的唯一映射，而这里最容易出的错
 * 全都**不会报错**：
 *
 * | 写错的形态 | 用户看到 | 他接下来会做什么 |
 * |---|---|---|
 * | 把 [ChannelResult.Unavailable] 与 [ChannelResult.Failed] 合成一句"操作失败" | 「操作失败」 | 反复重试 —— 而它要的其实是一条 adb 命令 |
 * | 把 [BlockReason.NOT_GRANTED] 也标成红色失败 | 「执行失败」 | 以为功能坏了 —— 其实只是还没放行 |
 * | [ChannelResult.Succeeded] 也说"已完成"而不区分"通道接受了" | 「已完成」 | 相信设置真的变了 —— 而系统有可能静默忽略 |
 *
 * ⇒ 这三种错法在界面上都"看起来正常"。所以这段文字必须在能被离线测试
 *   钉住的地方，而不是散在 Compose 的 `when` 分支里。
 *
 * ⚠️ 颜色不在本模块 —— [CapabilityOutcomeTone] 只是**语义**，
 *    "Info 用哪种蓝"留在 `ui/design`。这与 `PermissionAction` 是同一个手法：
 *    纯模块给枚举，Android 侧给 Intent。
 */
enum class CapabilityOutcomeTone {
    /** 做成了 */
    Success,

    /** 没做成，但**不是失败** —— 要么等用户决定，要么等用户去配置 */
    Info,

    /** 通道可用，这次没成。要重试或换参数 */
    Warning,

    /** 固定不允许 —— 用户做什么都没用 */
    Danger,
}

object CapabilityMessages {

    fun toneOf(outcome: CapabilityOutcome): CapabilityOutcomeTone = when (outcome) {
        is CapabilityOutcome.Executed -> when (outcome.result) {
            is ChannelResult.Succeeded -> CapabilityOutcomeTone.Success
            // ⚠️ 不是 Danger：Danger 的语义是"用户做什么都没用"，
            //    而这两种恰恰**都要用户去做点什么**（去授权 / 去改参数）。
            is ChannelResult.Unavailable,
            is ChannelResult.Failed,
            -> CapabilityOutcomeTone.Warning

            // ⚠️ 它**不可能**出现在 `Executed` 里 —— 见 impossibleInExecuted()。
            is ChannelResult.NeedsConfirmation -> impossibleInExecuted()
        }

        // 等用户点确认 —— 中性。
        is CapabilityOutcome.NeedsConfirmation -> CapabilityOutcomeTone.Info

        is CapabilityOutcome.Blocked -> when (outcome.verdict.reason) {
            // ★ 本文件最要紧的一行。NOT_GRANTED **不是拒绝**，
            //   是"还没配置" —— 给红色会让用户以为功能坏了，
            //   而他要做的只是在界面上点一下「放行」。
            BlockReason.NOT_GRANTED -> CapabilityOutcomeTone.Info

            // 其余都是固定不允许（命中硬拒绝清单、参数非法、插件越界）——
            // 用户做什么都改不了，这才是 Danger。
            else -> CapabilityOutcomeTone.Danger
        }
    }

    /**
     * 标题。**一行以内**，而且必须让用户一眼分清三种"没做成"。
     */
    fun titleOf(outcome: CapabilityOutcome): String = when (outcome) {
        is CapabilityOutcome.Executed -> when (outcome.result) {
            is ChannelResult.Succeeded -> "已经做完了"
            // ⚠️ "还差一步配置"而不是"执行失败" —— 后者会让用户反复重试，
            //    而这条要的是去跑一条命令，重试一百次也不会变。
            is ChannelResult.Unavailable -> "还差一步配置"
            is ChannelResult.Failed -> "试过了，没成"

            // ⚠️ 尤其不能在这里静默 —— 落到上面任何一支都会说出**假话**
            //    （"已经做完了" / "还差一步配置"），而它其实什么都没执行。
            is ChannelResult.NeedsConfirmation -> impossibleInExecuted()
        }

        is CapabilityOutcome.NeedsConfirmation -> "需要你确认"

        is CapabilityOutcome.Blocked -> when (outcome.verdict.reason) {
            BlockReason.NOT_GRANTED -> "还没有放行这一条"
            BlockReason.UNKNOWN_CAPABILITY -> "不认识这个操作"
            else -> "没有执行"
        }
    }

    /**
     * 正文。
     *
     * ⚠️ 一律取**判定层给出的那句话**，不在这里另写 —— 那些文案里有
     *    具体原因（哪个参数不合法、要跑哪条命令），重写一遍必然丢失信息。
     */
    fun detailOf(outcome: CapabilityOutcome): String = when (outcome) {
        is CapabilityOutcome.Executed -> when (val result = outcome.result) {
            is ChannelResult.Succeeded -> {
                val payload = result.payload
                when {
                    // ★★ 读回来的东西（文件内容、目录列表）**必须真的显示出来**。
                    //
                    //    `payload` 这个字段加了、`CapabilityRunner` 也填了，
                    //    但这里不消费它 —— 于是 2026-09-23 在真机上看到的
                    //    `file.read` 是「已经做完了 / 通道接受了这次操作」，
                    //    读到的内容一个字都没有。用户会以为这个能力什么都没做。
                    //
                    //    ⚠️ 这不是"显示得不好看"，是**这个能力对用户等于不存在**：
                    //       读文件的价值 100% 在内容上，不在"操作成功"这四个字上。
                    payload != null -> preview(payload)

                    // ⚠️ **不能**说"设置已经改了"。通道只保证"它接受了这次写入"，
                    //    系统仍可能静默忽略（写进去了、没报错、值没变）。
                    //    回读校验是下一步的事 —— 在那之前说"已完成"就是一句假话。
                    result.previousValue != null ->
                        "通道接受了这次操作。（原来的值已经记下，可以改回来。）"

                    else -> "通道接受了这次操作。"
                }
            }

            // ★ 这一支里装的就是**那条 adb 命令**（见 SettingsPermissionGuide）。
            is ChannelResult.Unavailable ->
                result.detail.ifBlank { result.reason.displayName }

            is ChannelResult.Failed ->
                result.reason + if (result.exitCode != null) {
                    "（退出码 ${result.exitCode}）"
                } else {
                    ""
                }

            is ChannelResult.NeedsConfirmation -> impossibleInExecuted()
        }

        is CapabilityOutcome.NeedsConfirmation -> outcome.verdict.userMessage
        is CapabilityOutcome.Blocked -> outcome.verdict.userMessage
    }

    /**
     * 用户现在**能做什么**。
     *
     * ⚠️ 三态而不是两态：`null`（无事可做）与"有个按钮"是两件事，
     *    而"按钮点了能解决"与"按钮点了只是引导"又是两件事。
     *    折成 `Boolean` 之后，界面上会出现点了没用的按钮。
     */
    fun nextStepOf(outcome: CapabilityOutcome): NextStep = when (outcome) {
        is CapabilityOutcome.NeedsConfirmation -> NextStep.CONFIRM

        is CapabilityOutcome.Blocked -> if (outcome.verdict.canFallbackToManual) {
            NextStep.GRANT
        } else {
            NextStep.NONE
        }

        is CapabilityOutcome.Executed -> when (outcome.result) {
            is ChannelResult.Succeeded -> NextStep.NONE
            // 去配置（跑那条命令 / 开权限）—— 与"重试"不是同一件事
            is ChannelResult.Unavailable -> NextStep.GO_CONFIGURE
            is ChannelResult.Failed -> NextStep.RETRY

            // ⚠️ 也不能静默成 NONE —— 那会让界面**不给任何按钮**，
            //    而用户此刻唯一该做的事就是去回答那个确认框。
            is ChannelResult.NeedsConfirmation -> impossibleInExecuted()
        }
    }

    /**
     * `CapabilityOutcome.Executed` 里**不可能**装 [ChannelResult.NeedsConfirmation]。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 为什么是"当场抛"而不是"兜一句文案"
     * ═══════════════════════════════════════════════════════════════
     *
     * `NeedsConfirmation` 的语义是「这一次**没有执行**，在等用户回答」——
     * 它有自己的顶层结局 [CapabilityOutcome.NeedsConfirmation]，
     * 由 [CapabilityRuntime] 显式产出。而 `Executed` 的语义是"跑过了"。
     *
     * 两者混在一起的后果**全都是安静的**，四个消费点各错一种：
     *
     * | 消费点 | 兜一句文案会得到 | 用户看到 |
     * |---|---|---|
     * | [toneOf] | 随便落进 Success/Warning 之一 | 一个不对的色调 |
     * | [titleOf] | 「已经做完了」 | **假话** —— 什么都没执行 |
     * | [detailOf] | 一条不存在的"结果" | 同上 |
     * | [nextStepOf] | 大概率 `NONE` | **不给按钮**，而他要做的就是回答确认框 |
     *
     * ⇒ 这正是本项目反复出现的那类"不报错、不崩溃，只是安静地少做一件事"
     *   的 bug。宁可炸在测试里，也不要对用户说一句假话。
     *
     * ⚠️ 四处的调用是**编译器强制**的（`when` 穷尽性），不是靠自觉 ——
     *    所以不可能出现"改了三处、漏了一处"。
     */
    private fun impossibleInExecuted(): Nothing = error(
        "CapabilityOutcome.Executed 里不该出现 NeedsConfirmation —— " +
            "它必须走 CapabilityOutcome.NeedsConfirmation 这条顶层分支，" +
            "否则界面会把它显示成「已经做完了」，而它其实什么都没执行。",
    )

    /**
     * 通道读回来的内容**能显示多长**。
     *
     * ⚠️ 必须有上限，而且上限要在这里（纯逻辑、可离线测）而不是在 Compose 里。
     *    通道一次能读的字节数上限是几万字节量级（见 `SafFileChannel`），
     *    把那个量级的字符串整段交给 `Text` 去排版，界面会卡住 ——
     *    而"卡住"比"截断"难排查得多：它看起来像设备慢。
     *
     * 1200 个字符 ≈ 手机上二十来行正文，够用户判断"这是不是我想要的那个文件"。
     */
    private const val PREVIEW_LIMIT = 1200

    /**
     * 把读回来的内容截成一段**能安全渲染**的预览。
     *
     * ⚠️ 不能直接在 [PREVIEW_LIMIT] 处 `take` —— 一个 emoji 在 Kotlin 字符串里
     *    是**两个** `Char`（代理对）。正好切在中间会留下一个孤立的 high
     *    surrogate，渲染出来是一个 `�`。
     *
     *    危害不在于难看，在于**极难被报上来**：用户看到"一大段正常文本里
     *    有一个乱码字符"，会以为是原文件本来就有问题，而不会想到是界面截断
     *    切坏了。所以宁可少显示一个字符。
     */
    private fun preview(text: String): String {
        if (text.length <= PREVIEW_LIMIT) return text

        // 若第 PREVIEW_LIMIT 个字符的前一个位置是 high surrogate，
        // 说明下一个位置是它的 low surrogate、正好要被切掉 → 少取一个。
        val end = if (text[PREVIEW_LIMIT - 1].isHighSurrogate()) {
            PREVIEW_LIMIT - 1
        } else {
            PREVIEW_LIMIT
        }

        return text.take(end) + "\n……（内容太长，这里只显示了前面 $end 个字符）"
    }
}

/**
 * 下一步。
 *
 * ⚠️ [RETRY] 与 [GO_CONFIGURE] 必须分开：合成一个"重试"按钮的后果是
 *    用户在权限没给的情况下反复点 —— 而每次都会得到同一句话。
 */
enum class NextStep {
    /** 什么都不用做（做成了，或者用户插不上手） */
    NONE,

    /** 等用户点「执行」 */
    CONFIRM,

    /** 等用户在界面上放行这一条能力 */
    GRANT,

    /** 通道没准备好 —— 去配置（跑命令 / 开权限 / 启动 Shizuku） */
    GO_CONFIGURE,

    /** 通道可用但这次没成 —— 可以原样再试一次 */
    RETRY,
}
