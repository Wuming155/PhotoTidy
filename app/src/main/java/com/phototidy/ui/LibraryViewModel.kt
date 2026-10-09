package com.phototidy.ui

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phototidy.Graph
import com.phototidy.R
import com.phototidy.core.media.LibraryOverview
import com.phototidy.core.media.MediaAccess
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaStoreRepository
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.MonthGroup
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
    val trashLoadingMore: Boolean = false,
    val trashLoadError: Boolean = false,
    val showEmptyAlbums: Boolean = false,
) {
    val albums: List<MediaAlbum> get() = overview?.albums.orEmpty()

    val months: List<MonthGroup> get() = overview?.months.orEmpty()

    val totalCount: Int get() = overview?.totalCount ?: 0

    val totalBytes: Long get() = overview?.totalBytes ?: 0L

    /** 最新的一张，首页封面用。 */
    val newest: MediaImage? get() = overview?.newest

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
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var loadedOnce = false
    private var sessionRestored = false

    /**
     * 权限结果回来了。
     *
     * 三条规则：
     *  1) 从无到有 → 扫描；
     *  2) 从「部分授权」升级到「完全授权」→ 必须重新扫描，否则用户会一直只看到那几张选中的照片；
     *  3) 反复拿到同样的结果（例如每次 onResume 都调用）→ 什么都不做。
     */
    fun onAccessResolved(access: MediaAccess) {
        val previous = _state.value.access
        _state.update { it.copy(access = access) }
        if (!access.canRead) return
        if (!loadedOnce || access != previous) {
            loadedOnce = true
            refresh()
        }
    }

    fun refresh() {
        if (!_state.value.canRead) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val overview = runCatching { repo.loadOverview() }
                .onFailure {
                    // 必须说出来。以前这里只往一个没人读的字段里塞文案，
                    // 结果扫描失败对用户完全静默 —— 看起来就像「照片全没了」。
                    // 旧状态（overview）保持不变，所以失败不会把已有内容清空。
                    _state.update { state -> state.copy(loading = false) }
                    ops.emit(res.getString(R.string.library_load_failed))
                }
                .getOrNull() ?: return@launch

            val trashCount = runCatching { repo.countTrashed() }.getOrDefault(0)
            val firstPage = runCatching { repo.pageTrashed(0, TRASH_PAGE_SIZE) }
                .getOrDefault(emptyList())

            _state.update {
                it.copy(
                    loading = false,
                    overview = overview,
                    trash = firstPage,
                    trashCount = trashCount,
                )
            }
            restoreSessionOnce()
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
        val next = runCatching { repo.pageTrashed(current.trash.size, TRASH_PAGE_SIZE) }
            .getOrDefault(emptyList())
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
    }

    /**
     * 冷启动把上次没提交完的「待清除队列」接回来。
     *
     * 只在第一次成功扫描之后做一次；照片在离线期间被删掉的话，
     * [MediaStoreRepository.imagesByIds] 自然查不到，队列里也就不会出现幽灵条目。
     */
    private fun restoreSessionOnce() {
        if (sessionRestored) return
        sessionRestored = true
        val ids = session.loadStaging()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val images = runCatching { repo.imagesByIds(ids) }.getOrDefault(emptyList())
            if (images.isEmpty()) {
                session.clearStaging()
            } else {
                staging.restore(images.sortedBy { ids.indexOf(it.id) })
            }
        }
    }

    /** 让外部（例如设置页）把「显示空相册」这个偏好同步进来。 */
    fun setShowEmptyAlbums(show: Boolean) {
        _state.update { it.copy(showEmptyAlbums = show) }
    }

    /**
     * 某个整理范围里有多少张。
     *
     * 数据来自概览聚合，**不需要**再扫一次库 —— 这是把「总数」和「照片列表」拆开之后
     * 顺带拿到的收益：进整理页之前就已经知道进度条分母了。
     */
    fun scopeCount(scope: MediaScope): Int = when (scope) {
        MediaScope.Recent -> _state.value.totalCount
        is MediaScope.Album -> _state.value.albums.firstOrNull { it.bucketId == scope.bucketId }?.count ?: 0
        is MediaScope.Month -> _state.value.months
            .firstOrNull { it.year == scope.year && it.month == scope.month }?.count ?: 0
    }

    /** 给整理会话用的分页取数器。分页真正落在 SQL 的 LIMIT/OFFSET 上。 */
    fun pageLoader(scope: MediaScope): suspend (Int, Int) -> List<MediaImage> =
        { offset, limit -> repo.pageImages(scope, offset, limit) }

    /** 整理页里可选的「移动目标」：所有相册 + 系统标准目录。 */
    fun moveTargets(currentScope: MediaScope): List<MediaAlbum> {
        val existing = _state.value.albums.filter { it.canReceive }
        val excludeBucket = (currentScope as? MediaScope.Album)?.bucketId
        val fromDevice = existing.filter { it.bucketId != excludeBucket }

        // 补上系统标准目录，即使用户还没有照片放进去，也允许把照片归到这些位置
        val standard = listOf(
            res.getString(R.string.album_camera) to "DCIM/Camera",
            res.getString(R.string.album_screenshots) to "Pictures/Screenshots",
            res.getString(R.string.album_downloads) to "Pictures/Downloads",
        )
        val missing = standard
            .filterNot { (name, _) -> fromDevice.any { it.name == name } }
            .map { (name, path) ->
                MediaAlbum(
                    bucketId = -name.hashCode().toLong(),
                    name = name,
                    relativePath = path,
                    count = 0,
                    cover = null,
                )
            }
        return fromDevice + missing
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
                onSuccess = { refresh() },
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

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    repo = Graph.repository,
                    ops = Graph.mediaOps,
                    staging = Graph.stagingTrash,
                    session = Graph.sessionStore,
                    res = Graph.resources,
                )
            }
        }
    }
}
