package com.pocketagent.ui.capability

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.capabilitylogic.Capability
import com.pocketagent.capabilitylogic.CapabilityAuditEvent
import com.pocketagent.capabilitylogic.CapabilityAuditOutcome
import com.pocketagent.capabilitylogic.CapabilityMessages
import com.pocketagent.capabilitylogic.CapabilityOutcome
import com.pocketagent.capabilitylogic.CapabilityOutcomeTone
import com.pocketagent.capabilitylogic.CapabilityRisk
import com.pocketagent.capabilitylogic.ConfirmReason
import com.pocketagent.capabilitylogic.NextStep
import com.pocketagent.capabilitylogic.ParamSpec
import com.pocketagent.filelogic.ScopeRoot
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaFilterChip
import com.pocketagent.ui.design.PaListDivider
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「第 0 档能力」页。
 *
 * ═══════════════════════════════════════════════════════════════
 *  第 0 档是什么：**不占屏幕的前提下，做用户真的需要的事**
 * ═══════════════════════════════════════════════════════════════
 *
 * ⚠️ 这里有一个容易搞反的地方，值得写下来：
 *    **「不占屏幕」是约束，不是目的。**
 *
 *    最初的实现把它当成了目的，于是收集了一批"因为不占屏所以能做"的设备开关
 *    （调音量、切深色模式、改亮度、收通知栏…）。它们全都满足约束，
 *    但**没有一个帮用户完成一件事** —— 用户不会因为"agent 能帮我改亮度"
 *    而觉得这个应用有用。
 *
 *    正确的问题应该是：*在不占屏的前提下，什么能力对用户最有价值？*
 *    答案是**文件与数据** —— 读一份文档、把一段文字写进文件、整理目录。
 *    那正是"接近电脑端办公"的那一类，也正好是**唯一不需要任何特权**的通道
 *    （SAF，用户点一次目录即可）。
 *
 * ⇒ 所以这一页现在显示的是 `CapabilityGroup.FILES` 与 `NOTIFY` 两组。
 *   设备控制类（`CapabilityGroup.DEVICE`）代码保留、但**不在这一页** ——
 *   它们技术上同样零占屏，只是不属于第 0 档的产品范围。
 *   见 `CapabilityGroup` 里「技术判据 vs 产品范围」那一段。
 *
 * 这一页是它们的入口。在此之前，裁决 / 规划 / 通道 / 编排四层都已有测试，
 * 但**没有任何界面能调用它们** —— 对用户来说等于不存在。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这一页刻意不做的事
 * ═══════════════════════════════════════════════════════════════
 *
 * · **不自己判断"能不能执行"** —— 判定全在 `CapabilityGuard`（纯模块、有测试）。
 *   这里连一个 `if (risk == SAFE)` 都不该有。
 * · **不把"还没放行"画成失败** —— 见 `CapabilityMessages`：那是 Info，
 *   因为用户点一下「放行」就能解决。
 * · **不把结果说成"设置已经改了"** —— 通道只保证"它接受了这次写入"。
 *
 * ⚠️ 文案一律来自 `CapabilityMessages`（纯模块、有测试），本文件只做映射。
 */
@Composable
fun CapabilityScreen(
    viewModel: CapabilityViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()

    // ★ 目录选择器。
    //
    // ⚠️ 用 `OpenDocumentTree` 而**不是** `OpenDocument`：
    //    前者授权的是**一整棵目录树**（之后可以长期访问里面任意文件），
    //    后者只授权**单个文件**（下次换个文件又要重选一次）。
    //    文件办公要的显然是前者。
    //
    // ⚠️ 拿到 URI 之后**必须**去 `takePersistableUriPermission`
    //    （在 `viewModel.grantDirectory` 里做）—— 不做的话这次授权
    //    只活到进程结束。表现是"今天能用，明天打开说还没授权"，
    //    而用户完全不知道自己做错了什么。
    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // null = 用户按了返回。**什么都不做，也不要提示** ——
        // 取消是正常操作，弹一句"授权失败"会让人以为出问题了。
        if (uri != null) viewModel.grantDirectory(uri)
    }

    PaScreen(
        title = "第 0 档能力",
        subtitle = "不占用你的屏幕，也能替你做事",
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

            item { IntroBlock() }

            // ★ 目录授权放在能力列表**上面**。
            //
            //    理由很具体：文件类能力全都依赖它。放在下面的话，
            //    用户会先点一遍「读取文件」、拿到"还没有授权任何目录"，
            //    再回头找这个区块 —— 而那个顺序本可以避免。
            item {
                DirectoryBlock(
                    granted = ui.grantedDirs,
                    unsupported = ui.unsupportedDirs,
                    error = ui.grantError,
                    onPick = { directoryPicker.launch(null) },
                    onRevoke = viewModel::revokeDirectory,
                    onDismissError = viewModel::dismissGrantError,
                )
            }

            // ⚠️ 结果横幅放在**最上面**：用户刚点完一个按钮，视线还在上半屏，
            //    把结果放在列表末尾等于让他自己去找。
            ui.last?.let { outcome ->
                item {
                    OutcomeBlock(
                        outcome = outcome,
                        onConfirm = viewModel::confirm,
                        onGrant = viewModel::grantAndRetry,
                        onRetry = viewModel::retry,
                        onDismiss = viewModel::dismissLast,
                    )
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("可以直接用（${ui.safe.size}）")
            }
            items(ui.safe, key = { it.id }) { capability ->
                CapabilityCard(
                    capability = capability,
                    granted = true,
                    onRun = { viewModel.edit(capability) },
                )
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("需要你逐项放行（${ui.guarded.size}）")
            }
            item {
                // ⚠️ 这一句必须写出来。不说的话，用户看到"未放行"会以为
                //    是权限问题，然后去系统设置里找 —— 而这一条跟系统权限无关。
                // ⚠️ 这一句必须跟着**实际显示的能力**走。
                //    它原来写的是"亮度、断网、停用应用"—— 那几条是设备控制类，
                //    已经从这一页移出去了（见 CapabilityGroup）。
                //    留着一句与列表对不上的说明，比不写更糟：
                //    用户会去找那几条，然后以为它们被藏起来了。
                Text(
                    text = "这一组会动到你原有的东西（覆盖文件内容、删除、移动），" +
                        "所以默认不执行，需要你逐项打开。" +
                        "放行记录目前只在本次运行内有效，重启应用后要重新放行。",
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            }
            items(ui.guarded, key = { it.id }) { capability ->
                CapabilityCard(
                    capability = capability,
                    granted = capability.id in ui.grantedIds,
                    onRun = { viewModel.edit(capability) },
                )
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaSectionTitle("最近的动作")
                    Spacer(Modifier.weight(1f))
                    if (ui.events.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearAudit) {
                            Text(
                                text = "清空",
                                style = PaType.label,
                                color = PaColor.TextSecondary,
                            )
                        }
                    }
                }
            }
            if (ui.events.isEmpty()) {
                item {
                    Text(
                        text = "还没有执行过任何动作。",
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }
            } else {
                // 最新的在最上面 —— 用户关心的是"刚才那次做了什么"。
                //
                // ⚠️ 刻意**不给 key**。看上去 `timestamp-capabilityId-outcome`
                //    是个自然的 key，但它**不唯一**：同一毫秒内、同一能力、
                //    同一种结论的两条事件（失败原因不同 → 不会被合并）会撞上，
                //    而 Compose 遇到重复 key 是**直接崩**，不是画错。
                //    日志列表本来就没有"某一行要保持身份"的需求，按位置即可。
                items(ui.events.asReversed()) { event ->
                    AuditRow(event)
                }
            }
        }
    }

    editing?.let { capability ->
        ParamDialog(
            capability = capability,
            onRun = { args -> viewModel.run(capability, args) },
            onDismiss = viewModel::cancelEdit,
        )
    }

    ui.confirm?.let { pending ->
        ConfirmDialog(
            reason = pending.reason,
            message = pending.userMessage,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  说明
// ─────────────────────────────────────────────────────────────────

@Composable
private fun IntroBlock() {
    PaBanner(
        tone = PaBannerTone.Info,
        title = "这些能力不会占用你的屏幕",
        description = "它们直接读写文件或调系统服务 —— 不打开界面、不抢焦点、" +
            "不点你的屏幕，你可以照常刷手机。",
    )
}

// ─────────────────────────────────────────────────────────────────
//  目录授权
// ─────────────────────────────────────────────────────────────────

/**
 * 「你的目录」—— 文件类能力的**范围**从哪来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么这里必须**显示路径**，不能只显示名字
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为路径就是**范围判定的依据**：agent 请求的路径要落在这条路径之下
 * 才会被放行。所以用户看到的必须是那个**真的会被用来比较**的字符串 ——
 * 只显示「文档」两个字的话，他没有任何办法判断
 * "为什么我明明授权了，还说越界"。
 *
 * ⚠️ 也因此，`/sdcard/Documents` 与 `/storage/emulated/0/Documents`
 *    在这一页上**是两个不同的目录**（前者是后者的符号链接，
 *    但判定层只做字符串比较，不解析链接）。
 *    界面上给的是后者 —— 照着它用就不会踩到。这是已知取舍。
 */
@Composable
private fun DirectoryBlock(
    granted: List<ScopeRoot>,
    unsupported: List<Uri>,
    error: String?,
    onPick: () -> Unit,
    onRevoke: (Uri) -> Unit,
    onDismissError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        PaSectionTitle("你的目录（${granted.size}）")

        PaListGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PaSpace.s),
                verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
            ) {
                if (granted.isEmpty()) {
                    Text(
                        text = "还没有授权任何目录。读写文件这类能力只能在你授权的目录里工作，" +
                            "别的地方它够不到 —— 这是安全设计，不是缺陷。",
                        style = PaType.body,
                        color = PaColor.TextPrimary,
                    )
                } else {
                    granted.forEach { root ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = root.displayName,
                                    style = PaType.body,
                                    color = PaColor.TextPrimary,
                                )
                                Text(
                                    text = root.path,
                                    style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
                                    color = PaColor.TextTertiary,
                                )
                            }
                            // ⚠️ 「撤销」是真的撤销（系统的授权列表里少一条），
                            //    不是"界面上不再显示"。所以它不该做得太容易点 ——
                            //    用 Text 样式而不是 Danger，避免误触后又不知道怎么恢复。
                            TextButton(onClick = { onRevoke(Uri.parse(root.token)) }) {
                                Text(
                                    text = "撤销",
                                    style = PaType.label,
                                    color = PaColor.TextSecondary,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(PaSpace.xxs))
                // ⚠️ 文案是「选一个目录」而不是「添加」——
                //    后者的心智模型是"往列表里加一条"，而实际发生的是
                //    **系统弹出一个文件选择器**。文案要对得上行为。
                PaButton(
                    text = if (granted.isEmpty()) "选一个目录" else "再选一个目录",
                    onClick = onPick,
                    style = PaButtonStyle.Primary,
                    fillWidth = true,
                )

                // ⚠️ 这段限制必须写出来。不写的话，用户会去选一个网盘目录，
                //    然后发现"选了没用"，而完全不知道为什么 ——
                //    最自然的结论是"这个功能坏了"。
                Text(
                    text = "只能选手机存储里的目录（例如「文档」「下载」）。" +
                        "网盘类的位置没有本地路径，PocketAgent 判断不了操作范围，所以暂时不支持。" +
                        "Android/data 里的内容，系统不允许授权给任何应用。",
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            }
        }

        if (unsupported.isNotEmpty()) {
            PaBanner(
                tone = PaBannerTone.Warning,
                title = "有 ${unsupported.size} 个位置用不了",
                description = "你授权了它们，但它们不是手机存储里的目录 —— " +
                    "PocketAgent 没有办法判断操作范围，所以没有启用。",
            )
        }

        error?.let { message ->
            PaBanner(
                tone = PaBannerTone.Warning,
                title = "目录授权出问题了",
                description = message,
            )
            ActionRow(
                primaryText = null,
                onPrimary = null,
                secondaryText = "知道了",
                onSecondary = onDismissError,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  结果
// ─────────────────────────────────────────────────────────────────

@Composable
private fun OutcomeBlock(
    outcome: CapabilityOutcome,
    onConfirm: () -> Unit,
    onGrant: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        PaBanner(
            tone = bannerToneOf(CapabilityMessages.toneOf(outcome)),
            title = CapabilityMessages.titleOf(outcome),
            // ⚠️ 正文里可能有一条 adb 命令，要能长按复制 ——
            //    用户得把它带到电脑上去。
            description = CapabilityMessages.detailOf(outcome),
        )

        when (CapabilityMessages.nextStepOf(outcome)) {
            NextStep.NONE -> Unit

            // ⚠️ 确认这条路径在界面上走的是对话框，不会落到这里。
            //    留一个显式的分支而不是 `else ->`，是为了让"新增一种下一步"
            //    变成编译错误，而不是一个静默什么都不做的分支。
            NextStep.CONFIRM -> Unit

            NextStep.GRANT -> ActionRow(
                primaryText = "放行这一条，再来一次",
                onPrimary = onGrant,
                secondaryText = "知道了",
                onSecondary = onDismiss,
            )

            // ⚠️ "去配置"**不给按钮** —— 正文里那条命令就是全部要做的事，
            //    再给一个点了只是收起横幅的按钮，会让人以为点它就能解决。
            NextStep.GO_CONFIGURE -> ActionRow(
                primaryText = null,
                onPrimary = null,
                secondaryText = "知道了",
                onSecondary = onDismiss,
            )

            NextStep.RETRY -> ActionRow(
                primaryText = "再试一次",
                onPrimary = onRetry,
                secondaryText = "知道了",
                onSecondary = onDismiss,
            )
        }
    }
}

@Composable
private fun ActionRow(
    primaryText: String?,
    onPrimary: (() -> Unit)?,
    secondaryText: String?,
    onSecondary: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
        if (primaryText != null && onPrimary != null) {
            PaButton(
                text = primaryText,
                onClick = onPrimary,
                style = PaButtonStyle.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        if (secondaryText != null && onSecondary != null) {
            PaButton(
                text = secondaryText,
                onClick = onSecondary,
                style = PaButtonStyle.Text,
                modifier = if (primaryText == null) Modifier else Modifier.weight(1f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  单项能力
// ─────────────────────────────────────────────────────────────────

@Composable
private fun CapabilityCard(
    capability: Capability,
    granted: Boolean,
    onRun: () -> Unit,
) {
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
                    text = capability.id,
                    style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
                    color = PaColor.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                PaBadge(
                    text = if (granted) "可以执行" else "未放行",
                    tone = if (granted) PaBadgeTone.Success else PaBadgeTone.Neutral,
                )
            }

            // ⚠️ 说明必须写出来，而且要说清"会发生什么"。
            //    不理解的权限，理性的选择就是拒绝。
            Text(
                text = capability.summary,
                style = PaType.body,
                color = PaColor.TextPrimary,
            )

            if (capability.params.isNotEmpty()) {
                Text(
                    text = "需要填：${capability.params.joinToString("、") { it.summary }}",
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            }

            Spacer(Modifier.height(PaSpace.xxs))
            PaButton(
                text = if (granted) "执行" else "放行后执行",
                onClick = onRun,
                style = if (granted) PaButtonStyle.Glass else PaButtonStyle.Primary,
                icon = Icons.Default.PlayArrow,
                fillWidth = true,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  审计
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AuditRow(event: CapabilityAuditEvent) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = clockFormat.format(Date(event.timestamp)),
                style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = event.capabilityId,
                style = PaType.caption.copy(fontFamily = FontFamily.Monospace),
                color = PaColor.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            PaBadge(text = outcomeText(event.outcome), tone = outcomeTone(event.outcome))
        }
        if (event.targetDigest.isNotBlank()) {
            // ⚠️ 摘要里可能有参数**原值**吗？没有 —— `digestOf` 只摘设置键与包名。
            //    这是审计日志的纪律之一（见 CapabilityAuditLog 文件头）。
            Text(
                text = "动到：${event.targetDigest}",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun outcomeText(outcome: CapabilityAuditOutcome): String = when (outcome) {
    CapabilityAuditOutcome.EXECUTED -> "已执行"
    CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM -> "确认后执行"
    // ⚠️ 「未放行」不能写成「被拒绝」：前者是门还在、你没开；后者是路永远堵着。
    CapabilityAuditOutcome.NOT_GRANTED -> "未放行"
    CapabilityAuditOutcome.DENIED -> "被拒绝"

    // ⚠️⚠️ 不能写「执行失败」。
    //
    // 这一档在审计层同时涵盖两种结局（见 `CapabilityAuditOutcome.FAILED` 的注释）：
    //   ① 通道**没准备好**（权限没给、Shizuku 没跑）→ 用户要做的是"去配置"
    //   ② 通道**真的报错**（命令返回非零）→ 用户要做的是"查参数 / 重试"
    //
    // 结果横幅对这两种说的是**不同的话**（「还差一步配置」vs「试过了，没成」，
    // 见 `CapabilityMessages`）。而审计区只有一格 badge —— 写「执行失败」
    // 就等于把它钉死成 ②，于是同一个动作在历史记录里是"失败"、
    // 在横幅里是"还差一步配置"，而两条里最多只有一条是对的。
    //
    // ⇒ 用一个**不暗示原因**的说法。审计区给的是"结局"，不是"下一步"；
    //   下一步由横幅负责（那里才有空间写那句话）。
    CapabilityAuditOutcome.FAILED -> "没做成"

    CapabilityAuditOutcome.CONFIRMATION_DECLINED -> "你选了不要"
    CapabilityAuditOutcome.CONFIRMATION_TIMEOUT -> "确认超时"
}

private fun outcomeTone(outcome: CapabilityAuditOutcome): PaBadgeTone = when (outcome) {
    CapabilityAuditOutcome.EXECUTED,
    CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM,
    -> PaBadgeTone.Success

    CapabilityAuditOutcome.NOT_GRANTED -> PaBadgeTone.Accent
    CapabilityAuditOutcome.DENIED -> PaBadgeTone.Danger
    CapabilityAuditOutcome.FAILED -> PaBadgeTone.Warning
    CapabilityAuditOutcome.CONFIRMATION_DECLINED,
    CapabilityAuditOutcome.CONFIRMATION_TIMEOUT,
    -> PaBadgeTone.Neutral
}

// ─────────────────────────────────────────────────────────────────
//  色调映射（语义 → 具体颜色，只在界面层做）
// ─────────────────────────────────────────────────────────────────

private fun bannerToneOf(tone: CapabilityOutcomeTone): PaBannerTone = when (tone) {
    CapabilityOutcomeTone.Success -> PaBannerTone.Success
    CapabilityOutcomeTone.Info -> PaBannerTone.Info
    CapabilityOutcomeTone.Warning -> PaBannerTone.Warning
    CapabilityOutcomeTone.Danger -> PaBannerTone.Danger
}

// ─────────────────────────────────────────────────────────────────
//  参数对话框
// ─────────────────────────────────────────────────────────────────

/**
 * 参数表单。
 *
 * ⚠️ **不做前端校验**。填错了就填错了 —— 裁决器的第 3 步会给出
 *    "哪个参数、哪个值、允许范围是什么"那句话，而它比这里手写的
 *    任何提示都准确。在这里再写一遍校验，就多了一处会与裁决器不一致的地方，
 *    而不一致的表现是"界面说没问题、执行说参数非法"。
 */
@Composable
private fun ParamDialog(
    capability: Capability,
    onRun: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // ⚠️ 默认值取自声明本身：Choice 取第一项、IntIn 取范围下界。
    //    不给默认值的话，用户面对一个空框不知道能填什么。
    val values = remember(capability.id) {
        mutableStateMapOf<String, String>().apply {
            capability.params.forEach { spec ->
                put(spec.name, defaultValueOf(spec))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "执行「${capability.id}」？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PaSpace.s)) {
                Text(
                    text = capability.summary,
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )

                if (capability.risk == CapabilityRisk.GUARDED) {
                    Text(
                        text = "执行前还会再问你一次 —— 这一次只是填参数。",
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }

                capability.params.forEach { spec ->
                    ParamField(
                        spec = spec,
                        value = values[spec.name].orEmpty(),
                        onChange = { values[spec.name] = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(capability.params.associate { it.name to values[it.name].orEmpty() }) },
            ) {
                Text(text = "继续", style = PaType.label, color = PaColor.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = PaType.label, color = PaColor.TextSecondary)
            }
        },
    )
}

@Composable
private fun ParamField(
    spec: ParamSpec,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PaSpace.xxs)) {
        Text(
            text = spec.summary,
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )

        when (spec) {
            is ParamSpec.Choice -> Row(
                horizontalArrangement = Arrangement.spacedBy(PaSpace.xxs),
            ) {
                // ⚠️ 选项数量可控（目录里最多 7 个），所以直接铺开；
                //    多了要换下拉，但下拉在玻璃风格里很丑，能铺就铺。
                spec.values.forEach { option ->
                    PaFilterChip(
                        text = option,
                        selected = option == value,
                        onClick = { onChange(option) },
                    )
                }
            }

            else -> PaTextField(
                value = value,
                onValueChange = onChange,
                placeholder = placeholderOf(spec),
            )
        }
    }
}

private fun defaultValueOf(spec: ParamSpec): String = when (spec) {
    is ParamSpec.Choice -> spec.values.firstOrNull().orEmpty()
    // ⚠️ 取范围**下界**而不是中间值：下界一定是合法的，而"中间值"
    //    在一段小范围里可能算出来等于下界 —— 那就成了"看起来填了、其实没填"。
    is ParamSpec.IntIn -> spec.range.first.toString()
    else -> ""
}

private fun placeholderOf(spec: ParamSpec): String = when (spec) {
    is ParamSpec.IntIn -> "${spec.range.first} ~ ${spec.range.last}"
    is ParamSpec.PackageName -> "com.example.app"
    is ParamSpec.Text -> "不超过 ${spec.maxLength} 个字"
    is ParamSpec.Choice -> ""
}

// ─────────────────────────────────────────────────────────────────
//  确认对话框
// ─────────────────────────────────────────────────────────────────

/**
 * 确认框。
 *
 * ⚠️ 文案来自判定层（`RequireConfirmation.userMessage`），里面带着
 *    **这一次具体要执行的那条命令**。不要在这里另写一句 ——
 *    用户确认的是他看到的那个操作，不是"某个叫这个名字的能力"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 标题与按钮必须按 [reason] 分叉
 * ═══════════════════════════════════════════════════════════════
 *
 * 底下有**两个**闸（见 [ConfirmReason]），而它们问的是两件不同的事。
 * 如果两个框的标题和按钮都用同一套字，用户在屏幕上看到的就是"同一个框弹了两次" ——
 * 而"看起来是同一个"正是他学会"看见就点同意"的起点。那时确认门就白设了。
 *
 * 所以这里让第二个框在**第一眼**上就不一样：它问的是那个文件，不是这个动作。
 *
 * ⚠️ 只有两个值，所以写成 `if` 而不是 `when` —— 但**不要**给它加
 *    `else ->` 兜底：将来多一种确认原因时，兜底会让新那一闸
 *    静默地沿用"执行"这套字，而那正是这里要避免的。
 */
@Composable
private fun ConfirmDialog(
    reason: ConfirmReason,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 能力闸：问"你允许做这类事吗" —— 所以是「动作」与「执行」。
    // 文件闸：问"这个文件会被覆盖/删除/移动，你确定吗" —— 所以是「文件」与「确认」。
    val asksAboutFile = reason == ConfirmReason.OPERATION_AFFECTS_FILES

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = if (asksAboutFile) "要动这个文件吗？" else "要执行这个动作吗？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            SelectionContainer {
                Text(
                    text = message,
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (asksAboutFile) "确认" else "执行",
                    style = PaType.label,
                    color = PaColor.Accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "不要", style = PaType.label, color = PaColor.TextSecondary)
            }
        },
    )
}
