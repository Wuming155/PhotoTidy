package com.phototidy.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 日期时间格式化。
 *
 * 刻意**不**再写死 `"yyyy 年 M 月 d 日"` —— 那是把中文语序焊进代码，
 * 语言一换就得改源码。改用 JDK 的本地化样式，格式随 Locale 走
 * （zh-CN → 「2026年10月9日」，en-US → 「Oct 9, 2026」）。
 */
private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** 日期 + 时分，整理页顶部的拍摄时间。 */
fun Long.toPhotoDateTime(): String {
    val at = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    return "${DATE.format(at)} ${TIME.format(at)}"
}

/**
 * 1234 -> "1,234"。
 *
 * 用默认 Locale 而不是写死 `Locale.US`：千分位分隔符在德语区是「.」、在法语区是空格。
 */
fun Int.grouped(): String = String.format(Locale.getDefault(), "%,d", this)

/** 人类可读的字节数。 */
fun Long.toReadableSize(): String {
    if (this <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}

/** 全部照片合计大小，用于回收站「将释放」提示。 */
fun Iterable<Long>.sumReadableSize(): String = sum().toReadableSize()
