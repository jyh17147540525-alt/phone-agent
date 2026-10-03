package com.pocketagent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `android_tap` —— 让 dsh 能"动手"的第一个工具。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 执行顺序（除参数解析外，全在 `call()` 里，不可调换）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *  ① 参数解析     —— 目标三选一（node_id / text / coordinates）+ 严校验
 *  ② 采集         —— 与 screen_read 同一条"如实"纪律
 *  ③ 目标解析     —— 在**本次快照**上解析（含可点击祖先上浮、跨快照位置校验）
 *  ④ 安全判定     —— 走 `ActionSafetyPort` → `:safety` 的 SafetyGuard.checkBeforeAction
 *                    （包名黑名单 → App 声明 → 页面文本 → 敏感控件 → 频率 → 危险动作）
 *  ⑤ 频率闸       —— 工具侧拟人节奏闸（20 次/分钟 + 防连点下限）
 *  ⑥ 派发         —— 走 `ActionDispatchPort` → `:action` 的 ActionDispatcher
 * ```
 *
 * ⚠️ ④ 在 ⑥ 之前是**安全设计的全部意义**：反过来就是"点完了才拦"。
 *    `TapToolTest` 用记录调用顺序的 stub 把这条钉死。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么安全判定不是本工具自己拼三道关卡
 * ═══════════════════════════════════════════════════════════════
 *
 * `:safety` 的 `DefaultSafetyGuard.checkBeforeAction` 已经实现了同语义的判定
 * （且顺序更长、理由都写在它的注释里、有自己的离线测试）。
 * 在本模块再拼一套 = 同一份安全策略实现两遍 —— 两份迟早漂移，
 * 而漂移的方向是**其中一份更松**。详见 `ActionSafetyPort` 的注释。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 盲点（坐标处无可识别节点）是有意的取舍
 * ═══════════════════════════════════════════════════════════════
 *
 * 坐标目标允许点在"没有节点的空白/自绘区域"上（关弹窗、画板类界面）。
 * 此时**只过了页面级安全关卡** —— 没有元素可做"目标校验"，也没有描述可做
 * 危险动作关键词匹配。成功文案与结构化输出都会如实标注这一点，
 * 不把它伪装成"一个普通点击"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 成功 ≠ 点到
 * ═══════════════════════════════════════════════════════════════
 *
 * `:action` 的 `ActionResult.Dispatched` 只表示"动作被派发了"，**不代表业务成功**
 * （那是 Verifier 的职责，本工具没有这一步）。成功文案必须把这句话带给模型，
 * 否则模型会以为点完了就结束了 —— 而按钮可能没响应、可能弹了新的对话框。
 */
class TapTool(
    private val reader: ScreenReaderPort,
    private val safety: ActionSafetyPort,
    private val dispatch: ActionDispatchPort,
    /**
     * 频率闸实例 —— 与 `SwipeTool` **共用同一个**（共享一个滚动窗口，
     * 否则两个工具各数各的，合起来可以跑到 40 次/分钟）。
     */
    private val rateLimiter: ActionRateLimiter,
) : McpTool {

    override val name: String = "android_tap"

    override val description: String =
        "点击手机屏幕上的一个元素。目标三选一：node_id（最精确，从 screen_read 输出里" +
            "原样抄 nodeId 与 bounds）、text（按文字找）、coordinates（坐标兜底）。" +
            "命中安全策略（支付/密码/验证码页面、危险动作按钮如「确认支付」）的点击会被拒绝，" +
            "被拒绝时不要重试。成功返回只代表动作已派发，请用 screen_read 确认结果。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "object")
                put(
                    "description",
                    "要点击的目标。kind=node_id 时必填 nodeId 与 bounds（一起从 screen_read 输出原样抄）；" +
                        "kind=text 时填 text（可用 exact 控制是否精确匹配）；" +
                        "kind=coordinates 时填 x 与 y（整数像素）",
                )
                putJsonObject("properties") {
                    putJsonObject("kind") {
                        put("type", "string")
                        putJsonArray("enum") { add("node_id"); add("text"); add("coordinates") }
                    }
                    putJsonObject("nodeId") { put("type", "string") }
                    putJsonObject("bounds") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                        put("description", "形如 [left, top, right, bottom]，与 nodeId 一起从 screen_read 输出抄")
                    }
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("exact") { put("type", "boolean") }
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                }
                putJsonArray("required") { add("kind") }
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "为什么点它（可选，只进日志与审计，不影响行为）")
            }
        }
        putJsonArray("required") { add("target") }
        // ⚠️ 同 screen_read：刻意不写 additionalProperties:false —— 模型多送一个
        //    无害字段时，整次调用失败的代价大于忽略它的代价。严格校验写在工具内部。
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── ① 参数解析 ───────────────────────────────────────────
        val parsed = when (val result = parseArguments(arguments)) {
            is ParsedArgs.Bad -> return ToolOutcome.Failed(result.reason)
            is ParsedArgs.Ok -> result
        }

        // ── ② 采集（与 screen_read 同款"如实"分支）──────────────
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto
            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}）。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务，然后重试。",
            )
            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── ③ 目标解析（在本快照上；失败即终止）─────────────────
        val target = when (val resolved = TargetResolver.resolve(parsed.spec, capture)) {
            is ResolveOutcome.Ok -> resolved.target
            is ResolveOutcome.Failed -> return ToolOutcome.Failed(resolved.reason)
        }

        // ── ④ 安全判定（`:safety` 的既有引擎，顺序在它内部）─────
        val verdict = safety.review(
            ActionSafetyQuery(
                packageName = capture.packageName,
                activityName = capture.activityName,
                actionType = "tap",
                targetDescription = describeTarget(target),
                visibleTexts = ScreenDerive.visibleTexts(capture),
                inputFields = ScreenDerive.inputFields(capture),
                actionsInLastMinute = rateLimiter.countInLastMinute(),
            ),
        )
        when (verdict) {
            is ActionSafetyOutcome.Allowed -> Unit

            is ActionSafetyOutcome.Blocked -> return ToolOutcome.Refused(blockedText(verdict))

            // v1 无"用户确认"通道：确认类一律降级为拒绝（见 ActionSafetyPort 注释）。
            is ActionSafetyOutcome.ConfirmationRequired ->
                return ToolOutcome.Refused(confirmationText())
        }

        // ── ⑤ 频率闸（在"确定要派发"的紧前一步 —— 见 ActionRateLimiter 注释）──
        val decision = rateLimiter.tryAcquire()
        if (decision is ActionRateLimiter.Decision.Denied) {
            return ToolOutcome.Refused(rateText(decision))
        }

        // ── ⑥ 派发 ───────────────────────────────────────────────
        val ref = target.clickNode?.let {
            ResolvedNodeRef(nodeId = it.nodeId, bounds = it.bounds, note = clickNote(target))
        }
        val outcome = dispatch.dispatch(
            AgentAction.Tap(
                ref = ref,
                point = target.blindPoint,
                reason = parsed.reason,
            ),
        )
        return mapOutcome(outcome, blind = target.blindPoint != null, note = clickNote(target))
    }

    // ═══════════════════════════════════════════════════════════
    //  参数解析（全部失败分支都要给"下一步"）
    // ═══════════════════════════════════════════════════════════

    private sealed interface ParsedArgs {

        data class Ok(val spec: TapTargetSpec, val reason: String?) : ParsedArgs

        data class Bad(val reason: String) : ParsedArgs
    }

    private fun parseArguments(args: JsonObject): ParsedArgs {
        val target = args["target"] as? JsonObject
            ?: return ParsedArgs.Bad(
                "缺少 target 参数。target 形如 {\"kind\":\"text\",\"text\":\"发送\"}、" +
                    "{\"kind\":\"coordinates\",\"x\":100,\"y\":200}、" +
                    "或 {\"kind\":\"node_id\",\"nodeId\":\"...\",\"bounds\":[l,t,r,b]}。" +
                    "请先从 screen_read 的输出里取目标。",
            )

        val kind = (target["kind"] as? JsonPrimitive)?.contentOrNull
            ?: return ParsedArgs.Bad("target.kind 缺失。必须是 node_id / text / coordinates 之一。")

        return when (kind) {
            "node_id" -> {
                val nodeId = (target["nodeId"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: return ParsedArgs.Bad("kind=node_id 需要 nodeId。请从 screen_read 输出的元素列表里原样抄。")
                val bounds = parseBounds(target["bounds"])
                    ?: return ParsedArgs.Bad(
                        "kind=node_id 需要 bounds（形如 [left, top, right, bottom]，与 nodeId 一起" +
                            "从 screen_read 输出抄）。它是派发前的位置校验 —— 没有它，界面一变就可能点到别的元素上。",
                    )
                ParsedArgs.Ok(TapTargetSpec.ByNodeId(nodeId, bounds), reasonOf(args))
            }

            "text" -> {
                val text = (target["text"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: return ParsedArgs.Bad("kind=text 需要非空的 text。")
                val exact = (target["exact"] as? JsonPrimitive)?.booleanOrNull ?: false
                ParsedArgs.Ok(TapTargetSpec.ByText(text, exact), reasonOf(args))
            }

            "coordinates" -> {
                val x = (target["x"] as? JsonPrimitive)?.intOrNull
                    ?: return ParsedArgs.Bad("kind=coordinates 需要整数 x。请从 screen_read 输出里取坐标（像素）。")
                val y = (target["y"] as? JsonPrimitive)?.intOrNull
                    ?: return ParsedArgs.Bad("kind=coordinates 需要整数 y。请从 screen_read 输出里取坐标（像素）。")
                ParsedArgs.Ok(TapTargetSpec.ByCoord(x, y), reasonOf(args))
            }

            else -> ParsedArgs.Bad("未知的 target.kind「$kind」。只支持 node_id / text / coordinates。")
        }
    }

    private fun parseBounds(value: JsonElement?): ScreenRect? {
        val array = value as? JsonArray ?: return null
        if (array.size != 4) return null
        val nums = array.map { (it as? JsonPrimitive)?.intOrNull ?: return null }
        val rect = ScreenRect(nums[0], nums[1], nums[2], nums[3])
        if (rect.right < rect.left || rect.bottom < rect.top) return null
        return rect
    }

    private fun reasonOf(args: JsonObject): String? =
        (args["reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.take(200)

    // ═══════════════════════════════════════════════════════════
    //  文案（对外字符串一律不含屏幕内容；理由同 screen_read）
    // ═══════════════════════════════════════════════════════════

    /**
     * 目标描述（供 `:safety` 的危险动作关键词匹配）。
     *
     * ⚠️ 只含**目标节点与可点击祖先**的文本/描述 —— 不是整页文本
     *    （页面文本走 `visibleTexts` 那条通道，两者职责不同、不要混）。
     *    盲点没有目标可描述 —— 返回 null，匹配引擎按"无目标"处理。
     */
    private fun describeTarget(target: ResolvedTarget): String? {
        if (target.blindPoint != null) return null
        val parts = target.candidates
            .flatMap {
                listOfNotNull(
                    it.text?.takeIf { t -> t.isNotBlank() },
                    it.contentDescription?.takeIf { d -> d.isNotBlank() },
                )
            }
            .distinct()
        return parts.joinToString(" ").take(200).ifBlank { null }
    }

    private fun blockedText(blocked: ActionSafetyOutcome.Blocked): String = buildString {
        append(blocked.userMessage)
        append("（这是安全策略的最终结论，请不要重试这次操作；")
        if (blocked.canFallbackToManual) {
            append("你可以把步骤讲给用户，请他手动完成。）")
        } else {
            append("请如实告诉用户，并请他重新确认接下来要做什么。）")
        }
    }

    /**
     * ⚠️ 刻意**不**回显目标文本：拒绝路径带出屏幕内容会制造一条泄漏路径
     *    （screen_read 的同款纪律，它的测试里钉着这条）。
     */
    private fun confirmationText(): String =
        "这次操作被安全策略拦截：它属于需要「用户本人确认」的高危动作（支付、转账、删除等不可逆操作）。" +
            "当前版本没有「用户确认」交互通道，所以这类操作一律不代做 —— " +
            "请如实告诉用户，并请他手动完成。不要重试。"

    private fun rateText(denied: ActionRateLimiter.Decision.Denied): String =
        "操作被暂停：${denied.reason}约 ${(denied.retryAfterMs + 999) / 1000} 秒后可继续。" +
            "（这是安全设计：防止死循环与连点，不是故障。请如实告诉用户，并确认任务是否卡住了。）"

    private fun mapOutcome(outcome: DispatchOutcome, blind: Boolean, note: String?): ToolOutcome =
        when (outcome) {
            is DispatchOutcome.Dispatched -> ToolOutcome.Success(
                text = buildString {
                    append("点击已派发（通道=").append(outcome.channel).append("，")
                        .append(outcome.latencyMs).append("ms）。")
                    append("注意：这只表示动作已交给系统，「不代表界面已按预期变化」—— 请用 screen_read 确认结果。")
                    if (blind) {
                        // ★ 评审补的硬约束（workbuddy，2026-10-03）：自绘界面可能是支付界面，
                        //   page 级判定挡不住「自绘 + 无文本命中」—— blind 是唯一信号，
                        //   它必须在文案里活下来，且要求模型对用户如实说明。
                        append("★ 本次是「盲点」点击：坐标处没有可识别元素，无法确认点到了什么——")
                        append("请务必如实告诉用户这是一次不确定的操作，并请他确认界面现状。")
                    }
                    note?.let { append("（").append(it).append("）") }
                },
                structured = buildJsonObject {
                    put("dispatched", true)
                    put("channel", outcome.channel)
                    put("latencyMs", outcome.latencyMs)
                    put("blind", blind)
                },
            )

            is DispatchOutcome.Refused -> ToolOutcome.Refused(outcome.reason)
            is DispatchOutcome.Failed -> ToolOutcome.Failed(outcome.reason)
        }

    private fun clickNote(target: ResolvedTarget): String? {
        val click = target.clickNode ?: return null
        val semantic = target.candidates.firstOrNull()
        return when {
            semantic != null && semantic.nodeId != click.nodeId ->
                "目标文本节点不可自点，已上浮到它的可点击父节点"

            !click.clickable ->
                "目标没有可点击标记，将按其位置派发一次点击"

            else -> null
        }
    }
}
