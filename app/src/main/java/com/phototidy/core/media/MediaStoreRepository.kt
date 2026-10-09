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

    companion object {
        val Empty = LibraryOverview(0, 0L, null, emptyList(), emptyList())
    }
}

enum class ConsentKind { Write, Trash, Delete }

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
 */
class MediaStoreRepository(private val context: Context) : MediaRepository {

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
     */
    suspend fun loadOverview(): LibraryOverview = withContext(Dispatchers.IO) {
        val albums = HashMap<Long, AlbumAcc>()
        val months = HashMap<Int, MonthAcc>()
        val zone = ZoneId.systemDefault()
        var total = 0
        var bytes = 0L
        var newest: MediaImage? = null

        openCursor(selection = VISIBLE, onlyTrashed = false)?.use { c ->
            val cols = Cols(c)
            while (c.moveToNext()) {
                val taken = c.takenAt(cols)
                val bucketId = c.getLong(cols.bucket)
                val size = c.getLong(cols.size)
                val path = c.getString(cols.path).orEmpty()

                total++
                bytes += size
                if (newest == null) newest = c.toImage(cols)

                val album = albums.getOrPut(bucketId) {
                    AlbumAcc(
                        name = c.albumName(cols).ifBlank { path.trim('/').substringBefore('/') },
                        path = path,
                        cover = c.toImage(cols),
                    )
                }
                album.count++

                val dt = Instant.ofEpochMilli(taken).atZone(zone)
                val key = dt.year * 100 + dt.monthValue
                val month = months.getOrPut(key) { MonthAcc(dt.year, dt.monthValue, c.toImage(cols)) }
                month.count++
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
                )
            }
                // 用「最新一张照片的时间」排序：刚拍完的相册就该排在最前面
                .sortedByDescending { it.cover?.takenAtMillis ?: 0L },
            months = months.values
                .map { MonthGroup(year = it.year, month = it.month, count = it.count, cover = it.cover) }
                .sortedWith(compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month }),
        )
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
            val entries = items.map { move ->
                uriFor(move.imageId) to ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, move.targetPath)
                }
            }
            updateAll(entries).toResult(
                kind = ConsentKind.Write,
                request = { denied -> MediaStore.createWriteRequest(resolver, denied).intentSender },
            )
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
            }.getOrElse { MediaOpResult.Failed(it.message ?: "无法创建回收站请求") }
        }
    }

    /** 从系统回收站还原。 */
    suspend fun restoreFromSystemTrash(images: List<MediaImage>): MediaOpResult =
        withContext(Dispatchers.IO) {
            val uris = images.map { uriFor(it.id) }
            if (uris.isEmpty()) return@withContext MediaOpResult.Success
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 0)
            }
            directUpdate(uris, values).toResult(
                kind = ConsentKind.Write,
                request = { denied -> MediaStore.createWriteRequest(resolver, denied).intentSender },
            )
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
            }.getOrElse { MediaOpResult.Failed(it.message ?: "无法创建删除请求") }
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
     */
    private fun MediaScope.filter(): Pair<String, Array<String>?> = when (this) {
        MediaScope.Recent -> VISIBLE to null

        is MediaScope.Album ->
            "$VISIBLE AND ${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId.toString())

        is MediaScope.Month -> {
            val zone = ZoneId.systemDefault()
            val start = YearMonth.of(year, month).atDay(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
            val end = YearMonth.of(year, month).plusMonths(1).atDay(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
            "$VISIBLE AND $EFFECTIVE_DATE >= ? AND $EFFECTIVE_DATE < ?" to
                arrayOf(start.toString(), end.toString())
        }
    }

    /**
     * 统一的游标入口。
     *
     * [onlyTrashed] 三态：true = 只看回收站，false = 排除回收站，null = 两者都要。
     *
     * ⚠️ 回收站必须通过查询参数来要，不能只写在 selection 里。
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
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            if (offset != null) putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        return resolver.query(collection, if (onlyIdColumn) ID_ONLY else PROJECTION, args, null)
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
    }

    private class MonthAcc(val year: Int, val month: Int, val cover: MediaImage) {
        var count: Int = 0
    }

    /** 直写结果：哪些 URI 被系统拒绝，以及首个非权限类错误。 */
    private class UpdateOutcome(val denied: List<Uri>, val firstError: String?)

    private fun UpdateOutcome.toResult(
        kind: ConsentKind,
        request: (List<Uri>) -> IntentSender,
    ): MediaOpResult = when {
        denied.isEmpty() && firstError == null -> MediaOpResult.Success
        denied.isEmpty() -> MediaOpResult.Failed(firstError ?: "操作失败")
        else -> runCatching { MediaOpResult.NeedsConsent(request(denied), kind) }
            .getOrElse { MediaOpResult.Failed(it.message ?: "无法创建授权请求") }
    }

    /**
     * 批量写入，每张照片可以带不同的值（批量移动时各自的 RELATIVE_PATH 不同）。
     *
     * ⚠️ 必须看 `update()` 的返回值，不能只看有没有抛异常。
     * MediaProvider 对「无权修改」的项有时**不抛异常，而是静默返回 0 行** ——
     * 实机实测 IS_TRASHED 就是这种情况：抛不出 SecurityException，也改不动，
     * 只看异常会把「什么都没做」误判成成功。
     */
    private fun updateAll(entries: List<Pair<Uri, ContentValues>>): UpdateOutcome {
        val denied = ArrayList<Uri>()
        var firstError: String? = null
        entries.forEach { (uri, values) ->
            try {
                val rows = resolver.update(uri, values, null, null)
                if (rows <= 0) denied += uri
            } catch (e: SecurityException) {
                denied += uri
            } catch (e: Exception) {
                if (firstError == null) firstError = e.message
            }
        }
        return UpdateOutcome(denied, firstError)
    }

    private fun directUpdate(uris: List<Uri>, values: ContentValues): UpdateOutcome =
        updateAll(uris.map { it to values })

    private companion object {
        const val VISIBLE =
            "COALESCE(${MediaStore.MediaColumns.IS_PENDING}, 0) = 0 AND " +
                "${MediaStore.MediaColumns.SIZE} > 0"

        /** 展示层口径的真·拍摄时间；见 `MediaScope.filter()` 的注释。 */
        const val EFFECTIVE_DATE =
            "COALESCE(NULLIF(${MediaStore.MediaColumns.DATE_TAKEN}, 0), " +
                "${MediaStore.MediaColumns.DATE_ADDED} * 1000)"

        val ID_ONLY = arrayOf(MediaStore.MediaColumns._ID)

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
    }
}

/**
 * 计算落点目录。
 *
 * 图片只能落在 DCIM 或 Pictures 这两棵子树下，且已有相册要复用它的真实路径（否则会造出一堆重名文件夹）。
 *
 * 刻意做成**顶层纯函数**：落点必须在**入队时**就定下来（排队阶段）而不是提交时，
 * 否则「会话中途相册被别的应用改名」会让排好的队指向别处；
 * 做成纯函数也方便单测，不依赖 [MediaStoreRepository] 持有的 `Context`。
 *
 * 复用时走大小写不敏感匹配（[String.endsWith] 的 ignoreCase）：
 * 当 `relativePath` 是 `DCIM/camera/`（小写）而相册名是 `Camera`（大写）时，
 * 若用大小写敏感判断会误判为「没有现成目录」而新建 `DCIM/Camera/`，
 * 与原有目录形成异大小写重名 —— 在 FAT / 类 FAT 文件系统上会出问题。
 */
fun computeTargetPath(album: MediaAlbum): String {
    val existing = album.relativePath.trim('/')
    if (existing.isNotBlank() && album.name.isNotBlank() &&
        existing.endsWith(album.name, ignoreCase = true)
    ) {
        return "$existing/"
    }
    val root = existing.substringBefore('/').ifBlank { "Pictures" }
    return "$root/${album.name}/"
}
