package com.phototidy.core.media

import com.phototidy.core.model.MediaImage
import org.junit.Assert.assertEquals
import org.junit.Test

class StagingTrashTest {

    private fun img(id: Long) = MediaImage(
        id = id, uri = "content://x/$id", displayName = "n$id",
        takenAtMillis = 0, addedAtMillis = 0, bucketId = 1, albumName = "A",
        relativePath = "DCIM/A", sizeBytes = 1, width = 1, height = 1,
        isFavorite = false, isTrashed = false,
    )

    @Test
    fun `stage dedups and unstage removes`() {
        val trash = StagingTrash()
        val a = img(1)
        trash.stage(a)
        trash.stage(a) // 重复入队应被忽略
        assertEquals(1, trash.items.value.size)

        trash.unstage(1)
        assertEquals(0, trash.items.value.size)
    }

    @Test
    fun `remove batches out`() {
        val trash = StagingTrash()
        trash.stage(img(1))
        trash.stage(img(2))
        trash.remove(listOf(1L))
        assertEquals(1, trash.items.value.size)
        assertEquals(2L, trash.items.value.first().id)
    }

    @Test
    fun `restore only when empty`() {
        val trash = StagingTrash()
        trash.stage(img(1))
        // 队列非空时 restore 不应覆盖用户已经排好的队
        trash.restore(listOf(img(2)))
        assertEquals(1, trash.items.value.size)
        assertEquals(1L, trash.items.value.first().id)
    }
}
