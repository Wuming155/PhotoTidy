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
 * P4b：自持落盘 + `restored` 就绪门控。`StagingTrash` 是进程级全局队列，但此前从无任何地方
 * 建模它的归属 —— `ReviewViewModel.persist()` 把进程内 `staging.items` 无条件写回全局快照，
 * 若暂存的异步恢复尚未完成就会把磁盘快照覆写成空（进程被杀后直接恢复到整理页时无需用户操作
 * 即可触发，`imagesByIds` 对暂存列表会 `chunked(900)` 切多片）。现落盘收敛到此处，
 * 恢复前拒绝任何落盘；`ReviewViewModel` 彻底剥离 `saveStaging`（`persist()` 只剩
 * `savePendingMoves`）。所有读写都在主线程，无需加锁。
 */
class StagingTrash(
    private val sessionStore: SessionStore? = null,
) {

    private val _items = MutableStateFlow<List<MediaImage>>(emptyList())

    /** 当前排队待清除的照片。 */
    val items: StateFlow<List<MediaImage>> = _items.asStateFlow()

    /**
     * 恢复是否已完成（就绪门控）。
     *
     * `false` 期间 [persistStaging] 直接返回，不触碰磁盘 —— 这正是 P4b 要的「恢复前拒绝落盘」。
     * 无快照时由调用方显式 [markRestored] 开门；有快照时由 [restore] 开门；
     * 恢复失败时保持关闭并允许下次重试（见 `LibraryViewModel.restoreSessionOnce`）。
     */
    var restored: Boolean = false
        private set

    /** 无快照 / 快照为空（已清空）时显式开门，允许后续自持落盘。 */
    fun markRestored() {
        restored = true
    }

    private fun persistStaging() {
        if (!restored) return
        val store = sessionStore ?: return
        store.saveStaging(_items.value.map { it.id })
    }

    /** 入队。已存在则忽略，避免撤销后重做导致的重复项。 */
    fun stage(image: MediaImage) {
        var changed = false
        _items.update { current ->
            if (current.any { it.id == image.id }) current
            else {
                changed = true
                current + image
            }
        }
        if (changed) persistStaging()
    }

    /** 撤销单张：出队。照片本身从未被改动过。 */
    fun unstage(imageId: Long) {
        var changed = false
        _items.update { current ->
            val next = current.filterNot { it.id == imageId }
            changed = next.size != current.size
            next
        }
        if (changed) persistStaging()
    }

    /** 批量出队（提交成功后清账）。 */
    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val set = ids.toHashSet()
        var changed = false
        _items.update { current ->
            val next = current.filterNot { it.id in set }
            changed = next.size != current.size
            next
        }
        if (changed) persistStaging()
    }

    /**
     * 冷启动恢复：把落盘的 id 换成照片后整队换入。
     *
     * 只在队列还是空的时候生效 —— 否则「用户在恢复完成前就已经滑了几张」会被这一下覆盖掉。
     * 无论是否生效，恢复完成即开门（`restored = true`）；空恢复（`items.isEmpty()`）同样开门，
     * 否则后续自持落盘会被永久拦截。
     */
    fun restore(items: List<MediaImage>) {
        if (items.isEmpty()) {
            restored = true
            return
        }
        _items.update { current -> if (current.isEmpty()) items else current }
        restored = true
    }
}
