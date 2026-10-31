package com.pocketagent.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.CompletionState
import com.pocketagent.agentui.SelectionState

/**
 * 交互类组件：勾选框、完成反馈按钮、叠卡。
 *
 * 与 [Components.kt] 的分工：那边是"骨架与动作"，这边是"选择与状态"。
 *
 * ★ 这三个的共同点是**逻辑已经由 `:agentui` 算好了**，本文件只负责画。
 *   所以这里没有任何 if/else 判断业务规则 —— 那种东西一旦写进 Compose，
 *   就掉出了离线验证器的覆盖范围。
 */

// ═══════════════════════════════════════════════════════════════
//  三态勾选框
// ═══════════════════════════════════════════════════════════════

/**
 * 三态勾选框。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不用 `TriStateCheckbox`
 * ═══════════════════════════════════════════════════════════════
 *
 * Material3 的那个组件有完整的 ripples、焦点态、无障碍语义，
 * 但它的大小固定（20dp 方块 + 一圈 padding），而且**半选态的样式
 * 是 Material 自己的**（一条横杠）。
 *
 * 本产品的玻璃风格要求：
 *   · 方块圆角 6dp、描边而非填充
 *   · 选中态是**强调色填充 + 白勾**
 *   · 半选态是一条**同色的短横**
 * 这些在 Material 组件上要全部覆盖，反而更麻烦。
 *
 * ⚠️ 但语义一点不能少：`Role.Checkbox` + `toggleable` 必须给，
 *    否则读屏用户听到的是"按钮"而不是"复选框，已选中"。
 */
@Composable
fun PaTriStateCheckbox(
    state: SelectionState.TriState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()

    // 选中/半选都是"强调"，未选是"描边"。颜色过渡用 fast（180ms），
    // 与全站其它即时反馈同档。
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> PaColor.SurfaceHigh
            state == SelectionState.TriState.OFF -> Color.Transparent
            else -> PaColor.Accent
        },
        animationSpec = PaMotion.fast(),
        label = "checkboxFill",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            !enabled -> PaColor.TextDisabled
            state == SelectionState.TriState.OFF -> PaColor.TextTertiary
            else -> PaColor.Accent
        },
        animationSpec = PaMotion.fast(),
        label = "checkboxBorder",
    )

    // 勾/横杠出现时的轻微放大：0.8 → 1.0。
    // ★ 这是"确认感"的来源，但幅度必须小 —— 大了会像弹窗。
    val markScale by animateFloatAsState(
        targetValue = if (state == SelectionState.TriState.OFF) 0.8f else 1f,
        animationSpec = PaMotion.bouncy(),
        label = "checkboxMarkScale",
    )

    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.5.dp, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.light()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SelectionState.TriState.OFF -> Unit
            SelectionState.TriState.ON -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF06121F),
                modifier = Modifier.size(16.dp).scale(markScale),
            )
            // ★ 半选是一条短横，不是勾 —— 用户必须能一眼区分
            //   "全选中"和"选了一部分"。
            SelectionState.TriState.PARTIAL -> Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = null,
                tint = Color(0xFF06121F),
                modifier = Modifier.size(16.dp).scale(markScale),
            )
        }
    }
}

/**
 * 一行「勾选框 + 标题 + 说明」。
 *
 * ★ 整行可点，不只是方块：手指点在文字上也该生效。
 *   只让方块可点会让这个组件在手机上很难用（24dp 远小于 44dp 的最小触摸目标）。
 */
@Composable
fun PaCheckRow(
    checked: Boolean,
    title: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onToggle,
            )
            .padding(vertical = PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaTriStateCheckbox(
            state = if (checked) SelectionState.TriState.ON else SelectionState.TriState.OFF,
            onClick = onToggle,
            enabled = enabled,
        )
        Spacer(Modifier.width(PaSpace.s))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PaType.body,
                color = if (enabled) PaColor.TextPrimary else PaColor.TextDisabled,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  完成反馈按钮
// ═══════════════════════════════════════════════════════════════

/**
 * **完成**按钮 —— 四种状态各有明确的视觉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它不是 `PaButton` 加一个 loading
 * ═══════════════════════════════════════════════════════════════
 *
 * 四种状态的**形状本身**就不同：
 *   · IDLE     → 文字"完成"
 *   · WORKING  → 转圈 + 文字变淡
 *   · DONE     → 勾 + 强调色，**不再可点**
 *   · FAILED   → 文字"重试" + 警示色
 *
 * 用一个布尔只能区分两种。硬塞进 `PaButton` 会让那个通用组件
 * 变复杂（它现在有 26 个调用点，每个都该保持简单）。
 *
 * ⚠️ 状态由 `:agentui/CompletionState` 算，本组件**不做判断** ——
 *    见那个文件的注释（防连点、迟到响应都在那边）。
 */
@Composable
fun PaCompleteButton(
    state: CompletionState.State,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()

    val bg by animateColorAsState(
        targetValue = when (state.phase) {
            CompletionState.Phase.DONE -> PaColor.Accent
            CompletionState.Phase.FAILED -> PaColor.Danger.copy(alpha = 0.16f)
            CompletionState.Phase.WORKING -> PaColor.SurfaceHigh
            CompletionState.Phase.IDLE -> PaColor.Accent
        },
        animationSpec = PaMotion.standard(),
        label = "completeBg",
    )
    val fg = when (state.phase) {
        CompletionState.Phase.DONE -> Color(0xFF06121F)
        CompletionState.Phase.FAILED -> PaColor.Danger
        CompletionState.Phase.WORKING -> PaColor.TextTertiary
        CompletionState.Phase.IDLE -> Color(0xFF06121F)
    }

    val label = when (state.phase) {
        CompletionState.Phase.IDLE -> "完成"
        CompletionState.Phase.WORKING -> "处理中"
        CompletionState.Phase.DONE -> "已完成"
        CompletionState.Phase.FAILED -> "重试"
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(PaRadius.s))
            .background(bg)
            .then(
                // 失败态是描边而不是实心：它不该看起来像"可以点的主按钮"，
                // 而是一个"提醒 + 出口"。
                //
                // ⚠️ `Modifier.border(width, color, shape)` —— 第二个参数是 Color
                //    不是 Brush。传 Brush 需要另一个重载，而这里不需要渐变。
                if (state.phase == CompletionState.Phase.FAILED) {
                    Modifier.border(
                        width = 1.dp,
                        color = PaColor.Danger.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(PaRadius.s),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                // ⚠️ `indication = null` 必须配 `interactionSource` ——
                //    只传 indication 时那个重载不存在（会报
                //    "None of the following candidates is applicable"）。
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = state.clickable,
                onClick = {
                    haptics.confirm()
                    onClick()
                },
            )
            .padding(horizontal = PaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when (state.phase) {
            CompletionState.Phase.WORKING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = PaColor.TextTertiary,
                )
                Spacer(Modifier.width(PaSpace.xs))
            }
            CompletionState.Phase.DONE -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(PaSpace.xxs))
            }
            else -> Unit
        }
        Text(text = label, style = PaType.label, color = fg)
    }

    // 失败原因用小字放在下面 —— 塞进按钮里会让按钮变宽、换行。
    //
    // ⚠️ `state.error` 是跨模块的 public 属性，**不能** smart cast
    //   （编译器无法保证它在读两次之间没变）。先取到局部变量。
    val err = state.error
    if (state.phase == CompletionState.Phase.FAILED && err != null) {
        Text(
            text = err,
            style = PaType.caption,
            color = PaColor.Danger,
            modifier = Modifier.padding(top = PaSpace.xxs),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  叠卡
// ═══════════════════════════════════════════════════════════════

/**
 * **叠起来的几张卡** —— 摞着时只露 3 张的边，点一下展开。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么摞起来
 * ═══════════════════════════════════════════════════════════════
 *
 * 一次任务会产生十几张卡。平铺的话用户要滚很久，而且**看不出
 * 这十几张是一次任务**（它们看起来是独立事件）。
 * 摞起来本身就在表达"这几个是一伙的"。
 *
 * ⚠️ 三条规则由 `:agentui/CardStack` 算（露几张、透明度和缩放怎么递减、
 *    超限展开时点击的那张要换进可见区）。本组件只画。
 */
@Composable
fun PaCardStack(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val stack = com.pocketagent.agentui.CardStack.Stack(List(count) { "c$it" })
    val stacked = com.pocketagent.agentui.CardStack.shouldStack(stack)

    // 展开/收起的旋转箭头：一个很小的方向提示。
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = PaMotion.standard(),
        label = "stackArrow",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = stacked,
                    onClick = {
                        haptics.light()
                        onToggle()
                    },
                ),
        ) {
            if (!expanded && stacked) {
                // ⚠️ 从**最底下**往上画，这样最上面那张压在最前。
                //    反过来的话看起来像"卡片向右下角歪"。
                //
                // ★ 偏移量：`stackOffset` 返回 0..0.08 的比例，
                //   乘上"一张卡露出的高度"（[STACK_PEEK]）就是实际错位。
                //   ⚠️ 这里第一版写成了 `offset * 400` —— 那个 400 是
                //      屏幕高度量级，会算出 32dp 的巨大错位，看起来像散架。
                //      正确的基准是"露出的边"本身。
                for (i in (com.pocketagent.agentui.CardStack.STACK_VISIBLE - 1) downTo 1) {
                    val offset = com.pocketagent.agentui.CardStack.stackOffset(i) ?: continue
                    val alpha = com.pocketagent.agentui.CardStack.stackAlpha(i) ?: continue
                    val scale = com.pocketagent.agentui.CardStack.stackScale(i) ?: continue
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 每张下移 `offset / 0.04 * STACK_PEEK`
                            // （0.04 是 stackOffset 的步长，见 CardStack）
                            //
                            // ⚠️ `STACK_PEEK` 已经是 Dp，不能再 `.dp` ——
                            //    `Dp * Dp` 没有定义，编译器会报
                            //    "None of the following candidates is applicable"。
                            .padding(top = STACK_PEEK * (offset / 0.04f))
                            .scale(scale)
                            .alpha(alpha)
                            .clip(RoundedCornerShape(PaRadius.m))
                            .background(PaColor.SurfaceHigh)
                            .height(STACK_PEEK),
                    )
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    collapsedContent()
                }
            } else {
                expandedContent()
            }
        }
    }
}

/**
 * 叠卡时每多一张露出的高度。
 *
 * ★ 6dp：足够看出"这里还有几张"，又不至于让一叠卡占掉半屏。
 *   再大（12dp+）会让 3 张卡看起来像 4 张独立的卡。
 */
private val STACK_PEEK = 6.dp
