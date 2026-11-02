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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketagent.personalogic.ProposedFact
import com.pocketagent.personalogic.WorldEntry
import com.pocketagent.personalogic.WorldEntryKind
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTopBar
import com.pocketagent.ui.design.PaType

/**
 * **世界书面板** —— 设定由你填，事实由它提议、你点头。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：这是"世界书不崩"的最后一道人工关口
 * ═══════════════════════════════════════════════════════════════
 *
 * 结构上是两块：
 *
 * | 块 | 谁写 | 能不能改 |
 * |---|---|---|
 * | **设定** | 用户手填 | 只有用户能改、能删 |
 * | **事实** | 场记提议 → **你点确认** | 也能删 |
 *
 * ⚠️ 场记提议的东西**不会自动进来**。没有这一步，AI 就会为了圆台词
 *    去造世界 —— 那是世界书崩掉的经典路径。
 *
 * ⚠️ 界面上刻意**不区分得太花**：两块用标题分开，条目一行一句。
 *    世界书是拿来"核对"的，不是拿来"欣赏"的。
 */
@Composable
fun WorldBookScreen(
    entries: List<WorldEntry>,
    pending: List<ProposedFact>,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddSetting: (title: String, keys: List<String>, content: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PaTopBar(title = "世界书", onBack = onBack)

        Column(modifier = Modifier.padding(horizontal = PaSpace.m)) {
            Text(
                text = "设定由你定，它只能提议事实、由你确认。",
                style = PaType.body,
                color = PaColor.TextSecondary,
            )
            Spacer(Modifier.height(PaSpace.m))

            // ── 待确认 ──────────────────────────────────────
            if (pending.isNotEmpty()) {
                Text(
                    text = "等你看一眼（${pending.size}）",
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                )
                Spacer(Modifier.height(PaSpace.xs))
                pending.forEach { f ->
                    Card {
                        Text(f.title, style = PaType.body, color = PaColor.TextPrimary)
                        Text(f.content, style = PaType.caption, color = PaColor.TextTertiary)
                        Text(
                            "触发词：${f.keys.joinToString("、")}",
                            style = PaType.caption,
                            color = PaColor.TextDisabled,
                        )
                        Spacer(Modifier.height(PaSpace.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.m)) {
                            Action("记下", PaColor.AccentText) { onConfirm(f.id) }
                            Action("不要", PaColor.TextTertiary) { onReject(f.id) }
                        }
                    }
                    Spacer(Modifier.height(PaSpace.xs))
                }
                Spacer(Modifier.height(PaSpace.m))
            }

            // ── 设定 ────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "设定",
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                )
                Action(if (adding) "收起" else "加一条", PaColor.AccentText) {
                    adding = !adding
                }
            }
            Spacer(Modifier.height(PaSpace.xs))

            if (adding) {
                Card {
                    PaTextField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "标题（如：她住的房子）",
                        )
                    Spacer(Modifier.height(PaSpace.xxs))
                    PaTextField(
                            value = keys,
                            onValueChange = { keys = it },
                            placeholder = "触发词，逗号分隔（如：家,小区,回去）",
                        )
                    Spacer(Modifier.height(PaSpace.xxs))
                    PaTextField(
                            value = content,
                            onValueChange = { content = it },
                            placeholder = "一句话说清这条设定",
                        )
                    Spacer(Modifier.height(PaSpace.xs))
                    Action("保存", PaColor.AccentText) {
                        onAddSetting(
                            title,
                            keys.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                            content,
                        )
                        title = ""; keys = ""; content = ""; adding = false
                    }
                }
                Spacer(Modifier.height(PaSpace.xs))
            }

            val settings = entries.filter { it.kind == WorldEntryKind.SETTING }
            if (settings.isEmpty()) {
                Text(
                    text = "还没有设定。加一条之后，它在这个话题上就不会乱编了。",
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            } else {
                settings.forEach { e ->
                    Card {
                        Text(e.title, style = PaType.body, color = PaColor.TextPrimary)
                        Text(e.content, style = PaType.caption, color = PaColor.TextTertiary)
                        Text(
                            "触发词：${e.keys.joinToString("、")}",
                            style = PaType.caption,
                            color = PaColor.TextDisabled,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Action("删掉", PaColor.TextTertiary) { onRemove(e.id) }
                    }
                    Spacer(Modifier.height(PaSpace.xs))
                }
            }

            // ── 已记住的事实 ────────────────────────────────
            val facts = entries.filter { it.kind == WorldEntryKind.FACT }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(PaSpace.m))
                Text(
                    text = "记住的事（${facts.size}）",
                    style = PaType.headline,
                    color = PaColor.TextPrimary,
                )
                Spacer(Modifier.height(PaSpace.xs))
                facts.forEach { e ->
                    Card {
                        Text(e.content, style = PaType.body, color = PaColor.TextPrimary)
                        Text(
                            "触发词：${e.keys.joinToString("、")}",
                            style = PaType.caption,
                            color = PaColor.TextDisabled,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Action("删掉", PaColor.TextTertiary) { onRemove(e.id) }
                    }
                    Spacer(Modifier.height(PaSpace.xs))
                }
            }

            Spacer(Modifier.height(PaSpace.l))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaRadius.m))
            .background(PaColor.Surface)
            .padding(PaSpace.m),
    ) { content() }
}

@Composable
private fun Action(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = PaType.label,
        color = color,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    )
}
