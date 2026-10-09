package com.phototidy.core.media

import com.phototidy.core.model.MediaAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputeTargetPathTest {

    @Test
    fun `reuses existing folder path`() {
        val album = MediaAlbum(
            bucketId = 1, name = "Camera", relativePath = "DCIM/Camera", count = 0, cover = null,
        )
        assertEquals("DCIM/Camera/", computeTargetPath(album))
    }

    @Test
    fun `creates folder under existing root`() {
        val album = MediaAlbum(
            bucketId = 2, name = "Screenshots", relativePath = "Pictures/Screenshots", count = 0, cover = null,
        )
        assertEquals("Pictures/Screenshots/", computeTargetPath(album))
    }

    @Test
    fun `falls back to Pictures root when path blank`() {
        val album = MediaAlbum(
            bucketId = 3, name = "New", relativePath = "", count = 0, cover = null,
        )
        assertEquals("Pictures/New/", computeTargetPath(album))
    }

    @Test
    fun `case-insensitive reuse avoids duplicate folder (Nit7)`() {
        // relativePath 小写、相册名大写：大小写敏感会误建 DCIM/Camera/，与 DCIM/camera 形成重名
        val album = MediaAlbum(
            bucketId = 4, name = "Camera", relativePath = "DCIM/camera", count = 0, cover = null,
        )
        assertEquals("DCIM/camera/", computeTargetPath(album))
    }
}
