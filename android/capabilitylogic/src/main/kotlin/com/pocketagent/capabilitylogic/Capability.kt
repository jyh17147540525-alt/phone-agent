package com.pocketagent.capabilitylogic

/**
 * 第 0 档能力的**声明部分** —— 一个能力是什么、走哪条通道、有多危险、要哪些参数。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要这一层
 * ═══════════════════════════════════════════════════════════════
 *
 * 「第 0 档」= **任务全程屏幕上不出现任何因 agent 而起的界面变化**。
 * 它不读节点树、不截图、不注入触摸 —— 而是走系统设置、shell 命令与文件接口。
 * ⚠️ 它**不等于**「与屏幕无关」：亮度、熄屏这类能力同样零占屏。
 *    零占屏是**技术判据**，第 0 档的产品范围由 [CapabilityGroup] 划定。
 * 详见 `docs/第0档零占屏任务清单-v1.0.md`。
 *
 * 这类能力与"点屏幕"的能力有一个**根本差别**，也是本模块存在的全部理由：
 *
 * | | 点屏幕（`:action`） | 第 0 档（本模块） |
 * |---|---|---|
 * | 错误后果 | 点错一个按钮 → 用户看得见 | 改错一个设置 → **用户看不见** |
 * | 影响范围 | 当前 App 的当前页面 | **整台设备** |
 * | 可观测性 | 屏幕上立刻有反应 | 可能几分钟后才表现出来 |
 *
 * `cmd package disable` 打错一个包名，用户下次想用那个 App 时才发现它没了；
 * `settings put secure enabled_accessibility_services` 写错一个字符，
 * agent 就把无障碍权限授予了自己 —— **而"默认拒绝、逐项放行"的整个模型就此归零**。
 *
 * 所以本模块的纪律是：**能力必须先在目录里声明，才能被调用**；
 * 声明里写死它可能的取值空间；再由 [CapabilityGuard] 在每次调用时重新裁决。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 风险只有两级，**没有第三级**
 * ═══════════════════════════════════════════════════════════════
 *
 * 你可能会想加一个 `FORBIDDEN` 级，把"永不放行"的能力也列进目录、标上它。
 * **不要那样做。** 本项目的既定立场是「**把禁令做成能力不存在，而不是配置可关**」
 * （见 `README.md` §安全边界）—— 一个出现在目录里、只是标了"禁止"的能力，
 * 迟早会有人写一行 `if (risk != FORBIDDEN)` 把它放过去；而一个**根本不在目录里**
 * 的能力，绕过的唯一方式是重新实现一遍整个能力层。
 *
 * 那些"永不放行"的东西去哪了？见 [CapabilityDenyRules] —— 它是一张**单向阀**，
 * 用来挡住"有人往目录里加了一条危险能力"这种情况。
 */
/**
 * 这条能力属于哪一组 —— **产品范围**的划分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 「零占屏」是技术判据，「第 0 档」是产品范围 —— 两者不是一回事
 * ═══════════════════════════════════════════════════════════════
 *
 * [DEVICE] 里的那些能力（亮度、熄屏、深色模式、Wi-Fi、媒体控制）
 * **技术上确实满足零占屏**：它们不 `startActivity`、不读节点树、不注入触摸。
 *
 * 但它们**不属于第 0 档的产品范围**。理由是一条更朴素的问题：
 *
 * > 在不占屏的前提下，什么能力对用户最有价值？
 *
 * 第 0 档要做的是「**手机上最接近电脑端办公的那一层**」—— 读写文件、
 * 整理目录、生成报表。而"切深色模式""收起通知栏"虽然满足约束，
 * 却不帮用户完成任何一件事。
 *
 * ⚠️ 这个区分是 2026-09-23 由用户提出的，它修正了一个真实的偏差：
 *    最初实现把「零占屏」这个**约束**当成了**目的**，于是收集了 14 条
 *    "因为不占屏所以能做"的设备开关。它们全部满足判据，但一条都不解决问题。
 *
 * ⚠️ 更现实的一层：那 14 条**全部走 [CapabilityChannel.SHELL]**，
 *    而 T0-B（shell 通道）**还没实现** —— 真机上它们一条都跑不起来，
 *    点了只会得到「还没实现」。把它们留在第 0 档页上，就是
 *    `docs/文件能力沙箱设计-v1.0.md` §8 批评的那种「看起来已实现」。
 *
 * ⇒ 所以 [DEVICE] 的代码**保留**（将来 T0-B 做好后可直接启用），
 *   但**不出现在第 0 档页**上。见 [CapabilityCatalog.zeroScreen]。
 */
enum class CapabilityGroup(
    /** 给用户看的组名。会作为能力页上的分组标题。 */
    val displayName: String,

    /**
     * 是否属于第 0 档的产品范围。
     *
     * ⚠️ 名字容易被读成"是否零占屏"。**它不是那个意思** ——
     *    见上面「技术判据 vs 产品范围」那一段：[DEVICE] 里的能力同样零占屏。
     */
    val isZeroScreen: Boolean,
) {
    /**
     * 文件与文档 —— 第 0 档的核心。
     *
     * 走 T0-D（SAF / 应用私有目录）。⚠️ 它是**唯一一条不需要任何特权**的通道：
     * 不需要电脑 adb、不需要 Shizuku、不需要签名级权限 ——
     * 用户只需在系统文件选择器里选一次目录。
     */
    FILES("文件与文档", isZeroScreen = true),

    /**
     * 通知 —— agent 的汇报通道。
     *
     * ⚠️ 通知**不是屏幕操作**，恰恰相反：它是"用通知**替代**界面"，
     *    是零占屏最典型的形态。去掉它，agent 就变成一个沉默的执行器 ——
     *    任务做完、失败、需要确认，用户都无从得知。
     */
    NOTIFY("通知", isZeroScreen = true),

    /**
     * 设备控制 —— 亮度、熄屏、深色模式、Wi-Fi、媒体控制等。
     *
     * ⚠️ **不属于第 0 档**，见枚举注释。代码保留，但不在第 0 档页显示。
     */
    DEVICE("设备控制", isZeroScreen = false),
}

enum class CapabilityChannel(
    /** 给用户看的名字。要说明**代价**，不能只写技术名词。 */
    val displayName: String,
) {
    /**
     * T0-A：直接读写 `Settings.Global/System/Secure`。
     *
     * 需要用户用 adb 授予 `WRITE_SECURE_SETTINGS`（该权限带 `development` 标志，
     * 可以 `pm grant`）。⚠️ **必须在 manifest 里先声明** ——
     * 对未声明的权限，`pm grant` 不报错也不生效。
     */
    SETTINGS("系统设置（需要「修改安全设置」权限）"),

    /**
     * T0-B：通过 Shizuku 以 shell（uid 2000）身份执行命令。
     *
     * 需要用户装并激活 Shizuku。⚠️ 这**不是 root** —— shell 改不了 SELinux 策略、
     * 设不了受限 `setprop`（P0-3 里 `enable_freeform_support` 就是被这条挡住的）。
     */
    SHELL("Shizuku（需要你已激活 Shizuku）"),

    /**
     * T0-D：文件接口 —— SAF（用户授权一个目录）或应用私有目录。
     *
     * ⚠️ 它是**唯一一条不需要任何特权**的通道：不需要电脑上跑 adb、
     *    不需要 Shizuku、不需要签名级权限。用户只需在系统文件选择器里
     *    选一次目录，授权之后一直有效。
     *
     * ⚠️ 但它的**可达范围**也最小：只能碰用户授权的那几个目录，
     *    够不到 `/Android/data`、够不到系统目录。
     *    这是安全模型，不是缺陷 —— 而且用户随时可以撤销授权。
     */
    FILES("文件（你选定的目录，不需要额外权限）"),
}

/**
 * 风险级别 —— 决定**默认**是否放行。
 *
 * ⚠️ 它只影响"要不要用户点一下确认"，**不影响前置判定**。
 *    无论哪一级，[CapabilityDenyRules] 与参数校验都照跑（见 [CapabilityGuard]）。
 */
enum class CapabilityRisk {
    /**
     * 默认放行。判据是三条**全满足**：
     * 1. 可逆 —— 用户随时能改回来
     * 2. 无数据损失 —— 不删除、不覆盖任何东西
     * 3. 用户立刻能发现 —— 不需要"过一会儿才察觉"
     *
     * 例：媒体播放控制、深色模式、收起通知栏。
     */
    SAFE,

    /**
     * 默认拒绝，需用户逐项放行后才会执行。
     *
     * 判据是**任一条**成立：
     * - **可能造成数据损失**（写文件覆盖、删除、移动）→ 用户的原文件没了
     * - 影响连接/可达性（关 Wi-Fi、开飞行模式）→ 用户可能因此收不到消息
     * - 影响其他 App 的运行（停用 App、加入电池白名单）
     * - 改变用户会持续感知的外观/行为（亮度、屏幕超时、勿扰）
     *
     * ⚠️ 第一条是 2026-09-23 补的。它本来是 [SAFE] 判据第 2 条的反面
     *    （"无数据损失"），却没有在 [GUARDED] 里列出来 —— 于是
     *    「写文件」这类能力在纸面上找不到该归哪一级。
     *    一条只写在"允许侧"的判据，在"拒绝侧"是不存在的。
     *
     * ⚠️ 这一级**插件永远碰不到** —— 见 [CapabilityGuard] 步骤 2。
     */
    GUARDED,
}

/**
 * 参数的规格。
 *
 * ⚠️ **它是"能力"的一部分，不是执行时才检查的东西。**
 *    把它写在声明里，才能回答"这个能力**可能**被用来做什么" ——
 *    而这个问题的答案决定了它该不该进目录。
 *
 * 例：`setting.write(namespace, key, value)` 这种**通用**能力看起来最省事，
 * 但它等于把整张设置表暴露出去，于是"能力目录"就退化成了"命令目录"，
 * 白名单也就名存实亡。**宁可写 20 条具体的、参数受限的能力。**
 */
sealed interface ParamSpec {

    val name: String

    val required: Boolean

    /** 给用户看的参数说明。会出现在确认框里。 */
    val summary: String

    /**
     * 从固定字面量里选一个。
     *
     * ⚠️ 命令拼装时**只会出现 [values] 里的字符串之一**。
     *    这条约束是"参数校验"与"命令规划"之间唯一的契约 ——
     *    [CommandPlanner] 直接信任它，不再二次检查。
     */
    data class Choice(
        override val name: String,
        val values: List<String>,
        override val summary: String,
        override val required: Boolean = true,
    ) : ParamSpec {
        init {
            require(values.isNotEmpty()) { "Choice 参数「$name」的候选值不能为空" }
            require(values.none { it.isBlank() }) { "Choice 参数「$name」的候选值不能为空白" }
        }
    }

    /** 闭区间整数。 */
    data class IntIn(
        override val name: String,
        val range: IntRange,
        override val summary: String,
        override val required: Boolean = true,
    ) : ParamSpec

    /**
     * Android 包名。
     *
     * ⚠️ 单独成一类而不是用 [Text]，是因为**它的校验规则容易写松**：
     *    包名至少要有两段（`com.example`），且每段不能以数字开头。
     *    一个过松的规则会让 `cmd package disable` 收到它看不懂的东西 ——
     *    而 `cmd` 对看不懂的参数**可能静默忽略**。
     */
    data class PackageName(
        override val name: String,
        override val summary: String,
        override val required: Boolean = true,
    ) : ParamSpec

    /**
     * 受控文本。
     *
     * ⚠️ [allowed] 是**白名单正则**（必须整串匹配），不是黑名单。
     *    黑名单在这里是错的：文本会被送进通知栏，而通知栏支持富文本与图标 ——
     *    漏掉一个控制字符，用户看到的就是一条**冒充系统通知**的假消息。
     */
    data class Text(
        override val name: String,
        val maxLength: Int,
        val allowed: Regex,
        override val summary: String,
        override val required: Boolean = true,
    ) : ParamSpec
}

/**
 * 一条能力的声明。
 *
 * ⚠️ [id] 是**稳定标识**，会进审计日志与用户可见的授权列表。
 *    改名等于让用户之前的授权记录对不上号，所以一旦发布就不要改。
 */
data class Capability(
    /** 稳定标识，形如 `media.dispatch`。见 [CapabilityCatalog] */
    val id: String,

    val channel: CapabilityChannel,

    /**
     * 这条能力属于第 0 档的哪一组。见 [CapabilityGroup]。
     *
     * ⚠️ 刻意**没有默认值**。给一个默认值意味着"新加能力时忘了标分组"
     *    会静默落进某一组 —— 落进 [CapabilityGroup.DEVICE] 用户永远看不到，
     *    落进 [CapabilityGroup.FILES] 用户以为能用。两种都错，而且都不报错。
     *    不给默认值，这种疏漏会**编译失败**。（同 [recipe] 的立场）
     */
    val group: CapabilityGroup,

    val risk: CapabilityRisk,

    /** 给用户看的一句话说明。**要说清"会发生什么"，不是"这个能力叫什么"** */
    val summary: String,

    val params: List<ParamSpec> = emptyList(),

    /**
     * 怎么执行。见 [ExecutionRecipe] 里"为什么配方写在这里"的论证。
     *
     * ⚠️ 它没有默认值。这是刻意的：一个"有声明、没实现"的能力
     *    比一个不存在的能力更危险 —— 它会出现在用户可见的授权列表里，
     *    让用户以为自己放行了一个能用的东西。
     */
    val recipe: ExecutionRecipe,
) {
    init {
        require(id.isNotBlank()) { "能力 id 不能为空" }
        require(id.none { it.isWhitespace() }) { "能力 id 不能含空白：$id" }
        require(params.map { it.name }.toSet().size == params.size) {
            "能力「$id」有重名参数"
        }

        // ★ 声明与配方必须严格一一对应。**两个方向都要查**：
        //
        //   配方引用了未声明的参数 → 规划期取不到值。这个方向至少会炸，
        //   炸在"用户点了确认之后"，还算能被发现。
        //
        //   声明了配方不用的参数 → **完全静默**：用户在确认框里被要求
        //   填一个毫无作用的字段，它会通过校验、进审计日志、
        //   看起来一切正常，只是对结果没有任何影响。
        //   而"我明明填了 XX 怎么没生效"这种问题，事后极难定位。
        val referenced = recipe.referencedParamNames()
        val declared = params.map { it.name }.toSet()

        require(referenced.all { it in declared }) {
            "能力「$id」的配方引用了未声明的参数：${referenced - declared}"
        }
        require(declared.all { it in referenced }) {
            "能力「$id」声明了配方用不到的参数：${declared - referenced}"
        }
    }

    /** 该能力的参数名集合。用于校验"有没有多传参数"。 */
    val paramNames: Set<String> get() = params.map { it.name }.toSet()
}

/** 参数的问题。 */
sealed interface ParamProblem {
    /** 缺必填参数 */
    data class Missing(val name: String, val summary: String) : ParamProblem

    /**
     * 多传了参数。
     *
     * ⚠️ 这**必须**是错误，不能忽略。忽略它的后果是：调用方以为
     *    `args = {"packageName": "com.x", "user": "999"}` 里的 `user` 生效了，
     *    而实际执行的是默认用户 —— 一个"看起来对、做的是另一件事"的调用。
     */
    data class Unknown(val name: String, val known: Set<String>) : ParamProblem

    /** 取值不在候选里 */
    data class NotAllowed(val name: String, val value: String, val allowed: List<String>) : ParamProblem

    /** 不是整数，或超出范围 */
    data class OutOfRange(val name: String, val value: String, val range: IntRange) : ParamProblem

    /** 不是合法的包名 */
    data class NotPackageName(val name: String, val value: String) : ParamProblem

    /** 文本超长或含不允许的字符 */
    data class BadText(val name: String, val value: String, val reason: String) : ParamProblem
}

/**
 * 参数校验 —— **收口到一处**。
 *
 * ⚠️ 为什么不放在 [CapabilityGuard] 里做私有方法：
 *    它是本模块里最容易被单独复用的逻辑（能力编辑页、插件清单校验都要用），
 *    而"每个调用点各写一遍校验"必然出现松紧不一 —— 与
 *    `PathNormalizer` 被单独拆出来是同一个理由。
 *
 * ★ 校验的**唯一目标**是让 [CommandPlanner] 可以无条件信任参数。
 *   所以这里宁可严到误伤，也不留"看起来能用"的缝。
 */
object CapabilityParams {

    /**
     * 包名规则。
     *
     * 依据 Android 官方对 `package` 属性的要求：至少两段、每段以字母开头、
     * 只允许字母数字下划线。⚠️ 本机实测过 `cmd package disable` 对
     * **不存在的包名**会报错，但对**格式合法的空字符串**行为未验证 ——
     * 所以这里从格式上就堵死空值。
     */
    private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]+)+$")

    fun validate(capability: Capability, args: Map<String, String>): List<ParamProblem> {
        val problems = mutableListOf<ParamProblem>()

        // ── 多传的参数 ─────────────────────────────────────────
        // 先查这个，因为它的存在会让后面所有判断失去意义：
        // 调用方以为自己传了某个参数，而实际生效的是默认值。
        for (key in args.keys) {
            if (key !in capability.paramNames) {
                problems += ParamProblem.Unknown(key, capability.paramNames)
            }
        }

        // ── 逐个参数 ───────────────────────────────────────────
        for (spec in capability.params) {
            val raw = args[spec.name]

            if (raw == null) {
                if (spec.required) {
                    problems += ParamProblem.Missing(spec.name, spec.summary)
                }
                continue
            }

            problems += validateOne(spec, raw)
        }

        return problems
    }

    private fun validateOne(spec: ParamSpec, raw: String): List<ParamProblem> = when (spec) {
        is ParamSpec.Choice ->
            if (raw in spec.values) emptyList()
            else listOf(ParamProblem.NotAllowed(spec.name, raw, spec.values))

        is ParamSpec.IntIn -> {
            val parsed = raw.toIntOrNull()
            // ⚠️ 不用 `toInt()` + try/catch：`toIntOrNull` 对 "12 " 也返回 null，
            //    这正是想要的 —— 带空格的数字应当被拒，而不是被宽容地修剪。
            if (parsed == null || parsed !in spec.range) {
                listOf(ParamProblem.OutOfRange(spec.name, raw, spec.range))
            } else {
                emptyList()
            }
        }

        is ParamSpec.PackageName ->
            if (PACKAGE_NAME.matches(raw)) emptyList()
            else listOf(ParamProblem.NotPackageName(spec.name, raw))

        is ParamSpec.Text -> when {
            raw.isEmpty() -> listOf(ParamProblem.BadText(spec.name, raw, "不能为空"))
            raw.length > spec.maxLength ->
                listOf(ParamProblem.BadText(spec.name, raw, "超过 ${spec.maxLength} 个字符"))

            !spec.allowed.matches(raw) ->
                listOf(ParamProblem.BadText(spec.name, raw, "含不允许的字符"))

            else -> emptyList()
        }
    }

    /** 包名校验的正则，供测试直接引用，避免测试里再抄一份。 */
    internal val packageNamePattern: Regex get() = PACKAGE_NAME
}
