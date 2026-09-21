package com.pocketagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * ⚠️ 四个 `onOpen*` 都是**可空**的，这是有意的。
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
    onOpenKeys: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    onOpenImport: (() -> Unit)? = null,
    onOpenPermissions: (() -> Unit)? = null,
) {
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
                        title = "API Key 与模型",
                        subtitle = "用自己的 Key 调用任意大模型",
                        badge = "未配置",
                        badgeTone = PaBadgeTone.Warning,
                        onClick = onOpenKeys,
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
                        subtitle = "无障碍、截图、悬浮窗",
                        badge = "待检查",
                        badgeTone = PaBadgeTone.Warning,
                        onClick = onOpenPermissions,
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
