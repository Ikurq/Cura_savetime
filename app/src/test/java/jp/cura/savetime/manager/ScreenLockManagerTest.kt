package jp.cura.savetime.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class ScreenLockManagerTest {

    private lateinit var screenLockManager: ScreenLockManager

    @Before
    fun setUp() {
        screenLockManager = ScreenLockManager(
            intervalTriggerMinutes = 45L,
            intervalDurationSeconds = 120,
            nightTriggerMinutes = 20L,
            nightDurationSeconds = 180,
            cooldownMinutes = 15L
        )
    }

    @Test
    fun testIntervalLock_triggersWhenContinuousMinutesReached() {
        val now = 1_000_000_000L

        // 閾値未満（44分）では発動しない
        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 44L,
                nowMs = now
            )
        )

        // 閾値到達（45分）で発動
        assertTrue(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 45L,
                nowMs = now
            )
        )

        // 閾値超過（50分）でも発動
        assertTrue(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 50L,
                nowMs = now
            )
        )
    }

    @Test
    fun testIntervalLock_doesNotTriggerWhenDisabled() {
        screenLockManager.isIntervalLockEnabled = false
        val now = 1_000_000_000L

        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 45L,
                nowMs = now
            )
        )
    }

    @Test
    fun testIntervalLock_doesNotTriggerDuringCooldown() {
        val triggerTime = 1_000_000_000L
        screenLockManager.recordLockTriggered(nowMs = triggerTime)

        // 10分後（クールダウン15分以内）は再発動しない
        val tenMinutesLater = triggerTime + 10 * 60 * 1000L
        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 60L,
                nowMs = tenMinutesLater
            )
        )

        // 16分後（クールダウン終了後）は再発動可能
        val sixteenMinutesLater = triggerTime + 16 * 60 * 1000L
        assertTrue(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 45L,
                nowMs = sixteenMinutesLater
            )
        )
    }

    @Test
    fun testEmergencyUnlock_suppressesTriggerDuringCooldown() {
        val unlockTime = 1_000_000_000L
        screenLockManager.recordEmergencyUnlock(nowMs = unlockTime)

        // 緊急解除直後（5分後）は再発動しない
        val fiveMinutesLater = unlockTime + 5 * 60 * 1000L
        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 50L,
                nowMs = fiveMinutesLater
            )
        )

        // クールダウン経過後は発動可能
        val twentyMinutesLater = unlockTime + 20 * 60 * 1000L
        assertTrue(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 45L,
                nowMs = twentyMinutesLater
            )
        )
    }

    @Test
    fun testIntervalLock_suppressedDuringCallOrAlarm() {
        val now = 1_000_000_000L

        // 通話中は発動しない
        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 50L,
                nowMs = now,
                isInCall = true,
                isAlarmActive = false
            )
        )

        // アラーム中は発動しない
        assertFalse(
            screenLockManager.shouldTriggerIntervalLock(
                continuousMinutes = 50L,
                nowMs = now,
                isInCall = false,
                isAlarmActive = true
            )
        )
    }

    @Test
    fun testNightLock_triggersOnlyDuringNightHours() {
        val calNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 30)
        }
        val calDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }

        // 深夜23:30、20分到達で発動
        assertTrue(
            screenLockManager.shouldTriggerNightLock(
                continuousMinutes = 20L,
                calendar = calNight,
                nowMs = calNight.timeInMillis
            )
        )

        // 昼間14:00は深夜ロック対象外
        assertFalse(
            screenLockManager.shouldTriggerNightLock(
                continuousMinutes = 20L,
                calendar = calDay,
                nowMs = calDay.timeInMillis
            )
        )
    }

    @Test
    fun testRemainingSecondsCalculation() {
        val now = 1_000_000_000L
        val endTimeFuture = now + 45 * 1000L
        val endTimePast = now - 10 * 1000L

        assertEquals(45, screenLockManager.calculateRemainingSeconds(endTimeFuture, now))
        assertEquals(0, screenLockManager.calculateRemainingSeconds(endTimePast, now))
        assertEquals(0, screenLockManager.calculateRemainingSeconds(0L, now))
    }
}
