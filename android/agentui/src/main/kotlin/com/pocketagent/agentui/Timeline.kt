package com.pocketagent.agentui

/**
 * 一次任务的时间线 —— 界面上的全部内容都在这里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是一个不可变的值 + 一个纯函数
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为"回放"必须是**真的**。用户事后想弄清楚"它刚才到底干了什么"时，
 * 我们唯一能提供的证据就是这条时间线；如果它只能由运行中的 UI 状态拼出来，
 * 那它就不是证据，而是回忆。
 *
 * ⇒ 所有变更只能经 [reduce]；[reduce] 不读时钟、不读磁盘、不碰 Android。
 *   给定同一串事件，结果逐字段相同（有测试钉住）。
 */
data class Timeline(
    val cards: List<Card> = emptyList(),
    /** 正在等用户点头的预演卡。 */
    val pendingIntent: CardId? = null,
    val pause: PauseReason? = null,
    /** 时间线上最后一条事件的时间，用于拒绝乱序事件。 */
    val lastAt: Long = Long.MIN_VALUE,
    /** 流式说话的序号（决定 `say-N` 的 N）。 */
    val saySeq: Int = 0,
    /** 问题卡的序号。 */
    val troubleSeq: Int = 0,
) {

    val isPaused: Boolean get() = pause != null

    /** 所有仍在跑的卡 —— 接管时必须把它们**全部**收尾。 */
    val running: List<Card> get() = cards.filter { it.state == CardState.RUNNING }

    /**
     * 仍在跑的**动作**卡。
     *
     * ★ 与 [running] 分开：顶部那条"执行中"进度条只该反映**动作**。
     *   若拿 [running] 去数，用户会在"助理正在说话"时也看到进度条在转 ——
     *   而那两件事的"能不能停"完全不同（说话可以打断，动作要收尾）。
     */
    val runningActions: List<Card.Act> get() = running.filterIsInstance<Card.Act>()

    /** 找一张卡。 */
    fun card(id: CardId): Card? = cards.firstOrNull { it.id == id }

    /** 最近一张卡（可能没有）。 */
    fun last(): Card? = cards.lastOrNull()

    /**
     * 追加一个事件。
     *
     * 拒绝一律走 [Reduction.Rejected]，且**原样返回**这条时间线 ——
     * 调用方必须处理拒绝分支，不能假装事件生效了。
     */
    fun reduce(event: CardEvent): Reduction {
        // ── ① 乱序：宁拒绝一条，也不拼出一个错位的历史 ──────────────
        if (event.at < lastAt) return Reduction.Rejected(this, RejectReason.OUT_OF_ORDER)

        // ── ② 暂停期间只接受"收尾类"事件 ────────────────────────────
        if (isPaused && !acceptsWhilePaused(event)) {
            return Reduction.Rejected(this, RejectReason.PAUSED)
        }

        return when (event) {
            is CardEvent.IntentProposed -> propose(event)
            is CardEvent.IntentConfirmed -> confirm(event)
            is CardEvent.IntentDeclined -> decline(event)
            is CardEvent.ToolStarted -> startTool(event)
            is CardEvent.ToolSucceeded -> succeedTool(event)
            is CardEvent.ToolFailed -> failTool(event)
            is CardEvent.Observed -> observe(event)
            is CardEvent.AssistantDelta -> appendSay(event)
            is CardEvent.AssistantDone -> finishSay(event)
            is CardEvent.TakeOver -> pauseNow(event.at, PauseReason.USER_TAKEOVER)
            is CardEvent.BudgetExhausted -> pauseNow(event.at, PauseReason.BUDGET_EXHAUSTED)
            is CardEvent.Resume -> Reduction.Applied(copy(pause = null, lastAt = event.at))
        }
    }

    // ── 事件处理 ────────────────────────────────────────────────────

    private fun propose(e: CardEvent.IntentProposed): Reduction {
        if (pendingIntent != null) {
            return Reduction.Rejected(this, RejectReason.INTENT_ALREADY_PENDING)
        }
        if (card(e.id) != null) return Reduction.Rejected(this, RejectReason.ID_REUSED)
        val intent = Card.Intent(
            id = e.id,
            at = e.at,
            state = CardState.DRAFT,
            evidence = e.evidence,
            action = e.action,
            target = e.target,
            risk = e.risk,
            reversible = e.reversible,
        )
        return Reduction.Applied(
            copy(cards = cards + intent, pendingIntent = e.id, lastAt = e.at),
        )
    }

    private fun confirm(e: CardEvent.IntentConfirmed): Reduction {
        val id = pendingIntent ?: return Reduction.Rejected(this, RejectReason.NO_PENDING_INTENT)
        val target = card(id) ?: return Reduction.Rejected(this, RejectReason.CARD_NOT_FOUND)
        if (target.state.isTerminal) {
            return Reduction.Rejected(this, RejectReason.CARD_ALREADY_TERMINAL)
        }
        // ★ 预演卡"确认"后进 DONE 而不是 RUNNING：它自己的使命（问一句）已经完成，
        //   接下来跑的是 Act 卡。让它停在 RUNNING 会让复盘里出现一张永远转圈的卡。
        return Reduction.Applied(
            copy(
                cards = cards.replace(id) { (it as Card.Intent).copy(state = CardState.DONE) },
                pendingIntent = null,
                lastAt = e.at,
            ),
        )
    }

    private fun decline(e: CardEvent.IntentDeclined): Reduction {
        val id = pendingIntent ?: return Reduction.Rejected(this, RejectReason.NO_PENDING_INTENT)
        return Reduction.Applied(
            copy(
                cards = cards.replace(id) { (it as Card.Intent).copy(state = CardState.BLOCKED) },
                pendingIntent = null,
                lastAt = e.at,
            ),
        )
    }

    private fun startTool(e: CardEvent.ToolStarted): Reduction {
        if (card(e.id) != null) return Reduction.Rejected(this, RejectReason.ID_REUSED)
        val act = Card.Act(
            id = e.id,
            at = e.at,
            state = CardState.RUNNING,
            evidence = e.evidence,
            tool = e.tool,
            summary = e.summary,
            detail = "",
            channel = e.channel,
            latencyMs = null,
            uncertainty = null,
        )
        return Reduction.Applied(copy(cards = cards + act, lastAt = e.at))
    }

    private fun succeedTool(e: CardEvent.ToolSucceeded): Reduction {
        val existing = card(e.id) ?: return Reduction.Rejected(this, RejectReason.CARD_NOT_FOUND)

        // ⚠️ 顺序要紧：**先判类型，再判终态**。
        //    反过来的话，"把工具结果打在观察卡上"永远只会得到「卡已终结」
        //    （观察卡一出生就是 DONE），于是真正的接线错误被掩盖成状态问题。
        if (existing !is Card.Act) {
            return Reduction.Rejected(this, RejectReason.WRONG_CARD_KIND)
        }
        if (existing.state.isTerminal) {
            return Reduction.Rejected(this, RejectReason.CARD_ALREADY_TERMINAL)
        }
        val done = existing.copy(
            state = CardState.DONE,
            detail = e.detail,
            latencyMs = e.latencyMs,
            uncertainty = e.uncertainty,
        )
        return Reduction.Applied(
            copy(cards = cards.replace(e.id) { done }, lastAt = e.at),
        )
    }

    private fun failTool(e: CardEvent.ToolFailed): Reduction {
        val existing = card(e.id) ?: return Reduction.Rejected(this, RejectReason.CARD_NOT_FOUND)

        // 同上：先判类型。失败也只会落在动作卡上 ——
        // 一张"看到"的卡没有"失败"这个状态，给它扣一顶 FAILED 帽子是编故事。
        if (existing !is Card.Act) {
            return Reduction.Rejected(this, RejectReason.WRONG_CARD_KIND)
        }
        if (existing.state.isTerminal) {
            return Reduction.Rejected(this, RejectReason.CARD_ALREADY_TERMINAL)
        }
        val closed = when (e.gate) {
            // 安全拒绝 / 用户拒绝：都不是"做不成"，而是"不该做"。
            Gate.SAFETY, Gate.PERMISSION -> CardState.BLOCKED
            Gate.EXECUTION -> CardState.FAILED
        }
        val troubleId = CardId("trouble-${troubleSeq + 1}")
        val trouble = Card.Trouble(
            id = troubleId,
            at = e.at,
            state = CardState.DONE,
            evidence = emptyList(),
            about = e.id,
            gate = e.gate,
            reason = e.reason,
            exits = e.exits,
        )
        return Reduction.Applied(
            copy(
                cards = cards.replace(e.id) { it.withState(closed) } + trouble,
                troubleSeq = troubleSeq + 1,
                lastAt = e.at,
            ),
        )
    }

    private fun observe(e: CardEvent.Observed): Reduction {
        if (card(e.id) != null) return Reduction.Rejected(this, RejectReason.ID_REUSED)
        val obs = Card.Obs(
            id = e.id,
            at = e.at,
            state = CardState.DONE,
            evidence = e.evidence,
            summary = e.summary,
            nodeCount = e.nodeCount,
            redacted = e.redacted,
        )
        return Reduction.Applied(copy(cards = cards + obs, lastAt = e.at))
    }

    private fun appendSay(e: CardEvent.AssistantDelta): Reduction {
        if (e.text.isEmpty()) return Reduction.Rejected(this, RejectReason.EMPTY_DELTA)
        val open = cards.lastOrNull()
            ?.takeIf { it is Card.Say && it.state == CardState.RUNNING } as? Card.Say
        return if (open != null) {
            Reduction.Applied(
                copy(
                    cards = cards.replace(open.id) { open.copy(text = open.text + e.text) },
                    lastAt = e.at,
                ),
            )
        } else {
            val seq = saySeq + 1
            val say = Card.Say(
                id = CardId("say-$seq"),
                at = e.at,
                state = CardState.RUNNING,
                evidence = emptyList(),
                text = e.text,
            )
            Reduction.Applied(copy(cards = cards + say, saySeq = seq, lastAt = e.at))
        }
    }

    private fun finishSay(e: CardEvent.AssistantDone): Reduction {
        val open = cards.lastOrNull()
            ?.takeIf { it is Card.Say && it.state == CardState.RUNNING } as? Card.Say
            ?: return Reduction.Applied(copy(lastAt = e.at)) // 没有开着的卡，Done 是幂等的
        return Reduction.Applied(
            copy(
                cards = cards.replace(open.id) { open.copy(state = CardState.DONE) },
                lastAt = e.at,
            ),
        )
    }

    /**
     * 立刻停手。
     *
     * ★★ 这个函数是整套设计的**要害**：暂停时**所有** RUNNING 卡必须一起收尾。
     *   漏掉任何一张，界面上就会出现"它还在动"的假象 —— 而用户刚刚才按了停止。
     */
    private fun pauseNow(at: Long, reason: PauseReason): Reduction {
        val closed = cards.map { if (it.state == CardState.RUNNING) it.withState(CardState.CANCELLED) else it }
        return Reduction.Applied(copy(cards = closed, pause = reason, lastAt = at))
    }

    private fun acceptsWhilePaused(event: CardEvent): Boolean = when (event) {
        // 恢复与"再次暂停"本身必须放行，否则暂停是单向的（用户回不来）。
        is CardEvent.Resume, is CardEvent.TakeOver, is CardEvent.BudgetExhausted -> true
        // 关闭流式说话卡是收尾，不是执行。
        is CardEvent.AssistantDone -> true
        else -> false
    }
}

/** 替换指定 id 的那一张卡；不存在时原样返回（调用方已先做过存在性检查）。 */
private fun List<Card>.replace(id: CardId, transform: (Card) -> Card): List<Card> =
    map { if (it.id == id) transform(it) else it }

/** 只改状态，其余字段原样。 */
private fun Card.withState(next: CardState): Card = when (this) {
    is Card.Intent -> copy(state = next)
    is Card.Act -> copy(state = next)
    is Card.Obs -> copy(state = next)
    is Card.Say -> copy(state = next)
    is Card.Trouble -> copy(state = next)
}
