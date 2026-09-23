package com.pocketagent.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.design.AuroraBackground
import com.pocketagent.ui.design.PaBottomBar
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaNavItem
import com.pocketagent.ui.design.PaTransition
import com.pocketagent.ui.dsh.DshIntegrationScreen
import com.pocketagent.ui.dsh.DshIntegrationViewModel
import com.pocketagent.ui.importer.ImportScreen
import com.pocketagent.ui.importer.ImportViewModel
import com.pocketagent.ui.keys.KeysScreen
import com.pocketagent.ui.keys.KeysViewModel
import com.pocketagent.ui.market.MarketScreen
import com.pocketagent.ui.market.MarketViewModel
import com.pocketagent.ui.models.ModelsScreen
import com.pocketagent.ui.models.ModelsViewModel
import com.pocketagent.ui.plugins.InstalledPluginsViewModel
import com.pocketagent.ui.plugins.PluginsScreen
import com.pocketagent.ui.settings.SettingsScreen
import com.pocketagent.ui.settings.SettingsViewModel
import com.pocketagent.ui.sources.SourcesScreen
import com.pocketagent.ui.sources.SourcesViewModel
import com.pocketagent.ui.tasks.TasksScreen
import com.pocketagent.ui.tasks.TasksViewModel

/**
 * 应用外壳。
 *
 * ═══════════════════════════════════════════════════════════════
 *  职责边界（重要）
 * ═══════════════════════════════════════════════════════════════
 *
 * 外壳只做三件事，别的都不做：
 *   1. 画**唯一一层**极光背景
 *   2. 管底部导航的选中态
 *   3. 定义页面之间的转场动画
 *
 * ⚠️ **极光背景只在这一层画**。各个页面（[com.pocketagent.ui.design.PaScreen]）
 * 刻意不含背景 —— 转场时背景必须静止，只有内容在动。
 * 如果每页各画一层，两层背景交叉淡入会看到"背景闪一下"，
 * 而且转场会变成"整屏被替换"而不是"内容在切换"。
 */

/** 导航路由。常量而不是字符串字面量，免得改一处漏一处。 */
object Routes {
    // ── 顶层 tab（显示底部导航）──────────────────────────
    const val TASKS = "tasks"
    const val PLUGINS = "plugins"
    const val SETTINGS = "settings"

    // ── 二级页面（隐藏底部导航）──────────────────────────
    const val MARKET = "market"
    const val IMPORT = "import"
    const val SOURCES = "sources"
    const val KEYS = "keys"
    const val MODELS = "models"
    const val DSH = "dsh"
}

/**
 * 一个底部导航项。
 *
 * [owns] 是**归属路由集合**：该 tab 下挂的所有路由（含二级页面）。
 *
 * 为什么需要它：用户从"设置"进入"插件源"时，路由变成了 `sources`，
 * 但用户心里还在"设置"这个分支里。如果只按当前路由匹配，
 * 底栏的选中态会**掉到第一个 tab 上**，看起来像应用跳错了地方。
 * 有了 owns，二级页面期间选中态稳稳留在入口 tab 上。
 */
private class TabSpec(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val owns: Set<String>,
)

private val TABS = listOf(
    TabSpec(
        route = Routes.TASKS,
        label = "任务",
        icon = Icons.Default.AutoAwesome,
        owns = setOf(Routes.TASKS),
    ),
    TabSpec(
        route = Routes.PLUGINS,
        label = "插件",
        icon = Icons.Default.Extension,
        owns = setOf(Routes.PLUGINS, Routes.MARKET, Routes.IMPORT),
    ),
    TabSpec(
        route = Routes.SETTINGS,
        label = "设置",
        icon = Icons.Default.Tune,
        owns = setOf(Routes.SETTINGS, Routes.SOURCES, Routes.KEYS, Routes.MODELS, Routes.DSH),
    ),
)

@Composable
fun AppShell(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // ⚠️ 任务状态持有者提升到**外壳**这一层，而不是留在 TasksScreen 里。
    //    原因见 TasksScreen 的 viewModel 参数注释：它不继承 ViewModel，
    //    生命周期等于承载它的那次组合，而 destination 会在切 tab 时被移出组合。
    //    放这里 = 生命周期等于整个应用。
    val tasksViewModel = remember { TasksViewModel() }

    // 转场位移量：设计令牌给的是 dp，而 slideIn/slideOut 要的是像素。
    // 在这里换算一次，避免每帧在动画 lambda 里做 with(density) 转换。
    val density = LocalDensity.current
    val enterOffsetX = with(density) { PaTransition.ENTER_OFFSET_X.dp.roundToPx() }
    val exitOffsetX = with(density) { PaTransition.EXIT_OFFSET_X.dp.roundToPx() }

    /** 当前是否停在顶层 tab 上 —— 决定底栏显不显示 */
    val isTopLevel = TABS.any { it.route == currentRoute }

    /** 底栏选中项。二级页面归属其入口 tab，见 [TabSpec.owns] */
    val selectedIndex = TABS.indexOfFirst { currentRoute in it.owns }.coerceAtLeast(0)

    /**
     * 切 tab。
     *
     * ⚠️ 三个导航选项缺一不可：
     *   · `popUpTo(startDestination) { saveState = true }`
     *     —— 清掉中间栈，否则从"设置→插件源"再切"任务"，
     *        按返回键会回到"插件源"，而用户已经离开那个分支了。
     *   · `launchSingleTop` —— 连点同一个 tab 不会叠出多份实例。
     *   · `restoreState` —— 切回来时恢复该 tab 之前的滚动位置。
     */
    fun goToTab(route: String) {
        if (currentRoute == route) return
        navController.navigate(route) {
            popUpTo(Routes.TASKS) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    AuroraBackground {
        Column(modifier = Modifier.fillMaxSize()) {

            NavHost(
                navController = navController,
                startDestination = Routes.TASKS,
                modifier = Modifier.weight(1f),

                // ── 转场 ────────────────────────────────────────
                // 位移刻意很小（24dp），靠"淡入 + 轻微缩放"制造"内容浮现"的观感。
                // 大位移滑动在手机上会晕，而且会暴露真实的切换耗时 —— 见 PaTransition。
                //
                // 退出比进入**快得多**（180ms vs 380ms）：旧内容必须迅速让位，
                // 否则两层内容长时间叠在一起，看起来是"卡住了"而不是"在切换"。
                enterTransition = {
                    fadeIn(animationSpec = PaMotion.page()) +
                        slideInHorizontally(animationSpec = PaMotion.page()) { enterOffsetX } +
                        scaleIn(animationSpec = PaMotion.page(), initialScale = PaTransition.ENTER_SCALE)
                },
                exitTransition = {
                    fadeOut(animationSpec = PaMotion.fast()) +
                        scaleOut(animationSpec = PaMotion.page(), targetScale = PaTransition.EXIT_SCALE)
                },
                // 返回时方向相反：新页面从左侧的"深处"浮现，旧页面向右退出
                popEnterTransition = {
                    fadeIn(animationSpec = PaMotion.page()) +
                        scaleIn(animationSpec = PaMotion.page(), initialScale = PaTransition.EXIT_SCALE)
                },
                popExitTransition = {
                    fadeOut(animationSpec = PaMotion.fast()) +
                        slideOutHorizontally(animationSpec = PaMotion.page()) { exitOffsetX } +
                        scaleOut(animationSpec = PaMotion.page(), targetScale = PaTransition.ENTER_SCALE)
                },
            ) {

                // ── 任务 ────────────────────────────────────────
                composable(Routes.TASKS) {
                    TasksScreen(
                        viewModel = tasksViewModel,
                        onOpenSettings = { goToTab(Routes.SETTINGS) },
                    )
                }

                // ── 插件 ────────────────────────────────────────
                composable(Routes.PLUGINS) {
                    val vm: InstalledPluginsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { InstalledPluginsViewModel(container.installer) }
                        }
                    )
                    PluginsScreen(
                        viewModel = vm,
                        onOpenMarket = { navController.navigate(Routes.MARKET) },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                    )
                }

                // ── 设置 ────────────────────────────────────────
                composable(Routes.SETTINGS) {
                    // 徽章要反映真实状态，所以设置页也需要一个状态持有者。
                    // 见 SettingsViewModel 的注释：写死成"未配置"会在用户
                    // 配好 Key 之后变成一句假话。
                    val vm: SettingsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                SettingsViewModel(openStore = container::openCredentialStore)
                            }
                        }
                    )
                    val keyStatus by vm.keyStatus.collectAsStateWithLifecycle()

                    SettingsScreen(
                        keyStatus = keyStatus,
                        // ⚠️ 权限页**尚未实现**，传 null。
                        //    传 null 的效果是那一行右侧不画箭头 —— 用户一眼就知道进不去。
                        //    绝不能传 `{}`：箭头照画、点了没反应，用户会认为应用坏了。
                        onOpenKeys = { navController.navigate(Routes.KEYS) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                        onOpenDsh = { navController.navigate(Routes.DSH) },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                        onOpenPermissions = null,
                    )
                }

                // ── 二级页面 ────────────────────────────────────
                //
                // 三个二级页面都已迁移到 ui/design 组件，与顶层 tab 共用同一套
                // 骨架（PaScreen）与同一层极光背景 —— 转场时背景静止不动。
                //
                // ⚠️ 新页面一律用 ui/design 下的组件，**不要**再引入 Material3 的
                //    Scaffold / TopAppBar / Card：它们自带不透明背景，会盖住极光，
                //    也会破坏"背景静止、只有内容在动"的转场观感。

                composable(Routes.MARKET) {
                    val vm: MarketViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { MarketViewModel(container.subscriptions, container.installer) }
                        }
                    )
                    MarketScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
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

                composable(Routes.SOURCES) {
                    val vm: SourcesViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { SourcesViewModel(container.subscriptions) }
                        }
                    )
                    SourcesScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }

                // API Key 管理。
                //
                // ⚠️ ViewModel 通过两个挂起函数拿依赖，而不是直接吃 container。
                //    这样"存储打不开"的三条分支（Unrecoverable / Retryable /
                //    Ready）不需要真机 Keystore 就能单测 —— 而它们恰恰是最容易
                //    写错、也最难在真机上复现的路径。
                composable(Routes.KEYS) {
                    val vm: KeysViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                KeysViewModel(
                                    openStore = container::openCredentialStore,
                                    resetStore = container::resetCredentialStore,
                                )
                            }
                        }
                    )
                    KeysScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }

                // 模型配置。
                //
                // ⚠️ 与 Key 页共用同一把存储钥匙，所以这一页自己也走一遍
                //    openCredentialStore —— 它带缓存，从 Key 页过来时是零开销的。
                //    不把 Key 页打开的结果传过来，是因为两个页面在导航栈里
                //    **互不依赖**：用户可能从设置直接进模型页，此时存储还没开。
                //
                // modelRepository() 返回 null = 存储还没打开。那**不是**"没有模型"，
                // 见 AppContainer 的注释 —— 两者在界面上的说法完全不同。
                composable(Routes.MODELS) {
                    val vm: ModelsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                ModelsViewModel(
                                    openStore = container::openCredentialStore,
                                    modelRepository = container::modelRepository,
                                )
                            }
                        }
                    )
                    ModelsScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                        onOpenKeys = { navController.navigate(Routes.KEYS) },
                    )
                }

                composable(Routes.DSH) {
                    val vm: DshIntegrationViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                // ⚠️ 全部收成函数而不是把 `AppContainer` 整个交给
                                //    ViewModel —— 见 DshIntegrationViewModel 的注释：
                                //    开关状态**不属于**这个页面，它只是镜像容器里那一份。
                                //    把容器整个交进去，下一步就会有人在 ViewModel 里
                                //    自己存一个 `isRunning`，然后页面开始说假话。
                                DshIntegrationViewModel(
                                    readStatus = container::dshIntegrationStatus,
                                    startIntegration = container::startDshIntegration,
                                    stopIntegration = container::stopDshIntegration,
                                    readDraftSettings = container::dshDraftSettings,
                                    draftSettingsPath = container.dshDraftSettingsPath,
                                )
                            }
                        }
                    )
                    DshIntegrationScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── 底部导航 ────────────────────────────────────────
            //
            // 进二级页面时向下滑出，而不是"瞬间消失"。
            // 滑出的同时 NavHost 的 weight 会跟着变大，内容区**平滑扩展** ——
            // 因为 AnimatedVisibility 的高度是逐帧动画的，Compose 每帧重新测量。
            // 所以这里不会出现内容跳动。
            AnimatedVisibility(
                visible = isTopLevel,
                enter = slideInVertically(animationSpec = PaMotion.page()) { it } +
                    fadeIn(animationSpec = PaMotion.fast()),
                exit = slideOutVertically(animationSpec = PaMotion.page()) { it } +
                    fadeOut(animationSpec = PaMotion.fast()),
            ) {
                PaBottomBar(
                    items = TABS.map { PaNavItem(label = it.label, icon = it.icon) },
                    selectedIndex = selectedIndex,
                    onSelect = { goToTab(TABS[it].route) },
                )
            }
        }
    }
}
