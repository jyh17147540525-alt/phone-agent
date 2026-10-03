package com.pocketagent.mcp

/**
 * 动作前的**安全判定端口** —— 由 `:app` 映射到 `:safety` 的 `SafetyGuard.checkBeforeAction`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么走这个端口，而不是在本模块里另装一套"三道关卡"
 * ═══════════════════════════════════════════════════════════════
 *
 * 派工建议的是「① 敏感判定 → ② 目标校验 → ③ 频率限制」三道关卡。
 * 但盘点后发现：`DefaultSafetyGuard.checkBeforeAction` **已经实现**了
 * 同语义的判定，而且顺序更长、理由都写在注释里：
 *
 * ```
 * 1. 内置包名黑名单   ← 单向阀，任何东西不能越过
 * 2. App 自动化声明    ← SAEP 类协议（第三方 App 的意愿）
 * 3. 页面敏感关键词
 * 4. 敏感输入控件
 * 5. 操作频率          ← 到这里才轮到"工程性"限制
 * 6. 危险动作二次确认  ← 最后才是"问一下用户"
 * ```
 *
 * 在本模块里再拼一套，等于把同一份安全策略实现两遍 ——
 * 两份策略迟早漂移，而漂移的方向是**其中一份比另一份松**，没有人会发现。
 * 所以这里只做**端口**：查询形状（本文件）→ `:app` 搬运 → `:safety` 判定。
 * 判定的"数量与顺序"由 `:safety` 自己的测试钉住（`DefaultSafetyGuardTest` 在
 * 离线验证器里跑）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ v1 的已知降级：确认类 → 拒绝
 * ═══════════════════════════════════════════════════════════════
 *
 * `SafetyGuard` 会返回 [ActionSafetyOutcome.ConfirmationRequired]（危险动作
 * "二次确认"）。**当前版本没有"用户确认"这条交互通道**（dsh 侧没有可用的
 * 确认 UI），所以工具层把它降级为 [ToolOutcome.Refused] —— 宁可不做，
 * 不假装确认过。将来做确认通道时，替换点就在工具的映射处（一行 when 分支）。
 */

/**
 * 一次动作前判定的输入。
 *
 * ⚠️ 与 [ScreenSafetyQuery] 同一条纪律：**只有"形状"，没有"内容"** ——
 *    [inputFields] 不含用户已输入的文本；[targetDescription] 是目标元素的
 *    文本/描述（用于危险动作关键词匹配），它是"要找它"的依据，不是用户数据。
 */
data class ActionSafetyQuery(
    val packageName: String,
    val activityName: String?,
    /**
     * 动作类型（工具语境，如 `"tap"` / `"swipe"`）。
     * 只进审计事件与文案，不参与判定 —— 判定看的是"页面 + 目标"。
     */
    val actionType: String,
    /**
     * 动作指向元素的描述（语义节点与点击节点的文本/描述拼接）。
     * 盲点（无元素可描述的坐标点击）传 null —— 危险动作关键词匹配按"无目标"处理。
     */
    val targetDescription: String?,
    /** 当前页面的可见文本（供页面级关键词匹配）。 */
    val visibleTexts: List<String>,
    /** 页面上的输入控件**形状**（不含已输入内容）。 */
    val inputFields: List<InputFieldLite>,
    /**
     * 最近一分钟内**已派发**的动作数。
     *
     * ⚠️ 这个计数由工具侧维护（[ActionRateLimiter]），不是判定方自己数的 ——
     *    `:safety` 的 `ActionContext.actionsInLastMinute` 就是"调用方喂进来"的设计。
     *    判定方拿它做安全硬闸（60/分钟），工具侧另有自己的拟人节奏闸（20/分钟），
     *    两道闸的职责不同、数值不同，这都是 `:safety` 注释里写好的既定设计。
     */
    val actionsInLastMinute: Int,
)

sealed interface ActionSafetyOutcome {

    /** 放行。 */
    data object Allowed : ActionSafetyOutcome

    /**
     * 硬拦截。`userMessage` 可**直接展示给用户**。
     *
     * @param canFallbackToManual `:safety` 给出的"能否降级为引导用户手动完成"——
     *   文案组织时用它决定是"请你接手"还是"请重新下达指令"。
     */
    data class Blocked(
        val userMessage: String,
        val canFallbackToManual: Boolean,
    ) : ActionSafetyOutcome

    /**
     * 需要用户显式确认后才能继续（危险动作）。
     *
     * ⚠️ v1 无确认通道 ⇒ 工具层降级为拒绝（见本文件顶部注释）。
     */
    data class ConfirmationRequired(val userMessage: String) : ActionSafetyOutcome
}

/**
 * 安全判定端口。实现方（`:app`）不得抛异常 ——
 * 判定引擎出错时应当**向"拦"的方向倒**（返回 Blocked），不是向"放"。
 */
interface ActionSafetyPort {
    suspend fun review(query: ActionSafetyQuery): ActionSafetyOutcome
}
