package com.pocketagent

import android.app.Application
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.shell.AppShell
import com.pocketagent.ui.theme.PocketAgentTheme
import timber.log.Timber

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
        // 图标强制浅色（SystemBarStyle.dark）：应用固定深色主题（见 PocketAgentTheme），
        // 深底配深色图标等于看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        super.onCreate(savedInstanceState)

        val container = (application as PocketAgentApp).container

        setContent {
            PocketAgentTheme {
                // ⚠️ 注意这里没有 NavHost —— 导航、极光背景、底部导航
                //    全部收在 AppShell 里。入口只负责"把容器交出去"。
                //    这样 MainActivity 不随界面结构变化而改动。
                AppShell(container)
            }
        }
    }
}
