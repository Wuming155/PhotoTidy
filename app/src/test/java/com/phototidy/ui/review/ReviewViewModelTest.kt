package com.phototidy.ui.review

import com.phototidy.core.StringProvider
import com.phototidy.core.media.MediaOpCoordinator
import com.phototidy.core.media.MediaOpResult
import com.phototidy.core.media.MediaRepository
import com.phototidy.core.media.SessionStore
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
        override fun saveStaging(imageIds: List<Long>) { staging.clear(); staging.addAll(imageIds) }
        override fun loadStaging(): List<Long> = staging.toList()
        override fun clearStaging() = staging.clear()
        override fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {
            pending[scopeKey] = moves.toList()
        }
        override fun loadPendingMoves(scopeKey: String): List<PendingMove> = pending[scopeKey].orEmpty()
        override fun clearPendingMoves(scopeKey: String) { pending.remove(scopeKey) }
    }

    private class FakeMediaRepository(
        private val moveResult: MediaOpResult = MediaOpResult.Success,
        private val trashResult: MediaOpResult = MediaOpResult.Success,
        private val images: Map<Long, MediaImage> = emptyMap(),
        /** 非空时 moveToAlbums 直接抛出：模拟 ROM 上意料外的异常（H1）。 */
        private val moveError: Throwable? = null,
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
        override suspend fun imagesByIds(ids: Collection<Long>): List<MediaImage> =
            ids.mapNotNull { images[it] }
    }

    private fun makeVm(
        repo: MediaRepository = FakeMediaRepository(),
        session: SessionStore = FakeSessionStore(),
        scope: MediaScope = MediaScope.Recent,
        totalCount: Int = 20,
        loaderImages: List<MediaImage> = (1L..20L).map { makeImage(it) },
        loader: (suspend (Int, Int) -> List<MediaImage>)? = null,
        onCommitted: () -> Unit = {},
    ): ReviewViewModel {
        val staging = StagingTrash()
        val strings = object : StringProvider {
            override fun get(resId: Int, vararg args: Any) = "msg"
        }
        val ops = MediaOpCoordinator(strings)
        return ReviewViewModel(
            repo = repo, staging = staging, ops = ops, session = session, strings = strings,
            scope = scope, totalCount = totalCount, initialMoveTargets = emptyList(),
            loader = loader ?: { _, _ -> loaderImages },
            onCommitted = onCommitted,
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
}
