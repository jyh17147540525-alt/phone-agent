package com.pocketagent.ui.tasks

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * **对话式渲染** —— ChatGPT / Claude 那种"一问一答"的形态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-27：从"任务卡片列表"改成"对话流"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话："与模型对话应该是以对话的方式存在，就像 ChatGPT 和 Claude
 * 那样的对话方式。"
 *
 * 改之前的问题：每一条用户发言都是一张**横穿屏幕的大卡片**，
 * 里面塞着提示词 + 状态徽章 + 回答 + 进度条。后果：
 *
 *  - 十句话之后屏幕上是十张一样重的卡片，**读起来像日志，不像对话**
 *  - 用户自己说的话和 AI 的回答**视觉权重相同**，分不清谁说的
 *  - 最简单的"你好"也占掉整行，留白全被卡片吃掉了
 *
 * ═══════════════════════════════════════════════════════════════
 *  两种形态，按内容自动切换
 * ═══════════════════════════════════════════════════════════════
 *
 * | 内容 | 形态 |
 * |---|---|
 * | 纯对话（问一句答一句） | **气泡流**：用户右、AI 左，无边框 |
 * | 真的在办事（跑 agent 循环） | **横穿卡片**：保留进度/步骤/中止 |
 *
 * ⚠️ 判据是"**这一轮有没有真的动手**"，不是"任务状态"。
 *    一个 Done 的任务可能只是回答了一句话（该用气泡），
 *    也可能跑完了一次完整的手机操作（该用卡片）。
 *
 *    用 `AgentTaskUi.isAgentRun` 来区分，那个字段由 `ChatPort` 的
 *    意图判定结果决定 —— 它是唯一知道"这一轮到底动没动手"的地方。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 气泡不画边框，只画底色
 * ═══════════════════════════════════════════════════════════════
 *
 * 深色主题下给气泡加描边会让每一句话看起来都像"一个控件"，
 * 满屏边框 = 没有重点。用户气泡用一点点强调色底，AI 侧**完全无底**——
 * 那条"AI 说的话是正文、不需要容器"的判断，是 ChatGPT 观感的核心。
 */
@Composable
fun ConversationList(
    tasks: List<AgentTaskUi>,
    onAbort: (String) -> Unit,
    modifier: Modifier = Modifier,
    onConfirm: ((String) -> Unit)? = null,
    onDecline: ((String) -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PaSpace.m),
    ) {
        tasks.forEach { task ->
            if (task.isAgentRun) {
                // 真的动了手 —— 保留完整卡片（进度、步骤、中止）
                TaskCard(task = task, onAbort = { onAbort(task.id) })
            } else {
                // 纯对话 —— 气泡流
                TurnBubbles(
                    task = task,
                    onAbort = { onAbort(task.id) },
                )
            }
        }
    }
}

/**
 * 一轮对话的两个气泡：用户的（右）+ AI 的（左）。
 *
 * ★ 顺序固定：先用户后 AI。即使 AI 还没回，用户那句也先显示 ——
 *   否则用户会怀疑"我到底发出去没有"。
 */
@Composable
private fun TurnBubbles(
    task: AgentTaskUi,
    onAbort: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {

        // ── 用户：右对齐的浅色气泡 ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    // ⚠️ 最多占 82% 宽度：满宽气泡会让"谁说的"失去方向感。
                    //    留出的那一点空白，就是对话感的来源。
                    .fillMaxWidth(0.82f)
                    .background(
                        color = PaColor.AccentSoft,
                        shape = RoundedCornerShape(
                            topStart = PaRadius.m,
                            topEnd = PaRadius.m,
                            bottomStart = PaRadius.m,
                            // 右下角收窄 —— 指向说话的人，是最省事的"方向暗示"
                            bottomEnd = PaRadius.xs,
                        ),
                    )
                    .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
            ) {
                Text(
                    text = task.prompt,
                    style = PaType.body,
                    color = PaColor.TextPrimary,
                )
            }
        }

        // ── 执行中：只显示一行状态，不要卡片 ──────────────────
        AnimatedVisibility(
            visible = task.state == TaskState.Running,
            enter = fadeIn(PaMotion.fast()) + expandVertically(PaMotion.standard()),
            exit = fadeOut(PaMotion.fast()) + shrinkVertically(PaMotion.standard()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = PaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠️ 不加进度条：纯对话的"进度"没有意义（只有一轮），
                //    画一条永远不会动的条反而让人以为卡住了。
                Text(
                    text = task.note,
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
                Spacer(Modifier.width(PaSpace.xs))
                Text(
                    text = "中止",
                    style = PaType.caption,
                    color = PaColor.Warning,
                    modifier = Modifier.clickable(onClick = onAbort),
                )
            }
        }

        // ── AI：左对齐，**无容器** ────────────────────────────
        val reply = task.note
        val showReply = (task.state == TaskState.Done || task.state == TaskState.Failed) &&
            reply.isNotBlank()

        AnimatedVisibility(
            visible = showReply,
            enter = fadeIn(PaMotion.standard()) + expandVertically(PaMotion.standard()),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = PaSpace.xs)) {
                Text(
                    text = SimpleMarkdown.render(reply),
                    style = PaType.body,
                    color = if (task.state == TaskState.Failed) {
                        PaColor.Danger
                    } else {
                        // ★ 正文用主色、**不加背景** —— 这一条是 ChatGPT 观感的核心：
                        //   AI 说的话是"文章"，不需要被框起来。
                        PaColor.TextPrimary
                    },
                    // 留一点右侧空白，避免长行顶到屏幕边
                    modifier = Modifier.fillMaxWidth(0.96f),
                )
            }
        }

        // ── 失败时的补充说明（只在没有正文时兜底）────────────
        AnimatedVisibility(
            visible = task.state == TaskState.Aborted &&
                !task.abortReason.isNullOrBlank(),
            enter = fadeIn(PaMotion.fast()),
        ) {
            Text(
                text = task.abortReason ?: "",
                style = PaType.caption,
                color = PaColor.Warning,
                modifier = Modifier.padding(top = PaSpace.xxs),
            )
        }
    }
}
