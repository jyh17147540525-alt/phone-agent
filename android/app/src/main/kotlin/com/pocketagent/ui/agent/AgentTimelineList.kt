package com.pocketagent.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.pocketagent.agentui.Card
import com.pocketagent.agentui.CardId
import com.pocketagent.agentui.CardState
import com.pocketagent.agentui.Exit
import com.pocketagent.agentui.PauseReason
import com.pocketagent.agentui.Timeline
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaSpace

/**
 * 时间线列表 —— 三拍的**呈现层**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 顺序：为什么"最新的在最下面"而不是最上面
 * ═══════════════════════════════════════════════════════════════
 *
 * 聊天式列表（新消息在下面）符合"对话在往下长"的直觉，而卡片讲的是
 * **一件事的推进过程** —— 它天然是往下长的，且用户读它时是"从头看到现在"。
 * 倒序会让"预演 → 执行 → 复盘"这条因果链被反过来读。
 *
 * ⇒ 正序 + 自动跟到底部。用户往上翻历史时**不抢滚动**
 *   （`if (!state.isScrollInProgress)` 那一条），否则他正在看的东西会被拽走。
 */
@Composable
fun AgentTimelineList(
    timeline: Timeline,
    modifier: Modifier = Modifier,
    onConfirm: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    onExit: ((CardId, Exit) -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // 自动跟到底部：只在用户**没有**在手动滚动时抢滚动位置。
    LaunchedEffect(timeline.cards.size, timeline.pause) {
        if (timeline.cards.isEmpty()) return@LaunchedEffect
        if (!listState.isScrollInProgress) {
            listState.animateScrollToItem(timeline.cards.lastIndex)
        }
    }

    if (timeline.cards.isEmpty() && timeline.pause == null) {
        PaEmptyState(
            title = "还没有动作",
            description = "说一句话，我会先告诉你打算怎么做，你点头我才动手。",
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            top = PaSpace.s,
            bottom = PaSpace.m,
        ),
        verticalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) {
        items(items = timeline.cards, key = { it.id.value }) { card ->
            // ★ 三个回调**按卡片类型与状态**决定是否下传，而不是先造出来再过滤。
            //
            //   逻辑层已经保证了"非 DRAFT 的预演卡不响应确认""非 Trouble 卡不响应出口"
            //   （`Timeline.reduce` 会返回 `CARD_ALREADY_TERMINAL` / `WRONG_CARD_KIND`）。
            //   界面这层再收一次口，防的是**点了没反应**：能点的按钮压根不该出现。
            val isDraftIntent = card is Card.Intent && card.state == CardState.DRAFT
            AgentCard(
                card = card,
                onConfirm = if (isDraftIntent) onConfirm else null,
                onDecline = if (isDraftIntent) onDecline else null,
                onExit = if (card is Card.Trouble && onExit != null) {
                    { exit: Exit -> onExit(card.id, exit) }
                } else {
                    null
                },
            )
        }

        // 接管条排在**最后一张卡之后**：它是"现在的状态"，
        // 而状态永远在时间线的末端。放顶部会让它看起来像一条历史记录。
        timeline.pause?.let { reason ->
            item(key = "takeover-bar") {
                TakeoverBar(
                    reason = reason,
                    timeline = timeline,
                    onResume = onResume ?: {},
                    onExport = onExport,
                )
            }
        }
    }
}

/**
 * 内置的演示时间线 —— 给 Compose Preview 与"忘了接线的同事"用。
 *
 * ⚠️ 它是**投影的样例**，不是业务数据：真数据只从 `Timeline.reduce` 来。
 *    这里刻意直接构造卡片（而不是跑一遍事件），因为 Preview 要的是
 *    "五种卡都长什么样"，跑事件反而要维护一串与展示无关的输入。
 */
internal fun previewTimeline(): Timeline = Timeline(
    cards = listOf(
        Card.Say(
            id = CardId("say-1"),
            at = 0L,
            state = CardState.DONE,
            evidence = emptyList(),
            text = "好，我先看看微信在不在前台。",
        ),
        Card.Obs(
            id = CardId("o-1"),
            at = 1L,
            state = CardState.DONE,
            evidence = listOf(
                com.pocketagent.agentui.Evidence(
                    com.pocketagent.agentui.EvidenceKind.NODE_REF,
                    "微信 · 聊天页",
                ),
            ),
            summary = "微信主界面",
            nodeCount = 42,
            redacted = false,
        ),
        Card.Intent(
            id = CardId("i-1"),
            at = 2L,
            state = CardState.DRAFT,
            evidence = listOf(
                com.pocketagent.agentui.Evidence(
                    com.pocketagent.agentui.EvidenceKind.USER_INSTRUCTION,
                    "给妈发个消息说我到家了",
                ),
                com.pocketagent.agentui.Evidence(
                    com.pocketagent.agentui.EvidenceKind.MODEL_CLAIM,
                    "输入框在屏幕底部",
                ),
            ),
            action = "给妈妈发一条消息",
            target = "微信 · 妈妈",
            risk = com.pocketagent.agentui.RiskLevel.EXTERNAL,
            reversible = false,
        ),
        Card.Act(
            id = CardId("a-1"),
            at = 3L,
            state = CardState.DONE,
            evidence = emptyList(),
            tool = "android_tap",
            summary = "点「发送」",
            detail = "已派发，通道=ACCESSIBILITY",
            channel = "ACCESSIBILITY",
            latencyMs = 72L,
            uncertainty = null,
        ),
        Card.Trouble(
            id = CardId("trouble-1"),
            at = 4L,
            state = CardState.DONE,
            evidence = emptyList(),
            about = CardId("a-1"),
            gate = com.pocketagent.agentui.Gate.PERMISSION,
            reason = "你刚才没有允许这一步。",
            exits = com.pocketagent.agentui.FailurePlan.exitsFor(
                com.pocketagent.agentui.Gate.PERMISSION,
            ),
        ),
    ),
    pendingIntent = null,
    pause = PauseReason.USER_TAKEOVER,
    lastAt = 4L,
    saySeq = 1,
    troubleSeq = 1,
)

/** 空时间线的 Preview 用。 */
internal val previewEmptyTimeline = Timeline()
