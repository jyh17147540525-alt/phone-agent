package com.pocketagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelChoice
import com.pocketagent.modelrouter.ThinkingEffort
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTopBar
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.models.ThinkingSlider

/**
 * **后台 AI 的调度** —— 两个窗口，不多不少。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "不要一下子就在这个页面跳出一堆模型选择，这个页面就两个窗口。
 * >  在对应的窗口里，点击选择模型后，会出现抽屉的样子，
 * >  把用户的密钥里的模型一下子全显示出来，用户选择完后，可以点击收起，
 * >  还有把那个思考强度的拉条滑块也搬到这里来"
 *
 * ⇒ 三条落在实现里：
 *
 * **① 页面上只有两个窗口。** 模型清单**不在页面上**，在抽屉里。
 *    理由：十四家厂商 × 每家十几个模型 = 一屏放不下，
 *    而放不下的清单等于逼用户滚动着找。
 *
 * **② 抽屉一次给全。** 不分组、不折叠、不"查看更多"。
 *    用户要的是"一眼扫完再挑"。
 *
 * **③ 拉条就在窗口里。** 不放进抽屉 —— 因为它是**这个角色**的属性，
 *    不是"选模型时顺便调"的东西。放在窗口里，改完立刻看得到。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么只需要两个窗口
 * ═══════════════════════════════════════════════════════════════
 *
 * 项目里"每轮之后的异步 AI"只有两件：
 * · **场记** —— 从对话里识别事件、整理世界书事实
 * · **记忆抽取** —— 把关键信息记成可检索的记忆
 *
 * ⚠️ 世界书的**检索**不在这里 —— 它是确定性计算，不调模型
 *    （见 `WorldBook` 的注释）。给它配模型是浪费。
 */
@Composable
fun BackgroundAiScreen(
    models: List<ModelConfig>,
    directorModelId: String?,
    directorEffort: ThinkingEffort,
    physioModelId: String?,
    physioEffort: ThinkingEffort,
    memoryModelId: String?,
    memoryEffort: ThinkingEffort,
    onPickDirectorModel: (String?) -> Unit,
    onDirectorEffort: (ThinkingEffort) -> Unit,
    onPickPhysioModel: (String?) -> Unit,
    onPhysioEffort: (ThinkingEffort) -> Unit,
    onPickMemoryModel: (String?) -> Unit,
    onMemoryEffort: (ThinkingEffort) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 哪个窗口的抽屉开着。null = 都关着
    var drawer by remember { mutableStateOf<Slot?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PaTopBar(title = "后台 AI", onBack = onBack)

            Column(modifier = Modifier.padding(horizontal = PaSpace.m)) {
                Text(
                    text = "这三件事在每轮之后异步跑，不占用你等回复的时间。" +
                        "它们只需要看懂和归类，用便宜快的模型就够。",

                    style = PaType.body,
                    color = PaColor.TextSecondary,
                )
                Spacer(Modifier.height(PaSpace.m))

                // ── 窗口一：场记 ────────────────────────────────
                SlotCard(
                    icon = "🌐",
                    title = "场记",
                    subtitle = "从对话里识别事件、整理世界书事实",
                    modelLabel = modelLabel(directorModelId),
                    effort = directorEffort,
                    recommendation = "推荐 low —— 判断该不该触发就够，不需要长篇推演",
                    onOpenDrawer = { drawer = Slot.DIRECTOR },
                    onEffort = onDirectorEffort,
                )

                Spacer(Modifier.height(PaSpace.m))

                // ── 窗口二：生理导演 ────────────────────────────
                //
                // ⚠️ 与场记**分开**：场记管世界书与事件，
                //    生理导演管用户这句话该让她身体怎样。
                //    共用一个模型位会让两种负载互相干扰。
                SlotCard(
                    icon = "🫀",
                    title = "生理导演",
                    subtitle = "读用户话里的线索，给她一点身体反应",
                    modelLabel = modelLabel(physioModelId),
                    effort = physioEffort,
                    recommendation = "推荐 off —— 只需识别他发烧了这类线索，推理帮不上忙",
                    onOpenDrawer = { drawer = Slot.PHYSIO },
                    onEffort = onPhysioEffort,
                )

                Spacer(Modifier.height(PaSpace.m))

                // ── 窗口三：记忆抽取 ────────────────────────────
                SlotCard(
                    icon = "🧠",
                    title = "记忆抽取",
                    subtitle = "把对话里的关键信息记成可检索的记忆",
                    modelLabel = modelLabel(memoryModelId),
                    effort = memoryEffort,
                    recommendation = "推荐 off —— 抽取是照抄加归类，推理反而是负担",
                    onOpenDrawer = { drawer = Slot.MEMORY },
                    onEffort = onMemoryEffort,
                )

                Spacer(Modifier.height(PaSpace.l))
            }
        }

        // ── 抽屉 ────────────────────────────────────────────────
        val open = drawer
        if (open != null) {
            // 遮罩：点空白关闭。不给这个手势，用户只能找"取消"按钮，
            // 而这里刻意没有那个按钮（用户要求的是"点击收起"）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { drawer = null },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = PaRadius.l, topEnd = PaRadius.l))
                    .background(PaColor.CanvasElevated)
                    .padding(top = PaSpace.m, bottom = PaSpace.l),
            ) {
                // ★ 用户要求"选择完后可以点击收起" —— 显式给一个按钮，
                //   不只是靠点遮罩（那两个动作对用户来说是不同的意图）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PaSpace.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when (open) {
                            Slot.DIRECTOR -> "场记用哪个模型"
                            Slot.PHYSIO -> "生理导演用哪个模型"
                            Slot.MEMORY -> "记忆抽取用哪个模型"
                        },
                        style = PaType.headline,
                        color = PaColor.TextPrimary,
                    )
                    Text(
                        text = "收起",
                        style = PaType.label,
                        color = PaColor.AccentText,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { drawer = null },
                    )
                }
                Spacer(Modifier.height(PaSpace.xs))

                // ⚠️ 复用 `ModelPickerSheet` 的**模型清单**部分，但不复用它的
                //    ThinkingSlider —— 那个已经搬到窗口里了。
                //    ⇒ 用一个精简的清单（见 ModelListOnly）。
                ModelListOnly(
                    models = models,
                    selectedId = when (open) {
                        Slot.DIRECTOR -> directorModelId
                        Slot.PHYSIO -> physioModelId
                        Slot.MEMORY -> memoryModelId
                    },
                    onPickAuto = {
                        when (open) {
                            Slot.DIRECTOR -> onPickDirectorModel(null)
                            Slot.PHYSIO -> onPickPhysioModel(null)
                            Slot.MEMORY -> onPickMemoryModel(null)
                        }
                        drawer = null
                    },
                    onPick = { id ->
                        when (open) {
                            Slot.DIRECTOR -> onPickDirectorModel(id)
                            Slot.PHYSIO -> onPickPhysioModel(id)
                            Slot.MEMORY -> onPickMemoryModel(id)
                        }
                        drawer = null
                    },
                )
            }
        }
    }
}

private enum class Slot { DIRECTOR, PHYSIO, MEMORY }

/** null = 跟随对话模型。 */
private fun modelLabel(id: String?): String = id ?: "跟随对话模型"

@Composable
private fun SlotCard(
    icon: String,
    title: String,
    subtitle: String,
    modelLabel: String,
    effort: ThinkingEffort,
    recommendation: String,
    onOpenDrawer: () -> Unit,
    onEffort: (ThinkingEffort) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaRadius.m))
            .background(PaColor.Surface)
            .padding(PaSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, style = PaType.headline)
            Spacer(Modifier.height(2.dp))
            Column(modifier = Modifier.padding(start = PaSpace.xs)) {
                Text(text = title, style = PaType.headline, color = PaColor.TextPrimary)
                if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = PaType.caption, color = PaColor.TextTertiary)
            }
            }
        }

        Spacer(Modifier.height(PaSpace.m))

        // ── 模型（点了开抽屉）──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenDrawer,
                )
                .padding(vertical = PaSpace.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "模型", style = PaType.body, color = PaColor.TextSecondary)
            Text(
                text = modelLabel,
                style = PaType.label,
                color = PaColor.AccentText,
            )
        }

        PaListDivider()

        // ── 思考强度（拉条就在这里，不在抽屉里）────────────
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = "思考强度",
            style = PaType.body,
            color = PaColor.TextSecondary,
        )
        Spacer(Modifier.height(PaSpace.xxs))
        ThinkingSlider(
            effort = effort,
            onPick = onEffort,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = effort.hint,
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
        Spacer(Modifier.height(PaSpace.xxs))
        Text(
            text = recommendation,
            style = PaType.caption,
            color = PaColor.TextDisabled,
        )
    }
}

/**
 * 只要**模型清单**的那一半（抽屉里用）。
 *
 * ⚠️ 不复用 `ModelPickerSheet` 整体：它把拉条也带上了，
 *    而拉条已经搬进窗口里 —— 一个设置项出现在两处必然漂移。
 */
@Composable
private fun ModelListOnly(
    models: List<ModelConfig>,
    selectedId: String?,
    onPickAuto: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 380 → 300：真机上 380 会把第二个窗口**整个**盖住，
            //    用户看不到"还有一件事也要配"。
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PaSpace.m),
    ) {
        // 「跟随对话模型」排第一 —— 它是默认，也是最安全的选择
        PickRow(
            title = "跟随对话模型",
            subtitle = "不单独指定，用你聊天时选的那个",
            selected = selectedId == null,
            onClick = onPickAuto,
        )
        models.forEach { m ->
            val title = m.label.ifBlank { m.modelDisplayName }
            PickRow(
                title = title,
                // ⚠️ 标题与 modelId 相同时**不重复显示** ——
                //    否则列表里每一行都是同一段字出现两遍，很像 bug。
                //    （真机截图里 `glm5.3flash` 就是这样。）
                subtitle = if (title == m.modelId) "" else m.modelId,
                selected = m.modelId == selectedId,
                onClick = { onPick(m.modelId) },
            )
        }
        if (models.isEmpty()) {
            Spacer(Modifier.height(PaSpace.m))
            Text(
                text = "还没有可用的模型。先在「设置 → 密钥」里填一把 Key。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.height(PaSpace.m))
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = PaSpace.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = PaSpace.s)) {
            Text(
                text = title,
                style = PaType.body,
                color = if (selected) PaColor.AccentText else PaColor.TextPrimary,
            )
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, style = PaType.caption, color = PaColor.TextTertiary)
            }
        }
        if (selected) {
            Text(text = "已选", style = PaType.caption, color = PaColor.AccentText)
        }
    }
}
