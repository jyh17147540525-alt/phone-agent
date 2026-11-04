package com.pocketagent.ui.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * 工作台页首的**开场白**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-04：这一块是"工作台"与"空白输入框"的分界线
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话："在这个页面，开场，背景上都会有这个角色的随机开场白，
 * 像 claude 那样" —— 并附了一张 Claude 首页的截图：
 * 一个八向星芒 + 「What's up next, 米克尔？」。
 *
 * 抄的是那个**结构**，不是那个图标 —— 用户看完真机截图说：
 * 「claude的图标你不要照搬呀」。照搬一个别的产品的标记，
 * 是把"像 claude 那样"误解成了"变成 claude"。
 *
 *   ① 一句**有人味**的主文案，称呼用的是角色对用户的称呼
 *      （`CustomPersona.addressUser`），不是"你"；
 *   ② 一行小字，说的是"她会怎么帮你"。
 *
 * ⇒ **上面没有任何图标。** 用户看完真机截图说「图标你还是去掉吧」。
 *    他是对的：一个页面要在第一眼里说清楚"有人在这里"，靠的是**句子**，
 *    不是符号。符号只能表达"这是某个应用的首页"—— 而工作台不是首页。
 *
 * ⚠️ 为什么不放角色头像：头像会让这一屏变成"聊天首页"，
 *    而工作台要的是"今天要办什么"的那种干爽。空着比放一个
 *    中性符号更干净 —— 这一屏的力量全在那句话上。
 *
 * ⚠️ 文案**不在这里写** —— 全在
 *    `:personalogic` 的 `WorkbenchGreeting` 里，有离线测试钉着
 *    （不许撒娇、不许动作描写、同一颗种子永远同一句）。
 *    这里只负责画。
 */
@Composable
internal fun WorkbenchHero(
    headline: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = PaSpace.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = PaType.headline,
            color = PaColor.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (hint.isNotBlank()) {
            Spacer(PaSpace.xs)
            Text(
                text = hint,
                style = PaType.body,
                color = PaColor.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

/** 与 `PaSpace` 同名的小工具，免得每处都写 `Spacer(Modifier.height(...))`。 */
@Composable
private fun Spacer(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(size))
}
