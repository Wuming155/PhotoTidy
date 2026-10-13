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
 * 四条设计约束：
 *  1. **按 photoId 记账，且全局共享**。范围是相互重叠的（10 月 ⊂ 最近），
 *     在「最近」里整理过的照片，进「10 月」时也必须是已整理的状态。
 *  2. **跨进程存活**，落在 [SessionStore] 的应用私有 SharedPreferences 里，不碰系统相册、
 *     不新增任何系统授权。
 *  3. **写盘节流**。攒够 [WRITE_BATCH] 条或超过 [WRITE_INTERVAL_MS] 才落一次，
 *     并在提交 / 退出整理时显式 [flush]。最坏情况丢掉的只是「最后不到 20 张」的记录。
 *  4. **落盘走增量**。这一条是踩过坑才加上的：账本是全应用唯一会随用户操作**不断变大**
 *     的数据，而全量重写发生在滑动回调所在的线程上 —— 几万张的库上每落一次盘就是
 *     几十万次字符串拼接，总代价 O(N²)，而且全部转化成界面卡顿。
 *     现在只写「自上次落盘以来新增 / 取消的那些 id」，代价与批量有关、与总量无关。
 *
 * 线程约定：
 *  · [contains] / [size] 可能在 IO 线程被调用（概览扫描逐行走一遍），所以集合本身是并发的；
 *  · [mark] / [unmark] / [flush] 一律只在主线程调用（整理会话与库状态都在主线程），
 *     内部的增量批次因此用普通集合即可 —— 这条约定要改，必须同时给它们加锁。
 */
class ReviewedLedger(private val store: SessionStore) {

    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var loaded = false

    /** 自上次落盘以来**新增**的 id。 */
    private val pendingMarked = LinkedHashSet<Long>()

    /** 自上次落盘以来**取消**的 id。 */
    private val pendingUnmarked = LinkedHashSet<Long>()

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
        if (!ids.add(id)) return
        // 同一批里又标又撤：以最后的状态为准，不能把 id 同时留在两个增量集合里
        pendingUnmarked.remove(id)
        pendingMarked.add(id)
        maybePersist()
    }

    /** 撤销一条决定（撤销栈回退、或照片从系统回收站被还原回来）。 */
    fun unmark(id: Long) {
        ensureLoaded()
        if (!ids.remove(id)) return
        pendingMarked.remove(id)
        pendingUnmarked.add(id)
        maybePersist()
    }

    fun unmark(targets: Collection<Long>) {
        ensureLoaded()
        if (targets.isEmpty()) return
        var changed = false
        targets.forEach { id ->
            if (ids.remove(id)) {
                pendingMarked.remove(id)
                pendingUnmarked.add(id)
                changed = true
            }
        }
        if (changed) maybePersist()
    }

    /**
     * 立即落盘（只写增量）。
     *
     * 调用时机：提交整理之后、退出整理页、还原回收站照片之后 ——
     * 这些是「用户已经认为事情办完了」的时刻，不能还欠着没写。
     */
    fun flush() {
        if (!loaded) return
        dirty = 0
        lastWriteAt = System.currentTimeMillis()
        if (pendingMarked.isEmpty() && pendingUnmarked.isEmpty()) return
        val marked = pendingMarked.toList()
        val unmarked = pendingUnmarked.toList()
        // 先摘下来再写：写盘过程中若又发生 mark（正常不会，但不必依赖这一点），
        // 它会落进新的增量批次，而不是被这次写入顺手吞掉
        pendingMarked.clear()
        pendingUnmarked.clear()
        store.appendReviewed(marked, unmarked)
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
