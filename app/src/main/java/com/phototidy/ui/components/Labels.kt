package com.phototidy.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.phototidy.R
import com.phototidy.core.model.MediaScope

/**
 * 数量词的统一出口。
 *
 * 「N 张」在中文里只有一种形式，但英语有单复数、俄语有三种、
 * 阿拉伯语有六种。所以这里走 `<plurals>` 而不是字符串拼接 ——
 * 现在只有 `other` 一条，将来加 `values-en/` 只需要补资源，不用回来改代码。
 */
@Composable
fun photosLabel(count: Int): String =
    pluralStringResource(R.plurals.photo_count, count, count)

@Composable
fun selectedLabel(count: Int): String =
    pluralStringResource(R.plurals.selected_count, count, count)

/**
 * 整理范围的展示名。
 *
 * 刻意放在 UI 层：`MediaScope` 是数据层的概念，它的 `key` 要稳定、可序列化，
 * 不能拿来当展示文案。两个变体对应两处不同的语境 ——
 * 顶栏空间紧张要短名，范围选择器里要写清楚「这是全部照片」。
 */
@Composable
fun MediaScope.shortLabel(): String = when (this) {
    MediaScope.Recent -> stringResource(R.string.scope_recent)
    is MediaScope.Month -> stringResource(R.string.month_title, year, month)
    is MediaScope.Album -> name
}

@Composable
fun MediaScope.optionLabel(): String = when (this) {
    MediaScope.Recent -> stringResource(R.string.scope_recent_all)
    is MediaScope.Month -> stringResource(R.string.month_title, year, month)
    is MediaScope.Album -> name
}
