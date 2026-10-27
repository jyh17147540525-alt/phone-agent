package com.pocketagent.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.pocketagent.agentlogic.PhoneTask
import com.pocketagent.core.database.entity.SessionEntity
import com.pocketagent.data.ConversationStore
import com.pocketagent.memorylogic.Turn
import com.pocketagent.memorylogic.TurnRole
import com.pocketagent.provider.api.ChatMessage
import timber.log.Timber
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import androidx.compose.material.icons.filled.Menu
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.glassEdge
import kotlinx.coroutines.delay

/**
 * 任务页 —— 移动端 agent 的主界面。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ M0 阶段的诚实说明
 * ═══════════════════════════════════════════════════════════════
 *
 * **agent 尚未接入。** 本文件的职责是**把「移动端专属」的设计意图可视化**，
 * 让评审者能看见 AgentBudget / 感知档位这些概念在界面上长什么样，
 * 而不是等实现完了才发现"这个概念没法呈现给用户"。
 *
 * 所以这里的任务推进是**本地模拟**的（见 [TasksViewModel]），
 * 数据不落盘、不联网、不出设备。接入真实 agent 时，
 * 把 [TasksViewModel] 的实现换成对 agent 层的订阅即可，UI 层不用动。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么界面上要显示「轮次」和「感知档位」
 * ═══════════════════════════════════════════════════════════════
 *
 * 这两个数字是移动端 agent 的成本来源（见《移动端Agent约束分析》§2.4）：
 * 轮次 × 单轮成本 ≈ 总能耗。**用户看不见成本，就不会理解为什么任务会被中止。**
 *
 * 与其在超限时弹一句"任务已中止"，不如**全程显示进度** ——
 * 用户看到 6/8 时会预期"快到头了"，看到中止时就不会觉得是 bug。
 * 这是把架构约束翻译成产品语言。
 */
@Composable
fun TasksScreen(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    /**
     * 任务状态持有者，**由外壳注入**。
     *
     * ⚠️ 为什么不能在这里 `remember { TasksViewModel() }`：
     *
     * [TasksViewModel] 刻意不继承 `ViewModel`（它会在接入 agent 时整体重写），
     * 所以它的生命周期就是**承载它的那次组合**。而本页面挂在 NavHost 的
     * destination 上 —— 底部导航切走时该 destination 会被移出组合，
     * `remember` 的值随之销毁。后果是：
     *   · 用户输入一半的任务描述没了
     *   · 正在跑的任务从列表里消失，但后台那个模拟线程还在跑
     *
     * 所以实例由 [com.pocketagent.ui.shell.AppShell] 持有并传进来，
     * 生命周期提升到整个应用。默认值保留只是为了 Compose Preview 方便。
     */
    /**
     * 对话端口。null = 还没接线 —— 那时界面会**如实说"还没接入模型"**，
     * 而不是演一段假的执行（见 [TasksViewModel] 的注释）。
     */
    chat: ChatPort? = null,
    viewModel: TasksViewModel = remember(chat) { TasksViewModel(chat) },
) {
    val vm = viewModel

    PaScreen(
        title = "任务",
        modifier = modifier,
        subtitle = if (vm.tasks.isEmpty()) null else "${vm.tasks.size} 个任务",
        // ★ 抽屉入口。用 leadingIcon 而不是 onBack ——
        //   摆一个返回箭头（←）当菜单用，用户会以为点它返回上一页。
        leadingIcon = Icons.Default.Menu,
        leadingContentDescription = "历史对话",
        onLeadingClick = vm::openDrawer,
        actions = {
            PaIconButton(
                icon = Icons.Default.Tune,
                onClick = onOpenSettings,
                contentDescription = "设置",
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            Box(modifier = Modifier.weight(1f)) {
                if (vm.tasks.isEmpty()) {
                    PaEmptyState(
                        title = "还没有任务",
                        description = "说一句话，我来替你操作手机。\n所有操作都在本机完成，数据不出设备。",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = PaSpace.screenH,
                            end = PaSpace.screenH,
                            top = PaSpace.s,
                            bottom = PaSpace.m,
                        ),
                        verticalArrangement = Arrangement.spacedBy(PaSpace.s),
                    ) {
                        // ★★ 2026-10-27：改成对话流。
                        //   纯对话走气泡（用户右、AI 左、无边框），
                        //   真的办事才用横穿卡片（带进度与中止）。
                        //   判据是 `task.isAgentRun`，由 `ChatPort` 的意图判定决定。
                        item {
                            ConversationList(
                                tasks = vm.tasks,
                                onAbort = { vm.abort(it) },
                            )
                        }
                    }
                }
            }

            TaskComposer(
                value = vm.draft,
                onValueChange = vm::onDraftChange,
                onSend = vm::submit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PaSpace.screenH)
                    .padding(bottom = PaSpace.s),
            )
        }
    }
}

/**
 * 任务卡片。
 *
 * 三种视觉状态：
 *  - **执行中**：显示轮次进度条 + 当前感知档位 + 中止按钮
 *  - **完成**：收起为一行，带绿色勾
 *  - **已中止**：带原因说明（"超出轮次预算"），而不是笼统的"失败"
 */
@Composable
internal fun TaskCard(
    task: AgentTaskUi,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-25 视觉层次重构（用户原话："全是字"）
        // ══════════════════════════════════════════════════════════
        //
        // 改之前：用户的话和 AI 的回答**视觉权重完全一样** ——
        // 同样的大字号、同样的颜色、都在左边。一屏下来分不清
        // "哪句是我说的、哪句是它说的"，这才是"全是字"的真正原因
        // （不是字多，是**没有层次**）。
        //
        // 现在：
        //  - 用户的话 = **小标签**，右对齐、次要色。它是"背景信息"，
        //    用户自己知道刚说过什么，不需要大字号重复给他看。
        //  - AI 的回答 = **主体**，左对齐、主色、可读字号。
        //  - 状态徽章贴着用户那一行（它描述的是"这一轮"的状态）。
        //
        // 这个布局参照了主流 AI 对话应用（ChatGPT / Claude）的通用做法：
        // **用户的输入是引用，助手的输出是正文。**
        Row(verticalAlignment = Alignment.Top) {
            Spacer(Modifier.weight(1f))
            Text(
                text = task.prompt,
                style = PaType.caption,
                color = PaColor.TextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(4f, fill = false)
                    .padding(bottom = PaSpace.xs),
            )
            Spacer(Modifier.width(PaSpace.xs))
            TaskStateBadge(task.state)
        }

        // 执行中的细节区。用 AnimatedVisibility 让它在状态变化时
        // 平滑展开/收起，而不是"啪"地出现
        AnimatedVisibility(
            visible = task.state == TaskState.Running,
            enter = expandVertically(animationSpec = PaMotion.standard()) + fadeIn(),
            exit = shrinkVertically(animationSpec = PaMotion.standard()) + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = PaSpace.s)) {
                Spacer(Modifier.height(PaSpace.xs))

                StepProgressBar(step = task.step, maxSteps = task.maxSteps)

                Spacer(Modifier.height(PaSpace.s))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaBadge(
                        text = perceptionTierLabel(task.perceptionTier),
                        tone = if (task.perceptionTier == 0) PaBadgeTone.Success else PaBadgeTone.Neutral,
                    )
                    Spacer(Modifier.width(PaSpace.xs))
                    AnimatedContent(
                        targetState = task.note,
                        transitionSpec = {
                            fadeIn(PaMotion.standard()) togetherWith fadeOut(PaMotion.fast())
                        },
                        label = "taskNote",
                    ) { note ->
                        Text(
                            text = note,
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(PaSpace.s))

                PaButton(
                    text = "中止",
                    onClick = onAbort,
                    style = PaButtonStyle.Text,
                )
            }
        }

        // ── 完成后的模型答复 ─────────────────────────────────────
        //
        // ★★ 这是一处"为旧世界设计的界面"漏掉的部分（内测用户 2026-10-12 报告）。
        //
        //    `note` 一直只在下面的 Running 块里渲染。以前这样没问题：
        //    那时 Done 意味着"没事可说了"（卡片收起为一行 + 绿勾）。
        //    但现在 Done 意味着**模型的答复就在 note 里** ——
        //    也就是说，用户最想看的那句话，恰好在任务完成的那一刻
        //    被收起动画一起带走了。表现是：卡片显示「hi ✓ 已完成」，然后……什么都没有。
        //
        // ⚠️ 判据是 `note.isNotBlank()` 而不是 state 本身：
        //    Done 但没话说的任务（理论上不该有）不该凭空多一块空卡片。
        // ⚠️ 用 body 而不是 Running 里的 caption：这是**结论**，
        //    不是过程中的进度注脚 —— 视觉权重应该对得起它的地位。
        AnimatedVisibility(
            // ★ 2026-10-25：原来只认 `Done`，于是**失败时正文整段不显示** ——
            //   而失败恰恰是用户最需要看正文的时候（里面写着为什么失败、
            //   下一步怎么做）。用户看到一张只有红徽章的空卡片，比什么都不显示更糟。
            //
            //   ⚠️ `Aborted` 不算在内：中止时 `note` 往往是"正在等模型回复…"，
            //      显示它等于把一句过期的进度当成结论。
            visible = (task.state == TaskState.Done || task.state == TaskState.Failed) &&
                task.note.isNotBlank(),
            enter = expandVertically(animationSpec = PaMotion.standard()) + fadeIn(),
            exit = shrinkVertically(animationSpec = PaMotion.standard()) + fadeOut(),
        ) {
            AnimatedContent(
                targetState = task.note,
                transitionSpec = {
                    fadeIn(PaMotion.standard()) togetherWith fadeOut(PaMotion.fast())
                },
                label = "taskReply",
            ) { note ->
                Text(
                    // ★ 2026-10-25：模型输出带 Markdown 标记（`**35**`），
                    //   原来原样显示成星号，看起来像"没渲染好的机器输出"。
                    //   这里过一层极简渲染（只认加粗与行内代码，见 SimpleMarkdown）。
                    text = SimpleMarkdown.render(note),
                    style = PaType.body,
                    color = PaColor.TextPrimary,
                    modifier = Modifier.padding(top = PaSpace.s),
                )
            }
        }

        // 中止/失败时的原因说明。把"为什么停"讲清楚 ——
        // 用户不会因为被中止而生气，只会因为不知道为什么被中止而生气
        //
        // ⚠️ 2026-10-02 修：这里原本**只判断了 `Aborted`**，而注释写的是
        //    "中止/失败"。于是失败时用户只看到"失败"两个字、看不到原因 ——
        //    注释与实现的差额，正好落在用户身上。
        //    **凡是要显示"为什么"的地方，条件必须覆盖所有终止态。**
        AnimatedVisibility(
            // ★ 只在**没有正文**时才用 abortReason 兜底 ——
            //   否则同一句原因会在正文下面再重复一遍（2026-10-25 真机见过）。
            visible = (task.state == TaskState.Aborted || task.state == TaskState.Failed) &&
                !task.abortReason.isNullOrBlank() &&
                task.abortReason != task.note,
            enter = expandVertically(animationSpec = PaMotion.standard()) + fadeIn(),
            exit = shrinkVertically(animationSpec = PaMotion.standard()) + fadeOut(),
        ) {
            Text(
                text = task.abortReason ?: "任务已中止",
                style = PaType.caption,
                color = PaColor.Warning,
                modifier = Modifier.padding(top = PaSpace.xs),
            )
        }
    }
}

/**
 * 轮次进度条。
 *
 * 用**分段**而不是连续条 —— 因为轮次是离散的整数，
 * 连续条会让人以为是百分比（"才走了 37%"），
 * 分段条让人数得清（"8 段里亮了 3 段"）。
 */
@Composable
private fun StepProgressBar(
    step: Int,
    maxSteps: Int,
    modifier: Modifier = Modifier,
) {
    val fraction by animateFloatAsState(
        targetValue = if (maxSteps <= 0) 0f else step.toFloat() / maxSteps,
        animationSpec = PaMotion.standard(),
        label = "stepProgress",
    )

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "轮次",
                style = PaType.label,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$step / $maxSteps",
                style = PaType.label,
                color = if (step >= maxSteps) PaColor.Warning else PaColor.TextSecondary,
            )
        }
        Spacer(Modifier.height(PaSpace.xxs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(PaRadius.pill))
                .background(PaColor.SurfaceHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .clip(RoundedCornerShape(PaRadius.pill))
                    .background(if (step >= maxSteps) PaColor.Warning else PaColor.Accent),
            )
        }
    }
}

@Composable
private fun TaskStateBadge(state: TaskState) {
    when (state) {
        TaskState.Running -> PaBadge(text = "执行中", tone = PaBadgeTone.Accent)
        TaskState.Done -> PaBadge(text = "已完成", tone = PaBadgeTone.Success)
        TaskState.Aborted -> PaBadge(text = "已中止", tone = PaBadgeTone.Warning)
        TaskState.Failed -> PaBadge(text = "失败", tone = PaBadgeTone.Danger)
    }
}

/**
 * 输入区。
 *
 * 用 [BasicTextField] 而不是 Material 的 `OutlinedTextField` ——
 * 后者自带一套完整的 Material 装饰（浮动标签、指示线、容器色），
 * 要改成玻璃风格等于把所有东西都覆盖一遍，不如直接用基础组件。
 */
@Composable
private fun TaskComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(PaRadius.m)
    val canSend = value.isNotBlank()

    Row(
        modifier = modifier
            .clip(shape)
            .background(PaColor.GlassTintStrong)
            .glassEdge(shape)
            .padding(start = PaSpace.m, end = PaSpace.xs, top = PaSpace.xs, bottom = PaSpace.xs)
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                fontSize = PaType.body.fontSize,
                lineHeight = PaType.body.lineHeight,
                color = PaColor.TextPrimary,
            ),
            cursorBrush = SolidColor(PaColor.Accent),
            maxLines = 4,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = "说出你想做的事…",
                            style = PaType.body,
                            color = PaColor.TextTertiary,
                        )
                    }
                    inner()
                }
            },
        )

        Spacer(Modifier.width(PaSpace.xs))

        SendButton(enabled = canSend, onClick = onSend)
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    // 可发送时图标放大一点点 —— 一个几乎察觉不到但能感觉到的"就绪"信号
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.92f,
        animationSpec = PaMotion.standard(),
        label = "sendButtonScale",
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(if (enabled) PaColor.Accent else PaColor.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "发送",
            tint = if (enabled) Color(0xFF06121F) else PaColor.TextDisabled,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  状态模型
// ═══════════════════════════════════════════════════════════════

enum class TaskState { Running, Done, Aborted, Failed }

/**
 * 侧边栏列表项。
 *
 * ⚠️ 刻意**不带 `activeLeafTurnId` / `titleSource` 这些内部字段** ——
 *    界面一旦能读到它们，就会开始"顺手用一下"，而数据层的重构立刻变成破坏性变更。
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val lastMessageAt: Long,
    val turnCount: Int,
    val pinned: Boolean,
    val isCurrent: Boolean,
)

/**
 * 实体 → 侧边栏列表项。
 *
 * ⚠️ 这是**唯一**允许读 `SessionEntity` 的地方，而且只读展示需要的字段 ——
 *    界面其它地方一律用 [SessionSummary]。
 */
private fun SessionEntity.toSummary(isCurrent: Boolean) = SessionSummary(
    id = id,
    title = title,
    lastMessageAt = lastMessageAt,
    turnCount = turnCount,
    pinned = pinned,
    isCurrent = isCurrent,
)

/**
 * 从库里恢复一条回复时，判断它当时**成功还是失败**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不能一律标"已完成"
 * ═══════════════════════════════════════════════════════════════
 *
 * 2026-10-25 真机：一条内容是 `模型调用失败：network unreachable`
 * 的历史卡片，挂着**绿色的「已完成」徽章**。
 *
 * 那个绿勾在说"事情办成了"，而正文在说"根本没连上模型" ——
 * 用户会得出"它失败了但系统认为成功了"这种结论，
 * 从而**不再信任所有状态标记**（包括那些对的）。
 *
 * ⇒ 恢复时按内容判断。判据刻意简单（认 `ChatPort.explain`
 *   与 `explainRun` 产出的那几种前缀），因为回复落库的是
 *   **已经翻好的用户文案**，不是原始异常。
 */
private fun Turn.toRecoveredState(): TaskState {
    val t = text.trim()
    val failed = t.startsWith("联系模型") ||
        t.startsWith("网络连不上") ||
        t.startsWith("这个模型的额度") ||
        t.startsWith("这个模型的 API Key") ||
        t.startsWith("模型名不对") ||
        t.startsWith("模型返回了空内容") ||
        t.startsWith("联系模型的时候出错了")
    return if (failed) TaskState.Failed else TaskState.Done
}

data class AgentTaskUi(
    val id: String,
    val prompt: String,
    val state: TaskState = TaskState.Running,
    val step: Int = 0,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** 当前使用的感知档位（0 = 不感知） */
    val perceptionTier: Int = 1,
    val note: String = "正在分析…",
    val abortReason: String? = null,
    /**
     * 这一轮**真的动了手**吗（跑过 agent 循环、调用过工具）。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 2026-10-27：界面的两种形态靠它分流
     * ═══════════════════════════════════════════════════════════
     *
     * 用户要求"像 ChatGPT/Claude 那样对话"，但**执行类任务仍要保留大卡片**
     * （那里有进度、步骤、中止，是必需的）。
     *
     * 所以判据不能是"任务状态"，只能是"**这一轮到底动没动手**"：
     *  - `false`（纯对话）→ 气泡流，用户右、AI 左，无边框
     *  - `true`（真办事）→ 横穿卡片，带进度与中止
     *
     * ⚠️ 这个值由 `ChatPort` 的意图判定结果决定 —— 它是唯一知道
     *    "这一轮挂没挂工具"的地方。界面自己猜不出来。
     */
    val isAgentRun: Boolean = false,

    /**
     * 这一轮附带的一句系统提示（当前只有一种：用户要求了成熟内容但没开启）。
     *
     * ⚠️ 与 `note` 分开而不是拼进去：`note` 是**模型的回答**，
     *    而提示是**应用要对用户说的话**。拼在一起会让用户以为那句话
     *    是角色说的 —— 而角色不该替产品解释开关在哪。
     */
    val hint: String? = null,
) {
    companion object {
        /**
         * 默认轮次上限 —— **从循环那边取，不要在这里另写一个数**。
         *
         * ═══════════════════════════════════════════════════════════
         *  ★ 2026-10-19 真机抓出的第三个 bug：进度条在骗人
         * ═══════════════════════════════════════════════════════════
         *
         * 这里原来硬编码 `8`，而实际循环用的是 `ChatPort.CHAT_MAX_STEPS`（16）。
         * 于是界面上写着「轮次 0 / 8」——**分母是假的**：
         *
         *  - 进度条走到 8/8 时任务并不会停（它还有 8 步额度）；
         *  - 用户看到"已经满了还在跑"，会以为界面卡死或计数坏了；
         *  - 而真到 16 步停下时，显示的是「8/8」——**看不出还剩多少**。
         *
         * 这类"显示的数字与实际用的数字不是同一个来源"的 bug，
         * 用户第一反应是"这软件有毛病"，而排查时会去看循环，
         * 因为循环是对的 —— **错误信息再次指向了无关的地方**。
         *
         * ⇒ 唯一事实来源是 `PhoneTask.DEFAULT_MAX_STEPS`。
         *   聊天那条链路会传入更大的 `CHAT_MAX_STEPS`，
         *   由 `AgentTaskUi.maxSteps` 在构造时显式带进来（见 `TasksScreen` 的调用点）。
         */
        const val DEFAULT_MAX_STEPS = PhoneTask.DEFAULT_MAX_STEPS

        /**
         * 每次发给模型的**历史轮数上限**。
         *
         * ⚠️ 它是**成本闸**，不只是体验参数：上下文越长，每次请求越贵 ——
         *    而花的是用户自己的钱。20 轮足够"接着聊"，
         *    更早的内容应该由 L1/L2 记忆（设计稿 §4）来承载，而不是靠堆上下文。
         */
        const val HISTORY_LIMIT = 20

        /**
         * 侧边栏一次列多少条。
         *
         * ⚠️ 刻意**不做分页**（v1）：个人助理的会话量级是"千"，不是"百万"，
         *    一次列 200 条已经远超用户会翻的量。真到了翻不动的量级，
         *    该上的是**搜索**而不是分页 —— 人不会"翻到第 7 页"，他会直接搜。
         */
        const val SESSION_LIST_LIMIT = 200
    }
}

private fun perceptionTierLabel(tier: Int): String = when (tier) {
    0 -> "未读取屏幕"
    1 -> "无障碍树"
    2 -> "树 + 局部截图"
    3 -> "截图（降质）"
    else -> "截图（原质）"
}

/**
 * 任务状态持有者。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-02：从「模拟执行」改成「真的对话」
 * ═══════════════════════════════════════════════════════════════
 *
 * 之前的版本会演一段执行过程（"正在分析当前页面…" → "定位目标控件…" →
 * 每 900ms 一步 → 走满必然 `Aborted`）。那是为了让 UI 动效可被评审的
 * **演示**，但界面上**没有任何地方说明它是假的** —— 于是用户会以为
 * "这个 app 连说句你好都要读屏、还总是失败"。
 *
 * ⇒ 现在改成：**有模型就真对话，没有模型就如实说"还没接入"**。
 *    两种情况都不再演。
 *
 * ⚠️ 仍然**不是**完整的 agent（不读屏、不点击、不调工具）。
 *    这里只做"对话"这一件事 —— 把它做真，比把一串假的做得像真的重要。
 */
class TasksViewModel(
    /**
     * 对话端口。null = 还没接线。
     *
     * ⚠️ 可空而不是"必须传"：`TasksScreen` 的预览与测试里没有 `AppContainer`，
     *    强行必传会让预览写不出来。而 null 的分支**有明确行为**
     *    （如实说"还没接入"），不是静默跳过。
     */
    private val chat: ChatPort? = null,

    /**
     * 会话仓储的**提供者**（不是实例）。
     *
     * ⚠️ 为什么必须是 `() ->`：库是**懒开**的（开库要碰 Keystore，冷启动路径上不该付这个代价），
     *    所以 `AppContainer.conversationRepository()` 在本类被构造时**大概率还是 null**。
     *    而本类由 `AppShell` 用 `remember` 持有 —— 只构造一次。
     *    ⇒ 存实例 = 永远拿到 null。
     *
     *    这正是项目里既有的那条纪律：**生命周期比调用方短的东西，不能存引用**
     *    （`AccessibilityActionExecutor` 对无障碍服务用的也是 `() -> Service?`）。
     *
     * ⚠️ 是 `suspend` 的：首次调用会触发开库，那是个慢操作。
     */
    private val conversations: (suspend () -> ConversationStore?)? = null,

    /**
     * 成熟内容授权的读取口。
     *
     * ★ 用 `() ->`，理由与 [conversations] 相同：`AppContainer`
     *   是唯一事实来源，用户在设置页改完之后这里必须读到新值 ——
     *   存一个 `Permission` 快照会永远停在构造时那一刻。
     *
     * ⚠️ 默认值是"全关"。预览里没有容器，默认值让预览**不需要额外接线**
     *    也不会因此崩掉。
     */
    private val maturePermission: () -> com.pocketagent.legacy.MatureContentGate.Permission =
        { com.pocketagent.legacy.MatureContentGate.Permission() },
) {

    val tasks = mutableStateListOf<AgentTaskUi>()

    /**
     * 这一轮要不要附一句"开关在哪"的提示。
     *
     * ★ 只在**真的要求了**且**没开**的时候返回非空 ——
     *   开了还提示一遍，等于每次亲密话题都提醒用户"这是被允许的"，
     *   那句话本身就是破坏气氛的。
     *
     * ⚠️ 提示语里**不提"限制""驳回"**：用户没做错事，
     *    他只是没打开一个默认关闭的开关。
     */
    private fun matureHintFor(userText: String): String? {
        if (maturePermission().effective) return null
        if (!com.pocketagent.legacy.MatureContentGate.looksLikeAdultRequest(userText)) return null
        return "这类内容默认关着。想开着的话：设置 → 亲密与成人内容。"
    }

    // 同一时间只允许一个模型请求在飞。
    // 2026-10-26 真机：3 个请求并发 → 互相抢带宽 + 厂商限流 → 全都"卡住"。
    private val chatGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 当前会话 id。null = 还没定下来（库没开，或还没说过话）*/
    private var sessionId: String? = null

    // ── 侧边栏：会话列表 ────────────────────────────────────────

    /**
     * 侧边栏的列表。
     *
     * ⚠️ 只带界面需要的字段，**不把 `SessionEntity` 整个漏到 UI 层** ——
     *    那个类型里有 `activeLeafTurnId` 这种纯内部状态，
     *    漏出去之后界面就会开始"顺手读一下"，而数据层的重构立刻变成破坏性变更。
     */
    val sessions = mutableStateListOf<SessionSummary>()

    /** 当前会话 id。界面用它高亮列表里那一项 */
    var currentSessionId by mutableStateOf<String?>(null)
        private set

    /** 侧边栏是否打开。放在这里而不是 AppShell，是为了让「新对话」等操作能顺手关掉它 */
    var drawerOpen by mutableStateOf(false)
        private set

    fun openDrawer() { drawerOpen = true }
    fun closeDrawer() { drawerOpen = false }

    init {
        refreshSessions()
    }

    /**
     * 启动/切换后刷新列表。
     *
     * ★ 在这之前进程一死聊天记录就没了 —— 这是"软件不能正常使用"里最硬的一条。
     *
     * ⚠️ 拿不到仓储（库没开 / Keystore 失效）时**静默跳过**：
     *    这时列表本来就是空的，而用户还没做任何操作，
     *    弹一个"读不到历史"反而会让他以为数据丢了。
     *    （真正需要报错的是**写入**路径 —— 那里失败意味着这次对话不会被保存。）
     */
    fun refreshSessions(keyword: String? = null) {
        val provider = conversations ?: run {
            Timber.w("历史会话：拿不到 conversations provider，跳过刷新")
            return
        }
        Thread {
            // ★ 2026-10-25 真机事故后改：原来这两个 `runCatching{}.getOrNull()`
            //   把**所有**失败都咽掉了，于是"历史记录全没了"在日志里
            //   **一个字都没有** —— 排查时无法区分三个完全不同的原因：
            //     ① Keystore / SQLCipher 打不开库
            //     ② 库里真的没数据
            //     ③ 读出来了但界面没渲染
            //   ⇒ 现在失败一律进日志（仍然不弹给用户，用户还没做任何操作，
            //    弹窗只会让他以为数据丢了）。这符合项目那条纪律：
            //    **安静地少做一件事**是最难查的 bug 形态，日志必须留痕。
            val store = runCatching { kotlinx.coroutines.runBlocking { provider() } }
                .onFailure { Timber.e(it, "历史会话：打不开会话仓储（库/Keystore 失败）") }
                .getOrNull() ?: return@Thread

            val rows = runCatching {
                kotlinx.coroutines.runBlocking {
                    val list = if (keyword.isNullOrBlank()) {
                        store.recentSessions(AgentTaskUi.SESSION_LIST_LIMIT)
                    } else {
                        store.searchSessions(keyword, AgentTaskUi.SESSION_LIST_LIMIT)
                    }
                    val cur = sessionId ?: store.currentSession().also { sessionId = it.id }.id
                    Timber.i("历史会话：读到 %d 个会话，当前=%s", list.size, cur)
                    // ★ 过滤掉**一条消息都没有**的会话（除非它就是当前那个）。
                    //
                    //   2026-10-25：侧边栏曾经堆着"今天 · 0 条"这类空壳 ——
                    //   它们每次冷启动都新建一个，越积越多，把真正的历史挤下去。
                    //   用户看到一列"0 条"会以为数据坏了。
                    //
                    //   ⚠️ 保留当前会话：用户正要在这个会话里说话，
                    //      列表里没有它会让"当前"这个高亮无对象可标。
                    list
                        .filter { it.turnCount > 0 || it.id == cur }
                        .map { it.toSummary(isCurrent = it.id == cur) }
                }
            }.onFailure { Timber.e(it, "历史会话：查询会话列表失败") }
                .getOrNull() ?: return@Thread

            sessions.clear()
            sessions.addAll(rows)

            // ★ 首次进入时把当前会话的历史读回来。
            //    ⚠️ 只在"还没加载过"时做 —— 每次刷新都重载会让
            //      正在进行的对话被覆盖成库里的旧状态。
            if (!historyLoaded) {
                historyLoaded = true
                loadPairs(store, sessionId)
            }
        }.start()
    }

    /** 切到另一个会话。 */
    fun switchTo(id: String) {
        if (id == sessionId) {
            closeDrawer()
            return
        }
        sessionId = id
        currentSessionId = id
        tasks.clear()
        drawerOpen = false
        val provider = conversations ?: return
        Thread {
            val store = runCatching { kotlinx.coroutines.runBlocking { provider() } }
                .getOrNull() ?: return@Thread
            loadPairs(store, id)
            refreshSessions()
        }.start()
    }

    /** 「新对话」。⚠️ 必须**真的**开一个新的，不能复用空闲超时那条路径 ——
     *  用户点这个按钮时明确表达了"上一段结束了"。 */
    fun startNewSession() {
        val provider = conversations ?: return
        drawerOpen = false
        Thread {
            val store = runCatching { kotlinx.coroutines.runBlocking { provider() } }
                .getOrNull() ?: return@Thread
            val s = runCatching { kotlinx.coroutines.runBlocking { store.newSession() } }
                .getOrNull() ?: return@Thread
            sessionId = s.id
            currentSessionId = s.id
            historyLoaded = true
            tasks.clear()
            refreshSessions()
        }.start()
    }

    fun renameSession(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val provider = conversations ?: return
        Thread {
            val store = runCatching { kotlinx.coroutines.runBlocking { provider() } }
                .getOrNull() ?: return@Thread
            runCatching { kotlinx.coroutines.runBlocking { store.renameSession(id, clean) } }
            refreshSessions()
        }.start()
    }

    /**
     * 删会话。
     *
     * ⚠️ 删的是**当前会话**时，要顺手切走 —— 否则界面会停在一个已经不存在的会话上，
     *    而下一次发言会创建一个新的会话 id，用户会觉得"我删了它却还在"。
     */
    fun deleteSession(id: String) {
        val provider = conversations ?: return
        Thread {
            val store = runCatching { kotlinx.coroutines.runBlocking { provider() } }
                .getOrNull() ?: return@Thread
            runCatching { kotlinx.coroutines.runBlocking { store.deleteSession(id) } }
            if (id == sessionId) {
                sessionId = null
                currentSessionId = null
                historyLoaded = false
                tasks.clear()
            }
            refreshSessions()
        }.start()
    }

    private var historyLoaded = false

    /** 把某个会话的当前路径读成界面卡片。 */
    private fun loadPairs(store: ConversationStore, sid: String?) {
        if (sid == null) {
            Timber.w("历史消息：sessionId 为空，跳过恢复")
            return
        }
        val pairs = runCatching {
            kotlinx.coroutines.runBlocking { store.restorePairs(sid) }
        }
            .onFailure { Timber.e(it, "历史消息：restorePairs 失败 session=%s", sid) }
            .getOrNull() ?: return
        Timber.i("历史消息：会话 %s 恢复 %d 轮（插入前 tasks=%d）", sid, pairs.size, tasks.size)

        // ⚠️ 直接改 `tasks`，不 post 到主线程 —— 与既有的 `update()` 一致
        //    （它也是在后台线程里改这个列表）。改成 post 会让两种写法并存，
        //    而"到底哪个线程在改它"变成需要读两处才能回答的问题。
        // 倒序插入：`tasks` 是**新的在前**（`submit` 用的是 `add(0, ...)`）
        pairs.asReversed().forEach { pair ->
            // ⚠️ `pair.prompt` 是**跨模块**属性，Kotlin 不对它做智能转换 ——
            //    必须取到局部变量再用（否则 `pair.prompt.id` 编译不过：
            //    "Smart cast to 'Turn' is impossible"）。
            val promptTurn = pair.prompt ?: run {
                // ★★ 2026-10-25：这里原本直接 `return@forEach` 跳过 ——
                //    而真机上出现过"只有 assistant 回复、没有用户提问"的孤儿数据
                //    （成因见 `runChat` 里那段长注释：两次会话解析给出了不同的 id）。
                //    跳过它的后果是**历史整片不显示**，用户以为数据丢了。
                //
                //   ⇒ 孤儿回复也要显示出来。它确实发生过，用户有权看到
                //     "我当时问过什么、它答了什么"。没有提问就如实标出来，
                //     比假装这条不存在更接近事实。
                val reply = pair.reply ?: return@forEach
                Timber.w("历史消息：孤儿回复 %s（无对应提问），仍展示", reply.id)
                tasks.add(
                    0,
                    AgentTaskUi(
                        id = reply.id,
                        prompt = "（这一轮的提问没有保存下来）",
                        // ⚠️ 不能一律标 Done（绿勾）：回复里带着错误说明时
                        //    标成"已完成"就是在骗人 —— 用户会以为事情办成了。
                        state = reply.toRecoveredState(),
                        step = 1,
                        note = reply.text,
                        perceptionTier = 0,
                    ),
                )
                return@forEach
            }
            val replyTurn = pair.reply
            tasks.add(
                0,
                AgentTaskUi(
                    id = promptTurn.id,
                    prompt = promptTurn.text,
                    state = when {
                        replyTurn == null -> TaskState.Running
                        else -> replyTurn.toRecoveredState()
                    },
                    step = if (replyTurn != null) 1 else 0,
                    note = replyTurn?.text ?: "正在等模型回复…",
                    perceptionTier = 0,
                ),
            )
        }
    }

    var draft by mutableStateOf("")
        private set

    fun onDraftChange(text: String) {
        draft = text
    }

    fun submit() {
        val prompt = draft.trim()
        if (prompt.isEmpty()) return

        // ★ 分母用**聊天链路的真实额度**，不是 `AgentTaskUi` 的默认值。
        //   2026-10-19 真机上这里显示「0 / 8」而循环实际给的是 16 ——
        //   分母来自另一个数，于是进度条既会"满了还在跑"，也看不出还剩多少。
        //   ⇒ 同一个数字只允许有一个来源（`ChatPort.CHAT_MAX_STEPS`）。
        val task = AgentTaskUi(
            id = "t${System.currentTimeMillis()}",
            prompt = prompt,
            maxSteps = CHAT_MAX_STEPS,
        )
        tasks.add(0, task)
        draft = ""

        if (chat == null) {
            // ★ 如实说明，而不是演一段假的执行
            update(task.id) {
                it.copy(
                    state = TaskState.Failed,
                    perceptionTier = 0,
                    note = "还没有接入模型。请到「模型」页面配置一个 API Key。",
                    abortReason = "未接入模型",
                )
            }
            return
        }

        runChat(task.id, prompt)
    }

    fun abort(id: String) {
        update(id) { it.copy(state = TaskState.Aborted, abortReason = "已由你中止") }
        // ⚠️ 中止必须**同时释放闸门**。
        //    否则：用户中止了卡住的请求 → 该请求的线程可能仍在网络里等 →
        //    闸门不释放 → 后面所有消息永远"排队中"直到 3 分钟超时。
        //    那会把"能中止"变成"中止了但更糟"。
        chatGate.set(false)
    }

    /**
     * 真发一轮对话。
     *
     * 用 `Thread` 而不是协程，是为了不引入 `viewModelScope` ——
     * 本类刻意不继承 `ViewModel`。`runBlocking` 在这里是安全的：
     * 它跑在一个守护线程上，不阻塞主线程。
     */
    private fun runChat(taskId: String, prompt: String) {
        Thread {
            // 串行闸门：前一个请求没结束，这一句就排队并如实显示。
            // 用自旋而非锁：等的是网络请求（通常几秒），且漏解锁不会永久卡死。
            if (!chatGate.compareAndSet(false, true)) {
                update(taskId) { it.copy(note = "排队中（前一句还没答完）…") }
                var waited = 0L
                while (!chatGate.compareAndSet(false, true)) {
                    Thread.sleep(120)
                    waited += 120
                    if (waited > 180_000L) {
                        update(taskId) {
                            it.copy(
                                state = TaskState.Failed,
                                note = "排队超时：前面那个请求一直没结束，可以先去中止它。",
                            )
                        }
                        return@Thread
                    }
                }
            }
            try {
                update(taskId) { it.copy(note = "正在等模型回复…", perceptionTier = 0) }

            // ★ 流式：第一片正文到达时**替换**掉"正在等模型回复…"，之后追加。
            //   用标记而不是"判断 note 等不等于那句话" —— 后者在流式
            //   过程中会误判（第二片的 note 已经不是那句话了，逻辑正确；
            //   但一旦模型的第一片正好是同样几个字，就会重复清空）。
            val streamStarted = java.util.concurrent.atomic.AtomicBoolean(false)

            val result = try {
                kotlinx.coroutines.runBlocking {
                    // ── ① 把用户这一句落库 ──────────────────────────
                    //
                    // ⚠️ **先落库再发请求**：反过来的话，请求成功但落库失败，
                    //    用户会看到回复、而下次打开它没了 —— 那比"这次没回复"更难解释。
                    val store = conversations?.let { runCatching { it() }.getOrNull() }
                    val sid = store?.let { s ->
                        runCatching {
                            // ★ 写消息时用 `ensureSessionForNewMessage` 而**不是**
                            //   `currentSession`：前者才应用"空闲超过 30 分钟就切会话"，
                            //   后者现在的语义是"打开时展示最近的那个"。
                            //   ⚠️ 两者用反的后果：要么两天的事并成一段（L2 归纳变糊），
                            //   要么用户的历史被续到新会话里（看起来像丢了）。
                            //
                            // ★★ 2026-10-25 修的第二个 bug：这里原本写的是
                            //     `sessionId?.let { id -> s.currentPath(id).let { id } }`
                            //     —— `currentPath` 返回空列表时**依然非 null**，
                            //     于是"当前会话"被原样返回，哪怕它一条消息都没有。
                            //     后果：用户的话被写进空会话，而回复走的是
                            //     `ChatPort` 里另一次 `currentSession()` 解析 ——
                            //     两次解析给出**不同**的会话 id，于是库里出现
                            //     "有关系没有提问"的孤儿回复（`TurnPairing` 配不成对），
                            //     历史在界面上就渲染不出来。
                            //     ⇒ 必须**先确认这个 id 真的存在且可写**，否则重新解析。
                            val session = sessionId
                                ?.takeIf { id -> runCatching { s.currentPath(id).isNotEmpty() }.getOrDefault(false) }
                                ?: s.ensureSessionForNewMessage().id
                            sessionId = session
                            // ══════════════════════════════════════════════
                            //  ★★ 2026-10-26 修的上下文断裂 bug
                            // ══════════════════════════════════════════════
                            //
                            // `append` 原本**没传 `parentTurnId`**，于是每一轮
                            // 在库里都是一个**孤立节点**（parentTurnId = null）。
                            //
                            // 而 `currentPath` 是靠 `activeLeafTurnId` **回溯父链**
                            // 得到路径的 —— 孤立节点回溯一步就停，于是：
                            //   第一轮 append 后：路径 1 条 ✓（看着正常）
                            //   第二轮 append 后：路径**还是 1 条** ✗（第一轮丢了）
                            //
                            // 表现就是用户说的"模型只有单次记忆，没有上下文"。
                            // 它不是"没传上下文"，而是**上下文被自己弄丢了**。
                            //
                            // ⇒ 追加时必须把**当前叶子**作为父节点。
                            val parentId = s.currentPath(session).lastOrNull()?.id
                            s.append(session, TurnRole.USER, prompt, parentTurnId = parentId)
                            val pathNow = s.currentPath(session)
                            Timber.i(
                                "落库后：session=%s 父=%s 路径 %d 条",
                                session.take(8), parentId?.take(6) ?: "无", pathNow.size,
                            )
                            session
                        }.getOrNull()
                    }

                    // ── ② 取上下文 ──────────────────────────────────
                    //
                    // ★ 这是本次接线真正的收益：在此之前每次只发**当前这一句**，
                    //   助理不记得你三句话前说过什么。
                    //
                    // ⚠️ 取的是**当前路径**的最近 N 条，不是"按时间最近 N 条"——
                    //   后者会把用户已经放弃的分支也喂给模型。
                    // ⚠️ 不含刚写进去的这一句（`recentForModel` 会含），
                    //   所以下面要**去掉最后一条 USER** 再自己补上，
                    //   否则用户的话会在上下文里出现两次。
                    val history = if (store != null && sid != null) {
                        runCatching {
                            store.recentForModel(sid, AgentTaskUi.HISTORY_LIMIT)
                                .filter { it.role != TurnRole.TOOL }
                                .dropLastWhile { it.role == TurnRole.USER && it.text == prompt }
                                .map { turn ->
                                    when (turn.role) {
                                        TurnRole.USER -> ChatMessage.user(turn.text)
                                        else -> ChatMessage.assistant(turn.text)
                                    }
                                }
                        }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                    // ⚠️ 只给 USER / ASSISTANT —— **系统提示词由 `ChatPort` 注入**
                    //    （人格装配器的输出 + 能力边界声明）。
                    //    界面不该知道"助理是谁"，那是数据层的事。
                    //
                    // ★ `onStep` 把循环的真实进度写回界面。
                    //   在此之前进度条全程停在「0 / 8」（开始设 0、结束设 1，
                    //   中间从不更新），用户无法区分"在动"和"卡死"。
                    //   ⚠️ 回调可能来自后台线程，`update` 内部做了同步（见它的注释）。
                    chat!!.send(
                        history = history + ChatMessage.user(prompt),
                        onStep = { step, desc ->
                            update(taskId) {
                                it.copy(step = step, note = desc, perceptionTier = 1)
                            }
                        },
                        onDelta = { delta ->
                            // ⚠️ 只在 Running 时接字：用户已经中止/失败了，
                            //    后台仍在到的分片不能把界面重新写回"有内容"。
                            update(taskId) {
                                if (it.state != TaskState.Running) return@update it
                                val base = if (streamStarted.getAndSet(true)) it.note else ""
                                it.copy(note = base + delta, perceptionTier = 0)
                            }
                        },
                    )
                }
            } catch (e: Exception) {
                // 端口承诺不抛异常，但这里仍然兜一道 ——
                // 一个未捕获的异常会让任务永远停在"正在等模型回复…"
                ChatResult.Failed(e.message ?: e::class.simpleName ?: "未知错误")
            }

            when (result) {
                is ChatResult.Ok -> {
                    // ★ 把答复也落库 —— 否则下次打开只剩用户那句话，
                    //   看起来像是对着空气说话。
                    conversations?.let { provider ->
                        runCatching {
                            kotlinx.coroutines.runBlocking {
                                val s = provider()
                                val id = sessionId
                                if (s != null && id != null) {
                                    // ⚠️ 同样要接父链（见上面 USER 那条的长注释）：
                                    //    不接的话，回复和被回复的那句话在库里
                                    //    也是两个孤立节点 —— `TurnPairing` 会配不成对，
                                    //    历史渲染不出来（2026-10-25 见过这个症状）。
                                    val parentId = s.currentPath(id).lastOrNull()?.id
                                    s.append(id, TurnRole.ASSISTANT, result.text, parentTurnId = parentId)
                                }
                            }
                        }
                    }
                    update(taskId) {
                        it.copy(
                            state = TaskState.Done,
                            step = 1,
                            note = result.text,
                            perceptionTier = 0,
                            // ★ 这一轮到底动没动手 —— 界面靠它选形态
                            isAgentRun = result.isAgentRun,
                            hint = matureHintFor(it.prompt),
                        )
                    }
                }

                is ChatResult.NoCredential -> {
                    Timber.w("对话失败：没有可用的默认凭据")
                    update(taskId) {
                        it.copy(
                            state = TaskState.Failed,
                            note = NO_KEY_HINT,
                            // ★ 2026-10-25：`note` 与 `abortReason` **不再写同一句话**。
                            //    现在失败时正文（note）也会显示，两处同文会上下重复一遍，
                            //    看起来像渲染 bug。note 承载正文，abortReason 只在
                            //    "没有正文"时兜底。
                            abortReason = null,
                        )
                    }
                }

                is ChatResult.Failed -> {
                    Timber.w("对话失败：%s", result.reason)
                    update(taskId) {
                        it.copy(
                            state = TaskState.Failed,
                            // ★ 只写 `note`：它是正文（失败时会显示，见 TaskCard 那段注释）。
                            //   `abortReason` 留空，否则同一句话会显示两遍。
                            note = result.reason,
                            abortReason = null,
                        )
                    }
                }
            }
            } finally {
                chatGate.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun update(id: String, transform: (AgentTaskUi) -> AgentTaskUi) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    companion object {
        // ⚠️ 这里曾有一条 `SYSTEM_PROMPT` 常量，已移到
        //    `AssistantPromptBuilder.CAPABILITY_BOUNDARY` ——
        //    它现在与人格提示词一起由 `ChatPort` 注入。
        //
        //    搬走不是为了好看，是因为**这两段必须一起改**：
        //    哪天聊天页真的能操作手机了，分散在两处时漏改的那一处
        //    会让助理**继续对用户说「我做不到」** ——
        //    它不会报错，只是用户永远不知道功能已经有了。
        //
        //    它原本的纪律仍然成立、也仍然写在那边：
        //    **刻意不承诺任何它做不到的事** —— 若提示词里写"你可以操作手机"，
        //    模型会答应下来，而用户等到的是一次失败。
        //    **一个说「我做不到」的助理，比一个答应后失败的助理好得多。**

        /** 没有 Key 时给用户看的那句话。**note 与 abortReason 共用一份**，避免两处文案漂移。 */
        const val NO_KEY_HINT: String =
            "还没有可用的 API Key。请到「模型」页面添加一个，再回来试。"
    }
}
