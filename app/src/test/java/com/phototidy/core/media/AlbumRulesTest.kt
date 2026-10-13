package com.phototidy.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新建相册的名字规则与截图路径判据。
 *
 * 这两条都是**纯函数**，所以能在 JVM 单测里直测 —— 它们的共同点是
 * 「一旦判错，用户看到的是照片落到了意料之外的地方」，值得钉住。
 */
class AlbumRulesTest {

    // ------------------------------------------------------------ 相册名

    @Test
    fun `blank name is empty issue`() {
        assertEquals(AlbumNameIssue.Empty, newAlbumIssue("", emptyList()))
        assertEquals(AlbumNameIssue.Empty, newAlbumIssue("   ", emptyList()))
    }

    @Test
    fun `path separators and control chars are rejected`() {
        // 带分隔符的名字会变成嵌套目录：用户以为建了一个相册，实际建了两层
        assertEquals(AlbumNameIssue.IllegalChars, newAlbumIssue("a/b", emptyList()))
        assertEquals(AlbumNameIssue.IllegalChars, newAlbumIssue("a\\b", emptyList()))
        assertEquals(AlbumNameIssue.IllegalChars, newAlbumIssue("a\u0000b", emptyList()))
    }

    @Test
    fun `duplicate is rejected case insensitively and ignoring padding`() {
        // 多数设备的文件系统不区分大小写，放行 Cat / cat 只会在磁盘上撞成一个目录
        assertEquals(AlbumNameIssue.Duplicate, newAlbumIssue("cat", listOf("Cat")))
        assertEquals(AlbumNameIssue.Duplicate, newAlbumIssue(" 旅行 ", listOf("旅行")))
    }

    @Test
    fun `a fresh name is accepted`() {
        assertNull(newAlbumIssue("旅行", listOf("相机", "截图")))
    }

    @Test
    fun `new album path always lands under Pictures and is a legal destination`() {
        // 空目录在 MediaStore 里不存在：目录由第一张照片的 RELATIVE_PATH 带出来，
        // 所以这个路径必须本身就是系统允许的落点。
        assertEquals("Pictures/旅行", newAlbumPath(" 旅行 "))
        assertTrue(isImageDestinationLegal(newAlbumPath("旅行")))
        assertTrue(isImageDestinationLegal(newAlbumPath("Screenshots")))
    }

    // ------------------------------------------------------------ 截图判据

    @Test
    fun `screenshot paths match the two known roots`() {
        assertTrue(isScreenshotPath("Pictures/Screenshots/"))
        assertTrue(isScreenshotPath("Pictures/Screenshots"))
        assertTrue(isScreenshotPath("DCIM/Screenshots/"))
        assertTrue(isScreenshotPath("pictures/screenshots/"))
    }

    @Test
    fun `prefix must end at a path boundary`() {
        // 否则 `Pictures/ScreenshotsBackup` 这类目录会被整批当成截图
        assertFalse(isScreenshotPath("Pictures/ScreenshotsOld/"))
        assertFalse(isScreenshotPath("Pictures/Camera/"))
        assertFalse(isScreenshotPath(""))
    }
}
