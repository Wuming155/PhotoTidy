package com.phototidy.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phototidy.Graph
import com.phototidy.R
import com.phototidy.core.media.MediaPermissions
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaScope
import com.phototidy.core.settings.SettingsStore
import com.phototidy.ui.albums.AlbumsScreen
import com.phototidy.ui.components.HelpSheet
import com.phototidy.ui.components.PermissionScreen
import com.phototidy.ui.components.shortLabel
import com.phototidy.ui.duplicates.DuplicatesScreen
import com.phototidy.ui.home.HomeScreen
import com.phototidy.ui.review.ExitReviewDialog
import com.phototidy.ui.review.ReviewScreen
import com.phototidy.ui.review.ReviewViewModel
import com.phototidy.ui.review.ScopeGroup
import com.phototidy.ui.review.ScopeOption
import com.phototidy.ui.settings.SettingsScreen
import com.phototidy.ui.trash.TrashScreen
import kotlinx.coroutines.launch

/**
 * 底部导航只有三个目的地。
 *
 * 回收站被刻意拿掉了：它是「善后」而不是「日常」，放在导航里会稀释主流程。
 * 现在它在设置页里（副标题直接显示系统回收站里有多少张）。
 */
private enum class RootTab(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    Home(R.string.tab_review, Icons.Rounded.Swipe),
    Albums(R.string.tab_albums, Icons.Rounded.PhotoLibrary),
    Settings(R.string.tab_settings, Icons.Rounded.Settings),
}

/** 宽屏判据：Material 官方断点，600dp 起把底部导航换成侧边导航轨。 */
private const val EXPANDED_WIDTH_DP = 600

/**
 * 应用外壳。
 *
 * 这里集中处理三件「全局只有一份」的事情，各界面自身保持无状态：
 *  1) 运行时权限申请与结果解释（含 Android 14+ 的部分授权）；
 *  2) MediaStore 写操作的系统授权弹窗（IntentSender）；
 *  3) 自适应导航形态 —— 窄屏底部导航条，宽屏侧边导航轨。
 *
 * 第 3 点是 targetSdk 36 的必修课：Android 16 起，最小宽度 ≥600dp 的设备上
 * `screenOrientation` 与 `resizeableActivity` 全部失效，应用必然要在平板、折叠屏展开态、
 * 桌面窗口模式下以任意尺寸呈现。所以自适应布局必须从第一天就是设计的一部分。
 */
@Composable
fun PhotoTidyRoot(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val widthDp = with(density) { windowInfo.containerSize.width.toDp().value }
    val expanded = widthDp >= EXPANDED_WIDTH_DP

    val libraryVm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val libraryState by libraryVm.state.collectAsStateWithLifecycle()
    val settings by settingsStore.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    // ---------------------------------------------------------------- 权限
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        libraryVm.onAccessResolved(
            MediaPermissions.resolveAccess(MediaPermissions.current(context), result),
        )
    }

    // 首次组合 + 每次回到前台都重新判定一次：用户可能刚刚在系统设置里改了授权范围
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        libraryVm.onAccessResolved(MediaPermissions.current(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    libraryVm.onResume()
                    libraryVm.onAccessResolved(MediaPermissions.current(context))
                }
                Lifecycle.Event.ON_PAUSE -> {
                    libraryVm.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---------------------------------------------------- 系统媒体授权弹窗
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        uiScope.launch {
            Graph.mediaOps.resumeConsent(result.resultCode == Activity.RESULT_OK)
        }
    }

    LaunchedEffect(Unit) {
        Graph.mediaOps.consent.collect { request -> consentLauncher.launch(request) }
    }
    LaunchedEffect(Unit) {
        Graph.mediaOps.messages.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
        }
    }

    // ---------------------------------------------------------------- 导航
    var tabName by rememberSaveable { mutableStateOf(RootTab.Home.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var scopeEncoded by rememberSaveable { mutableStateOf("") }
    var helpVisible by remember { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showDuplicates by rememberSaveable { mutableStateOf(false) }
    /** 退出整理的确认框（有待提交改动时才拦一道）。 */
    var exitConfirmOpen by remember { mutableStateOf(false) }
    /**
     * 当前整理会话的 ViewModel，只给系统返回键用。
     *
     * 返回键的处理在整理页**之外**（这一层管导航），而「有没有待提交改动」只有会话知道。
     * 存一个引用按需读 `state.value` 即可 —— 它是快照读取，不进组合期，不需要重组。
     */
    var reviewVmForBack by remember { mutableStateOf<ReviewViewModel?>(null) }

    val reviewScope = MediaScope.decode(scopeEncoded)
    /**
     * 是否处于「主壳」——也就是下面 `when` 里那条会组合 [Scaffold] 的分支。
     *
     * 主壳 = 非整理页 && 权限已判定 && 可读（`access == null` 时 `canRead` 也必为 false），
     * 与 `when` 的四个分支一一对应。
     *
     * 外层那个 [SnackbarHost] 只在**非主壳**时挂载：两个宿主同时读同一份
     * [snackbarHostState] 会把同一条消息渲染两遍，而 Compose 的状态读取没有「消费」语义。
     * 互斥之后主壳内交给 Scaffold 自带的宿主 —— 它会自己把 snackbar 抬到底部栏 / FAB 之上，
     * 那是外层宿主做不到的（它只能按 `navigationBars` inset 贴底）。
     */
    val shellVisible = reviewScope == null && libraryState.canRead
    val currentTab = RootTab.entries.firstOrNull { it.name == tabName } ?: RootTab.Home
    val selectedTab = if (showSettings) RootTab.Settings else currentTab
    // P7：导航单一真源提升到 Scaffold 之外 —— FAB（Scaffold 的 floatingActionButton slot）
    // 与内容 lambda 不共享局部作用域，此前 destinationKey 声明在内容内部导致 FAB 无法复用，
    // 只能手写 `currentTab == Home && !showSettings && !showTrash` 并漏掉 showDuplicates。
    // 现 FAB 与内容同源，均以 destinationKey 判定。
    val destinationKey = when {
        showDuplicates -> "duplicates"
        showTrash -> "trash"
        showSettings -> "settings"
        else -> currentTab.name
    }

    fun selectTab(selected: RootTab) {
        // 回收站 / 重复照片都是「次要页面」：点了底部导航的任意一项就该离开它们。
        // 不清这些标志的话导航在那些页面上是**死的** —— `destinationKey` 里它们
        // 优先于 tab，于是点「整理」「相册」都还停在原地，只能靠返回键出去。
        showTrash = false
        showDuplicates = false
        if (selected == RootTab.Settings) {
            showSettings = true
        } else {
            tabName = selected.name
            showSettings = false
        }
    }

    fun openScope(scope: MediaScope) {
        // P10 导航层拦截：该入口不是 VM 方法，范围切换按钮虽已在 UI 侧禁用，
        // 已展开的选择器仍可能在提交期间点选；提交期间切换范围会另建 VM 并丢弃在飞提交的上下文。
        if (reviewVmForBack?.state?.value?.submitting == true) return
        val nextEncoded = MediaScope.encode(scope)
        if (scopeEncoded != nextEncoded) {
            libraryVm.clearReviewSession()
            scopeEncoded = nextEncoded
        }
    }

    /**
     * 退出整理页。
     *
     * 必须做三件事，否则用户会看到那个「滑完 4 张，返回上一级还是 15 张」的现象：
     *  1. 把「已整理」账本结清（节流窗口里可能还欠着十几条没落盘）；
     *  2. 重扫照片库 —— 首页/相册/范围菜单上的「剩 N 张」全部由概览聚合得出，
     *     不重扫的话它们仍停留在进入整理页之前的数字。
     *  3. 清理整理会话的 ViewModelStore —— 释放 ReviewViewModel 及其队列内存，
     *     触发 onCleared()，避免在进程中累积残留。
     */
    fun leaveReview() {
        Graph.reviewedLedger.flush()
        libraryVm.refresh()
        libraryVm.clearReviewSession()
        scopeEncoded = ""
        reviewVmForBack = null
    }

    // 首次进入整理页自动讲一次手势（只讲一次，之后不再打扰）
    LaunchedEffect(reviewScope?.key) {
        if (reviewScope != null && !settings.helpShown) {
            helpVisible = true
            settingsStore.markHelpShown()
        }
    }

    // 系统返回 / 预测性返回：整理页 → 回收站 → 设置页 → 交给系统
    // 整理页里返回不再直接退出：有待提交改动时先问一次（见 ExitReviewDialog）——
    // 退出整理页即保留会话，用户有可能以为自己「取消」了这轮整理。
    // P10：必须保持 enabled = reviewScope != null，在回调内部静默消费。
    // 若把 submitting 写进 enabled，返回事件会穿透到 Activity ⇒ finish() ⇒ onDestroy
    // ⇒ ViewModelStore 清空 ⇒ viewModelScope 取消、在飞的 commit() 被打断在半途
    // （updateAll 是逐条循环）。静默吞掉无需额外提示 —— 状态行正显示 submittingLabel。
    BackHandler(enabled = reviewScope != null) {
        if (reviewVmForBack?.state?.value?.submitting == true) return@BackHandler
        if (reviewVmForBack?.state?.value?.hasPending == true) {
            exitConfirmOpen = true
        } else {
            leaveReview()
        }
    }
    BackHandler(enabled = reviewScope == null && showDuplicates) { showDuplicates = false }
    BackHandler(enabled = reviewScope == null && !showDuplicates && showTrash) { showTrash = false }
    BackHandler(enabled = reviewScope == null && !showDuplicates && !showTrash && showSettings) {
        showSettings = false
    }

    // 概览始终没来（首次扫描就失败了）：退回首页，别让用户对着一个永远转不完的圈。
    // 判据必须带 access —— 权限还没判定完时 overview 也是 null，那不是失败。
    val reviewWaitingForLibrary = reviewScope != null &&
        libraryState.overview == null &&
        !libraryState.loading &&
        libraryState.access?.canRead == true
    LaunchedEffect(reviewWaitingForLibrary) {
        if (reviewWaitingForLibrary) leaveReview()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // ------------------------------------- 整理页（全屏，无导航壳）
            reviewScope != null -> {
                // ⚠️ 概览没就绪时**不能**建 ReviewViewModel：它的 total / remaining 是构造参数，
                // 而 `viewModel(key = ...)` 一旦把它缓存进 ViewModelStore，之后传进来的新参数
                // 全部被忽略。进程死亡恢复时首帧的 `LibraryUiState` 还是初始值
                // （totalCount = 0，且 loading 初始就是 false），所以这里只能判 overview ——
                // 判 totalCount/loading 会让它拿着 total = 0 建出来，然后**永远**渲染
                // 「保留 0 / 移动 0 / 回收 0」的完成卡片（「再来一轮」也救不回来）。
                if (libraryState.overview == null) {
                    LoadingScreen()
                } else {
                    val scopeLabel = reviewScope.shortLabel()
                    val moveTargets = remember(reviewScope.key, libraryState.albums) {
                        libraryVm.moveTargets(reviewScope)
                    }
                    // 取数器把分页推到数据层：整理会话内存里最多只有一窗照片，
                    // 跟「最近」范围到底是 200 张还是 12.5 万张无关。
                    val loader = remember(reviewScope.key) {
                        libraryVm.pageLoader(reviewScope)
                    }
                    // 两个数都要：total 是分页上界（照片一共多少），remaining 是进度条分母
                    // 与「还剩多少」的口径（还没整理的有多少）。混用它们就会出现
                    // 「滑完 4 张，数字还是 15」这种看起来完全没生效的现象。
                    val total = libraryVm.scopeCount(reviewScope)
                    val remaining = libraryVm.scopeRemaining(reviewScope)
                    val reviewStoreOwner = remember(libraryVm) {
                        object : ViewModelStoreOwner {
                            override val viewModelStore: ViewModelStore
                                get() = libraryVm.getReviewViewModelStore()
                        }
                    }
                    val reviewVm: ReviewViewModel = viewModel(
                        viewModelStoreOwner = reviewStoreOwner,
                        key = "review-${reviewScope.key}",
                        factory = ReviewViewModel.factory(
                            scope = reviewScope,
                            total = total,
                            remaining = remaining,
                            moveTargets = moveTargets,
                            loader = loader,
                            // 提交真的改动了照片库，外层数据必须重扫，否则首页/回收站还在显示旧状态
                            onCommitted = { libraryVm.refresh() },
                        ),
                    )
                    val reviewState by reviewVm.state.collectAsStateWithLifecycle()

                    // 选择器里的标签不在这里预拼：月份标题要过 stringResource，
                    // 而这里是「每滑一张都会重组」的地方 —— 一百多个月份全部格式化一遍太贵。
                    // 标签改由 ScopePickerRow 在渲染到那一行时现算（见 MediaScope.optionLabel）。
                    val scopeOptions = remember(
                        reviewScope.key,
                        libraryState.albums,
                        libraryState.months,
                        libraryState.remainingTotal,
                        libraryState.screenshotRemaining,
                    ) {
                        buildList {
                            // 前两项是「快捷范围」，与「最近」同组渲染（不带分区标题）——
                            // 它们永远是少数几个、而且是最常用的入口。
                            if (libraryState.remainingTotal > 0) {
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Recent,
                                        remaining = libraryState.remainingTotal,
                                        group = ScopeGroup.Recent,
                                    ),
                                )
                            }
                            // 没有待整理截图时不显示已完成范围。
                            if (libraryState.screenshotRemaining > 0) {
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Screenshots,
                                        remaining = libraryState.screenshotRemaining,
                                        group = ScopeGroup.Recent,
                                    ),
                                )
                            }
                            libraryState.albums.filter { it.remaining > 0 }.forEach { album ->
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Album(album.bucketId, album.name),
                                        remaining = album.remaining,
                                        group = ScopeGroup.Album,
                                    ),
                                )
                            }
                            libraryState.months.forEach { month ->
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Month(month.year, month.month),
                                        remaining = month.remaining,
                                        group = ScopeGroup.Month,
                                    ),
                                )
                            }
                        }
                    }

                    // 记忆化回调：`reviewVm::keep` 这类绑定方法引用每次重组都会新建实例，
                    // 参数实例不等 ⇒ Compose 无法跳过子组件 —— 而这里每次滑动都会重组，
                    // 于是每滑一张整棵整理子树（含带 LazyRow 的快捷移动条）都要重跑。
                    val onKeep: (Long?) -> Unit = remember(reviewVm) { { id -> reviewVm.keep(id) } }
                    val onTrash: (Long?) -> Unit = remember(reviewVm) { { id -> reviewVm.trash(id) } }
                    val onUndo = remember(reviewVm) { reviewVm::undo }
                    val onCommit = remember(reviewVm) { reviewVm::commit }
                    val onMove: (MediaAlbum, Long?) -> Unit =
                        remember(reviewVm) { { album, id -> reviewVm.moveTo(album, id) } }
                    val onRestart = remember(reviewVm) { reviewVm::restart }
                    val onDefer = remember(reviewVm) { reviewVm::deferCurrent }
                    val onRetryLoad = remember(reviewVm) { reviewVm::retryLoad }
                    val onCreateAlbum = remember(reviewVm) { reviewVm::createAlbum }
                    val onRestoreStaged = remember(reviewVm) { reviewVm::restoreFromStaging }
                    val onRestoreAllStaged = remember(reviewVm) { reviewVm::restoreAllStaged }
                    val onDiscardChanges = remember(reviewVm) { reviewVm::discardChanges }
                    // 有待提交改动就先问一次（exitConfirmOpen）；没有就直接退出 ——
                    // 没有任何决定要丢的时候，多一个确认框只是挡路。
                    // P10：提交期间关闭 X 已在 UI 侧禁用，此处再拦一道，避免已展开的确认框在提交期间点出。
                    // 直接读 VM 快照而非捕获的 reviewState —— 后者在 remember 无 key 时会停留在首帧。
                    val onCloseReview = remember(reviewVm) {
                        {
                            val snapshot = reviewVm.state.value
                            if (snapshot.submitting) {
                                Unit
                            } else if (snapshot.hasPending) {
                                exitConfirmOpen = true
                            } else {
                                leaveReview()
                            }
                        }
                    }
                    val onOpenHelp = remember { { helpVisible = true } }
                    val onPickScope = remember { { scope: MediaScope -> openScope(scope) } }

                    // 把会话引用交给返回键（见 reviewVmForBack 的注释）
                    SideEffect { reviewVmForBack = reviewVm }

                    ReviewScreen(
                        state = reviewState,
                        scopeLabel = scopeLabel,
                        scopeOptions = scopeOptions,
                        sensitivity = settings.swipeSensitivity,
                        hapticsEnabled = settings.hapticsEnabled,
                        showNextPreview = settings.showNextPreview,
                        onClose = onCloseReview,
                        onKeep = onKeep,
                        onTrash = onTrash,
                        onUndo = onUndo,
                        onCommit = onCommit,
                        onMove = onMove,
                        onOpenHelp = onOpenHelp,
                        onPickScope = onPickScope,
                        onRestart = onRestart,
                        onDefer = onDefer,
                        onRetryLoad = onRetryLoad,
                        onCreateAlbum = onCreateAlbum,
                        onRestoreStaged = onRestoreStaged,
                        onRestoreAllStaged = onRestoreAllStaged,
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (exitConfirmOpen) {
                        ExitReviewDialog(
                            pendingCount = reviewState.pendingTotal,
                            discardableCount = reviewState.discardableCount,
                            onContinue = { exitConfirmOpen = false },
                            onKeepAndLeave = {
                                // P10：提交期间禁止离页 —— 否则 ViewModelStore 清空会取消在飞的 commit()。
                                // 对话框本身不会在提交期间新弹出（BackHandler/onClose 已拦截），
                                // 此处仅兜住“先弹框、再提交”的竞态：只关框，不离页。
                                exitConfirmOpen = false
                                if (reviewVm.state.value.submitting) return@ExitReviewDialog
                                leaveReview()
                            },
                            onDiscardAndLeave = {
                                exitConfirmOpen = false
                                // discardChanges 自带 submitting 兜底（静默返回），
                                // 但 leaveReview 必须同样拦截，否则等同于 BackHandler 穿透。
                                if (reviewVm.state.value.submitting) return@ExitReviewDialog
                                onDiscardChanges()
                                leaveReview()
                            },
                        )
                    }

                }
            }

            // ------------------------------------- 权限尚在判定中（避免闪一下权限页）
            libraryState.access == null -> LoadingScreen()

            // ------------------------------------- 权限引导
            !libraryState.canRead -> PermissionScreen(
                onRequest = { permissionLauncher.launch(MediaPermissions.required) },
                onOpenAppSettings = {
                    // 用完全限定名：本文件同时导入了 Icons.Rounded.Settings，避免同名冲突
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )

            // ------------------------------------- 主壳
            else -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        if (!expanded) {
                            AppNavigationBar(
                                current = selectedTab,
                                onSelect = ::selectTab,
                            )
                        }
                    },
                    floatingActionButton = {
                        // P7：以提升后的 destinationKey 为单一真源 —— 此前手写
                        // `currentTab == Home && !showSettings && !showTrash` 漏了 showDuplicates，
                        // 导致重复照片页仍浮出与本源无关的「开始整理」按钮。
                        if (destinationKey == RootTab.Home.name && libraryState.hasLibrary) {
                            ExtendedFloatingActionButton(
                                onClick = { openScope(MediaScope.Recent) },
                                icon = { Icon(Icons.Rounded.AutoAwesome, null) },
                                text = { Text(stringResource(R.string.home_hero_action)) },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                ) { innerPadding ->
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (expanded) {
                            AppNavigationRail(
                                current = selectedTab,
                                onSelect = ::selectTab,
                            )
                        }

                        // 底部留白由各页面自己的 footer spacer 负责（它们知道 FAB 与导航条的高度）
                        val contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())

                        // P7：destinationKey 已提升至 Scaffold 之外（见顶部），此处直接复用，不再重复声明。
                        AnimatedContent(
                            targetState = destinationKey,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "root-content",
                        ) { destination ->
                            when (destination) {
                                // 重复照片：纯本地比对，进页面才扫（见 DuplicatesScreen 的注释）
                                "duplicates" -> DuplicatesScreen(
                                    groups = libraryState.duplicateGroups,
                                    scanning = libraryState.duplicateScanning,
                                    scanned = libraryState.duplicateScanned,
                                    onScan = libraryVm::scanDuplicates,
                                    onTrash = libraryVm::trashDuplicateImages,
                                    onBack = { showDuplicates = false },
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                // 回收站：从设置页进来，不再占底部导航
                                "trash" -> TrashScreen(
                                    systemTrash = libraryState.trash,
                                    trashCount = libraryState.trashCount,
                                    onRestore = libraryVm::restoreFromSystemTrash,
                                    onDelete = libraryVm::deleteFromSystemTrash,
                                    onLoadMore = libraryVm::loadMoreTrash,
                                    onLoadAll = libraryVm::loadAllTrash,
                                    loadingMore = libraryState.trashLoadingMore,
                                    loadError = libraryState.trashLoadError,
                                    onRetry = libraryVm::loadMoreTrash,
                                    onBack = { showTrash = false },
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                RootTab.Settings.name, "settings" -> SettingsScreen(
                                    settings = settings,
                                    systemTrashCount = libraryState.trashCount,
                                    onOpenTrash = { showTrash = true },
                                    onUpdate = { transform ->
                                        settingsStore.update(transform)
                                    },
                                    onBack = { showSettings = false },
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                RootTab.Albums.name -> AlbumsScreen(
                                    state = libraryState,
                                    onOpenAlbum = { album -> openScope(album) },
                                    onRequestMoreAccess = {
                                        permissionLauncher.launch(MediaPermissions.required)
                                    },
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )

                                else -> HomeScreen(
                                    state = libraryState,
                                    onOpenScope = { scope -> openScope(scope) },
                                    onOpenSettings = { showSettings = true },
                                    onOpenAlbums = { tabName = RootTab.Albums.name },
                                    onOpenDuplicates = { showDuplicates = true },
                                    onRefresh = libraryVm::refresh,
                                    contentPadding = contentPadding,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 主壳之外（整理页 / 权限引导 / 加载态）也必须能弹 snackbar，所以挂在整个壳的最外层 ——
        // 但只在主壳没组合（没带自己的宿主）时挂，见 [shellVisible]
        if (!shellVisible) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }

    if (helpVisible) {
        HelpSheet(onDismiss = { helpVisible = false })
    }
}

@Composable
private fun AppNavigationBar(
    current: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        RootTab.entries.forEach { item ->
            val label = stringResource(item.labelRes)
            NavigationBarItem(
                selected = current == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    current: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        RootTab.entries.forEach { item ->
            val label = stringResource(item.labelRes)
            NavigationRailItem(
                selected = current == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
