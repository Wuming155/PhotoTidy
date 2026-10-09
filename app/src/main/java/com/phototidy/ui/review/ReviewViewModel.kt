package com.phototidy.ui.review

import com.phototidy.core.StringProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phototidy.Graph
import com.phototidy.R
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaRepository
import com.phototidy.core.media.computeTargetPath
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.PendingMove
import com.phototidy.core.model.ReviewAction
import com.phototidy.core.model.ReviewRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val total: Int = 0,
    val processed: Int = 0,
    val current: MediaImage? = null,
    val next: MediaImage? = null,
    /** 已排队、等提交的「进回收站」数量 */
    val stagedCount: Int = 0,
    /** 已排队、等提交的「移动」数量 */
    val pendingMoveCount: Int = 0,
    val canUndo: Boolean = false,
    val finished: Boolean = false,
    /** 正在从 MediaStore 取下一窗照片；UI 该显示转圈而不是空卡片 */
    val preparing: Boolean = false,
    val moveTargets: List<MediaAlbum> = emptyList(),
    val keptCount: Int = 0,
    val trashedCount: Int = 0,
    val movedCount: Int = 0,
    val submitting: Boolean = false,
    val submittingLabel: String = "",
    /** 本次会话的改动已经提交到系统相册 */
    val committed: Boolean = false,
) {
    val progress: Float
        get() = if (total == 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)

    /** 排队中、尚未落到系统相册的改动总数 */
    val pendingTotal: Int get() = stagedCount + pendingMoveCount

    val hasPending: Boolean get() = pendingTotal > 0
}

/** 一次从 MediaStore 取多少张。 */
private const val PAGE_SIZE = 150

/** 窗口剩余低于这个数量就提前补页，保证用户滑到底之前下一张已经就位。 */
private const val REFILL_BELOW = PAGE_SIZE / 2

/**
 * 整理会话。
 *
 * 核心不变量：**在用户点「提交」之前，系统相册不会有任何变化。**
 *
 * 三个动作全部只改内存：
 *  · 保留 —— 什么都不做，只是看下一张；
 *  · 清除 —— 进内存暂存回收站；
 *  · 移动到相册 —— 记进待移动表。
 *
 * 于是整个整理过程是零**媒体**写入、零权限弹窗的：滑 500 张不会触发一次系统对话框，
 * 也不会因为某张照片写失败而卡住节奏。到用户点「提交」时，才把整批改动
 * 合并成**一次**系统授权 + 一次批量写入。
 *
 * ## 为什么队列不是 `List<MediaImage>`
 *
 * 早期实现把整个范围（「最近」= 全库）一次性读进 `ArrayDeque`，
 * 500GB / 12.5 万张照片下光照片对象就是几十 MB，开局要等一次全表物化。
 * 现在改成**滑动窗口**：内存里最多 150 张，滑到还剩一半就异步补下一页
 * （分页落在 SQL 的 `LIMIT/OFFSET`，见 [MediaStoreRepository.pageImages]）。
 *
 * ## 撤销栈与计数
 *
 * `history` 只保存动作记录（约 48 字节/条），不重复持有照片本身。
 * 计数（保留/清除/移动/已处理）全部走**增量维护的标量** ——
 * 早期版本每次滑动都要 `history.toList()` 再跑三遍 `count { }`，
 * 长会话下是 O(n²)，滑到后面每一张都明显变卡。
 */
class ReviewViewModel(
    private val repo: MediaRepository,
    private val staging: StagingTrash,
    private val ops: MediaOpCoordinator,
    private val session: SessionStore,
    private val strings: StringProvider,
    private val scope: MediaScope,
    totalCount: Int,
    initialMoveTargets: List<MediaAlbum>,
    /** 按 offset/limit 从数据层取一页照片。 */
    private val loader: suspend (offset: Int, limit: Int) -> List<MediaImage>,
    /** 提交成功后回调，让外层重新扫描照片库（首页 / 相册 / 回收站的数据都变了）。 */
    private val onCommitted: () -> Unit = {},
) : ViewModel() {

    private val totalCount = totalCount

    /** 已加载、尚未消费的照片。 */
    private val window = ArrayDeque<MediaImage>()

    /** 「跳过这张，稍后再看」的照片，排在整条队列的最后。 */
    private val deferred = ArrayDeque<MediaImage>()

    /** 已经从数据层取到第几张（不含窗口里还没消费的）。 */
    private var loadedThrough = 0

    private var loadingPage = false

    /** 代际号：`restart()` 之后作废在途的分页请求，避免旧页污染新队列。 */
    private var generation = 0

    private val history = ArrayDeque<ReviewRecord>()

    /** 已排队但还没提交的移动：照片 id → 待移动记录 */
    private val pendingMoves = LinkedHashMap<Long, PendingMove>()

    // ---- 增量计数器：syncState() 必须是 O(1) ----
    // trashedCount / movedCount 不再单独维护：直接在 syncState() 里由 staging.items / pendingMoves 派生，
    // 从根上消除「计数器与队列背离」（见 Nit5）。
    private var processedCount = 0
    private var keptCount = 0

    private val _state = MutableStateFlow(
        ReviewUiState(
            total = totalCount,
            moveTargets = initialMoveTargets,
            preparing = totalCount > 0,
        ),
    )
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            staging.items.collect { items -> _state.update { it.copy(stagedCount = items.size) } }
        }
        restorePendingMoves()
        ensureWindow()
    }

    // ------------------------------------------------------------ 主流程动作

    /** ✔ 保留：什么都不做，只是看下一张。 */
    fun keep() {
        val image = advance() ?: return
        history.addLast(ReviewRecord(image, ReviewAction.Keep))
        processedCount++
        keptCount++
        syncState()
    }

    /**
     * ✕ 清除：只进内存暂存区。
     * 这里**没有**任何文件操作，所以即使连续滑 500 张也不会有一次媒体写入。
     */
    fun trash() {
        val image = advance() ?: return
        staging.stage(image)
        history.addLast(ReviewRecord(image, ReviewAction.Trash))
        processedCount++
        persist()
        syncState()
    }

    /**
     * ⬇ 移动到相册：同样只排队，不落盘。
     *
     * 一改一存的做法会让用户在整理过程中反复遇到系统授权框；
     * 排到提交时一次性批量处理，用户全程只需要点一次「允许」。
     */
    fun moveTo(album: MediaAlbum) {
        val image = advance() ?: return
        pendingMoves[image.id] = PendingMove(
            imageId = image.id,
            targetPath = computeTargetPath(album),
            albumName = album.name,
            bucketId = album.bucketId,
            albumPath = album.relativePath,
        )
        history.addLast(ReviewRecord(image, ReviewAction.Move, targetAlbum = album.name))
        processedCount++
        persist()
        syncState()
    }

    /**
     * ↩ 撤销上一个动作。
     * 因为所有动作都还在内存里，撤销就是纯粹的内存回滚 —— 不会产生任何写操作。
     */
    fun undo() {
        val last = history.removeLastOrNull() ?: return

        when (last.action) {
            ReviewAction.Trash -> {
                staging.unstage(last.image.id)
            }

            ReviewAction.Move -> {
                pendingMoves.remove(last.image.id)
            }

            ReviewAction.Keep -> keptCount--
        }
        processedCount--

        // 放回窗口头部，用户看到的就是「刚才那张又回来了」
        window.addFirst(last.image)
        persist()
        syncState()
    }

    /** 队列空了之后想再过一遍。已排队待提交的改动保留 —— 那是用户已经做出的决定。 */
    fun restart() {
        generation++
        window.clear()
        deferred.clear()
        loadedThrough = 0
        loadingPage = false
        history.clear()
        processedCount = 0
        keptCount = 0
        syncState()
    }

    /** 跳过当前这张，放到队尾稍后再看。 */
    fun deferCurrent() {
        val image = advance() ?: return
        deferred.addLast(image)
        syncState()
    }

    // ---------------------------------------------------------------- 提交

    /**
     * 提交本次整理的全部改动。
     *
     * 这是整个应用**唯一**会写入系统相册的入口，也是唯一会弹系统授权框的地方。
     *
     * 两类改动合并成一次调用：先直写；被拒时用一次「写授权」把「移动 + 回收站」一起申请下来
     * （大多数设备到这一步就够了）；若回收站部分仍被拒，再补一次回收站专属授权。
     * 所以用户在系统层面最多看到两个框，而不是「滑一张弹一次」。
     */
    fun commit() {
        if (_state.value.submitting) return

        val moves = pendingMoves.values.toList()
        val trashes = staging.items.value
        if (moves.isEmpty() && trashes.isEmpty()) {
            ops.emit(strings.get(R.string.commit_nothing_pending))
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(submitting = true, submittingLabel = pendingLabel(moves.size, trashes.size))
            }

            // 1) 移动属于「改数据」：要一次写授权，授权后由应用自己改 RELATIVE_PATH
            if (moves.isNotEmpty()) {
                ops.run(
                    successMessage = if (trashes.isEmpty()) {
                        doneLabel(moves.size, 0)
                    } else {
                        strings.get(R.string.commit_moved, moves.size)
                    },
                    onSuccess = { moves.forEach { move -> pendingMoves.remove(move.imageId) } },
                ) { repo.moveToAlbums(moves) }
            }

            // 2) 进回收站属于「授权即执行」：用户点同意时系统已经做完了，应用不再动手
            if (trashes.isNotEmpty()) {
                ops.runStaged(
                    successMessage = doneLabel(moves.size, trashes.size),
                    onSuccess = { staging.remove(trashes.map { it.id }) },
                    consentBudget = 1,
                ) { attempt -> repo.moveToSystemTrash(trashes.map { it.id }, attempt) }
            }

            // 把落盘的「打算」与当前内存状态对齐：
            // 成功时 onSuccess 已经把 pendingMoves 清空，这里存空列表等价于清除；
            // 被拒时仍留在内存里的意图必须保留，否则进程被杀就会丢掉这批「打算」
            // （与「意图可恢复」的设计自相矛盾，详见 R1）。
            session.savePendingMoves(scope.key, pendingMoves.values.toList())
            session.saveStaging(staging.items.value.map { it.id })

            _state.update { it.copy(submitting = false, submittingLabel = "") }
            syncState()
            // 不论成功与否都重扫一次，让界面反映照片库的真实状态
            onCommitted()
        }
    }

    private fun pendingLabel(moves: Int, trashes: Int): String = when {
        moves > 0 && trashes > 0 ->
            strings.get(R.string.commit_submitting_both, moves, trashes)

        moves > 0 -> strings.get(R.string.commit_submitting_moves, moves)
        else -> strings.get(R.string.commit_submitting_trash, trashes)
    }

    private fun doneLabel(moves: Int, trashes: Int): String = when {
        moves > 0 && trashes > 0 -> strings.get(R.string.commit_done_both, moves, trashes)
        moves > 0 -> strings.get(R.string.commit_done_moves, moves)
        else -> strings.get(R.string.commit_done_trash, trashes)
    }

    // ------------------------------------------------------------ 队列窗口

    /** 队首（还没被消费的第一张）。 */
    private fun at(index: Int): MediaImage? {
        val windowSize = window.size
        if (index < windowSize) return window.elementAt(index)
        // 窗口之后、尚未加载出来的区间要留位 —— 否则「稍后再看」的那些会插到它们前面
        val unloaded = (totalCount - loadedThrough).coerceAtLeast(0)
        val tailStart = windowSize + unloaded
        return if (index < tailStart) null else deferred.elementAtOrNull(index - tailStart)
    }

    private fun advance(): MediaImage? = window.removeFirstOrNull()

    private fun remainingCount(): Int =
        window.size + deferred.size + (totalCount - loadedThrough).coerceAtLeast(0)

    /**
     * 窗口见底就补齐。
     *
     * 这是**唯一**会读取照片的地方；用户滑动时它并行跑在后台，
     * 所以「翻到第 8 万张」不会出现明显停顿。
     */
    private fun ensureWindow() {
        if (loadingPage || loadedThrough >= totalCount) return
        if (window.size >= REFILL_BELOW) return

        loadingPage = true
        val requestOffset = loadedThrough
        val requestGeneration = generation
        viewModelScope.launch {
            val page = runCatching { loader(requestOffset, PAGE_SIZE) }.getOrDefault(emptyList())
            if (requestGeneration != generation) return@launch
            loadingPage = false
            if (page.size < PAGE_SIZE) {
                // 取不满一页 = 数据源到头了（或权限范围缩小了）。标记耗尽，别再反复请求。
                loadedThrough = totalCount
            } else {
                loadedThrough += page.size
            }
            window.addAll(page)
            syncWindow()
        }
    }

    /** 只更新与窗口相关的字段，不动计数器。 */
    private fun syncWindow() {
        val remaining = remainingCount()
        _state.update {
            it.copy(
                current = at(0),
                next = at(1),
                finished = remaining == 0,
                preparing = at(0) == null && remaining > 0,
            )
        }
    }

    private fun syncState() {
        _state.update {
            it.copy(
                processed = processedCount,
                current = at(0),
                next = at(1),
                pendingMoveCount = pendingMoves.size,
                canUndo = history.isNotEmpty(),
                finished = remainingCount() == 0,
                preparing = at(0) == null && remainingCount() > 0,
                keptCount = keptCount,
                // 直接由队列大小派生，队列与计数永不背离（见 Nit5）
                trashedCount = staging.items.value.size,
                movedCount = pendingMoves.size,
                // 「已经产生过非保留改动、且全部结算完」——用计数器判断，不再遍历历史
                committed = it.committed || (pendingMoves.isEmpty() &&
                    staging.items.value.isEmpty() &&
                    processedCount - keptCount > 0),
            )
        }
        ensureWindow()
    }

    // ------------------------------------------------------------ 持久化

    /**
     * 把「打算删哪些 / 打算挪到哪」写进应用私有存储。
     *
     * 注意保存的是**意图**而不是事实：照片本身没有任何变化，
     * 应用被系统回收时丢掉的也只是「还没提交的决定」—— 这正是要接回来的东西。
     */
    private fun persist() {
        session.saveStaging(staging.items.value.map { it.id })
        session.savePendingMoves(scope.key, pendingMoves.values.toList())
    }

    /**
     * 冷启动恢复：落盘的只有 id，回 MediaStore 换回照片。
     * 期间已被删除的照片不会出现在结果里，于是自然被剔除，不需要额外的失效判定。
     */
    private fun restorePendingMoves() {
        val snapshot = session.loadPendingMoves(scope.key)
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val byId = runCatching { repo.imagesByIds(snapshot.map { it.imageId }) }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            snapshot.forEach { move ->
                val image = byId[move.imageId] ?: return@forEach
                pendingMoves[move.imageId] = move
                history.addLast(ReviewRecord(image, ReviewAction.Move, targetAlbum = move.albumName))
                processedCount++
            }
            syncState()
        }
    }

    companion object {
        fun factory(
            scope: MediaScope,
            total: Int,
            moveTargets: List<MediaAlbum>,
            loader: suspend (offset: Int, limit: Int) -> List<MediaImage>,
            onCommitted: () -> Unit = {},
        ) = viewModelFactory {
            initializer {
                ReviewViewModel(
                    repo = Graph.repository,
                    staging = Graph.stagingTrash,
                    ops = Graph.mediaOps,
                    session = Graph.sessionStore,
                    strings = Graph.stringProvider,
                    scope = scope,
                    totalCount = total,
                    initialMoveTargets = moveTargets,
                    loader = loader,
                    onCommitted = onCommitted,
                )
            }
        }
    }
}
