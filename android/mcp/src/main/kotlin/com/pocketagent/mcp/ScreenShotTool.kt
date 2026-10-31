package com.pocketagent.mcp

import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.SystemUiRegions
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * **`screen_shot`** —— 把屏幕**真实的像素**交给模型。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：这是能力面上最大的一块缺口
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前模型只有 `screen_read`（无障碍树的**文字**），
 * 它看不到屏幕长什么样。而大量任务需要视觉：
 *
 * | 用户说 | 纯文本为什么不够 |
 * |---|---|
 * | "这个报错是什么意思" | 需要看那个红框和它的上下文 |
 * | "帮我点那个图标" | 树里图标常常只有 `contentDescription`，甚至为空 |
 * | "这个界面好看吗" | 根本没法回答 |
 * | "这个按钮是灰的吗" | 树的 `enabled` 不等于视觉上的灰 |
 *
 * ⇒ 新增这个工具。它与 `screen_read` **互补而不是替代**：
 *   · 树便宜（几 KB 文字）、精确（有坐标）、可操作
 *   · 像素贵（几百 KB）、模糊（要模型自己估坐标）、但能看懂"是什么"
 *
 * ⚠️ 所以描述里明确写"需要精确点击时用 screen_read" ——
 *   否则模型会一直用截图然后算错坐标。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 截图比 UI 树**敏感得多**，安全关卡必须更严
 * ═══════════════════════════════════════════════════════════════
 *
 * UI 树是结构化的（能逐字段过滤），而**像素不可过滤** ——
 * 一张截图里可能有验证码、聊天内容、支付金额、身份证照片。
 *
 * ⇒ 四道关卡（顺序不可调换）：
 *  1. **敏感判定**（`ScreenSafetyPort`，读屏后一票否决）
 *  2. **隐私过滤**（`PrivacyFilter`，`requiresScreenshot = true`）
 *     ⚠️ 与 `screen_read` 的关键区别：那边传 `false` 可以跳过像素检查；
 *        这边**必须**传 `true` —— 它的输出就是像素。
 *  3. 采集
 *  4. 体积上限
 */
class ScreenShotTool(
    /** 读屏端口 —— 用来做敏感判定。截图本身另有端口。 */
    private val reader: ScreenReaderPort,
    private val safety: ScreenSafetyPort,
    private val capturer: ScreenShotPort,
    /**
     * 隐私关卡的**工厂** —— 每次 `call()` 现造一个。
     *
     * ⚠️ **不能存实例**：`PrivacyFilter` 的 `systemUi` 是**屏幕坐标**，
     *    而用户可能把手机转成横屏 / 换了分屏比例。
     *    启动时量一次、之后一直用 ⇒ 转屏后裁剪矩形是错的，
     *    而错的方向是"该遮的没遮掉"（静默泄漏）。
     *
     * ★ 2026-10-31 从 `PrivacyFilter` 实例改成工厂：同一天发现
     *    `systemUi = unknown` 会让本工具**100% 拒绝**（`unknown` 的语义是
     *    "不知道系统栏在哪"，而 `PrivacyFilter` 对未知是保守丢弃整张图）。
     *    现在由 `:app` 侧的 `SystemUiProbe.detect` 每次真实量。
     */
    private val privacyFilter: () -> PrivacyFilter,
    /** 图片体积上限（字节）。超过就拒发。 */
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) : McpTool {

    override val name: String = "screen_shot"

    override val description: String = buildString {
        append("截取手机当前屏幕的图像，直接把像素给你看。")
        append("当你**需要看懂界面长什么样**时用它：")
        append("报错弹窗、图标形状、颜色/是否置灰、布局是否错位、「这个界面好看吗」。")
        append("\n\n")
        append("⚠️ 与 `screen_read` 的分工：")
        append("· 要**精确点击**某个元素 → 用 `screen_read`（它给准确坐标）")
        append("· 要**看懂内容/样子** → 用 `screen_shot`（像素没有坐标）")
        append("\n\n")
        append("敏感页面（支付/银行/密码/验证码/身份证）会被安全拦截，不返回图像。")
    }

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reason") {
                put("type", "string")
                put("description", "一句话说明为什么要看这张图（帮用户理解，也会进日志）")
            }
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val reason = (arguments["reason"] as? JsonPrimitive)?.content.orEmpty()

        // ── 关卡 ①：采集无障碍树（只为了做敏感判定）──────────
        //
        // ⚠️ 截图的敏感判定必须基于**当前页面的实际内容** ——
        //    光看包名不够（同一个 App 里既有聊天页也有支付页）。
        //    而读树是拿到那些信息的唯一途径。
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto
            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}），没法判断这个页面能不能截。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务。",
            )
            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── 关卡 ②：敏感判定 —— 一票否决 ─────────────────────
        val verdict = safety.review(
            ScreenSafetyQuery(
                packageName = capture.packageName,
                visibleTexts = ScreenDerive.visibleTexts(capture),
                fields = ScreenDerive.inputFields(capture),
            ),
        )
        if (verdict is ScreenSafetyOutcome.Block) {
            // ⚠️ 文案里带上"请如实告诉用户"：这条文本会进模型上下文，
            //    而模型对用户说什么，直接决定用户是"理解被拦"还是"觉得软件坏了"。
            return ToolOutcome.Refused(
                "这个页面上有不该外传的内容（${verdict.userMessage}），我不会截图。" +
                    "请如实告诉用户这件事，并建议他自己描述，或者先把敏感内容遮起来。",
            )
        }

        // ── 关卡 ③：隐私过滤（像素路径）─────────────────────
        //
        // ⚠️ `requiresScreenshot = true` 是关键差异 ——
        //    `screen_read` 传 false 时可以跳过像素检查（树没有像素），
        //    而这里必须跑完整检查。
        // ⚠️ `providerId` 传页面包名而不是模型标识：
        //    这个字段的用途是"按信任级别区分策略"，而截图场景下
        //    决定策略的是**页面属于谁**（银行 App vs 记事本）。
        val upload = UploadRequest(
            context = UploadContext(
                providerId = capture.packageName,
                // ★ `VISUAL_QA` 而不是 `TASK_REASONING`：
                //   枚举注释写明它是"送给模型做 OCR / 视觉问答" ——
                //   正是这个工具的用途。
                purpose = UploadPurpose.VISUAL_QA,
            ),
            requiresScreenshot = true,
            // ⚠️⚠️ 2026-10-31 修的第三个洞：这一行原来**没有**。
            //
            //    `PrivacyFilter` 的 `mask` 只从 `request.inputFieldRegions` 来，
            //    不传 ⇒ `plan.mask` 恒为空 ⇒ `maskInputFields = true` 也遮不住任何东西。
            //
            //    ⇒ 用户正在输入的**密码 / 验证码 / 手机号**会原样进图。
            //    而 `ScreenReadTool` 一直是传的（它传给了序列化器）——
            //    两个工具对同一个 `PrivacyFilter` 的用法不一致，
            //    漂移方向恰好是"更松"。
            //
            //    ⚠️ 这一行与 `ScreenReadTool` 的写法必须**保持一致**：
            //       `ScreenDerive.inputFieldRegions` 是"树 → 矩形"的唯一映射，
            //       两处各写一份就会在某个页面类型上漏掉。
            inputFieldRegions = ScreenDerive.inputFieldRegions(capture),
        )
        // ⚠️ 每次现造：`systemUi` 是屏幕坐标，转屏后旧值就是错的。
        //    工厂在 `:app` 侧（`SystemUiProbe.detect`）—— `:mcp` 不认识 Android。
        //
        // ⚠️⚠️ 2026-10-31 修的第二个洞：原来这里拿到 `Allowed` 就 `Unit`，
        //    **把 `plan` 直接丢了** —— 于是状态栏没人裁、输入框没人遮。
        //    对比 `ScreenReadTool`：它把 `plan.mask` 传给了 `ScreenSerializer`。
        //    ⇒ 现在把计划传下去，由 `ScreenShotPort` 执行（它才认识 Bitmap）。
        val plan = when (val review = privacyFilter().review(upload)) {
            is FilterOutcome.Dropped -> return ToolOutcome.Refused(
                "这个页面不适合截图外传（${review.reason}）。" +
                    "如果确实需要我看，请你自己描述，或者先遮住敏感内容。",
            )
            is FilterOutcome.Allowed -> review.plan
        }

        // ── 关卡 ④：采集像素（**按计划裁剪 / 遮蔽**）──────────
        val png = when (val outcome = capturer.capturePng(plan)) {
            is ScreenShotOutcome.Ok -> outcome.png
            is ScreenShotOutcome.Unavailable -> return ToolOutcome.Failed(
                "截图能力当前不可用（${outcome.reason}）。" +
                    "请确认已为本应用开启「屏幕录制」或等效权限，然后重试。",
            )
            is ScreenShotOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── 关卡 ⑤：体积 ──────────────────────────────────────
        if (png.size > maxBytes) {
            // ⚠️ 如实说清大小 —— 用户才知道"是图太大"而不是"功能坏了"。
            return ToolOutcome.Failed(
                "截图有 ${png.size / 1024}KB，超过上限 ${maxBytes / 1024}KB。" +
                    "换个简单点的界面再试，或者让我用 screen_read 读文字结构。",
            )
        }

        val note = if (reason.isNotBlank()) "（用途：${reason.take(60)}）" else ""
        return ToolOutcome.Success(
            text = "已截取当前屏幕（${png.size / 1024}KB）$note。" +
                "⚠️ 图里没有坐标 —— 需要精确点击请用 screen_read。",
            imageBytes = png,
            // ⚠️ 实际是 JPEG（见 `A11yScreenShotPort.compress`）——
            //    类型由产出方声明，`McpDispatcher` 原样转发。
            imageMimeType = ToolOutcome.DEFAULT_IMAGE_MIME,
        )
    }

    companion object {
        /**
         * 截图体积上限。
         *
         * ★ 1.5MB：base64 之后约 2MB，在多数厂商的请求体限制内（常见 10~20MB），
         *   而一张 1080p 的 PNG 截图通常 300KB~1.2MB。
         *
         * ⚠️ 不设更大：一张 5MB 的图 base64 后 7MB，加上对话历史很容易
         *    顶到厂商上限，而那个 413 错误对用户毫无意义。
         *
         * ⚠️ 也不设更小：截图里有文字，压缩太狠会让模型看不清
         *    （而"帮我看看写的什么"正是主要用途之一）。
         */
        const val DEFAULT_MAX_BYTES: Int = 1_500_000
    }
}

/** 截图端口 —— 与 `ScreenReaderPort` 同一个模式：`:mcp` 不认识 Android。 */
fun interface ScreenShotPort {
    /**
     * @param plan 必须**真的执行**（裁 `cropOut`、遮 `mask`）。
     *   ⚠️ 传了不执行 = 隐私关卡形同虚设，而且**不会报错**。
     */
    suspend fun capturePng(plan: RedactionPlan): ScreenShotOutcome
}

/** 一次截图的结果。三分支，理由同 `ToolOutcome`。 */
sealed interface ScreenShotOutcome {
    data class Ok(val png: ByteArray) : ScreenShotOutcome
    data class Unavailable(val reason: String) : ScreenShotOutcome
    data class Failed(val reason: String) : ScreenShotOutcome
}
