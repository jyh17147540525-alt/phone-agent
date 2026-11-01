package com.pocketagent.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketagent.agentui.IconGeometry
import com.pocketagent.ui.design.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketagent.modelrouter.ModelChoice
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.ThinkingEffort
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * **模型选择弹层** —— 快捷切换 + 思考强度。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：用户要求简化模型选择
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > "模型选择页你做得太复杂了，能不能像 workbuddy、deepseekharness
 * >  那样快捷切换模型，还可以通过类似于 codex 那样有一个思考强度滑块，
 * >  默认是让用户选自动的。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 设计上的三个决定
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **"自动"排第一，而且默认选中**
 *    用户明确要求默认自动。而"自动"放在列表顶部而不是藏进设置，
 *    是因为它是最常用的那一个 —— 常用项要最容易点到。
 *
 * ② **不显示档位/角色这些术语**
 *    原来的模型页让用户配"档位 + 用途 + 启用 + 价格"四项。
 *    这里只说人话："轻量 / 均衡 / 重型"作为**辅助标签**，
 *    而不是必填项。
 *
 * ③ **思考强度用一排分段按钮，不用真正的 Slider**
 *    ⚠️ Slider 是**连续**的，而这里只有 4 个**离散**的档位。
 *    用滑块会让用户以为可以拖到 0.37 这种位置 ——
 *    而实际上只有 4 个语义不同的选项。
 *    ⇒ 分段按钮（segmented）才是**诚实的控件选择**。
 *
 *    ★ 这与"Codex 的思考强度滑块"在**用法上等价**（都是一眼四选一），
 *      但不会误导用户可以连续调节。
 */
@Composable
fun ModelPickerSheet(
    models: List<ModelConfig>,
    choice: ModelChoice,
    effort: ThinkingEffort,
    onPickAuto: () -> Unit,
    onPick: (String) -> Unit,
    onEffort: (ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.m),
    ) {
        // ── 思考强度（拉条）─────────────────────────────────────
        //
        // ★★ 2026-11-01：用户发了参考图，要求做成"格子拉条"。
        //    原来这里是四个分段按钮 —— 功能一样，但图里的连续感
        //    更能表达"从更快到更强是一条梯度"，而不是四个孤立选项。
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

        Spacer(Modifier.height(PaSpace.l))

        // ── 模型 ───────────────────────────────────────────────
        Text(
            text = "用哪个模型",
            style = PaType.headline,
            color = PaColor.TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "自动会按场景挑 —— 聊天用快的，办事用稳的",
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
        Spacer(Modifier.height(PaSpace.xs))

        LazyColumn(
            modifier = Modifier
                // ⚠️ 限高：模型多时弹层不能顶满整屏（那样"思考强度"
                //    会被推出视野，而它就在上面 —— 用户以为没这个选项）。
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                ModelRow(
                    title = "自动",
                    subtitle = "按场景自己决定",
                    selected = choice is ModelChoice.Auto,
                    onClick = onPickAuto,
                )
            }
            items(models, key = { it.id }) { m ->
                ModelRow(
                    title = m.effectiveLabel,
                    subtitle = buildString {
                        append(m.tier.displayName)
                        if (!m.enabled) append(" · 已停用")
                        // ⚠️ 停用的模型**仍然列出来**但标清楚 ——
                        //    直接隐藏会让用户以为"我配的模型没了"。
                    },
                    selected = choice is ModelChoice.Pinned && choice.configId == m.id,
                    dimmed = !m.enabled,
                    onClick = { onPick(m.id) },
                )
            }
        }

        Spacer(Modifier.height(PaSpace.m))
    }
}

/**
 * 思考强度的一格。
 *
 * ★ 选中态用**实填**而不是描边：一排四格里，实填比描边更容易
 *   一眼看出"现在选的是哪个"。
 */
@Composable
private fun EffortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(if (selected) PaColor.Accent else PaColor.GlassTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PaType.label,
            color = if (selected) androidx.compose.ui.graphics.Color(0xFF06121F)
            else PaColor.TextSecondary,
        )
    }
}

/**
 * 模型列表的一行。
 *
 * @param dimmed 停用的模型 —— 降透明度但**仍然可点**
 *   （点了会选中它，然后 `pickModel` 会告诉你"已停用，这次自动挑了…"）。
 *   ⚠️ 不设成"不可点"：不可点会让用户以为界面卡了。
 */
@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    dimmed: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(if (selected) PaColor.AccentSoft else PaColor.GlassTint)
            .clickable(onClick = onClick)
            // ⚠️ 整行可点（48dp 高）：模型名可能很长，
            //    只让文字区域可点会让右侧一半点不到。
            .heightIn(min = 52.dp)
            .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PaType.body,
                color = if (dimmed) PaColor.TextTertiary else PaColor.TextPrimary,
            )
            Text(
                text = subtitle,
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = PaColor.AccentText,
                modifier = Modifier.size(IconGeometry.Size.COMPACT.dp),
            )
        }
    }
}
