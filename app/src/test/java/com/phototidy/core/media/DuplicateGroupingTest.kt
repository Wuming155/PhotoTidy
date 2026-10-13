package com.phototidy.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复照片的分组口径。
 *
 * 这是整个功能里**判错就要误删照片**的那一步：分组太松会把连拍、相似构图
 * 当成重复（用户以为删的是垃圾），分组太严则功能形同不存在。
 * 它被刻意抽成不依赖任何 Android 类型的纯函数，就是为了能在 JVM 单测里钉住。
 */
class DuplicateGroupingTest {

    @Test
    fun `matching fingerprints group together`() {
        val groups = groupByFingerprint(
            listOf(11L to "a", 22L to "b", 11L to "c"),
        )
        assertEquals(1, groups.size)
        // 指纹 22 只有一张，它不是重复，不能混进这一组
        assertEquals(setOf("a", "c"), groups[0].toSet())
    }

    @Test
    fun `a unique fingerprint is not a duplicate`() {
        // 单个指纹绝不是「重复」—— 这是最容易写错的一处（filter 写成 >= 1）
        assertTrue(
            groupByFingerprint(listOf(1L to "only")).isEmpty(),
        )
    }

    @Test
    fun `fingerprints differing by a single bit stay separate`() {
        // 刻意不做「汉明距离 ≤ N」的近似匹配：相邻的指纹意味着画面只说差一点点，
        // 而连拍恰好就是这种关系 —— 把它们判成重复，用户会以为自己删的是垃圾。
        val a = 0b0000_0000L
        val b = 0b0000_0001L
        val groups = groupByFingerprint(listOf(a to "shot1", b to "shot2"))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `multiple groups are all reported`() {
        val groups = groupByFingerprint(
            listOf(1L to "a", 1L to "b", 2L to "c", 2L to "d", 3L to "e"),
        )
        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups[0].toSet())
        assertEquals(setOf("c", "d"), groups[1].toSet())
    }
}
