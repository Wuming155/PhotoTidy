package com.phototidy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `grouped adds a thousands separator`() {
        assertTrue(1234.grouped().contains(","))
    }

    @Test
    fun `readable size handles bytes and kilobytes`() {
        assertEquals("0 B", 0L.toReadableSize())
        assertEquals("1 B", 1L.toReadableSize())
        val kb = 1024L.toReadableSize()
        assertTrue(kb.contains("KB"))
        assertTrue(kb.startsWith("1"))
    }

    @Test
    fun `sumReadableSize sums`() {
        assertEquals("2 B", listOf(1L, 1L).sumReadableSize())
    }

    @Test
    fun `photoDateTime is non-empty`() {
        assertTrue(1_600_000_000_000L.toPhotoDateTime().isNotEmpty())
    }
}
