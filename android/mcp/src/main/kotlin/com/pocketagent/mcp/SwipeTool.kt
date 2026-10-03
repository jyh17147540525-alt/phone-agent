package com.pocketagent.mcp

import kotlin.math.abs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `android_swipe` —— 滑动（滚动列表、翻页）。
 *
 * 与 [TapTool] 同一条流水线（采集 → 安全判定 → 频率闸 → 派发），
 * 少一步"目标解析"（滑动没有目标元素，只有起点终点）。
 *
 * ⚠️ 为什么滑动也要走安全判定：滑动是"对当前页面生效的动作"——
 *    在支付页上滑一下可能翻出付款确认、下拉刷新可能触发提交。
 *    页面级关卡（`:safety` 的第 1–4 步）必须照走。
 *
 * ⚠️ [MIN_DISTANCE_PX] 不是安全限制、是**可用性校验**：太短的滑动
 *    会被系统按点击处理（或直接无效），如实拒绝比"派发了一个不算数的动作"好。
 */
class SwipeTool(
    private val reader: ScreenReaderPort,
    private val safety: ActionSafetyPort,
    private val dispatch: ActionDispatchPort,
    /** 与 [TapTool] 共用同一个实例（见 `ActionRateLimiter` 注释）。 */
    private val rateLimiter: ActionRateLimiter,
) : McpTool {

    override val name: String = "android_swipe"

    override val description: String =
        "在屏幕上滑动（用于滚动列表、翻页）。给出起点 from 与终点 to（整数像素坐标，" +
            "从 screen_read 输出的屏幕尺寸与元素坐标里取），可选 durationMs（100–2000 毫秒，默认 300）。" +
            "命中安全策略的页面（支付/密码/验证码）会被拒绝，被拒绝时不要重试。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("from") {
                put("type", "object")
                put("description", "起点，形如 {\"x\":100,\"y\":900}（像素）")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                }
                putJsonArray("required") { add("x"); add("y") }
            }
            putJsonObject("to") {
                put("type", "object")
                put("description", "终点，形如 {\"x\":100,\"y\":300}（像素）")
                putJsonObject("properties") {
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                }
                putJsonArray("required") { add("x"); add("y") }
            }
            putJsonObject("durationMs") {
                put("type", "integer")
                put("description", "滑动时长（毫秒），100–2000，默认 300")
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "为什么滑动（可选，只进日志与审计）")
            }
        }
        putJsonArray("required") { add("from"); add("to") }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── ① 参数解析 ───────────────────────────────────────────
        val parsed = when (val result = parseArguments(arguments)) {
            is ParsedArgs.Bad -> return ToolOutcome.Failed(result.reason)
            is ParsedArgs.Ok -> result
        }

        // ── ② 采集（安全判定需要页面上下文；失败路径与 tap 一致）──
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto
            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}）。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务，然后重试。",
            )
            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── ③ 几何校验（需要屏幕尺寸，所以放在采集后）─────────────
        val outOfScreen = listOf(parsed.from, parsed.to).firstOrNull {
            it.x < 0 || it.x >= capture.width || it.y < 0 || it.y >= capture.height
        }
        if (outOfScreen != null) {
            return ToolOutcome.Failed(
                "坐标 (${outOfScreen.x}, ${outOfScreen.y}) 超出屏幕范围" +
                    "（当前屏幕 ${capture.width}×${capture.height}）。请从 screen_read 输出里重新取坐标。",
            )
        }
        val manhattan = abs(parsed.to.x - parsed.from.x) + abs(parsed.to.y - parsed.from.y)
        if (manhattan < MIN_DISTANCE_PX) {
            return ToolOutcome.Failed(
                "滑动距离太短（${manhattan} 像素，至少 $MIN_DISTANCE_PX）—— 太短的滑动会被系统" +
                    "当成点击。请加大距离，或改用 android_tap。",
            )
        }

        // ── ④ 安全判定（`:safety` 引擎；滑动也走完整页面关卡）────
        val verdict = safety.review(
            ActionSafetyQuery(
                packageName = capture.packageName,
                activityName = capture.activityName,
                actionType = "swipe",
                targetDescription = "滑动：(${parsed.from.x},${parsed.from.y}) → (${parsed.to.x},${parsed.to.y})",
                visibleTexts = ScreenDerive.visibleTexts(capture),
                inputFields = ScreenDerive.inputFields(capture),
                actionsInLastMinute = rateLimiter.countInLastMinute(),
            ),
        )
        when (verdict) {
            is ActionSafetyOutcome.Allowed -> Unit

            is ActionSafetyOutcome.Blocked -> return ToolOutcome.Refused(
                buildString {
                    append(verdict.userMessage)
                    append("（这是安全策略的最终结论，请不要重试这次滑动；")
                    if (verdict.canFallbackToManual) {
                        append("你可以把步骤讲给用户，请他手动完成。）")
                    } else {
                        append("请如实告诉用户，并请他重新确认接下来要做什么。）")
                    }
                },
            )

            is ActionSafetyOutcome.ConfirmationRequired -> return ToolOutcome.Refused(
                "这次滑动被安全策略拦截：它需要在用户确认后才能执行，而当前版本没有「用户确认」" +
                    "交互通道 —— 请如实告诉用户，并请他手动完成。不要重试。",
            )
        }

        // ── ⑤ 频率闸（紧前一步）───────────────────────────────────
        val decision = rateLimiter.tryAcquire()
        if (decision is ActionRateLimiter.Decision.Denied) {
            return ToolOutcome.Refused(
                "操作被暂停：${decision.reason}约 ${(decision.retryAfterMs + 999) / 1000} 秒后可继续。" +
                    "（这是安全设计：防止死循环与连点，不是故障。）",
            )
        }

        // ── ⑥ 派发 ───────────────────────────────────────────────
        return when (
            val outcome = dispatch.dispatch(
                AgentAction.Swipe(
                    from = parsed.from,
                    to = parsed.to,
                    durationMs = parsed.durationMs,
                    reason = parsed.reason,
                ),
            )
        ) {
            is DispatchOutcome.Dispatched -> ToolOutcome.Success(
                text = "滑动已派发（通道=${outcome.channel}，${outcome.latencyMs}ms）。" +
                    "注意：这只表示动作已交给系统，「不代表界面已滚动」—— 请用 screen_read 确认结果。",
                structured = buildJsonObject {
                    put("dispatched", true)
                    put("channel", outcome.channel)
                    put("latencyMs", outcome.latencyMs)
                },
            )

            is DispatchOutcome.Refused -> ToolOutcome.Refused(outcome.reason)
            is DispatchOutcome.Failed -> ToolOutcome.Failed(outcome.reason)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  参数解析
    // ═══════════════════════════════════════════════════════════

    private sealed interface ParsedArgs {

        data class Ok(
            val from: ScreenPoint,
            val to: ScreenPoint,
            val durationMs: Long,
            val reason: String?,
        ) : ParsedArgs

        data class Bad(val reason: String) : ParsedArgs
    }

    private fun parseArguments(args: JsonObject): ParsedArgs {
        val from = point(args["from"])
            ?: return ParsedArgs.Bad(
                "缺少 from（起点）。形如 {\"from\":{\"x\":100,\"y\":900},\"to\":{\"x\":100,\"y\":300}}，" +
                    "坐标是整数像素，从 screen_read 输出里取。",
            )
        val to = point(args["to"])
            ?: return ParsedArgs.Bad("缺少 to（终点）。格式同 from。")

        val durationMs = (args["durationMs"] as? JsonPrimitive)?.intOrNull?.toLong() ?: DEFAULT_DURATION_MS
        if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
            return ParsedArgs.Bad(
                "durationMs 必须在 $MIN_DURATION_MS–$MAX_DURATION_MS 之间（当前 $durationMs）。",
            )
        }

        val reason = (args["reason"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.take(200)

        return ParsedArgs.Ok(from, to, durationMs, reason)
    }

    private fun point(value: JsonElement?): ScreenPoint? {
        val obj = value as? JsonObject ?: return null
        val x = (obj["x"] as? JsonPrimitive)?.intOrNull ?: return null
        val y = (obj["y"] as? JsonPrimitive)?.intOrNull ?: return null
        return ScreenPoint(x, y)
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 300L
        const val MIN_DURATION_MS = 100L
        const val MAX_DURATION_MS = 2000L

        /** 滑动距离下限（曼哈顿距离，像素）。太短会被当成点击。 */
        const val MIN_DISTANCE_PX = 50
    }
}
