package com.phototidy.core.media

import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.PendingMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingTrashTest {

    private fun img(id: Long) = MediaImage(
        id = id, uri = "content://x/$id", displayName = "n$id",
        takenAtMillis = 0, addedAtMillis = 0, bucketId = 1, albumName = "A",
        relativePath = "DCIM/A", sizeBytes = 1, width = 1, height = 1,
        isFavorite = false, isTrashed = false,
    )

    private class FakeSessionStore : SessionStore {
        var staging: List<Long> = emptyList()
        var saves = 0
        override fun saveStaging(imageIds: List<Long>) {
            saves++
            staging = imageIds.toList()
        }
        override fun loadStaging(): List<Long> = staging
        override fun clearStaging() { staging = emptyList() }
        override fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {}
        override fun loadPendingMoves(scopeKey: String): List<PendingMove> = emptyList()
        override fun clearPendingMoves(scopeKey: String) {}
        override fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>) {}
        override fun loadReviewed(): List<Long> = emptyList()
    }

    @Test
    fun `stage dedups and unstage removes`() {
        // P4b：注入假实现并开门，内存语义与自持落盘一起验证。
        val store = FakeSessionStore()
        val trash = StagingTrash(store).apply { markRestored() }
        val a = img(1)
        trash.stage(a)
        trash.stage(a) // 重复入队应被忽略
        assertEquals(1, trash.items.value.size)
        assertEquals(listOf(1L), store.staging)

        trash.unstage(1)
        assertEquals(0, trash.items.value.size)
        assertEquals(emptyList<Long>(), store.staging)
    }

    @Test
    fun `remove batches out`() {
        val store = FakeSessionStore()
        val trash = StagingTrash(store).apply { markRestored() }
        trash.stage(img(1))
        trash.stage(img(2))
        trash.remove(listOf(1L))
        assertEquals(1, trash.items.value.size)
        assertEquals(2L, trash.items.value.first().id)
        assertEquals(listOf(2L), store.staging)
    }

    @Test
    fun `restore only when empty`() {
        val store = FakeSessionStore()
        val trash = StagingTrash(store).apply { markRestored() }
        trash.stage(img(1))
        // 队列非空时 restore 不应覆盖用户已经排好的队
        trash.restore(listOf(img(2)))
        assertEquals(1, trash.items.value.size)
        assertEquals(1L, trash.items.value.first().id)
    }

    @Test
    fun `no persistence before restored (P4b gate)`() {
        // 恢复前拒绝任何落盘：进程被杀恢复路径下，恢复完成前的 stage 不得覆写磁盘快照。
        val store = FakeSessionStore()
        val trash = StagingTrash(store)
        assertFalse(trash.restored)
        trash.stage(img(1))
        assertEquals(1, trash.items.value.size)
        assertEquals(0, store.saves)
        assertEquals(emptyList<Long>(), store.staging)

        trash.markRestored()
        assertTrue(trash.restored)
        trash.stage(img(2))
        assertEquals(listOf(1L, 2L), store.staging)
    }
}
