package com.phototidy.core.media

import java.util.concurrent.ConcurrentHashMap

/**
 * 「已整理」账本 —— 哪些照片用户已经做过决定了。
 *
 * 为什么必须有它：
 * 「保留」是这个应用里唯一**不改变照片库**的动作（另外两个是移动 / 回收站：提交后
 * 照片就离开原处了）。于是"整理过"这件事在照片库层面完全无痕 —— 用户滑了 400 张、
 * 退出再进来，那 400 张会原封不动地重新排队，看起来就像白干；首页的数字也永远是
 * 「这一范围一共有多少张」，而不是「还剩多少张」。
 *
 * 参考实现（Slidebox）没有这个问题，因为它根本没有「保留」：它的每个手势都必然
 * 把照片挪走或删掉，"未分类"因此可以由目录结构天然推导。我们不打算让「保留」
 * 偷偷搬动用户的照片，所以改成记一条账：照片原地不动，但**它不再出现在待整理队列里**。
 *
 * 三条设计约束：
 *  1. **按 photoId 记账，且全局共享**。范围是相互重叠的（10 月 ⊂ 最近），
 *     在「最近」里整理过的照片，进「10 月」时也必须是已整理的状态。
 *  2. **跨进程存活**，落在 [SessionStore] 的应用私有 SharedPreferences 里，不碰系统相册、
 *     不新增任何系统授权。
 *  3. **写盘节流**。每次滑动都全量重写一份 id 列表，在几万张的库上等于每秒往闪存里
 *     灌几百 KB。这里攒够 [WRITE_BATCH] 条或超过 [WRITE_INTERVAL_MS] 才落一次，
 *     并在提交 / 退出整理时显式 [flush]。最坏情况丢掉的只是「最后不到 20 张」的记录。
 *
 * 读写发生在不同线程（概览扫描跑在 IO，滑动回调在主线程），所以内部用并发集合，
 * 不做任何「只在主线程用」的假设。
 */
class ReviewedLedger(private val store: SessionStore) {

    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var loaded = false

    private var dirty = 0

    private var lastWriteAt = 0L

    /** 一张照片是否已经整理过。概览扫描会对每一行调用它，必须是廉价的。 */
    fun contains(id: Long): Boolean {
        ensureLoaded()
        return ids.contains(id)
    }

    /** 已整理的总张数（首页入口上的数字）。 */
    val size: Int
        get() {
            ensureLoaded()
            return ids.size
        }

    val isEmpty: Boolean get() = size == 0



    /** 记一条：用户对这张做过决定了。 */
    fun mark(id: Long) {
        ensureLoaded()
        if (ids.add(id)) maybePersist()
    }

    /** 撤销一条决定（撤销栈回退、或照片从系统回收站被还原回来）。 */
    fun unmark(id: Long) {
        ensureLoaded()
        if (ids.remove(id)) maybePersist()
    }

    fun unmark(ids: Collection<Long>) {
        ensureLoaded()
        if (ids.isEmpty()) return
        var changed = false
        ids.forEach { if (this.ids.remove(it)) changed = true }
        if (changed) maybePersist()
    }

    /**
     * 立即落盘。
     *
     * 调用时机：提交整理之后、退出整理页、还原回收站照片之后 ——
     * 这些是「用户已经认为事情办完了」的时刻，不能还欠着没写。
     */
    fun flush() {
        if (!loaded) return
        dirty = 0
        lastWriteAt = System.currentTimeMillis()
        store.saveReviewed(ids.toList())
    }

    private fun maybePersist() {
        dirty++
        val now = System.currentTimeMillis()
        if (dirty >= WRITE_BATCH || now - lastWriteAt >= WRITE_INTERVAL_MS) {
            flush()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            ids.addAll(store.loadReviewed())
            loaded = true
            lastWriteAt = System.currentTimeMillis()
        }
    }

    private companion object {
        /** 攒够这么多条记录就落一次盘。 */
        const val WRITE_BATCH = 20

        /** 或者距上次落盘超过这么久，也落一次。 */
        const val WRITE_INTERVAL_MS = 1_000L
    }
}
