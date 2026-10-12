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
 * `android_type` —— 往输入框里写文字。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它补的是能力桥最大的一处空白
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前能力桥**只能点击与滑动** —— 而"帮我搜一下 X"、"把地址填进去"
 * 这类任务的**第一步就是打字**。没有它，跨应用任务执行大部分走不通：
 * 能点开搜索框，却打不出一个字。
 *
 * 底层是 `ActionType.INPUT_TEXT`（`ACTION_SET_TEXT`），**替换**语义：
 * 写进去的内容就是输入框的全部内容。要清空就传空串。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 关卡顺序与 `android_tap` 完全一致（不可调换）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ① 读屏（拿页面上下文）→ ② 目标解析 → ③ 安全判定 → ④ 频率闸 → ⑤ 派发
 * ```
 *
 * ⚠️ ③ 在 ⑤ 之前：把"先写进去再判断该不该写"当拦截，不叫拦截 ——
 *    文字已经在输入框里了，而输入框的内容可能被下一个 App 读走。
 */
class TypeTextTool(
    private val reader: ScreenReaderPort,
    private val safety: ActionSafetyPort,
    private val dispatch: ActionDispatchPort,
    /** 与 `android_tap` / `android_swipe` **共用同一个实例** —— 见 ActionRateLimiter 的注释。 */
    private val rateLimiter: ActionRateLimiter,
) : McpTool {

    override val name: String = "android_type"

    override val description: String =
        "往一个输入框里写文字（搜索框、地址栏、聊天输入框…）。目标三选一：" +
            "node_id（最精确，从 screen_read 输出里原样抄 nodeId 与 bounds）、" +
            "text（按文字找）、coordinates（坐标兜底）。" +
            "语义是**替换**：写进去的就是输入框的全部内容，要清空就传空串 text。" +
            "命中安全策略（支付/密码/验证码页面）会被拒绝，被拒绝时不要重试。" +
            "成功返回只代表动作已派发，请用 screen_read 确认文字真的进去了。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("target") {
                put("type", "object")
                put(
                    "description",
                    "要写入的输入框。kind=node_id 时必填 nodeId 与 bounds（一起从 screen_read 输出原样抄）；" +
                        "kind=text 时填 text（可用 exact 控制是否精确匹配）；" +
                        "kind=coordinates 时填 x 与 y。",
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
                        put("description", "形如 [left, top, right, bottom]")
                    }
                    putJsonObject("text") { put("type", "string") }
                    putJsonObject("exact") { put("type", "boolean") }
                    putJsonObject("x") { put("type", "integer") }
                    putJsonObject("y") { put("type", "integer") }
                }
            }
            putJsonObject("text") {
                put("type", "string")
                // ⚠️ 「替换」这条语义**必须写在这里**（schema）而不只在工具描述里：
                //    模型最常读的是 schema。漏了它，模型会以为这是"追加"，
                //    然后在已有内容的框里打出重复文字。
                put(
                    "description",
                    "要写入的内容。**这是替换语义** —— 写进去的就是输入框的全部内容，" +
                        "不是追加。空串 = 清空该输入框。",
                )
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "你为什么要写这段文字（可选，用于审计）。")
            }
        }
        putJsonArray("required") { add("target"); add("text") }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── ① 参数解析 ───────────────────────────────────────────
        val spec = when (val parsed = TargetParsing.parseSpec(arguments["target"])) {
            is TargetParsing.Outcome.Bad -> return ToolOutcome.Failed(parsed.reason)
            is TargetParsing.Outcome.Ok -> parsed.spec
        }
        val text = arguments["text"]?.let { el ->
            (el as? JsonPrimitive)?.contentOrNull
        } ?: return ToolOutcome.Failed(
            "缺少 text 参数。要写入什么就传什么；清空输入框请传空串 \"\"。",
        )
        val reason = arguments["reason"]?.let { (it as? JsonPrimitive)?.contentOrNull }

        // ── ② 读屏（拿页面上下文）────────────────────────────────
        val capture = when (val outcome = reader.capture()) {
            is ScreenCaptureOutcome.Ok -> outcome.dto
            is ScreenCaptureOutcome.Unavailable -> return ToolOutcome.Failed(
                "读屏能力当前不可用（${outcome.reason}）。" +
                    "请确认已在系统设置中为本应用开启「辅助功能（无障碍）」服务，然后重试。",
            )
            is ScreenCaptureOutcome.Failed -> return ToolOutcome.Failed(outcome.reason)
        }

        // ── ③ 目标解析（在本快照上；失败即终止）──────────────────
        val target = when (val resolved = TargetResolver.resolve(spec, capture)) {
            is ResolveOutcome.Ok -> resolved.target
            is ResolveOutcome.Failed -> return ToolOutcome.Failed(resolved.reason)
        }

        // ── ④ 安全判定 ──────────────────────────────────────────
        //
        // ⚠️ **刻意不把 [text] 送进判定**：它是"要写的内容"，不是"动作指向哪里"。
        //    `ActionSafetyQuery.targetDescription` 的语义是后者（见该类型的注释：
        //    「动作指向元素的描述，如「底部的『确认支付』按钮」」）。
        //    把用户内容混进目标描述会让"危险动作"的判定被内容字符串污染 ——
        //    比如往搜索框打「确认支付」四个字，会被当成要点支付按钮。
        //    真正该拦"往敏感页面打字"的是**页面级判定**（本调用的其它字段）。
        val verdict = safety.review(
            ActionSafetyQuery(
                packageName = capture.packageName,
                activityName = capture.activityName,
                actionType = "type",
                targetDescription = describeTarget(target),
                visibleTexts = ScreenDerive.visibleTexts(capture),
                inputFields = ScreenDerive.inputFields(capture),
                actionsInLastMinute = rateLimiter.countInLastMinute(),
            ),
        )
        when (verdict) {
            is ActionSafetyOutcome.Allowed -> Unit
            is ActionSafetyOutcome.Blocked -> return ToolOutcome.Refused(
                "${verdict.userMessage}（这是安全策略的最终结论，请不要重试；" +
                    "请把上面的原因如实告诉用户。）",
            )
            is ActionSafetyOutcome.ConfirmationRequired -> return ToolOutcome.Refused(
                "这一步需要用户本人确认（${verdict.userMessage}）。" +
                    "当前版本没有「用户确认」通道，因此已按拒绝处理。" +
                    "请把这句话转达用户，并请他手动完成这一步。",
            )
        }

        // ── ⑤ 频率闸 ────────────────────────────────────────────
        val decision = rateLimiter.tryAcquire()
        if (decision is ActionRateLimiter.Decision.Denied) {
            return ToolOutcome.Refused(
                "操作太频繁（${decision.reason}）。请等几秒再试 —— " +
                    "这条限制是为了让操作节奏接近真人，也是为了让用户有机会接管。",
            )
        }

        // ── ⑥ 派发 ──────────────────────────────────────────────
        val ref = target.clickNode?.let {
            ResolvedNodeRef(nodeId = it.nodeId, bounds = it.bounds)
        }
        val outcome = dispatch.dispatch(
            AgentAction.TypeText(
                ref = ref,
                point = target.blindPoint,
                text = text,
                reason = reason,
            ),
        )

        return when (outcome) {
            is DispatchOutcome.Dispatched -> ToolOutcome.Success(
                // ⚠️ 刻意**不回显目标元素的文本**（同 TapTool 的纪律）——
                //    模型刚自己指定了目标，它不需要我们再复述一遍屏幕内容。
                text = if (text.isEmpty()) {
                    "已派发「清空输入框」（通道 ${outcome.channel}，${outcome.latencyMs}ms）。" +
                        "请用 screen_read 确认输入框确实空了。"
                } else {
                    "已派发「写入 ${text.length} 个字符」（通道 ${outcome.channel}，" +
                        "${outcome.latencyMs}ms）。" +
                        "⚠️ 这只代表动作已派发 —— **请用 screen_read 确认文字真的进去了**：" +
                        "有些输入框会拒绝程序写入（比如只认 IME 的控件）。"
                },
                structured = buildJsonObject {
                    put("dispatched", true)
                    put("channel", outcome.channel)
                    put("latencyMs", outcome.latencyMs)
                    put("textLength", text.length)
                },
            )

            is DispatchOutcome.Refused -> ToolOutcome.Refused(
                "${outcome.reason}（请把这句话转达用户。）",
            )

            is DispatchOutcome.Failed -> ToolOutcome.Failed(outcome.reason)
        }
    }

    /**
     * 给**安全引擎**看的元素描述。
     *
     * ⚠️ 与 `TapTool.describeTarget` 同一个口径：盲点（无候选节点）返回 null ——
     *    此时安全判定只能靠页面级信号，那是**如实**的表达，不是缺省值。
     *
     * ⚠️ 结果**只喂给安全引擎，不进工具返回值**：拒绝路径带出屏幕内容
     *    会制造一条泄漏路径（同 TapTool 的既有纪律）。
     */
    private fun describeTarget(target: ResolvedTarget): String? {
        if (target.blindPoint != null) return null
        return target.candidates
            .flatMap {
                listOfNotNull(
                    it.text?.takeIf { t -> t.isNotBlank() },
                    it.contentDescription?.takeIf { d -> d.isNotBlank() },
                )
            }
            .distinct()
            .joinToString(" ")
            .take(200)
            .ifBlank { null }
    }
}

/**
 * `android_key` —— 全局导航键（返回 / 回桌面 / 最近任务）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它**刻意不做安全判定**（与 tap / type 不同，这不是疏漏）
 * ═══════════════════════════════════════════════════════════════
 *
 * 在支付页上，「返回」是**把用户带出去**的动作，不是危险动作。
 * 拦它等于把人锁在支付页里 —— 方向正好反了。
 *
 * 而"危险"在本项目里的定义是**不可逆地做成一件事**（付款、删除、解绑）。
 * `BACK` / `HOME` / `RECENTS` 全都是**可逆的**：按错了再点回去就是了。
 *
 * ⇒ 所以本工具只过**频率闸**，不过安全判定。
 *    ⚠️ 将来若要加 `OPEN_APP`，那条**必须**过安全判定（打开 App 是不可逆的状态跳转，
 *    且能把用户带到支付页上）。**别照着本文件的样子加。**
 */
class GlobalKeyTool(
    private val dispatch: ActionDispatchPort,
    private val rateLimiter: ActionRateLimiter,
) : McpTool {

    override val name: String = "android_key"

    override val description: String =
        "按一个全局导航键：back（返回上一页）、home（回桌面）、recents（最近任务）。" +
            "用于：从当前页面退出去、回到桌面重新开始、在应用之间切换。" +
            "注意：这是**导航**动作，不受支付/密码页面的拦截 —— " +
            "在敏感页面上「返回」正是把你带出去的正确操作。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("key") {
                put("type", "string")
                put(
                    "description",
                    "要按的键。这是**导航**动作 —— 不受支付/密码页面的拦截：" +
                        "在敏感页面上按 back 正是把你带出去的正确操作。",
                )
                putJsonArray("enum") { GlobalKeyName.entries.forEach { add(it.id) } }
            }
            putJsonObject("reason") {
                put("type", "string")
                put("description", "你为什么要按这个键（可选，用于审计）。")
            }
        }
        putJsonArray("required") { add("key") }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val raw = arguments["key"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: return ToolOutcome.Failed("缺少 key 参数。")
        val key = GlobalKeyName.byId(raw) ?: return ToolOutcome.Failed(
            "不认识的键「$raw」。可用：" +
                GlobalKeyName.entries.joinToString("、") { "${it.id}（${it.label}）" } + "。",
        )
        val reason = arguments["reason"]?.let { (it as? JsonPrimitive)?.contentOrNull }

        val decision = rateLimiter.tryAcquire()
        if (decision is ActionRateLimiter.Decision.Denied) {
            return ToolOutcome.Refused(
                "操作太频繁（${decision.reason}）。请等几秒再试。",
            )
        }

        return when (val outcome = dispatch.dispatch(AgentAction.GlobalKey(key, reason))) {
            is DispatchOutcome.Dispatched -> ToolOutcome.Success(
                text = "已派发「${key.label}」（通道 ${outcome.channel}，${outcome.latencyMs}ms）。" +
                    "请用 screen_read 确认当前界面已经变了。",
                structured = buildJsonObject {
                    put("dispatched", true)
                    put("key", key.id)
                    put("channel", outcome.channel)
                    put("latencyMs", outcome.latencyMs)
                },
            )

            is DispatchOutcome.Refused -> ToolOutcome.Refused(outcome.reason)
            is DispatchOutcome.Failed -> ToolOutcome.Failed(outcome.reason)
        }
    }
}

/** 目标解析的共用部分（`android_tap` 与 `android_type` 的目标形状相同）。 */
internal object TargetParsing {

    sealed interface Outcome {
        data class Ok(val spec: TapTargetSpec) : Outcome
        data class Bad(val reason: String) : Outcome
    }

    /**
     * 解析 `target` 对象。
     *
     * ⚠️ 每个失败分支都要给"下一步" —— 模型读到"参数错误"只会再试一次同样的东西。
     */
    fun parseSpec(element: JsonElement?): Outcome {
        val obj = element as? JsonObject
            ?: return Outcome.Bad("缺少 target 参数。请给出要操作的元素（node_id / text / coordinates 三选一）。")
        return when (obj["kind"]?.jsonPrimitiveOrNull()) {
            "node_id" -> {
                val nodeId = obj["nodeId"]?.jsonPrimitiveOrNull()
                    ?: return Outcome.Bad("kind=node_id 时必须给 nodeId —— 从 screen_read 输出的节点里原样抄。")
                val bounds = obj["bounds"] as? JsonArray
                    ?: return Outcome.Bad("kind=node_id 时必须给 bounds —— 与 nodeId 一起从 screen_read 输出抄。")
                val nums = bounds.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                if (nums.size != 4) {
                    return Outcome.Bad("bounds 必须是四个整数 [left, top, right, bottom]，收到 ${nums.size} 个。")
                }
                Outcome.Ok(TapTargetSpec.ByNodeId(nodeId, ScreenRect(nums[0], nums[1], nums[2], nums[3])))
            }

            "text" -> {
                val text = obj["text"]?.jsonPrimitiveOrNull()
                    ?: return Outcome.Bad("kind=text 时必须给 text。")
                if (text.isBlank()) return Outcome.Bad("text 不能为空白。")
                Outcome.Ok(TapTargetSpec.ByText(text, obj["exact"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false))
            }

            "coordinates" -> {
                val x = obj["x"]?.let { (it as? JsonPrimitive)?.intOrNull }
                    ?: return Outcome.Bad("kind=coordinates 时必须给整数 x。")
                val y = obj["y"]?.let { (it as? JsonPrimitive)?.intOrNull }
                    ?: return Outcome.Bad("kind=coordinates 时必须给整数 y。")
                Outcome.Ok(TapTargetSpec.ByCoord(x, y))
            }

            else -> Outcome.Bad(
                "target.kind 必须是 node_id / text / coordinates 之一。" +
                    "建议优先用 node_id（最精确）：nodeId 与 bounds 一起从 screen_read 的输出里原样抄。",
            )
        }
    }

    private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
}
