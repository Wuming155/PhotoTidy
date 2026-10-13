package com.phototidy.ui.review

import com.phototidy.core.model.MediaImage

/**
 * 整理会话的滑动窗口队列。
 *
 * 队列在逻辑上是一条拼接起来的长队：
 *
 * ```
 * [ ready ] [ 还没取到的 unloaded 张 ] [ deferred ]
 * ```
 *
 *  · **ready** —— 已经加载进内存、等着被消费的照片（上限一页多一点）；
 *  · **unloaded** —— 数据源里还没取的张数，只知道**有多少**、不知道是哪些；
 *  · **deferred** —— 用户点过「稍后再看」的照片，排在整条队列的最后。
 *
 * ## 为什么必须是一个类
 *
 * 这三段的**位置**与三段的**可取出性**是两件事，而它们曾经被写在两个地方：
 * 读取当前位置的 `at()` 会把最后一段（deferred）当成队首，消费队首的 `advance()`
 * 却只认第一段（ready）。两个出口口径不一致的后果不是「少一张」——是**死局**：
 * 屏幕上有照片，但 keep / trash / move 全部静默返回，进度也永远走不完。
 * 这类「两个出口对不上」的缺陷靠读代码很难发现（两边单看都对），
 * 所以把三段收进一个类、让 [peek] 与 [take] 挨着写，再用单测把口径钉死。
 *
 * ## 不变量
 *
 * 1. [peek] 返回非 null 的位置上，[take] 必须真的能取出照片来；
 * 2. 每张照片在 [remaining] 里只被计一次（它在 ready、unloaded、deferred 中恰好属于一段）。
 *
 * 纯内存、不依赖任何 Android 类型 —— 它能在 JVM 单测里直测。
 */
internal class ReviewQueueWindow(
    /** 这个范围**一共**多少张，分页上界。 */
    private val totalCount: Int,
) {

    /** 已加载、尚未消费的照片。 */
    private val ready = ArrayDeque<MediaImage>()

    /** 「稍后再看」的照片，排在整条队列的最后。 */
    private val deferred = ArrayDeque<MediaImage>()

    /** 已经从数据层取到第几张（不含 ready 里还没消费的）。 */
    private var loadedThrough = 0

    /** 还没取到的张数。 */
    private val unloaded: Int get() = (totalCount - loadedThrough).coerceAtLeast(0)

    /** 数据源是否已经取完（取不满一页即视为取完，见 [acceptPage]）。 */
    val sourceExhausted: Boolean get() = loadedThrough >= totalCount

    /** ready 里还剩几张 —— 补页的触发判据。 */
    val readyCount: Int get() = ready.size

    /** 已经从数据层取到第几张 —— 下一页请求的 offset。 */
    val loadedCount: Int get() = loadedThrough

    /** 用户眼里的剩余总量。 */
    val remaining: Int get() = ready.size + deferred.size + unloaded

    /**
     * 队列位置 [index] 上的照片。
     *
     * 返回 null 表示**这个位置上是一张还没取到的照片** —— 不是「没有照片了」，
     * 界面据此显示转圈而不是完成卡片（见 `ReviewUiState.preparing`）。
     */
    fun peek(index: Int): MediaImage? {
        if (index < 0) return null
        if (index < ready.size) return ready.elementAt(index)
        val tailStart = ready.size + unloaded
        if (index < tailStart) return null
        return deferred.elementAtOrNull(index - tailStart)
    }

    /**
     * 取出队首并消费掉它。
     *
     * ⚠️ 口径必须与 [peek] 一致：`peek` 会把 deferred 里的照片当成队首时，
     * 这里就必须真的能把它取出来。[ready] 空了但数据源还没取完时返回 null ——
     * 那一刻队首是一张「还没到」的照片，谁都取不出来，这是正常的转圈态而不是死局。
     */
    fun take(): MediaImage? {
        ready.removeFirstOrNull()?.let { return it }
        if (unloaded > 0) return null
        return deferred.removeFirstOrNull()
    }

    /** 撤销 / 会话内还原：把照片放回队首，用户立刻能重新决定它。 */
    fun pushFront(image: MediaImage) = ready.addFirst(image)

    /** 当前这张「稍后再看」：从队首取出，放到整条队列的最后。 */
    fun deferCurrent(): MediaImage? {
        val image = take() ?: return null
        deferred.addLast(image)
        return image
    }

    /**
     * 收下一页。
     *
     * [keep] 决定哪些照片真的进队列 —— 已经整理过的照片会被滤掉，
     * 但它们**仍然算作已取到**。这是「翻页水位」与「队列内容」必须分开的原因：
     * 把滤掉的照片也从水位里扣掉，下一次请求就会从同一个 offset 再取一遍，永远翻不过去。
     *
     * 取不满一页说明数据源到头了（或权限范围缩小了），直接把水位推到顶，别再反复请求。
     */
    fun acceptPage(page: List<MediaImage>, pageSize: Int, keep: (MediaImage) -> Boolean) {
        loadedThrough = if (page.size < pageSize) totalCount else loadedThrough + page.size
        page.forEach { if (keep(it)) ready.addLast(it) }
    }

    /** ready 见底、数据源却还有内容 —— 该补页了。 */
    fun needsRefill(refillBelow: Int): Boolean = !sourceExhausted && ready.size < refillBelow

    fun clear() {
        ready.clear()
        deferred.clear()
        loadedThrough = 0
    }
}
