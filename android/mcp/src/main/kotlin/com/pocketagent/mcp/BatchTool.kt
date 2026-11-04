package com.pocketagent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `android_batch` —— 一次调用里连做几步。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的不是"点得快"，是"少一次往返模型"
 * ═══════════════════════════════════════════════════════════════
 *
 * 延迟账本（`docs/工程/agent能力扩张-方案.md` §9.5）里最大的一项是
 * **"每做一步就回一次模型"** —— 一次往返 800–3000ms，而手机侧真正花的时间
 * （一次 `input tap` 80–300ms）只是它的零头。所以"把五步拆成五次工具调用"
 * 慢的从来不是手机，是中间那五次模型往返。
 *
 * ⇒ 这个工具让模型说一次「点这里 → 等一下 → 点那里」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 但它**不**替模型看屏幕
 * ═══════════════════════════════════════════════════════════════
 *
 * 整批只采一次屏（用来解析 node_id / text 与做页面级安全判定），
 * 中间不重读。所以：
 * - 适合：**你已经知道点击顺序**的确定性流程（展开 → 选项 → 确认）；
 * - 不适合：每一步的结果决定下一步该点哪儿。那种情况请一步步来 ——
 *   本工具的失败语义只保证"停下来了"，不保证"停在你想的位置"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 安全关卡一步都不省
 * ═══════════════════════════════════════════════════════════════
 *
 * 每一小步都单独过 `:safety` 的判定与频率闸（不是"整批过一次"）——
 * 否则把一次危险点击塞进批中间就成了绕过手段。
 * 任一步被拒或失败：**立即停**，已经做过的不回滚（点都点了），
 * 结果里如实写出"做到第几步、停在哪一步、它说了什么"。
 */
class BatchTool(
    private val reader: ScreenReaderPort,
    private val safety: ActionSafetyPort,
    private val dispatch: ActionDispatchPort,
    /**
     * 与 `android_tap` / `android_swipe` **共用同一个**频率闸 ——
     * 各数各的话，批量能把总频率顶到三倍。
     */
    private val rateLimiter: ActionRateLimiter,
) : McpTool {

    override val name: String = "android_batch"

    override val description: String =
        "一次调用里连做几步动作（点按 / 滑动 / 输入 / 导航键），最多 $MAX_STEPS 步。" +
            "适合你已经知道点击顺序的确定性流程；不适合\"每步结果决定下一步\"。" +
            "整批只读一次屏，中间不重新读 —— 所以界面变化后剩下的步骤可能点错，" +
            "请在调用后用 screen_read 核对。任一步被安全策略拒绝或失败就立即停下，" +
            "已经做过的不会回滚。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("steps") {
                put("type", "array")
                put("description", "按顺序执行的动作，最多 $MAX_STEPS 步")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("op") {
                            put("type", "string")
                            putJsonArray("enum") {
                                add("tap"); add("swipe"); add("type"); add("key")
                            }
                        }
                        putJsonObject("nodeId") { put("type", "string") }
                        putJsonObject("bounds") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "integer") }
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "op=type 时是要写入的内容；op=tap 时是按文字定位的目标")
                        }
                        putJsonObject("x") { put("type", "integer") }
                        putJsonObject("y") { put("type", "integer") }
                        putJsonObject("fromX") { put("type", "integer") }
                        putJsonObject("fromY") { put("type", "integer") }
                        putJsonObject("toX") { put("type", "integer") }
                        putJsonObject("toY") { put("type", "integer") }
                        putJsonObject("durationMs") { put("type", "integer") }
                        putJsonObject("key") {
                            put("type", "string")
                            putJsonArray("enum") { add("back"); add("home"); add("recents") }
                        }
                    }
                    putJsonArray("required") { add("op") }
                }
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "整批是为了做什么（可选，只进日志与审计）")
            }
        }
        putJsonArray("required") { add("steps") }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val parsed = when (val result = parseSteps(arguments)) {
            is BatchArgs.Bad -> return ToolOutcome.Failed(result.reason)
            is BatchArgs.Ok -> result
        }

        // ── 整批只读一次屏 ────────────────────────────────────────
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto
            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}）。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务，然后重试。",
            )
            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── 频率闸：整批一次性占额度（见 tryAcquireBurst 的注释）──
        val quota = rateLimiter.tryAcquireBurst(parsed.steps.size)
        if (quota is ActionRateLimiter.Decision.Denied) {
            return stopped(emptyList(), 1, parsed.steps.size, rateText(quota), capture, refused = true)
        }

        val pageTexts = ScreenDerive.visibleTexts(capture)
        val pageFields = ScreenDerive.inputFields(capture)
        val done = mutableListOf<String>()

        parsed.steps.forEachIndexed { index, step ->
            val no = index + 1

            // ── 目标解析（tap / type 需要；在**同一份快照**上）──────
            val resolved = when (step) {
                is BatchStep.Tap -> when (val r = TargetResolver.resolve(step.spec, capture)) {
                    is ResolveOutcome.Ok -> r.target
                    is ResolveOutcome.Failed ->
                        return stopped(done, no, parsed.steps.size, r.reason, capture)
                }

                is BatchStep.Type -> step.spec?.let { spec ->
                    when (val r = TargetResolver.resolve(spec, capture)) {
                        is ResolveOutcome.Ok -> r.target
                        is ResolveOutcome.Failed ->
                            return stopped(done, no, parsed.steps.size, r.reason, capture)
                    }
                }

                is BatchStep.Swipe -> null
                is BatchStep.Key -> null
            }

            // ── 安全判定（每步都过，不合并）─────────────────────────
            val verdict = safety.review(
                ActionSafetyQuery(
                    packageName = capture.packageName,
                    activityName = capture.activityName,
                    actionType = step.actionType,
                    targetDescription = describeTarget(step, resolved),
                    visibleTexts = pageTexts,
                    inputFields = pageFields,
                    actionsInLastMinute = rateLimiter.countInLastMinute(),
                ),
            )
            when (verdict) {
                is ActionSafetyOutcome.Allowed -> Unit
                is ActionSafetyOutcome.Blocked ->
                    return stopped(done, no, parsed.steps.size, blockedText(verdict), capture, refused = true)

                is ActionSafetyOutcome.ConfirmationRequired ->
                    return stopped(done, no, parsed.steps.size, CONFIRMATION_TEXT, capture, refused = true)
            }

            // ── 派发 ──────────────────────────────────────────────
            val outcome = dispatch.dispatch(toAction(step, resolved, parsed.reason))
            when (outcome) {
                is DispatchOutcome.Dispatched ->
                    done += "第 $no 步（${step.label}）已派发（${outcome.channel}，${outcome.latencyMs}ms）"

                is DispatchOutcome.Refused ->
                    return stopped(done, no, parsed.steps.size, outcome.reason, capture, refused = true)

                is DispatchOutcome.Failed ->
                    return stopped(done, no, parsed.steps.size, outcome.reason, capture)
            }
        }

        return ToolOutcome.Success(
            text = buildString {
                append("已按顺序派发 ${done.size} 步。")
                append("⚠️ 这只表示动作都交给了系统，不代表界面已按预期变化 —— 请用 screen_read 确认结果。")
                append("\n").append(done.joinToString("\n"))
            },
            structured = buildJsonObject {
                put("total", parsed.steps.size)
                put("executed", done.size)
                put("stopped", false)
                putJsonArray("steps") { done.forEach { add(it) } }
            },
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  参数解析
    // ═══════════════════════════════════════════════════════════

    private sealed interface BatchArgs {
        data class Ok(val steps: List<BatchStep>, val reason: String?) : BatchArgs
        data class Bad(val reason: String) : BatchArgs
    }

    private fun parseSteps(args: JsonObject): BatchArgs {
        val array = args["steps"] as? JsonArray
            ?: return BatchArgs.Bad(
                "缺少 steps 参数。它是一组按顺序执行的动作，形如 " +
                    "[{\"op\":\"tap\",\"x\":100,\"y\":200},{\"op\":\"key\",\"key\":\"back\"}]。",
            )
        if (array.isEmpty()) return BatchArgs.Bad("steps 是空的 —— 这一步不用调用工具。")
        if (array.size > MAX_STEPS) {
            return BatchArgs.Bad(
                "steps 有 ${array.size} 步，超过上限 $MAX_STEPS。" +
                    "请拆成两次调用 —— 批次越长，界面变化后越容易点错。",
            )
        }

        val steps = array.mapIndexed { index, element ->
            val no = index + 1
            val obj = element as? JsonObject
                ?: return BatchArgs.Bad("第 $no 步不是对象。每一步必须是 {\"op\":…} 形式的对象。")
            when (val parsed = parseStep(obj, no)) {
                is StepParse.Bad -> return BatchArgs.Bad(parsed.reason)
                is StepParse.Ok -> parsed.step
            }
        }
        return BatchArgs.Ok(steps, reasonOf(args))
    }

    private sealed interface StepParse {
        data class Ok(val step: BatchStep) : StepParse
        data class Bad(val reason: String) : StepParse
    }

    private fun parseStep(obj: JsonObject, no: Int): StepParse {
        val op = (obj["op"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return StepParse.Bad("第 $no 步缺少 op。可选 tap / swipe / type / key。")

        return when (op) {
            "tap" -> when (val spec = parseSpec(obj, no)) {
                is SpecParse.Bad -> StepParse.Bad(spec.reason)
                is SpecParse.Ok -> StepParse.Ok(BatchStep.Tap(spec.spec, reasonOf(obj)))
            }

            "type" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
                    ?: return StepParse.Bad(
                        "第 $no 步（op=type）缺少 text。要清空输入框就传空串 \"\"。",
                    )
                if (text.length > MAX_TEXT) {
                    return StepParse.Bad("第 $no 步的 text 有 ${text.length} 字，超过上限 $MAX_TEXT。")
                }
                // 目标可选：不给就只发 ACTION_SET_TEXT 到当前焦点框
                val spec = if (obj.containsKey("nodeId") || obj.containsKey("x")) {
                    when (val s = parseSpec(obj, no)) {
                        is SpecParse.Bad -> return StepParse.Bad(s.reason)
                        is SpecParse.Ok -> s.spec
                    }
                } else {
                    null
                }
                StepParse.Ok(BatchStep.Type(spec, text, reasonOf(obj)))
            }

            "swipe" -> {
                val fromX = intOf(obj, "fromX", no, "swipe")
                    ?: return StepParse.Bad(missingCoord(no, "fromX"))
                val fromY = intOf(obj, "fromY", no, "swipe")
                    ?: return StepParse.Bad(missingCoord(no, "fromY"))
                val toX = intOf(obj, "toX", no, "swipe")
                    ?: return StepParse.Bad(missingCoord(no, "toX"))
                val toY = intOf(obj, "toY", no, "swipe")
                    ?: return StepParse.Bad(missingCoord(no, "toY"))
                val duration = (obj["durationMs"] as? JsonPrimitive)?.longOrNull ?: 300L
                StepParse.Ok(
                    BatchStep.Swipe(
                        from = ScreenPoint(fromX, fromY),
                        to = ScreenPoint(toX, toY),
                        durationMs = duration.coerceIn(1L, 60_000L),
                        reason = reasonOf(obj),
                    ),
                )
            }

            "key" -> {
                val raw = (obj["key"] as? JsonPrimitive)?.contentOrNull
                    ?: return StepParse.Bad("第 $no 步（op=key）缺少 key。可选 back / home / recents。")
                val key = GlobalKeyName.byId(raw)
                    ?: return StepParse.Bad(
                        "第 $no 步的 key「$raw」不认识。只支持 back / home / recents 三个导航键。",
                    )
                StepParse.Ok(BatchStep.Key(key, reasonOf(obj)))
            }

            else -> StepParse.Bad("第 $no 步的 op「$op」不认识。可选 tap / swipe / type / key。")
        }
    }

    private sealed interface SpecParse {
        data class Ok(val spec: TapTargetSpec) : SpecParse
        data class Bad(val reason: String) : SpecParse
    }

    private fun parseSpec(obj: JsonObject, no: Int): SpecParse {
        val nodeId = (obj["nodeId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (nodeId != null) {
            val bounds = parseBounds(obj["bounds"])
                ?: return SpecParse.Bad(
                    "第 $no 步给了 nodeId 但 bounds 缺失或格式不对（形如 [left, top, right, bottom]）。" +
                        "两者必须一起从 screen_read 输出原样抄 —— bounds 是派发前的位置校验。",
                )
            return SpecParse.Ok(TapTargetSpec.ByNodeId(nodeId, bounds))
        }

        val x = (obj["x"] as? JsonPrimitive)?.intOrNull
        val y = (obj["y"] as? JsonPrimitive)?.intOrNull
        if (x != null && y != null) return SpecParse.Ok(TapTargetSpec.ByCoord(x, y))
        if (x != null || y != null) {
            return SpecParse.Bad("第 $no 步的 x / y 只给了一个。坐标目标必须两个都给（像素）。")
        }

        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (text != null) {
            val exact = (obj["exact"] as? JsonPrimitive)?.booleanOrNull ?: false
            return SpecParse.Ok(TapTargetSpec.ByText(text, exact))
        }

        return SpecParse.Bad(
            "第 $no 步没有给目标。三种写法选一种：nodeId + bounds、x + y、或 text。",
        )
    }

    private fun intOf(obj: JsonObject, key: String, no: Int, op: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    private fun missingCoord(no: Int, key: String): String =
        "第 $no 步（op=swipe）缺少整数 $key。滑动需要 fromX/fromY/toX/toY 四个像素坐标。"

    private fun parseBounds(value: kotlinx.serialization.json.JsonElement?): ScreenRect? {
        val array = value as? JsonArray ?: return null
        if (array.size != 4) return null
        val nums = array.map { (it as? JsonPrimitive)?.intOrNull ?: return null }
        val rect = ScreenRect(nums[0], nums[1], nums[2], nums[3])
        if (rect.right < rect.left || rect.bottom < rect.top) return null
        return rect
    }

    private fun reasonOf(obj: JsonObject): String? =
        (obj["reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.take(200)

    // ═══════════════════════════════════════════════════════════
    //  映射与文案
    // ═══════════════════════════════════════════════════════════

    private fun toAction(step: BatchStep, resolved: ResolvedTarget?, reason: String?): AgentAction =
        when (step) {
            is BatchStep.Tap -> AgentAction.Tap(
                ref = resolved?.clickNode?.let {
                    ResolvedNodeRef(nodeId = it.nodeId, bounds = it.bounds)
                },
                point = resolved?.blindPoint,
                reason = step.reason ?: reason,
            )

            is BatchStep.Type -> AgentAction.TypeText(
                ref = resolved?.clickNode?.let {
                    ResolvedNodeRef(nodeId = it.nodeId, bounds = it.bounds)
                },
                point = resolved?.blindPoint,
                text = step.text,
                reason = step.reason ?: reason,
            )

            is BatchStep.Swipe -> AgentAction.Swipe(
                from = step.from,
                to = step.to,
                durationMs = step.durationMs,
                reason = step.reason ?: reason,
            )

            is BatchStep.Key -> AgentAction.GlobalKey(step.key, step.reason ?: reason)
        }

    private fun describeTarget(step: BatchStep, resolved: ResolvedTarget?): String? {
        val base = when (step) {
            is BatchStep.Tap -> "点击"
            is BatchStep.Type -> "输入"
            is BatchStep.Swipe -> "滑动"
            is BatchStep.Key -> "导航键 ${step.key.label}"
        }
        val parts = resolved?.candidates
            ?.flatMap {
                listOfNotNull(
                    it.text?.takeIf { t -> t.isNotBlank() },
                    it.contentDescription?.takeIf { d -> d.isNotBlank() },
                )
            }
            ?.distinct()
            .orEmpty()
        return (listOf(base) + parts).joinToString(" ").take(200)
    }

    private fun blockedText(blocked: ActionSafetyOutcome.Blocked): String = buildString {
        append(blocked.userMessage)
        append("（这是安全策略的最终结论，请不要重试这一步；")
        if (blocked.canFallbackToManual) {
            append("你可以把剩下的步骤讲给用户，请他手动完成。）")
        } else {
            append("请如实告诉用户，并请他重新确认接下来要做什么。）")
        }
    }

    private fun rateText(denied: ActionRateLimiter.Decision.Denied): String =
        "操作被暂停：${denied.reason}约 ${(denied.retryAfterMs + 999) / 1000} 秒后可继续。" +
            "（这是安全设计：防止死循环与连点，不是故障。）"

    /**
     * 停下来的统一出口。
     *
     * ⚠️ 文案必须让模型知道**屏幕已经变了**：前面的步骤真的做过了，不回滚。
     *    只报"失败了"会让它以为什么都没发生，接着按原计划再点一遍。
     */
    private fun stopped(
        done: List<String>,
        stepNo: Int,
        total: Int,
        reason: String,
        capture: ScreenCaptureDto,
        refused: Boolean = false,
    ): ToolOutcome {
        val text = buildString {
            append("批量动作在第 $stepNo 步（共 $total 步）停下：")
            append(reason)
            if (done.isEmpty()) {
                append(" 前面没有执行任何一步，界面没有被改动。")
            } else {
                append(" 已经执行过 ${done.size} 步，它们不会回滚 —— ")
                append("请先用 screen_read 看一下现在停在哪，再决定下一步。")
            }
            append(" （当前顶层包：")
            append(capture.packageName ?: "未知")
            append("）")
        }
        // 安全拦截与频率拦截是「终结结论」（Refused），通道/目标失败是「可排查」（Failed）——
        // 两者的排查方向与是否重试完全相反，不能合成一个。
        return if (refused) ToolOutcome.Refused(text) else ToolOutcome.Failed(text)
    }

    private companion object {
        /** 一批最多几步。再多就不是"少一次往返"，而是"一口气蒙着眼点"。 */
        const val MAX_STEPS = 12

        const val MAX_TEXT = 2000

        const val CONFIRMATION_TEXT =
            "这次操作被安全策略拦截：它属于需要「用户本人确认」的高危动作（支付、转账、删除等不可逆操作）。" +
                "当前版本没有「用户确认」交互通道，所以这类操作一律不代做 —— " +
                "请如实告诉用户，并请他手动完成。不要重试。"
    }
}

/** 批里的一步（解析完成后的形状）。 */
private sealed interface BatchStep {

    val label: String

    val actionType: String

    data class Tap(val spec: TapTargetSpec, val reason: String?) : BatchStep {
        override val label: String get() = "点击"
        override val actionType: String get() = "tap"
    }

    data class Type(val spec: TapTargetSpec?, val text: String, val reason: String?) : BatchStep {
        override val label: String get() = "输入"
        override val actionType: String get() = "type"
    }

    data class Swipe(
        val from: ScreenPoint,
        val to: ScreenPoint,
        val durationMs: Long,
        val reason: String?,
    ) : BatchStep {
        override val label: String get() = "滑动"
        override val actionType: String get() = "swipe"
    }

    data class Key(val key: GlobalKeyName, val reason: String?) : BatchStep {
        override val label: String get() = "导航键 ${key.label}"
        override val actionType: String get() = "key"
    }
}
