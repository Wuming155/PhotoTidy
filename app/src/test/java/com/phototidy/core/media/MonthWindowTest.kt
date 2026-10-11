package com.phototidy.core.media

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [monthWindowAt] 是冷启动扫描里唯一「正确性敏感」的换算：窗口边界必须与
 * `MediaScope.filter` 里 Month 条件的 SQL 口径（本地日历月、`>= start AND < end`）
 * 完全一致，否则「按月整理」会漏照片。这里用固定时区把各类边界钉死。
 */
class MonthWindowTest {

    @Test
    fun `window edges are the local calendar month`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val w = monthWindowAt(Instant.parse("2026-06-15T00:00:00Z").toEpochMilli(), zone)
        assertEquals(202606, w.key)
        assertEquals(
            ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            w.startMillis,
        )
        assertEquals(
            ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
            w.endMillisExclusive,
        )
        // 端点归属与 SQL 的 >= start AND < end 一致：start 算本月，end 算下月
        assertEquals(202606, monthWindowAt(w.startMillis, zone).key)
        assertEquals(202607, monthWindowAt(w.endMillisExclusive, zone).key)
    }

    @Test
    fun `crossing a year keeps year and month intact`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val dec = monthWindowAt(Instant.parse("2026-12-31T12:00:00Z").toEpochMilli(), zone)
        val jan = monthWindowAt(dec.endMillisExclusive, zone)
        assertEquals(202612, dec.key)
        assertEquals(2027, jan.year)
        assertEquals(1, jan.month)
        assertEquals(202701, jan.key)
    }

    @Test
    fun `dst shifts the window edges by the zone offset`() {
        val zone = ZoneId.of("America/New_York")
        val w = monthWindowAt(Instant.parse("2026-03-15T12:00:00Z").toEpochMilli(), zone)
        // 2026-03-08 起进入夏令时：月初是 EST(-5)，下月初是 EDT(-4)，窗口两端偏移不同。
        // 窗口必须按「本地日历月」切，而不是按固定 30 天或 UTC 月切。
        assertEquals(Instant.parse("2026-03-01T05:00:00Z").toEpochMilli(), w.startMillis)
        assertEquals(Instant.parse("2026-04-01T04:00:00Z").toEpochMilli(), w.endMillisExclusive)
        assertEquals(202603, w.key)
    }

    @Test
    fun `epoch zero follows the zone's local calendar`() {
        // DATE_TAKEN / DATE_ADDED 双零的脏数据会落在这里；SQL 侧同一 COALESCE 口径
        // 算出的是同一个时刻，两侧分组必然一致
        assertEquals(197001, monthWindowAt(0L, ZoneId.of("Asia/Shanghai")).key)
        assertEquals(196912, monthWindowAt(0L, ZoneId.of("America/New_York")).key)
    }
}
