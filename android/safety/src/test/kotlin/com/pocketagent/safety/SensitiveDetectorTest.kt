package com.pocketagent.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SensitiveDetector] 单元测试。
 *
 * 这个测试类守的是**漏判**方向 —— 每一个 assertFalse 背后都是一次真实事故。
 * 早先的实现有三个漏判通道（空格打断、全角不一致、英文无词边界），
 * 下面每一条都有对应的回归用例。
 */
class SensitiveDetectorTest {

    private val rules = SensitiveRules.Builtin

    // ═══════════════════════════════════════════════════════════
    //  归一化
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `NFKC 把全角字符折叠成半角`() {
        assertEquals("pay", SensitiveDetector.normalize("ＰＡＹ"))
        assertEquals("123", SensitiveDetector.normalize("１２３"))
    }

    @Test
    fun `零宽字符被剔除`() {
        // 有人用零宽字符把敏感词拆开，绕过朴素的子串匹配
        assertEquals("确认支付", SensitiveDetector.normalize("确认\u200B支付"))
        assertEquals("确认支付", SensitiveDetector.normalize("确\uFEFF认\u200D支付"))
    }

    @Test
    fun `compact 删除所有空白，包括全角空格与不换行空格`() {
        assertEquals("确认支付", SensitiveDetector.compact("确认 支付"))
        assertEquals("确认支付", SensitiveDetector.compact("确认　支付"))   // 全角空格
        assertEquals("确认支付", SensitiveDetector.compact("确认\u00A0支付")) // 不换行空格
        assertEquals("确认支付", SensitiveDetector.compact("  确认\n\t支付  "))
    }

    @Test
    fun `归一化统一转小写`() {
        assertEquals("password", SensitiveDetector.normalize("PassWord"))
    }

    // ═══════════════════════════════════════════════════════════
    //  关键词匹配 —— 漏判方向
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `中文关键词不受中间空格影响`() {
        // 回归用例：无障碍树把「确认支付」拆成两个节点，拼起来中间有空格。
        // 早先的子串匹配在这里漏判，这是最危险的一类。
        assertTrue(SensitiveDetector.containsKeyword("确认 支付", "确认支付"))
        assertTrue(SensitiveDetector.containsKeyword("请 确认 支付 订单", "确认支付"))
    }

    @Test
    fun `中文关键词不受全角空格影响`() {
        assertTrue(SensitiveDetector.containsKeyword("立即　付款", "立即付款"))
    }

    @Test
    fun `中文关键词不受零宽字符影响`() {
        assertTrue(SensitiveDetector.containsKeyword("确认\u200B支付", "确认支付"))
    }

    @Test
    fun `英文关键词必须有词边界`() {
        // 回归用例：`otp` 不能命中 `notpossible`。
        // 误判方向虽然安全，但会让产品变得不可用，同样是缺陷。
        assertTrue(SensitiveDetector.containsKeyword("enter otp here", "otp"))
        assertTrue(SensitiveDetector.containsKeyword("OTP", "otp"))
        assertFalse(SensitiveDetector.containsKeyword("notpossible", "otp"))
        assertFalse(SensitiveDetector.containsKeyword("stepbystep", "otp"))
    }

    @Test
    fun `英文关键词在压紧后依然能匹配`() {
        // 回归用例：曾经为了"空格无关"而对英文也做 compact，
        // 结果 "enter otp here" 压成 "enterotphere"，词边界断言失效，永远匹配不到。
        assertTrue(SensitiveDetector.containsKeyword("enter   otp   here", "otp"))
    }

    @Test
    fun `多词英文关键词能匹配`() {
        assertTrue(SensitiveDetector.containsKeyword("Enter Verification Code", "verification code"))
    }

    @Test
    fun `空关键词不匹配任何东西`() {
        assertFalse(SensitiveDetector.containsKeyword("任意文本", ""))
        assertFalse(SensitiveDetector.containsKeyword("任意文本", "   "))
    }

    // ═══════════════════════════════════════════════════════════
    //  包名匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `黑名单包名精确命中`() {
        val m = SensitiveDetector.matchPackage("com.eg.android.AlipayGphone", rules)

        assertNotNull(m)
        assertEquals(SensitivityHit.PACKAGE_BLACKLIST, m!!.hit)
    }

    @Test
    fun `子包名通过前缀命中`() {
        // 银行 App 常把功能拆到子包名里，精确匹配会漏
        val m = SensitiveDetector.matchPackage("cmb.pb.activity.main", rules)

        assertNotNull(m)
        assertEquals(SensitivityHit.PACKAGE_BLACKLIST, m!!.hit)
        assertTrue("应记录命中的前缀：${m.matchedRule}", m.matchedRule!!.endsWith("*"))
    }

    @Test
    fun `包名匹配区分大小写`() {
        // Android 包名是大小写敏感的，不能为了"宽容"而小写化，
        // 否则 com.ICBC 这种伪装包名会被误放行
        assertNull(SensitiveDetector.matchPackage("COM.EG.ANDROID.ALIPAYGPHONE", rules))
    }

    @Test
    fun `普通应用包名不命中`() {
        assertNull(SensitiveDetector.matchPackage("com.tencent.mm", rules))
        assertNull(SensitiveDetector.matchPackage("com.taobao.taobao", rules))
    }

    @Test
    fun `空包名不命中也不崩`() {
        assertNull(SensitiveDetector.matchPackage("", rules))
        assertNull(SensitiveDetector.matchPackage("   ", rules))
    }

    @Test
    fun `前缀匹配不会误伤同前缀的无关包名`() {
        // "com.si" 在精确黑名单里（社保），但它同时也是别的包名的前缀。
        // 这里确认我们没有把 "com.si" 放进前缀表 —— 否则 com.sina.weibo
        // 之类的应用会被整片误杀。
        assertFalse("com.si" in rules.packagePrefixes)
    }

    // ═══════════════════════════════════════════════════════════
    //  页面文本匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `页面出现支付关键词即命中`() {
        val m = SensitiveDetector.matchTexts(listOf("订单详情", "确认支付"), rules)

        assertNotNull(m)
        assertEquals(SensitivityHit.PAGE_KEYWORD, m!!.hit)
        assertEquals("确认支付", m.matchedRule)
    }

    @Test
    fun `被拆成多节点的关键词依然命中`() {
        val m = SensitiveDetector.matchTexts(listOf("确认", "支付"), rules)

        assertNotNull("拆分后必须依然命中", m)
        assertEquals(SensitivityHit.PAGE_KEYWORD, m!!.hit)
    }

    @Test
    fun `普通页面文本不命中`() {
        assertNull(
            SensitiveDetector.matchTexts(
                listOf("今日推荐", "附近的美食", "搜索你想吃的"),
                rules,
            )
        )
    }

    @Test
    fun `空文本列表不命中`() {
        assertNull(SensitiveDetector.matchTexts(emptyList(), rules))
        assertNull(SensitiveDetector.matchTexts(listOf("", "   "), rules))
    }

    @Test
    fun `验证码页面命中`() {
        assertNotNull(SensitiveDetector.matchTexts(listOf("请输入短信验证码"), rules))
    }

    // ═══════════════════════════════════════════════════════════
    //  危险动作匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `危险动作关键词命中`() {
        assertEquals("确认支付", SensitiveDetector.matchDangerousAction("底部的「确认支付」按钮", rules))
        assertEquals("发送", SensitiveDetector.matchDangerousAction("右上角 发送 按钮", rules))
    }

    @Test
    fun `普通动作不命中`() {
        assertNull(SensitiveDetector.matchDangerousAction("顶部的搜索框", rules))
        assertNull(SensitiveDetector.matchDangerousAction("返回按钮", rules))
    }

    @Test
    fun `空描述不命中也不崩`() {
        assertNull(SensitiveDetector.matchDangerousAction(null, rules))
        assertNull(SensitiveDetector.matchDangerousAction("", rules))
        assertNull(SensitiveDetector.matchDangerousAction("   ", rules))
    }

    // ═══════════════════════════════════════════════════════════
    //  控件匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `inputType 标记的密码框优先命中`() {
        // 这是唯一不依赖文本描述的信号 —— 即使 hint 写成「请输入」也能识别
        val m = SensitiveDetector.matchFields(
            listOf(InputFieldSignature(hint = "请输入", isPassword = true)),
            rules,
        )

        assertNotNull(m)
        assertEquals(SensitivityHit.PASSWORD_FIELD, m!!.hit)
    }

    @Test
    fun `密码框优先于金额框，与字段顺序无关`() {
        // 回归用例：早先逐字段判断，第 1 个字段是金额框就直接返回 AMOUNT_FIELD，
        // 用户看到"涉及资金"，而实际存在密码框这个更严重的信号。
        val fields = listOf(
            InputFieldSignature(hint = "转账金额"),
            InputFieldSignature(hint = "支付密码"),
        )

        val m = SensitiveDetector.matchFields(fields, rules)

        assertEquals(SensitivityHit.PASSWORD_FIELD, m?.hit)
    }

    @Test
    fun `密码框在最后也能被找到`() {
        val fields = listOf(
            InputFieldSignature(hint = "备注"),
            InputFieldSignature(hint = "收款人"),
            InputFieldSignature(hint = "登录密码"),
        )

        assertEquals(SensitivityHit.PASSWORD_FIELD, SensitiveDetector.matchFields(fields, rules)?.hit)
    }

    @Test
    fun `验证码框命中`() {
        val m = SensitiveDetector.matchFields(
            listOf(InputFieldSignature(hint = "短信验证码", maxLength = 6)),
            rules,
        )

        assertEquals(SensitivityHit.CAPTCHA_FIELD, m?.hit)
    }

    @Test
    fun `身份证与银行卡框命中`() {
        assertEquals(
            SensitivityHit.IDENTITY_FIELD,
            SensitiveDetector.matchFields(listOf(InputFieldSignature(hint = "身份证号")), rules)?.hit,
        )
        assertEquals(
            SensitivityHit.IDENTITY_FIELD,
            SensitiveDetector.matchFields(listOf(InputFieldSignature(hint = "银行卡号")), rules)?.hit,
        )
    }

    @Test
    fun `金额框命中`() {
        assertEquals(
            SensitivityHit.AMOUNT_FIELD,
            SensitiveDetector.matchFields(listOf(InputFieldSignature(hint = "转账金额")), rules)?.hit,
        )
    }

    @Test
    fun `验证码与身份证的档位顺序正确`() {
        // 验证码比身份证更该优先报出来（验证码是"正在被冒用"的直接信号）
        val fields = listOf(
            InputFieldSignature(hint = "身份证号"),
            InputFieldSignature(hint = "动态口令"),
        )

        assertEquals(SensitivityHit.CAPTCHA_FIELD, SensitiveDetector.matchFields(fields, rules)?.hit)
    }

    @Test
    fun `普通输入框不命中`() {
        assertNull(
            SensitiveDetector.matchFields(
                listOf(InputFieldSignature(hint = "搜索", className = "android.widget.EditText")),
                rules,
            )
        )
    }

    @Test
    fun `无 hint 的普通输入框不命中`() {
        assertNull(SensitiveDetector.matchFields(listOf(InputFieldSignature()), rules))
    }

    @Test
    fun `空字段列表不命中`() {
        assertNull(SensitiveDetector.matchFields(emptyList(), rules))
    }

    @Test
    fun `英文 hint 的密码框命中`() {
        assertEquals(
            SensitivityHit.PASSWORD_FIELD,
            SensitiveDetector.matchFields(listOf(InputFieldSignature(hint = "Password")), rules)?.hit,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  规则只能加不能减
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `用户规则只能追加，内置规则原样保留`() {
        val custom = rules.withUserRules(
            extraPackages = setOf("com.example.mybank"),
            extraKeywords = listOf("确认下单"),
        )

        // 新增的生效
        assertNotNull(SensitiveDetector.matchPackage("com.example.mybank", custom))
        assertNotNull(SensitiveDetector.matchTexts(listOf("确认下单"), custom))

        // 内置的一个都没少
        assertTrue(custom.packageBlacklist.containsAll(rules.packageBlacklist))
        assertTrue(custom.pageKeywords.containsAll(rules.pageKeywords))
        assertNotNull(SensitiveDetector.matchPackage("com.eg.android.AlipayGphone", custom))
        assertNotNull(SensitiveDetector.matchTexts(listOf("确认支付"), custom))
    }

    @Test
    fun `追加重复规则不会产生重复项`() {
        val once = rules.withUserRules(extraKeywords = listOf("确认下单"))
        val twice = once.withUserRules(extraKeywords = listOf("确认下单"))

        assertEquals(once.pageKeywords.size, twice.pageKeywords.size)
    }

    @Test
    fun `追加用户规则不会影响原规则对象`() {
        // SensitiveRules 是 data class，withUserRules 返回副本 —— 确认没有副作用
        val before = rules.pageKeywords.size
        rules.withUserRules(extraKeywords = listOf("随便什么"))

        assertEquals(before, rules.pageKeywords.size)
    }
}
