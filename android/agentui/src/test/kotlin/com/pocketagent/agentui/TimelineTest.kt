package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间线的状态机。
 *
 * ★ 本文件里最要紧的一组是「接管」那几条：用户按下停止之后，
 *   任何一张卡**都不许**还停在 RUNNING。界面上说"它还在动"而它已经停了，
 *   是比"停不下来"更坏的体验 —— 前者让人不敢再按，后者只是慢。
 */
class TimelineTest {

    private fun started(at: Long, id: String = "a-1", tool: String = "android_tap") =
        CardEvent.ToolStarted(at = at, id = CardId(id), tool = tool, summary = "点「发送」")

    private fun applied(r: Reduction): Timeline {
        assertTrue("期望 Applied，实际 $r", r is Reduction.Applied)
        return (r as Reduction.Applied).timeline
    }

    private fun rejected(r: Reduction): RejectReason {
        assertTrue("期望 Rejected，实际 $r", r is Reduction.Rejected)
        return (r as Reduction.Rejected).reason
    }

    // ── 空时间线 ───────────────────────────────────────────────────

    @Test
    fun `空时间线没有卡`() {
        assertTrue(Timeline().cards.isEmpty())
    }

    @Test
    fun `空时间线没有待确认的预演`() {
        assertNull(Timeline().pendingIntent)
    }

    @Test
    fun `空时间线没有暂停`() {
        assertNull(Timeline().pause)
        assertEquals(false, Timeline().isPaused)
    }

    @Test
    fun `空时间线没有在跑的卡`() {
        assertTrue(Timeline().running.isEmpty())
    }

    // ── 动作卡：开始 / 成功 / 失败 ──────────────────────────────────

    @Test
    fun `工具开始后出现一张 RUNNING 的动作卡`() {
        val t = applied(Timeline().reduce(started(10)))
        assertEquals(1, t.cards.size)
        assertEquals(CardState.RUNNING, t.cards[0].state)
    }

    @Test
    fun `工具开始后 running 里有一张卡`() {
        val t = applied(Timeline().reduce(started(10)))
        assertEquals(1, t.running.size)
    }

    @Test
    fun `工具成功把卡收成 DONE`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.ToolSucceeded(20, CardId("a-1"), "已派发", 72L))) }
        assertEquals(CardState.DONE, t.cards.single().state)
    }

    @Test
    fun `工具成功记录耗时`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.ToolSucceeded(20, CardId("a-1"), "已派发", 72L))) }
        assertEquals(72L, (t.cards.single() as Card.Act).latencyMs)
    }

    @Test
    fun `工具成功记录不确定性`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolSucceeded(20, CardId("a-1"), "已点击", 72L, "不知道点到了什么"),
                    ),
                )
            }
        assertEquals("不知道点到了什么", (t.cards.single() as Card.Act).uncertainty)
    }

    @Test
    fun `成功后 running 变空`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.ToolSucceeded(20, CardId("a-1"), "已派发", 72L))) }
        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `执行失败把卡收成 FAILED`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(
                            20,
                            CardId("a-1"),
                            Gate.EXECUTION,
                            "点了，但界面没反应",
                            FailurePlan.exitsFor(Gate.EXECUTION),
                        ),
                    ),
                )
            }
        assertEquals(CardState.FAILED, t.cards.first().state)
    }

    @Test
    fun `安全拒绝把卡收成 BLOCKED 而不是 FAILED`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(
                            20,
                            CardId("a-1"),
                            Gate.SAFETY,
                            "当前在支付页面",
                            FailurePlan.exitsFor(Gate.SAFETY),
                        ),
                    ),
                )
            }
        assertEquals(CardState.BLOCKED, t.cards.first().state)
    }

    @Test
    fun `用户拒绝把卡收成 BLOCKED`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(
                            20,
                            CardId("a-1"),
                            Gate.PERMISSION,
                            "你刚才没允许",
                            FailurePlan.exitsFor(Gate.PERMISSION),
                        ),
                    ),
                )
            }
        assertEquals(CardState.BLOCKED, t.cards.first().state)
    }

    @Test
    fun `失败后追加一张问题卡`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(
                            20,
                            CardId("a-1"),
                            Gate.EXECUTION,
                            "点了没反应",
                            FailurePlan.exitsFor(Gate.EXECUTION),
                        ),
                    ),
                )
            }
        assertEquals(2, t.cards.size)
        assertEquals(CardKind.TROUBLE, t.cards[1].kind)
    }

    @Test
    fun `问题卡 id 是递增的`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(20, CardId("a-1"), Gate.EXECUTION, "x", FailurePlan.exitsFor(Gate.EXECUTION)),
                    ),
                )
            }
        assertEquals(CardId("trouble-1"), t.cards[1].id)
        assertEquals(1, t.troubleSeq)
    }

    @Test
    fun `失败后 running 变空`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let {
                applied(
                    it.reduce(
                        CardEvent.ToolFailed(20, CardId("a-1"), Gate.EXECUTION, "x", FailurePlan.exitsFor(Gate.EXECUTION)),
                    ),
                )
            }
        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `重复成功被拒（卡已是终态）`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.ToolSucceeded(20, CardId("a-1"), "ok", 1L))) }
        assertEquals(
            RejectReason.CARD_ALREADY_TERMINAL,
            rejected(t.reduce(CardEvent.ToolSucceeded(30, CardId("a-1"), "ok again", 1L))),
        )
    }

    @Test
    fun `成功事件指向不存在的卡被拒`() {
        assertEquals(
            RejectReason.CARD_NOT_FOUND,
            rejected(Timeline().reduce(CardEvent.ToolSucceeded(10, CardId("nope"), "x", 1L))),
        )
    }

    @Test
    fun `id 重复的动作卡被拒`() {
        val t = applied(Timeline().reduce(started(10)))
        assertEquals(RejectReason.ID_REUSED, rejected(t.reduce(started(20))))
    }

    @Test
    fun `成功事件指向观察卡被拒（类型不对）`() {
        val t = applied(
            Timeline().reduce(
                CardEvent.Observed(10, CardId("o-1"), "微信主界面", 20),
            ),
        )
        // ★ 必须是 WRONG_CARD_KIND，不能是 CARD_NOT_FOUND：
        //   卡明明在，报"找不到"会把排查方向带到"是不是 id 传错了"。
        //   也不能是 CARD_ALREADY_TERMINAL：观察卡一出生就是 DONE，
        //   那个理由会把"接错线"掩盖成"状态问题"。
        assertEquals(
            RejectReason.WRONG_CARD_KIND,
            rejected(t.reduce(CardEvent.ToolSucceeded(20, CardId("o-1"), "x", 1L))),
        )
    }

    @Test
    fun `失败事件指向观察卡也被判类型不对`() {
        val t = applied(Timeline().reduce(CardEvent.Observed(10, CardId("o-1"), "微信主界面", 20)))
        assertEquals(
            RejectReason.WRONG_CARD_KIND,
            rejected(
                t.reduce(
                    CardEvent.ToolFailed(20, CardId("o-1"), Gate.EXECUTION, "x", FailurePlan.exitsFor(Gate.EXECUTION)),
                ),
            ),
        )
    }

    @Test
    fun `成功事件指向预演卡被判类型不对`() {
        val t = applied(Timeline().reduce(propose()))
        assertEquals(
            RejectReason.WRONG_CARD_KIND,
            rejected(t.reduce(CardEvent.ToolSucceeded(20, CardId("i-1"), "x", 1L))),
        )
    }

    @Test
    fun `类型不对被拒时观察卡仍是 DONE（没被扣上失败帽子）`() {
        val t = applied(Timeline().reduce(CardEvent.Observed(10, CardId("o-1"), "微信主界面", 20)))
        val after = (t.reduce(
            CardEvent.ToolFailed(20, CardId("o-1"), Gate.EXECUTION, "x", FailurePlan.exitsFor(Gate.EXECUTION)),
        ) as Reduction.Rejected).timeline
        assertEquals(CardState.DONE, after.cards.single().state)
        assertEquals(1, after.cards.size)
    }

    // ── 预演卡 ─────────────────────────────────────────────────────

    private fun propose(at: Long = 10, id: String = "i-1") = CardEvent.IntentProposed(
        at = at,
        id = CardId(id),
        action = "给妈妈发一条消息",
        target = "微信",
        risk = RiskLevel.EXTERNAL,
        reversible = false,
    )

    @Test
    fun `提出预演后出现一张 DRAFT 卡`() {
        val t = applied(Timeline().reduce(propose()))
        assertEquals(CardState.DRAFT, t.cards.single().state)
    }

    @Test
    fun `提出预演后有 pendingIntent`() {
        val t = applied(Timeline().reduce(propose()))
        assertEquals(CardId("i-1"), t.pendingIntent)
    }

    @Test
    fun `两个预演同时等确认时第二个被拒`() {
        val t = applied(Timeline().reduce(propose()))
        assertEquals(RejectReason.INTENT_ALREADY_PENDING, rejected(t.reduce(propose(at = 20, id = "i-2"))))
    }

    @Test
    fun `确认预演后 pendingIntent 清空`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentConfirmed(20))) }
        assertNull(t.pendingIntent)
    }

    @Test
    fun `确认预演后卡进 DONE（不再转圈）`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentConfirmed(20))) }
        assertEquals(CardState.DONE, t.cards.single().state)
    }

    @Test
    fun `确认预演后没有在跑的卡`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentConfirmed(20))) }
        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `没有待确认预演时确认被拒`() {
        assertEquals(
            RejectReason.NO_PENDING_INTENT,
            rejected(Timeline().reduce(CardEvent.IntentConfirmed(10))),
        )
    }

    @Test
    fun `否决预演后卡进 BLOCKED`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentDeclined(20, "那个群不对"))) }
        assertEquals(CardState.BLOCKED, t.cards.single().state)
    }

    @Test
    fun `否决预演后 pendingIntent 清空`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentDeclined(20, "不要"))) }
        assertNull(t.pendingIntent)
    }

    @Test
    fun `否决后不能再确认`() {
        val t = applied(Timeline().reduce(propose()))
            .let { applied(it.reduce(CardEvent.IntentDeclined(20, "不要"))) }
        assertEquals(RejectReason.NO_PENDING_INTENT, rejected(t.reduce(CardEvent.IntentConfirmed(30))))
    }

    @Test
    fun `没有待确认预演时否决被拒`() {
        assertEquals(
            RejectReason.NO_PENDING_INTENT,
            rejected(Timeline().reduce(CardEvent.IntentDeclined(10, "不要"))),
        )
    }

    // ── 观察卡 ─────────────────────────────────────────────────────

    @Test
    fun `观察卡一出现就是 DONE`() {
        val t = applied(Timeline().reduce(CardEvent.Observed(10, CardId("o-1"), "微信主界面", 20)))
        assertEquals(CardState.DONE, t.cards.single().state)
    }

    @Test
    fun `观察卡不进入 running`() {
        val t = applied(Timeline().reduce(CardEvent.Observed(10, CardId("o-1"), "微信主界面", 20)))
        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `观察卡的遮蔽标记被保留`() {
        val t = applied(
            Timeline().reduce(CardEvent.Observed(10, CardId("o-1"), "登录页", 12, redacted = true)),
        )
        assertTrue((t.cards.single() as Card.Obs).redacted)
    }

    // ── 流式说话 ───────────────────────────────────────────────────

    @Test
    fun `第一段增量产生一张 SAY 卡`() {
        val t = applied(Timeline().reduce(CardEvent.AssistantDelta(10, "好的")))
        assertEquals(CardKind.SAY, t.cards.single().kind)
    }

    @Test
    fun `第一段增量后卡是 RUNNING`() {
        val t = applied(Timeline().reduce(CardEvent.AssistantDelta(10, "好的")))
        assertEquals(CardState.RUNNING, t.cards.single().state)
    }

    @Test
    fun `后续增量拼进同一张卡`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.AssistantDelta(10, "好"))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "的"))) }
        assertEquals(1, t.cards.size)
        assertEquals("好的", (t.cards.single() as Card.Say).text)
    }

    @Test
    fun `增量拼完后 saySeq 只涨一次`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.AssistantDelta(10, "好"))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "的"))) }
        assertEquals(1, t.saySeq)
    }

    @Test
    fun `空增量被拒`() {
        assertEquals(RejectReason.EMPTY_DELTA, rejected(Timeline().reduce(CardEvent.AssistantDelta(10, ""))))
    }

    @Test
    fun `说完把卡收成 DONE`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.AssistantDelta(10, "好的"))) }
            .let { applied(it.reduce(CardEvent.AssistantDone(20))) }
        assertEquals(CardState.DONE, t.cards.single().state)
    }

    @Test
    fun `说完之后再来的增量开新卡`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.AssistantDelta(10, "第一句"))) }
            .let { applied(it.reduce(CardEvent.AssistantDone(20))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(30, "第二句"))) }
        assertEquals(2, t.cards.size)
        assertEquals(CardId("say-2"), t.cards[1].id)
    }

    @Test
    fun `没有开着的卡时 Done 是幂等的`() {
        val t = applied(Timeline().reduce(CardEvent.AssistantDone(10)))
        assertTrue(t.cards.isEmpty())
    }

    @Test
    fun `说话卡不会打断正在跑的动作卡`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "我在做"))) }
        assertEquals(2, t.cards.size)
        assertEquals(CardState.RUNNING, t.cards[0].state)
    }

    @Test
    fun `进度条只数动作卡，不数正在说话`() {
        // ★ 这条守的是"顶部那个转圈什么时候转"。
        //   若拿 running 去数，助理在说话时进度条也在转 ——
        //   而这两件事的可打断性完全不同（说话可以打断，动作要收尾）。
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "我在做"))) }
        assertEquals(1, t.runningActions.size)
        assertEquals(2, t.running.size)
    }

    @Test
    fun `只有说话卡时动作进度为空`() {
        val t = applied(Timeline().reduce(CardEvent.AssistantDelta(10, "好的")))
        assertTrue(t.runningActions.isEmpty())
    }

    @Test
    fun `接管时说话卡和动作卡一起收尾`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "我在做"))) }
            .let { applied(it.reduce(CardEvent.TakeOver(30))) }
        assertTrue("接管后还在跑：${t.running}", t.running.isEmpty())
        assertEquals(CardState.CANCELLED, t.cards[0].state)
        assertEquals(CardState.CANCELLED, t.cards[1].state)
    }

    // ── 接管（★ 本文件最重要的一组）────────────────────────────────

    @Test
    fun `接管后所有 RUNNING 卡变 CANCELLED`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(20, "在做"))) }
            .let { applied(it.reduce(CardEvent.TakeOver(30))) }
        assertTrue(t.cards.all { it.state != CardState.RUNNING })
    }

    @Test
    fun `接管后 running 为空`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `接管后被取消的卡是 CANCELLED 而不是 FAILED`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
        assertEquals(CardState.CANCELLED, t.cards.single().state)
    }

    @Test
    fun `接管后时间线处于暂停`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
        assertEquals(PauseReason.USER_TAKEOVER, t.pause)
    }

    @Test
    fun `接管后 isPaused 为真`() {
        val t = applied(Timeline().reduce(CardEvent.TakeOver(10)))
        assertTrue(t.isPaused)
    }

    @Test
    fun `接管期间不接受新的工具开始`() {
        val t = applied(Timeline().reduce(CardEvent.TakeOver(10)))
        assertEquals(RejectReason.PAUSED, rejected(t.reduce(started(20))))
    }

    @Test
    fun `接管期间不接受新的预演`() {
        val t = applied(Timeline().reduce(CardEvent.TakeOver(10)))
        assertEquals(RejectReason.PAUSED, rejected(t.reduce(propose(at = 20))))
    }

    @Test
    fun `接管期间不接受观察`() {
        val t = applied(Timeline().reduce(CardEvent.TakeOver(10)))
        assertEquals(
            RejectReason.PAUSED,
            rejected(t.reduce(CardEvent.Observed(20, CardId("o-1"), "微信", 3))),
        )
    }

    @Test
    fun `接管期间不接受新的说话增量`() {
        val t = applied(Timeline().reduce(CardEvent.TakeOver(10)))
        assertEquals(RejectReason.PAUSED, rejected(t.reduce(CardEvent.AssistantDelta(20, "我继续说"))))
    }

    @Test
    fun `接管期间允许关闭说话卡`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.AssistantDelta(10, "在做"))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
            .let { applied(it.reduce(CardEvent.AssistantDone(30))) }
        assertTrue(t.cards.all { it.state == CardState.CANCELLED || it.state == CardState.DONE })
    }

    @Test
    fun `接管可以重复（幂等）`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.TakeOver(10))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
        assertEquals(PauseReason.USER_TAKEOVER, t.pause)
    }

    @Test
    fun `恢复后暂停被清空`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.TakeOver(10))) }
            .let { applied(it.reduce(CardEvent.Resume(20))) }
        assertNull(t.pause)
    }

    @Test
    fun `恢复后可以继续开始工具`() {
        val t = Timeline()
            .let { applied(it.reduce(CardEvent.TakeOver(10))) }
            .let { applied(it.reduce(CardEvent.Resume(20))) }
            .let { applied(it.reduce(started(30))) }
        assertEquals(1, t.running.size)
    }

    @Test
    fun `恢复不会让已取消的卡复活`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.TakeOver(20))) }
            .let { applied(it.reduce(CardEvent.Resume(30))) }
        assertEquals(CardState.CANCELLED, t.cards.single().state)
    }

    @Test
    fun `预算耗尽也暂停`() {
        val t = applied(Timeline().reduce(CardEvent.BudgetExhausted(10)))
        assertEquals(PauseReason.BUDGET_EXHAUSTED, t.pause)
    }

    @Test
    fun `预算耗尽后 running 也为空`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.BudgetExhausted(20))) }
        assertTrue(t.running.isEmpty())
    }

    // ── 乱序 ───────────────────────────────────────────────────────

    @Test
    fun `时间倒退的事件被拒`() {
        val t = applied(Timeline().reduce(started(100)))
        assertEquals(RejectReason.OUT_OF_ORDER, rejected(t.reduce(CardEvent.Observed(50, CardId("o-1"), "微信", 3))))
    }

    @Test
    fun `时间相同的事件允许（同一毫秒内的连续事件）`() {
        val t = Timeline()
            .let { applied(it.reduce(started(100))) }
            .let { applied(it.reduce(CardEvent.AssistantDelta(100, "同时"))) }
        assertEquals(2, t.cards.size)
    }

    @Test
    fun `乱序被拒时时间线原样返回`() {
        val t = applied(Timeline().reduce(started(100)))
        val r = t.reduce(CardEvent.Observed(50, CardId("o-1"), "微信", 3)) as Reduction.Rejected
        assertEquals(t, r.timeline)
    }

    // ── 查询辅助 ───────────────────────────────────────────────────

    @Test
    fun `card 能按 id 找到卡`() {
        val t = applied(Timeline().reduce(started(10)))
        assertEquals(CardKind.ACT, t.card(CardId("a-1"))?.kind)
    }

    @Test
    fun `card 找不到时返回 null`() {
        assertNull(Timeline().card(CardId("nope")))
    }

    @Test
    fun `last 返回最后一张卡`() {
        val t = Timeline()
            .let { applied(it.reduce(started(10))) }
            .let { applied(it.reduce(CardEvent.Observed(20, CardId("o-1"), "微信", 3))) }
        assertEquals(CardId("o-1"), t.last()?.id)
    }

    @Test
    fun `空时间线的 last 是 null`() {
        assertNull(Timeline().last())
    }

    // ── 纯函数性 ───────────────────────────────────────────────────

    @Test
    fun `同一串事件两次 reduce 结果完全相同`() {
        val events = listOf(
            started(10),
            CardEvent.ToolSucceeded(20, CardId("a-1"), "已派发", 72L),
            CardEvent.Observed(30, CardId("o-1"), "微信主界面", 20),
            CardEvent.AssistantDelta(40, "好的"),
            CardEvent.AssistantDone(50),
        )
        fun run(): Timeline = events.fold(Timeline()) { acc, e -> applied(acc.reduce(e)) }
        assertEquals(run(), run())
    }

    @Test
    fun `reduce 不改原始时间线`() {
        val original = Timeline()
        val next = applied(original.reduce(started(10)))
        assertTrue(original.cards.isEmpty())
        assertEquals(1, next.cards.size)
    }
}
