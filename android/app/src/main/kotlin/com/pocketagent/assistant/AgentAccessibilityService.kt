package com.pocketagent.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 无障碍服务 —— **M0 实验器材，不是产品功能**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么现在就要有这个东西
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是 P0-2（EX-13「侧载应用的受限设置解除流程」）的**唯一实验器材**。
 *
 * Android 13 引入的受限设置机制，只在「应用试图启用一个无障碍服务」
 * 时才被触发。一个不声明无障碍服务的 APK，无论怎么侧载安装，
 * 都测不出这个机制 —— 因为我们连被拦截的资格都没有。
 *
 * 而 EX-13 是设计文档里**决定产品形态是否成立**的实验：
 * 若侧载场景下拿不到无障碍，整个「社区分发 + 无障碍读屏」的方案
 * 在 Android 13+ 上就不成立。
 *
 * 所以本类存在的理由不是"功能需要"，而是"没有它这个实验做不了"。
 * 这是「三个决定性实验未做前不写更多代码」这条纪律的**显式例外** ——
 * 写实验器材和执行那条纪律不冲突，恰恰相反，不写器材那条纪律就无法执行。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它刻意不做什么
 * ═══════════════════════════════════════════════════════════════
 *
 * - **不读屏幕内容**。`onAccessibilityEvent` 只记事件类型与包名，
 *   不碰 `rootInActiveWindow`，不取任何节点。
 * - **不执行任何操作**。不调用 `dispatchGesture`，不 `performAction`。
 * - **不缓存任何东西**。没有 UI 图谱、没有截图、没有落盘。
 *
 * 理由有两层：
 *
 * 1. **实验的有效性**。EX-13 要回答的是"权限能不能拿到"。
 *    如果这个服务同时还在读屏，那么实验失败时我们分不清是
 *    "权限没拿到"还是"拿到权限但读屏代码有问题"。
 *    变量必须只有一个。
 *
 * 2. **审计的诚实**。这个 APK 会被装到真机上，而真机上可能有
 *    用户的真实数据。一个"暂时不需要读屏、却已经能读屏"的构建，
 *    一旦被遗漏到某个分发给别人的包里，就是实打实的隐私事故。
 *    让它的能力与它的用途严格相等，是最省事的防线 ——
 *    因为"忘了删掉"这个错误届时不会有任何后果。
 *
 * 真正的感知层实现在 `:perception`，它落地时本类要么被替换、
 * 要么被改造成一个纯粹的通道壳。**在那之前，保持它什么都不做。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  怎么用它验证 EX-13
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * # 1. 看受限设置当前是什么模式（deny 就是被拦了）
 * adb shell cmd appops get com.pocketagent ACCESS_RESTRICTED_SETTINGS
 *
 * # 2. 侧载安装后，去系统设置里尝试开启本服务，记录是否被拦
 * #    设置 → 应用管理 → PocketAgent → 右上角 ⋮ → 允许受限设置
 *
 * # 3. 开启后确认系统真的把它绑上了
 * adb shell settings get secure enabled_accessibility_services
 * adb shell dumpsys accessibility | grep -A 5 PocketAgent
 * ```
 *
 * 这些命令 `tools/p0/run_p0.py` 已经封装好了，直接跑 `run P0-2`。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true
        // ⚠️ 这里**不能**打日志说"已获得读屏能力" —— 本类并没有读屏能力，
        //    那种日志会让人以为感知层已经就绪，进而跳过真正的实现工作。
        Timber.i("无障碍服务已连接（M0 探针，不读屏、不操作）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ⚠️ **刻意留空。**
        //
        // 不读 rootInActiveWindow、不取节点、不做任何缓存。
        // 这里唯一允许出现的是"证明事件确实在到达"的最低限度记录，
        // 而它连包名都不该记 —— 包名本身就属于用户行为信息。
        //
        // 如果将来有人想在这里加一行"顺手记一下当前应用"，
        // 那正是本类不该存在的那条线的另一边。
    }

    override fun onInterrupt() {
        // 无障碍反馈被系统中断。本服务不提供反馈，无需处理。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _connected.value = false
        Timber.i("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    companion object {
        /**
         * 服务当前是否被系统绑定。
         *
         * ⚠️ 这是一个**进程内**标志，不跨进程、不落盘。
         *    它的用途只有一个：让引导界面能显示"系统确实连上了"，
         *    而不是只能靠用户描述。
         *
         * ⚠️ 它**不能**用来判断"用户有没有开启权限" ——
         *    服务被系统绑定与权限被授予是两件事，前者会滞后。
         *    判断权限状态请读 `AccessibilityManager.getEnabledAccessibilityServiceList`。
         */
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
