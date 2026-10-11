package com.phototidy.core.media

import com.phototidy.core.model.MediaAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class StandardDirsMissingTest {

    private fun album(name: String, path: String) = MediaAlbum(
        bucketId = 1, name = name, relativePath = path, count = 0, cover = null,
    )

    private val standard = listOf(
        "相机" to "DCIM/Camera",
        "截图" to "Pictures/Screenshots",
        "下载" to "Pictures/Downloads",
    )

    @Test
    fun `same name at another path does not hide the standard folder`() {
        // ROM 把 DCIM/Camera 的桶名本地化成「相机」；设备上只要另有一个叫「相机」的目录
        // （旧版本会造出 DCIM/相机），按名字去重就会把 DCIM/Camera 整个挤掉 ——
        // 于是这个合法落点在应用里根本选不到。按路径判断则三项照旧齐全
        // （DCIM/相机 与 DCIM/Camera 是两棵不同的目录，谁也替不了谁）。
        val missing = standardDirsMissing(listOf(album("相机", "DCIM/相机")), standard)
        assertEquals(standard, missing)
    }

    @Test
    fun `same path is not offered twice`() {
        // 目录真的已经是设备相册（同一路径）时，不该再多一张指向同一目录的合成卡
        val missing = standardDirsMissing(listOf(album("Camera", "DCIM/Camera/")), standard)
        assertEquals(
            listOf("截图" to "Pictures/Screenshots", "下载" to "Pictures/Downloads"),
            missing,
        )
    }

    @Test
    fun `everything is missing when there are no device albums`() {
        assertEquals(standard, standardDirsMissing(emptyList(), standard))
    }

    @Test
    fun `path comparison ignores surrounding slashes and case`() {
        val missing = standardDirsMissing(listOf(album("Screenshots", "/pictures/screenshots/")), standard)
        assertEquals(
            listOf("相机" to "DCIM/Camera", "下载" to "Pictures/Downloads"),
            missing,
        )
    }
}
