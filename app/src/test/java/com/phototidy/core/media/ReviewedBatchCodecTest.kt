package com.phototidy.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量批次的编解码。
 *
 * 这是账本里唯一「写错了就丢数据」的地方，所以它刻意不依赖 `org.json`
 * （后者在 JVM 单测里是桩实现，方法一律返回默认值，测了等于没测）。
 */
class ReviewedBatchCodecTest {

    private fun decode(raw: String): Set<Long> = LinkedHashSet<Long>().also {
        decodeReviewedBatch(raw, it)
    }

    @Test
    fun `marked ids round-trip`() {
        val raw = encodeReviewedBatch(listOf(11L, 22L, 33L), emptyList())
        assertEquals(setOf(11L, 22L, 33L), decode(raw))
    }

    @Test
    fun `a negative id means removal`() {
        val raw = encodeReviewedBatch(listOf(11L, 22L), listOf(22L))
        // 同一批里既标又撤时，「撤」写在后面 —— 语义是「以最后的状态为准」
        assertEquals(setOf(11L), decode(raw))
    }

    @Test
    fun `an empty batch decodes to nothing`() {
        assertEquals(emptySet<Long>(), decode(encodeReviewedBatch(emptyList(), emptyList())))
        assertEquals(emptySet<Long>(), decode(""))
        assertEquals(emptySet<Long>(), decode("   "))
    }

    @Test
    fun `a corrupted batch is skipped instead of taking the whole ledger down`() {
        // 单批坏掉不该让整个账本读不出来（那等于用户的整理记录全丢）
        assertEquals(setOf(5L), decode("5 abc -7x"))
    }

    @Test
    fun `tokens outside the legal id domain are dropped`() {
        // 合法域是 1..Long.MAX_VALUE。0 与 Long.MIN_VALUE 会让「用符号表意」这个技巧失效
        // （-0L == 0L，-Long.MIN_VALUE == Long.MIN_VALUE 回绕），一律当损坏 token 丢弃，
        // 而且不能连累同一批里的合法 id。
        //
        // ⚠️ 这三个 token 都**不可能**由 encodeReviewedBatch 产出（`(-0L).toString()` 是 "0"，
        // Long.MIN_VALUE 也不在 id 域内），所以这里钉的是「读到脏数据时的行为」，不是编码格式。
        assertEquals(setOf(5L), decode("5 0 -0 ${Long.MIN_VALUE}"))
        assertEquals(emptySet<Long>(), decode("0"))
        assertEquals(emptySet<Long>(), decode("-0"))
        assertEquals(emptySet<Long>(), decode(Long.MIN_VALUE.toString()))
    }

    @Test
    fun `the legal domain includes its extremes`() {
        // 合法区间的上端必须能往返：Long.MAX_VALUE 编码成负数不会回绕
        assertEquals(
            setOf(Long.MAX_VALUE),
            decode(encodeReviewedBatch(listOf(Long.MAX_VALUE), emptyList())),
        )
        assertEquals(
            emptySet<Long>(),
            decode(encodeReviewedBatch(listOf(Long.MAX_VALUE), listOf(Long.MAX_VALUE))),
        )
    }

    @Test
    fun `decoding in order applies removals to earlier batches`() {
        // 冷启动是从第 0 批往后折叠的，跨批次的取消必须能删掉前面批次里的 id
        val out = LinkedHashSet<Long>().also {
            decodeReviewedBatch(encodeReviewedBatch(listOf(1L, 2L), emptyList()), it)
            decodeReviewedBatch(encodeReviewedBatch(emptyList(), listOf(1L)), it)
        }
        assertEquals(setOf(2L), out)
    }

    @Test
    fun `large ids survive the sign trick`() {
        val big = 9_000_000_000_000L
        assertEquals(setOf(big), decode(encodeReviewedBatch(listOf(big), emptyList())))
    }

    @Test
    fun `a snapshot is just a batch with no removals`() {
        // 合并（compaction）写出来的那份就是这个形状 —— 它必须能被同一套解码读回来
        val snapshot = encodeReviewedBatch(listOf(1L, 2L, 3L), emptyList())
        assertTrue(snapshot.indexOf('-') < 0)
        assertEquals(setOf(1L, 2L, 3L), decode(snapshot))
    }
}
