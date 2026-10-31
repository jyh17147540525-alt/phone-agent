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
import androidx.compose.runtime.mutableIntStateOf
import com.pocketagent.ui.theme.ThemeMode
import timber.log.Timber
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.call.CallRouteHost
import com.pocketagent.ui.design.PaColor
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

    /** 长期记忆的 3D 星图。 */
    const val MEMORY_GRAPH = "memory-graph"

    /**
     * **语音通话页** —— 一整屏的通话界面。
     *
     * ★ 2026-10-31：用户要"打电话的页面互动动画…可以随时打断"。
     *   它做成独立路由而不是聊天页里的浮层：通话时用户不看屏幕，
     *   屏幕上不该有大量他不需要读的文字。
     */
    const val CALL = "call"

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

    /**
     * **顶层 tab 的集合** —— 判断"这一跳是不是平级互换"。
     *
     * ★ 2026-10-30：转场要分成两套（平级交叉淡入 / 层级方向位移），
     *   而分开它们的唯一依据就是"两端是不是都在这一组里"。
     *
     * ⚠️ 必须**同时**判起点和终点，理由见 `isTabSwap` 的注释。
     */
    val TOP_LEVEL: Set<String> = setOf(TASKS, PLUGINS, SETTINGS)
}

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
            // ★ 传**读取口**而不是快照：用户在设置页改完开关后，
            //   这里下一次调用必须读到新值。
            maturePermission = { container.maturePermission() },
            // ★ 压缩段 → 记忆分层。见 TasksViewModel 里 absorbCompressed 的注释。
            absorbCompressed = { segments -> container.absorbCompressedSegments(segments) },
            // ★ 按住说话：录音器在容器里（要 Context），本类只拿到两个 lambda。
            startVoice = {
                if (!container.hasMicPermission()) {
                    "没有麦克风权限。去「设置 → 通知与主动消息」允许录音。"
                } else {
                    container.voiceRecorder.start()
                }
            },
            stopVoice = {
                val clip = container.voiceRecorder.stop() ?: return@TasksViewModel null
                clip.file.absolutePath to clip.durationMs
            },
            // ★ 朗读：开关在容器里判断（默认关），这里只负责把文本交出去。
            speakReply = { text -> if (container.ttsEnabled()) container.speakAsync(text) },
        )
    }

    // 转场位移量：设计令牌给的是 dp，而 slideIn/slideOut 要的是像素。
    // 在这里换算一次，避免每帧在动画 lambda 里做 with(density) 转换。
    val density = LocalDensity.current
    val enterOffsetX = with(density) { PaTransition.ENTER_OFFSET_X.dp.roundToPx() }
    val exitOffsetX = with(density) { PaTransition.EXIT_OFFSET_X.dp.roundToPx() }

    // ═══════════════════════════════════════════════════════════════
    //  ★ 转场也要尊重"系统关掉动画"
    // ═══════════════════════════════════════════════════════════════
    //
    // ⚠️ 光靠 `PaReveal` 自己判断是不够的 —— **路由转场不经过它**。
    //    用户在开发者选项里把动画关掉，路由切换还会坚持跑 180~280ms，
    //    那是"每次操作都慢半拍"，比不做动画更难受。
    //
    // ⇒ 在这里读一次，关掉时把转场时长压到 0（`PaMotion` 的 tween 接受 0，
    //   表现为**立刻切换**）。
    val motionPref = com.pocketagent.ui.design.LocalMotionPreference.current
    val tabSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        if (motionPref.animationsOn) PaMotion.fast() else androidx.compose.animation.core.snap()
    val layerSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        if (motionPref.animationsOn) PaMotion.standard() else androidx.compose.animation.core.snap()
    // ⚠️ `slideIn/OutHorizontally` 的 spec 类型是 `FiniteAnimationSpec<IntOffset>`，
    //    不能与上面的 Float 共用 —— 传错是编译期报错（这次就报了）。
    val slideSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntOffset> =
        if (motionPref.animationsOn) PaMotion.standard() else androidx.compose.animation.core.snap()

    // ═══════════════════════════════════════════════════════════════
    //   ★★ 2026-10-30：转场重做 —— 用户："各个页面之间的切换有种突兀感"
    // ═══════════════════════════════════════════════════════════════
    //
    // 先说清楚**突兀感到底来自哪里**。不是曲线不够顺，是**结构错了**：
    //
    //  ① **tab 平级切换用了横向滑动**。
    //     横向滑动的语义是"进入下一层 / 返回上一层"（那是钻取）。
    //     但「任务 ↔ 插件 ↔ 设置」是**平级**关系 —— 横向滑动会让大脑
    //     短暂地以为自己在钻取，然后发现又回到了同一层。那种错位
    //     就是"突兀"的主要成分。
    //     ★ iOS 的 tab bar 切换**从不横向滑动**，只有交叉淡入（或干脆瞬切）。
    //
    //  ② **两层动画叠在一起**。
    //     `PaScreen` 里的 `PaReveal` 会在内容上再跑一次 260ms 浮现，
    //     而 NavHost 的转场同时在跑 380ms。
    //     两个动画时间不同、曲线不同 → 内容先到、动画后完 → 抖动。
    //
    //  ③ **380ms 对 tab 太长**。
    //     iOS 的 tab 切换感知上几乎是"立刻"（淡入约 200ms 内）。
    //     380ms 会让人觉得"每次点底栏都要等"。
    //
    // ⇒ 三件事一起改：
    //    · tab 之间：**只交叉淡入，不位移、不缩放**，时长压到
    //      `PaDuration.Fast`（180ms）—— 快到像是"换了一下"而不是"切过去了"
    //    · 层级钻取：保留方向感（微位移 + 淡入），那是正确的语义
    //    · 内容浮现：**在 NavHost 转场期间不要跑**（见 `PaScreen` 的注释）
    //
    // ⚠️ 判断"是不是 tab 切换"必须看**起点和终点都在 TABS 里**，
    //    不能只看终点：从「设置 → 插件源」再点底栏「任务」也是 tab 切换，
    //    但那一跳的起点是二级页。那种情况下横向滑动反而**是对的**
    //    （用户确实从深层回到了顶层）。见 [isTabSwap]。

    /**
     * 这一跳是不是**平级 tab 互换**。
     *
     * ⚠️ 必须两头都在顶层 tab 里才算。只判终点会让
     *    「二级页 → 顶层」也走交叉淡入，而那一步用户确实期待"退回去"。
     */
    fun isTabSwap(initial: androidx.navigation.NavBackStackEntry?, target: androidx.navigation.NavBackStackEntry?): Boolean =
        com.pocketagent.agentui.TransitionKind.of(
            fromRoute = initial?.destination?.route,
            toRoute = target?.destination?.route,
            topLevel = Routes.TOP_LEVEL,
            isPop = false,
        ) == com.pocketagent.agentui.TransitionKind.Kind.TAB_SWAP

    // ═══════════════════════════════════════════════════════════════
    //  ★★ 2026-10-31：底栏撤掉之后，`isTopLevel` / `selectedIndex` 已删
    // ═══════════════════════════════════════════════════════════════
    //
    // 它们原来服务于底栏：
    //   · `isTopLevel`  —— 决定底栏显不显示
    //   · `selectedIndex` —— 底栏哪个高亮
    //
    // 底栏没了，这两个只剩"恒为真/恒为0"的空壳。留着会比删掉更糟：
    // 下一个人会以为还有别的地方在读它。
    //
    // ⚠️ 但 `goToTab` **保留** —— TasksScreen 的那个齿轮还在用它
    //    （聊天页右上角直接进设置的路径）。它仍然需要
    //    `popUpTo(TASKS) { saveState }` 来避免返回栈里堆一串设置页。

    /**
     * 进入一个顶层页面（对话页的齿轮入口用它）。
     *
     * ⚠️ 三个导航选项缺一不可：
     *   · `popUpTo(startDestination) { saveState = true }`
     *     —— 清掉中间栈，否则从"设置→插件源"再回"任务"，
     *        按返回键会回到"插件源"，而用户已经离开那个分支了。
     *   · `launchSingleTop` —— 连点不会叠出多份实例。
     *   · `restoreState` —— 回来时恢复之前的滚动位置。
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
                    // ══════════════════════════════════════════════════
                    //  ★★ 2026-10-31：插件 / 设置搬进抽屉右下角
                    // ══════════════════════════════════════════════════
                    //
                    // 用户："把 app 里最下方的三个页面留剩下对话一个主页，
                    //      其他的放入侧边栏右下角…用户一打开软件就能
                    //      直接对话模型了。"
                    //
                    // ⚠️ 必须先关抽屉再导航：不关的话新页面会在抽屉**后面**
                    //    打开，用户看到的是"点了没反应"，然后手动滑关抽屉
                    //    才发现已经在插件页了。
                    onOpenPlugins = {
                        tasksViewModel.closeDrawer()
                        navController.navigate(Routes.PLUGINS) {
                            launchSingleTop = true
                        }
                    },
                    onOpenSettings = {
                        tasksViewModel.closeDrawer()
                        navController.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
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

                // ══════════════════════════════════════════════════════
                //  转场：**两套语义，不是一个动画改参数**
                // ══════════════════════════════════════════════════════
                //
                // 用户："各个页面之间的切换有种突兀感"。
                // 根因是**平级和层级用了同一种动画** —— 见文件里
                // `isTabSwap` 上方那段注释的完整分析。
                //
                // ┌────────────┬──────────────────────────────────────┐
                // │ tab 互换    │ 纯交叉淡入，180ms，**零位移零缩放**      │
                // │ 层级钻取    │ 微位移 + 淡入 + 微缩放，280ms           │
                // └────────────┴──────────────────────────────────────┘
                //
                // ⚠️ tab 那一档刻意**不做任何位移**：位移的语义是"空间位置变了"。
                //    平级互换时空间没变，只是"这一层换成了另一层"。
                //    加了位移就会让大脑误以为在钻取 —— 那就是突兀感的来源。

                enterTransition = {
                    if (isTabSwap(initialState, targetState)) {
                        // ★ 只淡入。180ms：快到像"换了一下"，不像"切过去了"。
                        fadeIn(animationSpec = tabSpec)
                    } else {
                        // 层级的进入：内容从右侧微移进来（方向感是对的）
                        fadeIn(animationSpec = layerSpec) +
                            slideInHorizontally(animationSpec = slideSpec) {
                                enterOffsetX
                            } +
                            scaleIn(
                                animationSpec = layerSpec,
                                initialScale = PaTransition.ENTER_SCALE,
                            )
                    }
                },

                exitTransition = {
                    if (isTabSwap(initialState, targetState)) {
                        // ⚠️ 交叉淡入的关键是**退出的时长要和进入一致**。
                        //    退出更快（180 vs 380）会让两层内容短暂"叠"在一起 ——
                        //    那正是"卡了一下"的来源。
                        fadeOut(animationSpec = tabSpec)
                    } else {
                        // 旧内容迅速让位（比进入快）：否则两层长时间重叠
                        fadeOut(animationSpec = tabSpec) +
                            scaleOut(
                                animationSpec = layerSpec,
                                targetScale = PaTransition.EXIT_SCALE,
                            )
                    }
                },

                popEnterTransition = {
                    if (isTabSwap(initialState, targetState)) {
                        fadeIn(animationSpec = tabSpec)
                    } else {
                        // 返回：上一层从左侧"深处"浮回来
                        fadeIn(animationSpec = layerSpec) +
                            scaleIn(
                                animationSpec = layerSpec,
                                initialScale = PaTransition.EXIT_SCALE,
                            )
                    }
                },

                popExitTransition = {
                    if (isTabSwap(initialState, targetState)) {
                        fadeOut(animationSpec = tabSpec)
                    } else {
                        fadeOut(animationSpec = tabSpec) +
                            slideOutHorizontally(animationSpec = slideSpec) {
                                exitOffsetX
                            } +
                            scaleOut(
                                animationSpec = layerSpec,
                                targetScale = PaTransition.ENTER_SCALE,
                            )
                    }
                },
            ) {

                // ── 任务 ────────────────────────────────────────
                composable(Routes.TASKS) {
                    TasksScreen(
                        viewModel = tasksViewModel,
                        onOpenSettings = { goToTab(Routes.SETTINGS) },
                        // ★ 聊天页直接进角色设定：改完返回，下一句就生效。
                        onOpenPersona = { navController.navigate(Routes.PERSONA) },
                        // ★★ 2026-10-31：语音通话入口。
                        //   它从"还没接上"的占位变成了真的路由。
                        onOpenCall = { navController.navigate(Routes.CALL) },
                    )
                }

                // ── 语音通话 ────────────────────────────────────
                //
                // ★ 2026-10-31：一整屏的通话界面。
                //
                // ⚠️ 它**不显示底栏**（底栏已经撤掉了，见上文），
                //    也不用 `PaScreen` —— 通话页没有标题栏和返回箭头，
                //    唯一的出口是那个红色挂断按钮。
                //    理由：通话时"返回"这个动作会让人以为"退出去但还在通话"，
                //    而实际是挂断。把出口做成唯一一个明确动作，不产生歧义。
                composable(Routes.CALL) {
                    CallRouteHost(
                        container = container,
                        onHangUp = { navController.popBackStack() },
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

                    // ★ 成熟内容授权：容器是唯一事实来源，Compose 侧只需一个
                    //   tick 来触发重组（容器返回的是同步内存值，不是 Flow）。
                    var matureTick by remember { mutableIntStateOf(0) }
                    val maturePermission = remember(matureTick) { container.maturePermission() }
                    // ★★ 关系推进：阶段、进度、节奏 —— 全部来自容器（唯一事实来源）。
                    //   ⚠️ 也要挂在 `matureTick` 上：用户聊完回来，进度必须变；
                    //      否则"它到底在不在推进"这件事又变隐形了。
                    val stageLabel = remember(matureTick) { container.relationshipStage().label }
                    val stageProgress = remember(matureTick) { container.relationshipProgress() }
                    val currentSpeed = remember(matureTick) { container.stageSpeed() }
                    // ★ 破甲档位：容器是唯一事实来源，同上面几条。
                    val armorTier = remember(matureTick) { container.armorTier() }
                    // ★ 自测状态：跑一次会**真的调用模型**（3 个探针 × 2 组 = 6 次），
                    //   所以要有"正在测"的状态，不能让用户以为点了没反应。
                    val settingsScope = rememberCoroutineScope()
                    var probeResult by remember { mutableStateOf<List<String>>(emptyList()) }
                    var probeRunning by remember { mutableStateOf(false) }
                    // ⚠️ 记忆条数同样要跟着 tick 刷新：用户刚聊完回来，
                    //    这一行必须显示新的数字，否则"它记住了"这件事又变隐形了。
                    val memoryCount = remember(matureTick) { container.memoryAtomCount() }

                    // ══════════════════════════════════════════════
                    //  ★ 运行时权限：通知 + 粗略定位
                    // ══════════════════════════════════════════════
                    //
                    // ⚠️ 写进清单 ≠ 拿到。Android 13+ 的通知权限必须由用户
                    //    点一次；没有它 `notify()` 会被**静默丢弃**，
                    //    唯一的症状是"角色从来不主动找我"。
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var notifTick by remember { mutableIntStateOf(0) }
                    var locationTick by remember { mutableIntStateOf(0) }
                    fun granted(p: String): Boolean =
                        ctx.checkSelfPermission(p) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    val notificationGranted = remember(notifTick) {
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                            granted(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val locationGranted = remember(locationTick) {
                        granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { notifTick++ }
                    val locationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { locationTick++ }
                    var micTick by remember { mutableIntStateOf(0) }
                    var ttsTick by remember { mutableIntStateOf(0) }
                    val micGranted = remember(micTick) {
                        granted(android.Manifest.permission.RECORD_AUDIO)
                    }
                    val ttsOn = remember(ttsTick) { container.ttsEnabled() }
                    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    ) { micTick++ }

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
                        // ★ 成熟内容：两个门都必须显式操作。
                        //   容器同步返回内存里的新值 → `matureTick` 递增 → 重组。
                        maturePermission = maturePermission,
                        onAdultConfirmedChange = {
                            container.setAdultConfirmed(it)
                            matureTick++
                        },
                        onMatureEnabledChange = {
                            container.setMatureEnabled(it)
                            matureTick++
                        },
                        // ★ 关系推进：让"怎么才能到那一步"可见、可调。
                        //   ⚠️ 在此之前阶段是写死的 30/150/400 轮，而界面上
                        //      什么都不显示 —— 用户只能看到"开了也没用"。
                        relationshipStageLabel = stageLabel,
                        relationshipProgress = stageProgress,
                        stageSpeed = currentSpeed,
                        onStageSpeedChange = { speed ->
                            container.setStageSpeed(speed)
                            matureTick++
                        },
                        // ★ 角色稳定性（破甲）：减少"作为AI""写成摘要"这类退让。
                        armorTier = armorTier,
                        onArmorTierChange = { tier ->
                            container.setArmorTier(tier)
                            matureTick++
                        },
                        // ★ 自测：**用好几个固定场景跑 A/B 对照**，
                        //   不碰用户的角色卡、不写对话、不进记忆库。
                        armorProbeResult = probeResult,
                        armorProbeRunning = probeRunning,
                        onRunArmorProbe = {
                            if (!probeRunning) {
                                probeRunning = true
                                probeResult = emptyList()
                                Timber.i("破甲自测：点了按钮，开始跑")
                                settingsScope.launch {
                                    // ⚠️ 用**完整堆栈**记异常：静默吞掉会让
                                    //   "点了没反应"变成无法排查的形态。
                                    var lines: List<String>
                                    try {
                                        lines = container.runArmorProbe()
                                    } catch (t: Throwable) {
                                        Timber.e(t, "破甲自测抛异常")
                                        lines = listOf("自测抛异常：${t::class.simpleName}: ${t.message}")
                                    }
                                    Timber.i("破甲自测：结束，%d 行结果", lines.size)
                                    probeResult = lines
                                    probeRunning = false
                                }
                            }
                        },
                        memoryAtomCount = memoryCount,
                        onOpenMemory = { navController.navigate(Routes.MEMORY_GRAPH) },
                        notificationGranted = notificationGranted,
                        onRequestNotification = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                notifTick++
                            }
                        },
                        locationGranted = locationGranted,
                        onRequestLocation = {
                            locationLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        },
                        micGranted = micGranted,
                        onRequestMic = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                        ttsEnabled = ttsOn,
                        onToggleTts = {
                            container.setTtsEnabled(it)
                            ttsTick++
                        },
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


                // ══════════════════════════════════════════════════
                //  ★ 记忆星图 —— 3D 可旋转、可缩放的记忆团
                // ══════════════════════════════════════════════════
                composable(Routes.MEMORY_GRAPH) {
                    // ⚠️ 记忆库是**同步读**的（`memoryAtoms()` 内存缓存 + 文件），
                    //    但这里用 `remember` 接住，避免每次重组都重新读一遍。
                    val atoms = remember { container.memoryAtoms() }
                    // ★ 称呼由角色卡决定 —— 随笔里"你"要变成她实际叫的那个称呼。
                    val who = remember {
                        container.currentCustomPersona()?.addressUser?.takeIf { it.isNotBlank() }
                            ?: "你"
                    }
                    val personaBrief = remember {
                        container.currentCustomPersona()?.describeForPrompt().orEmpty()
                    }
                    // ★★ 用户要的"常驻"：进页面就开始在后台补写，
                    //    而不是等他点开每一个节点才现写。
                    //    ⚠️ 用 LaunchedEffect(Unit)：只在本页存活期内跑一次，
                    //      切走就取消（后台任务不该在用户离开后继续烧额度）。
                    LaunchedEffect(Unit) {
                        container.prefetchDiaries(userAddress = who, personaBrief = personaBrief)
                    }
                    com.pocketagent.ui.memory.MemoryGraphScreen(
                        atoms = atoms,
                        modifier = Modifier,
                        onBack = { navController.popBackStack() },
                        userName = who,
                        personaName = container.currentCustomPersona()?.name.orEmpty(),
                        // ★ 缓存的随笔：有就立刻显示，不等模型。
                        cachedDiary = { atom -> container.cachedDiary(atom.id) },
                        // ★ 没有缓存时让**角色模型**写一篇。
                        //   ⚠️ 走的是用户配的对话模型 —— 要的就是这个角色的口吻。
                        generateDiary = { atom ->
                            container.generateDiary(
                                atom = atom,
                                userAddress = who,
                                personaBrief = personaBrief,
                                dayHint = com.pocketagent.memorylogic.MemoryDiary.relativeDay(
                                    atom.at,
                                    System.currentTimeMillis(),
                                ),
                            )
                        },
                    )
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
                                        .onSuccess {
                                            // ★ 把"存了什么"说出来，而不是只报"已保存"。
                                            //   用户被"报成功、存的却是空壳"坑过一次，
                                            //   所以这里要能当场核对：名字 + 有效栏数。
                                            savedHint = "「${persona.name}」已保存：" +
                                                "${com.pocketagent.personalogic.PersonaInput.filledFields(persona)} 栏有内容，" +
                                                "下一条对话就会用它"
                                        }
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
            //
            // ══════════════════════════════════════════════════════
            //  ★★ 2026-10-31：**底栏已撤掉**
            // ══════════════════════════════════════════════════════
            //
            // 用户："把 app 里最下方的三个页面留剩下对话一个主页，
            //      其他的放入侧边栏右下角…用户一打开软件就能直接
            //      对话模型了。"
            //
            // 原来这里是一个 `AnimatedVisibility` 包着 `PaBottomBar`。
            // 现在整个删掉 —— 理由是**它服务的场景太窄**：
            //
            //   · 底栏占 56dp **永久高度**，但只在"切三个页"时有用
            //   · 而用户 95% 的时间只在对话页
            //   · 代价是**每一屏都少 56dp 对话空间**
            //
            // ⇒ 插件 / 设置收进抽屉右下角（见 `SessionDrawer` 的注释）。
            //   开机即对话页、零点击，这是"进来就能聊"的唯一形态。
            //
            // ⚠️ 顺带删掉的东西（都不能留）：
            //   · `PaBottomBar` 的调用 —— 留着就是一个没人看的组件
            //   · `isTopLevel` / `selectedIndex` —— 只有一个根页面之后它们恒真/恒0
            //   · `PaNavItem` / `TABS` 的底栏用途 —— TABS 仍被 `TOP_LEVEL`
            //     用来判断转场（那个还有用，见 `isTabSwap`）
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
                Text("去授权", color = PaColor.AccentText)
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
