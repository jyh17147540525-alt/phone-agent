package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **跨模块契约常量的数值钉子**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：为什么这个文件要存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 项目里有几个常量，**注释明确写着"必须与另一个模块一致"**：
 *
 *   · AppHostTool 的 VIRTUAL_W / VIRTUAL_H
 *     —— 注释说"与 :app 的 VirtualScreenHost 必须一致"
 *   · McpServer 的 MCP_PATH
 *     —— 注释说"dsh 配置里的 URL 与这里必须一致"
 *
 * ⚠️ 但 `:app` **不在离线验证器里**（1205 处 Android 依赖），
 *    所以**没有任何测试能真正跨这两个模块比对**。
 *
 * ⇒ 退而求其次：**把可验证的那一侧钉死**。
 *    · 有人改了 `:mcp` 的数值 ⇒ 这里的测试立刻红，
 *      他会被迫去看 `:app` 那一侧要不要一起改
 *    · 有人只改了 `:app` ⇒ 这里发现不了（已写进"已知局限"）
 *
 * ★ 这与上一轮的教训是同一条：
 *   **一条"必须与 X 一致"的注释，如果没有测试，就只是一句愿望。**
 *   上一轮 `TokenCounter` 的注释预言了漂移、然后真的漂了 31%。
 */
class CrossModuleContractTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 虚拟屏尺寸：必须与 :app 的 VirtualScreenHost 一致
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `虚拟屏尺寸钉在 720x1280`() {
        // ══════════════════════════════════════════════════════
        //  ⚠️ 另一侧在 `:app` 的 `VirtualScreenHost.SCREEN_W/H`
        //     （2026-11-01 核对时两侧都是 720×1280）
        // ══════════════════════════════════════════════════════
        //
        // 为什么这个数值要紧：模型拿到的 `tap` 坐标是**虚拟屏坐标系**的，
        // 与主屏不同。两边尺寸不一致时不会报错 ——
        // 只会**点到不相干的地方**，然后模型以为自己点对了。
        assertEquals("必须与 VirtualScreenHost.SCREEN_W 一致", 720, AppHostTool.VIRTUAL_W)
        assertEquals("必须与 VirtualScreenHost.SCREEN_H 一致", 1280, AppHostTool.VIRTUAL_H)
    }

    @Test
    fun `虚拟屏是竖屏`() {
        // ⚠️ 宽的比高的还大意味着有人把两个值写反了 ——
        //    那会让所有 tap 的 X/Y 互换，而**不会报错**
        assertTrue(
            "虚拟屏应当是竖屏：${AppHostTool.VIRTUAL_W}×${AppHostTool.VIRTUAL_H}",
            AppHostTool.VIRTUAL_H > AppHostTool.VIRTUAL_W,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  MCP 端点
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `MCP 路径是 slash mcp`() {
        // ⚠️ dsh 配置里的 URL 由 `mcpUrl` 拼出来，而它用这个常量 ——
        //    所以改这里会同时改两边，是**单一来源**。
        //    钉住它是因为它是外部约定（dsh-mcp-client 的配置形态）。
        assertEquals("/mcp", McpHttpServer.MCP_PATH)
    }

    @Test
    fun `MCP 路径必须是绝对路径且无尾斜杠`() {
        val p = McpHttpServer.MCP_PATH
        assertTrue("必须从 / 开始：$p", p.startsWith("/"))
        assertTrue("不能以 / 结尾（拼接会出双斜杠）：$p", !p.endsWith("/"))
        assertTrue("路径里不该有空格：$p", !p.contains(' '))
    }

    @Test
    fun `回环地址是纯 IPv4 lo`() {
        // ══════════════════════════════════════════════════════
        //  ⚠️ 这里踩过坑，见 docs 里那条"端口判据"的记录
        // ══════════════════════════════════════════════════════
        // 绑 `127.0.0.1` 时，`/proc/net/tcp6` 会把它显示成
        // `::ffff:127.0.0.1`（IPv4-mapped）—— 那是**正常**的。
        // 但绑成 `::1`（纯 IPv6）时，dsh 侧用 IPv4 就连不上。
        assertEquals("127.0.0.1", McpHttpServer.LOOPBACK_HOST)
        assertTrue(
            "不能用纯 IPv6 回环（::1）—— dsh 用 IPv4 连：${McpHttpServer.LOOPBACK_HOST}",
            !McpHttpServer.LOOPBACK_HOST.contains(':'),
        )
    }

    @Test
    fun `mcpUrl 由回环地址与路径拼成`() {
        // 端口为 0 表示"还没启动" —— 此时返回空串而不是 `:0`
        assertEquals("", McpHttpServer(McpDispatcher(ToolRegistry(emptyList()))).mcpUrl)
    }

    // ═══════════════════════════════════════════════════════════
    //  已知局限（写在这里，免得下一个人以为这个文件管全了）
    // ═══════════════════════════════════════════════════════════

    /**
     * ⚠️ **这个文件只能守住一半。**
     *
     * 它能发现：`:mcp` 这一侧的常量被改了。
     * 它发现不了：`:app` 那一侧被改了（`:app` 不在离线验证器里，
     *   1205 处 Android 依赖）。
     *
     * ⇒ 真要闭合这个缺口，需要一个**构建期**检查
     *   （Gradle 任务读两边的源文件比对，或让 `:app` 依赖 `:mcp` 的常量
     *   而不是自己再写一份）。
     *
     * ★ 后者更彻底：`VirtualScreenHost` 直接用 `AppHostTool.VIRTUAL_W`，
     *   就**结构上不可能漂移**。但那要确认 `:app` 已经依赖 `:mcp` ——
     *   它确实依赖（要注册 MCP 工具）。这是下一步该做的事，
     *   本轮没做，因为设备被占用期间无法验证虚拟屏的真实行为。
     */
    @Test
    fun `记录一个尚未闭合的缺口`() {
        // 这个测试不做断言 —— 它是一个**带位置的注释**。
        // ⚠️ 保留空断言是为了让它在测试列表里可见；
        //    真正的价值在注释里。
        assertTrue(true)
    }
}
