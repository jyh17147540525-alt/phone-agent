package com.pocketagent.provider.gateway.dsh

import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.provider.gateway.GatewayCallException
import com.pocketagent.provider.gateway.GatewayFailure

/**
 * 给 [com.pocketagent.provider.gateway.http.HttpGatewayServer] 提供真实的路由模式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么必须有这个类，不能省
 * ═══════════════════════════════════════════════════════════════
 *
 * `HttpGatewayServer` 的 `routingModeFor` 默认实现是：
 *
 * ```kotlin
 * { error("未配置路由模式提供者") }
 * ```
 *
 * 那个默认值是**刻意会抛**的（见它的长注释）：如果给一个"看起来合理"的默认
 * （比如固定 `Single("")`），表现会是"所有请求都报没有可用模型"，
 * 而排查方向会跑到配置页去 —— 明明问题是**接线漏了**。
 *
 * 代价是：**不提供实现，网关一个请求都服务不了。** 本类就是那个实现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 两条规则的优先级（这是本类唯一的实质逻辑）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **用户显式选过 → 用他的选择**（[explicitMode]）。
 *    依据 `ModelRouteCoordinator.inferMode()` 的原文警告：
 *
 *    > ⚠️ 只有在"用户从没做过选择"时才该调用它
 *    > 用户一旦显式选了模式（存在偏好设置里），就必须用他的选择 ——
 *    > 每次启动都重新推断会把他的设定悄悄改回去，而他找不到地方改回来。
 *
 * 2. **没选过 → 推断**（[ModelRouteCoordinator.inferMode]）。
 *    它按"有几个可用 worker"决定：1 个 → `Single`，多个 → `Scheduled`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 推断失败必须转成"用户能照做的一句话"
 * ═══════════════════════════════════════════════════════════════
 *
 * `inferMode()` 在"还没配好"时返回 `null`（不抛 —— 这是它的契约，
 * "用户还没配模型"是正常业务状态）。
 *
 * ⚠️ 但**网关的 `routingModeFor` 签名返回的是非空 `RoutingMode`** ——
 *    所以 `null` 必须在这里被翻译掉。翻译成什么，决定了用户体验：
 *
 * | 做法 | dsh 侧看到什么 | 用户会去查什么 |
 * |---|---|---|
 * | `?: error("没有可用模型")` | `模型服务出错（IllegalStateException）` | 网关是不是坏了 |
 * | ✅ `throw GatewayCallException(NoModelConfigured)` | `还没有配置可用的模型，请先去「模型」页添加` | **配置页** ← 正确方向 |
 *
 * `GatewayFailure.NoModelConfigured` 的 `userMessage` 就是为这个场景写的。
 * 而 `HttpGatewayServer.describeFailure` 对 `GatewayCallException` 会取出
 * `failure.userMessage` —— 所以这句话会**原样送到 dsh 那边**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 刻意不缓存
 * ═══════════════════════════════════════════════════════════════
 *
 * 每次请求都重新推断。理由与 `ModelRouteCoordinator` 自己的"不缓存"一致：
 * 模型配置是"读极多、写极少"的数据，SQLite 那次查询是内存级开销；
 * 而缓存会引入"用户在配置页改了模型，但 dsh 还在按旧配置走"这种
 * **看不出原因的诡异行为** —— 用户会以为"改了没生效"。
 */
class DshRoutingModeProvider(
    private val coordinator: ModelRouteCoordinator,

    /**
     * 用户在 PocketAgent 界面里**显式选过**的路由模式。
     *
     * ⚠️ 返回 `null` 表示"他从没选过"，此时才轮到推断。
     *    这与 [ModelRouteCoordinator.inferMode] 的契约一致。
     *
     * ⚠️ 收成一个函数而不是构造期求值 —— 用户随时可能在另一个页面改它，
     *    而网关是长驻的（`start()` 一次，服务很久）。
     */
    private val explicitMode: suspend () -> RoutingMode? = { null },
) {

    /**
     * 当前应当使用的路由模式。
     *
     * @throws GatewayCallException 携带 [GatewayFailure.NoModelConfigured]，
     *         当用户还没配好任何可用模型时。**这不是异常路径而是正常业务状态** ——
     *         用异常只是因为 `routingModeFor` 的签名没有"失败"这一侧
     *         （与 `GatewayCore.complete` 用 `Flow` 内异常表达"还没开始就失败"
     *         是同一个理由）。
     */
    suspend fun current(): RoutingMode =
        explicitMode()
            ?: coordinator.inferMode()
            ?: throw GatewayCallException(GatewayFailure.NoModelConfigured)

    /**
     * 供 `HttpGatewayServer` 直接使用的函数形态。
     *
     * 用法：
     * ```kotlin
     * val server = HttpGatewayServer(
     *     core = core,
     *     routingModeFor = routingModeProvider.asProvider(),
     *     sanitize = logSanitizer::sanitize,
     *     log = { Timber.d(it) },
     * )
     * ```
     */
    fun asProvider(): suspend () -> RoutingMode = { current() }
}
