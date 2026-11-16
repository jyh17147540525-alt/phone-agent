package com.pocketagent.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.personalogic.ChatStyle
import com.pocketagent.personalogic.LivingWith
import com.pocketagent.personalogic.PersonaInput
import com.pocketagent.personalogic.UserAgeBand
import com.pocketagent.personalogic.UserGender
import com.pocketagent.personalogic.UserProfile
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaFilterChip
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType

/**
 * **「关于你」** —— 用户自己的那一页。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 这一页要解决的事
 * ══════════════════════════════════════════════════════════════
 *
 * 现在 app 里角色的人格是用户填的、身体是用户填的、世界书是用户填的，
 * 唯独**对面那个人**是白纸。于是每段新关系都要先问一遍「你是做什么的」，
 * 而用户已经回答过很多次了。这一页把**已知的那一半**交给角色。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 四条界面约束
 * ══════════════════════════════════════════════════════════════
 *
 * ① **全部可选，没有一个必填**。
 *    这一页的敌人是"看起来像填表"。必填项会让用户直接退出，
 *    而他退出的那一刻，这一页就成了负资产。
 *
 * ② **不显示「未填」**。
 *    空字段在这里就是空着，不给它一个红点也不给它一个 0。
 *    填表软件才会追着人问，这一页不追。
 *
 * ③ **不追踪进度、不给完成度**。
 *    没有「已填 7/12 项」。那会把"想让角色更懂你"变成"打卡"，
 *    而打卡的下一步是敷衍。
 *
 * ④ **保存前告诉他这段占多少字**。
 *    提示词预算是有数的（[UserProfile.MAX_CHARS] = 900），
 *    写满一屏的人有权知道哪几句真的进得去 —— 隐私之外，
 *    这也是唯一能让他主动收敛的东西。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserProfileScreen(
    modifier: Modifier = Modifier,
    initial: UserProfile? = null,
    /**
     * 角色设定里填的「怎么称呼你」。
     *
     * 非空时这里的那一格会变成**只读提示**：两份称呼并存会让模型挑错一个，
     * 所以以角色那条为准（见 [UserProfile.promptSection]）。
     */
    characterCallsYou: String? = null,
    onSave: (UserProfile) -> Unit = {},
    onClear: (() -> Unit)? = null,
) {
    // ↓ 每个字段都用 remember(initial) 承接：换角色/重进页面不会串稿。
    var callName by remember(initial) { mutableStateOf(initial?.callName.orEmpty()) }
    var gender by remember(initial) { mutableStateOf(initial?.gender ?: UserGender.UNSPECIFIED) }
    var ageBand by remember(initial) { mutableStateOf(initial?.ageBand ?: UserAgeBand.UNSPECIFIED) }
    var livingWith by remember(initial) { mutableStateOf(initial?.livingWith ?: LivingWith.UNSPECIFIED) }
    var hardHistory by remember(initial) { mutableStateOf(initial?.hardHistory.orEmpty().joinToString("\n")) }
    var bigMoments by remember(initial) { mutableStateOf(initial?.bigMoments.orEmpty().joinToString("、")) }
    var proudOf by remember(initial) { mutableStateOf(initial?.proudOf.orEmpty().joinToString("、")) }
    var chatStyle by remember(initial) { mutableStateOf(initial?.chatStyle ?: ChatStyle.UNSPECIFIED) }
    var likes by remember(initial) { mutableStateOf(initial?.likes.orEmpty().joinToString("、")) }
    var dislikes by remember(initial) { mutableStateOf(initial?.dislikes.orEmpty().joinToString("、")) }
    var taboos by remember(initial) { mutableStateOf(initial?.taboos.orEmpty().joinToString("、")) }
    var bodyNotes by remember(initial) { mutableStateOf(initial?.bodyNotes.orEmpty()) }
    var sleepHour by remember(initial) { mutableStateOf(initial?.sleepHour?.toString().orEmpty()) }
    var wakeHour by remember(initial) { mutableStateOf(initial?.wakeHour?.toString().orEmpty()) }
    var knownFacts by remember(initial) { mutableStateOf(initial?.knownFacts.orEmpty().joinToString("；")) }
    var extra by remember(initial) { mutableStateOf(initial?.extra.orEmpty()) }

    // 组装只有一个入口：草稿（算字数）与保存（落盘）都走它。
    // 两处各拼一份必然漂移 —— 那种 bug 不会报错，只会让"存进去的和看到的不一样"。
    fun assemble(): UserProfile = UserProfile(
        callName = callName.trim().take(UserProfile.MAX_CALL_NAME_CHARS),
        gender = gender,
        ageBand = ageBand,
        livingWith = livingWith,
        hardHistory = PersonaInput.splitList(hardHistory, UserProfile.MAX_ITEMS),
        bigMoments = PersonaInput.splitList(bigMoments, UserProfile.MAX_ITEMS),
        proudOf = PersonaInput.splitList(proudOf, UserProfile.MAX_ITEMS),
        chatStyle = chatStyle,
        likes = PersonaInput.splitList(likes, UserProfile.MAX_ITEMS),
        dislikes = PersonaInput.splitList(dislikes, UserProfile.MAX_ITEMS),
        taboos = PersonaInput.splitList(taboos, UserProfile.MAX_ITEMS),
        bodyNotes = bodyNotes.trim().take(UserProfile.MAX_FIELD_CHARS),
        sleepHour = hourOrNull(sleepHour),
        wakeHour = hourOrNull(wakeHour),
        knownFacts = PersonaInput.splitList(knownFacts, UserProfile.MAX_ITEMS),
        extra = extra.trim().take(UserProfile.MAX_FIELD_CHARS),
    )

    val draft = assemble()
    val promptChars = draft.promptSection(callNameFromCharacter = characterCallsYou.orEmpty()).length
    val fromCharacter = characterCallsYou?.trim().orEmpty()
    // 角色设定那一栏常常是一整段说明（主称呼、什么时候换称呼、说话语气……），
    // 整段抄进界面会变成一堵墙，这里只截开头让人认得出是哪一条。
    val fromCharacterShort = if (fromCharacter.length <= 20) {
        fromCharacter
    } else {
        fromCharacter.take(20).trimEnd() + "…"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            top = PaSpace.xs,
            bottom = PaSpace.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
    ) {
        item {
            PaBanner(
                title = "这些都是给他看的",
                tone = PaBannerTone.Info,
                description = "填了的会被写进提示词，没填的一个字都不提。" +
                    "所以留空不等于写「不知道」，留空就是不存在。",
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("怎么叫你") }
        if (fromCharacter.isNotEmpty()) {
            item {
                Text(
                    text = "角色设定里已经写了「$fromCharacterShort」，就用那个 —— 两份称呼并存，他会挑错一个。" +
                        "这里留空就好。",
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }
        } else {
            item {
                Field(
                    label = "他希望怎么叫你",
                    hint = "昵称、小名，或者大名都行。超过 ${UserProfile.MAX_CALL_NAME_CHARS} 字会被截。",
                    value = callName,
                    onValueChange = { callName = it },
                    placeholder = "怎么称呼你",
                )
            }
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("基本") }
        item {
            Choice(
                title = "性别",
                hint = "只决定他说话时用「他」还是「她」，不影响别的。",
                options = UserGender.entries.filter { it != UserGender.UNSPECIFIED },
                selected = gender,
                optionLabel = { it.label },
                onSelect = { gender = it },
            )
        }
        item {
            Choice(
                title = "大概多大",
                hint = "知道个大概就够他说话，不用填生日。",
                options = UserAgeBand.entries.filter { it != UserAgeBand.UNSPECIFIED },
                selected = ageBand,
                optionLabel = { it.label },
                onSelect = { ageBand = it },
            )
        }
        item {
            Choice(
                title = "和谁住",
                hint = "一个人住和跟家人住，能接的话完全不一样。",
                options = LivingWith.entries.filter { it != LivingWith.UNSPECIFIED },
                selected = livingWith,
                optionLabel = { it.label },
                onSelect = { livingWith = it },
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("你的过去") }
        item {
            PaBanner(
                tone = PaBannerTone.Info,
                title = "他会知道，但不会主动问",
                description = "这几栏写的是你已经过完的那部分。它不是话题清单：" +
                    "填进来，等于让他知道你摔过、也站起来了。",
            )
        }
        item {
            Field(
                label = "难受过的事",
                hint = "失去过谁、病过、难熬的那几年。一条一行。",
                value = hardHistory,
                onValueChange = { hardHistory = it },
                placeholder = "外婆走的那年我一直在加班\n上一份工作撑到抑郁",
            )
        }
        item {
            Field(
                label = "特别的经历",
                hint = "做过的事、去过的地方、拐过弯的那几年。用「、」或换行分开。",
                value = bigMoments,
                onValueChange = { bigMoments = it },
                placeholder = "在北方待过四年、学过两年吉他",
            )
        }
        item {
            Field(
                label = "骄傲过的事",
                hint = "提起这些你会高兴。他知道该往哪儿夸你。",
                value = proudOf,
                onValueChange = { proudOf = it },
                placeholder = "一个人把房子装修完了",
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("你怎么说话") }
        item {
            Choice(
                title = "说话习惯",
                hint = "这一栏决定他每条回复的长短 —— 你话少，他也就不该长篇大论。",
                options = ChatStyle.entries.filter { it != ChatStyle.UNSPECIFIED },
                selected = chatStyle,
                optionLabel = { it.label },
                onSelect = { chatStyle = it },
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("喜欢与不喜欢") }
        item {
            Field(
                label = "喜欢",
                hint = "用「、」或换行分开，最多 ${UserProfile.MAX_ITEMS} 条。",
                value = likes,
                onValueChange = { likes = it },
                placeholder = "喝冰美式、下雨天、猫",
                singleLine = true,
            )
        }
        item {
            Field(
                label = "不喜欢",
                hint = "同上面，最多 ${UserProfile.MAX_ITEMS} 条。",
                value = dislikes,
                onValueChange = { dislikes = it },
                placeholder = "吵、被安排、香菜",
                singleLine = true,
            )
        }
        item {
            Field(
                label = "别碰的话题",
                hint = "这一条优先级最高，提示词再挤也不会裁掉。写了就别指望他忘。",
                value = taboos,
                onValueChange = { taboos = it },
                placeholder = "家里的事、前任、体检结果",
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("身体上要知道的") }
        item {
            Field(
                label = "过敏、旧伤、睡不好之类",
                hint = "写了他才会在你喊累的时候少说两句，也才不会瞎建议你吃海鲜。",
                value = bodyNotes,
                onValueChange = { bodyNotes = it },
                placeholder = "对海鲜过敏，腰有旧伤",
            )
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("作息") }
        item {
            Text(
                text = "填了之后他才知道什么时候不该吵你。只填一个也行。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
        item {
            Row2 {
                Field(
                    label = "几点睡",
                    hint = "",
                    value = sleepHour,
                    onValueChange = { sleepHour = it.filter { c -> c.isDigit() }.take(2) },
                    placeholder = "23",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Field(
                    label = "几点起",
                    hint = "",
                    value = wakeHour,
                    onValueChange = { wakeHour = it.filter { c -> c.isDigit() }.take(2) },
                    placeholder = "8",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item { Spacer(Modifier.height(PaSpace.xs)) }
        item { PaSectionTitle("已经发生过的事") }
        item {
            Field(
                label = "你们之间已经有过的事",
                hint = "写在这里的，他当作已经知道，不会再问一遍。用「；」或换行分开。",
                value = knownFacts,
                onValueChange = { knownFacts = it },
                placeholder = "一起养过一只猫；我说过我不喜欢惊喜",
            )
        }
        item {
            Field(
                label = "还想说一句",
                hint = "没地方放的，放这儿。",
                value = extra,
                onValueChange = { extra = it },
                placeholder = "",
            )
        }

        item { Spacer(Modifier.height(PaSpace.s)) }
        item {
            Text(
                text = if (promptChars == 0) {
                    "现在一个字都不会进提示词。"
                } else {
                    "这段现在占 $promptChars 字提示词" +
                        if (promptChars > UserProfile.MAX_CHARS) {
                            "（超过 ${UserProfile.MAX_CHARS}，多余的会被从最不重要的往下裁）"
                        } else {
                            "。"
                        }
                },
                style = PaType.caption,
                color = if (promptChars > UserProfile.MAX_CHARS) PaColor.Warning else PaColor.TextTertiary,
            )
        }
        item {
            PaButton(
                text = "保存",
                onClick = { onSave(assemble()) },
                style = PaButtonStyle.Primary,
                fillWidth = true,
            )
        }
        if (onClear != null) {
            item {
                PaButton(
                    text = "清空这一页",
                    onClick = onClear,
                    style = PaButtonStyle.Text,
                    fillWidth = true,
                )
            }
        }
        item { Spacer(Modifier.height(PaSpace.l)) }
    }
}

/**
 * 「几点」这一格。
 *
 * ★ 空着就是 null，不是 0 —— 0 点是**有效**的睡觉时间，
 *   拿 0 当"没填"会让人被半夜静音。这个坑在存储层也踩过一次
 *   （见 `UserProfileStore` 把小时存成字符串）。
 */
private fun hourOrNull(raw: String): Int? {
    if (raw.isBlank()) return null
    return PersonaInput.hour(raw, fallback = 23)
}

/** 标签 + 输入框 + 一行说明。三件套固定顺序，全页一致。 */
@Composable
private fun Field(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = label, style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xxs))
        PaTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            singleLine = singleLine,
        )
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(text = hint, style = PaType.caption, color = PaColor.TextTertiary)
        }
    }
}

/**
 * 单选组。
 *
 * ⚠️ 用 [PaFilterChip] 而不是 `PaButton`：按钮的语义是"按一下会发生一件事"，
 *    而这里是"选一个当前的取值"。同一个界面上两种语义混用，
 *    用户会分不清哪一下是动作、哪一下是状态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(
    title: String,
    hint: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xxs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            options.forEach { option ->
                PaFilterChip(
                    text = optionLabel(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(text = hint, style = PaType.caption, color = PaColor.TextTertiary)
        }
    }
}

/** 两格并排（作息那一段用）。 */
@Composable
private fun Row2(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) { content() }
}
