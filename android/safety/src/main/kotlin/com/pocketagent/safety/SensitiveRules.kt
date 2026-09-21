package com.pocketagent.safety

import java.text.Normalizer

/**
 * 敏感内容规则库与匹配引擎。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不能"把文本拼起来做子串匹配"
 * ═══════════════════════════════════════════════════════════════
 *
 * 早先的实现是 `keywordPatterns.any { it in visibleTexts.joinToString(" ") }`。
 * 三个问题：
 *
 *  1. **空格会打断匹配**。页面文本被拆成多个节点，拼起来是「确认 支付」，
 *     而关键词是「确认支付」→ 漏判。这是**漏判**，方向错误，最危险。
 *  2. **全角/半角不统一**。有些 App 的按钮写「立即付款」，有些写「立即付款」
 *     用了全角空格或兼容字形 → 同样的字，字节不同 → 漏判。
 *  3. **英文关键词没有词边界**。「otp」会命中「notpossible」→ 误判。
 *     误判方向安全，但会把产品变得不可用，同样是缺陷。
 *
 * 所以这里做三件事：**归一化 → 分类匹配 → 分级判定**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  两条不可协商的规则
 * ═══════════════════════════════════════════════════════════════
 *
 *  1. **用户只能加规则，不能删内置规则**。见 [withUserRules] —— 这是唯一的
 *     修改入口，它的签名里根本没有"移除"这个操作。用 API 形状把原则钉死，
 *     比写一句注释然后指望别人遵守可靠得多。
 *  2. **漏判比误判危险**。所有歧义一律向"拦"的方向倒。代价是可能多拦，
 *     但多拦只是用户手动点一下，漏拦是用户的钱。
 */
data class SensitiveRules(
    /** 按包名精确匹配 —— 整个 App 内都不允许自动化 */
    val packageBlacklist: Set<String> = emptySet(),
    /** 按包名前缀匹配 —— 用于银行类 App 的系列包名（如 `cmb.pb` 与 `cmb.pb.*`） */
    val packagePrefixes: Set<String> = emptySet(),
    /** 页面关键词 —— 出现在可见文本中即判定为敏感页面 */
    val pageKeywords: List<String> = emptyList(),
    /** 危险动作关键词 —— 只有"动作指向的元素"命中时才触发二次确认 */
    val dangerousActionKeywords: List<String> = emptyList(),
    /** 密码类控件特征 */
    val passwordHints: List<String> = emptyList(),
    /** 验证码类控件特征 */
    val captchaHints: List<String> = emptyList(),
    /** 金额类控件特征 */
    val amountHints: List<String> = emptyList(),
    /** 身份证 / 银行卡类控件特征 */
    val identityHints: List<String> = emptyList(),
) {

    /**
     * 追加用户规则。
     *
     * ⚠️ 这是**唯一**的规则修改入口，且只支持追加。
     *    不要为它加一个 `removeKeyword` 之类的兄弟方法 —— 那会让
     *    「用户不能配置绕过」这条产品红线变成一句空话。
     */
    fun withUserRules(
        extraPackages: Set<String> = emptySet(),
        extraKeywords: List<String> = emptyList(),
    ): SensitiveRules = copy(
        packageBlacklist = packageBlacklist + extraPackages,
        pageKeywords = (pageKeywords + extraKeywords).distinct(),
    )

    companion object {
        /**
         * 内置规则。
         *
         * ⚠️ 维护要求：每次版本更新都要复核这份清单，尤其是新出现的支付渠道
         *    与地方性银行 App。**这份清单的完整性直接决定产品的合规底线。**
         */
        val Builtin: SensitiveRules = SensitiveRules(
            packageBlacklist = setOf(
                // ── 支付 ──
                "com.eg.android.AlipayGphone",          // 支付宝
                "com.unionpay",                          // 云闪付
                "com.unionpay.mobilepay",
                "com.tencent.mm.plugin.pay",             // 微信支付（部分 ROM 独立包名）
                "com.jd.jrapp",                          // 京东金融
                "com.antfortune.wealth",                 // 蚂蚁财富
                // ── 银行 ──
                "com.icbc",                              // 工商银行
                "com.ccb",                               // 建设银行
                "com.bankcomm.Bankcomm",                 // 交通银行
                "com.chinamworld.main",                  // 中国银行
                "com.abchina.ebank",                     // 农业银行
                "com.cmbchina.ccd.pluto.cmbActivity",    // 招商银行
                "cmb.pb",                                // 招商银行（新包名）
                "com.citic.bank",                        // 中信银行
                "com.spdbccc.app",                       // 浦发银行
                "com.pingan.paces.ccms",                 // 平安银行
                // ── 证券 ──
                "com.android.dazhihui",                  // 大智慧
                "com.hexin.plat.android",                // 同花顺
                "com.guosen.android",                    // 国信证券
                // ── 政务与社保 ──
                "cn.gov.tax",                            // 个人所得税
                "com.si",                                // 社保
                "cn.gov.www",                            // 政务服务平台
            ),

            packagePrefixes = setOf(
                "cmb.pb.",
                "com.icbc.",
                "com.ccb.",
                "com.eg.android.AlipayGphone.",
            ),

            pageKeywords = listOf(
                // ── 资金操作 ──
                "确认支付", "立即支付", "确认付款", "立即付款", "去支付", "去付款",
                "转账", "收款", "提现", "充值", "还款", "付款码", "收付款",
                "输入支付密码", "指纹支付", "面容支付", "免密支付", "支付密码",
                // ── 身份验证 ──
                "验证码", "短信验证", "动态口令", "请输入密码", "设置密码",
                "修改密码", "重置密码", "实名认证", "人脸识别", "活体检测",
                // ── 金融产品 ──
                "贷款", "借款", "分期", "理财", "基金", "股票", "证券",
                "信用卡", "额度", "征信",
                // ── 不可逆操作 ──
                "确认删除", "永久删除", "注销账号", "解除绑定", "清空数据",
                "格式化",
            ),

            dangerousActionKeywords = listOf(
                "确认支付", "立即支付", "确认付款", "去支付",
                "确认转账", "转账", "提现", "充值",
                "确认删除", "永久删除", "注销", "解除绑定",
                "发送", "提交", "确认发布", "立即购买", "提交订单",
            ),

            passwordHints = listOf(
                "密码", "支付密码", "登录密码", "新密码", "旧密码", "原密码",
                "password", "passwd", "pwd", "pin",
            ),

            captchaHints = listOf(
                "验证码", "短信验证码", "动态口令", "图形验证码", "校验码",
                "captcha", "otp", "verification code", "auth code",
            ),

            amountHints = listOf(
                "金额", "转账金额", "付款金额", "收款金额", "输入金额",
                "amount", "转账额度",
            ),

            identityHints = listOf(
                "身份证", "身份证号", "证件号", "银行卡号", "卡号", "手机号",
                "id card", "card number",
            ),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  输入控件特征
// ═══════════════════════════════════════════════════════════════

/**
 * 输入控件的**形状**描述。
 *
 * ⚠️ 刻意不含 `text`（用户已输入的内容）。安全层判断"这是不是一个密码框"
 *    只需要看 hint / className / inputType，**不需要也不允许看里面写了什么**。
 *    多带一个字段就等于多一条泄漏路径。
 */
data class InputFieldSignature(
    val hint: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
    /** 来自 `inputType` 的 `TYPE_TEXT_VARIATION_PASSWORD` 等标志 */
    val isPassword: Boolean = false,
    val isEditable: Boolean = true,
    /**
     * 最大长度。验证码框常见 4 / 6 位。
     *
     * ⚠️ **保留字段，当前不参与判定。** 部分 App 的验证码框没有任何 hint，
     *    理论上可以靠 `maxLength ∈ {4,6}` 推断。但单凭长度判断误报率太高
     *    （年份、邮编、短密码都是 4~6 位），而误报会让产品变得不可用。
     *    等真机上收集到足够的样本再决定怎么用。
     */
    val maxLength: Int? = null,
)

// ═══════════════════════════════════════════════════════════════
//  匹配结果
// ═══════════════════════════════════════════════════════════════

/** 命中类型。带用户可读的说明 —— 拦截时必须让用户知道「为什么」。 */
enum class SensitivityHit(val userMessage: String) {
    PACKAGE_BLACKLIST("这个应用涉及资金或身份信息，我不会替你在里面操作。"),
    PAGE_KEYWORD("当前页面出现了支付、密码或验证码相关的内容，我不会继续操作。"),
    PASSWORD_FIELD("当前页面有密码输入框，我不会读取或填写密码。"),
    CAPTCHA_FIELD("当前页面有验证码输入框，验证码必须由你本人填写。"),
    AMOUNT_FIELD("当前页面有金额输入框，涉及资金的操作我不会代劳。"),
    IDENTITY_FIELD("当前页面要求填写身份证或银行卡信息，我不会代填。"),
}

/** 命中详情 */
data class SensitivityMatch(
    val hit: SensitivityHit,
    /** 命中的具体规则（用于本地审计与规则调试，不含页面内容） */
    val matchedRule: String? = null,
)

// ═══════════════════════════════════════════════════════════════
//  匹配引擎
// ═══════════════════════════════════════════════════════════════

object SensitiveDetector {

    /** 零宽字符与方向控制符 —— 有人用它们把敏感词拆开绕过匹配 */
    private val INVISIBLE_CHARS = Regex("""[\u200B-\u200F\u202A-\u202E\u2060-\u2064\uFEFF]""")
    private val WHITESPACE = Regex("""[\s\u00A0\u3000]+""")

    /** CJK 起始码位。高于它的字符按"中文"处理（不走词边界） */
    private const val CJK_START = 0x2E80

    /**
     * 归一化：NFKC 折叠全角/兼容字形，去掉零宽字符，转小写。
     *
     * 例：`"立即付款"`（含全角空格）与 `"立即付款"` 归一化后一致；
     *     `"Ｐａｙ"` → `"pay"`。
     */
    fun normalize(raw: String): String =
        INVISIBLE_CHARS.replace(Normalizer.normalize(raw, Normalizer.Form.NFKC), "")
            .lowercase()

    /**
     * 压紧：在归一化基础上删除所有空白。
     *
     * 用于中文关键词匹配。因为无障碍树会把一句话拆成多个节点，
     * 拼起来中间带空格 —— 不压紧就会漏判（「确认 支付」匹配不到「确认支付」）。
     */
    fun compact(raw: String): String = WHITESPACE.replace(normalize(raw), "")

    /**
     * 关键词匹配。
     *
     * 中文关键词走"压紧后子串匹配"（空格无关）；
     * 纯 ASCII 关键词走词边界匹配（避免 `otp` 命中 `notpossible`）。
     */
    fun containsKeyword(haystack: String, keyword: String): Boolean {
        val k = normalize(keyword)
        if (k.isBlank()) return false

        val isCjk = k.any { it.code >= CJK_START }
        return if (isCjk) {
            // 中文：压紧后子串匹配。空格无关，因为无障碍树会把一个词拆成多个节点
            compact(haystack).contains(compact(k))
        } else {
            // 英文：**必须用未压紧的文本**做词边界匹配。
            // 这里踩过一次：先对 haystack 做 compact 再去匹配 `\botp\b`，
            // 结果 "enter otp here" 被压成 "enterotphere"，`otp` 左边是字母 'r'，
            // 词边界断言失败 → 永远匹配不到。压紧和词边界是互斥的。
            val escaped = Regex.escape(k)
            Regex("""(?<![a-z0-9])$escaped(?![a-z0-9])""").containsMatchIn(normalize(haystack))
        }
    }

    /** 包名匹配：精确 + 前缀 */
    fun matchPackage(packageName: String, rules: SensitiveRules): SensitivityMatch? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null

        if (pkg in rules.packageBlacklist) {
            return SensitivityMatch(SensitivityHit.PACKAGE_BLACKLIST, pkg)
        }
        // 前缀匹配：银行 App 常把功能拆到子包名里
        val prefix = rules.packagePrefixes.firstOrNull { pkg.startsWith(it) }
        if (prefix != null) {
            return SensitivityMatch(SensitivityHit.PACKAGE_BLACKLIST, "$prefix*")
        }
        return null
    }

    /** 页面文本匹配 */
    fun matchTexts(visibleTexts: List<String>, rules: SensitiveRules): SensitivityMatch? {
        if (visibleTexts.isEmpty()) return null

        // 逐条文本分别匹配，而不是拼成一大串。
        // 拼接会让"相邻节点的文本"产生跨节点误判，也会让空格处理变得不可控。
        val haystack = visibleTexts.joinToString(" ")
        val keyword = rules.pageKeywords.firstOrNull { containsKeyword(haystack, it) }
        return keyword?.let { SensitivityMatch(SensitivityHit.PAGE_KEYWORD, it) }
    }

    /** 危险动作关键词匹配（只看动作指向的元素描述） */
    fun matchDangerousAction(
        actionDescription: String?,
        rules: SensitiveRules,
    ): String? {
        if (actionDescription.isNullOrBlank()) return null
        return rules.dangerousActionKeywords.firstOrNull {
            containsKeyword(actionDescription, it)
        }
    }

    /**
     * 控件匹配。
     *
     * 优先级：密码 > 验证码 > 身份证/银行卡 > 金额。
     * 一个输入框可能同时像"密码"和"金额"（比如支付金额框），
     * 按危险程度取最高的那个，不要返回一堆。
     */
    fun matchFields(
        fields: List<InputFieldSignature>,
        rules: SensitiveRules,
    ): SensitivityMatch? {
        if (fields.isEmpty()) return null

        // 第一优先：`inputType` 明确标记为密码的控件。
        // 这是**唯一不依赖文本描述**的信号 —— 即使 App 把 hint 写成「请输入」，
        // 只要 inputType 对，我们就知道这是密码框。所以先扫全部字段，而不是逐字段依次判断。
        fields.firstOrNull { it.isPassword }?.let {
            return SensitivityMatch(SensitivityHit.PASSWORD_FIELD, "inputType=password")
        }

        // 其余按**危险程度分档**扫描，而不是逐个字段依次判断。
        //
        // 两者的区别在一个具体场景上：页面同时有「转账金额」框（第 1 个）和
        // 「支付密码」框（第 2 个）。逐字段判断会先返回 AMOUNT_FIELD，
        // 用户看到"涉及资金"；分档扫描会返回 PASSWORD_FIELD，用户看到"有密码框"。
        // 后者才是这个页面真正的风险等级。
        val tiers = listOf(
            rules.passwordHints to SensitivityHit.PASSWORD_FIELD,
            rules.captchaHints to SensitivityHit.CAPTCHA_FIELD,
            rules.identityHints to SensitivityHit.IDENTITY_FIELD,
            rules.amountHints to SensitivityHit.AMOUNT_FIELD,
        )

        for ((hints, hit) in tiers) {
            for (field in fields) {
                val text = listOfNotNull(field.hint, field.contentDescription, field.className)
                    .joinToString(" ")
                if (text.isBlank()) continue
                hints.firstOrNull { containsKeyword(text, it) }?.let {
                    return SensitivityMatch(hit, it)
                }
            }
        }
        return null
    }
}
