package com.pocketagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketagent.core.common.permission.HostPermissions
import com.pocketagent.core.common.permission.PermissionSummary
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaListRow
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace

/**
 * 设置页。
 *
 * ═══════════════════════════════════════════════════════════════
 *  信息架构：为什么把「模型」「插件」「权限」放在同一页
 * ═══════════════════════════════════════════════════════════════
 *
 * 这三者在本产品里是**同一件事的三个面**。用户要理解
 * "这个 App 能做什么、代价是什么"，必须同时看到：
 *   · 模型（花钱的：BYOK Key 与用量）
 *   · 插件（能力的：从哪来、装了什么）
 *   · 权限（风险的：能读屏幕、能点屏幕）
 *
 * 拆成三个页面会让人分别看，**看不到它们之间的关系** ——
 * 而"装了个来路不明的插件 + 给了无障碍权限 + 配了自己的 Key"
 * 这个组合的风险，只有在同一屏上才看得出来。
 */
/**
 * ⚠️ 六个 `onOpen*` 都是**可空**的，这是有意的。
 *
 * 传 `null` 时 [PaListRow] 不会画右侧箭头 —— 于是"这一项暂时进不去"
 * 就通过**视觉本身**表达出来了。
 *
 * 反例是传空 lambda `{}`：箭头照画，用户点下去毫无反应。
 * 那比缺功能更糟 —— 缺功能用户能理解，点了没反应用户会认为应用坏了，
 * 然后开始怀疑别的功能也是坏的。**不要把"未实现"伪装成"可用"。**
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    keyStatus: SettingsViewModel.KeyStatus = SettingsViewModel.KeyStatus.Loading,
    /**
     * 宿主权限汇总。
     *
     * ⚠️ `null` = **还没读到**，与「全都没开」是两回事。
     *    这时**不画徽章** —— 画一个「0 项待开启」会让用户以为一切正常。
     */
    permissionSummary: PermissionSummary? = null,
    onOpenKeys: (() -> Unit)? = null,
    onOpenModels: (() -> Unit)? = null,
    onOpenDsh: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    onOpenImport: (() -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null,
    onOpenCapabilities: (() -> Unit)? = null,
) {
    val (keyBadge, keyTone) = keyBadgeFor(keyStatus)

    // 权限徽章：没读到就留空（见参数注释）。
    val permissionBadge = permissionSummary?.let(HostPermissions::badgeText)
    val permissionTone = when {
        permissionSummary == null -> PaBadgeTone.Neutral
        // 有用户能处理的 → 警示色，那是他该做的事
        permissionSummary.actionable > 0 -> PaBadgeTone.Warning
        // ⚠️ 只剩"要在电脑上授权"的那几项 → **不能掉进下面的 Success**。
        //    那等于告诉用户"全都好了"，而写 `Settings.Global` 那几条能力
        //    其实一条都跑不了。Accent 而不是 Warning：它确实是用户的待办，
        //    但**不是**"去系统设置点一下"—— 给警示色会让他去设置里找一个不存在的开关。
        permissionSummary.needsComputer > 0 -> PaBadgeTone.Accent
        // 用户做完了、只是应用还没做 → 中性色。**不能给 Success**：
        // 那等于告诉用户"全都好了"，而截图其实还拿不到。
        permissionSummary.appSidePending > 0 -> PaBadgeTone.Neutral
        else -> PaBadgeTone.Success
    }

    PaScreen(title = "设置", modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PaSpace.screenH,
                end = PaSpace.screenH,
                top = PaSpace.xs,
                bottom = PaSpace.l,
            ),
            verticalArrangement = Arrangement.spacedBy(PaSpace.s),
        ) {

            item { PaSectionTitle("模型") }
            item {
                PaListGroup {
                    PaListRow(
                        icon = Icons.Default.Key,
                        title = "API Key",
                        subtitle = "填入自己的 Key，加密存在本机",
                        badge = keyBadge,
                        badgeTone = keyTone,
                        onClick = onOpenKeys,
                    )
                    PaListDivider()
                    // ⚠️ 这一行与上一行是**两步**，不是同一件事的两半：
                    //    上面是"通行证"（填 Key），下面是"派活规则"（挑模型、定档位）。
                    //    合成一项的后果是用户填完 Key 就以为完事了 ——
                    //    而实际上一个模型都没配，任务跑不起来。
                    PaListRow(
                        icon = Icons.Default.Memory,
                        title = "模型配置",
                        subtitle = "挑模型、定档位、指定谁来做调度",
                        onClick = onOpenModels,
                    )
                    PaListDivider()
                    // ⚠️ 第三行是"把模型**接到别处去**"，与前两行是另一个方向：
                    //    前两行解决"本应用自己怎么调模型"，这一行解决
                    //    "让 dsh 也用上这些模型"。合成一行的后果是用户以为
                    //    配好模型就自动对 dsh 生效了 —— 而它需要**单独开启**。
                    PaListRow(
                        icon = Icons.Default.Cable,
                        title = "dsh 集成",
                        subtitle = "在本机起一个只供 dsh 用的模型出口",
                        onClick = onOpenDsh,
                    )
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("插件")
            }
            item {
                PaListGroup {
                    PaListRow(
                        icon = Icons.Default.Extension,
                        title = "插件源",
                        subtitle = "管理订阅地址与信任级别",
                        onClick = onOpenSources,
                    )
                    PaListDivider()
                    PaListRow(
                        icon = Icons.Default.FileDownload,
                        title = "本地导入",
                        subtitle = "从文件安装插件",
                        onClick = onOpenImport,
                    )
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("权限与安全")
            }
            item {
                PaListGroup {
                    PaListRow(
                        icon = Icons.Default.Shield,
                        title = "权限状态",
                        subtitle = "无障碍、悬浮窗、通知、截图",
                        // ⚠️ 这里先后经历过两句假话：
                        //    ① 最早是写死的 `"待检查"` —— 一句**承诺**，
                        //       而没有任何代码在检查它；
                        //    ② 后来改成 `"未实现"` —— 不再骗人，但它也**不是**事实：
                        //       无障碍和悬浮窗其实都能查。
                        //    现在读的是真状态（见 HostPermissions）。
                        badge = permissionBadge,
                        badgeTone = permissionTone,
                        onClick = onOpenPermissions,
                    )
                    PaListDivider()
                    // ⚠️ 这一行与上一行是**两件事**：
                    //    上面是"系统让不让我们看"（系统权限），
                    //    下面是"我们准不准自己动手"（能力授权）。
                    //    合成一行会让用户以为开了权限就等于放行了能力 ——
                    //    而两者是彼此独立的两套授权，各管一段。
                    PaListRow(
                        icon = Icons.Default.PlayArrow,
                        title = "第 0 档能力",
                        // ⚠️ 副标题必须与那一页**实际有什么**对得上。
                        //    它曾经写的是"改设置、调媒体、发通知" —— 那 14 条
                        //    设备控制类能力已经被移出第 0 档（见 CapabilityGroup），
                        //    而这里的文案留了下来：用户按它点进去，看到的是
                        //    一页文件能力，于是会以为"功能被砍了"。
                        //    （2026-09-23 真机目击）
                        subtitle = "不占屏幕就能做的事：读写文件、发通知",
                        onClick = onOpenCapabilities,
                    )
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("关于")
            }
            item {
                PaListGroup {
                    PaListRow(
                        icon = Icons.Default.Info,
                        title = "PocketAgent",
                        subtitle = "开源 · 数据不出设备 · 无自有服务端",
                        trailing = "M0",
                        onClick = null,
                    )
                }
            }
        }
    }
}

/**
 * 凭据状态 → 徽章文案与色调。
 *
 * ⚠️ "都不可用"用 `Warning` 而不是 `Danger`。
 *    它的意思是"需要你去处理一下"，而 Key 本身可能完好 ——
 *    比如余额没了（充值即可）、或者只是连不上（检查网络）。
 *    标红会让用户以为 Key 坏了然后去删它，而那正是本设计
 *    从头到尾在避免的那个动作。
 */
private fun keyBadgeFor(status: SettingsViewModel.KeyStatus): Pair<String, PaBadgeTone> =
    when (status) {
        SettingsViewModel.KeyStatus.Loading -> "…" to PaBadgeTone.Neutral

        is SettingsViewModel.KeyStatus.Configured -> when {
            status.total == 0 -> "未配置" to PaBadgeTone.Warning
            status.usable > 0 -> "可用" to PaBadgeTone.Success
            else -> "都不可用" to PaBadgeTone.Warning
        }

        SettingsViewModel.KeyStatus.Broken -> "打不开" to PaBadgeTone.Danger
    }
