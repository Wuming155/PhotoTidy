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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phototidy.Graph
import com.phototidy.R
import com.phototidy.core.media.MediaPermissions
import com.phototidy.core.model.MediaScope
import com.phototidy.core.settings.SettingsStore
import com.phototidy.ui.albums.AlbumsScreen
import com.phototidy.ui.components.HelpSheet
import com.phototidy.ui.components.PermissionScreen
import com.phototidy.ui.components.shortLabel
import com.phototidy.ui.home.HomeScreen
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
        libraryVm.onAccessResolved(MediaPermissions.interpret(result))
    }

    // 首次组合 + 每次回到前台都重新判定一次：用户可能刚刚在系统设置里改了授权范围
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        libraryVm.onAccessResolved(MediaPermissions.current(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                libraryVm.onAccessResolved(MediaPermissions.current(context))
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
        Graph.mediaOps.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(settings.showEmptyAlbums) {
        libraryVm.setShowEmptyAlbums(settings.showEmptyAlbums)
    }

    // ---------------------------------------------------------------- 导航
    var tabName by rememberSaveable { mutableStateOf(RootTab.Home.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var scopeEncoded by rememberSaveable { mutableStateOf("") }
    var helpVisible by remember { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }

    val reviewScope = MediaScope.decode(scopeEncoded)
    val currentTab = RootTab.entries.firstOrNull { it.name == tabName } ?: RootTab.Home
    val selectedTab = if (showSettings) RootTab.Settings else currentTab

    fun selectTab(selected: RootTab) {
        if (selected == RootTab.Settings) {
            showSettings = true
        } else {
            tabName = selected.name
            showSettings = false
        }
    }

    fun openScope(scope: MediaScope) {
        scopeEncoded = MediaScope.encode(scope)
    }

    // 首次进入整理页自动讲一次手势（只讲一次，之后不再打扰）
    LaunchedEffect(reviewScope?.key) {
        if (reviewScope != null && !settings.helpShown) {
            helpVisible = true
            settingsStore.markHelpShown()
        }
    }

    // 系统返回 / 预测性返回：整理页 → 设置页 → 交给系统
    BackHandler(enabled = reviewScope != null) { scopeEncoded = "" }
    BackHandler(enabled = reviewScope == null && showTrash) { showTrash = false }
    BackHandler(enabled = reviewScope == null && !showTrash && showSettings) { showSettings = false }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // ------------------------------------- 整理页（全屏，无导航壳）
            reviewScope != null -> {
                if (libraryState.totalCount == 0 && libraryState.loading) {
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
                    val total = libraryVm.scopeCount(reviewScope)
                    val reviewVm: ReviewViewModel = viewModel(
                        key = "review-${reviewScope.key}",
                        factory = ReviewViewModel.factory(
                            scope = reviewScope,
                            total = total,
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
                        libraryState.totalCount,
                    ) {
                        buildList {
                            add(
                                ScopeOption(
                                    scope = MediaScope.Recent,
                                    count = libraryState.totalCount,
                                    group = ScopeGroup.Recent,
                                ),
                            )
                            libraryState.albums.forEach { album ->
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Album(album.bucketId, album.name),
                                        count = album.count,
                                        group = ScopeGroup.Album,
                                    ),
                                )
                            }
                            libraryState.months.forEach { month ->
                                add(
                                    ScopeOption(
                                        scope = MediaScope.Month(month.year, month.month),
                                        count = month.count,
                                        group = ScopeGroup.Month,
                                    ),
                                )
                            }
                        }
                    }

                    // 记忆化回调：`reviewVm::keep` 这类绑定方法引用每次重组都会新建实例，
                    // 参数实例不等 ⇒ Compose 无法跳过子组件 —— 而这里每次滑动都会重组，
                    // 于是每滑一张整棵整理子树（含带 LazyRow 的快捷移动条）都要重跑。
                    val onKeep = remember(reviewVm) { reviewVm::keep }
                    val onTrash = remember(reviewVm) { reviewVm::trash }
                    val onUndo = remember(reviewVm) { reviewVm::undo }
                    val onCommit = remember(reviewVm) { reviewVm::commit }
                    val onMove = remember(reviewVm) { reviewVm::moveTo }
                    val onRestart = remember(reviewVm) { reviewVm::restart }
                    val onDefer = remember(reviewVm) { reviewVm::deferCurrent }
                    val onCloseReview = remember { { scopeEncoded = "" } }
                    val onOpenHelp = remember { { helpVisible = true } }
                    val onPickScope = remember { { scope: MediaScope -> openScope(scope) } }

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
                        modifier = Modifier.fillMaxSize(),
                    )

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
                        if (currentTab == RootTab.Home && !showSettings && !showTrash && libraryState.hasLibrary) {
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

                        val destinationKey = when {
                            showTrash -> "trash"
                            showSettings -> "settings"
                            else -> currentTab.name
                        }
                        AnimatedContent(
                            targetState = destinationKey,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "root-content",
                        ) { destination ->
                            when (destination) {
                                // 回收站：从设置页进来，不再占底部导航
                                "trash" -> TrashScreen(
                                    systemTrash = libraryState.trash,
                                    trashCount = libraryState.trashCount,
                                    onRestore = libraryVm::restoreFromSystemTrash,
                                    onDelete = libraryVm::deleteFromSystemTrash,
                                    onLoadMore = libraryVm::loadMoreTrash,
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
                                        libraryVm.setShowEmptyAlbums(transform(settings).showEmptyAlbums)
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

        // snackbar 在整理页也必须可见，所以挂在整个壳的最外层
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars),
        )
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
