package com.phototidy.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaScopeCodecTest {

    @Test
    fun `recent round-trips`() {
        assertEquals(MediaScope.Recent, MediaScope.decode(MediaScope.encode(MediaScope.Recent)))
    }

    @Test
    fun `month round-trips`() {
        val scope = MediaScope.Month(2026, 10)
        assertEquals(scope, MediaScope.decode(MediaScope.encode(scope)))
    }

    @Test
    fun `album round-trips`() {
        val scope = MediaScope.Album(42L, "Camera")
        assertEquals(scope, MediaScope.decode(MediaScope.encode(scope)))
    }

    @Test
    fun `decode of unknown returns null`() {
        assertEquals(null, MediaScope.decode(""))
        assertEquals(null, MediaScope.decode(null))
        assertEquals(null, MediaScope.decode("bogus:1:2"))
    }

    @Test
    fun `decode recognises recent literal`() {
        assertEquals(MediaScope.Recent, MediaScope.decode("recent"))
    }
}
