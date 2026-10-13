package com.phototidy.core.media

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.phototidy.core.model.PendingMove
import org.json.JSONArray
import org.json.JSONObject

/**
 * 整理会话的落盘快照。
 *
 * 解决的是 README 里承认过的那条取舍：会话级提交把待办放在内存里，
 * 整理到一半应用被系统回收（或者用户被系统清后台），排好的队就全没了。
 *
 * 做成接口是为了让 [com.phototidy.ui.review.ReviewViewModel] 能在 JVM 单测里跑 ——
 * 测试里注入一个内存实现即可，不必构造 `Context` / `SharedPreferences`。
 *
 * 三条设计约束：
 *  1. **只存 id，不存照片**。排队状态需要的是「哪几张」和「挪到哪」，
 *     完整元数据在恢复时回 MediaStore 换回来即可 —— 期间已经消失的照片自然被剔除，
 *     不需要任何额外的一致性判定。于是快照体积与照片总数无关（只有几 KB）。
 *  2. **写的是应用私有 SharedPreferences**，不碰系统相册，也不新增任何系统授权。
 *     整理的「零打扰」语义没有被破坏：用户依然一次系统弹窗都看不到。
 *  3. **`apply()` 异步落盘**，所以每次滑动都写一份也不会阻塞 UI 线程。
 *     ⚠️ 这只覆盖**磁盘**那一半：序列化（拼 JSON）是同步的、发生在调用线程上 ——
 *     而「已整理」账本是全应用唯一会随用户操作不断变大的数据，
 *     所以它走的是增量接口，见 [SessionStore.appendReviewed]。
 *
 * 注意：这里保存的是**意图**（打算删哪些、打算挪到哪），不是事实。
 * 照片本身从头到尾没有被改动过 —— 应用被回收时丢掉的也只是「还没提交的打算」。
 */
interface SessionStore {
    fun saveStaging(imageIds: List<Long>)
    fun loadStaging(): List<Long>
    fun clearStaging()
    fun savePendingMoves(scopeKey: String, moves: List<PendingMove>)
    fun loadPendingMoves(scopeKey: String): List<PendingMove>
    fun clearPendingMoves(scopeKey: String)

    /**
     * 「已整理」账本。
     *
     * 与前两组不同，这里存的是**事实**而不是意图：这张照片用户已经做过决定
     * （保留 / 移动 / 回收站），下次不该再排进整理队列。
     *
     * 它必须跨进程存活 —— 「保留」是唯一不改变照片库的动作，一旦丢了这条账，
     * 用户整理过的那几百张会原封不动地重新出现，等于白干。
     *
     * ⚠️ 它是**增量**接口而不是「存一份全量列表」，原因是调用频率：整理过程里每滑几张
     * 就会落一次盘，全量写的代价随账本规模线性增长（几万张的库上就是每次几十万次
     * 字符串拼接，而且发生在滑动回调所在的线程上）。增量写的代价只与**这一批**有关。
     */
    fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>)

    fun loadReviewed(): List<Long>
}

/** 基于应用私有 `SharedPreferences` 的生产实现。 */
class RealSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("phototidy_session", Context.MODE_PRIVATE)

    // ----------------------------------------------------------- 待清除队列（全局）

    override fun saveStaging(imageIds: List<Long>) {
        prefs.edit { putString(KEY_STAGING, JSONArray(imageIds).toString()) }
    }

    override fun loadStaging(): List<Long> = readIdArray(prefs.getString(KEY_STAGING, null))

    override fun clearStaging() {
        prefs.edit { remove(KEY_STAGING) }
    }

    // ----------------------------------------------------------- 待移动表（按范围）

    override fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {
        val array = JSONArray()
        moves.forEach { move ->
            array.put(
                JSONObject().apply {
                    put("id", move.imageId)
                    put("bucket", move.bucketId)
                    put("name", move.albumName)
                    put("albumPath", move.albumPath)
                    put("targetPath", move.targetPath)
                },
            )
        }
        prefs.edit { putString(pendingKey(scopeKey), array.toString()) }
    }

    override fun loadPendingMoves(scopeKey: String): List<PendingMove> {
        val raw = prefs.getString(pendingKey(scopeKey), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", -1L)
                if (id < 0L) return@mapNotNull null
                PendingMove(
                    imageId = id,
                    targetPath = item.optString("targetPath"),
                    albumName = item.optString("name"),
                    bucketId = item.optLong("bucket", 0L),
                    albumPath = item.optString("albumPath"),
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun clearPendingMoves(scopeKey: String) {
        prefs.edit { remove(pendingKey(scopeKey)) }
    }

    // ----------------------------------------------------------- 已整理账本（全局）

    /**
     * 追加一批增量。
     *
     * ## 为什么按批次分键，而不是一个键存全量列表
     *
     * 全量写的代价是 O(账本规模)，而整理过程每滑几张就要落一次盘 ——
     * 在几万张的库上，这等于把一整份列表反复拼装、反复写，总代价是 O(N²)。
     * 分批次之后每次只写「这一批新增/取消的 id」：
     *
     *  · **写入**代价与批量大小成正比，与账本总量无关（这是这里最关键的一条）；
     *  · **读取**代价仍是 O(总量)，但只在冷启动读一次。
     *
     * ⚠️ 残留成本：`SharedPreferences.apply()` 每次都会重写整个 XML 文件，
     * 也就是**磁盘写入**仍是 O(文件大小) —— 这一步是异步的（不在调用线程上），
     * 所以它不再造成界面卡顿，但库特别大时有可观的 IO 量。
     * 要连这一半也去掉，得把账本挪出 SharedPreferences（追加式二进制日志文件 / Room 表），
     * 那是另一个量级的改造，先不做。
     */
    override fun appendReviewed(marked: Collection<Long>, unmarked: Collection<Long>) {
        if (marked.isEmpty() && unmarked.isEmpty()) return
        val next = prefs.getInt(KEY_REVIEWED_BATCHES, 0)
        prefs.edit {
            putString(reviewedBatchKey(next), encodeReviewedBatch(marked, unmarked))
            putInt(KEY_REVIEWED_BATCHES, next + 1)
        }
        // 批次数量必须封顶，否则键数会随整理次数无限增长（键越多，冷启动的解析越久）
        if (next + 1 >= REVIEWED_BATCH_LIMIT) compactReviewed(next + 1)
    }

    override fun loadReviewed(): List<Long> {
        val batches = prefs.getInt(KEY_REVIEWED_BATCHES, 0)
        if (batches <= 0) return emptyList()
        val out = LinkedHashSet<Long>()
        for (index in 0 until batches) {
            val raw = prefs.getString(reviewedBatchKey(index), null) ?: continue
            decodeReviewedBatch(raw, out)
        }
        return out.toList()
    }

    /**
     * 把 [batches] 个批次合并成一份快照。
     *
     * 这一步是 O(总量) 的，但每 [REVIEWED_BATCH_LIMIT] 次落盘才发生一次 ——
     * 摊到每次落盘头上仍是「与批量成正比」。
     */
    private fun compactReviewed(batches: Int) {
        val merged = loadReviewed()
        prefs.edit {
            putString(reviewedBatchKey(0), encodeReviewedBatch(merged, emptyList()))
            putInt(KEY_REVIEWED_BATCHES, 1)
            for (index in 1 until batches) remove(reviewedBatchKey(index))
        }
    }

    private fun reviewedBatchKey(index: Int) = "$PREFIX_REVIEWED$index"

    // ------------------------------------------------------------------ 内部

    private fun pendingKey(scopeKey: String) = "$PREFIX_PENDING$scopeKey"

    private fun readIdArray(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map(array::getLong)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_STAGING = "staging_ids"
        const val PREFIX_PENDING = "pending_moves:"

        /** 增量批次的键前缀与批次总数。用序号分键，写入代价才与批量、而不是总量挂钩。 */
        const val PREFIX_REVIEWED = "reviewed_batch_"
        const val KEY_REVIEWED_BATCHES = "reviewed_batches"

        /** 批次数上限；超过就把全部批次折叠成一份快照。 */
        const val REVIEWED_BATCH_LIMIT = 64
    }
}

/**
 * 一批增量的编码：**照片 id 恒为正数**，所以用符号区分「新增」与「取消」不会有歧义 ——
 *
 * ```
 * "12 13 -12"   = 新增 12、13，随后取消 12
 * ```
 *
 * 为什么不用 JSON（文件里另外两处用了）：这两个函数是账本里唯一「写错了就丢数据」的地方，
 * 而 `org.json` 在 JVM 单测里是桩实现（方法一律返回默认值），根本没法测。
 * 纯字符串 + 纯 Kotlin 解析就不一样了 —— 它能在 JVM 单测里被逐条钉死。
 */
internal fun encodeReviewedBatch(marked: Collection<Long>, unmarked: Collection<Long>): String =
    buildString {
        marked.forEach { if (isNotEmpty()) append(' '); append(it) }
        unmarked.forEach { if (isNotEmpty()) append(' '); append(-it) }
    }

/**
 * [encodeReviewedBatch] 的逆运算。非法 token 直接跳过：单批损坏不该让整个账本读不出来。
 *
 * 合法域是 `1..Long.MAX_VALUE`（见上方「照片 id 恒为正数」），两个分支都按区间判定：
 * `0` 与 `Long.MIN_VALUE` 会让「用符号表意」这个技巧失效（`-0L == 0L`，
 * `-Long.MIN_VALUE == Long.MIN_VALUE` 回绕），落到它们头上的 token 一律当作损坏丢弃。
 */
internal fun decodeReviewedBatch(raw: String, into: MutableSet<Long>) {
    if (raw.isBlank()) return
    raw.split(' ').forEach { token ->
        val value = token.toLongOrNull() ?: return@forEach
        when (value) {
            in 1L..Long.MAX_VALUE -> into.add(value)
            in -Long.MAX_VALUE..-1L -> into.remove(-value)
            // 越界：0 / Long.MIN_VALUE —— 编码器产不出它们，只可能来自损坏的存储
            else -> Unit
        }
    }
}
