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
    ) : MediaRepository {
        var moveCalls = 0
        var trashCalls = 0
        override suspend fun moveToAlbums(items: List<PendingMove>): MediaOpResult {
            moveCalls++
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
    ): ReviewViewModel {
        val staging = StagingTrash()
        val strings = object : StringProvider {
            override fun get(resId: Int, vararg args: Any) = "msg"
        }
        val ops = MediaOpCoordinator(strings)
        return ReviewViewModel(
            repo = repo, staging = staging, ops = ops, session = session, strings = strings,
            scope = scope, totalCount = totalCount, initialMoveTargets = emptyList(),
            loader = { _, _ -> loaderImages },
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
    }
}
