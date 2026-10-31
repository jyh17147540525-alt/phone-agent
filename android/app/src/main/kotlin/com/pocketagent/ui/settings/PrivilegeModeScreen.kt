package com.pocketagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.rootlogic.CapabilityMatrix
import com.pocketagent.rootlogic.CapabilityTier
import com.pocketagent.rootlogic.ModeGatedCapability
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootProbeResult
import com.pocketagent.rootlogic.explain
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace

/**
 * **运行模式**页 —— root / 非 root 的切换与能力边界说明。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一页的设计原则：**说清边界，而不是卖功能**
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话："非 root 的上限就到那里了，没有 root 办不到的就办不到，
 * 要划清边界"。所以这一页的重点不是列一堆 root 能做的事，
 * 而是让用户**准确知道**：
 *
 *  1. 我现在处于什么模式
 *  2. 这个模式下**哪些做不到**，以及**为什么**（是系统的限制，不是应用偷懒）
 *  3. 升级到 root 能**多**得到什么
 *
 * ⚠️ 一条设计铁律：**"做不到"必须显示，不能隐藏**。
 *   把不可用的能力藏起来，用户会以为"这应用没这个功能"；
 *   明确列出来并说明原因，用户才会接受边界。
 *
 * @param current 当前**实际生效**的模式（不是用户选的 —— 见 AppContainer）
 * @param probe 最近一次 root 探测结论
 * @param onToggle 用户切开关时调用；返回切换后的生效模式
 */
@Composable
fun PrivilegeModeScreen(
    current: PrivilegeMode,
    probe: RootProbeResult?,
    onToggle: (PrivilegeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRoot = current == PrivilegeMode.ROOT
    // ★ 用局部状态承接开关，避免"点了没反应"的错觉 ——
    //   真正的生效模式由 onToggle 回写，若失败会在下面显示原因。
    var checked by remember(current) { mutableStateOf(isRoot) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
    ) {
        item {
            PaSectionTitle("运行模式")
        }

        // ── 开关本体 ─────────────────────────────────────────────
        item {
            PaListGroup {
                // ══════════════════════════════════════════════════════
                //  ★★ 2026-10-31：换成 `PaCheckRow`
                // ══════════════════════════════════════════════════════
                //
                // 原来这里是手写的 `Row { Column{}; Switch{} }`，
                // 而且**用的是 `MaterialTheme.typography` 与
                // `colorScheme.onSurfaceVariant`** —— 与本项目的设计系统
                // （`PaType` / `PaColor`）完全脱节。
                //
                // ⚠️ 后果不是"难看"，而是**它在深色主题下会跟别的页不一致**：
                //    Material 的 `onSurfaceVariant` 是它自己那套灰，
                //    而本项目刚做过 WCAG 审计、把 `TextSecondary` /
                //    `TextTertiary` 都算过对比度 —— Material 的灰没过那次审计。
                //
                // ⇒ 用 `PaCheckRow`（`7d0357d` 建好、此前**零调用方**）：
                //   它自带 `PaType` + `PaColor`，而且**整行可点**
                //   （原来只有那个小开关可点 —— 触控目标是 48dp 还是整行，
                //   在"躺着单手操作"时差别很大）。
                com.pocketagent.ui.design.PaCheckRow(
                    checked = checked,
                    title = if (checked) "Root 模式" else "普通模式",
                    subtitle = if (checked) {
                        PrivilegeMode.ROOT.summary
                    } else {
                        PrivilegeMode.NON_ROOT.summary
                    },
                    onToggle = {
                        val want = !checked
                        checked = want
                        onToggle(if (want) PrivilegeMode.ROOT else PrivilegeMode.NON_ROOT)
                    },
                    modifier = Modifier.padding(horizontal = PaSpace.s),
                )
            }
        }

        // ── 探测结论：说清"为什么不是你以为的那样" ────────────────
        item {
            val tone = when {
                isRoot -> PaBannerTone.Info
                probe is RootProbeResult.Denied -> PaBannerTone.Warning
                probe is RootProbeResult.Failed -> PaBannerTone.Warning
                else -> PaBannerTone.Info
            }
            val title = when {
                isRoot -> "Root 已就绪"
                checked != isRoot && checked -> "开关是开的，但实际没生效"
                probe is RootProbeResult.Denied -> "手机有 root，但本应用没被授权"
                else -> "当前是普通模式"
            }
            PaBanner(
                tone = tone,
                title = title,
                description = probe?.explain() ?: PrivilegeMode.NON_ROOT.summary,
            )
        }

        // ── 能力梯度 ─────────────────────────────────────────────
        item {
            PaSectionTitle("能力边界")
            Spacer(Modifier.height(4.dp))
            Text(
                text = "下面是本应用全部受模式影响的能力，以及在你当前模式下**做到哪一步**。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(CapabilityMatrix.all) { cap ->
            CapabilityRow(cap = cap, mode = current)
        }

        // ── 应用托管：把边界说在最前面 ────────────────────────────
        //
        // ★ 用户明确要求"虚拟屏、不干扰主屏"。而真机实测的结果是：
        //    真正的 VirtualDisplay 需要 Shizuku（未运行），
        //    而 freeform 小窗**看得见**但藏不起来。
        //
        //   ⇒ 把这件事**放在这里说清楚**，而不是等他点了托管才发现。
        //     藏在文档里等于没说。
        item {
            Spacer(Modifier.height(PaSpace.l))
            PaSectionTitle("应用托管")
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "让角色在**不占用你当前屏幕**的情况下操作 QQ、微信这类应用。",
                style = PaType.body,
                color = PaColor.TextPrimary,
            )
        }
        items(com.pocketagent.hosting.HostingLimitation.items) { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = PaColor.Warning,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.title, style = PaType.body, color = PaColor.TextPrimary)
                    Text(
                        text = item.detail,
                        style = PaType.caption,
                        color = PaColor.TextSecondary,
                    )
                }
            }
        }

        // ── root 能多得到什么 ────────────────────────────────────
        if (!isRoot) {
            item {
                val unlocked = CapabilityMatrix.unlockedByRoot()
                if (unlocked.isNotEmpty()) {
                    PaBanner(
                        tone = PaBannerTone.Info,
                        title = "开启 Root 模式可以多得到 ${unlocked.size} 项能力",
                        description = unlocked.joinToString("、") { it.title } +
                            "。⚠️ 这些能力一旦执行**不可撤销**，每一步都会先问你。",
                    )
                }
            }
        }
    }
}

/**
 * 单条能力的展示。
 *
 * ★ `Unavailable` 的条目**也要显示**（灰显 + 原因），
 *   而不是过滤掉 —— 见本文件顶部"做不到必须显示"那条铁律。
 */
@Composable
private fun CapabilityRow(cap: ModeGatedCapability, mode: PrivilegeMode) {
    val tier = cap.tierIn(mode)

    Pair(
        when (tier) {
            is CapabilityTier.Full -> Icons.Default.CheckCircle
            is CapabilityTier.Degraded -> Icons.Default.Warning
            is CapabilityTier.Unavailable -> Icons.Default.Lock
        },
        when (tier) {
            is CapabilityTier.Full -> MaterialTheme.colorScheme.primary
            is CapabilityTier.Degraded -> MaterialTheme.colorScheme.tertiary
            is CapabilityTier.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ).let { (icon, tint) ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = cap.title, style = MaterialTheme.typography.bodyMedium)

                // ★ 每种状态都必须给出**一句人话**，不能只靠图标颜色。
                when (tier) {
                    is CapabilityTier.Full -> {
                        // 完全可用时不必多说 —— 说多了是噪音
                    }
                    is CapabilityTier.Degraded -> {
                        Text(
                            text = "现在：${tier.how}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "还差：${tier.missing}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is CapabilityTier.Unavailable -> {
                        Text(
                            text = tier.why,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
