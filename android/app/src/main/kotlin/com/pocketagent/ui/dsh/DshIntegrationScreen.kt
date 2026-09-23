package com.pocketagent.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.data.DshIntegrationStatus
import com.pocketagent.provider.gateway.dsh.DshWriteResult
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * dsh 集成页。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个页面存在的理由是**让一条看不见的链路变得可验证**
 * ═══════════════════════════════════════════════════════════════
 *
 * 装配好之后，"网关起没起来、配置写没写出去"这两件事在代码里都是布尔值，
 * 在用户那里却完全没有痕迹。没有这个页面，用户点不了任何东西，
 * 我们也没法在真机上确认那条链到底通没通。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 页面上有两处**必须如实说出来的限制**
 * ═══════════════════════════════════════════════════════════════
 *
 * 它们不是"以后会修好的小毛病"，而是当前形态的**真实边界**：
 *
 * 1. **网关只在 PocketAgent 运行时有效** —— 它跑在本进程里，没有前台服务。
 *    应用被系统回收之后 dsh 就连不上了。
 * 2. **每次开启端口都会变** —— 端口是随机分配的（一条安全加固）。
 *    所以"重新开启"之后，dsh 那边的地址也要跟着改。
 *
 * 不说出来的后果是：用户开启 → 关掉应用 → dsh 报连接被拒 →
 * 他以为是我们把 dsh 弄坏了，排查方向完全错。
 * 这正是本项目反复强调的那类**静默失败**。
 */
@Composable
fun DshIntegrationScreen(
    viewModel: DshIntegrationViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val working = ui is DshIntegrationViewModel.UiState.Working
    val ready = ui as? DshIntegrationViewModel.UiState.Ready
    val status = ready?.status ?: DshIntegrationStatus.Off

    PaScreen(
        title = "dsh 集成",
        subtitle = "让 dsh 用上你在这里配的模型",
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

            item { StatusBlock(working = working, status = status) }

            item {
                ActionBlock(
                    working = working,
                    status = status,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                )
            }

            if (!working && status is DshIntegrationStatus.On) {
                item {
                    DraftBlock(
                        path = viewModel.draftSettingsPath,
                        text = ready?.draftSettings,
                    )
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("你需要知道")
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
                    // ⚠️ 这两条不是"以后会修好的小毛病"，而是当前形态的真实边界。
                    //    不说出来的后果见本文件的类注释。
                    PaBanner(
                        tone = PaBannerTone.Warning,
                        title = "网关只在 PocketAgent 运行时有效",
                        description = "它跑在本应用进程里，还没有前台服务保活。应用被系统回收之后，" +
                            "dsh 会连不上 —— 需要重新开启，并把配置重新抄一遍。",
                    )
                    PaBanner(
                        tone = PaBannerTone.Info,
                        title = "每次开启，端口都会变",
                        description = "端口由系统随机分配（这是一条安全加固，不是缺陷）。" +
                            "所以重新开启之后，dsh 那边的地址要跟着更新。",
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  状态
// ─────────────────────────────────────────────────────────────────

@Composable
private fun StatusBlock(working: Boolean, status: DshIntegrationStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        when {
            working -> PaBanner(
                tone = PaBannerTone.Info,
                title = "正在处理…",
                description = "网关的启停要读几个文件，稍等。",
            )

            status is DshIntegrationStatus.Off -> PaBanner(
                tone = PaBannerTone.Info,
                title = "未开启",
                description = "开启之后，本机会在 127.0.0.1 上起一个只供 dsh 使用的模型出口。",
            )

            status is DshIntegrationStatus.Blocked -> PaBanner(
                tone = PaBannerTone.Warning,
                title = "没能开启",
                // ⚠️ 这条文案来自容器，它已经点名了"用户该去做什么"。
                //    这里**不要**再包一层"请检查配置"之类的话 —— 那会把
                //    具体动作盖掉，用户又回到"不知道该干什么"。
                description = status.reason,
            )

            status is DshIntegrationStatus.On -> {
                // ⚠️ 「网关起来了」与「配置送达了」是**两件独立的事**，
                //    所以这里是两条横幅而不是一条。
                //    合成一条的后果：网关起来了但配置没写出去时，
                //    用户看到"运行中"就以为完事了，然后 dsh 报 MISSING_CREDENTIAL。
                PaBanner(
                    tone = PaBannerTone.Success,
                    title = "网关运行中",
                    description = "dsh 要连的地址：${status.baseUrl}",
                )
                DeliveryBlock(status.delivery)
            }
        }
    }
}

@Composable
private fun DeliveryBlock(delivery: DshWriteResult) {
    when (delivery) {
        is DshWriteResult.Success -> PaBanner(
            tone = PaBannerTone.Info,
            title = if (delivery.credentialsWritten) "配置已写入" else "配置已写入（凭据未写）",
            description = delivery.location,
        )

        is DshWriteResult.Partial -> PaBanner(
            tone = PaBannerTone.Warning,
            title = "配置写好了，但凭据没送到",
            // ⚠️ `Partial` 的理由里带着"要手工补哪一行"（由 DshGatewaySession 保证）。
            //    这正是它必须独立于 Success 的原因 —— 见 DshWriteResult 的注释。
            description = delivery.reason,
        )

        is DshWriteResult.Failure -> PaBanner(
            tone = PaBannerTone.Danger,
            title = "配置没写进去",
            description = delivery.reason,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  操作
// ─────────────────────────────────────────────────────────────────

@Composable
private fun ActionBlock(
    working: Boolean,
    status: DshIntegrationStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // `Blocked` 与 `Off` 都走"开启"这一个动作 —— 用户不需要区分
    // "还没点过"和"上次失败了"，他要做的都是同一件事：再点一次。
    val running = status is DshIntegrationStatus.On

    PaButton(
        text = if (running) "关闭" else "开启",
        onClick = if (running) onStop else onStart,
        enabled = !working,
        fillWidth = true,
        style = if (running) com.pocketagent.ui.design.PaButtonStyle.Glass
        else com.pocketagent.ui.design.PaButtonStyle.Primary,
    )
}

// ─────────────────────────────────────────────────────────────────
//  要抄给 dsh 的配置
// ─────────────────────────────────────────────────────────────────

@Composable
private fun DraftBlock(path: String, text: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        PaSectionTitle("要抄给 dsh 的配置")

        Text(
            // ⚠️ 必须解释"为什么要在这里显示全文" —— 否则用户会觉得
            //    "你们不是写了文件吗，为什么还要我从屏幕上抄"。
            //    真相是应用私有目录在非 root 设备上**根本打不开**。
            text = "应用私有目录（非 root 设备打不开），所以全文显示在这里：",
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )

        Text(
            text = path,
            style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
            color = PaColor.TextTertiary,
        )

        if (text == null) {
            PaBanner(
                tone = PaBannerTone.Warning,
                title = "读不到内容",
                description = "文件可能还没写出来。关掉再开启一次试试。",
            )
        } else {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PaSpace.xs))
                        .background(PaColor.Surface)
                        .padding(PaSpace.s),
                    style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
                    color = PaColor.TextPrimary,
                )
            }
            Text(
                text = "长按上面的文字可选中并复制。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
    }
}
