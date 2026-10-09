package com.phototidy.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效令牌 —— 手写的 Material 3 Expressive 运动规范。
 *
 * 为什么不用 `MaterialExpressiveTheme`？
 * 因为它目前只存在于 `material3:1.5.0-beta01`（未稳定，且该版本把 compileSdk 抬到了 37），
 * 让整个工程被迫跟 beta 走不值得。这里把 Expressive 的两条核心规则用稳定 API 复刻：
 *
 *  1) **空间动效（spatial）用弹簧**：位移 / 缩放 / 旋转这类改变「物体在空间中的位置」的动画，
 *     必须用低阻尼弹簧，允许轻微过冲 —— 这是 Expressive 手感「有弹性」的来源。
 *  2) **效果动效（effects）用缓动**：颜色 / 透明度这类不改变空间位置的变化，用快进快出的
 *     贝塞尔曲线，避免弹簧带来的视觉噪点（透明度来回抖动很廉价）。
 */
object PhotoTidyMotion {

    /** 强调缓动：开头快、结尾极缓，M3 的 emphasized decelerate。 */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调缓动：开头缓、结尾快，用于「离开」的动画。 */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 标准缓动。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 空间快速：手势跟随后松手、按钮形变。阻尼更高，避免「晃」。 */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 空间弹性：小幅强调，例如点按的 scale 反馈。 */
    fun <T> spatialBouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 效果默认：颜色、透明度。 */
    fun <T> effectsDefault(): FiniteAnimationSpec<T> = tween(durationMillis = 220, easing = Standard)

    /** 效果快速：手势过程中的实时反馈。 */
    fun <T> effectsFast(): FiniteAnimationSpec<T> = tween(durationMillis = 120, easing = EmphasizedDecelerate)

    /** 照片飞出屏幕：比默认更快，让「处理下一张」的节奏不拖沓。 */
    fun <T> dismissal(): FiniteAnimationSpec<T> = tween(durationMillis = 260, easing = EmphasizedAccelerate)
}
