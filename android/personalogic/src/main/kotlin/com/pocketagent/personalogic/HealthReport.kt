package com.pocketagent.personalogic

/**
 * **角色体检报告** —— 按现代完整体检的结构来，但只保留**会影响行为**的部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：为什么用体检报告当设定格式
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求："角色设定的填入我们按照现代医学的体检报告来设定"。
 *
 * 这个要求很对，理由不是"看起来专业"，而是：
 * **体检报告的结构逼你把身体说具体。**
 *
 * 写"她身体不太好"是没用的 —— 模型只能演成一个模糊的虚弱。
 * 写"血红蛋白 102 g/L，铁蛋白 11 ng/mL"是另一回事 ——
 * 那意味着**她爬两层楼会喘、冬天手脚冰凉、下午三四点会突然没电**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 设计原则：**默认全部正常，只填异常项**
 * ═══════════════════════════════════════════════════════════════
 *
 * 一份真体检报告八十多项，正常人通常只有三到五项标红。
 * 如果要求用户填八十个数字，没人会填。
 *
 * ⇒ 所有字段都有"正常成年人"的合理默认值；
 *   用户只需要填**异常的**那几项 + [BeyondReport]（报告覆盖不到的）。
 *
 * ⚠️ 这一点很重要：**报告的价值在异常项**。
 *    全绿的报告等于没写。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 边界声明
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是**虚构角色的设定格式**，不是医学建议，也不做诊断。
 * 数值只用于驱动角色行为（累不累、怕不怕冷），不用于任何健康判断。
 */
@kotlinx.serialization.Serializable
data class HealthReport(
    val basic: BasicInfo = BasicInfo(),
    val vitals: Vitals = Vitals(),
    /** 血常规 */
    val blood: BloodPanel = BloodPanel(),
    /** 生化：肝、肾、血脂、血糖 */
    val chemistry: ChemistryPanel = ChemistryPanel(),
    /** 甲状腺功能 */
    val thyroid: ThyroidPanel = ThyroidPanel(),
    /** 心电图 */
    val ecg: Ecg = Ecg(),
    /** 影像发现（超声/CT 的**结论**，不是片子） */
    val imaging: ImagingFindings = ImagingFindings(),
    /** 骨密度与营养相关 */
    val nutrition: NutritionPanel = NutritionPanel(),
    /** 女性专项。没有就不传 */
    val female: FemalePanel? = null,
    /** 生活方式问卷（体检报告固定有一节） */
    val lifestyle: Lifestyle = Lifestyle(),
    /** **体检报告覆盖不到的部分** —— 见 [BeyondReport] */
    val beyond: BeyondReport = BeyondReport(),
    /** 医生总结（一句话，可选）。这是报告最后那行"结论与建议" */
    val summary: String = "",
) {

    /**
     * 从报告推导出**引擎参数**。
     *
     * ⚠️ 这是本文件存在的意义：报告如果只变成一段描述文字，那是装饰。
     *    它必须改变 [PhysiologyEngine] / [HormoneEngine] 的行为，
     *    否则用户在设定里写"贫血"和写"健康"没有区别。
     */
    fun derive(): BodyProfile = BodyProfile(
        // ── 疲劳与恢复 ──────────────────────────────
        fatigueThreshold = when {
            // 血红蛋白低 / 铁蛋白低 ⇒ 更容易累
            blood.hb < 115.0 || nutrition.ferritin < 20.0 -> FatigueThreshold.LOW
            nutrition.vitaminD < 20.0 -> FatigueThreshold.LOW
            lifestyle.sleepHours < 6.5 -> FatigueThreshold.LOW
            else -> FatigueThreshold.NORMAL
        },
        recoverRate = when {
            // 甲减 / 贫血 ⇒ 恢复慢
            thyroid.tsh > 4.2 || blood.hb < 115.0 -> RecoverRate.SLOW
            lifestyle.exercisePerWeek >= 4 -> RecoverRate.FAST
            else -> RecoverRate.NORMAL
        },
        // ── 温度敏感 ────────────────────────────────
        coldSensitivity = when {
            blood.hb < 115.0 || vitals.systolic < 100 -> ColdSensitivity.HIGH
            vitals.systolic > 130 -> ColdSensitivity.LOW
            else -> ColdSensitivity.NORMAL
        },
        // ── 昼夜偏移 ────────────────────────────────
        // ⚠️ 夜猫子不是"晚睡"，是**整个节律后移**
        circadianShiftHours = when (lifestyle.sleepType) {
            SleepType.OWL -> 2.5
            SleepType.LARK -> -1.5
            SleepType.NORMAL -> 0.0
        },
        // ── 压力敏感 ────────────────────────────────
        // 静息心率快、有焦虑史 ⇒ 皮质醇更敏感、恢复更慢
        stressSensitivity = when {
            vitals.restingHeartRate >= 85 || beyond.anxietyTendency >= 70 -> StressSensitivity.HIGH
            vitals.restingHeartRate <= 60 -> StressSensitivity.LOW
            else -> StressSensitivity.NORMAL
        },
        // ── 痛阈 ────────────────────────────────────
        painTolerance = beyond.painTolerance,
        // ── 亲密需求 ────────────────────────────────
        intimacyNeed = beyond.intimacyNeed,
        // ── 需要偶尔触发的生理事件（过敏/旧伤/晕针…） ──
        triggers = buildList {
            if (beyond.allergies.isNotEmpty()) add(PhysioTrigger.ALLERGY)
            if (beyond.oldInjuries.isNotEmpty()) add(PhysioTrigger.OLD_INJURY)
            if (beyond.needleFaintness) add(PhysioTrigger.NEEDLE)
            if (lifestyle.caffeineCupsPerDay >= 4) add(PhysioTrigger.CAFFEINE)
            if (lifestyle.alcoholPerWeek >= 5) add(PhysioTrigger.ALCOHOL)
            if (nutrition.vitaminD < 20.0) add(PhysioTrigger.LOW_VITAMIN_D)
        }.toSet(),
    )

    /**
     * 报告里**标红**的项 —— 写成一句话，供提示词使用。
     *
     * ★ 只写异常项。全绿时返回空串（正常人就是没有特别要说的）。
     */
    fun abnormalNote(): String {
        val notes = buildList {
            if (blood.hb < 115.0) add("血红蛋白偏低")
            if (nutrition.ferritin < 20.0) add("铁蛋白偏低")
            if (nutrition.vitaminD < 20.0) add("维生素D不足")
            if (thyroid.tsh > 4.2) add("甲状腺功能偏低")
            if (thyroid.tsh < 0.35) add("甲状腺功能偏高")
            if (vitals.systolic < 100) add("血压偏低")
            if (vitals.systolic >= 130) add("血压偏高")
            if (chemistry.ldl >= 3.4) add("低密度脂蛋白偏高")
            if (chemistry.fastingGlucose >= 6.1) add("空腹血糖偏高")
            if (ecg.abnormal) add("心电图有异常")
            if (imaging.findings.isNotEmpty()) addAll(imaging.findings)
        }
        return if (notes.isEmpty()) "" else notes.joinToString("、")
    }
}

// ═══════════════════════════════════════════════════════════════
//  一、基本信息与生命体征
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class BasicInfo(
    /** 身高 cm */
    val heightCm: Int = 165,
    /** 体重 kg */
    val weightKg: Double = 55.0,
    /** 腰围 cm */
    val waistCm: Int = 68,
    /** 年龄段：不填具体生日，只填区间 —— 报告只关心这个 */
    val ageBand: AgeBand = AgeBand.ADULT,
) {
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}

enum class AgeBand { YOUTH, ADULT, MIDDLE, SENIOR }

@kotlinx.serialization.Serializable
data class Vitals(
    /** 收缩压 mmHg */
    val systolic: Int = 112,
    /** 舒张压 mmHg */
    val diastolic: Int = 72,
    /** 静息心率 */
    val restingHeartRate: Int = 68,
    /** 体温 ℃ */
    val bodyTemp: Double = 36.5,
    /** 呼吸频率 */
    val respiratoryRate: Int = 16,
    /** 血氧 % */
    val spo2: Int = 98,
)

// ═══════════════════════════════════════════════════════════════
//  二、血常规
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class BloodPanel(
    /** 白细胞 10^9/L */
    val wbc: Double = 6.2,
    /** 红细胞 10^12/L */
    val rbc: Double = 4.4,
    /** 血红蛋白 g/L ★ 最常见的异常项 */
    val hb: Double = 132.0,
    /** 红细胞压积 % */
    val hct: Double = 40.0,
    /** 平均红细胞体积 fL（低+贫血 ⇒ 缺铁） */
    val mcv: Double = 90.0,
    /** 血小板 10^9/L */
    val plt: Double = 220.0,
    /** 中性粒细胞比例 % */
    val neutrophilPct: Double = 58.0,
)

// ═══════════════════════════════════════════════════════════════
//  三、生化
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class ChemistryPanel(
    // 肝
    val alt: Double = 20.0,
    val ast: Double = 22.0,
    val albumin: Double = 45.0,
    // 肾
    val creatinine: Double = 62.0,
    val urea: Double = 4.8,
    val uricAcid: Double = 280.0,
    // 血脂
    val totalCholesterol: Double = 4.4,
    val triglyceride: Double = 1.0,
    val hdl: Double = 1.5,
    val ldl: Double = 2.4,
    // 血糖
    val fastingGlucose: Double = 4.9,
    val hba1c: Double = 5.1,
)

// ═══════════════════════════════════════════════════════════════
//  四、甲状腺 / 心电
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class ThyroidPanel(
    /** TSH mIU/L。高=甲减（怕冷、迟钝、易胖），低=甲亢 */
    val tsh: Double = 2.1,
    val ft3: Double = 4.6,
    val ft4: Double = 14.0,
)

@kotlinx.serialization.Serializable
data class Ecg(
    /** 心率 */
    val rate: Int = 68,
    /** 窦性心律 */
    val sinusRhythm: Boolean = true,
    /** 有无 ST-T 改变、传导阻滞等 */
    val abnormal: Boolean = false,
)

// ═══════════════════════════════════════════════════════════════
//  五、影像与营养
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class ImagingFindings(
    /** 腹部超声结论，如「脂肪肝（轻度）」 */
    val findings: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
data class NutritionPanel(
    /** 铁蛋白 ng/mL。低 ⇒ 易累、怕冷 */
    val ferritin: Double = 55.0,
    /** 维生素D ng/mL */
    val vitaminD: Double = 32.0,
    /** 叶酸 */
    val folate: Double = 12.0,
    /** 同型半胱氨酸 */
    val homocysteine: Double = 9.0,
    /** 骨密度 T 值。低于 -1 偏低，低于 -2.5 骨质疏松 */
    val boneTScore: Double = 0.2,
)

/**
 * 女性专项。
 *
 * ⚠️ 与 [Cycle] 联动：这里填的周期参数会被 `Cycle` 读取。
 *    **不是所有人都有这一节** —— 传 null 表示没有。
 */
@kotlinx.serialization.Serializable
data class FemalePanel(
    val cycleLengthDays: Int = 28,
    val periodLengthDays: Int = 5,
    /** 经期症状描述（如实填写，不是医疗论断） */
    val periodSymptoms: List<String> = listOf("腰酸", "容易累"),
    /** 乳腺超声结论 */
    val breastNote: String = "",
    /** 妇科超声结论 */
    val gynNote: String = "",
)

// ═══════════════════════════════════════════════════════════════
//  六、生活方式（体检报告固定有一节）
// ═══════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class Lifestyle(
    /** 平均睡眠小时 */
    val sleepHours: Double = 7.0,
    /** 睡眠型 */
    val sleepType: SleepType = SleepType.NORMAL,
    /** 每周运动次数 */
    val exercisePerWeek: Int = 2,
    /** 每天咖啡几杯 */
    val caffeineCupsPerDay: Int = 1,
    /** 每周饮酒次数 */
    val alcoholPerWeek: Int = 0,
    val smokes: Boolean = false,
)

enum class SleepType { LARK, NORMAL, OWL }

// ═══════════════════════════════════════════════════════════════
//  ★ 七、体检报告**覆盖不到**的部分
// ═══════════════════════════════════════════════════════════════

/**
 * **体检报告查不出来的那些事**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有这一节
 * ═══════════════════════════════════════════════════════════════
 *
 * 体检报告能告诉你她血红蛋白多少，但告诉不了你：
 * 她冬天会把手塞进你口袋里、她打针会晕、她被碰到腰会笑。
 *
 * ⚠️ 而这些**恰恰是最像人的部分**。一份全是化验单的角色设定，
 *    读起来像病历，不像一个人。
 */
@kotlinx.serialization.Serializable
data class BeyondReport(
    /** 痛阈 0..100。低 = 一点疼就皱眉 */
    val painTolerance: Int = 50,
    /** 亲密需求 0..100。影响她多主动靠近 */
    val intimacyNeed: Int = 50,
    /** 焦虑倾向 0..100。高 ⇒ 皮质醇更敏感、恢复更慢 */
    val anxietyTendency: Int = 40,
    /** 体温偏好：怕冷 / 怕热 / 无所谓 */
    val temperaturePreference: TemperaturePreference = TemperaturePreference.NORMAL,
    /** 晕针晕血 */
    val needleFaintness: Boolean = false,
    /** 过敏（花粉、芒果、酒精…） */
    val allergies: List<String> = emptyList(),
    /** 旧伤（"左脚踝崴过，阴天会酸"） */
    val oldInjuries: List<String> = emptyList(),
    /** 长期在吃的药 */
    val medications: List<String> = emptyList(),
    /** 声音特征（"说话偏慢，尾音会拖"） */
    val voiceNote: String = "",
    /** 身体小动作（咬指甲、抱胳膊、摸耳垂） */
    val bodyHabits: List<String> = emptyList(),
    /** 敏感的地方（角色扮演用，可空） */
    val sensitiveSpots: List<String> = emptyList(),
    /** 体味 / 常用香（"很淡的皂香"） */
    val scentNote: String = "",
    /** 空腹会怎样（"血糖低就手抖、脾气差"） —— 报告查不出，但很真实 */
    val hungerNote: String = "",
)

enum class TemperaturePreference { COLD_BLOODED, NORMAL, HEAT_BLOODED }

// ═══════════════════════════════════════════════════════════════
//  八、推导结果（引擎读的就是这个）
// ═══════════════════════════════════════════════════════════════

/**
 * 从体检报告推导出的**引擎参数**。
 *
 * ⚠️ 与 `HealthReport` 分开：报告是**人填的原始数据**，
 *    这个是**代码消费的结论**。混在一起会让"改一个化验值"
 *    直接影响行为，而中间没有一处可测试的转换。
 */
data class BodyProfile(
    val fatigueThreshold: FatigueThreshold = FatigueThreshold.NORMAL,
    val recoverRate: RecoverRate = RecoverRate.NORMAL,
    val coldSensitivity: ColdSensitivity = ColdSensitivity.NORMAL,
    /** 昼夜节律整体后移多少小时（夜猫子 +2.5） */
    val circadianShiftHours: Double = 0.0,
    val stressSensitivity: StressSensitivity = StressSensitivity.NORMAL,
    val painTolerance: Int = 50,
    val intimacyNeed: Int = 50,
    val triggers: Set<PhysioTrigger> = emptySet(),
) {

    /** 疲劳阈值对精力消耗的倍率。 */
    val fatigueMultiplier: Double
        get() = when (fatigueThreshold) {
            FatigueThreshold.LOW -> 1.5
            FatigueThreshold.NORMAL -> 1.0
        }

    /** 恢复速率倍率。 */
    val recoverMultiplier: Double
        get() = when (recoverRate) {
            RecoverRate.SLOW -> 0.6
            RecoverRate.NORMAL -> 1.0
            RecoverRate.FAST -> 1.4
        }

    /** 皮质醇增益倍率。 */
    val stressMultiplier: Double
        get() = when (stressSensitivity) {
            StressSensitivity.HIGH -> 1.4
            StressSensitivity.NORMAL -> 1.0
            StressSensitivity.LOW -> 0.8
        }

    companion object {
        /** 什么都没填时的默认（健康成年人）。 */
        val Default = BodyProfile()
    }
}

enum class FatigueThreshold { LOW, NORMAL }
enum class RecoverRate { SLOW, NORMAL, FAST }
enum class ColdSensitivity { HIGH, NORMAL, LOW }
enum class StressSensitivity { HIGH, NORMAL, LOW }

/** 需要**偶尔被触发**的生理事件来源。 */
enum class PhysioTrigger(val label: String) {
    /** 过敏（换季、花粉、特定食物） */
    ALLERGY("过敏"),
    /** 旧伤（阴天、久坐、提重物） */
    OLD_INJURY("旧伤"),
    /** 晕针晕血 */
    NEEDLE("晕针"),
    /** 咖啡因（喝多了心悸、不喝头痛） */
    CAFFEINE("咖啡因"),
    /** 酒精（脸红、上头快） */
    ALCOHOL("酒精"),
    /** 维生素D不足（情绪低、容易累） */
    LOW_VITAMIN_D("维生素D不足"),
}
