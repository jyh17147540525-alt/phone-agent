package com.pocketagent.mcp

/**
 * 一次「把能力桥接进 dsh」的完整会话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它把两件各自完备、但需要有人连起来的事串上
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   McpEndpoint        →  起一个 loopback MCP 服务器，拿到 (mcpUrl, token)
 *   DshMcpConfigPatch  →  把 (mcpUrl, token) 渲染成 dsh 能读的补丁草稿
 * ```
 *
 * 与 `DshGatewaySession` 同构（那是网关版的同一件事）——本项目吃过一次
 * 「两段各自完备、中间无人」的亏，所以粘合层必须显式存在而不是让 :app 临场拼。
 *
 * ⚠️ **它刻意不认识 Android**：服务器走 [McpEndpoint] 窄接口。
 *    于是"起服务 → 渲染草稿"整条链路能进离线验证器 —— 这条链路里
 *    错的后果全是静默的（渲染出一个 dsh 读不懂的 YAML、token 对不上、URL 拼错）。
 *
 * ⚠️ **投递不在本类**：把草稿写进手机容器的 `cordis.patch.yml` 需要跨 UID 写权限，
 *    那是网关那一期就未解决、也不该在这里新造机制的问题（见设计文档 §6）。
 *    本类只产出**内容**；"送到哪儿"由调用方负责。
 */
class McpBridgeSession(
    private val endpoint: McpEndpoint,
    /** dsh 侧命名空间的 `serverName`（决定工具名 `mcp__<serverName>__screen_read`）。 */
    private val serverName: String = DshMcpConfigPatch.DEFAULT_SERVER_NAME,
) {

    /**
     * 启动服务器并渲染配置草稿。
     *
     * 幂等：`McpEndpoint.start()` 已启动时不重新分配端口与 token
     * （渲染器对同一输入输出相同文本）。
     *
     * @return 见 [McpBridgeStartResult]。**不抛异常** —— 失败是业务状态。
     */
    fun start(): McpBridgeStartResult {
        if (!endpoint.start()) {
            return McpBridgeStartResult.NotStarted(
                "能力桥没能在 127.0.0.1 上启动。可能是端口被占用、或系统限制了本地监听。" +
                    "重启应用后重试；若一直失败，请把这条信息反馈给我们。",
            )
        }

        val patch = DshMcpConfigPatch(
            url = endpoint.mcpUrl,
            token = endpoint.token,
            serverName = serverName,
        )

        return McpBridgeStartResult.Running(
            mcpUrl = endpoint.mcpUrl,
            patchDraft = patch.draftDocument(),
        )
    }

    /**
     * 停掉服务器。
     *
     * ⚠️ 与 `DshGatewaySession.stop()` 同一条取舍：**不清理已投递的配置**。
     *    停掉之后那份补丁还指着已释放的端口 —— 但清理它需要（同样不存在的）
     *    容器写权限，而"配了连不上"比"配置凭空消失"更容易向用户解释。
     */
    fun stop() {
        endpoint.stop()
    }
}

/**
 * [McpBridgeSession.start] 的结果。两层分开的理由同 `DshGatewayStartResult`：
 * **"服务器没起来"与"草稿没渲染对"需要做的事不同**，合并成一个布尔值
 * 会让其中一种说不出话（渲染失败在类型系统上表现为异常，而不是这里的分支）。
 */
sealed interface McpBridgeStartResult {

    /**
     * 服务器**在跑**。
     *
     * @property patchDraft 渲染好的配置草稿全文（含用法说明注释）——
     *   调用方负责把它写到用户能取到的地方（应用私有草稿目录）。
     */
    data class Running(
        val mcpUrl: String,
        val patchDraft: String,
    ) : McpBridgeStartResult

    /** 服务器**没起来**。此时什么都没有产出，不能渲染一份指向不存在端口的配置。 */
    data class NotStarted(val reason: String) : McpBridgeStartResult
}
