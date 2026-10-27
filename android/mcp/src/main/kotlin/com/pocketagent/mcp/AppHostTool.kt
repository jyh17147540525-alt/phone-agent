package com.pocketagent.mcp

import com.pocketagent.hosting.HostBounds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `app_host` —— 让角色**在托管窗口里操作被授权的应用**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它与 `android_tap` / `android_type` 的根本区别
 * ═══════════════════════════════════════════════════════════════
 *
 * | | `android_tap` | `app_host` |
 * |---|---|---|
 * | 操作对象 | **用户当前看着的屏幕** | 一个缩在角落的托管窗口 |
 * | 用户感知 | 看得见自己的手机被点 | 大部分时候注意不到 |
 * | 失败后果 | 点错用户的东西 | 只影响那个窗口 |
 *
 * 用户的要求是"让用户无感、不占用屏幕"。`android_tap` 做不到那件事 ——
 * 它必然动用户的当前界面。所以这是**两个不同的工具**，不能合并。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一次调用只做一件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 不做「host + tap + type」的复合动作。理由：
 *  - 复合动作失败时**不知道哪一步错了**，而模型需要那个信息才能纠正
 *  - 用户中止时复合动作已经在半路，回滚不了
 *
 * ⇒ 拆成 `host` / `release` / `tap` / `type` 四个独立动作，
 *   模型按需组合，每一步的结果都独立可见。
 */
class AppHostTool(
    private val runner: AppHostingPort,
    private val screenSize: () -> Pair<Int, Int>,
) : McpTool {

    override val name: String = "app_host"

    override val description: String =
        "在**托管窗口**里启动或操作一个应用（QQ/微信/抖音等），不会占用用户当前屏幕。" +
            "用法：action=host 把应用启动到屏幕右上角的小窗；action=release 恢复全屏；" +
            "action=tap/type 在那个窗口里操作。" +
            "⚠️ 需要 root。⚠️ 小窗**仍然可见**（系统不允许完全隐藏），只是不挡用户。" +
            "操作前先用 screen_read 确认窗口里的界面。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put(
                    "enum",
                    kotlinx.serialization.json.buildJsonArray {
                        listOf("host", "release", "tap", "type").forEach {
                            add(JsonPrimitive(it))
                        }
                    },
                )
                put("description", "host=托管启动 / release=恢复全屏 / tap=点击 / type=输入")
            }
            putJsonObject("package") {
                put("type", "string")
                put("description", "要托管的包名，如 com.tencent.mm")
            }
            putJsonObject("x") { put("type", "integer"); put("description", "点击的屏幕 X 坐标") }
            putJsonObject("y") { put("type", "integer"); put("description", "点击的屏幕 Y 坐标") }
            putJsonObject("text") { put("type", "string"); put("description", "要输入的文字") }
        }
        put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("action")) })
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return ToolOutcome.Failed("缺少 action 参数（host/release/tap/type）")

        return when (action) {
            "host" -> {
                val pkg = arguments["package"]?.jsonPrimitive?.content
                    ?: return ToolOutcome.Failed("host 需要 package 参数")
                val (w, h) = screenSize()
                when (val r = runner.host(pkg, HostBounds.cornerOf(w, h), w, h)) {
                    is AppHostingPort.Ok -> ToolOutcome.Success(r.text)
                    // ⚠️ 「需要 root」走 Refused：它是**最终结论**，
                    //    模型换个包名再试也不会成 —— 那是用户要去做的事。
                    is AppHostingPort.NeedsRoot -> ToolOutcome.Refused(
                        r.reason + " ⚡ 这是权限问题，**不要换个应用再试**；请告诉用户去授权。",
                    )
                    is AppHostingPort.Failed -> ToolOutcome.Failed(r.reason)
                }
            }

            "release" -> {
                val pkg = arguments["package"]?.jsonPrimitive?.content
                    ?: return ToolOutcome.Failed("release 需要 package 参数")
                val (w, h) = screenSize()
                when (val r = runner.release(pkg, w, h)) {
                    is AppHostingPort.Ok -> ToolOutcome.Success(r.text)
                    is AppHostingPort.NeedsRoot -> ToolOutcome.Refused(r.reason)
                    is AppHostingPort.Failed -> ToolOutcome.Failed(r.reason)
                }
            }

            "tap" -> {
                val x = arguments["x"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return ToolOutcome.Failed("tap 需要整数 x")
                val y = arguments["y"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return ToolOutcome.Failed("tap 需要整数 y")
                when (val r = runner.tap(x, y)) {
                    is AppHostingPort.Ok -> ToolOutcome.Success(r.text)
                    is AppHostingPort.NeedsRoot -> ToolOutcome.Refused(r.reason)
                    is AppHostingPort.Failed -> ToolOutcome.Failed(r.reason)
                }
            }

            "type" -> {
                val text = arguments["text"]?.jsonPrimitive?.content
                    ?: return ToolOutcome.Failed("type 需要 text 参数")
                when (val r = runner.type(text)) {
                    is AppHostingPort.Ok -> ToolOutcome.Success(r.text)
                    is AppHostingPort.NeedsRoot -> ToolOutcome.Refused(r.reason)
                    is AppHostingPort.Failed -> ToolOutcome.Failed(r.reason)
                }
            }

            else -> ToolOutcome.Failed("未知 action：$action（可用：host/release/tap/type）")
        }
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
    fun host(packageName: String, bounds: HostBounds, screenW: Int, screenH: Int): Outcome
    fun release(packageName: String, screenW: Int, screenH: Int): Outcome
    fun tap(x: Int, y: Int): Outcome
    fun type(text: String): Outcome

    sealed interface Outcome
    data class Ok(val text: String) : Outcome
    data class Failed(val reason: String) : Outcome
    /** 需要 root 而当前没有 —— **用户要去处理**，模型换参数没用。 */
    data class NeedsRoot(val reason: String) : Outcome
}
