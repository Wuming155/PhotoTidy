package com.phototidy.core.media

import com.phototidy.core.model.MediaImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 整理会话的「待清除队列」。
 *
 * 滑动「清除」只是把照片放进这里，**不碰系统相册** —— 这是整个应用最关键的安全设计。
 * 一直滑到用户点「提交」为止，系统相册不会有任何变化；那一次提交也只弹一次系统授权。
 *
 * 为什么不做成 ReviewViewModel 的字段？
 * 因为它必须比整理会话活得久：用户整理到一半切去相册看一眼、或者屏幕转个方向导致
 * ViewModel 重建，已经排进队列的照片不能因此丢掉。
 *
 * 队列只在内存里，应用重启即清空 —— 这是安全的，因为照片从头到尾没被改动过，
 * 清空的只是「用户打算删哪些」这个意图。
 *
 * 现在这份「意图」会同步落盘一份（见 [SessionStore]），所以应用被系统回收后
 * 冷启动能把它接回来；照片本体依旧从未被触碰过。
 */
class StagingTrash {

    private val _items = MutableStateFlow<List<MediaImage>>(emptyList())

    /** 当前排队待清除的照片。 */
    val items: StateFlow<List<MediaImage>> = _items.asStateFlow()

    /** 入队。已存在则忽略，避免撤销后重做导致的重复项。 */
    fun stage(image: MediaImage) {
        _items.update { current -> if (current.any { it.id == image.id }) current else current + image }
    }

    /** 撤销单张：出队。照片本身从未被改动过。 */
    fun unstage(imageId: Long) {
        _items.update { current -> current.filterNot { it.id == imageId } }
    }

    /** 批量出队（提交成功后清账）。 */
    fun remove(ids: Collection<Long>) {
        val set = ids.toHashSet()
        _items.update { current -> current.filterNot { it.id in set } }
    }

    /**
     * 冷启动恢复：把落盘的 id 换成照片后整队换入。
     *
     * 只在队列还是空的时候生效 —— 否则「用户在恢复完成前就已经滑了几张」会被这一下覆盖掉。
     */
    fun restore(items: List<MediaImage>) {
        if (items.isEmpty()) return
        _items.update { current -> if (current.isEmpty()) items else current }
    }
}
