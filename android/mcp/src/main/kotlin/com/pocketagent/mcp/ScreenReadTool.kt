package com.pocketagent.mcp

import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.PageKind
import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.SystemUiRegions
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `screen_read` —— 把"读屏"以 MCP 工具的形式暴露给 dsh。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 关卡顺序是安全设计的一部分（不可调换）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ① 敏感判定（包名 / 页面文本 / 控件形状）—— 一票否决
 *   ② PrivacyFilter 关卡（上传路径的唯一出口）—— 产出遮蔽计划
 *   ③ 序列化 —— 照计划执行遮蔽
 * ```
 *
 * 顺序错一步就是一次静默泄漏：
 * - 把 ③ 放到 ② 前面 → 输入框里刚打的半句话**已经进了响应体**；
 * - 把 ① 放到最后 → 支付页的节点已经发给了模型，"先看到再拦截"不叫拦截。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么拒绝走 [ToolOutcome.Refused] 而不是抛异常
 * ═══════════════════════════════════════════════════════════════
 *
 * 拒绝是**产品行为**，不是故障：模型需要拿到一句能对用户解释的话，
 * 并且知道"这条路别走了"。异常会把它降级成 `-32603 内部错误` ——
 * 用户看到"出错了"，而真正发生的是"安全策略正确地工作了一次"。
 */
class ScreenReadTool(
    private val reader: ScreenReaderPort,
    private val safety: ScreenSafetyPort,

    /**
     * 隐私关卡。
     *
     * ⚠️ `systemUi = unknown` 对**树路径**是安全的：`PrivacyFilter.review` 在
     *    `requiresScreenshot=false` 时于系统 UI 检查**之前**就返回（树没有像素）。
     *    这里显式写出来，免得将来有人以为"unknown 会丢树"而顺手改成别的值。
     */
    private val privacyFilter: PrivacyFilter = PrivacyFilter(systemUi = SystemUiRegions.unknown),
) : McpTool {

    override val name: String = "screen_read"

    override val description: String =
        "读取手机当前屏幕上的可见元素（无障碍树）。返回元素列表：文本、类型、坐标、可点击性，" +
            "用于了解用户当前在哪个界面、有哪些可操作项。" +
            "注意：只有文字结构、没有图像；敏感页面（支付/银行/密码/验证码）与隐私策略命中的内容会被安全拦截。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
        // ⚠️ 刻意**不写** additionalProperties:false —— 模型多送一个无害字段时，
        //    整次调用失败的代价大于"忽略它"的代价。参数校验写在工具内部（当前无参）。
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── 采集 ─────────────────────────────────────────────────
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto

            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}）。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务，然后重试。",
            )

            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── ① 敏感判定：一票否决（最高优先级，不看其它任何条件）──
        val verdict = safety.review(
            ScreenSafetyQuery(
                packageName = capture.packageName,
                visibleTexts = ScreenDerive.visibleTexts(capture),
                fields = ScreenDerive.inputFields(capture),
            ),
        )
        if (verdict is ScreenSafetyOutcome.Block) {
            // ⚠️ 文案里刻意带上"请如实告诉用户"：这条文本会进模型上下文，
            //    而模型对用户说什么，直接决定用户是"理解被拦"还是"觉得软件坏了"。
            return ToolOutcome.Refused(
                "${verdict.userMessage}（这是安全策略的最终结论，请不要重试读取；" +
                    "请把上面的原因如实告诉用户。）",
            )
        }

        // ── ② 隐私关卡：产出遮蔽计划（树的文本同样会带出正在打的字）──
        val plan = when (
            val outcome = privacyFilter.review(
                UploadRequest(
                    context = UploadContext(
                        providerId = PROVIDER_ID,
                        purpose = UploadPurpose.TASK_REASONING,
                    ),
                    requiresScreenshot = false,
                    // 敏感页面已在 ① 拦下；走到这里一定是普通页面。
                    pageKind = PageKind.NORMAL,
                    inputFieldRegions = ScreenDerive.inputFieldRegions(capture),
                ),
            )
        ) {
            is FilterOutcome.Dropped -> return ToolOutcome.Refused(outcome.reason)
            is FilterOutcome.Allowed -> outcome.plan
        }

        // ── ③ 序列化：照遮蔽计划执行（"照"是字面意思，不许二次判断）──
        return ToolOutcome.Success(
            text = ScreenSerializer.toText(capture, plan.mask),
            structured = ScreenSerializer.toStructured(capture, plan.mask),
        )
    }

    private companion object {
        /**
         * 计量归属：这条数据是发给 dsh 的。
         *
         * ⚠️ 与 `HttpGatewayServer.contextFor()` 里的 `Consumer.Dsh` 保持同一个口径 ——
         *    将来审计/用量汇总时，"dsh 读了多少屏"必须能一处对上。
         */
        const val PROVIDER_ID = "dsh"
    }
}
