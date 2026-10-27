package com.pocketagent.mcp

import com.pocketagent.hosting.HostBounds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `app_host` —— 让角色**在虚拟屏里操作被授权的应用**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-28：从"角落小窗"改成"虚拟屏"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话是硬的：
 *
 * > 角色操这些软件时……**都必须在虚拟屏里**，不能对用户使用手机造成干扰。
 * > 要让用户无感，不占用用户的屏幕。
 * > 还有虚拟屏执行时，**不能影响打扰到用户的主屏幕**。
 *
 * 上一版的 `host` 用的是 freeform 小窗 —— 那个**做不到**这一条：
 * 窗口真的画在用户屏幕上，只是缩到了角落。真机证据见
 * `:hosting` 的 `VirtualScreenCommands` 头注释。
 *
 * 现在 `start` 走的是 VirtualDisplay：目标应用跑在一块**用户看不见的屏**上，
 * 主屏的焦点不变。
 *
 * | 动作 | 作用 |
 * |---|---|
 * | `start` | 把应用拉到虚拟屏（需要 root） |
 * | `stop` | 清掉被托管的应用并释放虚拟屏 |
 * | `tap` / `swipe` / `type` / `key` | 在**虚拟屏坐标系**里操作 |
 * | `shot` | 取一帧画面（给模型看） |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 两条容易犯的错，工具层直接拦掉
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **坐标混用**。`tap` 的坐标是虚拟屏的（720×1280），不是主屏的
 *    （1440×3200）。混用不会报错，只会点到不相干的地方，然后模型
 *    以为"点对了但界面没变"。
 * 2. **一次调用做多件事**。仍然是拆开的：失败时要知道是哪一步错的。
 */
class AppHostTool(
    private val runner: AppHostingPort,
    private val screenSize: () -> Pair<Int, Int>,
) : McpTool {

    override val name: String = "app_host"

    override val description: String =
        "在**虚拟屏**里操作被授权的应用（QQ/微信/抖音等）。" +
            "这块屏用户看不见，也不会影响用户当前正在用的屏幕。" +
            "用法：action=start 把应用拉进虚拟屏 → action=tap/swipe/type/key 在里面操作 → " +
            "action=shot 取画面确认 → action=stop 收工释放。" +
            "⚠️ 需要 root。⚠️ tap 的坐标是**虚拟屏**的 ${VIRTUAL_W}×${VIRTUAL_H}，不是手机屏幕的坐标。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray {
                        ACTIONS.forEach { add(JsonPrimitive(it)) }
                    },
                )
                put(
                    "description",
                    "start=拉进虚拟屏 / stop=收工 / tap=点击 / swipe=滑动 / " +
                        "type=输入 / key=按键 / shot=取画面",
                )
            }
            putJsonObject("package") {
                put("type", "string")
                put("description", "要托管的包名，如 com.tencent.mm / com.tencent.mobileqq")
            }
            putJsonObject("x") { put("type", "integer"); put("description", "虚拟屏坐标 X（0..$VIRTUAL_W）") }
            putJsonObject("y") { put("type", "integer"); put("description", "虚拟屏坐标 Y（0..$VIRTUAL_H）") }
            putJsonObject("x2") { put("type", "integer"); put("description", "滑动终点 X") }
            putJsonObject("y2") { put("type", "integer"); put("description", "滑动终点 Y") }
            putJsonObject("text") { put("type", "string"); put("description", "要输入的文字") }
            putJsonObject("key") {
                put("type", "integer")
                put("description", "Android keycode，如 4=返回、3=Home、66=回车")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return ToolOutcome.Failed("缺少 action 参数（${ACTIONS.joinToString("/")}）")

        return map(runner.virtual(action = action, args = arguments))
    }

    private fun map(o: AppHostingPort.Outcome): ToolOutcome = when (o) {
        is AppHostingPort.Ok -> ToolOutcome.Success(o.text)
        // ⚠️ 「需要 root」走 Refused：它是**最终结论**，
        //    模型换一个应用再试也不会成 —— 那是用户要去处理的事。
        is AppHostingPort.NeedsRoot -> ToolOutcome.Refused(
            o.reason + " ⚡ 这是权限问题，**不要换个应用再试**；请告诉用户去授权。",
        )
        is AppHostingPort.Failed -> ToolOutcome.Failed(o.reason)
    }

    companion object {
        private val ACTIONS = listOf("start", "stop", "tap", "swipe", "type", "key", "shot")

        /** 虚拟屏尺寸 —— 与 `:app` 的 `VirtualScreenHost` 必须一致。 */
        const val VIRTUAL_W = 720
        const val VIRTUAL_H = 1280
    }
}

/**
 * 托管端口 —— `:mcp` 只认识这个形状，不认识 `:app` 的实现。
 *
 * ⚠️ 与 `:hosting` 的 `HostingResult` **分开放**：
 *    那个是纯逻辑模块的结果（零 Android），这个是 MCP 侧的端口。
 *    合并会把 `:hosting` 拖进 MCP 的类型世界，失去离线可测性。
 */
interface AppHostingPort {

    /**
     * **虚拟屏**上的一次操作。
     *
     * ★ 参数用 `JsonObject` 直传而不是展开成十个具名参数：
     *   展开的话，每加一个 action 都要改接口 + 适配器 + 三处测试。
     *   而这里每个 action 的参数形状本来就不同，硬统一只会产生一堆可空参数。
     *   ⚠️ 代价是参数校验的责任落在实现方 —— 所以实现方必须逐项校验，
     *   见 `:app` 的 `HostingAdapter`。
     */
    fun virtual(action: String, args: JsonObject): Outcome

    sealed interface Outcome
    data class Ok(val text: String) : Outcome
    data class Failed(val reason: String) : Outcome
    /** 需要 root 而当前没有 —— **用户要去处理**，模型换参数没用。 */
    data class NeedsRoot(val reason: String) : Outcome
}

/** 旧的小窗托管端口形状。保留给 `:hosting` 的既有调用方，新代码不要用。 */
@Deprecated("小窗托管看得见，不满足「不能打扰用户主屏」，改用 AppHostingPort.virtual")
interface LegacyAppHostingPort {
    fun host(packageName: String, bounds: HostBounds, screenW: Int, screenH: Int): AppHostingPort.Outcome
    fun release(packageName: String, screenW: Int, screenH: Int): AppHostingPort.Outcome
    fun tap(x: Int, y: Int): AppHostingPort.Outcome
    fun type(text: String): AppHostingPort.Outcome
}
