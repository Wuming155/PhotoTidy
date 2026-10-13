package com.phototidy.ui.review

import android.util.Log
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
import com.phototidy.core.media.isImageDestinationLegal
import com.phototidy.core.media.newAlbumIssue
import com.phototidy.core.media.newAlbumPath
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.PendingMove
import com.phototidy.core.model.ReviewAction
import com.phototidy.core.media.ReviewedLedger
import com.phototidy.core.model.ReviewRecord
import com.phototidy.util.runCatchingUnlessCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    /** 待整理张数（进度条分母）。口径与参考实现一致：回答「还剩多少」，不是「一共有多少」。 */
    val total: Int = 0,
    /** 这个范围一共多少张（含已经整理掉的），只用于「已整理 N 张」这类副信息。 */
    val rawTotal: Int = 0,
    val processed: Int = 0,
    val current: MediaImage? = null,
    val next: MediaImage? = null,
    /** 已排队、等提交的「进回收站」数量 */
    val stagedCount: Int = 0,
    /**
     * 已排队待清除的照片本体。
     *
     * 「整理中回收站」面板要展示缩略图，所以这里得带图像对象；
     * 计数仍然以 [stagedCount] 为准（两者在同一个 collector 里一起更新，不可能背离）。
     */
    val stagedItems: List<MediaImage> = emptyList(),
    /** 已排队、等提交的「移动」数量 */
    val pendingMoveCount: Int = 0,
    val canUndo: Boolean = false,
    val finished: Boolean = false,
    /** 正在从 MediaStore 取下一窗照片；UI 该显示转圈而不是空卡片 */
    val preparing: Boolean = false,
    val moveTargets: List<MediaAlbum> = emptyList(),
    val keptCount: Int = 0,
    val submitting: Boolean = false,
    val submittingLabel: String = "",
    /** 本次会话的改动已经提交到系统相册 */
    val committed: Boolean = false,
    /**
     * 这一页照片没能读出来。
     *
     * 必须与「整理完了」严格区分：两者在界面上都是「没有当前照片」，
     * 但一个意味着「没事干了」，另一个意味着「有照片却读不到」。
     * 把它们混为一谈，一次查询失败就会被渲染成「这一轮整理完了」——
     * 用户看着照片库里的照片，被告知已经整理完，这就是应用在说谎。
     */
    val loadError: Boolean = false,
) {
    /** 读取失败、手上也没有可操作的照片：界面该给的是「重试」，不是转圈更不是「完成」。 */
    val windowUnavailable: Boolean get() = loadError && current == null

    /**
     * 这个范围里**已经整理过**的张数（进入会话时就已经整理掉的）。
     *
     * 「还剩 total 张没整理」+「已经整理掉 alreadyReviewed 张」才是这个范围的全貌，
     * 只报一个数会让用户以为照片消失了（这也是这次踩到的坑）。
     */
    val alreadyReviewed: Int get() = (rawTotal - total).coerceAtLeast(0)

    val progress: Float
        get() = if (total == 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)

    /** 排队中、尚未落到系统相册的改动总数 */
    val pendingTotal: Int get() = stagedCount + pendingMoveCount

    val hasPending: Boolean get() = pendingTotal > 0

    /**
     * 「回收站 / 移动」两个统计值直接由队列派生。
     *
     * 它们曾经是与队列并列的字段、只在 `syncState()` 里赋值 —— 于是「进入一个已经结束的范围」
     * 这条不经过任何动作的路径会显示陈旧值：卡片写「回收站 0」，下面一行却写着
     * 「已排队 回收 4 张」。派生之后两者在物理上不可能背离。
     */
    val trashedCount: Int get() = stagedCount
    val movedCount: Int get() = pendingMoveCount
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
 * 窗口本身（「已加载 / 还没取到 / 稍后再看」三段的位置与取出规则）全部收在
 * [ReviewQueueWindow] 里 —— 它是这一段唯一正确性敏感的设施，单独成类才能被单测钉死。
 * 这个 ViewModel 只负责编排：什么时候请求下一页、什么时候补窗、什么时候结账。
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
    /** 这个范围里**一共**有多少张 —— 分页上界，不是进度条分母。 */
    totalCount: Int,
    /** 这个范围里**还没整理**的有多少张 —— 进度条分母与「还剩多少」的数字。 */
    remainingCount: Int,
    initialMoveTargets: List<MediaAlbum>,
    /** 按 offset/limit 从数据层取一页照片。 */
    private val loader: suspend (offset: Int, limit: Int) -> List<MediaImage>,
    /** 提交成功后回调，让外层重新扫描照片库（首页 / 相册 / 回收站的数据都变了）。 */
    private val onCommitted: () -> Unit = {},
    /** 「已整理」账本：入队时跳过、决定时记账。 */
    private val reviewed: ReviewedLedger,
) : ViewModel() {

    private val totalCount = totalCount

    /**
     * 滑动窗口队列。
     *
     * 「已加载 / 还没取到 / 稍后再看」三段的位置与取出规则全部收在它里面 ——
     * 分成两个出口（读位置的 `at()`、消费队首的 `advance()`）曾经口径不一致，
     * 后果是「屏幕上有照片但所有动作静默失效」的死局。见 [ReviewQueueWindow] 的注释。
     */
    private val queue = ReviewQueueWindow(totalCount)

    private var loadingPage = false

    /** 代际号：`restart()` 之后作废在途的分页请求，避免旧页污染新队列。 */
    private var generation = 0

    private val history = ArrayDeque<ReviewRecord>()

    /** 已排队但还没提交的移动：照片 id → 待移动记录 */
    private val pendingMoves = LinkedHashMap<Long, PendingMove>()

    /** 本次会话新建的相册计数器，只用来保证哨兵 bucketId 唯一（见 [createAlbum]）。 */
    private var newAlbumSeq = 0

    // ---- 增量计数器：syncState() 必须是 O(1) ----
    // 回收站 / 移动的数量不在这里维护，也不在 syncState() 里赋值，而是直接由
    // staging / pendingMoves 派生（见 ReviewUiState）—— 从根上消除「计数器与队列背离」。
    private var processedCount = 0
    private var keptCount = 0

    /**
     * 「再来一轮」：本次会话忽略账本，把这一范围的照片从头再放一遍。
     *
     * 刻意**不**去删账本里的记录 —— 重看一遍不等于「这些又变回没整理过」，
     * 用户滑到一半退出去，再进来看到的仍应该是「已整理」的状态。
     */
    private var ignoreLedger = false

    private val _state = MutableStateFlow(
        ReviewUiState(
            // 分母 = 待整理数（参考实现的口径：数字回答「还剩多少」，不是「一共有多少」）
            total = remainingCount,
            rawTotal = totalCount,
            moveTargets = initialMoveTargets,
            preparing = totalCount > 0,
        ),
    )
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            staging.items.collect { items ->
                _state.update { it.copy(stagedCount = items.size, stagedItems = items) }
            }
        }
        restorePendingMoves()
        ensureWindow()
    }

    // ------------------------------------------------------------ 主流程动作

    /**
     * ✔ 保留：不改动照片，但**记一条已整理的账**。
     *
     * 「保留」是唯一不改变照片库的动作，所以它必须留下账 —— 否则用户滑过的照片
     * 下次会原封不动地重新排队，首页的数字也永远停在「一共有多少张」上。
     */
    fun keep() {
        val image = advance() ?: return
        history.addLast(ReviewRecord(image, ReviewAction.Keep))
        reviewed.mark(image.id)
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
        reviewed.mark(image.id)
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
        reviewed.mark(image.id)
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

        // 「我反悔了」= 这张重新算未整理。账本必须一起回退：留在账本里的话，
        // 它会既不在队列里、也不算待整理 —— 从用户视角看就是照片凭空消失了。
        reviewed.unmark(last.image.id)

        // 放回窗口头部，用户看到的就是「刚才那张又回来了」
        queue.pushFront(last.image)
        persist()
        syncState()
    }

    // ------------------------------------------------------------ 新建相册

    /**
     * 会话内新建一个相册，并把它挂进快捷移动条，返回新相册供调用方立刻投递当前这张。
     *
     * 三件事刻意不做：
     *  1) **不落盘**。MediaStore 里没有「目录」这种实体，空目录根本不存在 ——
     *     「创建」的实体化发生在第一张照片带着 `RELATIVE_PATH` 写进去的那一刻。
     *     所以这里只是把目标记进内存，随后的 [moveTo] 会照常把它排进待移动表
     *     （那条记录是落盘的，冷启动能恢复，「建完就丢了」不会发生）。
     *  2) **不做异步**。参考实现同样是同步返回、立刻投递（见 notes §1）——
     *     建个目录要转圈，用户会以为应用卡了。
     *  3) **不校验外部状态**。重名只跟本次会话的移动目标比：设备上已存在同名相册
     *     在 [LibraryViewModel.moveTargets] 里已经是一个真实目标了，用户直接点它就行。
     *
     * 返回 null 表示名字不可用（空 / 含分隔符 / 与本次会话已有目标重名）。
     * UI 层会用 `newAlbumIssue` 先给出**具体的**错误文案，这里只是不让脏数据进队列。
     */
    fun createAlbum(rawName: String): MediaAlbum? {
        val existing = _state.value.moveTargets.map { it.name }
        if (newAlbumIssue(rawName, existing) != null) return null
        val name = rawName.trim()
        val album = MediaAlbum(
            // 哨兵区间与「系统标准目录」错开，且离 Int32 的真实 BUCKET_ID 极远
            bucketId = NEW_ALBUM_BUCKET_BASE - (++newAlbumSeq),
            name = name,
            relativePath = newAlbumPath(name),
            count = 0,
            cover = null,
        )
        _state.update { it.copy(moveTargets = it.moveTargets + album) }
        ops.emit(strings.get(R.string.album_new_created, name))
        return album
    }

    // ------------------------------------------------------- 整理中的回收站

    /**
     * 把一张已排队待清除的照片放回整理队列（会话内回收站的「还原」）。
     *
     * 与 [undo] 的区别是**定位方式**：撤销是后进先出的，误删的那批如果不在栈顶，
     * 用户就得先把后来做的所有决定全撤掉才够得着它；这里按 id 精确取回。
     *
     * 三件事一起做，缺一不可：
     *  · 出暂存队列（照片本来就没动过，纯内存）；
     *  · 抹掉账本 —— 否则这张会既不在队列里、也不算待整理，从用户视角就是照片消失了；
     *  · 摘掉撤销栈里对应的那条「清除」记录 —— 留着它，用户之后点「撤销」时会撤销一个
     *    已经不在队列里的动作，队列与撤销栈从此对不上。
     */
    fun restoreFromStaging(imageId: Long) {
        if (staging.items.value.none { it.id == imageId }) return
        restoreStagedInternal(imageId)
        persist()
        syncState()
    }

    /** 把排队待清除的整批放回整理队列（对应参考实现的「全部还原」）。 */
    fun restoreAllStaged() {
        val ids = staging.items.value.map { it.id }
        if (ids.isEmpty()) return
        ids.forEach { restoreStagedInternal(it) }
        persist()
        syncState()
    }

    private fun restoreStagedInternal(imageId: Long) {
        val image = staging.items.value.firstOrNull { it.id == imageId } ?: return
        staging.unstage(imageId)
        val wasThisSession = history.removeAll {
            it.action == ReviewAction.Trash && it.image.id == imageId
        }
        if (wasThisSession) processedCount = (processedCount - 1).coerceAtLeast(0)
        reviewed.unmark(imageId)
        // 放回窗口头部：用户刚说「这张不该删」，就该立刻能重新决定它
        queue.pushFront(image)
    }

    // ------------------------------------------------------------ 丢弃改动

    /**
     * 丢弃本次会话的全部决定，把一切还原到「进入整理页之前」。
     *
     * 这是纯内存回滚：照片库从头到尾没被改动过（改动只在提交时落盘），
     * 所以不需要任何权限，也不可能丢数据。逆序回滚与逐次 [undo] 完全等价，
     * 只是不去动窗口 —— 会话马上要结束了，没必要把照片重新排一遍。
     *
     * 只回滚**本次会话的 history**：暂存队列是全应用共享的（可能含上个会话留下的
     * 待清除项），那些不属于「本次改动」，不能一起丢掉。
     */
    fun discardChanges() {
        while (history.isNotEmpty()) {
            val record = history.removeLast()
            when (record.action) {
                ReviewAction.Trash -> staging.unstage(record.image.id)
                ReviewAction.Move -> pendingMoves.remove(record.image.id)
                // 保留没有队列副作用，回滚它只是把账本和计数退回去（计数在循环外统一归零）
                ReviewAction.Keep -> Unit
            }
            reviewed.unmark(record.image.id)
        }
        processedCount = 0
        keptCount = 0
        persist()
        // 账本可能还欠着节流窗口里的几条，必须立刻结清 —— 丢弃之后立刻退出整理页，
        // 留着没写的记录会让首页数字对不上。
        reviewed.flush()
        syncState()
    }

    /**
     * 队列空了之后想再过一遍。已排队待提交的改动保留 —— 那是用户已经做出的决定。
     *
     * 这一轮**忽略账本**：把这一范围的照片全部重新排一遍（分母也回到「一共有多少张」）。
     * 账本本身不动，所以滑到一半退出去、再进来，看到的仍是「已整理」的状态。
     */
    fun restart() {
        generation++
        queue.clear()
        loadingPage = false
        history.clear()
        processedCount = 0
        keptCount = 0
        ignoreLedger = true
        // 上一轮的读取失败不该把新一轮也堵在错误卡片上：这是用户主动重开的读取
        _state.update { it.copy(loadError = false, total = totalCount) }
        syncState()
    }

    /**
     * 跳过当前这张，放到队尾稍后再看。
     *
     * 走 [ReviewQueueWindow.deferCurrent]（取出队首 → 追加到 deferred），
     * 而不是自己拼两步 —— 取出规则必须与 [ReviewQueueWindow.peek] 一致，
     * 否则滑到最后一张时会停在 deferred 上，且没有任何动作能推进它。
     */
    fun deferCurrent() {
        if (queue.deferCurrent() == null) return
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

            try {
                // 1) 移动属于「改数据」：要一次写授权，授权后由应用自己改 RELATIVE_PATH
                if (moves.isNotEmpty()) {
                    ops.run(
                        successMessage = if (trashes.isEmpty()) {
                            doneLabel(moves.size, 0)
                        } else {
                            strings.get(R.string.commit_moved, moves.size)
                        },
                        onSuccess = { moves.forEach { move -> pendingMoves.remove(move.imageId) } },
                        // 失败之后必须对账：批量直写是逐条进行的，失败之前那几张可能已经落库，
                        // 而队列里还可能留着永远不可能完成的项 —— 见 [dropUnreachableMoves]。
                        onFailure = { _ -> dropUnreachableMoves() },
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // 顶层兜底。仓库层已把可预期的失败包成 MediaOpResult.Failed，能走到这里的是
                // 意料外的异常（个别 ROM 的 MediaStore 怪癖等）。不能放它出去：
                // viewModelScope 没有异常处理器，未捕获异常会把整个应用崩掉，
                // 而崩溃前留下的是「提交到一半」的中间态。
                // 这类异常恰恰是最需要现场证据的 —— 必须留下完整堆栈，不能只弹「操作失败」。
                Log.e(TAG, "commit failed: ${pendingLabel(moves.size, trashes.size)}", e)
                ops.emit(strings.get(R.string.media_op_failed))
            } finally {
                // 不论成功、被拒还是中断，收尾都不能省：
                //  · 「打算」必须与内存对齐后落盘 —— 成功时 onSuccess 已把队列清空，
                //    这里存空列表等价于清除；被拒/中断时留在内存里的意图必须保住（见 R1）。
                //  · submitting 必须复位 —— 否则异常路径上提交按钮永久转圈，会话锁死。
                //  · 重扫照片库，让首页/回收站反映真实状态。
                session.savePendingMoves(scope.key, pendingMoves.values.toList())
                session.saveStaging(staging.items.value.map { it.id })
                // 「已整理」账本也在这里结清：提交是用户认为「这一轮办完了」的时刻，
                // 不能还欠着没写盘（节流窗口内最多只会有十几条没落盘，但没理由不写）。
                reviewed.flush()
                _state.update { it.copy(submitting = false, submittingLabel = "") }
                syncState()
                onCommitted()
            }
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

    private fun advance(): MediaImage? = queue.take()

    /**
     * 窗口见底就补齐。
     *
     * 这是**唯一**会读取照片的地方；用户滑动时它并行跑在后台，
     * 所以「翻到第 8 万张」不会出现明显停顿。
     *
     * ⚠️ 读取失败**不是**「数据源到头了」。这里以前是 `getOrDefault(emptyList())`：
     * 异常被吞成一张空页，紧接着落进「取不满一页 = 已耗尽」的分支，于是一次查询失败
     * 就被渲染成「这一轮整理完了」——而照片库里一张都没少，用户看到的是应用在说谎。
     * 现在失败进 [ReviewUiState.loadError]，界面给的是「读取失败 + 重试」。
     */
    private fun ensureWindow() {
        // 上一次读取已经失败：等用户显式重试（[retryLoad]），不要每次 syncState 都重撞一次
        if (_state.value.loadError) return
        if (loadingPage) return
        // 「数据源取完了」与「窗口还够用」分开判：前者决定还发不发请求，后者决定现在要不要发
        if (!queue.needsRefill(REFILL_BELOW)) return

        loadingPage = true
        val requestOffset = queue.loadedCount
        val requestGeneration = generation
        viewModelScope.launch {
            // 取消不是失败，不能冒充成「读取失败」弹给用户
            val page = runCatchingUnlessCancelled { loader(requestOffset, PAGE_SIZE) }.getOrNull()
            if (requestGeneration != generation) return@launch
            loadingPage = false

            // 两条失败判据：
            //  · 查询抛异常（page == null）；
            //  · 概览说有 totalCount 张，第一页却一张都取不到 —— 计数与取数口径不一致，
            //    属于读取出了问题，而不是「这个范围是空的」。静默收成「完成」会让用户
            //    以为照片都整理完了（真实案例：按月筛选的参数类型不对，SQL 永远匹配 0 行）。
            // 判据必须看**原始页**：下面会把已整理的照片滤掉，滤空是正常的（范围已经整理完了）。
            val firstPageEmpty = page?.isEmpty() == true && requestOffset == 0 && totalCount > 0
            if (page == null || firstPageEmpty) {
                syncWindow(loadError = true)
                return@launch
            }

            // 已整理的照片不再进队列 —— 这是「已整理 / 未整理」在行为上的落点：
            // 首页数字减下去不算数，用户再进来时必须真的看不到它们。
            // 它们被滤掉但仍然算作「已取到」，所以翻页水位照常推进，不会卡在同一个 offset。
            queue.acceptPage(page, PAGE_SIZE) { image ->
                ignoreLedger || !reviewed.contains(image.id)
            }

            // 整页都是已整理的照片：窗口没长，但数据源还没到头 —— 立刻再补一页，
            // 否则用户看到的是转圈（preparing）而不是「下一条待整理」。
            if (queue.needsRefill(REFILL_BELOW)) {
                syncWindow(loadError = false)
                ensureWindow()
                return@launch
            }
            syncWindow(loadError = false)
        }
    }

    /**
     * 读取失败后的重试入口。
     *
     * 只清错误标记再走一次 [syncState]：翻页水位在失败路径上从未被推进，
     * 所以重试会**原地**重取失败的那一页，不会把用户已经滑过的照片再放回队列。
     */
    fun retryLoad() {
        if (loadingPage) return
        _state.update { it.copy(loadError = false) }
        syncState()
    }

    /** 只更新与窗口相关的字段，不动计数器。 */
    private fun syncWindow(loadError: Boolean) {
        _state.update {
            val remaining = queue.remaining
            it.copy(
                current = queue.peek(0),
                next = queue.peek(1),
                finished = remaining == 0,
                // 读取失败时不能转圈：转圈在这块版面上等价于「正在整理」，而实际什么都没在发生
                preparing = !loadError && queue.peek(0) == null && remaining > 0,
                loadError = loadError,
            )
        }
    }

    private fun syncState() {
        _state.update {
            val remaining = queue.remaining
            val blocked = it.loadError && queue.peek(0) == null
            it.copy(
                processed = processedCount,
                current = queue.peek(0),
                next = queue.peek(1),
                pendingMoveCount = pendingMoves.size,
                canUndo = history.isNotEmpty(),
                finished = remaining == 0,
                preparing = !blocked && queue.peek(0) == null && remaining > 0,
                keptCount = keptCount,
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
     *
     * 另外两类也必须在这里剔除 —— 它们会让队列**永久卡死**：提交按钮一直亮着，
     * 用户每次点提交都撞同一堵墙，而队列一条都清不掉。
     *  · 照片已经躺在目标目录里：这次移动其实早就完成了（提交中途被杀进程就会留下这种队列）。
     *  · 落点被系统禁止：老版本会把 `Download/` 这类目录当成移动目标，而 MediaStore 对图片
     *    只接受 `DCIM/` 与 `Pictures/` 两棵子树（见 [isImageDestinationLegal]），
     *    重试多少次都是同一句报错。
     */
    private fun restorePendingMoves() {
        val snapshot = session.loadPendingMoves(scope.key)
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val byId = runCatching { repo.imagesByIds(snapshot.map { it.imageId }) }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            var unreachable = 0
            snapshot.forEach { move ->
                val image = byId[move.imageId] ?: return@forEach
                if (isAtTarget(image, move)) return@forEach
                if (!isImageDestinationLegal(move.targetPath)) {
                    unreachable++
                    return@forEach
                }
                pendingMoves[move.imageId] = move
                history.addLast(ReviewRecord(image, ReviewAction.Move, targetAlbum = move.albumName))
                // 恢复出来的待移动项也是「已经决定过」的照片，账本要一起补齐
                reviewed.mark(move.imageId)
                processedCount++
            }
            if (unreachable > 0) {
                ops.emit(strings.get(R.string.commit_move_target_skipped, unreachable))
            }
            // 会话内新建的相册只活在内存里（见 [createAlbum]），所以冷启动恢复时
            // 必须把快照里的目标重新挂回移动条 —— 否则用户自己建的相册会从列表上消失，
            // 照片照样能移（队列里有），但入口没了。
            val knownTargets = _state.value.moveTargets
                .mapTo(HashSet()) { it.relativePath.trim('/') }
            val restoredTargets = snapshot
                .filter { isImageDestinationLegal(it.targetPath) }
                .map { move ->
                    MediaAlbum(
                        bucketId = move.bucketId,
                        name = move.albumName,
                        relativePath = move.albumPath.ifBlank { move.targetPath.trim('/') },
                        count = 0,
                        cover = null,
                    )
                }
                .filter { knownTargets.add(it.relativePath.trim('/')) }
            if (restoredTargets.isNotEmpty()) {
                _state.update { it.copy(moveTargets = it.moveTargets + restoredTargets) }
            }
            // 剔除的结果必须落盘，否则下次冷启动又把它们捞回来
            persist()
            syncState()
        }
    }

    /**
     * 提交失败之后对账，把队列里两类「再排下去也没意义」的项摘掉：
     *  · 已经真的落到目标目录的 —— 批量直写是逐条进行的，失败之前的那几条可能早已落库；
     *  · 落点被系统禁止的 —— 重试一万次也只会得到同一句报错。
     *
     * 不做这一步的话，[ReviewUiState.hasPending] 永远为真：提交按钮一直亮着、
     * 每次点提交都重新撞上同一个必失败的项，队列却一条也清不掉。
     */
    private suspend fun dropUnreachableMoves() {
        if (pendingMoves.isEmpty()) return
        val byId = runCatching { repo.imagesByIds(pendingMoves.keys.toList()) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
        var dropped = 0
        var unreachable = 0
        pendingMoves.entries.removeAll { (id, move) ->
            val image = byId[id]
            when {
                image != null && isAtTarget(image, move) -> {
                    dropped++
                    true
                }

                !isImageDestinationLegal(move.targetPath) -> {
                    dropped++
                    unreachable++
                    true
                }

                else -> false
            }
        }
        if (dropped == 0) return
        if (unreachable > 0) {
            ops.emit(strings.get(R.string.commit_move_target_skipped, unreachable))
        }
        persist()
        syncState()
    }

    /** 照片是否已经在这次移动的目标目录里了（路径收尾的斜杠无关紧要）。 */
    private fun isAtTarget(image: MediaImage, move: PendingMove): Boolean =
        image.relativePath.trim('/') == move.targetPath.trim('/')

    companion object {
        private const val TAG = "ReviewViewModel"

        /**
         * 会话内新建相册的 bucketId 起点。
         *
         * 与 `LibraryViewModel.SYNTHETIC_BUCKET_BASE`（标准目录，`Long.MIN_VALUE + n`）分开，
         * 也远在 Int32 真实 BUCKET_ID 之外 —— 快捷移动条按 `bucketId:name` 做 LazyRow key，
         * 撞车会直接因 key 重复崩溃。
         */
        private const val NEW_ALBUM_BUCKET_BASE = Long.MIN_VALUE / 2

        fun factory(
            scope: MediaScope,
            /** 范围内一共多少张（分页上界）。 */
            total: Int,
            /** 范围内还没整理的有多少张（进度条分母）。 */
            remaining: Int,
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
                    remainingCount = remaining,
                    initialMoveTargets = moveTargets,
                    loader = loader,
                    onCommitted = onCommitted,
                    reviewed = Graph.reviewedLedger,
                )
            }
        }
    }

    override fun onCleared() {
        // 应用被回收 / 会话结束：把欠着的「已整理」记录结清，别让节流窗口吞掉它们。
        reviewed.flush()
        super.onCleared()
    }
}
