package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **体检报告 → 行为参数**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"报告变成装饰"
 * ═══════════════════════════════════════════════════════════════
 *
 * 一份设定如果只是被塞进提示词当描述，那用户在报告里写"贫血"
 * 和写"健康"对他的体感没有区别 —— 那是装饰。
 *
 * ⇒ 每一条异常项都必须**改变一个倍率或阈值**，而这里逐条钉住。
 */
class HealthReportTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 默认是健康成年人
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `什么都不填时是正常人`() {
        val p = HealthReport().derive()
        assertEquals(FatigueThreshold.NORMAL, p.fatigueThreshold)
        assertEquals(RecoverRate.NORMAL, p.recoverRate)
        assertEquals(ColdSensitivity.NORMAL, p.coldSensitivity)
        assertEquals(StressSensitivity.NORMAL, p.stressSensitivity)
        assertEquals(0.0, p.circadianShiftHours, 1e-9)
        assertTrue("默认不该有任何异常触发源", p.triggers.isEmpty())
    }

    @Test
    fun `全绿的报告不该写出任何异常`() {
        // 报告的价值在异常项 —— 全绿等于没写
        assertEquals("", HealthReport().abnormalNote())
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 贫血：最常见的异常，也最影响体感
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `贫血会让她更容易累 恢复更慢 更怕冷`() {
        // ══════════════════════════════════════════════════════
        //  ★ 一条化验值必须同时改三件事，否则"贫血"只是四个字
        // ══════════════════════════════════════════════════════
        val anemic = HealthReport(blood = BloodPanel(hb = 102.0)).derive()

        assertEquals(FatigueThreshold.LOW, anemic.fatigueThreshold)
        assertEquals(RecoverRate.SLOW, anemic.recoverRate)
        assertEquals(ColdSensitivity.HIGH, anemic.coldSensitivity)
        assertTrue("疲劳倍率应当更高：${anemic.fatigueMultiplier}", anemic.fatigueMultiplier > 1.0)
        assertTrue("恢复倍率应当更低：${anemic.recoverMultiplier}", anemic.recoverMultiplier < 1.0)
    }

    @Test
    fun `贫血会出现在标红清单里`() {
        val note = HealthReport(blood = BloodPanel(hb = 102.0)).abnormalNote()
        assertTrue("应当提到血红蛋白：$note", note.contains("血红蛋白"))
    }

    @Test
    fun `铁蛋白低也会累`() {
        // 血红蛋白正常但铁储备见底 —— 体检常见，且真的很累
        val p = HealthReport(
            nutrition = NutritionPanel(ferritin = 12.0),
        ).derive()
        assertEquals(FatigueThreshold.LOW, p.fatigueThreshold)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 甲状腺：影响代谢与温度
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `甲减会恢复慢`() {
        val p = HealthReport(thyroid = ThyroidPanel(tsh = 6.5)).derive()
        assertEquals(RecoverRate.SLOW, p.recoverRate)
    }

    @Test
    fun `甲亢会出现在标红清单`() {
        val note = HealthReport(thyroid = ThyroidPanel(tsh = 0.2)).abnormalNote()
        assertTrue("应当提到甲状腺：$note", note.contains("甲状腺"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 昼夜型：夜猫子不是"晚睡"，是整个节律后移
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `夜猫子的节律整体后移`() {
        val owl = HealthReport(lifestyle = Lifestyle(sleepType = SleepType.OWL)).derive()
        assertEquals(2.5, owl.circadianShiftHours, 1e-9)
    }

    @Test
    fun `早鸟的节律整体前移`() {
        val lark = HealthReport(lifestyle = Lifestyle(sleepType = SleepType.LARK)).derive()
        assertTrue("应当为负（前移）：${lark.circadianShiftHours}", lark.circadianShiftHours < 0)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 压力敏感
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `静息心率快会让她更怕压力`() {
        val p = HealthReport(vitals = Vitals(restingHeartRate = 92)).derive()
        assertEquals(StressSensitivity.HIGH, p.stressSensitivity)
        assertTrue("皮质醇倍率应当更高：${p.stressMultiplier}", p.stressMultiplier > 1.0)
    }

    @Test
    fun `焦虑倾向高也会让她更怕压力`() {
        val p = HealthReport(beyond = BeyondReport(anxietyTendency = 85)).derive()
        assertEquals(StressSensitivity.HIGH, p.stressSensitivity)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 体检报告覆盖不到的部分
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `痛阈与亲密需求会传下去`() {
        val p = HealthReport(
            beyond = BeyondReport(painTolerance = 20, intimacyNeed = 85),
        ).derive()
        assertEquals(20, p.painTolerance)
        assertEquals(85, p.intimacyNeed)
    }

    @Test
    fun `过敏与旧伤会变成触发源`() {
        val p = HealthReport(
            beyond = BeyondReport(
                allergies = listOf("花粉"),
                oldInjuries = listOf("左脚踝", "右肩"),
            ),
        ).derive()
        assertTrue(PhysioTrigger.ALLERGY in p.triggers)
        assertTrue(PhysioTrigger.OLD_INJURY in p.triggers)
    }

    @Test
    fun `晕针与咖啡因也会变成触发源`() {
        val p = HealthReport(
            beyond = BeyondReport(needleFaintness = true),
            lifestyle = Lifestyle(caffeineCupsPerDay = 5),
        ).derive()
        assertTrue(PhysioTrigger.NEEDLE in p.triggers)
        assertTrue(PhysioTrigger.CAFFEINE in p.triggers)
    }

    @Test
    fun `维生素D不足既影响精力也进入触发源`() {
        val p = HealthReport(nutrition = NutritionPanel(vitaminD = 12.0)).derive()
        assertEquals(FatigueThreshold.LOW, p.fatigueThreshold)
        assertTrue(PhysioTrigger.LOW_VITAMIN_D in p.triggers)
    }

    // ═══════════════════════════════════════════════════════════
    //  合理性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `BMI 算得对`() {
        val r = HealthReport(basic = BasicInfo(heightCm = 165, weightKg = 55.0))
        assertEquals(20.2, r.basic.bmi, 0.1)
    }

    @Test
    fun `运动多的人恢复快`() {
        val p = HealthReport(lifestyle = Lifestyle(exercisePerWeek = 5)).derive()
        assertEquals(RecoverRate.FAST, p.recoverRate)
    }

    @Test
    fun `睡眠不足会让疲劳阈值变低`() {
        val p = HealthReport(lifestyle = Lifestyle(sleepHours = 5.5)).derive()
        assertEquals(FatigueThreshold.LOW, p.fatigueThreshold)
    }

    @Test
    fun `标红清单会列出多条`() {
        val note = HealthReport(
            blood = BloodPanel(hb = 100.0),
            nutrition = NutritionPanel(ferritin = 10.0, vitaminD = 15.0),
            vitals = Vitals(systolic = 95),
        ).abnormalNote()
        assertTrue("应当提到血红蛋白：$note", note.contains("血红蛋白"))
        assertTrue("应当提到铁蛋白：$note", note.contains("铁蛋白"))
        assertTrue("应当提到维生素D：$note", note.contains("维生素D"))
        assertTrue("应当提到血压：$note", note.contains("血压"))
    }

    @Test
    fun `女性专项可以为空`() {
        // 不是所有角色都有这一节
        assertEquals(null, HealthReport().female)
    }
}
