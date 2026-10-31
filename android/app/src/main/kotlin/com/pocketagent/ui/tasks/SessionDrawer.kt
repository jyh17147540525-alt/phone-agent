package com.pocketagent.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType

/**
 * 会话抽屉 —— 历史会话的列表 / 搜索 / 切换 / 重命名 / 删除。
 *
 * 设计依据：`docs/会话侧边栏与全局记忆设计-v1.0.md` §3.1。
 * 交互形态**对齐 ChatGPT / Claude**（那是十年验证过的信息架构，没有理由标新立异）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它是「记忆的可见面」而不只是"一个历史列表"
 * ═══════════════════════════════════════════════════════════════
 *
 * 本稿引入 L1/L2/L3 记忆之后，助理会攒下**用户自己都没意识到被记下**的东西。
 * 而"能看见、能拿走、能删掉"是这个设计能被接受的前提（设计稿 §4.5 同一条理由）。
 *
 * ⇒ 这个抽屉里能搜到的，就是记忆层确实存住的；
 *   在这里删掉的，记忆层的对应部分也会跟着走（`SessionDao.deleteCascade`）。
 *
 * ⚠️ **本组件不做任何 IO**：它只发意图（onSwitch / onRename / …），
 *    全部由 `TasksViewModel` 执行。这样它能在预览里被完整渲染出来。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionSummary>,
    onNewSession: () -> Unit,
    onSwitch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    // ══════════════════════════════════════════════════════════
    //  ★★ 2026-10-31：插件 / 设置入口搬到这里
    // ══════════════════════════════════════════════════════════
    //
    // 用户："把 app 里最下方的三个页面留剩下对话一个主页，其他的
    //      放入侧边栏右下角，右下角有两个按钮，一个是插件按钮，
    //      一个是设置按钮，这一用户一打开软件就能直接对话模型了。"
    //
    // ⚠️ 为什么这个改动是对的：底部导航占了 **56dp 的永久高度**，
    //    而它只在"切换三个平级页面"时有用 —— 但那三个页面里，
    //    用户 95% 的时间只在一个（对话）。用一个常驻横条去服务
    //    一个低概率动作，代价是**每一屏都少 56dp 的对话空间**。
    //
    // ⇒ 撤掉底栏，把两个低频入口收进抽屉右下角。
    //   开机就是对话页、零点击 —— 这是"进来就能聊"的唯一形态。
    onOpenPlugins: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    var keyword by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(PaColor.CanvasElevated)
            .padding(horizontal = PaSpace.s, vertical = PaSpace.m),
    ) {
        PaButton(
            text = "新对话",
            onClick = {
                keyword = ""
                onSearch("")
                onNewSession()
            },
            style = PaButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.Add,
        )

        Spacer(Modifier.height(PaSpace.s))

        PaTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                onSearch(it)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索对话…",
            leadingIcon = Icons.Default.Search,
        )

        Spacer(Modifier.height(PaSpace.s))

        when {
            // ⚠️ 三态必须分开 —— 设计稿 §3.1 的纪律：
            //    "搜索无结果" 与 "还没有对话" 是完全不同的两件事，
            //    合成一句会让用户以为自己的历史没了。
            sessions.isEmpty() && keyword.isNotBlank() -> DrawerHint(
                title = "没有匹配「$keyword」的对话",
                action = "清除搜索" to {
                    keyword = ""
                    onSearch("")
                },
            )

            sessions.isEmpty() -> DrawerHint(
                title = "还没有对话。\n说一句话开始。",
                action = null,
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = PaSpace.m),
                verticalArrangement = Arrangement.spacedBy(PaSpace.xxs),
            ) {
                groupSessions(sessions).forEach { (label, group) ->
                    item(key = "h-$label") {
                        Text(
                            text = label,
                            style = PaType.overline,
                            color = PaColor.TextTertiary,
                            modifier = Modifier.padding(
                                start = PaSpace.xs,
                                top = PaSpace.s,
                                bottom = PaSpace.xxs,
                            ),
                        )
                    }
                    items(items = group, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onSwitch = { onSwitch(session.id) },
                            onRename = { renaming = session },
                            onDelete = { deleting = session },
                        )
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════
        //  ★★ 底部：插件 / 设置
        // ══════════════════════════════════════════════════════════
        //
        // ⚠️ 位置在**右下角**，不是铺满一行 —— 用户原话是"右下角有两个
        //    按钮"。铺满会看起来像另一个导航栏，而这两个是**低频入口**，
        //    视觉权重必须明显低于上面的会话列表。
        //
        // ★ 用图标按钮而不是文字按钮：
        //   · 文字（"插件" / "设置"）会和会话标题抢注意力
        //   · 图标 + 极淡的背景色，是"工具在哪"的暗示，不是"主要功能"
        //
        // ⚠️ 用 `Spacer(weight(1f))` 把这一行顶到底部 ——
        //    列表少的时候（只有一两段对话）按钮仍然在底部，
        //    不会跟着列表飘到屏幕中间。那种"按钮位置随内容变"是最烦人的。
        Spacer(modifier = Modifier.weight(1f))

        // ⚠️ 一条极淡的分隔线，把"工具"和"内容"分开。
        //    没有它，这两个图标会和最后一条会话贴在一起 ——
        //    看起来像"那条会话有两个操作按钮"（重命名/删除那种）。
        //
        // ★ 用 6% alpha 的 GlassBorder 而不是实线：
        //   它只需要"暗示分组"，不需要"划出边界"。
        //   实线会让抽屉看起来被切成两块。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(top = PaSpace.xs)
                .background(PaColor.GlassBorder.copy(alpha = 0.06f)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = PaSpace.xs),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenPlugins != null) {
                PaIconButton(
                    icon = Icons.Default.Extension,
                    onClick = onOpenPlugins,
                    contentDescription = "插件",
                )
                Spacer(Modifier.width(PaSpace.xxs))
            }
            if (onOpenSettings != null) {
                PaIconButton(
                    icon = Icons.Default.Tune,
                    onClick = onOpenSettings,
                    contentDescription = "设置",
                )
            }
        }
    }

    // ── 重命名 ────────────────────────────────────────────────
    renaming?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = PaColor.Surface,
            shape = RoundedCornerShape(PaRadius.l),
            title = { Text("重命名", style = PaType.headline, color = PaColor.TextPrimary) },
            text = {
                PaTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "给这段对话起个名字",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target.id, draft)
                    renaming = null
                }) { Text("保存", color = PaColor.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text("取消", color = PaColor.TextSecondary)
                }
            },
        )
    }

    // ── 删除（两步确认）────────────────────────────────────────
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = PaColor.Surface,
            shape = RoundedCornerShape(PaRadius.l),
            title = { Text("删除这段对话？", style = PaType.headline, color = PaColor.TextPrimary) },
            text = {
                Text(
                    text = "「${target.title}」以及里面的 ${target.turnCount} 轮对话会被**永久删除**，" +
                        "无法恢复。",
                    style = PaType.body,
                    color = PaColor.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleting = null
                }) { Text("删除", color = PaColor.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("取消", color = PaColor.TextSecondary)
                }
            },
        )
    }
}

/** 一行会话。 */
@Composable
private fun SessionRow(
    session: SessionSummary,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PaRadius.s))
            // ★ 当前会话高亮。没有它，用户切来切去之后不知道自己在哪一段里。
            .background(if (session.isCurrent) PaColor.AccentSoft else Color.Transparent)
            .clickable(onClick = onSwitch)
            .padding(horizontal = PaSpace.xs, vertical = PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                style = PaType.label,
                color = PaColor.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${formatRelative(session.lastMessageAt)} · ${session.turnCount} 条",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }

        Box {
            PaIconButton(
                icon = Icons.Default.MoreVert,
                onClick = { menuOpen = true },
                contentDescription = "更多",
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = PaColor.Danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** 空态 / 无结果态。 */
@Composable
private fun DrawerHint(title: String, action: Pair<String, () -> Unit>?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PaSpace.xxl, start = PaSpace.s, end = PaSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(PaSpace.s))
            TextButton(onClick = onClick) { Text(label, color = PaColor.Accent) }
        }
    }
}

// ── 分组与时间 ───────────────────────────────────────────────────

/**
 * 按「今天 / 昨天 / 前 7 天 / 更早」分组。
 *
 * ⚠️ 分组是**展示**，不影响排序 —— 组内仍按 `lastMessageAt` 降序
 *    （列表本身已经是那个顺序，这里只做切分）。
 *
 * ⚠️ 判据用**本地日**而不是"距今 24 小时"：
 *    用户说"今天"指的是日历上的今天。用 24 小时制会让
 *    "昨晚 11 点说的话"在今天早上 8 点被归进"今天"，而用户觉得那是昨天。
 */
internal fun groupSessions(
    sessions: List<SessionSummary>,
    now: Long = System.currentTimeMillis(),
): List<Pair<String, List<SessionSummary>>> {
    val startOfToday = now - (now + TIMEZONE_OFFSET_GUESS_MS) % DAY_MS
    val startOfYesterday = startOfToday - DAY_MS
    val sevenDaysAgo = startOfToday - 7 * DAY_MS

    val buckets = linkedMapOf(
        "今天" to mutableListOf<SessionSummary>(),
        "昨天" to mutableListOf<SessionSummary>(),
        "前 7 天" to mutableListOf<SessionSummary>(),
        "更早" to mutableListOf<SessionSummary>(),
    )
    sessions.forEach { s ->
        val bucket = when {
            s.lastMessageAt >= startOfToday -> "今天"
            s.lastMessageAt >= startOfYesterday -> "昨天"
            s.lastMessageAt >= sevenDaysAgo -> "前 7 天"
            else -> "更早"
        }
        buckets.getValue(bucket) += s
    }
    return buckets.filterValues { it.isNotEmpty() }.toList()
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * 时区偏移的**近似值**。
 *
 * ⚠️ 这里刻意不用 `TimeZone.getDefault()` —— 那需要 Android 运行时，
 *    而本函数要被单元测试覆盖。中国是 UTC+8 且不用夏令时，
 *    所以固定偏移在这里是**正确**的。
 *    真要支持多时区时，应该把 `zoneOffsetMs` 做成参数由调用方注入
 *    （同本项目"内核不读时钟"的既有纪律）。
 */
private const val TIMEZONE_OFFSET_GUESS_MS = 8 * 60 * 60 * 1000L

/** 相对时间。只做粗粒度 —— 抽屉里精确到分钟没有意义。 */
internal fun formatRelative(at: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - at
    return when {
        diff < 0 -> "刚刚"
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        else -> "${diff / 86_400_000L} 天前"
    }
}
