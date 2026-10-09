package com.phototidy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.phototidy.core.settings.ThemeMode

/** 主题层附带的状态，供各界面直接读取（避免每个 Composable 都传一遍 isDark）。 */
data class PhotoTidyThemeState(
    val isDark: Boolean,
    /** 动态取色（Material You）是否实际生效 */
    val dynamicColorApplied: Boolean,
)

val LocalPhotoTidyThemeState = staticCompositionLocalOf {
    PhotoTidyThemeState(isDark = false, dynamicColorApplied = false)
}

object PhotoTidyTheme {
    val state: PhotoTidyThemeState
        @Composable @ReadOnlyComposable get() = LocalPhotoTidyThemeState.current

    /** 语义色快捷访问，自动适配明暗。 */
    val keep: androidx.compose.ui.graphics.Color
        @Composable @ReadOnlyComposable get() = SemanticColors.keep(state.isDark)

    val trash: androidx.compose.ui.graphics.Color
        @Composable @ReadOnlyComposable get() = SemanticColors.trash(state.isDark)

    val favorite: androidx.compose.ui.graphics.Color
        @Composable @ReadOnlyComposable get() = SemanticColors.favorite(state.isDark)

    val undo: androidx.compose.ui.graphics.Color
        @Composable @ReadOnlyComposable get() = SemanticColors.undo(state.isDark)
}

@Composable
fun PhotoTidyTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    // 动态取色需要 Android 12（API 31）以上；本项目 minSdk 31，所以条件实际上恒为 true，
    // 但保留判断是为了将来把 minSdk 下调时不至于出问题。
    @Suppress("ObsoleteSdkInt") // minSdk 31 下恒真，保留判断是为了将来下调 minSdk 时不出错
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicApplied = dynamicColor && supportsDynamic

    val scheme: ColorScheme = when {
        dynamicApplied && isDark -> dynamicDarkColorScheme(context)
        dynamicApplied -> dynamicLightColorScheme(context)
        isDark -> PhotoTidyDarkScheme
        else -> PhotoTidyLightScheme
    }.let { if (pureBlack && isDark) pureBlackScheme(it) else it }

    CompositionLocalProvider(
        LocalPhotoTidyThemeState provides PhotoTidyThemeState(
            isDark = isDark,
            dynamicColorApplied = dynamicApplied,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PhotoTidyTypography,
            shapes = PhotoTidyShapes,
            content = content,
        )
    }
}
