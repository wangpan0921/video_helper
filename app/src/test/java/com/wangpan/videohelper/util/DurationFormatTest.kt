package com.wangpan.videohelper.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun lessThanOneMinuteShowsSeconds() {
        assertEquals("0秒", formatRecordingDuration(0))
        assertEquals("5秒", formatRecordingDuration(5_000))
        assertEquals("59秒", formatRecordingDuration(59_000))
        // 不足一秒向下取整
        assertEquals("0秒", formatRecordingDuration(900))
    }

    @Test
    fun betweenOneAndSixtyMinutesShowsMinutes() {
        assertEquals("1分", formatRecordingDuration(60_000))
        assertEquals("1分", formatRecordingDuration(90_000)) // 1分30秒 → 1分
        assertEquals("40分", formatRecordingDuration(40 * 60_000L))
        assertEquals("59分", formatRecordingDuration(59 * 60_000L + 59_000))
    }

    @Test
    fun sixtyMinutesOrMoreShowsHoursMinutesSeconds() {
        assertEquals("1时0分0秒", formatRecordingDuration(60 * 60_000L))
        assertEquals("1时1分1秒", formatRecordingDuration((3600 + 60 + 1) * 1000L))
        assertEquals("2时30分15秒", formatRecordingDuration((2 * 3600 + 30 * 60 + 15) * 1000L))
    }

    @Test
    fun negativeTreatedAsZero() {
        assertEquals("0秒", formatRecordingDuration(-1000))
    }
}
