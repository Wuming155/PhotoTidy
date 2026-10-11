package com.phototidy.core.media

import com.phototidy.core.model.MediaAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // relativePath 小写、相册名大写：落点原样复用真实目录，不会另建 DCIM/Camera/ 造成异大小写重名
        val album = MediaAlbum(
            bucketId = 4, name = "Camera", relativePath = "DCIM/camera", count = 0, cover = null,
        )
        assertEquals("DCIM/camera/", computeTargetPath(album))
    }

    @Test
    fun `localized bucket name still reuses the real folder`() {
        // 中文 ROM 上 DCIM/Camera 的桶名是「相机」。按「相册名是不是路径后缀」判断会判不出复用，
        // 转而在同一棵树里另建 DCIM/相机/ —— 同一个相册被劈成两栏。落点只看路径，不看显示名。
        val album = MediaAlbum(
            bucketId = 5, name = "相机", relativePath = "DCIM/Camera", count = 0, cover = null,
        )
        assertEquals("DCIM/Camera/", computeTargetPath(album))
    }

    @Test
    fun `download album falls back to a legal root`() {
        // 实机报错：allowed directories are [DCIM, Pictures] —— 图片进不了 Download/，
        // 所以「移到 Download 相册」这个落点必须换掉，否则提交时必然撞 IllegalArgumentException。
        assertFalse(isImageDestinationLegal("Download/"))
        val album = MediaAlbum(
            bucketId = 6, name = "Download", relativePath = "Download", count = 0, cover = null,
        )
        assertEquals("Pictures/Download/", computeTargetPath(album))
    }

    @Test
    fun `only DCIM and Pictures can receive images`() {
        assertTrue(isImageDestinationLegal("DCIM/Camera/"))
        assertTrue(isImageDestinationLegal("Pictures/Screenshots"))
        assertTrue(isImageDestinationLegal("/Pictures/"))
        assertTrue(isImageDestinationLegal("dcim/"))
        assertFalse(isImageDestinationLegal(""))
        assertFalse(isImageDestinationLegal("/"))
        assertFalse(isImageDestinationLegal("Download/"))
        assertFalse(isImageDestinationLegal("Android/media/com.example/"))
        assertFalse(isImageDestinationLegal("Documents/"))
    }
}
