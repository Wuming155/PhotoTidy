package com.phototidy.core.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.phototidy.R
import com.phototidy.core.StringProvider
import com.phototidy.core.model.MediaAlbum
import com.phototidy.core.model.MediaImage
import com.phototidy.core.model.MediaScope
import com.phototidy.core.model.MonthGroup
import com.phototidy.core.model.PendingMove
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 照片库的聚合概览。
 *
 * ⚠️ 这里**没有**「全部照片」这个字段，是刻意的。
 *
 * 早期版本把每张照片物化成 `MediaImage` 再在 Kotlin 侧聚合相册/月份。
 * 单张 MediaImage 带一个 `Uri` 对象和三个 `String`，算上对象头实际约 **400 字节**，
 * 而不是注释里曾写的 200 字节 —— 12.5 万张（约 500GB 照片）就是 ~50MB 常驻堆，
 * 冷启动还要为此分配 12.5 万个对象。
 *
 * 现在改成：**一次游标扫描只累积计数与封面**，内存占用与照片总数解耦 ——
 * 只在每个相册/月份的第一行（也就是最新那张）物化一个 MediaImage 当封面。
 * 125000 张照片同样是几百个对象的开销。
 */
data class LibraryOverview(
    val totalCount: Int,
    val totalBytes: Long,
    /** 最新的一张，首页封面用。 */
    val newest: MediaImage?,
    val albums: List<MediaAlbum>,
    val months: List<MonthGroup>,
) {
    val isEmpty: Boolean get() = totalCount == 0

    /**
     * 还没整理过的总张数。
     *
     * 由各月份聚合而来，而不是单独扫一遍 —— 全局待整理数恒等于所有月份待整理数之和
     * （每张照片恰好落在一个月里），再扫一次纯属浪费。
     */
    val remainingCount: Int get() = months.sumOf { it.remaining }

    companion object {
        val Empty = LibraryOverview(0, 0L, null, emptyList(), emptyList())
    }
}

enum class ConsentKind { Write, Trash, Delete }

data class MoveFailure(
    val uri: Uri? = null,
    val imageId: Long,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface MediaOpResult {
    /** 已直接完成，无需用户介入。 */
    data object Success : MediaOpResult

    /**
     * 目标媒体不属于本应用，系统要求用户明确授权。
     *
     * 把 [intentSender] 交给 `ActivityResultContracts.StartIntentSenderForResult` 启动，
     * 拿到 RESULT_OK 后**原样重试同一个操作**即可 —— 本仓库所有写操作都是幂等的。
     */
    data class NeedsConsent(val intentSender: IntentSender, val kind: ConsentKind) : MediaOpResult

    data class Failed(val message: String) : MediaOpResult

    /** 批量操作结果：明确告知哪些成功、哪些失败，避免单个失败项造成全局卡死。 */
    data class Batch(
        val successfulIds: Set<Long>,
        val failedItems: Map<Long, MoveFailure>,
    ) : MediaOpResult
}

/**
 * MediaStore 数据访问层。
 *
 * 设计原则：
 *  1) 所有方法都是 suspend + Dispatchers.IO，调用方不需要关心线程。
 *  2) **读操作一律走游标分页**，任何方法都不会一次物化整个照片库。
 *  3) 所有写操作走同一条路径：「先直写，被拒再要授权」。
 *     自家创建的媒体可以直接改；别人的媒体会抛 SecurityException，
 *     此时改用 `MediaStore.createXxxRequest()` 拉起系统授权框，用户同意后重试。
 *  4) 不做乐观更新。写失败必须回到 UI，让用户明确知道照片没被改动。
 *
 * 失败文案走 [StringProvider]：`core/` 里不留任何展示字面量，
 * 文案一律落在 `strings.xml`。
 */
class MediaStoreRepository(
    private val context: Context,
    private val strings: StringProvider,
) : MediaRepository {

    private val resolver: ContentResolver = context.contentResolver
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun uriFor(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    // ------------------------------------------------------------------ 读取

    /**
     * 扫描整个可见照片库，产出聚合概览。**不保留逐张记录。**
     *
     * 为什么不在 SQL 里 GROUP BY？
     * `group_by` 需要拼原始 SQL 片段，各厂商 MediaProvider 行为不一致（这个坑本项目已经踩过一次）。
     * 这里改为「游标流式遍历 + Kotlin 侧累加」：只留计数和每组封面，
     * 既避开了厂商差异，也拿到了分页的内存收益。
     *
     * 游标按拍摄时间倒序，所以**每个分组第一次出现的那一行就是该组最新的照片**，直接当封面。
     *
     * [isReviewed] 是「已整理」账本。顺手在同一个循环里算出每个相册 / 每个月份的**待整理**张数：
     * 再扫一遍全库只为数出「还剩多少」，在 12.5 万张的库上要多花一次完整扫描的时间，不值。
     */
    suspend fun loadOverview(isReviewed: (Long) -> Boolean = { false }): LibraryOverview =
        withContext(Dispatchers.IO) {
        val albums = HashMap<Long, AlbumAcc>()
        val months = HashMap<Int, MonthAcc>()
        val zone = ZoneId.systemDefault()
        var total = 0
        var bytes = 0L
        var newest: MediaImage? = null

        // 月份窗口缓存。游标按拍摄时间倒序，绝大多数行都落在上一次算好的月份区间里，
        // 只有跨月（以及 DATE_TAKEN 缺失、退化用 DATE_ADDED 的乱序段）才需要重新做时区换算。
        // 不缓存的话，每一行都要分配 Instant + ZonedDateTime —— 12.5 万张就是几十 MB
        // 的瞬时垃圾，全砸在冷启动最慢的这条扫描路径上。
        // 窗口由 [monthWindowAt] 产出，边界口径与 [MediaScope.filter] 里 Month 条件的
        // SQL 完全一致，行为不变。
        var window: MonthWindow? = null

        openCursor(selection = VISIBLE, onlyTrashed = false)?.use { c ->
            val cols = Cols(c)
            while (c.moveToNext()) {
                val taken = c.takenAt(cols)
                val bucketId = c.getLong(cols.bucket)
                val size = c.getLong(cols.size)

                total++
                bytes += size
                if (newest == null) newest = c.toImage(cols)

                // 命中判定按值校验区间而非假设游标单调：即便日期口径分叉导致乱序，
                // 也只是多算几次窗口，不会把照片分错月份
                val w = window?.takeIf { taken >= it.startMillis && taken < it.endMillisExclusive }
                    ?: monthWindowAt(taken, zone).also { window = it }
                val month = months.getOrPut(w.key) {
                    MonthAcc(year = w.year, month = w.month, cover = c.toImage(cols))
                }
                month.count++

                val album = albums.getOrPut(bucketId) {
                    // RELATIVE_PATH 只有相册的第一行才需要（做名字兜底）——
                    // 放在循环体里读的话，每一行都要为一个用不上的值分配一个字符串
                    val path = c.getString(cols.path).orEmpty()
                    AlbumAcc(
                        name = c.albumName(cols).ifBlank { path.trim('/').substringAfterLast('/') },
                        path = path,
                        cover = c.toImage(cols),
                    )
                }
                album.count++

                if (!isReviewed(c.getLong(cols.id))) {
                    month.remaining++
                    album.remaining++
                }
            }
        }

        LibraryOverview(
            totalCount = total,
            totalBytes = bytes,
            newest = newest,
            albums = albums.map { (bucketId, acc) ->
                MediaAlbum(
                    bucketId = bucketId,
                    name = acc.name.ifBlank { "Pictures" },
                    relativePath = acc.path,
                    count = acc.count,
                    cover = acc.cover,
                    remaining = acc.remaining,
                )
            }
                // 用「最新一张照片的时间」排序：刚拍完的相册就该排在最前面
                .sortedByDescending { it.cover?.takenAtMillis ?: 0L },
            months = months.values
                .map {
                    MonthGroup(
                        year = it.year,
                        month = it.month,
                        count = it.count,
                        cover = it.cover,
                        remaining = it.remaining,
                    )
                }
                .sortedWith(compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month }),
        )
    }

    /**
     * 截图范围的统计：一共多少张、其中还没整理的有多少张。
     *
     * 为什么不塞进 [loadOverview] 的循环里顺手算：
     * 那里刻意避免逐行 `getString(RELATIVE_PATH)`（12.5 万行就是 12.5 万次字符串分配，
     * 全砸在冷启动最慢的那条扫描路径上）。改成一条带 LIKE 的**独立查询**、只取 `_ID` 一列，
     * 匹配到的行数（截图通常几百张）与照片库规模无关。
     *
     * 与 `MediaScope.Screenshots` 的 SQL 条件共用同一组常量，口径不可能分叉。
     */
    suspend fun loadScreenshotStats(
        isReviewed: (Long) -> Boolean = { false },
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var total = 0
        var remaining = 0
        openCursor(
            selection = "$VISIBLE AND $SCREENSHOT_PATH_CLAUSE",
            selectionArgs = SCREENSHOT_PATH_ARGS,
            onlyTrashed = false,
            onlyIdColumn = true,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (c.moveToNext()) {
                total++
                if (!isReviewed(c.getLong(idCol))) remaining++
            }
        }
        total to remaining
    }

    /**
     * 流式找出**文件大小相同**的照片组，供重复检测使用。
     *
     * 按 SIZE 排序后，同大小照片在游标中相邻，只需暂存当前一组；[maxCandidateCount]
     * 同时限制返回的照片总数，因此大候选组和全库唯一大小集合都不会被整体物化。
     * Finder 的哈希预算也是同一个上限，避免候选列表大于实际可处理量。
     */
    suspend fun findDuplicateCandidates(maxCandidateCount: Int): Map<Long, List<Long>> =
        withContext(Dispatchers.IO) {
            if (maxCandidateCount < 2) return@withContext emptyMap()

            val groups = LinkedHashMap<Long, List<Long>>()
            var collected = 0
            var currentSize: Long? = null
            var firstId = 0L
            var currentGroup: ArrayList<Long>? = null

            openCursor(
                selection = VISIBLE,
                onlyTrashed = false,
                projection = ID_SIZE,
                sortOrder = SIZE_CANDIDATE_SORT_ORDER,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val size = c.getLong(sizeCol)
                    if (size <= 0L) continue
                    val id = c.getLong(idCol)

                    if (currentSize == null || size != currentSize) {
                        val previousGroup = currentGroup
                        if (previousGroup != null) {
                            groups[currentSize!!] = previousGroup
                            collected += previousGroup.size
                            if (collected >= maxCandidateCount) break
                        }
                        currentGroup = null
                        if (maxCandidateCount - collected < 2) break
                        currentSize = size
                        firstId = id
                        continue
                    }

                    val remaining = maxCandidateCount - collected
                    // 至少两个同尺寸照片才有资格成为候选组。
                    if (remaining < 2) break

                    val group = currentGroup
                    if (group == null) {
                        // 唯一尺寸只保留一个原始 ID，不为每行创建 List 或 MediaImage。
                        currentGroup = arrayListOf(firstId, id)
                    } else if (group.size < remaining) {
                        group.add(id)
                    } else {
                        // 当前同尺寸组已经填满剩余预算；不再收集超预算的 ID。
                        groups[size] = group
                        collected += group.size
                        break
                    }
                }
            }

            currentGroup?.let { group ->
                if (collected < maxCandidateCount) groups[currentSize!!] = group
            }
            groups
        }

    /**
     * 取某一范围内的一页照片。
     *
     * 分页落在 **SQL 侧**（`QUERY_ARG_LIMIT` / `QUERY_ARG_OFFSET`，API 30+ 由 MediaProvider 直接支持），
     * 所以「翻到第 80000 张」不会先把前 79999 张读出来再丢掉。
     */
    suspend fun pageImages(scope: MediaScope, offset: Int, limit: Int): List<MediaImage> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            val (selection, args) = scope.filter()
            val out = ArrayList<MediaImage>(limit)
            openCursor(
                selection = selection,
                selectionArgs = args,
                onlyTrashed = false,
                limit = limit,
                offset = offset,
            )?.use { c ->
                val cols = Cols(c)
                while (c.moveToNext()) out += c.toImage(cols)
            }
            out
        }

    /**
     * 按 id 批量取回照片。用于**会话恢复**：
     * 落盘的只有 id，冷启动时再回 MediaStore 换回完整元数据 ——
     * 期间已经被删掉的照片自然不会被带回来，不需要额外的失效判定。
     */
    override suspend fun imagesByIds(ids: Collection<Long>): List<MediaImage> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val out = ArrayList<MediaImage>(ids.size)
        // SQLite 的变量上限是 999，分批查询
        ids.chunked(900).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            openCursor(
                selection = "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                selectionArgs = chunk.map { it.toString() }.toTypedArray(),
                onlyTrashed = null,
            )?.use { c ->
                val cols = Cols(c)
                while (c.moveToNext()) out += c.toImage(cols)
            }
        }
        out
    }

    /** 系统回收站里有多少张。只读 `_ID` 一列，不物化任何 MediaImage。 */
    suspend fun countTrashed(): Int = withContext(Dispatchers.IO) {
        var count = 0
        openCursor(selection = null, onlyTrashed = true, onlyIdColumn = true)?.use { c ->
            count = c.count
        }
        count
    }

    /** 系统回收站的一页。 */
    suspend fun pageTrashed(offset: Int, limit: Int): List<MediaImage> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val out = ArrayList<MediaImage>(limit)
        openCursor(selection = null, onlyTrashed = true, limit = limit, offset = offset)?.use { c ->
            val cols = Cols(c)
            while (c.moveToNext()) out += c.toImage(cols)
        }
        out
    }

    // ------------------------------------------------------------------ 写操作

    /**
     * 批量移动到各自的相册 —— 一次调用、一次系统授权。
     *
     * 每张照片可以有不同的目标：一次整理会话里用户可能把照片分投到好几个相册，
     * 如果按相册分组多次调用，就会弹出好几次系统授权框。
     *
     * 实现方式是改写 `RELATIVE_PATH` —— 分区存储下这是唯一能让「其他相册应用也看得见」的移动方式，
     * 直接操作文件路径从 Android 11 起对共享目录已不可行。
     */
    override suspend fun moveToAlbums(items: List<PendingMove>): MediaOpResult =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext MediaOpResult.Success
            val denied = ArrayList<Uri>()
            val successfulIds = LinkedHashSet<Long>()
            val failedItems = LinkedHashMap<Long, MoveFailure>()

            items.forEach { move ->
                val uri = uriFor(move.imageId)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, move.targetPath)
                }
                try {
                    val rows = resolver.update(uri, values, null, null)
                    if (rows > 0) {
                        successfulIds.add(move.imageId)
                    } else {
                        denied += uri
                    }
                } catch (e: SecurityException) {
                    denied += uri
                } catch (e: Exception) {
                    Log.w(TAG, "update failed for image ${move.imageId} to ${move.targetPath}", e)
                    failedItems[move.imageId] = MoveFailure(
                        uri = uri,
                        imageId = move.imageId,
                        message = e.message ?: strings.get(R.string.media_op_failed),
                        cause = e,
                    )
                }
            }

            when {
                denied.isNotEmpty() -> runCatching {
                    MediaOpResult.NeedsConsent(
                        MediaStore.createWriteRequest(resolver, denied).intentSender,
                        ConsentKind.Write,
                    )
                }.getOrElse {
                    Log.w(TAG, "consent request failed", it)
                    MediaOpResult.Failed(strings.get(R.string.media_op_consent_request_failed))
                }

                failedItems.isNotEmpty() -> MediaOpResult.Batch(successfulIds, failedItems)
                else -> MediaOpResult.Success
            }
        }


    /**
     * 移入系统回收站（在系统相册里可见，30 天后自动清空）。
     *
     * ⚠️ 这里**不走**「先直写、被拒再授权」那套流程。
     * `MediaStore.createTrashRequest` 的语义是**授权即执行** ——
     * 用户在系统框上点「允许」的那一下，系统就已经把媒体移进回收站了，
     * 应用不需要、也不应该再自己 update 一次。
     *
     * 实机踩到的坑：授权通过后再去 `update(IS_TRASHED = 1)` 会**静默返回 0 行**，
     * 于是「系统明明已经做完了」被误判成「没做成」，还会白弹第二次框。
     *
     * [attempt] > 0 表示用户已经同意过 —— 系统已完成，直接算成功。
     */
    override suspend fun moveToSystemTrash(imageIds: List<Long>, attempt: Int): MediaOpResult {
        if (imageIds.isEmpty()) return MediaOpResult.Success
        if (attempt > 0) return MediaOpResult.Success
        return withContext(Dispatchers.IO) {
            runCatching {
                MediaOpResult.NeedsConsent(
                    intentSender = MediaStore.createTrashRequest(
                        resolver,
                        imageIds.map(::uriFor),
                        true,
                    ).intentSender,
                    kind = ConsentKind.Trash,
                )
            }.getOrElse {
                Log.w(TAG, "trash request failed", it)
                MediaOpResult.Failed(strings.get(R.string.media_op_trash_request_failed))
            }
        }
    }

    /**
     * 从系统回收站还原。
     *
     * ⚠️ 与 [moveToSystemTrash] 严格对称 —— **不能**走「先直写、被拒再要写授权」那条路。
     *
     * `IS_TRASHED` 不是普通可写列：`update()` 改它会**静默返回 0 行**且不抛 SecurityException
     * （见 [updateAll] 的实机结论），写权限（`createWriteRequest`）也不解锁这一列。
     * 唯一能翻它的 API 是 `MediaStore.createTrashRequest`，其 `value` 参数就是
     * 「要写入的 IS_TRASHED 值」—— 进回收站传 true，还原传 false。
     *
     * 这里曾经直写 `IS_TRASHED = 0`、被拒后弹 `createWriteRequest` 再重放，于是：
     * 直写 0 行 → 判为「被拒」→ 弹写权限框 → 用户点允许 → 重放仍然 0 行 →
     * 授权次数用尽 → 报「系统未放行」。回收站里的照片因此永远出不来。
     *
     * [attempt] > 0 表示用户已经同意过 —— 同 [moveToSystemTrash]：授权即执行，
     * 系统已经改完了，再 update 一次反而会静默 0 行被误判成失败。
     */
    suspend fun restoreFromSystemTrash(imageIds: List<Long>, attempt: Int): MediaOpResult {
        if (imageIds.isEmpty()) return MediaOpResult.Success
        if (attempt > 0) return MediaOpResult.Success
        return withContext(Dispatchers.IO) {
            runCatching {
                MediaOpResult.NeedsConsent(
                    intentSender = MediaStore.createTrashRequest(
                        resolver,
                        imageIds.map(::uriFor),
                        false,
                    ).intentSender,
                    kind = ConsentKind.Trash,
                )
            }.getOrElse {
                Log.w(TAG, "restore request failed", it)
                MediaOpResult.Failed(strings.get(R.string.media_op_trash_request_failed))
            }
        }
    }

    /**
     * 彻底删除。
     *
     * 这一步没有「直删」的快路径 —— 即使媒体是本应用创建的，也统一交给系统确认对话框。
     * 理由：这是不可逆操作，多一次系统级确认换来的心理安全边际完全值得。
     */
    suspend fun requestPermanentDelete(images: List<MediaImage>, attempt: Int = 0): MediaOpResult {
        if (images.isEmpty()) return MediaOpResult.Success
        // 同 moveToSystemTrash：createDeleteRequest 也是「授权即执行」，系统删完就完事
        if (attempt > 0) return MediaOpResult.Success
        return withContext(Dispatchers.IO) {
            runCatching {
                MediaOpResult.NeedsConsent(
                    intentSender = MediaStore.createDeleteRequest(
                        resolver,
                        images.map { uriFor(it.id) },
                    ).intentSender,
                    kind = ConsentKind.Delete,
                )
            }.getOrElse {
                Log.w(TAG, "delete request failed", it)
                MediaOpResult.Failed(strings.get(R.string.media_op_delete_request_failed))
            }
        }
    }

    // ------------------------------------------------------------------ 查询构造

    /**
     * [MediaScope] → SQL 过滤条件。
     *
     * 日期过滤用 `COALESCE(NULLIF(date_taken, 0), date_added * 1000)`：
     * 系统并不保证每张照片都写了 `DATE_TAKEN`（截图、第三方应用存的图常常没有），
     * 缺失时退化用入库时间 —— 这与展示层 `MediaImage.takenAtMillis` 的取值口径必须完全一致，
     * 否则「按月份整理」会漏照片。
     *
     * ⚠️ 月份区间的两个参数**必须** `CAST(? AS INTEGER)`。
     * `ContentResolver.query` 的 `selectionArgs` 是 `String[]`，SQLite 侧一律以 **TEXT**
     * 存储类绑定。而区间左端是 `COALESCE(...)` 表达式，**没有列亲和性**：
     * 两个操作数都没有亲和性时 SQLite 按存储类比较，而它的次序是
     * `NULL < INTEGER/REAL < TEXT < BLOB` —— 于是一整列 INTEGER 毫秒值永远 `>= '1790784000000'`
     * 为假，**月份范围永远匹配 0 行**（相册条件不受影响：`BUCKET_ID` 是有 INTEGER 亲和性的列，
     * TEXT 参数会被转成数字）。
     * 显式 `CAST` 把参数落成 INTEGER 值，比较才是数值比较。
     */
    private fun MediaScope.filter(): Pair<String, Array<String>?> = when (this) {
        MediaScope.Recent -> VISIBLE to null

        is MediaScope.Album ->
            "$VISIBLE AND ${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId.toString())

        // 截图：按 RELATIVE_PATH 前缀匹配，不按桶显示名（见 MediaScope.Screenshots 的注释）。
        // 两个「兜底」列出的 ROM 差异：
        //  · AOSP / 多数 ROM 落 `Pictures/Screenshots`；
        //  · 少数 ROM（含部分国产定制）把截图丢进 `DCIM/Screenshots`。
        // SQLite 的 LIKE 对 ASCII 默认大小写不敏感，`pictures/screenshots` 这类写法也能命中。
        MediaScope.Screenshots ->
            "$VISIBLE AND $SCREENSHOT_PATH_CLAUSE" to SCREENSHOT_PATH_ARGS

        is MediaScope.Month -> {
            val zone = ZoneId.systemDefault()
            val start = YearMonth.of(year, month).atDay(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
            val end = YearMonth.of(year, month).plusMonths(1).atDay(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
            "$VISIBLE AND $EFFECTIVE_DATE >= CAST(? AS INTEGER) AND $EFFECTIVE_DATE < CAST(? AS INTEGER)" to
                arrayOf(start.toString(), end.toString())
        }
    }

    /**
     * 统一的游标入口。
     *
     * [onlyTrashed] 三态：true = 只看回收站（`MATCH_ONLY`），false = 排除回收站（`MATCH_EXCLUDE`），
     * **null = 不传这个查询参数**，把行为交回平台默认。
     *
     * ⚠️ 别把 null 读成「两者都要」——「两者都要」必须显式传 `MATCH_INCLUDE`。
     * 目前只有 [imagesByIds] 传 null，而它的调用方（会话恢复、待移动对账、重复检测）
     * 关心的都只是**没被回收**的照片，所以行为是对的；但下一个来这里加调用的人必须知道这一点。
     *
     * ⚠️ 另一个坑：回收站必须通过查询参数来要，不能只写在 selection 里。
     * MediaProvider 默认对查询**整体排除** is_trashed=1 的项，
     * 实测「selection 写 IS_TRASHED != 0」永远返回空 —— 于是应用内的
     * 「系统回收站」一栏看起来永远是空的，哪怕照片确实已经在里面。
     */
    private fun openCursor(
        selection: String?,
        selectionArgs: Array<String>? = null,
        onlyTrashed: Boolean? = false,
        limit: Int? = null,
        offset: Int? = null,
        onlyIdColumn: Boolean = false,
        /** 自定义投影（例如只取 `_ID` + `SIZE`）；缺省用完整 [PROJECTION]。 */
        projection: Array<String>? = null,
        sortOrder: String = SORT_ORDER,
    ): Cursor? {
        val args = Bundle().apply {
            if (onlyTrashed != null) {
                putInt(
                    MediaStore.QUERY_ARG_MATCH_TRASHED,
                    if (onlyTrashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE,
                )
            }
            if (selection != null) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            }
            if (!selectionArgs.isNullOrEmpty()) {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (offset != null) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        val columns = when {
            projection != null -> projection
            onlyIdColumn -> ID_ONLY
            else -> PROJECTION
        }
        return resolver.query(collection, columns, args, null)
    }

    // ------------------------------------------------------------------ 内部

    /** 投影列下标，循环外算一次。 */
    private class Cols(c: Cursor) {
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val favorite = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
        val trashed = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
        val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
        val bucketName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
    }

    /** 拍摄时间（毫秒）：系统可能没写 DATE_TAKEN，退化用入库时间。 */
    private fun Cursor.takenAt(c: Cols): Long {
        val taken = getLong(c.taken)
        return if (taken > 0L) taken else getLong(c.added) * 1000L
    }

    private fun Cursor.albumName(c: Cols): String =
        getString(c.bucketName)?.takeIf { it.isNotBlank() }
            ?: getString(c.path).orEmpty().trimEnd('/').substringAfterLast('/', "")

    private fun Cursor.toImage(c: Cols): MediaImage {
        val id = getLong(c.id)
        return MediaImage(
            id = id,
            uri = ContentUris.withAppendedId(collection, id).toString(),
            displayName = getString(c.name).orEmpty(),
            takenAtMillis = takenAt(c),
            addedAtMillis = getLong(c.added) * 1000L,
            bucketId = getLong(c.bucket),
            albumName = albumName(c),
            relativePath = getString(c.path).orEmpty(),
            sizeBytes = getLong(c.size),
            width = getInt(c.width),
            height = getInt(c.height),
            isFavorite = getInt(c.favorite) == 1,
            isTrashed = getInt(c.trashed) == 1,
        )
    }

    private class AlbumAcc(val name: String, val path: String, val cover: MediaImage) {
        var count: Int = 0

        /** 其中还没整理过的张数。 */
        var remaining: Int = 0
    }

    private class MonthAcc(val year: Int, val month: Int, val cover: MediaImage) {
        var count: Int = 0

        /** 其中还没整理过的张数。 */
        var remaining: Int = 0
    }



    private companion object {
        const val TAG = "MediaStoreRepository"

        const val VISIBLE =
            "COALESCE(${MediaStore.MediaColumns.IS_PENDING}, 0) = 0 AND " +
                "${MediaStore.MediaColumns.SIZE} > 0"

        /** 展示层口径的真·拍摄时间；见 `MediaScope.filter()` 的注释。 */
        const val EFFECTIVE_DATE =
            "COALESCE(NULLIF(${MediaStore.MediaColumns.DATE_TAKEN}, 0), " +
                "${MediaStore.MediaColumns.DATE_ADDED} * 1000)"

        /**
         * 截图目录判据；与 [isScreenshotPath] 的语义必须完全一致（精确匹配 + `/%` 前缀）。
         *
         * 务必用双 `LIKE ?`，不要用 `=` / `IN` —— 它们走 BINARY 排序规则，会丢掉现注释
         * 所依赖的 ASCII 大小写不敏感（见 `MediaScope.Screenshots` 的注释）。
         * `LIKE '.../%'` 既覆盖目录内文件，也能排除 `ScreenshotsBackup/` 这类前缀污染。
         */
        const val SCREENSHOT_PATH_CLAUSE =
            "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"

        val SCREENSHOT_PATH_ARGS = arrayOf(
            // 兼容无尾斜杠的写入（大小写不敏感，LIKE 对 ASCII 默认不敏感）
            SCREENSHOT_PATH_PICTURES,
            // 常见形态：目录内文件 / 子目录
            "$SCREENSHOT_PATH_PICTURES/%",
            SCREENSHOT_PATH_DCIM,
            "$SCREENSHOT_PATH_DCIM/%",
        )

        val ID_ONLY = arrayOf(MediaStore.MediaColumns._ID)

        /** 重复检测的粗筛只需要这两列：不物化任何照片对象。 */
        val ID_SIZE = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)

        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )

        const val SORT_ORDER =
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC," +
                " ${MediaStore.MediaColumns.DATE_ADDED} DESC," +
                " ${MediaStore.MediaColumns._ID} DESC"

        /** 按大小流式分组，并在组内沿用照片的默认新旧顺序。 */
        const val SIZE_CANDIDATE_SORT_ORDER =
            "${MediaStore.MediaColumns.SIZE} ASC, ${MediaStore.MediaColumns.DATE_TAKEN} DESC," +
                " ${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
    }
}

/**
 * 某个毫秒时刻所属的「本地日历月」窗口。
 *
 * [startMillis] / [endMillisExclusive] 的口径必须与 [MediaScope.filter] 里 Month 条件的
 * SQL 边界（`EFFECTIVE_DATE >= start AND < end`，两端都用 `atStartOfDay(zone)`）完全一致，
 * 否则「按月整理」会漏照片。抽成纯函数是为了让跨月 / 跨年 / DST / 时间戳为 0 这些
 * 边界能在 JVM 单测里直测 —— 这是冷启动扫描里唯一「正确性敏感」的换算。
 */
internal data class MonthWindow(
    val year: Int,
    val month: Int,
    val startMillis: Long,
    val endMillisExclusive: Long,
) {
    /** HashMap 分组用的稳定键。 */
    val key: Int get() = year * 100 + month
}

/** [epochMillis] 落在 [zone] 时区的哪个日历月，以及该月的毫秒区间。 */
internal fun monthWindowAt(epochMillis: Long, zone: ZoneId): MonthWindow {
    val ym = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone))
    return MonthWindow(
        year = ym.year,
        month = ym.monthValue,
        startMillis = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillisExclusive = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

/** 共享存储里图片唯一被允许的两棵顶层子树之一（另一棵是 `DCIM`）。 */
private const val IMAGE_ROOT = "Pictures"

/** 截图目录的两种前缀（末尾不带 `/`）：AOSP 用前者，少数定制 ROM 用后者。 */
internal const val SCREENSHOT_PATH_PICTURES = "Pictures/Screenshots"
internal const val SCREENSHOT_PATH_DCIM = "DCIM/Screenshots"

/**
 * 这张照片算不算截图 —— 只看**路径前缀**，不看桶显示名。
 *
 * 参考实现（Slidebox）硬编码了桶显示名 `"Screenshots"`，那在中文 ROM 上会直接失效
 * （桶名被本地化成「截图」）。路径是目录的唯一身份，名字不是 —— 这条教训本项目
 * 在 `standardDirsMissing` / `computeTargetPath` 上已经付过两次学费。
 *
 * 大小写不敏感：`RELATIVE_PATH` 反映设备上的真实目录，不该因为设备写成 `pictures/` 就漏掉。
 * 前缀后面必须紧跟 `/` 或到头：否则 `Pictures/ScreenshotsBackup` 也会被误判成截图。
 *
 * P3：与生产 SQL 判据（`SCREENSHOT_PATH_CLAUSE` / `SCREENSHOT_PATH_ARGS`）同源 ——
 * 「精确匹配（无尾斜杠写入）+ `/%` 前缀」两条分别对应 `= 前缀` 与 `LIKE 前缀/%`，
 * 语义上与双 `LIKE ?` 完全一致。此前该函数全仓只有测试引用，生产走 SQL，
 * 「前缀必须止于路径边界」的断言保护的是不参与生产的死代码，现已收敛。
 */
internal fun isScreenshotPath(relativePath: String): Boolean {
    val path = relativePath.trim('/')
    val lower = path.lowercase()
    return listOf(SCREENSHOT_PATH_PICTURES, SCREENSHOT_PATH_DCIM).any { prefix ->
        val p = prefix.lowercase()
        lower == p || lower.startsWith("$p/")
    }
}

/**
 * 这个相对路径是不是图片的**合法落点**。
 *
 * MediaProvider 对 `RELATIVE_PATH` 有硬校验：图片只能落在 `DCIM/` 或 `Pictures/` 之下，
 * 其余顶层目录（`Download/`、`Android/`…）一律拒绝。实机报错原文：
 *
 * ```
 * java.lang.IllegalArgumentException: Primary directory Download not allowed for
 * content://media/external_primary/images/media/228; allowed directories are [DCIM, Pictures]
 * ```
 *
 * 所以「把照片移到 Download 相册」在这套 API 下是**不可能完成**的 —— 这种目标一开始就不该
 * 出现在移动目标里（见 `LibraryViewModel.moveTargets`），也不该被排进队列。
 *
 * 大小写不敏感比较：`RELATIVE_PATH` 直接来自 MediaStore，反映设备上的真实目录，
 * 不该因为设备把它写成 `dcim/` 就把整个相册判成不能收照片。
 */
fun isImageDestinationLegal(relativePath: String): Boolean {
    val top = relativePath.trim('/').substringBefore('/')
    return top.equals("DCIM", ignoreCase = true) || top.equals(IMAGE_ROOT, ignoreCase = true)
}

/**
 * 计算落点目录。
 *
 * 已有相册一律**原样复用它的真实路径**：`relativePath` 就是这批照片所在的目录，
 * 而落点必须在**入队时**就定下来（排队阶段）、不能等到提交时再算 ——
 * 否则「会话中途相册被别的应用改名」会让排好的队指向别处。
 *
 * 这里曾经用「相册名是不是路径后缀」来决定能否复用。桶名是系统给的**显示名**，
 * 会随系统语言变：中文 ROM 上 `DCIM/Camera` 的桶名是「相机」，后缀匹配不上，
 * 于是转而在同一棵树下另建 `DCIM/相机/` —— 同一个相册被劈成两个，
 * 照片在系统相册里凭空多出一栏。现在只看路径本身是否合法。
 *
 * 不合法（`Download/` 这类）时退到标准根目录下按名字建目录：宁可换个位置，
 * 也不能产出一个必然被系统拒绝的落点。
 *
 * 做成**顶层纯函数**是为了让这些规则能在 JVM 单测里直测，不依赖 `Context`。
 */
fun computeTargetPath(album: MediaAlbum): String {
    val existing = album.relativePath.trim('/')
    if (isImageDestinationLegal(existing)) return "$existing/"
    val name = album.name.trim().trim('/')
    return if (name.isBlank()) "$IMAGE_ROOT/" else "$IMAGE_ROOT/$name/"
}

/**
 * 从「系统标准目录」里挑出设备相册中还没有的那些，**按路径**判断，不看名字。
 *
 * 这里曾经按名字判断（`existing.any { it.name == name }`）。名字只是系统给桶的**显示名**：
 * 它会随系统语言变，也会与另一个真实目录重名 —— 设备上只要存在一个叫「相机」的相册，
 * 合成的「相机 → `DCIM/Camera`」就被跳过，于是**真正的 DCIM/Camera 反而选不到**
 * （旧版本留下的 `DCIM/相机` 就会触发这种遮蔽）。
 * 目录的唯一身份是路径，名字不是。
 */
internal fun standardDirsMissing(
    existing: List<MediaAlbum>,
    standard: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val taken = existing.map { it.relativePath.trim('/').lowercase() }.toSet()
    return standard.filterNot { (_, path) -> path.trim('/').lowercase() in taken }
}

// ---------------------------------------------------------------- 新建相册

/** 相册名不能用的原因。UI 层据此选提示文案，`core/` 里不留任何字面量。 */
enum class AlbumNameIssue { Empty, IllegalChars, Duplicate }

/**
 * 新建相册时对名字的校验。
 *
 * **必须在客户端先拦**（参考实现也是这么做的：拿卷内已有名字集合做 `contains` 判断）。
 * 把重名丢给系统去报错，用户看到的是一句没人看得懂的底层异常，而且错误来得比输入慢半拍。
 *
 * 返回 `null` 表示名字可用。
 *
 * 重名判定大小写不敏感：`FUSE`/`MediaProvider` 下层文件系统在多数设备上就不区分大小写，
 * 放行 `Cat` 与 `cat` 只会在磁盘上撞成一个目录 —— 那比直接拦下更让人困惑。
 */
fun newAlbumIssue(rawName: String, existingNames: Collection<String>): AlbumNameIssue? {
    val name = rawName.trim()
    return when {
        name.isEmpty() -> AlbumNameIssue.Empty
        name.any { it == '/' || it == '\\' || it.isISOControl() } -> AlbumNameIssue.IllegalChars
        existingNames.any { it.trim().equals(name, ignoreCase = true) } -> AlbumNameIssue.Duplicate
        else -> null
    }
}

/**
 * 新建相册的目标路径：统一落在 `Pictures/` 下。
 *
 * 为什么不像系统标准目录那样给「DCIM/ 或 Pictures/」两个选择：
 * 这一屏是「我正看着照片，想立刻给它找个新地方」—— 多一个位置选择就是多一次分心，
 * 而 `Pictures/` 是 `RELATIVE_PATH` 校验里与 `DCIM` 并列的合法根，落进去系统相册必然看得见。
 *
 * 空目录不会凭空出现：MediaStore 里没有「目录」这种实体，目录由第一张照片的
 * `RELATIVE_PATH` 带出来。所以「创建相册」在这一版里等价于「把这张照片投进这个新目录」——
 * 这也正是参考实现的用户感知（建完即投递，不用再点一次移动条）。
 */
fun newAlbumPath(name: String): String = "$IMAGE_ROOT/${name.trim().trim('/')}"

