package com.phototidy.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * 配色策略
 * ---------
 * 照片整理类应用的正确做法是「中性哑光的机身 + 高饱和的功能色」：
 * 机身材质不抢戏，让照片本身成为画面上唯一的高饱和内容。
 * 所以这里的 primary 用低饱和的深青绿（作为品牌骨架），
 * 而「保留 / 回收站 / 收藏」三个语义色单独定义，不参与动态取色 —— 语义色必须稳定。
 */

// ============ 浅色 ============
private val LightPrimary = Color(0xFF006B5C)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFF79F7E0)
private val LightOnPrimaryContainer = Color(0xFF00201A)
private val LightSecondary = Color(0xFF4B635C)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFCDE8DF)
private val LightOnSecondaryContainer = Color(0xFF072019)
private val LightTertiary = Color(0xFF42618A)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFD3E4FF)
private val LightOnTertiaryContainer = Color(0xFF001C38)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)
private val LightBackground = Color(0xFFF4FBF8)
private val LightOnBackground = Color(0xFF161D1B)
private val LightSurface = Color(0xFFF4FBF8)
private val LightOnSurface = Color(0xFF161D1B)
private val LightSurfaceVariant = Color(0xFFDAE5E1)
private val LightOnSurfaceVariant = Color(0xFF3F4946)
private val LightOutline = Color(0xFF6F7976)
private val LightOutlineVariant = Color(0xFFBEC9C5)

// ============ 深色 ============
private val DarkPrimary = Color(0xFF5AD8BC)
private val DarkOnPrimary = Color(0xFF003730)
private val DarkPrimaryContainer = Color(0xFF005045)
private val DarkOnPrimaryContainer = Color(0xFF79F7E0)
private val DarkSecondary = Color(0xFFB1CCC3)
private val DarkOnSecondary = Color(0xFF1D352F)
private val DarkSecondaryContainer = Color(0xFF344C45)
private val DarkOnSecondaryContainer = Color(0xFFCDE8DF)
private val DarkTertiary = Color(0xFFA9C9FF)
private val DarkOnTertiary = Color(0xFF0A2E52)
private val DarkTertiaryContainer = Color(0xFF29466E)
private val DarkOnTertiaryContainer = Color(0xFFD3E4FF)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)
private val DarkBackground = Color(0xFF0E1513)
private val DarkOnBackground = Color(0xFFDDE4E1)
private val DarkSurface = Color(0xFF0E1513)
private val DarkOnSurface = Color(0xFFDDE4E1)
private val DarkSurfaceVariant = Color(0xFF3F4946)
private val DarkOnSurfaceVariant = Color(0xFFBEC9C5)
private val DarkOutline = Color(0xFF889390)
private val DarkOutlineVariant = Color(0xFF3F4946)

val PhotoTidyLightScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF6F3),
    surfaceContainer = Color(0xFFE8F0ED),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDDE5E1),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = Color(0xFF5AD8BC),
    scrim = Color(0xFF000000),
)

val PhotoTidyDarkScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A211F),
    surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerHighest = Color(0xFF2F3634),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    inversePrimary = Color(0xFF006B5C),
    scrim = Color(0xFF000000),
)

/** 纯黑（AMOLED）深色：省电 + 照片边缘不漏光。设置里可开关。 */
fun pureBlackScheme(base: ColorScheme): ColorScheme = base.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
)

/*
 * 语义色：不随主题/动态取色变化。
 * 这是 Slidebox 那类「肌肉记忆型」应用的核心 —— 绿色永远是「保留」，红色永远是「删除」，
 * 换主题不该改变用户的直觉判断。
 */
object SemanticColors {
    /** 保留 / 下一张 */
    val Keep = Color(0xFF12B76A)
    val KeepDark = Color(0xFF4BE08F)

    /** 回收站 / 删除 */
    val Trash = Color(0xFFE5484D)
    val TrashDark = Color(0xFFFF6B6F)

    /** 撤销（中性，不与三个主语义抢注意力） */
    val Undo = Color(0xFF6B7A76)
    val UndoDark = Color(0xFF9FB0AB)

    fun keep(isDark: Boolean) = if (isDark) KeepDark else Keep
    fun trash(isDark: Boolean) = if (isDark) TrashDark else Trash
    fun undo(isDark: Boolean) = if (isDark) UndoDark else Undo
}
