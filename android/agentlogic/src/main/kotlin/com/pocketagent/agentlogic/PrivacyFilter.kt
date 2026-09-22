package com.pocketagent.agentlogic

/**
 * 隐私过滤器 —— **所有上传路径的唯一出口**。
 *
 * ## 为什么必须"唯一出口"
 *
 * 项目原则 7 说「Key 永不落明文」，原则 4 说「主动放弃支付环节」。
 * 但这两条只覆盖了"凭据"和"支付页"两种数据。**真正会漏的是截图里的其它东西**：
 * 通知栏里的聊天内容、状态栏的运营商与电量、输入框里刚打的半句话、
 * 后台应用的缩略图预览、以及别人发来的消息气泡。
 *
 * 这些数据**没有任何一处代码是"专门上传它们的"** —— 它们搭着截图顺路上去的。
 *
 * 于是就有了本组件存在的唯一理由：
 *
 * > **只要过滤动作分散在各调用点，就必然有漏。**
 * > 今天循环里只有一处截图，明天加了 OCR、后天加了多模态问答、大后天插件也来截图 ——
 * > 每加一处都要记得过滤一次，而"记得"是不可依赖的。
 *
 * 所以本模块的形态是**一个必须被穿过的关卡**，而不是一组可选的工具函数。
 *
 * ## 设计上的关键决定：过滤不了就**丢弃**，绝不"尽力而为"
 *
 * 一个"尽力模糊"的过滤器会给人一种虚假的安全感：它输出了一张看起来没问题的图，
 * 而里面可能还剩半个身份证号。用户看不到证据，我们也没有。
 *
 * 因此本模块的失败模式是**保守的**：
 * - 无法确定某个区域安全 → 整张图丢弃，返回 [FilterOutcome.Dropped]
 * - 无法确定是否触碰禁止上传的内容 → 整条不发出
 *
 * **代价是任务可能失败。这正是想要的取舍** —— 任务失败用户看得见、能重试、
 * 能换个说法；而一条泄漏出去的消息收不回来。
 *
 * ## ⚠️ 本模块只做"决策"，不做"像素操作"
 *
 * 真正的裁剪 / 模糊 / 打码需要 `android.graphics`，一旦引入就把本模块拖出
 * 离线验证器的范围（本项目最贵的一课）。
 *
 * 所以这里产出的是**一份指令**（[RedactionPlan]）：要裁掉哪几个矩形、
 * 要遮蔽哪几块区域、要不要整张丢弃。Android 层照着执行。
 *
 * 这是本项目既定模式：**契约在纯模块、适配在 Android 层**。
 * 好处是"哪些内容会被遮"这个**最容易错、也最该被复查**的判断，
 * 可以用确定性离线测试逐条钉住。
 */
sealed interface FilterOutcome {

    /**
     * 允许上传，附带要执行的遮蔽指令。
     *
     * [plan] 可能表示"无需任何修改"（[RedactionPlan.isNoOp]）——
     * 那也是一个明确结论，不是"没检查"。
     */
    data class Allowed(val plan: RedactionPlan) : FilterOutcome

    /**
     * **整条丢弃，不上传。**
     *
     * @param reason 必须能直接展示给用户（"执行过程可见"原则）。
     *   用户需要知道任务为什么停在这里，否则只会觉得"这软件坏了"。
     */
    data class Dropped(val reason: String) : FilterOutcome
}

/**
 * 要执行的遮蔽指令。由 [PrivacyFilter] 产出，由 Android 层执行。
 *
 * ⚠️ **本类型刻意不含任何"保留原文"的字段** ——
 * 一旦能携带原始内容，就有调用方会"为了调试"把它记进日志。
 */
data class RedactionPlan(
    /** 要**完全裁掉**的矩形（原图像素坐标）。通常是整条状态栏 / 通知栏 */
    val cropOut: List<RedactRegion> = emptyList(),

    /** 要**遮蔽**（填充纯色或强模糊）的矩形。通常是输入框、消息气泡 */
    val mask: List<RedactRegion> = emptyList(),

    /**
     * 是否要**降质**后再上传。
     *
     * ⚠️ 降质**不是**隐私手段 —— 它是流量手段（见 `AgentBudget.maxUploadBytes`）。
     * 刻意放在这里是因为两者同为"上传前对图像的处理"，但**必须区分开**：
     * 把降质当成脱敏是危险的（缩小的文字仍可能被 OCR 读出）。
     */
    val reduceQuality: Boolean = false,
) {
    /** 无需任何修改 —— 是"检查过且干净"，不是"没检查" */
    val isNoOp: Boolean get() = cropOut.isEmpty() && mask.isEmpty() && !reduceQuality
}

/** 一个矩形区域（原图像素坐标）。纯数据，不引 `android.graphics.Rect` */
data class RedactRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left) { "矩形左右坐标颠倒：left=$left right=$right" }
        require(bottom >= top) { "矩形上下坐标颠倒：top=$top bottom=$bottom" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
}

/**
 * 系统 UI 区域 —— **由 Android 层量出来传进来，本模块不猜。**
 *
 * ⚠️ 为什么不在本模块里写死"状态栏 80px"：
 * 刘海屏 / 挖孔屏 / 手势条 / 分屏模式下，系统栏的高度和位置全都不同。
 * 猜错的后果是**该裁的没裁掉**，而这是静默的。
 *
 * 传进来就要求 Android 层显式获取（`WindowInsets` / `DisplayCutout`），
 * 取不到时填 [unknown] —— 而 [unknown] 会让过滤器**保守地丢整张图**。
 */
data class SystemUiRegions(
    /** 状态栏（含时间、电量、通知图标、运营商） */
    val statusBar: RedactRegion?,
    /** 导航栏 / 手势条 */
    val navigationBar: RedactRegion?,
    /** 刘海 / 挖孔区域 */
    val displayCutout: RedactRegion? = null,
) {
    /** 是否至少有一个区域是已知的 */
    val hasAnyKnown: Boolean
        get() = statusBar != null || navigationBar != null || displayCutout != null

    /** 全部已知矩形，便于统一处理 */
    val allKnown: List<RedactRegion>
        get() = listOfNotNull(statusBar, navigationBar, displayCutout)

    companion object {
        /**
         * **取不到系统栏信息**时的占位。
         *
         * ⚠️ 这不是"无需处理"的意思 —— 它的含义是"我们不知道哪些地方是系统 UI"，
         * 因此 [PrivacyFilter] 遇到它会**丢弃整张图**。
         *
         * 故意做成一个显式常量而不是 `SystemUiRegions(null, null)`：
         * 后者读起来像"这个页面没有系统栏"，而前者读起来像"我们没量到"。
         * **两种含义完全不同，不该长得一样。**
         */
        val unknown = SystemUiRegions(statusBar = null, navigationBar = null)
    }
}

/**
 * 上传上下文 —— 描述"这条数据要发去哪、用来干什么"。
 *
 * 隐私判断离不开上下文：同一张截图，发给用户自己配的模型做任务规划，
 * 和发去一个插件作者自建的分析服务，需要完全不同的严格程度。
 */
data class UploadContext(
    /** 目标 Provider 的标识（用于按信任级别区分策略） */
    val providerId: String,

    /**
     * 这次上传的用途。
     *
     * ⚠️ **`PLUGIN_ANALYSIS` 是本模块存在的主要理由之一** ——
     * 插件是最不可信的来源（见原则 8：插件默认关进沙箱），
     * 而插件想拿截图做分析时，它并不比模型厂商更值得信任。
     */
    val purpose: UploadPurpose,

    /** 是否已经过 `PrivacyFilter`（由调用方填，用于检测绕过） */
    val alreadyFiltered: Boolean = false,
)

/** 上传用途 —— 决定过滤强度 */
enum class UploadPurpose {
    /** 送给模型做任务规划 / 决策 */
    TASK_REASONING,

    /** 送给模型做 OCR / 视觉问答 */
    VISUAL_QA,

    /**
     * 插件请求的分析。
     *
     * ★ 与上面两种的区别不在"技术上要遮什么"，而在**信任前提**：
     * 前两种的对象是用户自己配置的模型厂商，这一种的对象是插件作者。
     * 所以它**一律走最严格策略**，且不允许通过参数放宽。
     */
    PLUGIN_ANALYSIS,

    /** 本地调试用（**禁止出现在 release 构建里**，由 R1/R8 类检查兜底） */
    LOCAL_DEBUG,
}

/**
 * 过滤策略 —— 由 [PrivacyFilter] 依据上下文选出一套，与具体实现分离。
 *
 * 分开的好处：策略是**可以被测试逐条覆盖的**，而实现只有一份。
 * 若把两者写在一起，"哪些数据会被遮"就散落在 if/else 里，无法穷举验证。
 */
data class FilterPolicy(
    /** 是否裁掉系统 UI（状态栏 / 导航栏 / 刘海） */
    val stripSystemUi: Boolean = true,

    /**
     * 是否遮蔽文本输入区域。
     *
     * ★ 输入框是**最容易被忽略的高风险区**：用户可能正在输入密码、
     * 手机号、验证码，而这些内容恰好就在截图正中央，最显眼的位置。
     */
    val maskInputFields: Boolean = true,

    /** 是否遮蔽屏幕上的消息列表（聊天内容、通知列表） */
    val maskMessageLists: Boolean = true,

    /** 是否允许上传**任何**截图 */
    val allowScreenshots: Boolean = true,

    /** 命中"敏感页面"时是否立即停止任务（对应项目原则 4） */
    val abortOnSensitivePage: Boolean = true,

    /** 是否允许降质以控制流量 */
    val allowQualityReduction: Boolean = true,
)

/**
 * 页面类型 —— 由 Android 层的页面识别器给结论，本模块据此决策。
 *
 * ⚠️ 识别在 Android 层、**决策在纯层**，是刻意的：
 * 识别要读 `packageName` / 无障碍树，离不开 Android；
 * 而"识别成支付页之后该怎么办"是纯策略，必须可离线穷举测试。
 */
enum class PageKind {
    /** 普通页面 */
    NORMAL,

    /** 文本输入页（登录、搜索、编辑） */
    INPUT,

    /**
     * 敏感页面（支付 / 银行 / 密码管理）。
     *
     * 项目原则 4：命中后**立即停止、丢弃截图、交还用户**。
     * 规则只许加严，不许放宽。
     */
    SENSITIVE,
}

/**
 * 上传前的隐私关卡。
 *
 * ## 用法（**唯一正确用法**）
 *
 * ```kotlin
 * when (val outcome = filter.review(request)) {
 *     is FilterOutcome.Allowed -> gateway.send(outcome.plan)   // 只有这一条能上传
 *     is FilterOutcome.Dropped -> ui.showStopped(outcome.reason)
 * }
 * ```
 *
 * ## ⚠️ 三条使用纪律（违反任一条，本组件的价值归零）
 *
 * 1. **不允许存在第二条上传路径。** 任何"直连 Provider"的代码路径都是绕过。
 *    由 `check_privacy_routes.py` 做 CI 守卫（扫 `OkHttpClient` / `HttpURLConnection`
 *    的直接构造点）。
 * 2. **禁止静默降级。** 能力被拒必须抛出明确异常或返回 [FilterOutcome.Dropped]，
 *    绝不允许"过滤失败但照样发出去"。见 [review] 的 `require`。
 * 3. **不为方便加"全部放行"开关。** 用户可见的开关只能加严（比如"连降质也关掉"），
 *    不能放宽到"不过滤"。
 */
class PrivacyFilter(
    private val systemUi: SystemUiRegions,
    private val policy: FilterPolicy = FilterPolicy(),
) {

    /**
     * 审查一次上传请求，给出允许/丢弃的结论。
     *
     * ## 决策顺序（**短路返回，顺序不可调换**）
     *
     * 1. **敏感页面** —— 最高优先级，一票否决，不看其它条件
     * 2. **禁止截图的上传** —— `allowScreenshots = false` 时直接丢弃
     * 3. **系统 UI 未知** —— 无法安全裁剪 → 丢弃（保守）
     * 4. 正常路径 —— 产出裁剪 + 遮蔽指令
     *
     * ⚠️ 第 1 步必须在最前。若把"是否需要裁状态栏"之类的判定放前面，
     * 敏感页面会在算完一堆无关条件后才被拦下 —— **结论一样，但多绕的每一步
     * 都是将来被人改错的机会**。而且顺序错了以后，"支付页被放过一次"
     * 这种 bug 只有在真实支付页上才复现，极难在测试里发现。
     *
     * @throws IllegalArgumentException 当请求标记 `alreadyFiltered = true`
     *   却又没有携带计划时 —— 说明有人伪造了标记来绕过关卡。
     *   **这是刻意的硬失败**，不是"退回重新过滤"：
     *   伪造标记本身就是 bug，静默修正会让它一直存在。
     */
    fun review(request: UploadRequest): FilterOutcome {
        // ── 步骤 0：伪造标记检测（硬失败）───────────────────────
        require(!(request.context.alreadyFiltered && request.priorPlan == null)) {
            "上传请求声称已过滤但未附带过滤计划 —— 疑似绕过 PrivacyFilter。" +
                "provider=${request.context.providerId}"
        }

        // ── 步骤 1：敏感页面，一票否决 ──────────────────────────
        if (request.pageKind == PageKind.SENSITIVE) {
            return FilterOutcome.Dropped(
                reason = "当前页面属于敏感页面（支付/银行/密码），已按安全策略中止。" +
                    "截图已丢弃，请手动完成这一步。",
            )
        }

        // ── 步骤 2：该用途根本不允许截图 ────────────────────────
        // 插件分析是最需要收紧的一类：它由第三方编写，且不在我们可控范围内。
        // ★ 这里刻意**不看** `policy.allowScreenshots` —— 它是调用方可配的，
        //   而插件分析的底线不该由调用方决定。
        if (request.requiresScreenshot && request.context.purpose == UploadPurpose.PLUGIN_ANALYSIS) {
            return FilterOutcome.Dropped(
                reason = "插件不允许获取屏幕内容。如需该能力，请在插件权限页逐项放行。",
            )
        }
        if (request.requiresScreenshot && !policy.allowScreenshots) {
            return FilterOutcome.Dropped(
                reason = "当前策略不允许上传截图。",
            )
        }

        // ── 步骤 3：只需要文本的上传，不受系统 UI 影响 ──────────
        // 无障碍树文本没有像素，系统 UI 区域无从谈起。
        // ⚠️ 但**输入框内容仍需遮蔽** —— 树的文本同样会带出用户正在打的字。
        if (!request.requiresScreenshot) {
            return FilterOutcome.Allowed(
                plan = RedactionPlan(
                    mask = if (policy.maskInputFields) request.inputFieldRegions else emptyList(),
                    reduceQuality = false, // 无像素可降
                ),
            )
        }

        // ── 步骤 4：系统 UI 未知 → 保守丢弃 ─────────────────────
        // ⚠️ 这是本模块**最重要的保守取舍**。
        //    有人会想"那就整张上传吧，反正大部分区域是安全的"——
        //    但状态栏里有通知内容，而通知内容常常就是聊天消息。
        if (policy.stripSystemUi && !systemUi.hasAnyKnown) {
            return FilterOutcome.Dropped(
                reason = "无法确定屏幕上的系统 UI 区域，为避免上传通知栏内容，已放弃本次截图。" +
                    "这通常是权限或系统版本问题，请反馈。",
            )
        }

        // ── 步骤 5：正常路径，组装遮蔽计划 ──────────────────────
        val crops = if (policy.stripSystemUi) systemUi.allKnown else emptyList()

        val masks = buildList {
            if (policy.maskInputFields) addAll(request.inputFieldRegions)
            if (policy.maskMessageLists) addAll(request.messageListRegions)
        }

        // 降质只在允许时开，且**只在确实是流量受限时**才开 —— 见 FilterPolicy 注释。
        val reduce = policy.allowQualityReduction && request.qualityReductionWanted

        return FilterOutcome.Allowed(
            plan = RedactionPlan(
                cropOut = crops,
                mask = masks,
                reduceQuality = reduce,
            ),
        )
    }

    /** 便捷方法：直接要结论，不要计划（用于"这条能不能发"的快速判定） */
    fun allows(request: UploadRequest): Boolean = review(request) is FilterOutcome.Allowed
}

/**
 * 一次待审查的上传请求。
 *
 * 刻意把"要发什么"与"怎么发"分开：本类型不含任何内容字段
 * （不装 bitmap、不装文本），只有**关于内容的元信息**。
 * 这样即使有人把它记进日志也不会泄漏内容。
 */
data class UploadRequest(
    val context: UploadContext,

    /** 这一步是否真的需要上传像素。false = 只传文本（无障碍树 / 动作结果） */
    val requiresScreenshot: Boolean,

    /** 页面类型（由 Android 层识别后传入） */
    val pageKind: PageKind = PageKind.NORMAL,

    /**
     * 输入框区域（原图坐标）。
     *
     * ★ 由 Android 层从无障碍树里取 `isEditable` 节点算出，不在本模块猜。
     * 取不到时传空列表 —— 副作用是输入框不会被遮，
     * 因此 Android 层**必须**尽量取，取不到要在日志里留痕。
     */
    val inputFieldRegions: List<RedactRegion> = emptyList(),

    /** 消息列表 / 通知列表区域 */
    val messageListRegions: List<RedactRegion> = emptyList(),

    /** 调用方是否希望降质（真正是否降质由策略决定） */
    val qualityReductionWanted: Boolean = false,

    /** 上游已给过的计划（仅用于校验伪造的 `alreadyFiltered` 标记） */
    val priorPlan: RedactionPlan? = null,
)
