package com.phototidy.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val hapticsEnabled: Boolean = true,
    /** 滑动触发阈值倍率：越小越容易触发（0.6 灵敏 ~ 1.8 迟钝） */
    val swipeSensitivity: Float = 1.0f,
    /** 整理页是否显示「下一张」的预览堆叠 */
    val showNextPreview: Boolean = true,
    /** 相册页是否显示 0 张的文件夹 */
    val showEmptyAlbums: Boolean = false,
    val helpShown: Boolean = false,
)

/**
 * 设置存储。
 *
 * 用 SharedPreferences 而不是 DataStore：这里只有不到 10 个标量，且需要在首帧同步读取
 * （否则会先闪一下浅色主题再切深色）。DataStore 是异步的，反而要额外处理这个闪烁。
 * 如果将来设置项膨胀或需要跨进程，再换 DataStore。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("phototidy_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val current: AppSettings get() = _state.value

    private fun read() = AppSettings(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.System),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
        hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
        swipeSensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f).coerceIn(0.6f, 1.8f),
        showNextPreview = prefs.getBoolean(KEY_NEXT_PREVIEW, true),
        showEmptyAlbums = prefs.getBoolean(KEY_EMPTY_ALBUMS, false),
        helpShown = prefs.getBoolean(KEY_HELP_SHOWN, false),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit {
            putString(KEY_THEME, next.themeMode.name)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putBoolean(KEY_PURE_BLACK, next.pureBlack)
            putBoolean(KEY_HAPTICS, next.hapticsEnabled)
            putFloat(KEY_SENSITIVITY, next.swipeSensitivity)
            putBoolean(KEY_NEXT_PREVIEW, next.showNextPreview)
            putBoolean(KEY_EMPTY_ALBUMS, next.showEmptyAlbums)
            putBoolean(KEY_HELP_SHOWN, next.helpShown)
        }
    }

    fun markHelpShown() = update { it.copy(helpShown = true) }

    fun toggleTheme() = update {
        it.copy(
            themeMode = when (it.themeMode) {
                ThemeMode.System -> ThemeMode.Light
                ThemeMode.Light -> ThemeMode.Dark
                ThemeMode.Dark -> ThemeMode.System
            },
        )
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_PURE_BLACK = "pure_black"
        const val KEY_HAPTICS = "haptics"
        const val KEY_SENSITIVITY = "swipe_sensitivity"
        const val KEY_NEXT_PREVIEW = "next_preview"
        const val KEY_EMPTY_ALBUMS = "show_empty_albums"
        const val KEY_HELP_SHOWN = "help_shown"
    }
}
