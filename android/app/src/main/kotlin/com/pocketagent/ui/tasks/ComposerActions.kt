package com.pocketagent.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * **输入框上方的功能入口** —— 照豆包那一排图标做的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * "在对话输入里加入一些其他的功能，就是豆包有的这个 app 都得有，
 *  图片，文件，音频，视频通话，语音通话，屏幕共享，等等等等，
 *  这些图标的 ui 就模仿豆包的吧。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这里有一条不能含糊的界线：[ComposerAction.wired]
 * ═══════════════════════════════════════════════════════════════
 *
 * 图标可以一次全画出来，但**点了之后发生什么**必须如实：
 *
 * · `wired = true`  → 真的打开系统选择器 / 真的开始录音
 * · `wired = false` → 明确说一句"还没接上"，**不是**静默无反应
 *
 * 理由是本项目一以贯之的那条：**不要把"未实现"伪装成"可用"**。
 * 一个点了没反应的图标，用户会认为应用坏了 —— 然后开始怀疑别的功能也是坏的；
 * 而一句"这个还没接上"，用户只会觉得这个版本还早，但仍可信。
 */
enum class ComposerAction(
    val label: String,
    val icon: ImageVector,
    /** 现在是否**真的**能用。false 时点击必须给出明确回应。 */
    val wired: Boolean,
    /** 未接通时对用户说的话。 */
    val pendingNote: String = "",
) {
    IMAGE("图片", Icons.Default.Image, wired = true),
    FILE("文件", Icons.Default.AttachFile, wired = true),
    AUDIO("语音输入", Icons.Default.Mic, wired = false, pendingNote = "语音输入还没接上（要先申请麦克风权限并接语音识别）"),
    VOICE_CALL("语音通话", Icons.Default.Call, wired = false, pendingNote = "语音通话还没接上（实时语音链路要单独做）"),
    VIDEO_CALL("视频通话", Icons.Default.Videocam, wired = false, pendingNote = "视频通话还没接上"),
    SCREEN_SHARE("屏幕共享", Icons.Default.ScreenShare, wired = false, pendingNote = "屏幕共享还没接上（现在的做法是让角色直接看你的屏幕）"),
}

/**
 * 一排圆形功能图标。
 *
 * ★ 视觉上刻意贴近豆包：
 *   · 圆形浅底 + 20dp 线图标 + 无文字（文字只在无障碍标签里）
 *   · 只在**输入区上方**占一行，不抢正文的位置
 *   · 窄屏可横向滚动 —— 六个图标在 360dp 宽的机器上放不下，
 *     挤成一团比滚动更难用
 */
@Composable
fun ComposerActionRow(
    onAction: (ComposerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PaSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (action in ComposerAction.entries) {
            ActionChip(action = action, onClick = { onAction(action) })
        }
    }
}

@Composable
private fun ActionChip(action: ComposerAction, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            // ⚠️ 未接通的图标**不做灰度**：那会让人觉得"这个功能被砍了"。
            //    保持同样的视觉，点下去才告诉他"还没接上" —— 顺序反了会更糟。
            .background(PaColor.GlassTintStrong)
            .clickable(onClickLabel = action.label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            tint = PaColor.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 底部一句提示（选完文件、或点了未接通的功能时）。
 *
 * ⚠️ 它必须和"角色说的话"在视觉上区分开：小一号、弱化色。
 *    混在一起会让用户以为这句话是角色说的。
 */
@Composable
fun ComposerHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PaType.caption,
        color = PaColor.TextTertiary,
        modifier = modifier.padding(horizontal = PaSpace.m, vertical = PaSpace.xxs),
    )
}
