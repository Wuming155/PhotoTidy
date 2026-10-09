package com.phototidy.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 照片访问权限的三态。
 *
 * Android 14 起用户可以只授权「部分照片」，这必须被当成一等公民对待 ——
 * 不能像老代码那样把「没拿到完全授权」直接等同于「没授权」，否则用户选了 3 张照片进来看到空白页会以为应用坏了。
 */
enum class MediaAccess {
    /** READ_MEDIA_IMAGES 已授予（或 ≤32 的 READ_EXTERNAL_STORAGE） */
    Full,

    /** 仅 Android 14+：READ_MEDIA_VISUAL_USER_SELECTED 已授予，MediaStore 只会返回用户勾选的照片 */
    Partial,

    Denied,
    ;

    val canRead: Boolean get() = this != Denied

    val isPartial: Boolean get() = this == Partial
}

object MediaPermissions {

    private const val READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * 需要向系统申请的权限列表。
     *
     * 在 Android 14+ 上同时申请 READ_MEDIA_IMAGES 与 READ_MEDIA_VISUAL_USER_SELECTED 是关键：
     * 只声明前者的话，系统的权限对话框里根本不会出现「选择照片」这个第三选项。
     */
    @Suppress("InlinedApi")
    val required: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            READ_MEDIA_IMAGES,
            READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun current(context: Context): MediaAccess {
        val full = required.first()
        val fullGranted = ContextCompat.checkSelfPermission(context, full) ==
            PackageManager.PERMISSION_GRANTED
        if (fullGranted) return MediaAccess.Full

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val partialGranted = ContextCompat.checkSelfPermission(
                context,
                READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
            if (partialGranted) return MediaAccess.Partial
        }
        return MediaAccess.Denied
    }

    /** 把 ActivityResultContracts.RequestMultiplePermissions 的返回结果映射成三态。 */
    fun interpret(result: Map<String, Boolean>): MediaAccess {
        if (result[READ_MEDIA_IMAGES] == true) return MediaAccess.Full
        if (result[READ_MEDIA_VISUAL_USER_SELECTED] == true) return MediaAccess.Partial
        // ≤32 的设备 key 是 READ_EXTERNAL_STORAGE
        if (result.values.any { it }) return MediaAccess.Full
        return MediaAccess.Denied
    }
}
