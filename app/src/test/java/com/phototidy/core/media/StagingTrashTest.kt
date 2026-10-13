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
    fun `restore merges with in-flight items and persists`() {
        val store = FakeSessionStore().apply { staging = listOf(2L) }
        val trash = StagingTrash(store)
        trash.stage(img(1))
        // 队列非空时 restore 应合并恢复项与内存中先滑入的项（去重），并落盘开门
        trash.restore(listOf(img(2), img(1)))
        assertEquals(2, trash.items.value.size)
        assertEquals(setOf(1L, 2L), trash.items.value.map { it.id }.toSet())
        assertTrue(trash.restored)
        assertEquals(listOf(2L, 1L), store.staging)
    }

    @Test
    fun `restore empty queue without pruning adopts items without rewriting disk`() {
        val store = FakeSessionStore().apply { staging = listOf(1L, 2L) }
        val trash = StagingTrash(store)
        // 初始为空队列，且未剔除任何项：换入队列并开门，无需多做一次磁盘写入
        trash.restore(listOf(img(1), img(2)), pruned = false)
        assertEquals(2, trash.items.value.size)
        assertTrue(trash.restored)
        assertEquals(0, store.saves)
    }

    @Test
    fun `restore empty queue with pruning persists to align disk`() {
        val store = FakeSessionStore().apply { staging = listOf(1L, 2L) }
        val trash = StagingTrash(store)
        // 发现失效 id 并剔除后（pruned = true）：换入队列、开门，并主动落盘对齐
        trash.restore(listOf(img(1)), pruned = true)
        assertEquals(1, trash.items.value.size)
        assertTrue(trash.restored)
        assertEquals(1, store.saves)
        assertEquals(listOf(1L), store.staging)
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
