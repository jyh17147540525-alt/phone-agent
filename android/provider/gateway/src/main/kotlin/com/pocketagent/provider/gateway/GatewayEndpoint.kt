package com.pocketagent.provider.gateway

/**
 * 一个**可启停的 loopback 网关端点**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要有这个接口
 * ═══════════════════════════════════════════════════════════════
 *
 * [com.pocketagent.provider.gateway.http.HttpGatewayServer] 自己已经足够薄，
 * 但它**持有 ServerSocket** —— 而 socket 是"只有真机能验"的那一半
 * （见它的类注释）。任何需要"起一个网关并把配置送出去"的上层代码，
 * 如果直接依赖那个具体类，就会连带把 socket 拖进自己的测试。
 *
 * 上层真正需要的只有四件事：**地址、token、启动、停止**。
 * 收窄到这四件之后，会话逻辑（渲染 → 合并 → 投递）可以完全离线测。
 *
 * ⚠️ 这与本项目既有的做法一致：`CredentialDecryptor`（窄到只剩 `decrypt`）、
 *    `HttpGatewayServer` 收 `sanitize: (String)->String`、
 *    `RoutingBridge` 收 `modelById` 函数 —— **构造期拿不到或不想依赖的东西
 *    不要硬塞进构造器**。
 *
 * ⚠️ **接口放在本模块而不是上层模块**：反过来会让 `:provider:gateway`
 *    依赖上层（比如 `:keymgmt`），而那条依赖会把 Room 与 Keystore
 *    拖进网关 —— 它就再也进不了离线验证器了。
 */
interface GatewayEndpoint {

    /**
     * 供客户端使用的基址，形如 `http://127.0.0.1:12345/v1`。
     *
     * ⚠️ **`start()` 之前不可用**（此时端口还没分配）。实现方不要在这里
     *    返回一个"看起来合理"的默认值 —— 那会让上层把一份指向错误端口的
     *    配置写进 dsh，而症状是"dsh 连不上"，排查方向完全错。
     */
    val baseUrl: String

    /**
     * 本次启动的本地 token。
     *
     * ⚠️ **绝不可写进日志**。它只允许通过进程内传参进入配置文件
     *    （见 `HttpGatewayServer.start` 的注释）。
     */
    val token: String

    /**
     * 启动。已启动时返回 `true` 且**不重新分配端口/token**（幂等）。
     *
     * @return 是否成功。**不抛异常** —— 端口被占用、权限不足都是业务状态。
     */
    fun start(): Boolean

    /** 停止并释放端口。幂等。 */
    fun stop()
}
