package com.phototidy.ui

import android.content.ContentResolver
import android.content.res.Resources
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phototidy.Graph
import com.phototidy.R
import com.phototidy.core.media.DuplicateFinder
import com.phototidy.core.media.DuplicateGroup
import com.phototidy.core.media.LibraryOverview
import com.phototidy.core.media.MediaAccess
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaStoreRepository
import com.phototidy.core.media.ReviewedLedger
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.media.isImageDestinationLegal
import com.phototidy.core.media.isVideoDestinationLegal
import com.phototidy.core.media.isSystemMoveTargetPath
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.MonthGroup
import com.phototidy.util.runCatchingUnlessCancelled
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 系统回收站一次加载多少张。 */
private const val TRASH_PAGE_SIZE = 200

data class LibraryUiState(
    /**
     * null 表示「还没判定」。
     *
     * 这个 null 很重要：它不是「没权限」，而是「还不知道」。
     * 如果初始值直接给 Denied，已授权用户在冷启动的第一帧会看到权限引导页闪一下 ——
     * 体验上就像应用每次启动都怀疑你一次。
     */
    val access: MediaAccess? = null,
    val loading: Boolean = false,
    /**
     * 聚合概览：相册 / 月份 / 总数 / 总大小。
     *
     * 注意这里**没有** `images: List<MediaImage>` —— 照片库规模再大，
     * 这一层的内存占用都只跟相册数、月份数有关（见 [LibraryOverview] 的注释）。
     */
    val overview: LibraryOverview? = null,
    /** 系统回收站已加载的那几页。 */
    val trash: List<MediaImage> = emptyList(),
    val trashCount: Int = 0,
    /** 截图范围：一共多少张。0 = 设备上没有截图，首页与范围选择器都不给入口。 */
    val screenshotCount: Int = 0,
    /** 截图范围里还没整理过多少张。 */
    val screenshotRemaining: Int = 0,
    /** 重复照片：是否正在比对。 */
    val duplicateScanning: Boolean = false,
    /** 已经比对过一轮（用来区分「还没扫」和「扫完发现没有」）。 */
    val duplicateScanned: Boolean = false,
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val trashLoadingMore: Boolean = false,
    val trashLoadError: Boolean = false,
) {
    val albums: List<MediaAlbum> get() = overview?.albums.orEmpty()

    /** 整理入口只列仍有未处理照片的月份；完整照片库仍保存在 [overview] 中供浏览。 */
    val months: List<MonthGroup> get() = overview?.months.orEmpty().filter { it.remaining > 0 }

    val totalCount: Int get() = overview?.totalCount ?: 0

    /**
     * 默认来源目录中、尚未被整理账本记录的照片数；整理首页只展示这部分。
     * 自建相册里的照片视为已归类，完整库总数仍由 [totalCount] 提供给相册页。
     */
    val remainingTotal: Int get() = overview?.remainingCount ?: 0

    /** 整理页只展示待整理照片占用的空间。 */
    val remainingBytes: Long get() = overview?.remainingBytes ?: 0L

    /** 已整理的总张数，首页入口上的数字。 */
    val reviewedCount: Int get() = (totalCount - remainingTotal).coerceAtLeast(0)

    /** 最新的一张待整理照片，整理页封面用。 */
    val newestPending: MediaImage? get() = overview?.newestPending

    val hasLibrary: Boolean get() = totalCount > 0

    val canRead: Boolean get() = access?.canRead == true

    val isPartial: Boolean get() = access?.isPartial == true
}

/**
 * 照片库状态。
 *
 * 只做「读取 + 系统回收站的写操作」，不掺任何整理会话的逻辑。
 * 会话状态（排队区、撤销栈）全在 ReviewViewModel 里，两者互不引用。
 */
class LibraryViewModel(
    private val repo: MediaStoreRepository,
    private val ops: MediaOpCoordinator,
    private val staging: StagingTrash,
    private val session: SessionStore,
    private val res: Resources,
    /** 「已整理」账本：概览扫描要用它把待整理数从总数里扣出来。 */
    private val reviewed: ReviewedLedger,
    /** 重复照片比对（纯本地，见 [DuplicateFinder]）。 */
    private val duplicates: DuplicateFinder,
    private val contentResolver: ContentResolver? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var loadedOnce = false
    private var sessionRestored = false

    /** 正在扫描中。同时充当并发刷新的合并开关（见 [refresh]）。 */
    private var refreshing = false

    /** 扫描期间又来了刷新请求 —— 跑完补一次，不能直接丢。 */
    private var refreshQueued = false

    private var isResumed = false
    @Volatile
    private var mediaDirty = false
    private var debounceJob: Job? = null
    private var observerRegistered = false

    private val mediaObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged()
        }
    }

    /**
     * 收到 MediaStore 变动通知。
     * 若应用在前台，进行 500ms 防抖合并后触发刷新；
     * 若应用在后台，只记录 mediaDirty，待回到前台时在 onResume 对账刷新。
     */
    fun onMediaChanged() {
        if (isResumed) {
            mediaDirty = true
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(500L)
                if (isResumed && mediaDirty) {
                    mediaDirty = false
                    refresh()
                }
            }
        } else {
            mediaDirty = true
        }
    }

    fun onResume() {
        isResumed = true
        if (mediaDirty) {
            mediaDirty = false
            debounceJob?.cancel()
            refresh()
        }
    }

    fun onPause() {
        isResumed = false
        debounceJob?.cancel()
    }

    private fun registerObserverIfNeeded() {
        if (!observerRegistered && contentResolver != null) {
            try {
                contentResolver.registerContentObserver(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                    true,
                    mediaObserver,
                )
                observerRegistered = true
            } catch (_: SecurityException) {
            }
        }
    }

    private fun unregisterObserverIfNeeded() {
        debounceJob?.cancel()
        mediaDirty = false
        if (observerRegistered && contentResolver != null) {
            try {
                contentResolver.unregisterContentObserver(mediaObserver)
            } catch (_: Exception) {
            }
            observerRegistered = false
        }
    }

    /**
     * 权限结果回来了。
     *
     * 三条规则：
     *  1) 从无到有 → 扫描；
     *  2) 从「部分授权」升级到「完全授权」→ 必须重新扫描，否则用户会一直只看到那几张选中的照片；
     *  3) 只要当前是**部分授权**，每次回到前台都重扫。
     *
     * 第 3 条看着像浪费，其实是必需的：Android 14+ 的「选择更多照片」不走应用的权限回调，
     * 用户重新勾一批之后 `READ_MEDIA_VISUAL_USER_SELECTED` 仍是已授予 ——
     * `MediaPermissions.current()` 前后都返回 [MediaAccess.Partial]，状态**没有变化**。
     * 只比枚举值的话，新授权的那批照片在界面上永远不会出现，
     * 而相册页那条横幅的按钮承诺的正是「选完就能看到更多」。部分授权是不稳定状态，
     * 不能用「状态是否变化」来判断要不要重扫。
     */
    fun onAccessResolved(access: MediaAccess) {
        val previous = _state.value.access
        _state.update { it.copy(access = access) }
        if (access.canRead) {
            registerObserverIfNeeded()
        } else {
            unregisterObserverIfNeeded()
        }
        if (!access.canRead) return
        if (!loadedOnce || access != previous || access.isPartial) {
            loadedOnce = true
            refresh()
        }
    }

    /**
     * 重新扫描照片库。
     *
     * 并发请求会被**合并**：同一份只读数据不需要两遍全表扫描，但后到的请求也不能直接丢掉 ——
     * 它可能是「提交之后」触发的，那时照片库已经变了，丢掉就会让界面停在旧数据上。
     * 所以记一个待办，当前这轮跑完再补一次。
     */
    fun refresh() {
        if (!_state.value.canRead) return
        if (refreshing) {
            refreshQueued = true
            return
        }
        refreshing = true
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                // 概览（全表扫描）、回收站计数、回收站首页、截图统计四路查询互不依赖，并行取：
                // 大库上刷新耗时从「四者之和」收敛为「最慢的一个」。
                // runCatching 换成不吞取消的变体：取消是流程控制不是失败，
                // 吞掉会让「刷新被取消」冒充成「读取失败」弹给用户。
                val overviewDeferred = async {
                    runCatchingUnlessCancelled { repo.loadOverview(reviewed::contains) }
                }
                val trashCountDeferred = async { runCatchingUnlessCancelled { repo.countTrashed() } }
                val firstPageDeferred =
                    async { runCatchingUnlessCancelled { repo.pageTrashed(0, TRASH_PAGE_SIZE) } }
                // 截图统计也是一条独立查询（见 loadScreenshotStats 的注释），同样并行取。
                // 截图通常只有几百张，这条查询的代价与照片库规模无关。
                val screenshotsDeferred =
                    async { runCatchingUnlessCancelled { repo.loadScreenshotStats(reviewed::contains) } }

                val overview = overviewDeferred.await()
                    .onFailure {
                        // 必须说出来。以前这里只往一个没人读的字段里塞文案，
                        // 结果扫描失败对用户完全静默 —— 看起来就像「照片全没了」。
                        // 旧状态（overview）保持不变，所以失败不会把已有内容清空。
                        ops.emit(res.getString(R.string.library_load_failed))
                    }
                    .getOrNull()

                if (overview != null) {
                    val trashCount = trashCountDeferred.await().getOrDefault(0)
                    val firstPage = firstPageDeferred.await().getOrDefault(emptyList())
                    // 这条查询失败不等于照片库坏了：截图为 null 时把两个数字**保持原样**，
                    // 而不是清成 0 —— 清 0 会让首页磁贴突然消失，看起来像截图被删了。
                    val screenshots = screenshotsDeferred.await().getOrNull()

                    _state.update {
                        it.copy(
                            loading = false,
                            overview = overview,
                            trash = firstPage,
                            trashCount = trashCount,
                            screenshotCount = screenshots?.first ?: it.screenshotCount,
                            screenshotRemaining = screenshots?.second ?: it.screenshotRemaining,
                        )
                    }
                    // 暂存队列的恢复必须挂在「扫描成功」之后：照片 id 要靠 MediaStore 换回完整元数据。
                    // 首次扫描失败时它会推迟到下一次成功的扫描 —— 这是刻意的，
                    // 否则恢复出来的队列可能是基于一份不完整的库。
                    restoreSessionOnce()
                }
            } finally {
                // 取消路径（runCatchingUnlessCancelled 不再吞 CancellationException）
                // 也必须把 loading / refreshing 复位。当前取消等价于 VM 销毁、复位无人可见；
                // 这层兜的是将来 refresh 被挪进更窄作用域/加防抖时的「永久转圈 + 闸门锁死」。
                _state.update { it.copy(loading = false) }
                refreshing = false
            }
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }

    /** 系统回收站向下翻页。 */
    fun loadMoreTrash() {
        if (_state.value.trashLoadingMore) return
        if (_state.value.trash.size >= _state.value.trashCount) return
        viewModelScope.launch { loadNextTrashPage() }
    }

    /**
     * 一次性把回收站剩下的页全读完。
     *
     * 给「全选」用：全选必须选中回收站里的**每一张**，而列表是分页的，
     * 只选已加载的那几页就成了「选了一部分却说是全选」——那比没有全选更误导。
     *
     * 靠 [loadNextTrashPage] 里的 `size >= trashCount` 判据收口，不会无限翻页；
     * `trashLoadingMore` 兼作互斥锁，与 [loadMoreTrash] 不会并发抓同一页。
     */
    fun loadAllTrash() {
        if (_state.value.trashLoadingMore) return
        viewModelScope.launch {
            while (loadNextTrashPage() == TRASH_PAGE_SIZE) {
                // 取满一页说明后面还有，继续；取不满或取空都在下一轮判据里自然收口
            }
        }
    }

    /** 读下一页，返回本页实际条数（0 = 到底了或读取失败）。 */
    private suspend fun loadNextTrashPage(): Int {
        val current = _state.value
        if (current.trash.size >= current.trashCount) return 0
        _state.update { it.copy(trashLoadingMore = true, trashLoadError = false) }
        try {
            // 取数不能吞掉取消（用 runCatchingUnlessCancelled 而不是 runCatching）：
            // 取消被吞成「空页」之后，下面那次 update 会把 trashCount 截断成「已加载的页数」
            // 并置上 loadError —— 一个还活着的会话会因此以为回收站读完了，
            // 正是 TrashScreen 里反复警告的「拿一部分冒充全部」。
            val next = runCatchingUnlessCancelled {
                repo.pageTrashed(current.trash.size, TRASH_PAGE_SIZE)
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    trash = it.trash + next,
                    // 取不满一页说明到头了；把真实条数收敛一下，避免滚动到底还在请求
                    trashCount = if (next.size < TRASH_PAGE_SIZE) it.trash.size + next.size else it.trashCount,
                    trashLoadingMore = false,
                    // 期望还有更多却空手而归 = 本次加载失败。置位让 UI 给出重试入口，
                    // 否则 LaunchedEffect(systemTrash.size) 因 size 不变不再触发，spinner 会一直转（见 FYI2）。
                    trashLoadError = next.isEmpty() && it.trash.size < it.trashCount,
                )
            }
            return next.size
        } finally {
            // 取消路径上那次 update 会被整段跳过，这里兜住闸门 —— 与 refresh() 的 finally
            // 同一模式。当前取消等价于 VM 销毁、复位无人可见，这层兜的是将来把这个函数
            // 挪进更窄作用域时的「永久转圈 + 闸门锁死」。
            _state.update { it.copy(trashLoadingMore = false) }
        }
    }

    /**
     * 冷启动把上次没提交完的「待清除队列」接回来。
     *
     * 只在第一次成功扫描之后做一次；照片在离线期间被删掉的话，
     * [MediaStoreRepository.imagesByIds] 自然查不到，队列里也就不会出现幽灵条目。
     *
     * P4a：必须用 [runCatchingUnlessCancelled] 且只有 `Result.success(emptyList)` 时才
     * `clearStaging()` —— `runCatching` 会把取消吞成空列表，Activity finish / VM clear 时
     * 恰有一笔在飞的恢复查询会被吞成 `emptyList()`，进而把整份待清除快照永久删掉；
     * 一次 MediaProvider 重启或权限范围变化造成的瞬时异常同理。失败/取消一律保留快照，
     * 等下次重试（此处重置 `sessionRestored` 以允许下次成功扫描时重试）。
     * P4b：恢复前后需开关 `StagingTrash` 的落盘门控，避免恢复完成前被覆写为空。
     */
    private fun restoreSessionOnce() {
        if (sessionRestored) return
        sessionRestored = true
        val ids = session.loadStaging()
        if (ids.isEmpty()) {
            // 无快照也需开门，否则后续 StagingTrash 的自持落盘会被永久拦截。
            staging.markRestored()
            return
        }
        viewModelScope.launch {
            val result = runCatchingUnlessCancelled { repo.imagesByIds(ids) }
            if (result.isFailure) {
                // 失败/取消：保留快照等下次重试，本次不开门（P4b 门控继续拦截落盘，
                // 避免把磁盘上的快照覆写成空），并允许下次成功扫描时重试。
                sessionRestored = false
                return@launch
            }
            val images = result.getOrNull().orEmpty()
            if (images.isEmpty()) {
                session.clearStaging()
                staging.markRestored()
            } else {
                // 排队顺序必须还原（落盘只有 id，回库后按原次序排回）。
                // 先建一次 id→序号 的索引：逐张 ids.indexOf 是 O(n²)，千张规模的
                // 暂存队列在主线程上要白做几百万次比较 —— 而这段跑在冷启动路径上。
                val order = HashMap<Long, Int>(ids.size * 2)
                ids.forEachIndexed { index, id -> order[id] = index }
                val sorted = images.sortedBy { order[it.id] ?: Int.MAX_VALUE }
                staging.restore(sorted, pruned = images.size != ids.size)
            }
        }
    }

    // ------------------------------------------------------ 重复照片

    /**
     * 比对全库的重复照片。
     *
     * 刻意**不做开机预热**：这条扫描要解码几百张缩略图，放在冷启动路径上
     * 等于让每个用户为「可能根本不存在的重复照片」先等几秒。改成进页面才扫。
     */
    fun scanDuplicates() {
        if (_state.value.duplicateScanning) return
        viewModelScope.launch {
            _state.update { it.copy(duplicateScanning = true) }
            try {
                val groups = runCatchingUnlessCancelled { duplicates.findDuplicates() }
                    .onFailure { ops.emit(res.getString(R.string.duplicate_scan_failed)) }
                    .getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        duplicateScanning = false,
                        duplicateScanned = true,
                        duplicateGroups = groups,
                    )
                }
            } finally {
                // 取消路径（离开页面）也必须复位，否则下次进来会卡在「正在比对」
                _state.update { it.copy(duplicateScanning = false) }
            }
        }
    }

    /**
     * 把重复照片里挑出来的那些移进系统回收站。
     *
     * 走的是与「清除」完全相同的语义与通道：`createTrashRequest` 是**授权即执行**，
     * 用户点同意时系统就已经做完了（见 [MediaStoreRepository.moveToSystemTrash]）——
     * 所以这里没有直删快路径，照片在系统回收站里还能还原 30 天。
     */
    fun trashDuplicateImages(images: List<MediaImage>) {
        if (images.isEmpty()) return
        val removed = images.map { it.id }.toSet()
        viewModelScope.launch {
            ops.runStaged(
                successMessage = res.getString(R.string.duplicate_trashed, images.size),
                onSuccess = {
                    // 已经做过决定：进回收站的照片不该再回到整理队列
                    images.forEach { reviewed.mark(it.id) }
                    reviewed.flush()
                    // 就地收敛剩下的组，不再重扫一遍全库 —— 用户刚刚才看过这些结果
                    _state.update { state ->
                        state.copy(
                            duplicateGroups = state.duplicateGroups.mapNotNull { group ->
                                val left = group.images.filterNot { it.id in removed }
                                if (left.size >= 2) {
                                    DuplicateGroup(left, left.drop(1).sumOf { it.sizeBytes })
                                } else {
                                    null
                                }
                            },
                        )
                    }
                    refresh()
                },
                consentBudget = 1,
            ) { attempt -> repo.moveToSystemTrash(images.map { it.id }, attempt) }
        }
    }

    /**
     * 某个整理范围里一共多少张照片。
     *
     * 数据来自概览聚合，**不需要**再扫一次库 —— 这是把「总数」和「照片列表」拆开之后
     * 顺带拿到的收益：进整理页之前就已经知道分页上界了。
     *
     * 注意：这是**分页上界**，不是进度条分母。分母要用 [scopeRemaining]。
     */
    fun scopeCount(scope: MediaScope): Int = when (scope) {
        MediaScope.Recent -> _state.value.totalCount
        MediaScope.Screenshots -> _state.value.screenshotCount
        is MediaScope.Album -> _state.value.albums.firstOrNull { it.bucketId == scope.bucketId }?.count ?: 0
        is MediaScope.Month -> _state.value.months
            .firstOrNull { it.year == scope.year && it.month == scope.month }?.count ?: 0
    }

    /**
     * 某个范围内**还没整理**的张数 —— 进度条分母与首页数字都用它。
     *
     * 与 [scopeCount] 的差值就是「这个范围已经整理掉多少张」，这正是用户要看到的区别。
     */
    fun scopeRemaining(scope: MediaScope): Int = when (scope) {
        MediaScope.Recent -> _state.value.remainingTotal
        MediaScope.Screenshots -> _state.value.screenshotRemaining
        is MediaScope.Album -> _state.value.albums.firstOrNull { it.bucketId == scope.bucketId }
            ?.let { if (scope.browseAll) it.count else it.remaining } ?: 0

        is MediaScope.Month -> _state.value.months
            .firstOrNull { it.year == scope.year && it.month == scope.month }?.remaining ?: 0
    }

    /** 给整理会话用的分页取数器。分页真正落在 SQL 的 LIMIT/OFFSET 上。 */
    fun pageLoader(scope: MediaScope): suspend (Int, Int) -> List<MediaImage> =
        { offset, limit -> repo.pageImages(scope, offset, limit) }

    /** 整理页里可选的「移动目标」：真实相册；系统标准目录不作为归档目标。 */
    fun moveTargets(currentScope: MediaScope): List<MediaAlbum> {
        // 留下照片或视频可落入的真实目录，并排除系统默认相册；具体按当前媒体类型再筛一次。
        val existing = _state.value.albums
            .filter {
                it.canReceive && (isImageDestinationLegal(it.relativePath) ||
                    isVideoDestinationLegal(it.relativePath))
            }
            .filterNot { isSystemMoveTargetPath(it.relativePath) }
        val excludeBucket = (currentScope as? MediaScope.Album)?.bucketId
        val fromDevice = existing.filter { it.bucketId != excludeBucket }
        return fromDevice
    }

    // ------------------------------------------------------ 系统回收站

    /**
     * 系统回收站还原。
     *
     * 走 [MediaOpCoordinator.runStaged] 而非 [MediaOpCoordinator.run]：还原和进回收站一样，
     * 是「授权即执行」的一次性请求（`createTrashRequest(..., false)`），
     * 授权回来后必须让仓库知道「这是第 1 次尝试」而直接判成功 —— 见 [MediaStoreRepository.restoreFromSystemTrash]。
     */
    fun restoreFromSystemTrash(images: List<MediaImage>) {
        if (images.isEmpty()) return
        viewModelScope.launch {
            ops.runStaged(
                successMessage = res.getString(R.string.trash_restored, images.size),
                // 还原 = 照片回到照片库，于是它**重新变成未整理** —— 参考实现的做法
                // （「恢复后这些照片会被视为未分类」）。不抹掉账本的话，还原回来的照片
                // 会永远排不进整理队列，用户再也没法决定它们。
                onSuccess = {
                    reviewed.unmark(images.map { it.id })
                    reviewed.flush()
                    refresh()
                },
                consentBudget = 1,
            ) { attempt -> repo.restoreFromSystemTrash(images.map { it.id }, attempt) }
        }
    }

    fun deleteFromSystemTrash(images: List<MediaImage>) {
        if (images.isEmpty()) return
        viewModelScope.launch {
            ops.runStaged(
                successMessage = res.getString(R.string.trash_deleted, images.size),
                onSuccess = { refresh() },
                consentBudget = 1,
            ) { attempt -> repo.requestPermanentDelete(images, attempt) }
        }
    }

    // ------------------------------------------------------ 整理会话 ViewModelStore 生命周期管理
    /**
     * 整理会话专用的 ViewModelStore。
     *
     * 由 LibraryViewModel 持有，因此在屏幕旋转/窗口调整等配置变更时得以保留；
     * 当用户主动退出整理页（leaveReview）时由 [clearReviewSession] 清理，
     * 触发 ReviewViewModel.onCleared() 并释放窗口队列，避免在 Activity 的 ViewModelStore 中堆积。
     */
    private var reviewViewModelStore: ViewModelStore? = null

    fun getReviewViewModelStore(): ViewModelStore =
        reviewViewModelStore ?: ViewModelStore().also { reviewViewModelStore = it }

    fun clearReviewSession() {
        reviewViewModelStore?.clear()
        reviewViewModelStore = null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterObserverIfNeeded()
        debounceJob?.cancel()
        reviewViewModelStore?.clear()
        reviewViewModelStore = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    repo = Graph.repository,
                    ops = Graph.mediaOps,
                    staging = Graph.stagingTrash,
                    session = Graph.sessionStore,
                    res = Graph.resources,
                    reviewed = Graph.reviewedLedger,
                    duplicates = Graph.duplicateFinder,
                    contentResolver = Graph.context.contentResolver,
                )
            }
        }
    }
}
