package com.phototidy.core.model

/**
 * 一张照片的元数据快照。
 *
 * 刻意不持有 Bitmap：列表里可能有几万条，Bitmap 由图片加载库按需异步解码。
 * 这里的字段刚好够「排序 / 分组 / 显示 / 定位」四种用途。
 */
data class MediaImage(
    val id: Long,
    /**
     * 内容 URI 的字符串形式（例如 `content://media/external/images/media/42`）。
     *
     * 用 `String` 而非 `android.net.Uri` 是有意为之：这样 [MediaImage] 不依赖 Android 框架类型，
     * 整理会话的纯逻辑（撤销栈 / 窗口 / 计数）就能在 JVM 单测里直接构造，无需 Robolectric。
     * 真正需要 `Uri` 的写操作（移动 / 进回收站）在 `MediaStoreRepository.uriFor` 处现转。
     */
    val uri: String,
    val displayName: String,
    /** 拍摄时间（毫秒）。系统可能没写 DATE_TAKEN，退化用入库时间。 */
    val takenAtMillis: Long,
    val addedAtMillis: Long,
    val bucketId: Long,
    val albumName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
) {
    /** Coil 缓存键。URI 已包含 id，天然唯一。 */
    val key: String get() = uri

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1f
}

/**
 * 一个相册（= 设备上的一个存图目录）。
 * [relativePath] 为空表示这是「最近 / 相机胶卷」这类虚拟集合，不能作为移动目标。
 */
data class MediaAlbum(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val count: Int,
    val cover: MediaImage?,
) {
    val canReceive: Boolean get() = relativePath.isNotBlank()

    companion object {
        /** 还原一张照片「原本所在的相册」，用于撤销移动。 */
        fun of(image: MediaImage): MediaAlbum = MediaAlbum(
            bucketId = image.bucketId,
            name = image.albumName.ifBlank { "Pictures" },
            relativePath = image.relativePath,
            count = 0,
            cover = image,
        )
    }
}

/**
 * 按月分组（整理首页的时间轴）。
 *
 * 刻意**不**在这里拼「2026 年 3 月」这类文案：数据层一旦拼好字符串，
 * 就等于把展示语言焊死在模型里，上多语言时只能回头改模型。
 * 展示用的标题由 UI 侧用 `strings.xml` 里的格式串生成。
 */
data class MonthGroup(
    val year: Int,
    val month: Int,
    val count: Int,
    val cover: MediaImage?,
)

/**
 * 一次整理会话的作用范围。
 * key 用于 ViewModel 的实例隔离 —— 不同范围必须是不同的会话，互不污染撤销栈。
 */
sealed interface MediaScope {
    /** 稳定的机器标识：用于 ViewModel 实例隔离与持久化键，**不参与展示**。 */
    val key: String

    data object Recent : MediaScope {
        override val key: String = "recent"
    }

    data class Month(val year: Int, val month: Int) : MediaScope {
        override val key: String = "month:$year:$month"
    }

    data class Album(val bucketId: Long, val name: String) : MediaScope {
        override val key: String = "album:$bucketId:$name"
    }

    companion object {
        /**
         * 序列化 / 反序列化。
         *
         * 用于 `rememberSaveable` —— 进程被系统回收后重建界面，不能把用户丢回首页，
         * 那会打断「翻到第 800 张时被打断」这种真实场景。
         */
        /**
         * 序列化。
         *
         * 直接用 [key] —— 它本来就是「这一个范围」的规范字符串表示，
         * 再写一份 when 分支意味着两套格式要手工保持同步，是纯粹的隐患。
         */
        fun encode(scope: MediaScope?): String = scope?.key.orEmpty()

        fun decode(raw: String?): MediaScope? = when {
            raw.isNullOrBlank() -> null
            raw == "recent" -> MediaScope.Recent
            raw.startsWith("month:") -> raw.removePrefix("month:").split(":").let { parts ->
                val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val month = parts.getOrNull(1)?.toIntOrNull() ?: return null
                Month(year, month)
            }

            raw.startsWith("album:") -> raw.removePrefix("album:").split(":", limit = 2).let { parts ->
                val bucketId = parts.getOrNull(0)?.toLongOrNull() ?: return null
                Album(bucketId, parts.getOrNull(1).orEmpty())
            }

            else -> null
        }
    }
}

/**
 * 一次已排队、尚未落盘的移动。
 *
 * 只带 id 和已解析好的目标路径，**不带 MediaImage 引用** —— 这样它才能被序列化落盘，
 * 也才能让整理会话的内存占用与照片库规模脱钩。
 */
data class PendingMove(
    val imageId: Long,
    /** 提交时写入 `RELATIVE_PATH` 的目标目录，入队时已算好。 */
    val targetPath: String,
    /** 目标相册展示名，仅用于回显。 */
    val albumName: String,
    /** 目标相册 bucketId；系统标准目录用负数占位。 */
    val bucketId: Long,
    /** 目标相册原有 relativePath，恢复时用于重建相册对象。 */
    val albumPath: String,
)

/** 一次整理动作。用于撤销栈。 */
enum class ReviewAction { Keep, Trash, Move }

data class ReviewRecord(
    val image: MediaImage,
    val action: ReviewAction,
    /** Move 时的目标相册名 */
    val targetAlbum: String? = null,
)
