package com.phototidy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状令牌。
 *
 * Material 3 Expressive 的关键视觉特征之一是把圆角整体放大 —— 照片卡片、列表行、
 * 底部操作条都用「大圆角 + 无描边」，让界面显得柔软、有厚度，而不是贴片。
 *
 * 这里比 M3 默认值整体上调一档：
 *   默认 extraLarge=28dp -> 这里 32dp
 *   默认 large=16dp      -> 这里 24dp
 */
val PhotoTidyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 照片卡片专用：比 extraLarge 更圆，形成「一层套一层」的视觉递进。 */
val PhotoCardShape = RoundedCornerShape(28.dp)

/** 底部快捷操作条（半悬浮的 sheet）：只圆上两角。 */
val BottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** 胶囊：用于「相机 / 相册」分段控件、相册快捷移动按钮。 */
val PillShape = RoundedCornerShape(percent = 50)
