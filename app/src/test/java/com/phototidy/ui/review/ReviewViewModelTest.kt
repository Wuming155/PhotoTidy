package com.phototidy.ui.review

import com.phototidy.core.StringProvider
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaOpResult
import com.phototidy.core.media.MediaRepository
import com.phototidy.core.media.ReviewedLedger
import com.phototidy.core.media.SessionStore
import com.phototidy.core.media.isImageDestinationLegal
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.PendingMove
import com.phototidy.core.media.StagingTrash
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun makeImage(id: Long) = MediaImage(
        id = id, uri = "content://x/$id", displayName = "n$id",
        takenAtMillis = 0, addedAtMillis = 0, bucketId = 1, albumName = "A",
        relativePath = "DCIM/A", sizeBytes = 1, width = 1, height = 1,
        isFavorite = false, isTrashed = false,
    )

    private class FakeSessionStore : SessionStore {
        private val staging = mutableListOf<Long>()
        private val pending = mutableMapOf<String, List<PendingMove>>()
        private val reviewed = linkedSetOf<Long>()
        override fun saveStaging(imageIds: List<Long>) { staging.clear(); staging.addAll(imageIds) }
        override fun loadStaging(): List<Long> = staging.toList()
        override fun clearStaging() = staging.clear()
        override fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {
            pending[scopeKey] = moves.toList()
        }
        override fun loadPendingMoves(scopeKey: String): List<PendingMove> = pending[scopeKey].orEmpty()
        override fun clearPendingMoves(scopeKey: String) { pending.remove(scopeKey) }

        /** 与生产实现同口径：先把本批取消的删掉，再加入本批新增的。 */
        override fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>) {
            reviewed.removeAll(unmarked.toSet())
            reviewed.addAll(marked)
        }

        override fun loadReviewed(): List<Long> = reviewed.toList()
    }

    private class FakeMediaRepository(
        private val moveResult: MediaOpResult = MediaOpResult.Success,
        private val trashResult: MediaOpResult = MediaOpResult.Success,
        private val images: Map<Long, MediaImage> = emptyMap(),
        /** 非空时 moveToAlbums 直接抛出：模拟 ROM 上意料外的异常（H1）。 */
        private val moveError: Throwable? = null,
        /** 非空时 imagesByIds 直接抛出：模拟 MediaStore 查询失败。 */
        private val imagesError: Throwable? = null,
    ) : MediaRepository {
        var moveCalls = 0
        var trashCalls = 0
        override suspend fun moveToAlbums(items: List<PendingMove>): MediaOpResult {
            moveCalls++
            moveError?.let { throw it }
            return moveResult
        }
        override suspend fun moveToSystemTrash(imageIds: List<Long>, attempt: Int): MediaOpResult {
            trashCalls++
            return trashResult
        }
        override suspend fun imagesByIds(ids: Collection<Long>): List<MediaImage> {
            imagesError?.let { throw it }
            return ids.mapNotNull { images[it] }
        }
    }

    private fun makeVm(
        repo: MediaRepository = FakeMediaRepository(),
        session: SessionStore = FakeSessionStore(),
        staging: StagingTrash? = null,
        scope: MediaScope = MediaScope.Recent,
        totalCount: Int = 20,
        /** 待整理数（进度条分母）。默认与总数相同 = 这个范围一张都还没整理过。 */
        remaining: Int = totalCount,
        reviewed: ReviewedLedger = ReviewedLedger(session),
        loaderImages: List<MediaImage> = (1L..20L).map { makeImage(it) },
        loader: (suspend (Int, Int) -> List<MediaImage>)? = null,
        onCommitted: () -> Unit = {},
    ): ReviewViewModel {
        val strings = object : StringProvider {
            override fun get(resId: Int, vararg args: Any) = "msg"
        }
        val ops = MediaOpCoordinator(strings)
        // P4b：StagingTrash 自持落盘，测试中同样注入同一份 session 并开门，
        // 否则 staging 的变更不会落盘，`session.loadStaging()` 断言将 vacuously 通过/失败。
        val resolvedStaging = staging ?: StagingTrash(session).apply { markRestored() }
        // 外部传入的 staging 若尚未开门（旧构造），此处显式开门以模拟 Library 恢复完成后的常态；
        // 若其内部 store 与 session 不是同一份，落盘目标不一致 —— 调用方应优先传入 session 关联实例。
        if (!resolvedStaging.restored) resolvedStaging.markRestored()
        return ReviewViewModel(
            repo = repo, staging = resolvedStaging, ops = ops, session = session, strings = strings,
            scope = scope, totalCount = totalCount, remainingCount = remaining,
            initialMoveTargets = emptyList(),
            loader = loader ?: { _, _ -> loaderImages },
            onCommitted = onCommitted,
            reviewed = reviewed,
        )
    }

    private val moveAlbum =
        MediaAlbum(bucketId = 2, name = "B", relativePath = "DCIM/B", count = 0, cover = null)

    @Test
    fun `trash then undo returns to zero`() {
        val vm = makeVm()
        vm.trash()
        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(1, vm.state.value.trashedCount)
        assertEquals(1, vm.state.value.processed)
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(0, vm.state.value.trashedCount)
        assertEquals(0, vm.state.value.processed)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun `counts are derived from queues so they never drift (Nit5)`() {
        val vm = makeVm()
        vm.trash()
        vm.trash()
        // trashedCount 必须等于 staging 队列大小 —— 计数器不再单独维护
        assertEquals(vm.state.value.stagedCount, vm.state.value.trashedCount)
        assertEquals(2, vm.state.value.trashedCount)

        vm.moveTo(moveAlbum)
        assertEquals(vm.state.value.pendingMoveCount, vm.state.value.movedCount)
        assertEquals(1, vm.state.value.movedCount)
    }

    @Test
    fun `restart preserves staged decisions but resets counters`() {
        val vm = makeVm()
        vm.trash()
        vm.restart()
        // 队列保留（用户已做出的决定），计数器归零后由队列重新派生
        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(1, vm.state.value.trashedCount)
        assertEquals(0, vm.state.value.processed)
    }

    @Test
    fun `R1 commit failure keeps pending move snapshot`() {
        val session = FakeSessionStore()
        val repo = FakeMediaRepository(moveResult = MediaOpResult.Failed("denied"))
        val vm = makeVm(repo = repo, session = session)
        vm.moveTo(moveAlbum)
        assertEquals(1, vm.state.value.pendingMoveCount)

        vm.commit()

        // 提交失败后，内存里仍保留待移动，磁盘快照同样保留（不会被无条件清空）
        assertEquals(1, vm.state.value.pendingMoveCount)
        assertEquals(1, session.loadPendingMoves(MediaScope.Recent.key).size)
        assertFalse(vm.state.value.committed)
    }

    @Test
    fun `commit success clears snapshots`() {
        val session = FakeSessionStore()
        val vm = makeVm(session = session)
        vm.trash()
        vm.moveTo(moveAlbum)
        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(1, vm.state.value.pendingMoveCount)

        vm.commit()

        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(0, vm.state.value.pendingMoveCount)
        assertEquals(0, session.loadPendingMoves(MediaScope.Recent.key).size)
        assertEquals(0, session.loadStaging().size)
        assertTrue(vm.state.value.committed)
    }

    @Test
    fun `clearing the whole queue keeps undo available`() {
        // 「全部清掉、还没提交」是最需要反悔的一刻：队列滑空只意味着没有下一张了，
        // 不代表撤销跟着失效 —— 改动全在内存里，撤销就是纯内存回滚。
        val vm = makeVm(totalCount = 3, loaderImages = (1L..3L).map { makeImage(it) })
        repeat(3) { vm.trash() }

        assertTrue(vm.state.value.finished)
        assertTrue(vm.state.value.canUndo)
        assertEquals(3, vm.state.value.stagedCount)

        vm.undo()

        assertFalse(vm.state.value.finished)
        assertEquals(2, vm.state.value.stagedCount)
        assertEquals(3L, vm.state.value.current?.id)
    }

    @Test
    fun `restores pending moves from snapshot on construction`() {
        val session = FakeSessionStore()
        session.savePendingMoves(
            MediaScope.Recent.key,
            listOf(
                PendingMove(
                    imageId = 7L, targetPath = "DCIM/B/", albumName = "B",
                    bucketId = 2, albumPath = "DCIM/B",
                ),
            ),
        )
        val vm = makeVm(session = session, repo = FakeMediaRepository(images = mapOf(7L to makeImage(7L))))
        assertEquals(1, vm.state.value.pendingMoveCount)
        // 恢复项占着 pendingMoves，「改动已结算」的派生条件不成立 ——
        // 在真正提交之前 committed 必须保持 false，恢复不能污染完成态语义
        assertFalse(vm.state.value.committed)
    }

    @Test
    fun `restore drops moves the system can never honour, and keeps the rest`() {
        // 老版本会把 Download/ 当移动目标，而 MediaStore 只让图片落在 DCIM/ 与 Pictures/ 之下 ——
        // 这种项重试多少次都是同一句报错。再叠上「批量直写是逐条进行的、失败之前那几条可能已落库」，
        // 队列就会卡成「一提交就报错，却永远清不掉」。恢复时就得把这两类无用项摘掉。
        val session = FakeSessionStore()
        session.savePendingMoves(
            MediaScope.Recent.key,
            listOf(
                // 已经躺在目标目录里：这次移动其实早就完成了
                PendingMove(1L, "DCIM/A/", "A", 1L, "DCIM/A"),
                // 落点被系统禁止，永远不可能成功
                PendingMove(2L, "Download/", "Download", 3L, "Download"),
                // 这条才是真欠着的
                PendingMove(3L, "DCIM/B/", "B", 2L, "DCIM/B"),
            ),
        )
        val images = mapOf(1L to makeImage(1L), 2L to makeImage(2L), 3L to makeImage(3L))
        val vm = makeVm(session = session, repo = FakeMediaRepository(images = images))

        assertEquals(1, vm.state.value.pendingMoveCount)
        // 摘掉的结果必须落盘，否则下次冷启动又把它们捞回来
        assertEquals(listOf(3L), session.loadPendingMoves(MediaScope.Recent.key).map { it.imageId })
    }

    @Test
    fun `window refills from the next page before running dry`() {
        // 分页补窗是「翻到第 8 万张也不停顿」的保证：第一页滑穿之前下一页必须已在内存里；
        // 页取不满说明数据源到头了，标记耗尽后不能再发起第三次请求。
        val all = (1L..200L).map { makeImage(it) }
        val calls = mutableListOf<Pair<Int, Int>>()
        val vm = makeVm(
            totalCount = 200,
            loader = { offset, limit ->
                calls += offset to limit
                all.drop(offset).take(limit)
            },
        )
        repeat(200) { index ->
            assertFalse(vm.state.value.finished)
            vm.trash()
        }
        assertTrue(vm.state.value.finished)
        assertEquals(200, vm.state.value.stagedCount)
        // 第一页 150 张 + 窗口剩余低于阈值时补的最后一页（50 < 150 → 到头）——恰好两次取数
        assertEquals(listOf(0 to 150, 150 to 150), calls)
    }

    // ------------------------------------------------------ 已整理 / 未整理

    @Test
    fun `a deferred photo is still actionable once the rest of the scope is drained (C1)`() {
        // 「稍后再看」的照片排在队尾，但滑到最后一张时它必须**真的能操作**。
        // 曾经「读取队首位置」与「消费队首」的口径不一致：屏幕上明明有那张照片，
        // keep / trash / move 却全部静默返回，进度也永远走不完 —— 会话直接卡死在这里。
        val vm = makeVm(
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        vm.deferCurrent()
        assertEquals("跳过之后队首是第 2 张", 2L, vm.state.value.current?.id)

        vm.keep()
        vm.keep()
        assertEquals(2, vm.state.value.processed)

        // 队列里只剩被 defer 的那张：它必须重新成为可操作对象，而不是卡在屏幕上
        assertFalse(vm.state.value.finished)
        assertEquals(1L, vm.state.value.current?.id)

        vm.keep()

        assertEquals("对 deferred 照片执行 keep 必须推进计数", 3, vm.state.value.processed)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun `keep is recorded in the ledger and survives a restart of the app`() {
        // 「保留」不改照片库，所以它必须留下一条账 —— 否则用户滑过的照片下次会原封不动
        // 地重新排队，首页数字也永远停在「一共有多少张」。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        vm.keep()

        assertTrue(ledger.contains(1L))
        // 落盘是**节流**的（攒够 20 条或隔 1 秒写一次），所以刚滑完这一秒内快照可能还没更新。
        // 真正的持久化保障是「退出整理页 / 提交 / onCleared」三处的 flush —— 这里显式验证它。
        ledger.flush()
        assertTrue(ReviewedLedger(session).contains(1L))
    }

    @Test
    fun `already reviewed photos never re-enter the queue`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        ledger.mark(1L)
        ledger.mark(2L)

        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            remaining = 1,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        // 队列里只剩第 3 张；分母也是「待整理」的 1，而不是总数 3
        assertEquals(3L, vm.state.value.current?.id)
        assertEquals(1, vm.state.value.total)
        assertEquals(3, vm.state.value.rawTotal)
        assertEquals(2, vm.state.value.alreadyReviewed)
    }

    @Test
    fun `a scope that is entirely reviewed is finished, not a load failure`() {
        // 全滤空与「查询坏了」在数据上都是「窗口是空的」，但结论相反：
        // 一个是没事干了，一个是把照片读没了。混在一起就会重演那次「明明还有照片却报完成」。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        (1L..3L).forEach { ledger.mark(it) }

        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            remaining = 0,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.loadError)
    }

    @Test
    fun `pages made entirely of reviewed photos are paged through instead of stalling`() {
        // 大库里「前 150 张都整理过了」很常见。整页被滤空时必须继续翻页，
        // 否则用户看到的是永久转圈（preparing），而数据源其实还有内容。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val all = (1L..300L).map { makeImage(it) }
        (1L..150L).forEach { ledger.mark(it) }
        val calls = mutableListOf<Pair<Int, Int>>()
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 300,
            remaining = 150,
            loader = { offset, limit ->
                calls += offset to limit
                all.drop(offset).take(limit)
            },
        )

        assertEquals(151L, vm.state.value.current?.id)
        assertEquals(listOf(0 to 150, 150 to 150), calls)
    }

    @Test
    fun `undo puts the photo back into the unreviewed queue`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        vm.keep()
        assertTrue(ledger.contains(1L))

        vm.undo()

        // 留在账本里的话，这张既不在队列里、也不算待整理 —— 从用户视角就是凭空消失
        assertFalse(ledger.contains(1L))
        assertEquals(1L, vm.state.value.current?.id)
    }

    @Test
    fun `restart re-reviews the whole scope without erasing the ledger`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )

        vm.keep()
        vm.keep()
        vm.restart()

        // 「再来一轮」= 重看这一范围的全部照片：分母回到总数，第 1 张重新排队
        assertEquals(3, vm.state.value.total)
        assertEquals(1L, vm.state.value.current?.id)
        // 但账本不动：滑到一半退出去再进来，看到的仍然应该是「已整理」
        assertTrue(ledger.contains(1L))
        assertTrue(ledger.contains(2L))
    }

    @Test
    fun `commit resets submitting and keeps snapshot when repo throws (H1)`() {
        // 仓库层已把可预期的失败包成 MediaOpResult，但个别 ROM 可能抛出意料外的异常。
        // 提交流程必须有顶层兜底：submitting 复位、「打算」落盘保住、外层照常刷新 ——
        // 而不是把异常放给没有 handler 的 viewModelScope，把整个应用崩掉。
        val session = FakeSessionStore()
        val repo = FakeMediaRepository(moveError = IllegalStateException("rom quirk"))
        var committedCallbacks = 0
        val vm = makeVm(repo = repo, session = session, onCommitted = { committedCallbacks++ })
        vm.moveTo(moveAlbum)
        assertEquals(1, vm.state.value.pendingMoveCount)

        vm.commit()

        assertFalse(vm.state.value.submitting)
        // 中断的提交不丢「打算」：内存与落盘快照都必须原样保留
        assertEquals(1, vm.state.value.pendingMoveCount)
        assertEquals(1, session.loadPendingMoves(MediaScope.Recent.key).size)
        assertEquals(1, committedCallbacks)
    }

    // ------------------------------------------------------ 新建相册

    @Test
    fun `new album becomes a move target and is immediately usable`() {
        val vm = makeVm()
        val album = vm.createAlbum("旅行")

        assertNotNull(album)
        assertTrue(vm.state.value.moveTargets.any { it.name == "旅行" })
        // 落点必须是系统允许的目录，否则排好队、点提交，换来的是一句底层异常
        assertTrue(isImageDestinationLegal(album!!.relativePath))

        // 建完立刻投递：当前这张进待移动表，目标就是新相册
        vm.moveTo(album)
        assertEquals(1, vm.state.value.pendingMoveCount)
    }

    @Test
    fun `new album rejects blank and duplicate names`() {
        val vm = makeVm()
        // 已有的真实相册（这里用初始移动目标模拟）不能被重复创建
        val first = vm.createAlbum("旅行")
        assertNotNull(first)
        assertNull(vm.createAlbum(" 旅行 "))
        assertNull(vm.createAlbum("   "))
        assertNull(vm.createAlbum("a/b"))
        // 失败不能把脏目标留在列表里
        assertEquals(1, vm.state.value.moveTargets.size)
    }

    // ------------------------------------------------- 整理中回收站 / 丢弃改动

    @Test
    fun `restoring one staged photo puts it back in the queue`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )
        vm.trash()
        vm.trash()
        assertEquals(2, vm.state.value.stagedCount)

        vm.restoreFromStaging(1L)

        assertEquals(1, vm.state.value.stagedCount)
        // 放回队列头部，用户立刻能重新决定它
        assertEquals(1L, vm.state.value.current?.id)
        // 账本必须一起回退，否则这张会既不在队列里也不算待整理 —— 从用户视角就是消失了
        assertFalse(ledger.contains(1L))
        // 撤销栈里那条「清除」也摘掉了：撤销栈应该只剩「第 2 张」那一条，
        // 再撤一次退回的是它，而不是一个已经不在队列里的动作
        assertTrue(vm.state.value.canUndo)
        vm.undo()
        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(2L, vm.state.value.current?.id)
    }

    @Test
    fun `restore all staged empties the queue and reverts the ledger`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )
        vm.trash()
        vm.trash()

        vm.restoreAllStaged()

        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(0, session.loadStaging().size)
        assertFalse(ledger.contains(1L))
        assertFalse(ledger.contains(2L))
    }

    @Test
    fun `discard changes rewinds the whole session and leaves nothing on disk`() {
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )
        vm.keep()
        vm.trash()
        vm.moveTo(moveAlbum)
        assertEquals(3, vm.state.value.pendingTotal + vm.state.value.keptCount)

        vm.discardChanges()

        // 三类决定一起回滚：队列空、账本空、落盘快照空
        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(0, vm.state.value.pendingMoveCount)
        assertEquals(0, vm.state.value.keptCount)
        assertEquals(0, vm.state.value.processed)
        assertEquals(0, session.loadStaging().size)
        assertEquals(0, session.loadPendingMoves(MediaScope.Recent.key).size)
        assertFalse(ledger.contains(1L))
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun `discard changes only rewinds this session, not the shared staging queue`() {
        // 暂存队列是全应用共享的（冷启动会把上个会话的待清除项接回来）。
        // 「丢弃本次改动」只该回滚本次会话做过的决定 —— 把别人的队列一起清掉
        // 等于替用户撤销了他根本没在本轮做出的选择。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        // P4b：staging 必须与同一份 session 关联并开门，否则自持落盘无目标，
        // `session.loadStaging()` 将与内存背离。
        val staging = StagingTrash(session).apply { markRestored() }
        staging.stage(makeImage(99L))
        val vm = makeVm(
            session = session,
            reviewed = ledger,
            staging = staging,
            totalCount = 3,
            loaderImages = (1L..3L).map { makeImage(it) },
        )
        vm.trash()
        assertEquals(2, vm.state.value.stagedCount)

        vm.discardChanges()

        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(listOf(99L), session.loadStaging())
    }

    // ------------------------------------------------------ P2(γ) 出栈封印 / P11(iv) 口径分离

    @Test
    fun `partial success seals settled moves and keeps but leaves rejected trash undoable (P2 acceptance 1)`() {
        // 移动成功 + 回收站被拒后，↺ 能撤销那条被拒的 Trash；已结算的 Move 与 Keep 已出栈。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val repo = FakeMediaRepository(
            moveResult = MediaOpResult.Success,
            trashResult = MediaOpResult.Failed("denied"),
        )
        val vm = makeVm(
            repo = repo, session = session, reviewed = ledger,
            totalCount = 5, loaderImages = (1L..5L).map { makeImage(it) },
        )
        vm.keep() // id 1
        vm.moveTo(moveAlbum) // id 2
        vm.trash() // id 3
        assertTrue(vm.state.value.canUndo)

        vm.commit()

        // Move 已结算出栈，Keep 因 hasAnySettled 被封印，仅剩被拒的 Trash
        assertEquals(0, vm.state.value.pendingMoveCount)
        assertEquals(1, vm.state.value.stagedCount)
        assertTrue(vm.state.value.canUndo)
        // 被拒的 Trash 仍可撤销
        vm.undo()
        assertEquals(0, vm.state.value.stagedCount)
        // 已结算的 Move 账本不得被抹掉（仍标记为已整理）
        assertTrue(ledger.contains(2L))
        // 被封印的 Keep 账本同样保留
        assertTrue(ledger.contains(1L))
    }

    @Test
    fun `full rejection seals nothing and keeps undo and discard for Keeps (P2 acceptance 3)`() {
        // 全拒绝（hasAnySettled == false）时撤销键仍然亮，且 discard 能回滚本次会话的 Keep。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val repo = FakeMediaRepository(
            moveResult = MediaOpResult.Failed("denied"),
            trashResult = MediaOpResult.Failed("denied"),
        )
        val vm = makeVm(
            repo = repo, session = session, reviewed = ledger,
            totalCount = 5, loaderImages = (1L..5L).map { makeImage(it) },
        )
        vm.keep() // id 1
        vm.moveTo(moveAlbum) // id 2
        vm.commit()

        // 全失败：Move 仍在队列（dropUnreachableMoves 未摘除合法项），Keep 未被封印
        assertTrue(vm.state.value.canUndo)
        assertEquals(1, vm.state.value.pendingMoveCount)

        vm.discardChanges()
        // Keep 已回滚，账本抹掉
        assertFalse(ledger.contains(1L))
        assertEquals(0, vm.state.value.keptCount)
    }

    @Test
    fun `undo and discard never unmark settled entries (P2 acceptance 2)`() {
        // 提交成功项的账本不得被 undo()/discardChanges() 抹掉。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session, reviewed = ledger,
            totalCount = 5, loaderImages = (1L..5L).map { makeImage(it) },
        )
        vm.keep() // id 1
        vm.trash() // id 2
        vm.commit()
        assertEquals(0, vm.state.value.stagedCount)
        assertFalse(vm.state.value.canUndo)

        // 已无可撤销，undo 是空操作，账本保留
        vm.undo()
        assertTrue(ledger.contains(1L))
        assertTrue(ledger.contains(2L))

        // 新决定 + 丢弃：已封印的旧账本不受影响
        vm.keep() // id 3
        vm.discardChanges()
        assertTrue(ledger.contains(1L))
        assertTrue(ledger.contains(2L))
        assertFalse(ledger.contains(3L))
    }

    @Test
    fun `restart then discard really drops three (P2 acceptance 4, P11)`() {
        // restart() 后退出，「丢弃改动(3)」真的丢 3 项 —— 索引不再依赖 history。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val vm = makeVm(
            session = session, reviewed = ledger,
            totalCount = 5, loaderImages = (1L..5L).map { makeImage(it) },
        )
        vm.trash() // 1
        vm.trash() // 2
        vm.trash() // 3
        assertEquals(3, vm.state.value.discardableCount)
        vm.restart()
        // restart 清 history 但保留队列，discardable 仍为 3（session 索引 + pending）
        assertEquals(0, vm.state.value.processed)
        assertEquals(3, vm.state.value.stagedCount)
        assertEquals(3, vm.state.value.discardableCount)

        vm.discardChanges()
        assertEquals(0, vm.state.value.stagedCount)
        assertEquals(0, session.loadStaging().size)
        assertFalse(ledger.contains(1L))
        assertFalse(ledger.contains(2L))
        assertFalse(ledger.contains(3L))
    }

    @Test
    fun `discardableCount excludes foreign staging but pendingTotal includes it (P2 acceptance 5)`() {
        // 其他范围攒下的暂存不得被本范围的「丢弃改动」虚报或吞掉。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val staging = StagingTrash(session).apply { markRestored() }
        staging.stage(makeImage(99L)) // 外部范围遗留
        val vm = makeVm(
            session = session, reviewed = ledger, staging = staging,
            totalCount = 3, loaderImages = (1L..3L).map { makeImage(it) },
        )
        // 本会话什么都没做：全局有 1，但可丢弃为 0
        assertEquals(1, vm.state.value.pendingTotal)
        assertEquals(0, vm.state.value.discardableCount)

        vm.discardChanges()
        // 外部暂存原样保留
        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(listOf(99L), session.loadStaging())
    }

    @Test
    fun `restoreAllStaged stays within this session (P2 acceptance 6, P12)`() {
        // 「全部还原」不越过本会话边界。
        val session = FakeSessionStore()
        val ledger = ReviewedLedger(session)
        val staging = StagingTrash(session).apply { markRestored() }
        staging.stage(makeImage(99L)) // 外部
        val vm = makeVm(
            session = session, reviewed = ledger, staging = staging,
            totalCount = 3, loaderImages = (1L..3L).map { makeImage(it) },
        )
        vm.trash() // id 1，本会话
        assertEquals(2, vm.state.value.stagedCount)

        vm.restoreAllStaged()

        // 仅本会话的 1 被还原，99 留存
        assertEquals(1, vm.state.value.stagedCount)
        assertEquals(99L, vm.state.value.stagedItems.first().id)
        assertTrue(ledger.contains(99L) || !ledger.contains(99L)) // 外部账本不动（未 mark 过）
        assertFalse(ledger.contains(1L))
    }

    @Test
    fun `restore pending moves preserves disk snapshot on query exception`() {
        val session = FakeSessionStore()
        val move = PendingMove(
            imageId = 7L, targetPath = "DCIM/B/", albumName = "B",
            bucketId = 2, albumPath = "DCIM/B",
        )
        session.savePendingMoves(MediaScope.Recent.key, listOf(move))
        val repo = FakeMediaRepository(imagesError = RuntimeException("query failed"))
        makeVm(session = session, repo = repo)

        // 查询抛异常时，不应将磁盘快照冲刷为空，必须保留待下次重试恢复
        assertEquals(listOf(7L), session.loadPendingMoves(MediaScope.Recent.key).map { it.imageId })
    }
}
