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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.mutableStateOf
import com.pocketagent.ui.theme.ThemeMode
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketagent.capability.SafDirectoryGrants
import com.pocketagent.capability.SafFileChannel
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.design.AuroraBackground
import com.pocketagent.ui.design.PaBottomBar
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaNavItem
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
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
import com.pocketagent.ui.capability.CapabilityScreen
import com.pocketagent.ui.capability.CapabilityViewModel
import com.pocketagent.ui.permissions.PermissionsScreen
import com.pocketagent.ui.permissions.PermissionsViewModel
import com.pocketagent.ui.plugins.InstalledPluginsViewModel
import com.pocketagent.ui.plugins.PluginsScreen
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootProbeResult
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.persona.PersonaScreen
import com.pocketagent.ui.settings.PrivilegeModeScreen
import com.pocketagent.ui.settings.SettingsScreen
import com.pocketagent.ui.settings.SettingsViewModel
import com.pocketagent.ui.sources.SourcesScreen
import com.pocketagent.ui.sources.SourcesViewModel
import com.pocketagent.ui.tasks.ContainerChatPort
import com.pocketagent.ui.tasks.SessionDrawer
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

    /** 运行模式（root / 非 root）与能力边界。 */
    const val PRIVILEGE_MODE = "privilege-mode"

    /** 角色设定（自定义角色 / 未亡人模式入口）。 */
    const val PERSONA = "persona"

    // ── 二级页面（隐藏底部导航）──────────────────────────
    const val MARKET = "market"
    const val IMPORT = "import"
    const val SOURCES = "sources"
    const val KEYS = "keys"
    const val MODELS = "models"
    const val DSH = "dsh"
    const val PERMISSIONS = "permissions"
    // 第 0 档能力的入口。它是"用户真的能用一次"的那一页 ——
    // 在此之前裁决/规划/通道/编排四层都有测试，但没有任何界面能调用它们。
    const val CAPABILITIES = "capabilities"
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
        owns = setOf(
            Routes.SETTINGS, Routes.SOURCES, Routes.KEYS,
            Routes.MODELS, Routes.DSH, Routes.PERMISSIONS, Routes.CAPABILITIES,
            Routes.PRIVILEGE_MODE, Routes.PERSONA,
        ),
    ),
)

@Composable
fun AppShell(
    container: AppContainer,
    /**
     * 当前明暗偏好。
     *
     * ⚠️ 状态**不属于本函数** —— 它在 `MainActivity` 里，因为
     *    `PocketAgentTheme`（真正需要重组的节点）在更上面。
     *    这里只负责显示与回传。
     */
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // ⚠️ 对话端口在这里建一次并复用。
    //    它本身无状态（每次 send 都重新取凭据、用完清零），但**重复构造会让
    //    remember 的 key 变化 → 重建 ViewModel → 丢掉整个任务列表**。
    val chatPort = remember(container) { ContainerChatPort(container) }


    // ── 首次启动引导 ────────────────────────────────────────────────
    // ★ 只在**第一次**打开 App 时弹一次。
    //
    // ⚠️ 判据是一个**显式标记**，不是「权限齐不齐」——
    //    后者会让用户每少开一个权限就被重新弹一次，那是骚扰，不是引导。
    // ⚠️ `initialValue = true`：首帧先当成「看过」，等偏好读回来再决定弹不弹。
    //    反过来会让老用户每次冷启动都闪一下弹窗（而它随即消失）。
    val guideScope = rememberCoroutineScope()
    val firstRunSeen by container.firstRunSeen.collectAsStateWithLifecycle(initialValue = true)
    var guideDismissed by rememberSaveable { mutableStateOf(false) }
    if (!firstRunSeen && !guideDismissed) {
        FirstRunGuide(
            onGoGrant = {
                guideDismissed = true
                guideScope.launch { container.markFirstRunSeen() }
                navController.navigate(Routes.PERMISSIONS)
            },
            onDismiss = {
                guideDismissed = true
                guideScope.launch { container.markFirstRunSeen() }
            },
        )
    }

    // ⚠️ 任务状态持有者提升到**外壳**这一层，而不是留在 TasksScreen 里。
    //    原因见 TasksScreen 的 viewModel 参数注释：它不继承 ViewModel，
    //    生命周期等于承载它的那次组合，而 destination 会在切 tab 时被移出组合。
    //    放这里 = 生命周期等于整个应用。
    val tasksViewModel = remember {
        TasksViewModel(
            chat = chatPort,
            // ★ 传**提供者**而不是实例：库是懒开的，构造时它大概率还是 null，
            //   而这里 `remember` 只跑一次 —— 存实例等于永远拿到 null。
            //   首次调用会顺带把库开起来（要碰 Keystore，所以是 suspend）。
            conversations = {
                container.openCredentialStore()
                container.conversationRepository()
            },
        )
    }

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

    // ── 会话抽屉 ────────────────────────────────────────────────
    //
    // ⚠️ 状态源在 `TasksViewModel`（而不是这里）—— 因为"新对话""切会话"
    //    这些操作执行完要顺手把抽屉关上，而它们都在 ViewModel 里。
    //    放两处会让"谁负责关"变成一个需要读两个文件才能回答的问题。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(tasksViewModel.drawerOpen) {
        if (tasksViewModel.drawerOpen) drawerState.open() else drawerState.close()
    }
    // ⚠️ 手势只在任务页开启：设置页、插件页往右滑也会拉出抽屉，
    //    而那里的用户预期是"返回上一页"（边缘返回手势）。
    val drawerGestures = currentRoute == Routes.TASKS

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerGestures,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = PaColor.CanvasElevated,
                drawerShape = RoundedCornerShape(topEnd = PaRadius.l, bottomEnd = PaRadius.l),
            ) {
                SessionDrawer(
                    sessions = tasksViewModel.sessions,
                    onNewSession = tasksViewModel::startNewSession,
                    onSwitch = tasksViewModel::switchTo,
                    onRename = tasksViewModel::renameSession,
                    onDelete = tasksViewModel::deleteSession,
                    onSearch = { tasksViewModel.refreshSessions(it) },
                )
            }
        },
    ) {
        // 抽屉关掉时把状态同步回去 —— 用户是**滑**关的，没走 ViewModel
        LaunchedEffect(drawerState.currentValue) {
            if (drawerState.currentValue == DrawerValue.Closed && tasksViewModel.drawerOpen) {
                tasksViewModel.closeDrawer()
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
                                SettingsViewModel(
                                    openStore = container::openCredentialStore,
                                    readPermissions = container.hostPermissionReader::read,
                                )
                            }
                        }
                    )
                    val keyStatus by vm.keyStatus.collectAsStateWithLifecycle()
                    val permissionSummary by vm.permissionSummary.collectAsStateWithLifecycle()

                    // ⚠️ 必须挂在 `ON_RESUME`：用户点进系统设置开完权限再回来，
                    //    徽章若显示旧值，他会以为"开了也没用"，然后再去开一遍。
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }

                    SettingsScreen(
                        keyStatus = keyStatus,
                        onOpenKeys = { navController.navigate(Routes.KEYS) },
                        onOpenModels = { navController.navigate(Routes.MODELS) },
                        onOpenDsh = { navController.navigate(Routes.DSH) },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
                        onOpenImport = { navController.navigate(Routes.IMPORT) },
                        permissionSummary = permissionSummary,
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                        onOpenCapabilities = { navController.navigate(Routes.CAPABILITIES) },
                        onOpenPrivilegeMode = { navController.navigate(Routes.PRIVILEGE_MODE) },
                        onOpenPersona = { navController.navigate(Routes.PERSONA) },
                        // ★ 明暗偏好：用 remember 承接，切换后立刻重组。
                        //   容器是唯一事实来源，这里只是读 + 回写。
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
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

                composable(Routes.PERMISSIONS) {
                    val vm: PermissionsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                PermissionsViewModel(container.hostPermissionReader)
                            }
                        }
                    )
                    PermissionsScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }

                // 第 0 档能力的入口。⚠️ 它拿的是**同一个** `settingsAccess`
                // 对象 —— 权限页读"有没有授权"、这一页真正去写，
                // 两边同源才不会出现"页面说已授权、点下去说没权限"。
                composable(Routes.CAPABILITIES) {
                    // ⚠️ 取 `applicationContext` 再传下去，不要用 `LocalContext.current`
                    //    本身：ViewModel 活得比 Activity 长，持有 Activity 的 Context
                    //    会把它连同整棵 View 树一起泄漏掉。
                    val appContext = LocalContext.current.applicationContext

                    val vm: CapabilityViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                CapabilityViewModel(
                                    settingsAccess = container.settingsAccess,
                                    // ⚠️ 这两个都是**无状态**的薄壳（真相来源是系统：
                                    //    SAF 授权在 `persistedUriPermissions`、
                                    //    文件在 ContentResolver 里），所以在这里
                                    //    每次重建一个完全没问题。
                                    safGrants = SafDirectoryGrants(appContext),
                                    fileChannel = SafFileChannel(appContext),
                                )
                            }
                        }
                    )
                    CapabilityScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }


                composable(Routes.PRIVILEGE_MODE) {
                    // ★ 模式状态放在容器里（容器是进程级单例）——
                    //   放 ViewModel 会让"切到 root"在页面被销毁后丢失，
                    //   而用户期望的是"切一次，一直有效"。
                    var mode by remember { mutableStateOf(container.privilegeMode()) }
                    var probe by remember { mutableStateOf(container.rootProbe()) }

                    PaScreen(title = "运行模式", onBack = { navController.popBackStack() }) {
                        PrivilegeModeScreen(
                            current = mode,
                            probe = probe,
                            onToggle = { want ->
                                // ★ 立刻探一次，并把**实际生效**的模式回写到界面。
                                //   用户可能选了 root 但 Magisk 没放行 ——
                                //   那时界面必须显示"开关是开的，但没生效"，
                                //   而不是假装成功（那是本项目最忌讳的形态）。
                                val (effective, result) = container.setPrivilegeMode(want)
                                mode = effective
                                probe = result
                            },
                        )
                    }
                }


                composable(Routes.PERSONA) {
                    // ★ 2026-10-25 自我走查发现的断裂：
                    //   `PersonaScreen` 的 onSave 默认 `{}`，而这里没传实现 ——
                    //   用户填完点保存**什么都不会发生**，也不报错。
                    //
                    //   ⇒ 现在真的写盘，且**保存后立刻生效**（容器里同步更新内存）。
                    val scope = rememberCoroutineScope()
                    var savedHint by remember { mutableStateOf<String?>(null) }

                    PaScreen(title = "角色设定", onBack = { navController.popBackStack() }) {
                        PersonaScreen(
                            initial = container.currentCustomPersona(),
                            onSave = { persona ->
                                scope.launch {
                                    runCatching { container.saveCustomPersona(persona) }
                                        .onSuccess { savedHint = "「${persona.name}」已保存，下一条对话就会用它" }
                                        .onFailure { savedHint = "保存失败：${it.message ?: "未知原因"}" }
                                }
                            },
                            // ⚠️ 未亡人模式的完整配置页是下一步工作。
                            //    但**不能留空实现** —— 空按钮比没有按钮更糟
                            //    （用户会反复点，且以为坏了）。
                            //    现在至少给出明确反馈：我们知道你要什么，
                            //    但这个功能还没开放，不假装成功。
                            onRequestLegacy = { relation, name ->
                                savedHint = "「${name ?: relation.label}」这个方向我知道，" +
                                    "完整的配置页还没做好 —— 现在还没有开放。"
                            },
                        )
                        savedHint?.let { msg ->
                            PaBanner(
                                tone = PaBannerTone.Info,
                                title = "已保存",
                                description = msg,
                            )
                        }
                    }
                }

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
                                    // MCP 能力桥（P2）—— 同一套"收函数"的纪律。
                                    readMcpStatus = container::mcpBridgeStatus,
                                    startMcp = container::startMcpBridge,
                                    stopMcp = container::stopMcpBridge,
                                    readMcpDraft = container::mcpDraft,
                                    mcpDraftPath = container.mcpDraftPath,
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
}


/**
 * 首次启动引导 —— 说清「要开什么」和「为什么」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这段话必须由应用来说，而不是让用户自己摸索
 * ═══════════════════════════════════════════════════════════════
 *
 * 无障碍 / 悬浮窗 / 通知 / 截图这四项，**系统不允许应用自己开** ——
 * 必须用户亲手在系统设置里点。而用户第一次打开这个应用时，
 * 他唯一的信息来源是一个写着「权限状态」的页面，上面四个词他一个都不懂。
 *
 * ⇒ 结果是他要么不开（应用什么都干不了，然后被卸载），
 *   要么闭眼全开（**包括他其实不想给的那些**）。两种都不是好结局。
 *
 * ⚠️ 这里**只说明，不催**：没有"必须开启才能继续"，也没有倒计时。
 *    用户点「稍后」就真的稍后，而且**不会再弹**（标记已经置位）。
 *
 * ⚠️ 最后一段那句「之后不需要你再点」不是安慰话 —— 它是真的：
 *    权限由系统记住，应用内的开关也会在冷启动时自动恢复
 *    （见 `AppContainer.restoreEnabledIntegrations`）。
 */
@Composable
private fun FirstRunGuide(
    onGoGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "先开几个权限，我才干得了活",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PaSpace.s)) {
                Text(
                    text = "所有操作都在你手机上完成，数据不出设备。但有四件事必须由你亲手授权 —— " +
                        "系统不允许应用自己开：",
                    style = PaType.body,
                    color = PaColor.TextSecondary,
                )
                GuideItem("辅助功能（无障碍）", "读屏与点击。不开启我什么都做不了。")
                GuideItem("通知", "任务跑起来时你能随时看到进度，也方便一键停下。")
                GuideItem("悬浮窗", "在别的应用上面显示一个「急停」入口。")
                GuideItem("截图", "读屏读不到内容时的兜底（部分应用不暴露文字）。")
                Text(
                    text = "授权是「一次性」的 —— 系统会记住。之后每次打开我都在，" +
                        "不需要你再点一遍。",
                    style = PaType.body,
                    color = PaColor.TextPrimary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onGoGrant) {
                Text("去授权", color = PaColor.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后", color = PaColor.TextSecondary)
            }
        },
    )
}

/** 引导里的一项：**粗体名字 + 一句"它能让我做什么"**。 */
@Composable
private fun GuideItem(name: String, why: String) {
    Column {
        Text(text = name, style = PaType.label, color = PaColor.TextPrimary)
        Text(text = why, style = PaType.caption, color = PaColor.TextSecondary)
    }
}
