package com.pocketagent.ui.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.legacy.GriefPhase
import com.pocketagent.legacy.KinshipDetector
import com.pocketagent.legacy.LegacyBoundary
import com.pocketagent.legacy.LegacySignal
import com.pocketagent.legacy.RelationKind
import com.pocketagent.personalogic.CustomPersona
import com.pocketagent.personalogic.PersonaCompleteness
import com.pocketagent.personalogic.PersonaGender
import com.pocketagent.personalogic.PersonaInput
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaListGroup
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType

/**
 * **角色设定页** —— 自定义角色 + 未亡人模式的入口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么两件事放同一页
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户描述"这个角色是谁"时，**可能是在描述一个还在的人，也可能不是**。
 * 分成两个页面会逼他先做一个分类决定 —— 而那个决定本身就很痛。
 *
 * ⇒ 一个输入框，一个提交。系统自己判断该走哪条路，
 *   判定结果**先给用户看**，由他确认。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 未亡人那部分的入口必须**克制**
 * ═══════════════════════════════════════════════════════════════
 *
 * 不做醒目的功能卡片、不做入口动画、不在首页提示"你失去了谁吗"。
 * 它只在**用户自己写到那里**的时候，轻轻出现一次。
 */
@Composable
fun PersonaScreen(
    modifier: Modifier = Modifier,
    /** 已有角色（编辑时回填）。null = 新建。 */
    initial: CustomPersona? = null,
    onSave: (CustomPersona) -> Unit = {},
    /**
     * 用户明确同意开启「未亡人」模式。
     *
     * @param relation 识别到的关系
     * @param displayName 那个人的名字（可能没抽到）
     *
     * ⚠️ 这是**用户主动按下**的动作，不是自动切换 ——
     *    理由见 `LegacySuggestion` 的注释：自动切换意味着
     *    用户可能在毫无准备的时候，面对一个用逝者语气说话的助手。
     */
    onRequestLegacy: (RelationKind, String?) -> Unit = { _, _ -> },
) {
    // ★ 有 `initial` 就回填 —— 否则用户改一个字就要从头再填一遍，
    //   而"上次填的东西不见了"会让他不愿意再改第二次。
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var identity by remember(initial) { mutableStateOf(initial?.identity.orEmpty()) }
    var relationTo by remember(initial) { mutableStateOf(initial?.relationTo.orEmpty()) }
    var addressUser by remember(initial) { mutableStateOf(initial?.addressUser.orEmpty()) }
    var catchphrases by remember(initial) {
        mutableStateOf(initial?.catchphrases?.joinToString("、").orEmpty())
    }
    // ★ 毛病：用户写，每行一条。这是「不完美模拟」的**唯一燃料** ——
    //   系统不生成脾气，只复现用户说的。
    var flaws by remember(initial) {
        mutableStateOf(initial?.flawsRaw?.joinToString("\n").orEmpty())
    }
    var gender by remember(initial) { mutableStateOf(initial?.gender ?: PersonaGender.UNSPECIFIED) }

    // ── 立体度的第二组（2026-10-28）────────────────────────────
    var verbosity by remember(initial) {
        mutableStateOf(initial?.verbosity ?: com.pocketagent.personalogic.PersonaVerbosity.NORMAL)
    }
    var dailyLife by remember(initial) { mutableStateOf(initial?.dailyLife.orEmpty()) }
    var interests by remember(initial) { mutableStateOf(initial?.interests?.joinToString("、").orEmpty()) }
    var taboos by remember(initial) { mutableStateOf(initial?.taboos?.joinToString("、").orEmpty()) }
    var sharedRefs by remember(initial) {
        mutableStateOf(initial?.sharedReferences?.joinToString("、").orEmpty())
    }
    var initiative by remember(initial) {
        mutableStateOf(initial?.initiative ?: com.pocketagent.personalogic.PersonaInitiative.NORMAL)
    }
    var sleepHour by remember(initial) { mutableStateOf((initial?.sleepHour ?: 23).toString()) }
    var wakeHour by remember(initial) { mutableStateOf((initial?.wakeHour ?: 7).toString()) }

    // 把所有输入拼起来交给识别器 —— 用户在任何一个框里写到"走了"都该被感知
    val combinedText = listOf(name, identity, relationTo, catchphrases).joinToString(" ")
    val signal = remember(combinedText) { KinshipDetector.detect(combinedText) }

    // ══════════════════════════════════════════════════════════════
    //  ★★ 2026-10-29 第二次修「保存后设定消失」——这次修的是**成因**
    // ══════════════════════════════════════════════════════════════
    //
    // 第一次的修法（留住原因 + 禁用按钮）是错的：它把
    // 「用户输入撞到我们自己的 24 字/8 条限制」当成了**用户要修的问题**。
    // 用户是在描述一个人，没有哪种写法是错的。
    //
    // 真机根因（对着设备上的 `custom_persona.preferences_pb` 确认）：
    //   ① 组合期用 `CustomPersona(...)` 造预览对象，外面包 `runCatching`
    //   ② 输入越过 init 的 `require`（名字 > 24 字 / 口癖 > 8 条）⇒ 抛异常
    //   ③ 兜底 = 空壳 `CustomPersona(id="draft", name="（未命名）")`
    //   ④ 按钮可用性看的是**输入框原文**，仍然可点
    //   ⑤ 点保存 ⇒ **空壳被写进盘**
    //   ⑥ 保存后 `initial` 变成那个空壳 ⇒ 表单按 `initial` 重填 ⇒
    //      用户看到的就是「刚填的全没了」
    //
    // ⇒ 现在的构造走 `PersonaInput.toPersona`，它**永不抛异常**：
    //   超长名字按字符截断、口癖取前 8 条、作息夹到 0..23。
    //   于是既不需要 `runCatching`，也不需要把错误写进 state ——
    //   而"在组合期写 state"本身会触发无限重组，那也是要避免的。
    //
    // ⚠️ 空壳这条路已经彻底封死：占位名在 store 那一侧会被**拒绝保存**
    //    （`CustomPersonaStore.save`），盘上已有的空壳会被 `decode` 当作
    //    "没设过" —— 三处都拦，因为这个 bug 的实质就是
    //    "占位被当成真数据存了进去"。
    //
    // ★ 表单没展示的字段（自称/方言/记忆/头像）从 `initial` 透传：
    //   用户改个名字不该顺手把他写过的方言删掉。
    // ★ 唯一的组装入口：草稿与保存都走这里。
    //   两处各造一次必然漂移（上一版就是这样：草稿和保存用的不是同一套逻辑）。
    fun assemble(id: String, rawName: String) = PersonaInput.toPersona(
        // ⚠️ 预览用的 id 只在"新建"时需要一个稳定值 ——
        //    用时间戳会让每次重组都换 id，而保存时**另外**生成正式 id。
        id = id,
        name = rawName,
        identity = identity,
        relationTo = relationTo,
        addressUser = addressUser,
        addressSelf = initial?.addressSelf.orEmpty(),
        dialect = initial?.dialect.orEmpty(),
        gender = gender,
        avatarRes = initial?.avatarRes,
        catchphrases = catchphrases,
        memories = initial?.memories.orEmpty(),
        flaws = flaws,
        // ★ 立体度第二组：**草稿也要带上**，否则预览效果评估会与实际不符。
        verbosity = verbosity,
        dailyLife = dailyLife,
        interests = interests,
        taboos = taboos,
        sharedReferences = sharedRefs,
        initiative = initiative,
        sleepHour = sleepHour,
        wakeHour = wakeHour,
    )

    /** 这一帧的画面 —— 名字没填时是占位，**只用于渲染**。 */
    val draft = assemble(initial?.id ?: DRAFT_ID, name)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PaSpace.screenH, end = PaSpace.screenH,
            top = PaSpace.xs, bottom = PaSpace.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
    ) {
        item {
            Text(
                text = "这个角色是谁",
                style = PaType.title,
                color = PaColor.TextPrimary,
            )
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "说清他是谁、怎么说话，比调一堆滑杆有用。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        item { Spacer(Modifier.height(PaSpace.s)) }

        // ── 基础信息 ─────────────────────────────────────────────
        item {
            PaField(
                label = "名字",
                value = name,
                placeholder = "他会怎么介绍自己",
                // ⚠️ 输入期就截断，而不是提交时判为非法。
                //    过长的粘贴会**当场**停在 24 字，用户立刻看见；
                //    而不是填完一按保存、设定整个变空（那正是原来的 bug）。
                onValueChange = { name = PersonaInput.clampName(it) },
            )
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = "最多 ${CustomPersona.MAX_NAME} 字 —— 名字就是个称呼。" +
                    "想写一整段设定，写进下面那栏。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
        item {
            PaField(
                label = "这是一个什么人",
                value = identity,
                placeholder = "一句话说清他的职业、性格、说话方式",
                singleLine = false,
                onValueChange = { identity = it },
            )
        }
        item {
            PaField(
                label = "和你的关系",
                value = relationTo,
                placeholder = "我的大学室友 / 我妈",
                onValueChange = { relationTo = it },
            )
        }
        item {
            PaField(
                label = "他怎么称呼你",
                value = addressUser,
                placeholder = "小陈 / 你",
                onValueChange = { addressUser = it },
            )
        }
        item {
            PaField(
                label = "他常说的话",
                value = catchphrases,
                placeholder = "用顿号隔开，如：这事儿啊、慢慢来",
                onValueChange = { catchphrases = it },
            )
            // ★ 口癖是唯一有数量上限的栏位（上限现在由 `PersonaInput` 执行，
            //   不再由构造函数抛异常执行）。超出的**只取前几条** ——
            //   但必须当场说出来，否则用户会以为写进去的都生效了。
            val allPhrases = PersonaInput.splitList(catchphrases)
            if (allPhrases.size > CustomPersona.MAX_CATCHPHRASES) {
                Spacer(Modifier.height(PaSpace.xxs))
                Text(
                    text = "写了 ${allPhrases.size} 条，只用前 ${CustomPersona.MAX_CATCHPHRASES} 条 —— " +
                        "太多会让模型混着用，反而不像他。",
                    style = PaType.caption,
                    color = PaColor.Warning,
                )
            }
        }

        // ══════════════════════════════════════════════════════════
        //  ★★ 立体度第二组 —— 去掉"AI 味"的那几栏
        // ══════════════════════════════════════════════════════════
        //
        // 判断标准只有一条：**这一栏能不能改变它在某一轮里说的话？**
        //   · 说话长度 → 直接决定篇幅（AI 味的头号来源是"每次都三段论"）
        //   · 日常     → 解决"AI 永远随时在线"的违和感
        //   · 兴趣/雷点 → 人有所不谈
        //   · 共同经历 → 只有你俩懂的梗，比十行形容词更能让关系显得真实
        item { Spacer(Modifier.height(PaSpace.s)) }
        item {
            Text("让他更像个人", style = PaType.body, color = PaColor.TextPrimary)
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                "下面这几栏不填也能用，但填了之后他会明显不一样。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }
        item {
            // 说话长度：三选一
            Text("说话长短", style = PaType.caption, color = PaColor.TextSecondary)
            Spacer(Modifier.height(PaSpace.xxs))
            Row {
                for (v in com.pocketagent.personalogic.PersonaVerbosity.entries) {
                    PaButton(
                        text = v.label,
                        onClick = { verbosity = v },
                        style = if (verbosity == v) PaButtonStyle.Primary else PaButtonStyle.Glass,
                        modifier = Modifier.padding(end = PaSpace.xs),
                    )
                }
            }
        }
        item {
            PaField(
                label = "他平时在干嘛",
                value = dailyLife,
                placeholder = "例：在一家小公司写代码，最近老加班，通勤要一小时",
                onValueChange = { dailyLife = it },
            )
        }
        item {
            PaField(
                label = "爱聊什么",
                value = interests,
                placeholder = "用顿号分隔。例：老电影、游戏、做饭",
                onValueChange = { interests = it },
            )
        }
        item {
            PaField(
                label = "不爱聊什么",
                value = taboos,
                placeholder = "用顿号分隔。例：催婚、体重、前公司",
                onValueChange = { taboos = it },
            )
        }
        item {
            PaField(
                label = "你们之间才懂的说法",
                value = sharedRefs,
                placeholder = "用顿号分隔。例：那天那家店、第三排、老王",
                singleLine = false,
                onValueChange = { sharedRefs = it },
            )
        }
        item {
            Text("主动程度", style = PaType.caption, color = PaColor.TextSecondary)
            Spacer(Modifier.height(PaSpace.xxs))
            Row {
                for (v in com.pocketagent.personalogic.PersonaInitiative.entries) {
                    PaButton(
                        text = v.label,
                        onClick = { initiative = v },
                        style = if (initiative == v) PaButtonStyle.Primary else PaButtonStyle.Glass,
                        modifier = Modifier.padding(end = PaSpace.xs),
                    )
                }
            }
        }
        item {
            Row {
                Box(Modifier.weight(1f)) {
                    PaField(
                        label = "几点睡",
                        value = sleepHour,
                        placeholder = "23",
                        onValueChange = { sleepHour = it.filter(Char::isDigit).take(2) },
                    )
                }
                Spacer(Modifier.width(PaSpace.s))
                Box(Modifier.weight(1f)) {
                    PaField(
                        label = "几点起",
                        value = wakeHour,
                        placeholder = "7",
                        onValueChange = { wakeHour = it.filter(Char::isDigit).take(2) },
                    )
                }
            }
        }
        item {
            Text(
                "作息只影响语气（困了会敷衍、刚醒会短），不影响他能不能回你 —— " +
                    "你随时找他，他都在。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }


        //
        // 用户原话："为了尽可能模拟这个，会在用户自定义角色设定的时候，
        // 要求用户写入这个角色的坏习惯。"
        //
        // ⚠️ 为什么必须有这一栏：系统随机编的脾气**一定 OOC**。
        //    只有用户自己写下来的毛病，"他也会这样"才立得住。
        item { Spacer(Modifier.height(PaSpace.s)) }
        item {
            PaField(
                label = "他有哪些毛病",
                value = flaws,
                placeholder = "每行一条，格式：标签：具体表现\n" +
                    "例：爱较真：一说到我的工作就数落我，说我不该辞职",
                singleLine = false,
                onValueChange = { flaws = it },
            )
        }
        item {
            Text(
                text = "写下来之后，这个人才会真的跟你闹别扭 —— " +
                    "而且闹的都是你写的这些，不是系统编的脾气。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }

        // ── 性别（三档，含不指定）────────────────────────────────
        item { Spacer(Modifier.height(PaSpace.s)) }
        item { PaSectionTitle("性别") }
        item {
            PaListGroup {
                Row(modifier = Modifier.fillMaxWidth().padding(PaSpace.m)) {
                    for (g in PersonaGender.entries) {
                        PaButton(
                            text = g.label,
                            onClick = { gender = g },
                            style = if (gender == g) PaButtonStyle.Primary else PaButtonStyle.Text,
                            modifier = Modifier.padding(end = PaSpace.xs),
                        )
                    }
                }
            }
        }

        // ── 完整性提示：填了但没用的地方要说清楚 ─────────────────
        val hints = PersonaCompleteness.hints(draft)
        if (hints.isNotEmpty()) {
            item { Spacer(Modifier.height(PaSpace.s)) }
            item {
                PaBanner(
                    tone = PaBannerTone.Info,
                    title = "还可以补 ${hints.size} 处",
                    description = hints.joinToString("\n") { "· ${it.field}：${it.why}" },
                )
            }
        }

        // ══════════════════════════════════════════════════════════
        //  ★ 未亡人模式的建议 —— **只在识别到时出现，且不做成诱人的卡片**
        // ══════════════════════════════════════════════════════════
        when (signal) {
            is LegacySignal.Likely -> {
                item { Spacer(Modifier.height(PaSpace.s)) }
                item {
                    // ★ 天数由「离开的时间」实时算，不再写死。
                    //   ⚠️ 写死 0 会让所有人永远停在"最初的日子" ——
                    //      而那个阶段的提示是最轻的，于是长期用户
                    //      **永远等不到**那句"该找个人说说话了"。
                    val phase = remember { GriefPhase.of(daysSincePassed()) }
                    LegacySuggestionCard(
                        relation = signal.relation,
                        onRequest = { onRequestLegacy(signal.relation, signal.displayName) },
                        // ⚠️ 措辞来自 `KinshipDetector.suggestion`，不在这里重写 ——
                        //    它是"不说错话"的唯一来源，两处各写一份必然漂移。
                        message = remember(signal) { KinshipDetector.suggestion(signal).message },
                        boundaryNote = remember(phase) {
                            LegacyBoundary.openingNote(phase, signal.displayName ?: "他")
                        },
                    )
                }
            }
            is LegacySignal.Ambiguous -> {
                item { Spacer(Modifier.height(PaSpace.s)) }
                item {
                    PaBanner(
                        tone = PaBannerTone.Info,
                        title = "你提到的是一位已经不在了的亲人吗？",
                        description = "如果是，我可以做另一个版本 —— 用你记得的样子，" +
                            "加上一条它自己的日常生活。如果你只是在描述想要的助手风格，忽略这条就行。",
                    )
                }
            }
            LegacySignal.None -> Unit
        }

        // ── 保存 ─────────────────────────────────────────────────
        item { Spacer(Modifier.height(PaSpace.l)) }
        item {
            PaButton(
                text = "保存角色",
                onClick = {
                    // ★ 用**输入框当前值**重新组装一次。
                    //   `assemble` 不会抛异常，所以这里不存在"点了没反应"——
                    //   要么真的存进去，要么由 store 明确报错（占位名会被拒）。
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) return@PaButton
                    onSave(
                        assemble(
                            id = initial?.id ?: "custom-${System.currentTimeMillis()}",
                            rawName = trimmed,
                        )
                    )
                },
                style = PaButtonStyle.Primary,
                enabled = name.trim().isNotEmpty(),
            )
        }
    }
}

/**
 * 未亡人建议卡。
 *
 * ★ 它**刻意长得不像功能入口**：没有大图标、没有渐变色、没有"立即体验"。
 *   它是一段话加一个不显眼的按钮。
 */
@Composable
private fun LegacySuggestionCard(
    relation: RelationKind,
    onRequest: () -> Unit,
    message: String,
    boundaryNote: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PaSpace.xs),
    ) {
        Text(
            text = message,
            style = PaType.body,
            color = PaColor.TextPrimary,
        )
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = boundaryNote,
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )
        Spacer(Modifier.height(PaSpace.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaButton(
                text = "好，按我记得的样子做",
                // ⚠️ 2026-10-25 自我走查：这里原本是空实现 `{}` ——
                //    和 `onSave` 那个 bug 是同一类：按钮能点，但什么都不会发生，
                //    也不报错。用户会以为"没生效"，然后反复点。
                //
                // ⇒ 现在至少把"要开启未亡人模式"这件事**明确传出去**，
                //   由调用方决定下一步。完整配置页是下一步的工作，
                //   但**不能留一个空按钮** —— 空按钮比没有按钮更糟。
                onClick = onRequest,
                style = PaButtonStyle.Text,
            )
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = "不想的话直接忽略",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
    }
}

/** 统一风格的输入框 —— 本页内部用，避免每处重复写样式。 */
@Composable
private fun PaField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = PaType.label,
            color = PaColor.TextSecondary,
        )
        Spacer(Modifier.height(PaSpace.xxs))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(text = placeholder, style = PaType.body, color = PaColor.TextTertiary)
            },
            textStyle = PaType.body.copy(color = PaColor.TextPrimary),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.fillMaxWidth(),
            // ══════════════════════════════════════════════════════════
            //  ★★ 2026-10-27 修：深色主题下"输入的字体与背景融成一体"
            // ══════════════════════════════════════════════════════════
            //
            // 根因：不指定 `colors` 时，`OutlinedTextField` 用 Material3 的
            // 默认配色 —— 那套读的是 `MaterialTheme.colorScheme`，
            // 而我们的主题**刻意只映射了 8 个槽位**（见 AppTheme 注释）。
            //
            // 于是：**背景来自 PaColor，文字色来自 Material 默认** ——
            // 深色下最严重：默认文字色是深色，背景也是深色 ⇒ 字看不见。
            //
            // ⚠️ 输入框有 7 类视觉槽位（文字/光标/边框三态/占位/容器），
            //    漏一个就还是花的。这里一次全部钉死到 PaColor。
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = PaColor.TextPrimary,
                unfocusedTextColor = PaColor.TextPrimary,
                disabledTextColor = PaColor.TextDisabled,
                cursorColor = PaColor.Accent,
                focusedPlaceholderColor = PaColor.TextTertiary,
                unfocusedPlaceholderColor = PaColor.TextTertiary,
                // 聚焦时用强调色边框 —— 明确的"我正在这里打字"信号
                focusedBorderColor = PaColor.Accent,
                unfocusedBorderColor = PaColor.GlassBorder,
                errorBorderColor = PaColor.Danger,
                // 容器透明：让页面的暖色/深色底透上来（与卡片同一材质）
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

/**
 * 距那个人离开多少天。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这里暴露了一个**真实缺口**：界面上还没有「离开的日期」这一栏
 * ═══════════════════════════════════════════════════════════════
 *
 * `LegacyProfile` 要求 `passedAwayAtMs` 必填，但角色设定页收集不到它 ——
 * 所以现在只能返回 0（等于"刚离开"），哀伤分期永远停在最初。
 *
 * ⇒ 这是个**已知的、必须补的**缺口，不是可以含糊过去的东西：
 *    它导致"长期用户永远等不到那句关心"。
 *    补法是在未亡人配置页里加一个日期选择（那时才需要，
 *    普通自定义角色不需要知道任何人什么时候离开）。
 *
 * ⚠️ 返回 0 是**保守**的选择：把提示做得更轻，而不是说重话。
 */
private fun daysSincePassed(): Int = 0

/**
 * 新建角色时，预览用草稿的稳定 id。
 *
 * ⚠️ 它**不会**被保存到盘上：保存路径另外生成 `custom-<时间戳>`。
 * 它存在的唯一理由：让"还没保存"这一帧有一个稳定的 id，
 * 而不是每次重组都换一个时间戳（那会让 `remember(initial)` 之类的键来回跳）。
 */
private const val DRAFT_ID = "custom-draft"
