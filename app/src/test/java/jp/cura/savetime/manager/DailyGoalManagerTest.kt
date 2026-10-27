package jp.cura.savetime.manager

import jp.cura.savetime.tracker.TimelineGraphCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DailyGoalManagerTest {

    private val timeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun createTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun testLogicalDayBoundary_before4Am_belongsToPreviousDay() {
        // 2026-10-27 03:59:59 -> 論理日の開始は 2026-10-26 04:00:00
        val nowMs = createTimestamp(2026, 10, 27, 3, 59, 59)
        val expectedStartMs = createTimestamp(2026, 10, 26, 4, 0, 0)

        val actualStartMs = DailyGoalManager.getLogicalDayStartMs(nowMs, baseHour = 4, timeZone = timeZone)
        assertEquals(expectedStartMs, actualStartMs)
    }

    @Test
    fun testLogicalDayBoundary_atOrAfter4Am_belongsToCurrentDay() {
        // 2026-10-27 04:00:00 -> 論理日の開始は 2026-10-27 04:00:00
        val nowExact4Am = createTimestamp(2026, 10, 27, 4, 0, 0)
        val expectedStartMs = createTimestamp(2026, 10, 27, 4, 0, 0)
        val actualStart4Am = DailyGoalManager.getLogicalDayStartMs(nowExact4Am, baseHour = 4, timeZone = timeZone)
        assertEquals(expectedStartMs, actualStart4Am)

        // 2026-10-27 23:59:59 -> 論理日の開始は 2026-10-27 04:00:00
        val nowLateNight = createTimestamp(2026, 10, 27, 23, 59, 59)
        val actualStartLateNight = DailyGoalManager.getLogicalDayStartMs(nowLateNight, baseHour = 4, timeZone = timeZone)
        assertEquals(expectedStartMs, actualStartLateNight)
    }

    @Test
    fun testCalculateGoalFromPastUsage_reduces10Percent() {
        // 10,000秒の実績 -> 10%削減して 9,000秒
        val pastUsageSec = 10_000L
        val calculated = DailyGoalManager.calculateGoalFromPastUsage(pastUsageSec)
        assertEquals(9_000L, calculated)
    }

    @Test
    fun testCalculateGoalFromPastUsage_enforcesMinimum30Minutes() {
        // 1,000秒の実績 -> 10%削減で900秒だが、最低保証30分（1800秒）が適用される
        val pastUsageSec = 1_000L
        val calculated = DailyGoalManager.calculateGoalFromPastUsage(pastUsageSec)
        assertEquals(DailyGoalManager.MINIMUM_GOAL_SECONDS, calculated)
        assertEquals(1800L, calculated)
    }

    @Test
    fun testCalculateGoalFromPastUsage_fallbackOnZeroOrNegative() {
        // 実績が0または負の場合、デフォルト値（3時間 = 10,800秒）にフォールバック
        assertEquals(DailyGoalManager.DEFAULT_FALLBACK_GOAL_SECONDS, DailyGoalManager.calculateGoalFromPastUsage(0L))
        assertEquals(DailyGoalManager.DEFAULT_FALLBACK_GOAL_SECONDS, DailyGoalManager.calculateGoalFromPastUsage(-500L))
        assertEquals(10800L, DailyGoalManager.DEFAULT_FALLBACK_GOAL_SECONDS)
    }

    @Test
    fun testShouldTriggerMorningWakeupLock_oncePerLogicalDay() {
        val day1StartMs = createTimestamp(2026, 10, 27, 4, 0, 0)
        val day1Unlock1Ms = createTimestamp(2026, 10, 27, 7, 30, 0)
        val day1Unlock2Ms = createTimestamp(2026, 10, 27, 8, 15, 0)
        val day2UnlockMs = createTimestamp(2026, 10, 28, 6, 0, 0)

        // 1. 初回ロック解除（未実行）は発動可能
        var lastLockDayMs = 0L
        assertTrue(DailyGoalManager.shouldTriggerMorningWakeupLock(lastLockDayMs, day1Unlock1Ms))

        // 2. 発動を記録
        lastLockDayMs = day1StartMs

        // 3. 同日中の2回目のロック解除は発動しない
        assertFalse(DailyGoalManager.shouldTriggerMorningWakeupLock(lastLockDayMs, day1Unlock2Ms))

        // 4. 朝4時を跨いだ翌日のロック解除は再び発動可能
        assertTrue(DailyGoalManager.shouldTriggerMorningWakeupLock(lastLockDayMs, day2UnlockMs))
    }

    @Test
    fun testTimelineGraphCalculator_formatWithBaseHour4() {
        // 朝4時基準の場合、secondOfDay = 0 は "04:00"
        assertEquals("04:00", TimelineGraphCalculator.formatHourMinute(0, baseHour = 4))
        assertEquals("04:00:00", TimelineGraphCalculator.formatSecondOfDay(0, baseHour = 4))

        // secondOfDay = 3600 (1時間後) は "05:00"
        assertEquals("05:00", TimelineGraphCalculator.formatHourMinute(3600, baseHour = 4))

        // secondOfDay = 72000 (20時間後 = 深夜24:00) は "00:00"
        assertEquals("00:00", TimelineGraphCalculator.formatHourMinute(72000, baseHour = 4))

        // secondOfDay = 86400 (24時間後 = 翌朝04:00) は "04:00"
        assertEquals("04:00", TimelineGraphCalculator.formatHourMinute(86400, baseHour = 4))
    }

    @Test
    fun testIsSleepBlockAvailable_between22And4() {
        // 21:59:59 -> 利用不可
        val t2159 = createTimestamp(2026, 10, 27, 21, 59, 59)
        assertFalse(DailyGoalManager.isSleepBlockAvailable(t2159, timeZone))

        // 22:00:00 -> 利用可能
        val t2200 = createTimestamp(2026, 10, 27, 22, 0, 0)
        assertTrue(DailyGoalManager.isSleepBlockAvailable(t2200, timeZone))

        // 23:30:00 -> 利用可能
        val t2330 = createTimestamp(2026, 10, 27, 23, 30, 0)
        assertTrue(DailyGoalManager.isSleepBlockAvailable(t2330, timeZone))

        // 00:00:00 -> 利用可能
        val t0000 = createTimestamp(2026, 10, 28, 0, 0, 0)
        assertTrue(DailyGoalManager.isSleepBlockAvailable(t0000, timeZone))

        // 03:59:59 -> 利用可能
        val t0359 = createTimestamp(2026, 10, 28, 3, 59, 59)
        assertTrue(DailyGoalManager.isSleepBlockAvailable(t0359, timeZone))

        // 04:00:00 -> 利用不可（朝4時リセットで終了）
        val t0400 = createTimestamp(2026, 10, 28, 4, 0, 0)
        assertFalse(DailyGoalManager.isSleepBlockAvailable(t0400, timeZone))

        // 12:00:00 -> 利用不可
        val t1200 = createTimestamp(2026, 10, 28, 12, 0, 0)
        assertFalse(DailyGoalManager.isSleepBlockAvailable(t1200, timeZone))
    }
}
