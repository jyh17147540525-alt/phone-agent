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
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
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
                        items(items = vm.tasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                onAbort = { vm.abort(task.id) },
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
private fun TaskCard(
    task: AgentTaskUi,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.prompt,
                style = PaType.headline,
                color = PaColor.TextPrimary,
                modifier = Modifier.weight(1f),
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

        // 中止/失败时的原因说明。把"为什么停"讲清楚 ——
        // 用户不会因为被中止而生气，只会因为不知道为什么被中止而生气
        //
        // ⚠️ 2026-10-02 修：这里原本**只判断了 `Aborted`**，而注释写的是
        //    "中止/失败"。于是失败时用户只看到"失败"两个字、看不到原因 ——
        //    注释与实现的差额，正好落在用户身上。
        //    **凡是要显示"为什么"的地方，条件必须覆盖所有终止态。**
        AnimatedVisibility(
            visible = task.state == TaskState.Aborted || task.state == TaskState.Failed,
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
) {
    companion object {
        /**
         * 默认轮次上限。
         *
         * 8 这个数字来自能耗估算：约 0.07% 电量/次（见《移动端Agent约束分析》§2.4）。
         * 它同时是 [AgentBudget] 的默认值 —— 改这里之前先确认能耗预算是否也改了。
         */
        const val DEFAULT_MAX_STEPS = 8
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
) {

    val tasks = mutableStateListOf<AgentTaskUi>()

    var draft by mutableStateOf("")
        private set

    fun onDraftChange(text: String) {
        draft = text
    }

    fun submit() {
        val prompt = draft.trim()
        if (prompt.isEmpty()) return

        val task = AgentTaskUi(id = "t${System.currentTimeMillis()}", prompt = prompt)
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
            update(taskId) { it.copy(note = "正在等模型回复…", perceptionTier = 0) }

            val result = try {
                kotlinx.coroutines.runBlocking {
                    chat!!.send(
                        listOf(
                            ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(prompt),
                        ),
                    )
                }
            } catch (e: Exception) {
                // 端口承诺不抛异常，但这里仍然兜一道 ——
                // 一个未捕获的异常会让任务永远停在"正在等模型回复…"
                ChatResult.Failed(e.message ?: e::class.simpleName ?: "未知错误")
            }

            when (result) {
                is ChatResult.Ok -> update(taskId) {
                    it.copy(state = TaskState.Done, step = 1, note = result.text, perceptionTier = 0)
                }

                is ChatResult.NoCredential -> {
                    Timber.w("对话失败：没有可用的默认凭据")
                    update(taskId) {
                        it.copy(
                            state = TaskState.Failed,
                            note = NO_KEY_HINT,
                            // ★ abortReason 要写**完整原因**，不是"未配置 Key"这种简写 ——
                            //   它是用户唯一能看到的那行字（note 在 Running 块里，失败时不显示）
                            abortReason = NO_KEY_HINT,
                        )
                    }
                }

                is ChatResult.Failed -> {
                    Timber.w("对话失败：%s", result.reason)
                    update(taskId) {
                        it.copy(
                            state = TaskState.Failed,
                            note = result.reason,
                            abortReason = result.reason,
                        )
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun update(id: String, transform: (AgentTaskUi) -> AgentTaskUi) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    companion object {
        /**
         * 系统提示词。
         *
         * ⚠️ 它**刻意不承诺任何它做不到的事**。这一版没有读屏、没有点击、
         *    没有工具调用 —— 如果提示词里写"你可以操作手机"，
         *    模型会答应下来，而用户等到的是一次失败。
         *    **一个说"我做不到"的助理，比一个答应后失败的助理好得多。**
         */
        const val SYSTEM_PROMPT: String =
            "你是一个手机上的助理。当前版本你**只能对话**，还不能操作手机" +
                "（不能读屏、不能点击、不能读写文件）。" +
                "如果用户要求你操作手机，请如实说明你现在做不到，并说明这还在开发中。"

        /** 没有 Key 时给用户看的那句话。**note 与 abortReason 共用一份**，避免两处文案漂移。 */
        const val NO_KEY_HINT: String =
            "还没有可用的 API Key。请到「模型」页面添加一个，再回来试。"
    }
}
