package com.pocketagent.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * **用户要不要动画** —— 尊重系统的两条无障碍设置。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这属于"丝滑"，而不只是"无障碍"
 * ═══════════════════════════════════════════════════════════════
 *
 * iOS 的动效之所以让人舒服，一个常被忽略的原因是：
 * **它在该不动的时候真的不动。**
 *
 *  · 开了「减弱动态效果」⇒ 上浮、缩放、视差全部关掉，只保留淡入
 *  · 开发者选项里把动画时长设成 0（很多人这么干）⇒ 立刻跳过
 *  · 系统省电模式 ⇒ 减少合成开销
 *
 * ⚠️ 反过来说：一个"无论用户怎么设置都坚持播完动画"的 App，
 *    在那些人手里就是**每次操作都慢半拍** —— 那是卡顿，不是丝滑。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个必须分开的信号
 * ═══════════════════════════════════════════════════════════════
 *
 * | 信号 | 含义 | 我们怎么反应 |
 * |---|---|---|
 * | `ANIMATOR_DURATION_SCALE == 0` | 用户把动画彻底关了 | **全部关掉**，直接显示 |
 * | `transition_animation_scale == 0` | 同上，但只针对窗口转场 | 页面转场改纯淡入 |
 * | 省电模式 | 系统在省电 | 保留动画但**去掉缩放**（缩放最贵） |
 *
 * ★ 三条都读，是因为它们的**语义不同**，不能合并成一个布尔：
 *   把"关掉动画"和"省电"当成同一件事，会导致省电时界面**硬切**——
 *   那是另一种粗糙。
 *
 * ⚠️ 读系统设置要 `ContentObserver` 而不是只在组合时读一次：
 *    用户在设置里改了，切回来必须立刻生效。
 *    只读一次的话，他得**杀进程重进**才看得到变化 —— 那正是"设置没生效"。
 */
object MotionPreference {

    /** 动画是否应该播放（最粗的那一档）。 */
    data class State(
        /** 系统动画总开关。false ⇒ 一切都别动。 */
        val animationsOn: Boolean = true,
        /** 是否保留缩放/位移这类"贵"的效果。省电时关掉，保留淡入。 */
        val richMotion: Boolean = true,
    ) {
        /** 最终判断：这个元素该不该做浮现动画。 */
        val shouldReveal: Boolean get() = animationsOn
    }

    private fun readScale(context: android.content.Context, key: String): Float =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, key, 1f)
        }.getOrDefault(1f)

    /**
     * 读一次当前状态。
     *
     * ⚠️ 用 `Settings.Global` 而不是 `Settings.System`：
     *    这三个动画倍率在 AOSP 里都挂在 Global 命名空间下。
     *    读错命名空间会恒返回默认值 1f —— 表现为"设置永远不生效"，
     *    而且不报错。
     */
    fun read(context: android.content.Context): State {
        val animator = readScale(context, Settings.Global.ANIMATOR_DURATION_SCALE)
        val transition = readScale(context, Settings.Global.TRANSITION_ANIMATION_SCALE)
        val powerSave = runCatching {
            context.getSystemService(android.content.Context.POWER_SERVICE)
                .let { it as? android.os.PowerManager }
                ?.isPowerSaveMode == true
        }.getOrDefault(false)

        val on = animator > 0f && transition > 0f
        return State(
            animationsOn = on,
            // ★ 省电时仍然允许淡入（几乎零成本），但关掉位移/缩放
            richMotion = on && !powerSave,
        )
    }
}

/**
 * 当前动效偏好。**默认全开** —— 读不到系统值时不该惩罚用户。
 *
 * ⚠️ 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：
 *    它变化极少（用户去系统设置改），而变化时**整屏都该重组**。
 *    用普通 compositionLocal 会带来"只有读它的那几个地方重组"的
 *    复杂度，换不到任何收益。
 */
val LocalMotionPreference = staticCompositionLocalOf { MotionPreference.State() }

/**
 * 在根部读系统设置并**持续监听**。
 *
 * 用法（AppShell 或 theme 根部）：
 * ```
 * val state = rememberMotionPreference()
 * CompositionLocalProvider(LocalMotionPreference provides state) { ... }
 * ```
 */
@Composable
fun rememberMotionPreference(): MotionPreference.State {
    val context = LocalContext.current
    var state by remember { mutableStateOf(MotionPreference.read(context)) }

    DisposableEffect(context) {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // ⚠️ 在 onChange 里直接重读：这三个值没有"变化后的新值"参数，
                //    只能重新查。开销可忽略（用户不会每秒改一次动画设置）。
                state = MotionPreference.read(context)
            }
        }
        val resolver = context.contentResolver
        // ⚠️ 三个 URI 都要注册。只注册一个的话，
        //    用户改的恰好是另外两个之一时，界面不会更新。
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false, observer,
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
                false, observer,
            )
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WINDOW_ANIMATION_SCALE),
                false, observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }

    return state
}
