package com.phototidy.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.net.toUri
import com.phototidy.core.model.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一组重复照片。
 *
 * [wastedBytes] 是「把它们清掉能释放多少」—— 已扣除要保留的那一张，
 * 直接回答用户心里那个问题（「清这些能省多少」），而不是让他自己乘一遍。
 */
data class DuplicateGroup(
    val images: List<MediaImage>,
    val wastedBytes: Long,
)

/**
 * 纯本地的重复照片检测。
 *
 * 参考实现（Slidebox）把重复检测做成了付费功能，且它跑在客户端还是服务端无法静态判定
 * （见 notes §5）。这里**不跟随那个形态**：不上传、不联网、不收费 ——
 * 应用清单里连网络权限都没有，照片在物理上离不开这台设备。
 *
 * 两级判据，先便宜后昂贵：
 *  1) **文件大小**：一次游标扫描就能分组，零解码开销
 *     （见 [MediaStoreRepository.findSizeCandidates]）。大小不同的两张照片不可能是同一张。
 *  2) **感知哈希（dHash）**：只对大小相同的候选算。把图缩到 9×8 灰度、
 *     取「横向相邻像素谁更亮」的 64 位指纹 —— 它天然容忍重新编码、元数据差异、
 *     轻微缩放，而这些正是「同一张照片存了两遍」最常见的形态。
 *
 * 只把**指纹完全相同**的照片判为一组。刻意不做「汉明距离 ≤ N」的近似匹配：
 * 那会把连拍、相似构图但从不同角度拍的照片成批判成重复，而这一页的出口是
 * 「批量移进回收站」—— 判错一次的代价是让用户以为自己删掉的是垃圾。
 */
class DuplicateFinder(
    private val context: Context,
    private val repo: MediaStoreRepository,
) {

    /**
     * 扫一遍照片库，返回重复组（按可释放空间从大到小）。
     *
     * 全流程跑在 IO 线程：解码缩略图 + 计算指纹是 CPU/IO 混合负载，
     * 放在主线程会让界面僵住几秒（照片多的时候更久）。
     */
    suspend fun findDuplicates(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val candidates = repo.findSizeCandidates()
        if (candidates.isEmpty()) return@withContext emptyList()

        // 计算量的硬上限。做到一半被打断比「等到天荒地老」更糟，
        // 所以宁可少算几组，也要让这个功能有确定的结束时间。
        var budget = MAX_HASHES
        val groups = ArrayList<DuplicateGroup>()

        for ((_, ids) in candidates) {
            if (budget <= 0) break
            val images = runCatching { repo.imagesByIds(ids) }.getOrDefault(emptyList())
            if (images.size < 2) continue

            val fingerprinted = ArrayList<Pair<Long, MediaImage>>(images.size)
            for (image in images) {
                if (budget <= 0) break
                budget--
                val hash = runCatching { perceptualHash(image.uri) }
                    .onFailure { Log.w(TAG, "hash failed for ${image.id}", it) }
                    .getOrNull() ?: continue
                fingerprinted += hash to image
            }
            // 分组口径走纯函数（见 groupByFingerprint）：判错就要误删照片的那一步
            // 必须能在 JVM 单测里直测。
            groupByFingerprint(fingerprinted).forEach { same ->
                groups += DuplicateGroup(
                    images = same,
                    wastedBytes = same.drop(1).sumOf { it.sizeBytes },
                )
            }
        }

        groups.sortedByDescending { it.wastedBytes }
    }

    /**
     * 一张照片的 64 位 dHash。
     *
     * 实现细节：
     *  · `setTargetSize(9, 8)` —— dHash 只需要横向相邻像素的比较，9 列 × 8 行正好给出 64 个比较。
     *    刻意不按原始宽高比缩放：dHash 的标准做法就是在拉伸后比较，省掉一次几何换算。
     *  · `ALLOCATOR_SOFTWARE` —— 硬件位图（`ALLOCATOR_HARDWARE`）**读不了像素**，
     *    `getPixels` 会直接抛异常。这个坑在这里堵死。
     *  · 用 `use { }` 强制回收 —— 9×8 只有 288 字节，但几百张就是几百次实打实的分配，
     *    低端机上足以看出内存抖动。
     */
    private fun perceptualHash(uriString: String): Long? {
        val source = ImageDecoder.createSource(context.contentResolver, uriString.toUri())
        val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.setTargetSize(HASH_WIDTH, HASH_HEIGHT)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
        return try {
            if (bitmap.width < HASH_WIDTH || bitmap.height < HASH_HEIGHT) return null
            val pixels = IntArray(HASH_WIDTH * HASH_HEIGHT)
            bitmap.getPixels(pixels, 0, HASH_WIDTH, 0, 0, HASH_WIDTH, HASH_HEIGHT)

            // 亮度用整数近似（Rec.601 的定点形式），避免 72 次浮点运算
            val luma = IntArray(pixels.size) { index ->
                val p = pixels[index]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                (r * 77 + g * 151 + b * 28) shr 8
            }
            var hash = 0L
            for (y in 0 until HASH_HEIGHT) {
                for (x in 0 until HASH_WIDTH - 1) {
                    val left = luma[y * HASH_WIDTH + x]
                    val right = luma[y * HASH_WIDTH + x + 1]
                    if (left > right) hash = hash or (1L shl (y * (HASH_WIDTH - 1) + x))
                }
            }
            hash
        } finally {
            // 必须显式回收：9×8 只是 288 字节，但一张 SOFTWARE 位图在低端机上
            // 也是实打实的分配，几百张就是几百次 —— 全靠 GC 收会看出内存抖动。
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "DuplicateFinder"

        /** dHash 的目标尺寸：9 列 × 8 行 → 8×8 = 64 位指纹。 */
        const val HASH_WIDTH = 9
        const val HASH_HEIGHT = 8

        /**
         * 一次扫描最多解码多少张。
         *
         * 800 张在低端机上约几秒；不设上限的话，一个「同尺寸照片特别多」的库
         * （连拍很容易造出几千张同分辨率照片）会让这个页面转到用户以为它挂了。
         */
        const val MAX_HASHES = 800
    }
}

/**
 * 把「指纹 → 对象」按指纹分组，返回至少两项一组的那些。
 *
 * 抽成顶层纯函数是为了能在 JVM 单测里直测分组口径 —— 这是整个功能里
 * 「判错就要误删照片」的那一步，值得钉住；它不依赖任何 Android 类型。
 */
internal fun <T> groupByFingerprint(entries: List<Pair<Long, T>>): List<List<T>> =
    entries.groupBy({ it.first }, { it.second }).values.filter { it.size >= 2 }
