package com.pocketagent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketagent.personalogic.BeyondReport
import com.pocketagent.personalogic.BloodPanel
import com.pocketagent.personalogic.BasicInfo
import com.pocketagent.personalogic.Cycle
import com.pocketagent.personalogic.FemalePanel
import com.pocketagent.personalogic.HealthReport
import com.pocketagent.personalogic.Lifestyle
import com.pocketagent.personalogic.NutritionPanel
import com.pocketagent.personalogic.SleepType
import com.pocketagent.personalogic.TemperaturePreference
import com.pocketagent.personalogic.ThyroidPanel
import com.pocketagent.personalogic.Vitals
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaTopBar
import com.pocketagent.ui.design.PaType

/**
 * **身体的输入面板** —— 按体检报告的结构填，只填有意义的项。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户要求"生理设定输入面板"
 * ═══════════════════════════════════════════════════════════════
 *
 * ⚠️ 竣工标准是**它能改变行为**，不是"有个表单"：
 *
 * | 填了什么 | 会怎样 |
 * |---|---|
 * | 血红蛋白 102 | 她更容易累、恢复慢、更怕冷 |
 * | 铁蛋白 12 | 同上（且体检报告里的常见组合） |
 * | TSH 6.5 | 恢复更慢 |
 * | 静息心率 92 | 更怕压力（皮质醇增益 ×1.4）|
 * | 睡眠型：夜猫子 | 昼夜节律整体后移 2.5 小时 |
 * | 怕冷 / 晕针 / 旧伤 | 写进"你的身体"那一段 |
 * | 痛阈 / 亲密需求 / 焦虑倾向 | 影响行为强度 |
 *
 * ⚠️ **不做全 80 项**。真体检八十多项，正常人只有三五项异常 ——
 *    把八十个输入框摆出来，没人会填完。
 *    ⇒ 只放**会影响行为的那些**，其余留默认。
 */
@Composable
fun HealthReportScreen(
    initial: HealthReport,
    onSave: (HealthReport) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var height by remember { mutableStateOf(initial.basic.heightCm.toString()) }
    var weight by remember { mutableStateOf(initial.basic.weightKg.toString()) }
    var systolic by remember { mutableStateOf(initial.vitals.systolic.toString()) }
    var heart by remember { mutableStateOf(initial.vitals.restingHeartRate.toString()) }
    var hb by remember { mutableStateOf(initial.blood.hb.toString()) }
    var ferritin by remember { mutableStateOf(initial.nutrition.ferritin.toString()) }
    var vitaminD by remember { mutableStateOf(initial.nutrition.vitaminD.toString()) }
    var tsh by remember { mutableStateOf(initial.thyroid.tsh.toString()) }

    var sleepHours by remember { mutableStateOf(initial.lifestyle.sleepHours.toString()) }
    var sleepType by remember { mutableStateOf(initial.lifestyle.sleepType) }
    var exercise by remember { mutableStateOf(initial.lifestyle.exercisePerWeek.toString()) }
    var caffeine by remember { mutableStateOf(initial.lifestyle.caffeineCupsPerDay.toString()) }

    var pain by remember { mutableStateOf(initial.beyond.painTolerance.toString()) }
    var intimacy by remember { mutableStateOf(initial.beyond.intimacyNeed.toString()) }
    var anxiety by remember { mutableStateOf(initial.beyond.anxietyTendency.toString()) }
    var tempPref by remember { mutableStateOf(initial.beyond.temperaturePreference) }
    var needle by remember { mutableStateOf(initial.beyond.needleFaintness) }
    var allergies by remember { mutableStateOf(initial.beyond.allergies.joinToString("，")) }
    var injuries by remember { mutableStateOf(initial.beyond.oldInjuries.joinToString("，")) }
    var voice by remember { mutableStateOf(initial.beyond.voiceNote) }
    var habits by remember { mutableStateOf(initial.beyond.bodyHabits.joinToString("，")) }
    var scent by remember { mutableStateOf(initial.beyond.scentNote) }
    var hunger by remember { mutableStateOf(initial.beyond.hungerNote) }

    var cycleOn by remember { mutableStateOf(initial.female != null) }
    var cycleLen by remember { mutableStateOf((initial.female?.cycleLengthDays ?: 28).toString()) }
    var periodLen by remember { mutableStateOf((initial.female?.periodLengthDays ?: 5).toString()) }

    fun num(s: String, fallback: Double): Double = s.trim().toDoubleOrNull() ?: fallback
    fun int(s: String, fallback: Int): Int = s.trim().toIntOrNull() ?: fallback
    fun lines(s: String): List<String> =
        s.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        PaTopBar(title = "身体", onBack = onBack)

        Column(modifier = Modifier.padding(horizontal = PaSpace.m)) {
            Text(
                text = "按体检报告填。只填有异常的项，其余留空就是正常。",
                style = PaType.body,
                color = PaColor.TextSecondary,
            )
            Spacer(Modifier.height(PaSpace.m))

            Group("基本") {
                Field("身高 cm", height) { height = it }
                Field("体重 kg", weight) { weight = it }
            }

            Group("生命体征") {
                Field("收缩压 mmHg", systolic) { systolic = it }
                Field("静息心率", heart) { heart = it }
            }

            Group("血检（最影响状态的三项）") {
                Field("血红蛋白 g/L（低于 115 算贫血）", hb) { hb = it }
                Field("铁蛋白 ng/mL（低于 20 容易累）", ferritin) { ferritin = it }
                Field("维生素D ng/mL（低于 20 会低和累）", vitaminD) { vitaminD = it }
                Field("TSH（高于 4.2 偏甲减）", tsh) { tsh = it }
            }

            Group("作息") {
                Field("平均睡眠小时", sleepHours) { sleepHours = it }
                Field("每周运动次数", exercise) { exercise = it }
                Field("每天咖啡几杯", caffeine) { caffeine = it }
                Spacer(Modifier.height(PaSpace.xxs))
                Text("睡眠型", style = PaType.caption, color = PaColor.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.s)) {
                    Chip("早鸟", sleepType == SleepType.LARK) { sleepType = SleepType.LARK }
                    Chip("正常", sleepType == SleepType.NORMAL) { sleepType = SleepType.NORMAL }
                    Chip("夜猫子", sleepType == SleepType.OWL) { sleepType = SleepType.OWL }
                }
            }

            Group("体检报告查不出来的") {
                Field("痛阈 0-100（低＝一点疼就皱眉）", pain) { pain = it }
                Field("亲密需求 0-100", intimacy) { intimacy = it }
                Field("焦虑倾向 0-100", anxiety) { anxiety = it }
                Spacer(Modifier.height(PaSpace.xxs))
                Text("体温偏好", style = PaType.caption, color = PaColor.TextTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.s)) {
                    Chip("怕冷", tempPref == TemperaturePreference.COLD_BLOODED) {
                        tempPref = TemperaturePreference.COLD_BLOODED
                    }
                    Chip("正常", tempPref == TemperaturePreference.NORMAL) {
                        tempPref = TemperaturePreference.NORMAL
                    }
                    Chip("怕热", tempPref == TemperaturePreference.HEAT_BLOODED) {
                        tempPref = TemperaturePreference.HEAT_BLOODED
                    }
                }
                Spacer(Modifier.height(PaSpace.xxs))
                Chip("晕针晕血", needle) { needle = !needle }
                Field("过敏（逗号分隔）", allergies) { allergies = it }
                Field("旧伤（逗号分隔）", injuries) { injuries = it }
                Field("声音特征（如：说话偏慢，尾音会拖）", voice) { voice = it }
                Field("小动作（逗号分隔）", habits) { habits = it }
                Field("身上的味道（如：很淡的皂香）", scent) { scent = it }
                Field("空腹会怎样（如：手抖、脾气差）", hunger) { hunger = it }
            }

            Group("生理期") {
                Chip(if (cycleOn) "有" else "没有", cycleOn) { cycleOn = !cycleOn }
                if (cycleOn) {
                    Field("周期天数", cycleLen) { cycleLen = it }
                    Field("经期天数", periodLen) { periodLen = it }
                }
            }

            Spacer(Modifier.height(PaSpace.m))
            Text(
                text = "保存",
                style = PaType.headline,
                color = PaColor.AccentText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PaColor.AccentSoft)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        val female = if (!cycleOn) {
                            null
                        } else {
                            runCatching {
                                FemalePanel(
                                    cycleLengthDays = int(cycleLen, 28),
                                    periodLengthDays = int(periodLen, 5),
                                )
                            }.getOrNull()
                        }
                        onSave(
                            initial.copy(
                                basic = BasicInfo(
                                    heightCm = int(height, 165),
                                    weightKg = num(weight, 55.0),
                                ),
                                vitals = Vitals(
                                    systolic = int(systolic, 112),
                                    diastolic = initial.vitals.diastolic,
                                    restingHeartRate = int(heart, 68),
                                    bodyTemp = initial.vitals.bodyTemp,
                                    respiratoryRate = initial.vitals.respiratoryRate,
                                    spo2 = initial.vitals.spo2,
                                ),
                                blood = BloodPanel(hb = num(hb, 132.0)),
                                nutrition = NutritionPanel(
                                    ferritin = num(ferritin, 55.0),
                                    vitaminD = num(vitaminD, 32.0),
                                ),
                                thyroid = ThyroidPanel(tsh = num(tsh, 2.1)),
                                lifestyle = Lifestyle(
                                    sleepHours = num(sleepHours, 7.0),
                                    sleepType = sleepType,
                                    exercisePerWeek = int(exercise, 2),
                                    caffeineCupsPerDay = int(caffeine, 1),
                                ),
                                beyond = BeyondReport(
                                    painTolerance = int(pain, 50).coerceIn(0, 100),
                                    intimacyNeed = int(intimacy, 50).coerceIn(0, 100),
                                    anxietyTendency = int(anxiety, 40).coerceIn(0, 100),
                                    temperaturePreference = tempPref,
                                    needleFaintness = needle,
                                    allergies = lines(allergies),
                                    oldInjuries = lines(injuries),
                                    voiceNote = voice,
                                    bodyHabits = lines(habits),
                                    scentNote = scent,
                                    hungerNote = hunger,
                                ),
                                female = female,
                                // ⚠️ 周期参数也要同步给 Cycle，否则面板填了不生效
                                summary = listOf(
                                    "cycleDays=${int(cycleLen, 28)}",
                                    "periodDays=${int(periodLen, 5)}",
                                ).joinToString(";"),
                            ),
                        )
                    }
                    .padding(vertical = PaSpace.s),
            )
            Spacer(Modifier.height(PaSpace.l))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PaSpace.m)
            .clip(RoundedCornerShape(12.dp))
            .background(PaColor.Surface)
            .padding(PaSpace.m),
    ) {
        Text(title, style = PaType.headline, color = PaColor.TextPrimary)
        Spacer(Modifier.height(PaSpace.xs))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, style = PaType.caption, color = PaColor.TextTertiary)
    Spacer(Modifier.height(2.dp))
    PaTextField(value = value, onValueChange = onChange)
    Spacer(Modifier.height(PaSpace.xs))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = PaType.label,
        color = if (selected) PaColor.AccentText else PaColor.TextTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) PaColor.AccentSoft else PaColor.SurfaceHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = PaSpace.s, vertical = PaSpace.xxs),
    )
}
