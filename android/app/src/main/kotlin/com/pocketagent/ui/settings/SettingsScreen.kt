package com.pocketagent.ui.settings

import com.pocketagent.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.theme.ThemeMode
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
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
    onOpenPrivilegeMode: (() -> Unit)? = null,
    onOpenPersona: (() -> Unit)? = null,
    /** 当前明暗偏好 */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 切换明暗偏好 */
    onThemeModeChange: (ThemeMode) -> Unit = {},
    /** 成熟内容授权（成年确认 + 开关，两个门） */
    maturePermission: com.pocketagent.legacy.MatureContentGate.Permission =
        com.pocketagent.legacy.MatureContentGate.Permission(),
    /** 确认/撤回成年 */
    onAdultConfirmedChange: (Boolean) -> Unit = {},
    /** 打开/关闭成熟内容 */
    onMatureEnabledChange: (Boolean) -> Unit = {},
    /** 长期记忆库里有多少条原子。0 = 还没记住任何事 */
    memoryAtomCount: Int = 0,
    /** 通知权限是否已授予（Android 13+ 必须由用户点出来，不能只写在清单里） */
    notificationGranted: Boolean = false,
    /** 点"开启通知" */
    onRequestNotification: () -> Unit = {},
    /** 粗略定位是否已授予（查天气用） */
    locationGranted: Boolean = false,
    /** 点"允许大致位置" */
    onRequestLocation: () -> Unit = {},
    /** 麦克风是否已授予（按住说话用） */
    micGranted: Boolean = false,
    /** 点"允许录音" */
    onRequestMic: () -> Unit = {},
    /** 是否自动朗读回复 */
    ttsEnabled: Boolean = false,
    /** 切换自动朗读 */
    onToggleTts: (Boolean) -> Unit = {},
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
                    PaListDivider()
                    // ★ 角色设定是**聊天体验的上游**：它决定"和你说话的是谁"。
                    //   放在权限之前 —— 用户更常来改的是这个。
                    PaListRow(
                        icon = Icons.Default.Person,
                        title = "角色设定",
                        subtitle = "自定义角色 / 用你记得的样子做一个人",
                        onClick = onOpenPersona,
                    )
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
                    PaListDivider()
                    // ★ 运行模式是**权限的上游**：系统给不给我们权限是一回事，
                    //   我们准不准自己用 root 能力是另一回事。
                    //   放在这里而不是塞进「权限状态」，因为用户来这一行的
                    //   目的是"我能不能更强"，不是"还差哪个开关"。
                    PaListRow(
                        icon = Icons.Default.Lock,
                        title = "运行模式",
                        subtitle = "普通模式 / Root 模式，以及两边的能力边界",
                        onClick = onOpenPrivilegeMode,
                    )
                }
            }

            // ══════════════════════════════════════════════════════
            //  ★ 明暗主题 —— 默认跟随系统
            // ══════════════════════════════════════════════════════
            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("外观")
            }
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "明暗主题",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = "默认跟随手机设置。你也可以固定其中一个。",
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            for (m in ThemeMode.entries) {
                                PaButton(
                                    text = m.label,
                                    onClick = { onThemeModeChange(m) },
                                    style = if (themeMode == m) PaButtonStyle.Primary else PaButtonStyle.Glass,
                                    modifier = Modifier.padding(end = PaSpace.xs),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("记忆")
            }
            item {
                PaListGroup {
                    // ★ 这一行存在的理由：**让"它记住了"这件事可见**。
                    //   在此之前记忆是隐形的 —— 用户无法区分"它真的记得我"
                    //   和"它每次都答得像记得我"。而不可见的记忆，坏掉了也没人知道。
                    PaListRow(
                        icon = Icons.Default.Psychology,
                        title = "长期记忆",
                        subtitle = if (memoryAtomCount > 0) {
                            "已记住 $memoryAtomCount 条关于你的事，每轮只挑相关的几条用"
                        } else {
                            "聊得多一些之后，它会开始记住关于你的事"
                        },
                        trailing = if (memoryAtomCount > 0) "$memoryAtomCount" else null,
                        // ⚠️ 还没有记忆详情页 —— 传 null（不画箭头），
                        //    而不是传空 lambda 假装能进（同本文件顶部的纪律）。
                        onClick = null,
                    )
                }
            }

            // ══════════════════════════════════════════════════════
            //  ★ 亲密与成人内容 —— 两个门，默认全关
            // ══════════════════════════════════════════════════════
            //
            // ⚠️ 为什么必须放在设置里、且分成两步：
            //    用户要的是"数字生命有生理需求"，这没问题。
            //    但"角色能亲密"不等于"谁都能打开"。
            //    所以这里：① 一次性的成年确认（事实）② 随时可关的开关（意愿）。
            //
            // ⚠️ 没有第二个开关时，关掉内容 = 要重新证明一次成年 ——
            //    那是一种惩罚，会让人宁可一直开着。
            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("亲密与成人内容")
            }
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "成年确认",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = if (maturePermission.adultConfirmed) {
                                "已确认。这一步只记录事实，不会自己打开内容。"
                            } else {
                                "先确认你已满 18 岁。没确认之前，角色的亲密表达会停在关系这一层。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (maturePermission.adultConfirmed) "已确认成年" else "我确认已满 18 岁",
                                onClick = { onAdultConfirmedChange(true) },
                                style = if (maturePermission.adultConfirmed) {
                                    PaButtonStyle.Glass
                                } else {
                                    PaButtonStyle.Primary
                                },
                                enabled = !maturePermission.adultConfirmed,
                            )
                        }
                    }
                }
            }
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "允许成熟内容",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = when {
                                maturePermission.effective ->
                                    "已开启。角色会按你们的关系阶段表达亲密 —— 关系不到那一步，仍然不会说。"
                                !maturePermission.adultConfirmed ->
                                    "需要先完成上面的成年确认。"
                                else -> "关着的时候角色照样会亲密、会撒娇、会生气，只是不写露骨的性描写。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (maturePermission.matureEnabled) "关掉" else "打开",
                                onClick = {
                                    onMatureEnabledChange(!maturePermission.matureEnabled)
                                },
                                style = if (maturePermission.matureEnabled) {
                                    PaButtonStyle.Glass
                                } else {
                                    PaButtonStyle.Primary
                                },
                                // ⚠️ 未确认成年时按钮**禁用而不是隐藏** ——
                                //    隐藏会让用户找不到这个功能，以为产品没有；
                                //    禁用 + 上面那句原因，才是可理解的。
                                enabled = maturePermission.adultConfirmed,
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            //  ★ 通知与主动消息 —— 角色能不能自己来找你
            // ══════════════════════════════════════════════════════
            //
            // ⚠️ 这两项都是**运行时权限**：写进清单不等于拿到。
            //    Android 13+ 的通知权限必须由用户点一次；没有它，
            //    `notify()` 会被静默丢弃 —— 界面上什么都看不出来，
            //    只有"角色从来不主动找我"这一个症状。
            item {
                Spacer(Modifier.height(PaSpace.xs))
                PaSectionTitle("通知与主动消息")
            }
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "通知权限",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = if (notificationGranted) {
                                "已开启。角色会在合适的时机自己给你发消息 —— 不会太频繁。"
                            } else {
                                "没开的话，角色说的话发不出去（系统会直接丢掉），而界面上不会有任何提示。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (notificationGranted) "已开启" else "开启通知",
                                onClick = onRequestNotification,
                                style = if (notificationGranted) {
                                    PaButtonStyle.Glass
                                } else {
                                    PaButtonStyle.Primary
                                },
                                enabled = !notificationGranted,
                            )
                        }
                    }
                }
            }
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "大致位置",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = if (locationGranted) {
                                "已开启。只用来查当地天气，坐标会四舍五入到约 11 公里。"
                            } else {
                                "只用来查天气。不开也能用 —— 只是它不会跟你提天气。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (locationGranted) "已开启" else "允许大致位置",
                                onClick = onRequestLocation,
                                style = if (locationGranted) {
                                    PaButtonStyle.Glass
                                } else {
                                    PaButtonStyle.Primary
                                },
                                enabled = !locationGranted,
                            )
                        }
                    }
                }
            }

            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "按住说话（麦克风）",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = if (micGranted) {
                                "已开启。在输入框里长按可以录一条语音发给角色。"
                            } else {
                                "不开就没法发语音消息。只在按住输入框那一下录音，不做后台录音。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (micGranted) "已开启" else "允许录音",
                                onClick = onRequestMic,
                                style = if (micGranted) PaButtonStyle.Glass else PaButtonStyle.Primary,
                                enabled = !micGranted,
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            //  ★ 朗读回复（TTS）
            // ══════════════════════════════════════════════════════
            item {
                PaListGroup {
                    Column(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                        Text(
                            text = "朗读回复",
                            style = PaType.body,
                            color = PaColor.TextPrimary,
                        )
                        Spacer(Modifier.height(PaSpace.xxs))
                        Text(
                            text = if (ttsEnabled) {
                                "已开启。角色会把回复念出来（需要配一条语音 Key）。"
                            } else {
                                "默认关闭 —— 突然出声是打扰。想听的时候再开。"
                            },
                            style = PaType.caption,
                            color = PaColor.TextSecondary,
                        )
                        Spacer(Modifier.height(PaSpace.s))
                        Row {
                            PaButton(
                                text = if (ttsEnabled) "关掉" else "开启朗读",
                                onClick = { onToggleTts(!ttsEnabled) },
                                style = if (ttsEnabled) PaButtonStyle.Glass else PaButtonStyle.Primary,
                            )
                        }
                    }
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
                        // ⚠️ 2026-10-02 修：这里原本是**硬编码的 `"M0"`**。
                        //
                        //    后果：`versionName` 从 `0.1.0-m0` 改成 `0.1.0-m1` 之后，
                        //    界面上**照样显示 M0** —— 两处真相来源，其中一处是写死的，
                        //    漂移时没有任何人会知道。用户看到的就是"这软件几个月没动过"。
                        //
                        //    ⇒ 让它读构建时注入的真实版本号。**版本号只允许有一个来源。**
                        trailing = BuildConfig.VERSION_NAME,
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
