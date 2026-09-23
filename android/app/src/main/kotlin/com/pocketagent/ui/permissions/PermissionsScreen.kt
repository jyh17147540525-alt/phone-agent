package com.pocketagent.ui.permissions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.core.common.permission.GrantState
import com.pocketagent.core.common.permission.PermissionAction
import com.pocketagent.core.common.permission.PermissionSummary
import com.pocketagent.core.common.permission.PermissionVerdict
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * 权限状态页。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个页面的立场：**宁可说"现在拿不到"，也不说一句好听的假话**
 * ═══════════════════════════════════════════════════════════════
 *
 * 之前设置页那一行写的是"待检查" —— 那是一句**承诺**，而没有任何代码在检查它。
 * 用户读到"待检查"会以为应用会自己查，或者点进去就能看到，而两者都不成立。
 *
 * 现在这里是真的在查。三种"没开"必须分开，因为它们**要用户做的事完全不同**：
 *
 *  · **未开启** —— 去系统设置开一下就行（给按钮）
 *  · **尚未实现** —— 应用侧还没做，用户做什么都没用（**不给按钮**）
 *  · 以及本机不适用（例如 API 32 上没有通知权限可给）—— 也是不给按钮
 *
 * 给一个点了没用的按钮，比不给按钮更糟：用户会以为自己点错了，
 * 然后反复点、或者去别处找。**不要把"未实现"伪装成"可用"。**
 *
 * ⚠️ 判定逻辑全在 `:core:common` 的 `HostPermissions`（纯 Kotlin、有测试）。
 *    本文件只负责画和跳转。
 */
@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ⚠️⚠️ 必须挂在 `ON_RESUME` 上。用户在这个页面点"去开启"，跳到系统设置，
    //      开完再回来 —— 如果看到的是**旧**状态，他会得出"开了也没用"的结论，
    //      然后再去开一遍。而这个失败没有任何东西会报错。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // 只有 API 33+ 才会走到这个 launcher（该显示哪个动作由纯逻辑决定，
    // 而它在 API 32 上给的是"去通知设置"）。
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // 不论用户点了允许还是拒绝，都重读一次 —— 系统的真实状态才是唯一事实。
        viewModel.refresh()
    }

    PaScreen(
        title = "权限状态",
        subtitle = "这些是应用能不能干活的前提",
        onBack = onBack,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PaSpace.screenH,
                end = PaSpace.screenH,
                top = PaSpace.xs,
                bottom = PaSpace.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(PaSpace.s),
        ) {

            item { SummaryBlock(ui.summary) }

            items(ui.verdicts, key = { it.id }) { verdict ->
                PermissionCard(
                    verdict = verdict,
                    onAct = {
                        when (val action = verdict.action) {
                            // 已授予 / 应用还没做 → 没有动作，按钮本来就不会画。
                            null -> Unit

                            PermissionAction.REQUEST_NOTIFICATIONS ->
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                            else -> viewModel.settingsIntent(action)?.let(context::startActivity)
                        }
                    },
                )
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("你需要知道")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
                    // ⚠️ 这两条不是免责声明，是用户会遇到、而且会误解的两件事。
                    //    不说出来的后果：用户遇到时以为是应用坏了。
                    PaBanner(
                        tone = PaBannerTone.Warning,
                        title = "无障碍权限很大，而且会被重置",
                        description = "开启之后，应用能看到屏幕上的内容 —— " +
                            "包括别人发给你的消息、以及输入框里刚打的字。" +
                            "我们只在自己执行任务时读取，但这个权限本身的范围就是这么大。" +
                            "另外：覆盖安装之后无障碍权限会被系统重置，" +
                            "需要重新开一次。",
                    )
                    PaBanner(
                        tone = PaBannerTone.Info,
                        title = "部分机型会拦住侧载应用的无障碍权限",
                        description = "这是系统的「受限设置」保护。如果开关点不动、或者被自动关掉，" +
                            "需要在系统设置里先允许本应用开启受限设置 —— 各机型路径不同。" +
                            "（红米 K60 / HyperOS 实测不会拦。）",
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  汇总
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SummaryBlock(summary: PermissionSummary) {
    // ⚠️ 三种情况分开说，因为它们要用户做的事不同：
    //    · 有待开启的 → 去开（用户能解决）
    //    · 用户做完了但应用还没做 → **明确说"剩下的在我们这边"**，
    //      否则用户会继续在系统设置里找，找一个不存在的东西
    //    · 都好了 → 就绪
    val banner = when {
        summary.actionable > 0 -> Triple(
            PaBannerTone.Warning,
            "还有 ${summary.actionable} 项没开",
            "下面的每一项都说明了它是干什么用的，以及为什么需要它。",
        )

        summary.appSidePending > 0 -> Triple(
            PaBannerTone.Info,
            "你已经做完了，剩下的在我们这边",
            "系统侧能给的都给了。还有 ${summary.appSidePending} 项能力应用还没实现，" +
                "那不需要你做什么 —— 也不需要你去系统设置里找。",
        )

        else -> Triple(
            PaBannerTone.Success,
            "全部就绪",
            "四项能力都已可用。",
        )
    }

    PaBanner(tone = banner.first, title = banner.second, description = banner.third)
}

// ─────────────────────────────────────────────────────────────────
//  单项
// ─────────────────────────────────────────────────────────────────

@Composable
private fun PermissionCard(verdict: PermissionVerdict, onAct: () -> Unit) {
    PaListGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaSpace.s),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = verdict.label,
                    style = PaType.body,
                    color = PaColor.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                PaBadge(text = stateText(verdict.state), tone = stateTone(verdict.state))
            }

            // ⚠️ 用途必须写出来。不说清"这项是干嘛的"，用户没法判断该不该给 ——
            //    而不理解的权限，理性的选择就是拒绝。
            Text(
                text = verdict.purpose,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )

            // `action == null` 时不画按钮。这不是省略，是**刻意的**：
            // 已授予的没什么可做；应用还没做的，按钮点了也没用。
            verdict.action?.let { action ->
                Spacer(Modifier.height(PaSpace.xxs))
                PaButton(
                    text = actionText(action),
                    onClick = onAct,
                    style = PaButtonStyle.Glass,
                    fillWidth = true,
                )
            }
        }
    }
}

private fun stateText(state: GrantState): String = when (state) {
    GrantState.Granted -> "已开启"
    GrantState.Denied -> "未开启"
    // ⚠️ "尚未实现"而不是"未授权" —— 后者会让用户去系统设置里找一个不存在的开关。
    GrantState.Undeclared -> "尚未实现"
}

private fun stateTone(state: GrantState): PaBadgeTone = when (state) {
    GrantState.Granted -> PaBadgeTone.Success
    GrantState.Denied -> PaBadgeTone.Warning
    // Neutral 而不是 Warning：这不是用户的待办，给警示色等于在催他做一件他做不了的事。
    GrantState.Undeclared -> PaBadgeTone.Neutral
}

/**
 * ⚠️ 按钮文案必须点出**去的是哪个地方**。
 *
 * 尤其通知这一项：API 33 以上是"请求权限"（弹系统对话框），
 * API 32 及以下是"去通知设置"（那是应用通知开关所在处）。
 * 两处写法都叫"开启通知"的话，API 32 的用户会以为该弹的东西没弹出来。
 */
private fun actionText(action: PermissionAction): String = when (action) {
    PermissionAction.OPEN_ACCESSIBILITY_SETTINGS -> "去开启无障碍"
    PermissionAction.OPEN_OVERLAY_SETTINGS -> "去授权悬浮窗"
    PermissionAction.REQUEST_NOTIFICATIONS -> "请求通知权限"
    PermissionAction.OPEN_NOTIFICATION_SETTINGS -> "去通知设置"
}
