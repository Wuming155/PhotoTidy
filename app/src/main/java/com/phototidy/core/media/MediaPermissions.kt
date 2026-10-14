package com.phototidy.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 照片访问权限的三态。
 *
 * Android 14 起用户可以只授权「部分照片和视频」，这必须被当成一等公民对待 ——
 * 不能像老代码那样把「没拿到完全授权」直接等同于「没授权」，否则用户只选了几项媒体时会以为应用坏了。
 */
enum class MediaAccess {
    /** 已授予照片或视频读取权限（或 ≤32 的 READ_EXTERNAL_STORAGE） */
    Full,

    /** 仅 Android 14+：READ_MEDIA_VISUAL_USER_SELECTED 已授予，MediaStore 只会返回用户勾选的媒体 */
    Partial,

    Denied,
    ;

    val canRead: Boolean get() = this != Denied

    val isPartial: Boolean get() = this == Partial
}

object MediaPermissions {

    // READ_MEDIA_IMAGES 是 API 33 引入的常量，而 minSdk 是 31 ——
    // 运行时按 SDK_INT 分支取值，所以这里引用它是有意的（编译器只看到字面量内联）。
    @Suppress("InlinedApi")
    private const val READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES
    @Suppress("InlinedApi")
    private const val READ_MEDIA_VIDEO = Manifest.permission.READ_MEDIA_VIDEO
    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    /**
     * 需要向系统申请的权限列表。
     *
     * 在 Android 14+ 上同时申请照片、视频和 READ_MEDIA_VISUAL_USER_SELECTED 是关键：
     * 同时声明部分媒体权限，系统才会提供「选择照片和视频」的授权选项。
     */
    @Suppress("InlinedApi")
    val required: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            READ_MEDIA_IMAGES,
            READ_MEDIA_VIDEO,
            READ_MEDIA_VISUAL_USER_SELECTED,
        )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun current(context: Context): MediaAccess {
        val fullGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO).any { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
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
        if (result[READ_MEDIA_IMAGES] == true || result[READ_MEDIA_VIDEO] == true) return MediaAccess.Full
        if (result[READ_MEDIA_VISUAL_USER_SELECTED] == true) return MediaAccess.Partial
        // ≤32 的设备 key 是 READ_EXTERNAL_STORAGE
        if (result.values.any { it }) return MediaAccess.Full
        return MediaAccess.Denied
    }

    /**
     * 将权限请求结果解析为媒体访问状态。
     * 当请求被取消或未发生变动时，必须结合系统当前真实权限状态，
     * 避免在 Android 14+ 下取消选择器时，原有的 Partial 权限被错误降级为 Denied。
     */
    fun resolveAccess(currentFromContext: MediaAccess, result: Map<String, Boolean>): MediaAccess {
        if (result[READ_MEDIA_IMAGES] == true || result[READ_MEDIA_VIDEO] == true) return MediaAccess.Full
        if (result[READ_MEDIA_VISUAL_USER_SELECTED] == true) return MediaAccess.Partial
        if (result.values.any { it }) return MediaAccess.Full
        // 如果 result 中没有任何一项为 true（例如用户按返回取消了授权弹窗），
        // 必须回退检查当前的系统权限状态，不能直接降级为 Denied
        return currentFromContext
    }
}
