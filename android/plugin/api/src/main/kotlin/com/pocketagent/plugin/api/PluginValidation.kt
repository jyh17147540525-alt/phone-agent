package com.pocketagent.plugin.api

/**
 * 插件校验、信任分级与导入风险提示。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么校验必须发生在「反序列化之前」
 * ═══════════════════════════════════════════════════════════════
 *
 * [PluginManifest.capabilities] 的类型是 `List<PluginCapability>` —— 一个严格枚举。
 * 一个申请 `payment.pay` 的恶意插件，如果直接反序列化，得到的是
 * **一个看不懂的枚举解析错误**，而不是"它试图申请被禁止的能力"。
 *
 * 更糟的是：解析失败看起来像"插件格式不对"，用户会以为是插件作者的疏忽，
 * 于是换一个版本再试 —— 而实际上这是一次**明确的越权尝试**，应该被记录并拒绝。
 *
 * 所以流程必须是：
 * ```
 * 宽松解析原始 JSON
 *   → inspectRawCapabilities()   ← 在这里抓禁止前缀，能看到原始字符串
 *   → 结构性校验 validate()
 *   → 严格反序列化
 * ```
 * 先看，再判，最后才解析。
 *
 * ═══════════════════════════════════════════════════════════════
 *  校验的两条准则
 * ═══════════════════════════════════════════════════════════════
 *
 *  1. **ERROR 拦安装，WARNING 只提示。** 判据是"这个字段能不能被恶意利用"，
 *     而不是"这个字段规范不规范"。格式不规范的插件很多，全拦掉生态就死了。
 *  2. **过度申请权限是警告信号。** 一个 L1 规则包申请 `screen.capture`
 *     （它根本无法使用截图）不是"无害的冗余"，而是"它在为将来留后门"。
 *     这类情况必须显式提示用户。
 */
object PluginValidator {

    /** 官方保留的 ID 前缀 —— 防止第三方插件冒充官方 */
    private val RESERVED_ID_PREFIXES = listOf(
        "com.pocketagent.",
        "app.pocketagent.",
        "io.pocketagent.",
        "org.pocketagent.",
    )

    private val ID_SEGMENT = Regex("""^[a-z][a-z0-9-]*$""")
    private val PACKAGE_NAME = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")
    private val HOSTNAME = Regex("""^(\*\.)?[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?)+$""")
    private val SHA256_HEX = Regex("""^[0-9a-fA-F]{64}$""")

    private const val MAX_ID_LENGTH = 128
    private const val MAX_NAME_LENGTH = 60
    private const val MAX_DESCRIPTION_LENGTH = 500
    private const val MAX_CAPABILITIES = 12
    private const val MAX_TARGET_APPS = 50

    // ═══════════════════════════════════════════════════════════
    //  第一道：原始能力字符串检查（必须在反序列化之前跑）
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查插件**原始声明**的能力字符串。
     *
     * ⚠️ 这是唯一能捕获"申请禁止能力"的位置 —— 一旦进入严格反序列化，
     *    未知字符串就变成了解析错误，我们再也看不到它想申请什么。
     *
     * 返回的 issue 里两种严重程度对应两类**性质完全不同**的问题：
     *
     *  - **禁止前缀 → ERROR**：`payment.*` / `key.*` / `crypto.*` / `system.*`
     *    是明确的越权尝试，拦安装并记审计。
     *  - **未知能力 → WARNING**：可能是版本不匹配或作者拼写错误。条目保留、
     *    该能力丢弃，原始字符串留给界面提示。**不要把它升级成 ERROR** ——
     *    那样一个用了新能力的插件会把整个条目从市场里抹掉（踩过一次）。
     *
     * @param rawCapabilityIds 从 JSON 里读出的原始字符串列表
     */
    fun inspectRawCapabilities(rawCapabilityIds: List<String>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        for (raw in rawCapabilityIds) {
            val id = raw.trim().lowercase()

            val forbidden = PluginCapability.FORBIDDEN_PREFIXES.firstOrNull { id.startsWith(it) }
            if (forbidden != null) {
                issues += ValidationIssue(
                    severity = IssueSeverity.ERROR,
                    field = "capabilities",
                    message = "申请了永不开放的能力「$raw」（禁止前缀：$forbidden）。" +
                        "这类能力涉及资金、密钥与系统权限，本体不提供。",
                )
                continue
            }

            // ⚠️ 这里必须是 WARNING 而不是 ERROR —— 曾经写成 ERROR，被测试抓出来了。
            //
            // 判据回到本文件开头那条准则：ERROR 的标准是「这个字段能不能被恶意利用」。
            // 一个我们不认识的能力**无法被利用** —— PluginHost 根本不实现它，
            // 插件调用它只会失败。所以这是**兼容性问题，不是安全问题**。
            //
            // 判成 ERROR 的实际后果：某个插件用了一个新版本才有的能力，
            // 旧版本体就会把**整个条目**从市场里抹掉，用户连"有这么个插件"
            // 都看不到，还以为是市场没收录。正确做法是保留条目、丢掉这个能力，
            // 并把原始字符串留在 MarketEntry.rawCapabilityIds 里，由界面提示
            // 「它需要更新的本体」。过滤可以，但必须留痕。
            if (PluginCapability.fromId(id) == null) {
                issues += ValidationIssue(
                    severity = IssueSeverity.WARNING,
                    field = "capabilities",
                    message = "未知能力「$raw」，本版本不提供，已忽略。" +
                        "可能是插件针对更高版本的 API 编写，或作者拼写错误。",
                )
            }
        }

        return issues
    }

    // ═══════════════════════════════════════════════════════════
    //  第二道：结构性校验
    // ═══════════════════════════════════════════════════════════

    fun validate(manifest: PluginManifest): PluginValidationResult {
        val issues = buildList {
            addAll(validateIdentity(manifest))
            addAll(validateVersion(manifest))
            addAll(validateLevel(manifest))
            addAll(validateCapabilities(manifest))
            addAll(validateTargets(manifest))
            addAll(validateNetwork(manifest))
            addAll(validateEntry(manifest))
            addAll(validateIntegrity(manifest))
            addAll(validateSettings(manifest))
        }

        val errors = issues.filter { it.severity == IssueSeverity.ERROR }
        return if (errors.isEmpty()) {
            PluginValidationResult.Accepted(issues.filter { it.severity == IssueSeverity.WARNING })
        } else {
            PluginValidationResult.Rejected(errors)
        }
    }

    // ── ID 与名称 ────────────────────────────────────────────

    private fun validateIdentity(m: PluginManifest): List<ValidationIssue> = buildList {
        val id = m.id.trim()

        when {
            id.isEmpty() -> add(error("id", "插件 ID 不能为空"))
            id.length > MAX_ID_LENGTH -> add(error("id", "插件 ID 过长（${id.length} > $MAX_ID_LENGTH）"))
            id != m.id -> add(error("id", "插件 ID 首尾不能有空白字符"))
            else -> {
                val segments = id.split('.')
                if (segments.size < 3) {
                    add(error("id", "插件 ID 必须是反向域名形式（至少三段），如 community.wechat.auto-reply"))
                }
                segments.forEachIndexed { index, seg ->
                    if (!ID_SEGMENT.matches(seg)) {
                        add(
                            error(
                                "id",
                                "插件 ID 第 ${index + 1} 段「$seg」不合法：" +
                                    "只能用小写字母、数字与连字符，且必须以字母开头",
                            )
                        )
                    }
                }
            }
        }

        val reserved = RESERVED_ID_PREFIXES.firstOrNull { id.startsWith(it) }
        if (reserved != null) {
            add(
                error(
                    "id",
                    "「$reserved」是官方保留前缀，第三方插件不能使用。" +
                        "如果你确实在开发官方插件，请联系维护者。",
                )
            )
        }

        if (m.name.isBlank()) add(error("name", "插件名称不能为空"))
        if (m.name.length > MAX_NAME_LENGTH) add(error("name", "插件名称过长（上限 $MAX_NAME_LENGTH）"))
        if ((m.description?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
            add(error("description", "插件描述过长（上限 $MAX_DESCRIPTION_LENGTH）"))
        }

        // 描述缺失不拦，但提示 —— 没有描述的插件在市场上没人敢装
        if (m.description.isNullOrBlank()) {
            add(warning("description", "没有填写插件描述，用户在市场上无法判断这个插件做什么"))
        }
    }

    // ── 版本 ─────────────────────────────────────────────────

    private fun validateVersion(m: PluginManifest): List<ValidationIssue> = buildList {
        if (SemanticVersion.parse(m.version) == null) {
            add(error("version", "版本号「${m.version}」不符合语义化版本格式，应形如 1.0.0 或 1.0.0-beta.1"))
        }

        when {
            m.apiVersion <= 0 -> add(error("apiVersion", "API 版本必须为正整数"))
            m.apiVersion > PluginManifest.CURRENT_API_VERSION -> add(
                error(
                    "apiVersion",
                    "插件要求 API 版本 ${m.apiVersion}，高于本体支持的 " +
                        "${PluginManifest.CURRENT_API_VERSION}。请升级本应用后再安装。",
                )
            )
        }
    }

    // ── 级别 ─────────────────────────────────────────────────

    private fun validateLevel(m: PluginManifest): List<ValidationIssue> = buildList {
        if (m.level == PluginLevel.L3_NATIVE) {
            // L3 是"独立 APK + AIDL"，等于把任意代码加载进本进程。
            // 在签名校验与进程隔离方案成熟之前不开放 —— 这是产品决定，不是技术缺陷。
            add(
                error(
                    "level",
                    "L3 原生插件当前不开放。它要求加载独立 APK 并与本体跨进程通信，" +
                        "在签名校验机制成熟前，本体不会加载任何原生插件。",
                )
            )
        }
    }

    // ── 能力 ─────────────────────────────────────────────────

    private fun validateCapabilities(m: PluginManifest): List<ValidationIssue> = buildList {
        val caps = m.capabilities

        if (caps.size > MAX_CAPABILITIES) {
            add(error("capabilities", "申请的能力过多（${caps.size} > $MAX_CAPABILITIES）"))
        }
        if (caps.size != caps.distinct().size) {
            add(error("capabilities", "能力列表有重复项"))
        }

        // 过度申请：L1 规则包用不到这些能力，声明了就是冗余（或别有用心）
        if (m.level == PluginLevel.L1_RULES) {
            val unusable = caps.filter { it in L1_UNUSABLE_CAPABILITIES }
            if (unusable.isNotEmpty()) {
                add(
                    warning(
                        "capabilities",
                        "L1 规则包声明了它无法使用的能力：" +
                            unusable.joinToString("、") { it.id } +
                            "。L1 只能做声明式匹配与动作，用不到这些 —— 请确认这不是在为后续版本预留权限。",
                    )
                )
            }
        }

        // 需要模型调用却没有描述，用户不知道会不会烧钱
        if (PluginCapability.LLM_CALL in caps && m.description.isNullOrBlank()) {
            add(warning("description", "插件会调用你的 AI 模型（消耗额度），但没有说明用途"))
        }
    }

    // ── 目标应用 ─────────────────────────────────────────────

    private fun validateTargets(m: PluginManifest): List<ValidationIssue> = buildList {
        if (m.targetApps.size > MAX_TARGET_APPS) {
            add(error("targetApps", "目标应用过多（${m.targetApps.size} > $MAX_TARGET_APPS）"))
        }
        m.targetApps.forEach { pkg ->
            if (!PACKAGE_NAME.matches(pkg)) {
                add(error("targetApps", "「$pkg」不是合法的应用包名"))
            }
        }

        // 会操作屏幕却不限定目标应用 —— 意味着它可以对任何 App 生效
        val canAct = m.capabilities.any {
            it == PluginCapability.ACTION_CLICK ||
                it == PluginCapability.ACTION_INPUT ||
                it == PluginCapability.ACTION_GESTURE
        }
        if (canAct && m.targetApps.isEmpty()) {
            add(
                warning(
                    "targetApps",
                    "插件可以模拟点击/输入，但没有限定目标应用 —— 意味着它对你手机上的" +
                        "任何应用都能生效。除非确实需要，建议限定具体应用。",
                )
            )
        }
    }

    // ── 网络 ─────────────────────────────────────────────────

    private fun validateNetwork(m: PluginManifest): List<ValidationIssue> = buildList {
        val needsNetwork = PluginCapability.NETWORK_REQUEST in m.capabilities

        if (needsNetwork && m.allowedHosts.isEmpty()) {
            add(
                error(
                    "allowedHosts",
                    "声明了 network.request 能力，但 allowedHosts 为空。" +
                        "网络能力必须限定域名，不允许插件访问任意地址。",
                )
            )
        }
        if (!needsNetwork && m.allowedHosts.isNotEmpty()) {
            add(warning("allowedHosts", "填写了 allowedHosts 但没有申请 network.request 能力，该配置不会生效"))
        }

        m.allowedHosts.forEach { host ->
            when {
                host.isBlank() -> add(error("allowedHosts", "域名白名单里有空项"))
                host.contains("://") -> add(error("allowedHosts", "「$host」不应包含协议前缀，只写域名"))
                host.contains('/') -> add(error("allowedHosts", "「$host」不应包含路径，只写域名"))
                host == "*" -> add(
                    error(
                        "allowedHosts",
                        "不允许通配所有域名。网络能力必须明确列出要访问的域名。",
                    )
                )
                !HOSTNAME.matches(host) -> add(error("allowedHosts", "「$host」不是合法的域名"))
            }
        }
    }

    // ── 入口文件 ─────────────────────────────────────────────

    private fun validateEntry(m: PluginManifest): List<ValidationIssue> = buildList {
        val entry = m.entry

        if (entry.isNullOrBlank()) {
            // L1/L2 都必须有入口文件
            add(error("entry", "缺少入口文件路径"))
            return@buildList
        }

        // ⚠️ 路径穿越是插件最常见的越权手法：
        //    入口写成 "../../../data/data/包名/databases/x" 就能读到别的插件甚至本体的数据
        if (entry.startsWith('/') || entry.startsWith('\\')) {
            add(error("entry", "入口路径必须是相对路径，不能以斜杠开头"))
        }
        if (entry.contains("..")) {
            add(error("entry", "入口路径不能包含「..」，这会被用来读取插件目录之外的文件"))
        }
        if (entry.contains('\\')) {
            add(error("entry", "入口路径必须用正斜杠 / 分隔"))
        }
        if (entry.contains('\u0000')) {
            add(error("entry", "入口路径包含非法字符"))
        }

        val expectedExt = when (m.level) {
            PluginLevel.L1_RULES -> ".json"
            PluginLevel.L2_SCRIPT -> ".js"
            PluginLevel.L3_NATIVE -> null
        }
        if (expectedExt != null && !entry.lowercase().endsWith(expectedExt)) {
            add(error("entry", "${m.level} 的入口文件应以 $expectedExt 结尾，当前是「$entry」"))
        }
    }

    // ── 完整性 ───────────────────────────────────────────────

    private fun validateIntegrity(m: PluginManifest): List<ValidationIssue> = buildList {
        val hash = m.sha256
        if (hash != null && !SHA256_HEX.matches(hash)) {
            add(error("sha256", "sha256 必须是 64 位十六进制字符串，当前长度 ${hash.length}"))
        }
        if (hash == null) {
            add(
                warning(
                    "sha256",
                    "插件没有提供内容哈希，安装时无法校验文件是否被篡改。" +
                        "本地导入的插件不受此影响（风险由你自己承担），但市场插件应当提供。",
                )
            )
        }
    }

    // ── 配置界面 ─────────────────────────────────────────────

    private fun validateSettings(m: PluginManifest): List<ValidationIssue> = buildList {
        val keys = m.settingsSchema.map { it.key }
        if (keys.size != keys.distinct().size) {
            add(error("settingsSchema", "配置项 key 有重复"))
        }
        keys.forEach { key ->
            if (key.isBlank()) add(error("settingsSchema", "配置项 key 不能为空"))
            if (key.length > 64) add(error("settingsSchema", "配置项 key 过长：$key"))
        }
        m.settingsSchema.forEach { field ->
            if (field.label.isBlank()) {
                add(warning("settingsSchema", "配置项「${field.key}」没有标签，用户看不懂这一项是干什么的"))
            }
            if (field is SettingField.Select) {
                if (field.options.isEmpty()) {
                    add(error("settingsSchema", "下拉配置项「${field.key}」没有可选值"))
                } else if (field.defaultIndex !in field.options.indices) {
                    add(error("settingsSchema", "下拉配置项「${field.key}」的默认值下标越界"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  风险汇总
    // ═══════════════════════════════════════════════════════════

    /**
     * 综合风险等级 —— 取所有已声明能力中的最高值。
     *
     * 用于：安装确认页的配色、市场上插件卡片的角标、以及"是否要求输入确认词"的判定。
     */
    fun summarizeRisk(capabilities: List<PluginCapability>): PluginCapability.RiskLevel? =
        capabilities.maxByOrNull { it.risk.ordinal }?.risk

    /** L1 规则包在结构上无法使用的能力。声明了它们说明在过度申请权限 */
    private val L1_UNUSABLE_CAPABILITIES = setOf(
        PluginCapability.SCREEN_CAPTURE,
        PluginCapability.NOTIFICATION_READ,
        PluginCapability.CLIPBOARD_READ,
        PluginCapability.CLIPBOARD_WRITE,
        PluginCapability.STORAGE,
        PluginCapability.NETWORK_REQUEST,
        // 文件能力同样用不到：L1 是声明式规则包，它的动作只有点击/输入/滚动那一组，
        // 没有任何一步能拿文件内容做判断。声明了就是冗余。
        PluginCapability.FILE_READ,
        PluginCapability.FILE_WRITE,
        PluginCapability.FILE_DELETE,
    )

    private fun error(field: String, message: String) =
        ValidationIssue(IssueSeverity.ERROR, field, message)

    private fun warning(field: String, message: String) =
        ValidationIssue(IssueSeverity.WARNING, field, message)
}

// ═══════════════════════════════════════════════════════════════
//  校验结果
// ═══════════════════════════════════════════════════════════════

enum class IssueSeverity {
    /** 拦截安装。判据是"这个字段能不能被恶意利用" */
    ERROR,

    /** 只提示，不拦。判据是"用户知情后可以自行决定" */
    WARNING,
}

data class ValidationIssue(
    val severity: IssueSeverity,
    /** 出问题的字段名，用于在导入界面上定位到具体配置项 */
    val field: String,
    /** 面向用户/审核者的说明。要讲清"为什么"，不能只讲"不合法" */
    val message: String,
)

sealed interface PluginValidationResult {
    data class Accepted(val warnings: List<ValidationIssue>) : PluginValidationResult
    data class Rejected(val errors: List<ValidationIssue>) : PluginValidationResult

    val isAccepted: Boolean get() = this is Accepted
}

// ═══════════════════════════════════════════════════════════════
//  信任分级
// ═══════════════════════════════════════════════════════════════

/** 插件来源 —— 决定用户看到的提示强度 */
enum class PluginSource {
    /** 本体内置 */
    BUILTIN,

    /** 从插件市场下载，且通过内容哈希校验 */
    MARKET,

    /** 用户从本地文件导入 */
    LOCAL_IMPORT,
}

/**
 * 信任等级。
 *
 * ⚠️ 注意 [MARKET_UNSIGNED] 与 [LOCAL_UNVERIFIED] 的区别：
 *    市场插件至少有内容哈希校验（能发现"下载途中被替换"），
 *    本地导入的插件连这个都没有（用户拿到的是什么就是什么）。
 *    所以本地导入的提示必须**更重**，不能只换一句文案。
 */
enum class PluginTrustLevel(val displayName: String) {
    BUILTIN("内置"),
    VERIFIED_SIGNATURE("作者已签名"),
    MARKET_UNSIGNED("市场来源，未签名"),
    LOCAL_UNVERIFIED("本地导入，未经验证"),
}

// ═══════════════════════════════════════════════════════════════
//  导入风险提示
// ═══════════════════════════════════════════════════════════════

/**
 * 导入前的风险告知内容。
 *
 * 用户明确要求「导入时明确提示风险由用户自行承担」。这里把"明确"落到三件具体的事上：
 *
 *  1. **逐条列出这个插件能做什么**，而不是笼统一句"插件可能有风险"
 *  2. **说清本体管不了什么** —— 用户得知道"没有签名校验"意味着什么
 *  3. **高危插件要求手动输入确认词**，把"点一下同意"变成"读一遍再确认"
 *
 * ⚠️ 另一条硬约束：[bullets] 里**不要写 Markdown 标记**（`**粗体**` 之类）。
 *    这一层是 UI 无关的契约，而渲染方是 Compose 的 `Text` —— 它不认识 `**`，
 *    会把星号原样显示给用户。在一份风险告知里冒出
 *    `插件**没有经过任何审核**` 这种字面量，会直接削掉它的可信度。
 */
data class ImportRiskNotice(
    val title: String,
    val summary: String,
    /** 逐条风险说明 */
    val bullets: List<String>,
    /** 该插件声明的全部能力，按风险降序 */
    val declaredCapabilities: List<PluginCapability>,
    val highestRisk: PluginCapability.RiskLevel?,
    /** 校验产生的警告 */
    val warnings: List<ValidationIssue>,
    /** 是否要求用户手动输入确认词才能继续 */
    val requireTypedConfirmation: Boolean,
) {
    companion object {

        /** 需要手动输入确认词的风险档位 */
        private val TYPED_CONFIRMATION_RISKS = setOf(
            PluginCapability.RiskLevel.CRITICAL,
            PluginCapability.RiskLevel.HIGH,
        )

        /** 确认词。必须是一个用户会认真读一遍的词，而不是"OK" */
        const val CONFIRMATION_PHRASE = "我已知晓风险"

        fun forLocalImport(
            manifest: PluginManifest?,
            warnings: List<ValidationIssue> = emptyList(),
        ): ImportRiskNotice {
            val caps = manifest?.capabilities?.sortedByDescending { it.risk.ordinal }.orEmpty()
            val highest = PluginValidator.summarizeRisk(caps)

            val bullets = buildList {
                add(
                    "这个插件没有经过任何审核。它来自你选择的本地文件，" +
                        "本应用无法验证它的来源、作者，也无法验证内容是否被篡改。"
                )
                add(
                    "它声明的能力会立即获得授权。插件一旦启用，" +
                        "就能使用下面列出的全部能力，不会在每次调用时再问你。"
                )

                if (caps.isEmpty()) {
                    add("它没有声明任何能力，理论上无法读取屏幕或模拟操作。")
                } else {
                    add("它声明了以下能力（按风险从高到低）：")
                    caps.forEach { cap ->
                        add("　· ${cap.userFacingDescription}（${cap.id}，风险：${cap.risk.label}）")
                    }
                }

                // 这几条是最容易出事的组合，单独点名
                if (PluginCapability.NETWORK_REQUEST in caps) {
                    val hosts = manifest?.allowedHosts.orEmpty()
                    if (hosts.isEmpty()) {
                        add("⚠️ 它可以访问网络，但没有限定域名 —— 意味着屏幕上的内容可能被发送到任意服务器。")
                    } else {
                        add("⚠️ 它可以把数据发送到这些域名：${hosts.joinToString("、")}。")
                    }
                }
                if (PluginCapability.SCREEN_CAPTURE in caps) {
                    add("⚠️ 它能截取你的屏幕画面。配合网络能力，等于可以把屏幕内容传出去。")
                }
                if (PluginCapability.LLM_CALL in caps) {
                    add("⚠️ 它会调用你配置的 AI 模型，产生费用，费用由你承担。")
                }
                if (PluginCapability.ACTION_INPUT in caps && PluginCapability.SCREEN_READ in caps) {
                    add("⚠️ 它既能读屏幕又能代你输入 —— 请确认你信任它的作者。")
                }

                add(
                    "即使插件有害，本应用无法替你追责：没有账号体系、" +
                        "没有服务端、没有插件作者的联系方式。风险由你自行承担。"
                )
                add("安全页面拦截（支付、密码、验证码）依然有效，这一层插件绕不过去。")
            }

            return ImportRiskNotice(
                title = "导入未经验证的插件",
                summary = "你正在导入「${manifest?.name ?: "未知插件"}」" +
                    "（${manifest?.id ?: "无 ID"}）。请先读完下面的内容再决定。",
                bullets = bullets,
                declaredCapabilities = caps,
                highestRisk = highest,
                warnings = warnings,
                requireTypedConfirmation = highest != null && highest in TYPED_CONFIRMATION_RISKS,
            )
        }
    }
}

/** 风险等级的中文标签，用于界面展示 */
val PluginCapability.RiskLevel.label: String
    get() = when (this) {
        PluginCapability.RiskLevel.LOW -> "低"
        PluginCapability.RiskLevel.MEDIUM -> "中"
        PluginCapability.RiskLevel.HIGH -> "高"
        PluginCapability.RiskLevel.CRITICAL -> "极高"
    }
