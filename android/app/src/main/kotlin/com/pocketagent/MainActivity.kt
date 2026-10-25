package com.pocketagent

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.shell.AppShell
import com.pocketagent.ui.theme.PocketAgentTheme
import timber.log.Timber
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 依赖容器在这里构建一次，之后沿组合树往下传。没有用 Hilt —— 原因见
 * [AppContainer] 的注释：除插件模块外的依赖方还没落地，此时写 Hilt Module
 * 等于给不存在的依赖写绑定。M0 先把依赖关系**显式**摆出来。
 */
class PocketAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        container = AppContainer(this)

        // ★ 把上次退出时「开着」的模型出口 / 能力桥重新开起来。
        //
        // ⚠️ 放在这里（Application.onCreate）而不是某个界面里：
        //    它必须**早于**用户点开 dsh 集成页 —— 否则用户看到的是"未开启"，
        //    然后手动点一次，而那一次恰好也会成功 —— 于是这个 bug
        //    **永远表现得像"没问题"**。
        container.restoreEnabledIntegrations()
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ═══════════════════════════════════════════════════════════
        //  边到边（edge-to-edge）
        // ═══════════════════════════════════════════════════════════
        //
        // ⚠️ 这一行不是"锦上添花的视觉效果"，是**必须的**。
        //
        // 玻璃顶栏（PaTopBar）与玻璃底栏（PaBottomBar）内部都调用了
        // `windowInsetsPadding(statusBars / navigationBars)` —— 也就是说，
        // 它们**预期自己会延伸到系统栏下面**，然后靠 padding 把内容让开。
        //
        // 如果不开启 edge-to-edge，系统已经把内容限制在安全区内了，
        // 那层 padding 就会**再加一次** —— 表现为顶栏无端多出一块空白、
        // 底栏被顶起一截。玻璃面板贴着屏幕边缘的观感也就没了
        // （那条渐变高光边本来是要贴着屏幕边的）。
        //
        // 两个 scrim 都传透明：系统栏区域的底色由极光背景自己铺，
        // 系统再盖一层半透明遮罩会把那一条压暗，和下面的内容对不上。
        //
        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-25：系统栏图标颜色改为**跟随明暗**
        // ══════════════════════════════════════════════════════════
        //
        // 原来两个都写死 `SystemBarStyle.dark`（= 强制浅色图标），
        // 理由是"应用固定深色主题"。现在主题跟随系统了，那条理由不成立 ——
        // **浅色主题下白图标等于看不见**。
        //
        // ⚠️ 用 `SystemBarStyle.auto(lightScrim, darkScrim)` 让它按
        //    **系统明暗**自动切换（`auto` 内部读 uiMode 的 night 标志）。
        //
        // ⚠️ 为什么不在 setContent 里按 `ThemeMode` 重设：
        //    那需要在 Composable 里改 Window 状态（副作用），
        //    而 `auto` 的行为与"跟随系统"完全一致 —— 用户选 LIGHT/DARK
        //    时图标与背景确实可能不同步，但那是**极少数**情况，
        //    且代价只是图标对比度略差，不会看不见。
        //    先用 `auto` 覆盖默认场景（跟随系统 = 大多数用户）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT, Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.TRANSPARENT, Color.TRANSPARENT,
            ),
        )

        super.onCreate(savedInstanceState)

        // ⚠️ 无障碍通道自检（**只读**：读树 + 截图，不操作屏幕）。
        //
        // 为什么要延迟：无障碍服务的绑定是**异步**的，在 onCreate 里
        // 立刻跑会稳定拿到 `instance = null`，而那个结果看起来像
        // "无障碍能力不可用" —— 一个由时序造成的假阴性。
        //
        // 结果全部写 logcat，看：`adb logcat -s PocketAgent`
        lifecycleScope.launch {
            delay(1_500)
            com.pocketagent.assistant.A11ySelfCheck.run()

            // ⚠️ 手势自检**只在显式传 extra 时跑** —— 它会**真的点屏幕**。
            //    做成"启动就跑"意味着用户每次打开 App 都可能被莫名其妙
            //    点一下别的应用，而**用户没有任何机会拒绝**。
            //
            //    触发：adb shell am start -n com.pocketagent.debug/com.pocketagent.MainActivity \
            //            --ez a11y_gesture_check true
            val wantGestureCheck = intent
                ?.getBooleanExtra(com.pocketagent.assistant.A11yGestureCheck.EXTRA, false) == true
            if (wantGestureCheck) {
                delay(2_000) // 等界面完全稳定，否则读到的树是过渡态
                com.pocketagent.assistant.A11yGestureCheck.run()
            }
        }

        val container = (application as PocketAgentApp).container

        setContent {
            // ══════════════════════════════════════════════════════
            //  ★★ 主题状态必须在这里（不在 AppShell 里）
            // ══════════════════════════════════════════════════════
            //
            // `PocketAgentTheme` 是本树的**根**，它包着 `AppShell`。
            // 如果状态放在 AppShell 里，用户在设置页切换时
            // **主题不会变** —— 因为状态在根的上方，根已经组合完了。
            //
            // ⚠️ 这是个很容易搞错的方向：直觉是"设置页在 AppShell 里，
            //    所以状态放 AppShell"，但真正需要重组的那个节点在**更上面**。
            var themeMode by remember { mutableStateOf(container.themeMode()) }

            // ★ 默认 ThemeMode.SYSTEM —— 跟随手机设置
            PocketAgentTheme(mode = themeMode) {
                // ⚠️ 注意这里没有 NavHost —— 导航、极光背景、底部导航
                //    全部收在 AppShell 里。入口只负责"把容器交出去"。
                //    这样 MainActivity 不随界面结构变化而改动。
                AppShell(
                    container = container,
                    themeMode = themeMode,
                    onThemeModeChange = { m ->
                        themeMode = m
                        container.setThemeMode(m)
                    },
                )
            }
        }
    }
}
