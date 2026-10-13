package com.phototidy.core.media

import com.phototidy.core.model.PendingMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已整理」账本的落盘口径。
 *
 * 这里钉的是它**只写增量**这一条：账本是全应用唯一会随用户操作不断变大的数据，
 * 每次落盘都重写全量的话，代价是 O(N²)，而且发生在滑动回调所在的线程上 ——
 * 几万张的库上，用户能直接感觉到每一次落盘。
 */
class ReviewedLedgerTest {

    /** 内存实现，追加语义与 `RealSessionStore` 的批次折叠严格一致。 */
    private class FakeStore : SessionStore {
        val batches = mutableListOf<Pair<List<Long>, List<Long>>>()
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

        override fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>) {
            batches += marked.toList() to unmarked.toList()
        }

        override fun loadReviewed(): List<Long> {
            val out = linkedSetOf<Long>()
            batches.forEach { (marked, unmarked) ->
                out.removeAll(unmarked.toSet())
                out.addAll(marked)
            }
            return out.toList()
        }
    }

    @Test
    fun `flush only writes what changed since the previous flush`() {
        val store = FakeStore()
        val ledger = ReviewedLedger(store)

        ledger.mark(1L)
        ledger.mark(2L)
        ledger.flush()
        assertEquals(1, store.batches.size)
        assertEquals(listOf(1L, 2L), store.batches[0].first)

        ledger.mark(3L)
        ledger.flush()

        // 第二批只带第 3 张 —— 这就是「写入代价与账本总量无关」的落点。
        // 曾经这里每次都是全量列表，几万张时每次落盘都要重拼一整份。
        assertEquals(2, store.batches.size)
        assertEquals(listOf(3L), store.batches[1].first)
    }

    @Test
    fun `flush with nothing pending writes nothing`() {
        val store = FakeStore()
        val ledger = ReviewedLedger(store)
        ledger.mark(1L)
        ledger.flush()
        ledger.flush()
        ledger.flush()
        assertEquals(1, store.batches.size)
    }

    @Test
    fun `marking and unmarking the same photo before a flush leaves it unmarked`() {
        val store = FakeStore()
        val ledger = ReviewedLedger(store)

        ledger.mark(5L)
        ledger.unmark(5L)
        ledger.flush()

        // 关键：那个 id 不会被「先标记、后取消」两笔一起写进去 ——
        // 只有最后的状态落盘，否则重新读回来的账本会多出一张已经撤销的照片
        assertTrue(store.loadReviewed().isEmpty())
        assertFalse(ledger.contains(5L))
    }

    @Test
    fun `unmarking then re-marking before a flush leaves it marked`() {
        val store = FakeStore()
        val ledger = ReviewedLedger(store)

        ledger.mark(7L)
        ledger.flush()
        ledger.unmark(7L)
        ledger.mark(7L)
        ledger.flush()

        assertEquals(listOf(7L), store.loadReviewed())
    }

    @Test
    fun `a fresh ledger restores everything the previous one wrote`() {
        val store = FakeStore()
        val first = ReviewedLedger(store)
        first.mark(1L)
        first.mark(2L)
        first.flush()
        first.unmark(1L)
        first.flush()

        val second = ReviewedLedger(store)
        assertTrue(second.contains(2L))
        assertFalse("取消过的记录不能复活", second.contains(1L))
        assertEquals(1, second.size)
    }

    @Test
    fun `batch unmark records every removal`() {
        val store = FakeStore()
        val ledger = ReviewedLedger(store)
        listOf(1L, 2L, 3L).forEach(ledger::mark)
        ledger.flush()

        ledger.unmark(listOf(1L, 3L))
        ledger.flush()

        assertEquals(listOf(2L), store.loadReviewed())
    }
}
