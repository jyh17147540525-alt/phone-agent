package com.pocketagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.Card
import com.pocketagent.agentui.CardState
import com.pocketagent.agentui.Evidence
import com.pocketagent.agentui.EvidenceKind
import com.pocketagent.agentui.Exit
import com.pocketagent.agentui.FailurePlan
import com.pocketagent.agentui.Gate
import com.pocketagent.agentui.PauseReason
import com.pocketagent.agentui.RiskLevel
import com.pocketagent.agentui.TimelineExport
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * 卡片渲染 —— 「看得见、停得下、说得清」的界面部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 `:agentui` 的分工（别把逻辑写进来）
 * ═══════════════════════════════════════════════════════════════
 *
 * `:agentui` 是**纯逻辑**：卡片的状态、失败卡的出口、撤销范围、导出格式，
 * 全部在那里定好并已被 280 条离线测试钉住。本文件**只做投影**：
 * 把已经算好的东西画出来。
 *
 * ⇒ 这里**不允许**出现任何 `if (风险高) 就不给重试` 之类的判断 ——
 *   判断在 `FailurePlan.exitsFor()` 里，这里只 `forEach` 画。
 *   一旦在这里再判一次，两侧就会漂移，而漂移的方向是**界面比逻辑宽松**
 *   （界面给了出口，逻辑没给），用户点了没反应。
 *
 * ⚠️ 颜色取自 [PaColor] 的语义色，**不新造颜色**，也不走主题 ——
 *    理由见 `Tokens.kt`：风险色是条件反射，同一件事在两处不同颜色等于没有颜色。
 */

// ═══════════════════════════════════════════════════════════════
//  风险 → 颜色/文案（唯一的映射点）
// ═══════════════════════════════════════════════════════════════

/**
 * 五档动作风险 → 语义色。
 *
 * ⚠️ [RiskLevel] 与 `PluginCapability.RiskLevel` 是**两个维度**（见
 *    `agentui/RiskLevel.kt` 顶部表格），所以这里必然是多对一的映射：
 *    插件那套没有"花钱"这一档，而这一档恰好是最不能记授权的那一档。
 */
internal fun riskTone(risk: RiskLevel): PaBadgeTone = when (risk) {
    RiskLevel.READ -> PaBadgeTone.Neutral
    RiskLevel.WRITE -> PaBadgeTone.Accent
    RiskLevel.EXTERNAL -> PaBadgeTone.Warning
    // 花钱与身份信息用同一个"最红"的档：用户不需要在这两者之间再取舍，
    // 需要的是一眼看出"这一步我必须自己来"。
    RiskLevel.MONEY, RiskLevel.IDENTITY -> PaBadgeTone.Danger
}

/**
 * 风险的中文短语。
 *
 * ★ 措辞刻意用**动作会造成的后果**，而不是"风险等级：高"。
 *   "高"是给评估者看的；"会发出去"是给用户看的 —— 他要判断的是"我认不认这件事"。
 */
internal fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.READ -> "只看一眼"
    RiskLevel.WRITE -> "改动这台手机"
    RiskLevel.EXTERNAL -> "会发出去"
    RiskLevel.MONEY -> "会花钱"
    RiskLevel.IDENTITY -> "涉及身份信息"
}

/** 卡片状态的视觉标记。与 `TimelineExport.stateMark` 同源，但那是 Markdown 用词。 */
private fun stateTone(state: CardState): PaBadgeTone = when (state) {
    CardState.DRAFT -> PaBadgeTone.Accent
    CardState.RUNNING -> PaBadgeTone.Accent
    CardState.DONE -> PaBadgeTone.Success
    CardState.BLOCKED -> PaBadgeTone.Warning
    CardState.FAILED -> PaBadgeTone.Danger
    CardState.CANCELLED -> PaBadgeTone.Neutral
}

private fun stateLabel(state: CardState): String = when (state) {
    CardState.DRAFT -> "等你点头"
    CardState.RUNNING -> "正在做"
    CardState.DONE -> "做完了"
    CardState.BLOCKED -> "没做（被拦下）"
    CardState.FAILED -> "没做成"
    CardState.CANCELLED -> "已停下"
}

// ═══════════════════════════════════════════════════════════════
//  单张卡
// ═══════════════════════════════════════════════════════════════

/**
 * 一张卡。
 *
 * @param onConfirm 预演卡的「就这么做」。**只有 [CardState.DRAFT] 时才可能被调用。**
 * @param onDecline 预演卡的「算了」。
 * @param onExit 失败卡的某个出口。参数是出口本身 —— 调用方要按出口类型分派
 *   （`Exit.Retry` / `Exit.TakeOver` / `Exit.Alternative`），**不要**在这里判。
 */
@Composable
fun AgentCard(
    card: Card,
    modifier: Modifier = Modifier,
    onConfirm: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    onExit: ((Exit) -> Unit)? = null,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        when (card) {
            is Card.Intent -> IntentBody(card, onConfirm, onDecline)
            is Card.Act -> ActBody(card)
            is Card.Obs -> ObsBody(card)
            is Card.Say -> SayBody(card)
            is Card.Trouble -> TroubleBody(card, onExit)
        }
    }
}

/**
 * 卡头：状态徽章 + 风险徽章。
 *
 * ★ 风险徽章**只在预演卡上出现**，且是刻意放在卡头的 ——
 *   "要花钱"这件事必须在用户的视线扫过卡片时就被看到，
 *   而不是藏在展开的详情里（那里只有已经决定要看的人才会看到）。
 */
@Composable
private fun CardHeader(
    state: CardState,
    risk: RiskLevel? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (risk != null) {
            PaBadge(text = riskLabel(risk), tone = riskTone(risk))
            Spacer(Modifier.width(PaSpace.xs))
        }
        Spacer(Modifier.weight(1f))
        if (state != CardState.RUNNING) {
            PaBadge(text = stateLabel(state), tone = stateTone(state))
        } else {
            RunningIndicator()
        }
        trailing?.let {
            Spacer(Modifier.width(PaSpace.xs))
            it()
        }
    }
}

/**
 * 「正在做」的指示器。
 *
 * ★ 这是一个**会动的**东西，所以它必须只表达"进程在跑"这一件事 ——
 *   任何更花哨的动画都会让人以为"快好了"，而实际耗时是不可知的。
 *   透明度呼吸（而不是旋转/进度条）刻意避开"进度"的含义：
 *   我们并不知道还剩多少。
 */
@Composable
private fun RunningIndicator() {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "runningAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(alpha)
                .clip(RoundedCornerShape(PaRadius.pill))
                .background(PaColor.Accent),
        )
        Spacer(Modifier.width(PaSpace.xxs))
        Text(text = stateLabel(CardState.RUNNING), style = PaType.label, color = PaColor.Accent)
    }
}

// ── 预演卡 ─────────────────────────────────────────────────────

/**
 * 预演卡：**先说做什么，再问能不能做**。
 *
 * 这是三拍里的第一拍。它与"直接做完了告诉我"的差别，正是本设计要买的东西：
 * 用户能在事情发生**之前**拦下它。
 */
@Composable
private fun IntentBody(
    card: Card.Intent,
    onConfirm: (() -> Unit)?,
    onDecline: (() -> Unit)?,
) {
    Column {
        CardHeader(state = card.state, risk = card.risk)

        Spacer(Modifier.height(PaSpace.s))

        Text(
            text = card.action,
            style = PaType.headline,
            color = PaColor.TextPrimary,
        )
        if (card.target != null) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "对象：${card.target}",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        if (card.evidence.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.s))
            WhyPanel(evidence = card.evidence)
        }

        // 只在待确认时给按钮 —— 卡片一旦执行完，按钮就成了"再点一次"的陷阱。
        if (card.state == CardState.DRAFT && (onConfirm != null || onDecline != null)) {
            Spacer(Modifier.height(PaSpace.s))
            Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
                if (onConfirm != null) {
                    PaButton(text = confirmText(card.risk), onClick = onConfirm)
                }
                if (onDecline != null) {
                    PaButton(text = "算了", onClick = onDecline, style = PaButtonStyle.Text)
                }
            }
        }
    }
}

/**
 * 确认按钮的文案。
 *
 * ★ 刻意**不复用** `IntentGate.confirmLabel()` —— 那个在纯逻辑层，
 *   输出的是"确认执行"这类通用词；这里是界面层，要贴风险说话
 *   （"会花钱"的卡上写"确认执行"太轻了）。
 *   两处文案都指向同一个动作，但服务不同读者。
 *
 * ⚠️ 但**要不要显示按钮**这件事必须与逻辑层一致（`CardState.DRAFT`），
 *    这里没有自己判断。
 */
private fun confirmText(risk: RiskLevel): String = when (risk) {
    RiskLevel.READ -> "看一下"
    RiskLevel.WRITE -> "可以"
    RiskLevel.EXTERNAL -> "发出去"
    RiskLevel.MONEY -> "我来付这一步"
    RiskLevel.IDENTITY -> "我来填这一步"
}

// ── 动作卡 ─────────────────────────────────────────────────────

/**
 * 动作卡：**正在做/做完了什么**。
 *
 * ⚠️ 摘要不换行：`Act.SUMMARY_MAX_CHARS = 60` 是逻辑层的硬约束，
 *    它保证这一行不会把卡片撑成一屏 —— 详情在下面另起一行。
 */
@Composable
private fun ActBody(card: Card.Act) {
    Column {
        CardHeader(state = card.state)

        Spacer(Modifier.height(PaSpace.xs))

        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = card.tool,
                style = PaType.overline,
                color = PaColor.TextTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(PaRadius.xs))
                    .background(PaColor.SurfaceHigh)
                    .padding(horizontal = PaSpace.xxs, vertical = 2.dp),
            )
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = card.summary,
                style = PaType.body,
                color = PaColor.TextPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        if (card.detail.isNotBlank()) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = card.detail,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        // ⚠️ 不确定性**必须**显示在最显眼处，不能折进详情。
        //    它出现时意味着"我们不知道点到了什么"，用户需要据此决定要不要核查。
        //
        // ⚠️ 必须先把 `uncertainty` 取到局部变量：它是**另一个模块**里声明的
        //    公开属性，Kotlin 不对跨模块属性做 smart cast（编译期直接报
        //    "Smart cast is impossible"）。写成 `if (card.uncertainty != null)`
        //    再 `card.uncertainty` 传下去，编译不过。
        val uncertainty = card.uncertainty
        if (uncertainty != null) {
            Spacer(Modifier.height(PaSpace.xs))
            PaBadge(text = uncertainty, tone = PaBadgeTone.Warning)
        }

        val meta = buildList {
            card.channel?.let { add("通道 $it") }
            card.latencyMs?.let { add("${it}ms") }
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(text = meta.joinToString(" · "), style = PaType.caption, color = PaColor.TextTertiary)
        }
    }
}

// ── 观察卡 ─────────────────────────────────────────────────────

/** 观察卡：**看到了什么**。这是模型的依据，不是模型的结论。 */
@Composable
private fun ObsBody(card: Card.Obs) {
    Column {
        CardHeader(state = card.state)

        Spacer(Modifier.height(PaSpace.xs))

        Text(text = card.summary, style = PaType.body, color = PaColor.TextPrimary)

        Spacer(Modifier.height(PaSpace.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${card.nodeCount} 个节点",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            if (card.redacted) {
                Spacer(Modifier.width(PaSpace.xs))
                PaBadge(text = "含已遮蔽内容", tone = PaBadgeTone.Neutral)
            }
        }

        if (card.evidence.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.s))
            WhyPanel(evidence = card.evidence)
        }
    }
}

// ── 说话卡 ─────────────────────────────────────────────────────

/**
 * 说话卡：**模型对用户说的话**，流式追加。
 *
 * ⚠️ [card.text] 是累加过的全文（`:agentui` 的 `appendSay` 负责拼接），
 *    这里**不做增量拼接**。在界面层拼字符串会让"重放同一串事件得到同样结果"
 *    这条不变量失效 —— 而复盘的正确性全靠它。
 */
@Composable
private fun SayBody(card: Card.Say) {
    Column {
        CardHeader(state = card.state)
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = card.text,
            style = PaType.body,
            color = PaColor.TextPrimary,
        )
    }
}

// ── 问题卡 ─────────────────────────────────────────────────────

/**
 * 问题卡：**没做成，以及现在能怎么办**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 出口来自 `FailurePlan`，这里一个字都不加
 * ═══════════════════════════════════════════════════════════════
 *
 * 逻辑层已经保证了三条铁律（见 `FailurePlan`）：安全拒绝没有「重试」、
 * 用户拒绝必须有「重新授权」、执行失败必须有「我来」。
 * 界面如果自己再加一个"重试"，就等于把第一条铁律在界面上推翻了 ——
 * 而用户点下去必然再次被拒，且他会以为"多试几次就行"。
 */
@Composable
private fun TroubleBody(card: Card.Trouble, onExit: ((Exit) -> Unit)?) {
    val tone = when (card.gate) {
        Gate.SAFETY -> PaBadgeTone.Warning
        Gate.PERMISSION -> PaBadgeTone.Accent
        Gate.EXECUTION -> PaBadgeTone.Danger
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaBadge(text = FailurePlan.headline(card.gate), tone = tone)
            Spacer(Modifier.weight(1f))
            PaBadge(text = stateLabel(card.state), tone = stateTone(card.state))
        }

        Spacer(Modifier.height(PaSpace.xs))
        Text(text = card.reason, style = PaType.body, color = PaColor.TextPrimary)

        val causes = FailurePlan.likelyCauses(card.gate)
        if (causes.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = "可能的原因：" + causes.joinToString("；"),
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        Spacer(Modifier.height(PaSpace.s))

        // 出口**横向铺开**而不是折进菜单：失败时刻用户最需要的是一眼看到所有选择。
        Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
            card.exits.forEach { exit ->
                PaButton(
                    text = exitText(exit),
                    onClick = { onExit?.invoke(exit) },
                    style = if (exit is Exit.TakeOver) PaButtonStyle.Primary else PaButtonStyle.Glass,
                    enabled = onExit != null,
                    fillWidth = true,
                )
            }
        }

        Spacer(Modifier.height(PaSpace.xxs))
        Text(
            text = FailurePlan.takeoverHint(card.gate),
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
    }
}

/**
 * 出口按钮的文案。
 *
 * ★ 「我来」这个词是**刻意的**：`Exit.TakeOver` 的语义是"我不做了，你来做"，
 *   而不是"重试"或"再试一次"。它必须听起来像一个可托付的动作。
 */
private fun exitText(exit: Exit): String = when (exit) {
    Exit.Retry -> "再试一次"
    is Exit.Alternative -> exit.label
    Exit.TakeOver -> "我来"
}

// ═══════════════════════════════════════════════════════════════
//  为什么（依据面板）
// ═══════════════════════════════════════════════════════════════

/**
 * 依据面板：**回答"你凭什么"**。
 *
 * ★ 四类依据的措辞必须**明显不同**，尤其是最后一条：
 *   前三条是"我们查到的"，最后一条是"模型说的（未经核对）"。
 *   混在一起，用户就无法分辨"它看到了按钮"和"它以为那里有按钮"。
 */
@Composable
fun WhyPanel(
    evidence: List<Evidence>,
    modifier: Modifier = Modifier,
) {
    if (evidence.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(PaColor.SurfaceHigh)
            .clickable { expanded = !expanded }
            .padding(PaSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "依据",
                style = PaType.overline,
                color = PaColor.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "收起" else "${evidence.size} 条",
                style = PaType.label,
                color = PaColor.TextTertiary,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = PaMotion.standard()) + fadeIn(),
            exit = shrinkVertically(animationSpec = PaMotion.standard()) + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = PaSpace.xs)) {
                evidence.forEach { e ->
                    Row(modifier = Modifier.padding(bottom = PaSpace.xxs)) {
                        Text(
                            text = evidenceTag(e.kind),
                            style = PaType.overline,
                            color = evidenceTone(e.kind),
                        )
                        Spacer(Modifier.width(PaSpace.xs))
                        Text(
                            text = e.detail,
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun evidenceTag(kind: EvidenceKind): String = when (kind) {
    EvidenceKind.NODE_REF -> "看到"
    EvidenceKind.RULE_HIT -> "规则"
    EvidenceKind.USER_INSTRUCTION -> "你说"
    EvidenceKind.MODEL_CLAIM -> "模型说"
}

/**
 * ★ [EvidenceKind.MODEL_CLAIM] 用**警告色**，其余中性。
 *
 * 理由与 `TimelineExport.evidence()` 里那句"（未经核对）"相同：
 * 它是模型的主张，不是我们的发现。用同一个颜色，等于告诉用户
 * "这条和那条一样可信"。
 */
private fun evidenceTone(kind: EvidenceKind): Color = when (kind) {
    EvidenceKind.MODEL_CLAIM -> PaColor.Warning
    else -> PaColor.TextTertiary
}

// ═══════════════════════════════════════════════════════════════
//  接管条
// ═══════════════════════════════════════════════════════════════

/**
 * 接管条：**暂停时永远可见的那一条**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它必须"一直挂着"而不是自动消失
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户按下「我来」的那一刻，脑子里想的是"等等，我来"。此时如果界面上
 * 没有任何东西**持续**告诉他"已经停了"，他会怀疑是不是还要手动去哪儿关掉 ——
 * 而对一个正在操作他手机的东西，这种不确定是无法忍受的。
 *
 * ⇒ 它是**状态显示**，不是一次性提示：不设超时、不淡出，直到用户点恢复。
 *
 * ⚠️ 恢复按钮的文案是「接着来」而不是「继续」：后者在中文里更像
 *    "继续原来的流程（也许我不该恢复）"，前者更像"我把这件交回给你"。
 *    这个按钮按下去意味着**再次授权**，措辞必须让用户意识到这一点。
 */
@Composable
fun TakeoverBar(
    reason: PauseReason,
    timeline: com.pocketagent.agentui.Timeline,
    onResume: () -> Unit,
    onExport: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 暂停时 `pauseNow` 已经把所有进行中的卡收尾了，所以这里数不到 RUNNING 卡。
    // ⚠️ 数的是**CANCELLED** 而不是"所有卡"：用户关心的是"它刚才停在了几件事上"。
    val cancelled = timeline.cards.count { it.state == CardState.CANCELLED }

    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(PaRadius.pill))
                        .background(PaColor.Warning),
                )
                Spacer(Modifier.width(PaSpace.xs))
                Text(
                    text = pauseHeadline(reason),
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = pauseDetail(reason, cancelled),
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )

            Spacer(Modifier.height(PaSpace.s))
            Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
                PaButton(text = "接着来", onClick = onResume)
                if (onExport != null) {
                    PaButton(
                        text = "看它做了什么",
                        onClick = onExport,
                        style = PaButtonStyle.Text,
                    )
                }
            }
        }
    }
}

private fun pauseHeadline(reason: PauseReason): String = when (reason) {
    PauseReason.USER_TAKEOVER -> "已停下，交给你了"
    PauseReason.BUDGET_EXHAUSTED -> "先停一下（这轮用完了）"
}

/**
 * ★ 这句话是接管条的**全部价值所在**：它必须回答"停在哪了"。
 *
 * 只说"已暂停"是不够的 —— 用户会立刻追问"那它刚才有没有动我的东西？"
 * 而这个数字就是答案（[Timeline.pauseNow] 保证了这些卡确实已经收尾）。
 */
private fun pauseDetail(reason: PauseReason, cancelled: Int): String = when {
    cancelled == 0 -> "还没有开始动手就停了。"
    reason == PauseReason.USER_TAKEOVER ->
        "有 $cancelled 件事停在了半路，没有继续。你做完之后点「接着来」，我会从结果接着往下。"
    else ->
        "有 $cancelled 件事没做完。点「接着来」我会再试一次 —— 也可以先看看它做了什么。"
}

// ═══════════════════════════════════════════════════════════════
//  导出面板（复盘）
// ═══════════════════════════════════════════════════════════════

/**
 * 复盘：把 [timeline] 导出成**给人看的** Markdown。
 *
 * ⚠️ 用 [TimelineExport.toMarkdown] 而不是自己拼字符串 ——
 *    导出格式是 `:agentui` 的契约（有 20+ 条测试钉着），
 *    界面自己拼一份，两份就会漂移，而用户会贴给客服看。
 */
@Composable
fun TimelineReview(
    timeline: com.pocketagent.agentui.Timeline,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PaSectionTitle(text = "它做了什么")
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = TimelineExport.toMarkdown(timeline),
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )
    }
}
