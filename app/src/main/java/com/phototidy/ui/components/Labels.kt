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

/**
 * 「剩 N 张」—— 全应用的主数字口径。
 *
 * 与 [photosLabel]（「N 张」）刻意分开：把「一共有多少」和「还剩多少要整理」
 * 混用同一个词，正是这次被用户抓到的那个坑 —— 滑完 4 张返回首页，数字还是「15 张」，
 * 看起来像没生效，其实是它答的是另一个问题。
 */
@Composable
fun remainingLabel(remaining: Int): String =
    pluralStringResource(R.plurals.photo_remaining, remaining, remaining)

/** 「剩 N / M」，给空间紧张的瓦片用。 */
@Composable
fun remainingCompactLabel(remaining: Int, total: Int): String =
    pluralStringResource(R.plurals.photo_remaining_compact, remaining, remaining, total)

/** 「剩 N 张 · 共 M 张」，给有整行空间的列表用。 */
@Composable
fun remainingOfTotalLabel(remaining: Int, total: Int): String =
    pluralStringResource(R.plurals.photo_remaining_of_total, remaining, remaining, total)

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
    MediaScope.Screenshots -> stringResource(R.string.scope_screenshots)
    is MediaScope.Month -> stringResource(R.string.month_title, year, month)
    is MediaScope.Album -> name
}

@Composable
fun MediaScope.optionLabel(): String = when (this) {
    MediaScope.Recent -> stringResource(R.string.scope_recent_all)
    MediaScope.Screenshots -> stringResource(R.string.scope_screenshots_all)
    is MediaScope.Month -> stringResource(R.string.month_title, year, month)
    is MediaScope.Album -> name
}
