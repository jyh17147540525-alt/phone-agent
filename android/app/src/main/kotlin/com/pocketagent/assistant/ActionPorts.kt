package com.pocketagent.assistant

import android.graphics.Rect
import com.pocketagent.action.ActionDispatcher
import com.pocketagent.action.ActionResult
import com.pocketagent.action.ActionType
import com.pocketagent.action.ElementRef
import com.pocketagent.action.HumanizePolicy
import com.pocketagent.action.Point
import com.pocketagent.action.UiAction
import com.pocketagent.mcp.ActionDispatchPort
import com.pocketagent.mcp.ActionSafetyOutcome
import com.pocketagent.mcp.ActionSafetyPort
import com.pocketagent.mcp.ActionSafetyQuery
import com.pocketagent.mcp.AgentAction
import com.pocketagent.mcp.DispatchOutcome
import com.pocketagent.mcp.ScreenPoint
import com.pocketagent.mcp.ScreenRect
import com.pocketagent.safety.ActionContext
import com.pocketagent.safety.InputFieldSignature
import com.pocketagent.safety.SafetyGuard
import com.pocketagent.safety.SafetyVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 执行类 MCP 工具（`android_tap` / `android_swipe`）在 `:app` 侧的**全部接线**：
 * 安全判定端口 + 执行派发端口的两个适配器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 McpBridge.kt 的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * `McpBridge.kt` 是**读屏方向**的两根端口（ScreenReaderPort / ScreenSafetyPort）；
 * 本文件是**执行方向**的两根端口（ActionSafetyPort / ActionDispatchPort）。
 * 两条纪律完全相同：**只搬运、不判定** ——
 *
 * - 判定在 `:mcp`（编排 + 目标解析）与 `:safety`（安全检查引擎）里，都有离线测试；
 * - 本文件里任何"顺手过滤一下"都会制造一份规则之外的影子规则。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本文件不含"组装"
 * ═══════════════════════════════════════════════════════════════
 *
 * `ActionDispatcher` 的实例从哪来（无障碍执行器的实现 + 装配），以及把两个工具
 * 注册进 `ToolRegistry`，属于**真机接线**（WorkBuddy 侧，见 TO-WORKBUDDY 的
 * 「接线步骤」）。本文件只提供两个适配器类，供接线时 new 出来。
 */

/**
 * 安全端口适配器：`:mcp` 的查询形状 → `:safety` 的 [SafetyGuard.checkBeforeAction]。
 *
 * ⚠️ `executedSteps` 固定 0：那是审计字段（"已执行 N 步"），当前没有 agent 循环
 *    提供这个上下文；它不参与任何判定，如实填 0，不编造。
 */
class SafetyGuardAdapter(private val guard: SafetyGuard) : ActionSafetyPort {

    override suspend fun review(query: ActionSafetyQuery): ActionSafetyOutcome {
        val verdict = guard.checkBeforeAction(
            ActionContext(
                packageName = query.packageName,
                activityName = query.activityName,
                actionType = query.actionType,
                targetDescription = query.targetDescription,
                visibleTexts = query.visibleTexts,
                // 与 SafetyDetector 的既有纪律一致：只搬"形状"，不含已输入内容。
                inputFields = query.inputFields.map {
                    InputFieldSignature(
                        hint = it.hint,
                        className = it.className,
                        contentDescription = it.contentDescription,
                        isPassword = it.isPassword,
                        isEditable = it.isEditable,
                    )
                },
                executedSteps = 0,
                actionsInLastMinute = query.actionsInLastMinute,
            ),
        )
        return when (verdict) {
            is SafetyVerdict.Allowed -> ActionSafetyOutcome.Allowed
            is SafetyVerdict.Blocked -> ActionSafetyOutcome.Blocked(
                userMessage = verdict.userMessage,
                canFallbackToManual = verdict.canFallbackToManual,
            )
            is SafetyVerdict.RequireConfirmation ->
                ActionSafetyOutcome.ConfirmationRequired(verdict.userMessage)
        }
    }
}

/**
 * 执行端口适配器：`:mcp` 的 [AgentAction] → `:action` 的 [UiAction] → 派发 → 映射回来。
 *
 * ## 两个需要读注释才能理解的字段
 *
 * **[UiAction.safetyCleared] = true** —— `UiAction.isRisky` 靠它区分"裸动作"与
 * "已过安全判定"。本适配器收到的每个动作，都在工具层刚过完
 * `SafetyGuard.checkBeforeAction`（同一个 shield）—— 如实标注，**不是绕过**。
 * 若将来执行侧再挂一道 guard，注意别做双重确认/双重限流。
 *
 * **派发前的 [HumanizePolicy.randomInterval] 延迟** —— 审计确认过：这个函数
 * 此前**全仓零调用**（拟人化抽象定义了但没接线）。这里是它的调用点：
 * 每次派发前等一个随机的拟人间隔（300–800ms），让操作节奏接近真人。
 * 若将来决定在 dispatcher 内部统一做，把 `delay` 这一行挪走即可（只有这一处）。
 */
class ActionDispatchAdapter(private val dispatcher: ActionDispatcher) : ActionDispatchPort {

    override suspend fun dispatch(action: AgentAction): DispatchOutcome {
        val uiAction = when (action) {
            is AgentAction.Tap -> {
                val ref = action.ref
                if (ref != null) {
                    UiAction(
                        type = ActionType.CLICK,
                        targetDescription = action.reason,
                        elementRef = ElementRef.ByNodeId(ref.nodeId, ref.bounds.toRect()),
                        safetyCleared = true,
                    )
                } else {
                    val point = action.point
                        ?: return DispatchOutcome.Failed(
                            "工具内部错误：点击同时缺少节点引用与坐标（这是接线 bug，不该发生）。",
                        )
                    UiAction(
                        type = ActionType.TAP_COORD,
                        targetDescription = action.reason,
                        point = point.toPoint(),
                        safetyCleared = true,
                    )
                }
            }

            is AgentAction.Swipe -> UiAction(
                type = ActionType.SWIPE,
                targetDescription = action.reason,
                point = action.from.toPoint(),
                endPoint = action.to.toPoint(),
                durationMs = action.durationMs,
                safetyCleared = true,
            )
        }

        // 拟人化节奏（见类注释）。放在真正派发之前，失败路径不浪费这几百毫秒。
        delay(HumanizePolicy.randomInterval())

        val result = try {
            dispatcher.dispatch(uiAction)
        } catch (e: CancellationException) {
            throw e // 协程取消必须原样上抛，不许吞
        } catch (e: Exception) {
            // 「异常不得外抛」是 ActionExecutor 对实现的纪律；这里再兜一层 ——
            // MCP 工具链上任何泄漏的异常都会变成没有面向用户文案的 -32603。
            return DispatchOutcome.Failed(
                "执行通道抛出了异常（${e::class.simpleName ?: "未知"}）。" +
                    "这可能是一次临时故障，可以重试一次；若持续出现，请把这条信息交给开发者。",
            )
        }

        return when (result) {
            is ActionResult.Dispatched ->
                DispatchOutcome.Dispatched(result.channel.name, result.latencyMs)

            is ActionResult.NeedUserIntervention -> DispatchOutcome.Refused(
                "执行通道要求人工接手（原因：${result.reason.name}）：${result.message}" +
                    "请把这句话转达用户，并请他手动完成后续步骤。",
            )

            is ActionResult.Unsupported -> DispatchOutcome.Failed(
                "当前执行通道不支持这个动作（通道=${result.channel}）。" +
                    "请把这一情况告知用户：可能的成因是执行通道尚未接线完成。",
            )

            is ActionResult.ChannelUnavailable -> DispatchOutcome.Failed(
                "执行通道不可用（通道=${result.channel}，原因：${result.reason}）。" +
                    "请检查无障碍服务或 Shizuku 权限后重试。",
            )

            is ActionResult.TargetNotFound -> DispatchOutcome.Failed(
                "目标已失效（${result.description ?: "界面已变化"}）。" +
                    "请重新 screen_read 确认当前界面，再用新的定位信息重试。",
            )

            is ActionResult.Failed -> DispatchOutcome.Failed(
                "执行失败（通道=${result.channel}，原因：${result.reason}）。可重试一次；持续失败请告知开发者。",
            )
        }
    }
}

private fun ScreenRect.toRect(): Rect = Rect(left, top, right, bottom)

private fun ScreenPoint.toPoint(): Point = Point(x, y)
