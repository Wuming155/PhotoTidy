package com.phototidy.ui.review

import com.phototidy.core.model.MediaImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动窗口队列的口径。
 *
 * 这里钉的是那条**曾经断掉、而且断得很难看**的不变量：
 * 「[ReviewQueueWindow.peek] 返回非 null 的位置上，[take] 必须真的能取出照片来」。
 *
 * 断掉的后果不是「少一张」而是死局：屏幕上有照片、keep / trash / move 全部静默失效、
 * 进度也永远走不完（用户唯一的出路是「再来一轮」或退出重进）。
 * 两个出口分在两个文件里时，这一条靠读代码发现不了 —— 两边单看都是对的。
 */
class ReviewQueueWindowTest {

    private fun img(id: Long) = MediaImage(
        id = id, uri = "content://x/$id", displayName = "n$id",
        takenAtMillis = 0, addedAtMillis = 0, bucketId = 1, albumName = "A",
        relativePath = "DCIM/A", sizeBytes = 1, width = 1, height = 1,
        isFavorite = false, isTrashed = false,
    )

    private fun windowOf(total: Int, loaded: List<Long>, pageSize: Int = 150): ReviewQueueWindow {
        val window = ReviewQueueWindow(total)
        window.acceptPage(loaded.map(::img), pageSize) { true }
        return window
    }

    /** 全部按「取到的顺序」消费一遍。 */
    private fun drain(window: ReviewQueueWindow): List<Long> =
        generateSequence { window.take() }.map { it.id }.toList()

    @Test
    fun `take returns exactly what peek showed`() {
        val window = windowOf(total = 3, loaded = listOf(1, 2, 3), pageSize = 3)
        repeat(3) {
            assertSame(window.peek(0), window.take())
        }
        assertNull(window.take())
        assertEquals(0, window.remaining)
    }

    @Test
    fun `the unloaded region reads as null instead of being jumped over`() {
        // 还没取到的照片在队列里**占位**：不占位的话，「稍后再看」的照片会插到它们前面，
        // 用户会先看到一张早就跳过的照片，而真正该看的那批还没加载出来。
        val window = ReviewQueueWindow(totalCount = 10)
        window.acceptPage(listOf(img(1), img(2)), pageSize = 2) { true }

        assertEquals(1L, window.peek(0)?.id)
        assertEquals(2L, window.peek(1)?.id)
        assertNull("位置 2 是一张还没取到的照片", window.peek(2))
        assertFalse(window.sourceExhausted)
        assertTrue(window.needsRefill(refillBelow = 10))

        assertEquals(1L, window.take()?.id)
        assertEquals(2L, window.take()?.id)
        // 窗口空了、数据源还有 8 张：这是**转圈态**（preparing），不是「可以往下取」
        assertNull(window.peek(0))
        assertNull("队首还没到，谁都取不出来 —— 但这不是死局：补页之后它就有了", window.take())
        assertEquals(8, window.remaining)
    }

    @Test
    fun `deferred photos come last, and are still consumable`() {
        // 这一条是 C1 的回归闸门：曾经 peek 会把 deferred 当成队首，
        // 而 take 只认已加载的那一段 —— 于是滑到最后一张时停在 deferred 上，谁都推不动它。
        val window = windowOf(total = 3, loaded = listOf(1, 2, 3), pageSize = 3)

        assertEquals(1L, window.deferCurrent()?.id)
        assertEquals("跳过之后队首是第 2 张", 2L, window.peek(0)?.id)
        assertEquals("deferred 排在整条队列最后", 1L, window.peek(2)?.id)

        assertEquals(2L, window.take()?.id)
        assertEquals(3L, window.take()?.id)
        // 队列里只剩那张被 defer 的：它必须**成为可操作对象**，而不是卡在屏幕上
        assertEquals(1, window.remaining)
        assertEquals(1L, window.peek(0)?.id)
        assertEquals(1L, window.take()?.id)
        assertEquals(0, window.remaining)
    }

    @Test
    fun `a deferred photo is not skippable when the source still has pages`() {
        // 数据源还有内容时，deferred 仍然要排在「还没取到」的后面 ——
        // 否则用户刚说「稍后再看」，下一张立刻又是它。
        val window = ReviewQueueWindow(totalCount = 10)
        window.acceptPage(listOf(img(1), img(2)), pageSize = 2) { true }
        window.deferCurrent()

        assertEquals(2L, window.peek(0)?.id)
        assertNull("位置 1 是还没取到的照片，不是 deferred", window.peek(1))

        window.acceptPage((3L..10L).map(::img), pageSize = 8) { true }
        // 9 张已加载（第 2 张 + 新的一整页）+ 1 张 deferred
        assertEquals(10, window.remaining)
        assertEquals("9 张已加载之后才是被 defer 的那张", 1L, window.peek(9)?.id)
    }

    @Test
    fun `filtered photos still advance the watermark`() {
        // 「已整理的照片不进队列」与「它们算作已取到」必须分开：
        // 把滤掉的照片也从水位里扣掉，下一页请求会从同一个 offset 再取一遍，永远翻不过去。
        val window = ReviewQueueWindow(totalCount = 6)
        window.acceptPage(listOf(img(1), img(2), img(3)), pageSize = 3) { it.id != 2L }

        assertEquals("水位按页大小推进，与过滤结果无关", 3, window.loadedCount)
        assertEquals(listOf(1L, 3L), drain(window).take(2).sorted())
        assertNull("窗口被滤空但数据源还有内容", window.peek(0))
        assertEquals(3, window.remaining)
    }

    @Test
    fun `a short page marks the source exhausted`() {
        val window = ReviewQueueWindow(totalCount = 100)
        window.acceptPage(listOf(img(1)), pageSize = 150) { true }
        assertTrue(window.sourceExhausted)
        assertFalse("到头了就不该再请求", window.needsRefill(refillBelow = 75))
    }

    @Test
    fun `pushFront makes an undone photo the very next one`() {
        val window = windowOf(total = 3, loaded = listOf(1, 2, 3), pageSize = 3)
        window.take()
        window.take()
        assertEquals(3L, window.peek(0)?.id)

        window.pushFront(img(2))
        assertEquals(2L, window.peek(0)?.id)
        assertEquals(2L, window.take()?.id)
        assertEquals(3L, window.peek(0)?.id)
    }

    @Test
    fun `clear empties every segment so a restart really starts over`() {
        val window = windowOf(total = 3, loaded = listOf(1, 2, 3), pageSize = 3)
        window.deferCurrent()
        window.clear()

        // 「再来一轮」= 这一范围的 3 张照片全部重新排队：水位归零 ⇒ 3 张都算未加载
        assertEquals(3, window.remaining)
        assertEquals(0, window.loadedCount)
        assertNull(window.peek(0))
        assertNull(window.take())
        assertTrue("重来一轮之后还要能从头取数", window.needsRefill(refillBelow = 1))
    }

    @Test
    fun `every photo is counted exactly once`() {
        val window = ReviewQueueWindow(totalCount = 10)
        window.acceptPage(listOf(img(1), img(2)), pageSize = 2) { true }
        window.deferCurrent()
        // 1 张 deferred + 1 张已加载 + 8 张未加载
        assertEquals(10, window.remaining)
        window.acceptPage((3L..10L).map(::img), pageSize = 8) { true }
        assertEquals(10, window.remaining)
        assertEquals(10, drain(window).size)
        assertEquals(0, window.remaining)
    }
}
