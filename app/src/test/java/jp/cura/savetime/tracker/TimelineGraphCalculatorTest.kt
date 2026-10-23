package jp.cura.savetime.tracker

import jp.cura.savetime.model.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimelineGraphCalculatorTest {

    private val timeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun timestampForToday(hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            set(2026, Calendar.OCTOBER, 23, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun testSecondOfDayConversion_coversZeroTo86400() {
        val startOfDayMs = timestampForToday(0, 0, 0)

        assertEquals(0, TimelineGraphCalculator.toSecondOfDay(timestampForToday(0, 0, 0), startOfDayMs))
        assertEquals(3661, TimelineGraphCalculator.toSecondOfDay(timestampForToday(1, 1, 1), startOfDayMs))
        assertEquals(43200, TimelineGraphCalculator.toSecondOfDay(timestampForToday(12, 0, 0), startOfDayMs))
        assertEquals(86399, TimelineGraphCalculator.toSecondOfDay(timestampForToday(23, 59, 59), startOfDayMs))
    }

    @Test
    fun testBuildDayBars_mapsEntriesTo86400SecondAxisAndSecondsHeight() {
        val startOfDayMs = timestampForToday(0, 0, 0)
        val entries = listOf(
            TimelineEntry(
                packageName = "com.twitter.android",
                appName = "X",
                timestampMs = timestampForToday(12, 0, 35),
                durationSeconds = 45L
            ),
            TimelineEntry(
                packageName = "com.google.android.youtube",
                appName = "YouTube",
                timestampMs = timestampForToday(12, 0, 0),
                durationSeconds = 30L
            )
        )

        val bars = TimelineGraphCalculator.buildDayBars(entries, startOfDayMs)
        assertEquals(2, bars.size)

        // 時系列順（昇順）にソートされていること
        assertEquals("YouTube", bars[0].appName)
        assertEquals(43200, bars[0].startSecondOfDay)
        assertEquals(43230, bars[0].endSecondOfDay)
        assertEquals(30L, bars[0].durationSeconds)

        assertEquals("X", bars[1].appName)
        assertEquals(43235, bars[1].startSecondOfDay)
        assertEquals(43280, bars[1].endSecondOfDay)
        assertEquals(45L, bars[1].durationSeconds)
    }

    @Test
    fun testBuildDayBars_clampsCrossMidnightEntryAndFiltersOutOtherDays() {
        val startOfDayMs = timestampForToday(0, 0, 0)
        val prevDayLateMs = startOfDayMs - 20_000L // 前日 23:59:40 から 50秒間（当日 00:00:30 まで）
        val prevDayOldMs = startOfDayMs - 3600_000L // 前日 23:00:00 から 60秒間（当日にかからない）

        val entries = listOf(
            TimelineEntry("pkg.cross", "CrossApp", prevDayLateMs, 50L),
            TimelineEntry("pkg.old", "OldApp", prevDayOldMs, 60L)
        )

        val bars = TimelineGraphCalculator.buildDayBars(entries, startOfDayMs)
        assertEquals(1, bars.size)
        assertEquals("CrossApp", bars[0].appName)
        assertEquals(0, bars[0].startSecondOfDay)
        assertEquals(30, bars[0].endSecondOfDay)
        assertEquals(30L, bars[0].durationSeconds)
    }

    @Test
    fun testComputeYAxisMaxSeconds_returnsNiceScaleInSeconds() {
        assertEquals(60L, TimelineGraphCalculator.computeYAxisMaxSeconds(emptyList()))

        val shortBar = listOf(
            TimelineBar("pkg.a", "AppA", 1000, 1025, 25L, 0L)
        )
        assertEquals(60L, TimelineGraphCalculator.computeYAxisMaxSeconds(shortBar))

        val mediumBar = listOf(
            TimelineBar("pkg.a", "AppA", 1000, 1140, 140L, 0L)
        )
        assertEquals(180L, TimelineGraphCalculator.computeYAxisMaxSeconds(mediumBar))

        val longBar = listOf(
            TimelineBar("pkg.a", "AppA", 1000, 1750, 750L, 0L)
        )
        assertEquals(900L, TimelineGraphCalculator.computeYAxisMaxSeconds(longBar))
    }

    @Test
    fun testFindBarAtSecond_matchesExactAndNearestWithinTolerance() {
        val bars = listOf(
            TimelineBar("pkg.yt", "YouTube", 36000, 36030, 30L, 100L),
            TimelineBar("pkg.x", "X", 36040, 36070, 30L, 200L)
        )

        // 範囲内ジャストヒット
        val exact = TimelineGraphCalculator.findBarAtSecond(bars, targetSecond = 36015, toleranceSeconds = 60)
        assertNotNull(exact)
        assertEquals("YouTube", exact?.appName)

        // わずかに外れているが許容誤差内
        val near = TimelineGraphCalculator.findBarAtSecond(bars, targetSecond = 36080, toleranceSeconds = 60)
        assertNotNull(near)
        assertEquals("X", near?.appName)

        // 許容誤差外
        val far = TimelineGraphCalculator.findBarAtSecond(bars, targetSecond = 50000, toleranceSeconds = 60)
        assertNull(far)
    }

    @Test
    fun testFormatSecondOfDay_formatsHHmmssCleanly() {
        assertEquals("00:00:00", TimelineGraphCalculator.formatSecondOfDay(0))
        assertEquals("12:30:45", TimelineGraphCalculator.formatSecondOfDay(12 * 3600 + 30 * 60 + 45))
        assertEquals("24:00:00", TimelineGraphCalculator.formatSecondOfDay(86400))
    }
}
