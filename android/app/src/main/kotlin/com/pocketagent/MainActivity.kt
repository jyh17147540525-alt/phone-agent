package com.pocketagent

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.home.HomeScreen
import com.pocketagent.ui.importer.ImportScreen
import com.pocketagent.ui.importer.ImportViewModel
import com.pocketagent.ui.market.MarketScreen
import com.pocketagent.ui.market.MarketViewModel
import com.pocketagent.ui.plugins.InstalledPluginsScreen
import com.pocketagent.ui.plugins.InstalledPluginsViewModel
import com.pocketagent.ui.theme.PocketAgentTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        super.onCreate(savedInstanceState)

        val container = (application as PocketAgentApp).container

        setContent {
            PocketAgentTheme {
                AppNavHost(container)
            }
        }
    }
}

/** 导航路由。常量而不是字符串字面量，免得改一处漏一处 */
private object Routes {
    const val HOME = "home"
    const val MARKET = "market"
    const val IMPORT = "import"
    const val PLUGINS = "plugins"
}

@Composable
private fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            // 已安装数量在进主页时读一次。放 IO 线程 —— 主线程读目录会让
            // 首页白屏一下，而那个卡顿会被理解成「这应用很慢」，不是「它在读磁盘」
            val installedCount by produceState<Int?>(initialValue = null) {
                value = withContext(Dispatchers.IO) { container.installer.installedIds().size }
            }

            HomeScreen(
                onOpenMarket = { navController.navigate(Routes.MARKET) },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
                onOpenPlugins = { navController.navigate(Routes.PLUGINS) },
                installedCount = installedCount,
            )
        }

        composable(Routes.MARKET) {
            // ViewModel 按路由作用域创建：离开市场页时它的状态会一起销毁。
            // 这对市场页是合适的 —— 下次进来本来就该重新拉一次源
            val vm: MarketViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { MarketViewModel(container.subscriptions, container.installer) }
                }
            )
            MarketScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenImport = { navController.navigate(Routes.IMPORT) },
            )
        }

        composable(Routes.IMPORT) {
            val vm: ImportViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ImportViewModel(container.installer) }
                }
            )
            ImportScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.PLUGINS) {
            // ⚠️ 这个 ViewModel 每次进页面都重新建（随路由作用域销毁），
            //    所以它会在 init 里重新扫一遍磁盘 —— 正是我们想要的：
            //    用户刚从市场装完插件退回来，列表必须已经是新的。
            val vm: InstalledPluginsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { InstalledPluginsViewModel(container.installer) }
                }
            )
            InstalledPluginsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenMarket = { navController.navigate(Routes.MARKET) },
            )
        }
    }
}
