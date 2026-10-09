package com.phototidy.core.media

import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.PendingMove

/**
 * [com.phototidy.ui.review.ReviewViewModel] 需要的媒体仓库子集。
 *
 * 抽成接口是为了让整理会话的纯逻辑（撤销栈、滑动窗口、增量计数、提交记账）能在 JVM 单测里跑：
 * 真正碰 `MediaStore` 的只有三个方法，测试里用一个假实现返回预设结果即可，
 * 不必构造 `Context`。
 */
interface MediaRepository {
    suspend fun moveToAlbums(items: List<PendingMove>): MediaOpResult

    suspend fun moveToSystemTrash(imageIds: List<Long>, attempt: Int = 0): MediaOpResult

    suspend fun imagesByIds(ids: Collection<Long>): List<MediaImage>
}
