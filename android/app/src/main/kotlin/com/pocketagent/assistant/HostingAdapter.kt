package com.pocketagent.assistant

import com.pocketagent.mcp.AppHostTool
import com.pocketagent.mcp.AppHostingPort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * `:mcp` 的 [AppHostingPort] → `:app` 的 [VirtualScreenHost]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么适配层在 `:app` 而不是 `:mcp`
 * ═══════════════════════════════════════════════════════════════
 *
 * `:mcp` 是**纯 Kotlin 模块**（要进离线验证器），它不能出现 `Context`、
 * `VirtualDisplay`、`ProcessBuilder`。所以那边只有端口，Android 的实现只能在这边。
 *
 * ⚠️ 参数校验的责任在这里（见 [AppHostingPort.virtual] 的注释）：
 *    端口签名是 `(action, JsonObject)`，编译器不管参数对不对。
 *    每一项都必须自己校验，**缺参数要报出来**，不能静默用默认值 ——
 *    静默默认值的后果是"点了 (0,0)"，而模型以为点对了。
 */
class HostingAdapter(private val host: VirtualScreenHost) : AppHostingPort {

    override fun virtual(action: String, args: JsonObject): AppHostingPort.Outcome {
        return try {
            when (action) {
                "start" -> {
                    val pkg = args.str("package")
                        ?: return AppHostingPort.Failed("start 需要 package 参数")
                    when (val err = host.launch(pkg)) {
                        null -> AppHostingPort.Ok(
                            "已把「$pkg」拉进虚拟屏（用户看不见，主屏没被影响）。" +
                                "下一步可以 tap/swipe/type 在里面操作，或 shot 看一眼。",
                        )
                        ROOT_MISSING -> AppHostingPort.NeedsRoot(err)
                        else -> AppHostingPort.Failed(err)
                    }
                }

                "stop" -> {
                    // ⚠️ 先清应用再释放屏 —— 顺序不能换（见 VirtualScreenHost.stop）
                    host.stop()
                    AppHostingPort.Ok("已收工：虚拟屏上的应用都关掉了，屏也释放了。")
                }

                "tap" -> {
                    val x = args.int("x") ?: return AppHostingPort.Failed("tap 需要整数 x")
                    val y = args.int("y") ?: return AppHostingPort.Failed("tap 需要整数 y")
                    host.tap(x, y)?.let { AppHostingPort.Failed(it) }
                        ?: AppHostingPort.Ok("已点击虚拟屏坐标 ($x,$y)")
                }

                "swipe" -> {
                    val x1 = args.int("x") ?: return AppHostingPort.Failed("swipe 需要整数 x")
                    val y1 = args.int("y") ?: return AppHostingPort.Failed("swipe 需要整数 y")
                    val x2 = args.int("x2") ?: return AppHostingPort.Failed("swipe 需要整数 x2")
                    val y2 = args.int("y2") ?: return AppHostingPort.Failed("swipe 需要整数 y2")
                    host.swipe(x1, y1, x2, y2)?.let { AppHostingPort.Failed(it) }
                        ?: AppHostingPort.Ok("已滑动 ($x1,$y1)→($x2,$y2)")
                }

                "type" -> {
                    val text = args.str("text")
                        ?: return AppHostingPort.Failed("type 需要 text 参数")
                    host.type(text)?.let { AppHostingPort.Failed(it) }
                        ?: AppHostingPort.Ok("已输入 ${text.length} 字")
                }

                "key" -> {
                    val code = args.int("key") ?: return AppHostingPort.Failed("key 需要整数 keycode")
                    host.key(code)?.let { AppHostingPort.Failed(it) }
                        ?: AppHostingPort.Ok("已按键 $code")
                }

                "shot" -> {
                    // ⚠️ 先分清"屏还没建"和"屏上没有画面"：真机上模型第一句就
                    //    调 shot 是常事，那时它得到"没有画面"会以为应用起不来，
                    //    于是反复 start —— 而真正该做的是先 start 一次。
                    if (host.displayId == null) {
                        return AppHostingPort.Failed(
                            "虚拟屏还没建起来 —— 先调用 action=start 把应用拉进去，再 shot。",
                        )
                    }
                    val png = host.snapshotPng()
                        ?: return AppHostingPort.Failed(
                            "这块屏上暂时还没有画面（应用可能还在冷启动，通常要 3～6 秒）。" +
                                "等一下再 shot。",
                        )
                    // ★ 顺手落一份到磁盘：这样"这块屏上真有内容"是**可以被查看**的，
                    //   而不是只有一句日志。路径随结果一起返回，便于排查。
                    val saved = runCatching { host.saveSnapshot() }.getOrNull()
                    AppHostingPort.Ok(
                        "取到一帧虚拟屏画面（${png.size} 字节 PNG，${AppHostTool.VIRTUAL_W}x${AppHostTool.VIRTUAL_H}）。" +
                            (saved?.let { "已存到 $it。" } ?: "") +
                            buildString {
                                val described = runCatching { host.describeScreen() }.getOrNull()
                                if (described != null) {
                                    appendLine()
                                    appendLine(described)
                                    appendLine()
                                    // ★★ 让角色**真的看到**画面。见 describeScreen 的注释：
                                    //    这一版给的是亮度布局图（任何模型都能用），
                                    //    不是多模态图片 —— 因为用户可能配的是纯文本模型。
                                    append(
                                        "⚠️ 上面是**亮度布局图**（越亮的字符越亮），不是文字识别结果。" +
                                            "它能告诉你哪里是标题栏、哪里是列表、哪里是大片空白" +
                                            "（多半还在加载）；**但它读不出界面上的字** —— " +
                                            "不要假装读到了文字内容。",
                                    )
                                } else {
                                    append(
                                        "⚠️ 这一版还不能把画面交给模型看 —— " +
                                            "你需要靠 tap/swipe 与已知的界面结构来操作；" +
                                            "不要凭想象描述画面内容。",
                                    )
                                }
                            },
                    )
                }

                else -> AppHostingPort.Failed("未知 action：$action")
            }
        } catch (t: Throwable) {
            Timber.e(t, "虚拟屏操作失败：%s", action)
            AppHostingPort.Failed("虚拟屏操作失败：${t.message ?: t::class.simpleName}")
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

    private companion object {
        /** `VirtualScreenHost.launch` 用这句表示"根本没 root"，需要走 NeedsRoot 通道。 */
        const val ROOT_MISSING = "root 命令没返回（su 不可用？）"
    }
}
