package jp.cura.savetime.manager

import jp.cura.savetime.model.AppLimitConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLimitManagerTest {

    private lateinit var appLimitManager: AppLimitManager

    @Before
    fun setUp() {
        appLimitManager = AppLimitManager(
            prefs = null,
            cooldownMinutes = 5L
        )
    }

    @Test
    fun testSetAndGetLimit() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            blockDurationSeconds = 180,
            isEnabled = true
        )
        appLimitManager.setLimit(config)

        val retrieved = appLimitManager.getLimit("com.twitter.android")
        assertNotNull(retrieved)
        assertEquals("com.twitter.android", retrieved?.packageName)
        assertEquals("X", retrieved?.appName)
        assertEquals(3L, retrieved?.limitMinutes)
        assertEquals(180, retrieved?.blockDurationSeconds)
        assertTrue(retrieved?.isEnabled == true)
    }

    @Test
    fun testRemoveLimit() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            blockDurationSeconds = 180
        )
        appLimitManager.setLimit(config)
        assertNotNull(appLimitManager.getLimit("com.twitter.android"))

        appLimitManager.removeLimit("com.twitter.android")
        assertNull(appLimitManager.getLimit("com.twitter.android"))
    }

    @Test
    fun testToggleLimit() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            isEnabled = true
        )
        appLimitManager.setLimit(config)

        appLimitManager.toggleLimit("com.twitter.android", false)
        assertFalse(appLimitManager.getLimit("com.twitter.android")!!.isEnabled)

        appLimitManager.toggleLimit("com.twitter.android", true)
        assertTrue(appLimitManager.getLimit("com.twitter.android")!!.isEnabled)
    }

    @Test
    fun testTriggerAppLimit_triggersWhenContinuousMinutesReached() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            blockDurationSeconds = 180
        )
        appLimitManager.setLimit(config)

        val now = 1_000_000_000L

        // 3分未満 (2分59秒 = 179,000ms) では発動しない
        val belowResult = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 179_000L,
            nowMs = now
        )
        assertNull(belowResult)

        // 3分到達 (180,000ms) で発動
        val reachResult = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 180_000L,
            nowMs = now
        )
        assertNotNull(reachResult)
        assertEquals(3L, reachResult?.limitMinutes)
        assertEquals(180, reachResult?.blockDurationSeconds)

        // 3分超過 (5分 = 300,000ms) でも発動
        val overResult = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 300_000L,
            nowMs = now
        )
        assertNotNull(overResult)
    }

    @Test
    fun testTriggerAppLimit_doesNotTriggerWhenUnregisteredPackage() {
        val now = 1_000_000_000L
        val result = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.unregistered.app",
            continuousDurationMs = 600_000L,
            nowMs = now
        )
        assertNull(result)
    }

    @Test
    fun testTriggerAppLimit_doesNotTriggerWhenDisabled() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            isEnabled = false
        )
        appLimitManager.setLimit(config)

        val now = 1_000_000_000L
        val result = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 200_000L,
            nowMs = now
        )
        assertNull(result)
    }

    @Test
    fun testTriggerAppLimit_suppressedDuringCallOrAlarm() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L
        )
        appLimitManager.setLimit(config)

        val now = 1_000_000_000L

        // 通話中
        val callResult = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 200_000L,
            nowMs = now,
            isInCall = true,
            isAlarmActive = false
        )
        assertNull(callResult)

        // アラーム中
        val alarmResult = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 200_000L,
            nowMs = now,
            isInCall = false,
            isAlarmActive = true
        )
        assertNull(alarmResult)
    }

    @Test
    fun testTriggerAppLimit_doesNotTriggerDuringCooldown() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L
        )
        appLimitManager.setLimit(config)

        val triggerTime = 1_000_000_000L
        appLimitManager.recordAppLockTriggered("com.twitter.android", nowMs = triggerTime)

        // クールダウン期間内 (3分後、クールダウンは5分)
        val threeMinutesLater = triggerTime + 3 * 60 * 1000L
        val suppressed = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 200_000L,
            nowMs = threeMinutesLater
        )
        assertNull(suppressed)

        // クールダウン経過後 (6分後)
        val sixMinutesLater = triggerTime + 6 * 60 * 1000L
        val allowed = appLimitManager.shouldTriggerAppLimitLock(
            packageName = "com.twitter.android",
            continuousDurationMs = 200_000L,
            nowMs = sixMinutesLater
        )
        assertNotNull(allowed)
    }

    @Test
    fun testTriggerAppLimit_suppressedAfterEmergencyUnlock() {
        val config = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L
        )
        appLimitManager.setLimit(config)

        val unlockTime = 1_000_000_000L
        appLimitManager.recordAppEmergencyUnlock("com.twitter.android", nowMs = unlockTime)

        // 緊急解除後4分 (クールダウン以内)
        val fourMinutesLater = unlockTime + 4 * 60 * 1000L
        assertNull(
            appLimitManager.shouldTriggerAppLimitLock(
                packageName = "com.twitter.android",
                continuousDurationMs = 200_000L,
                nowMs = fourMinutesLater
            )
        )

        // 緊急解除後6分 (クールダウン後)
        val sixMinutesLater = unlockTime + 6 * 60 * 1000L
        assertNotNull(
            appLimitManager.shouldTriggerAppLimitLock(
                packageName = "com.twitter.android",
                continuousDurationMs = 200_000L,
                nowMs = sixMinutesLater
            )
        )
    }

    @Test
    fun testMultipleApps_independentLimits() {
        val xConfig = AppLimitConfig(
            packageName = "com.twitter.android",
            appName = "X",
            limitMinutes = 3L,
            blockDurationSeconds = 180
        )
        val ytConfig = AppLimitConfig(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            limitMinutes = 10L,
            blockDurationSeconds = 300
        )
        appLimitManager.setLimit(xConfig)
        appLimitManager.setLimit(ytConfig)

        val now = 1_000_000_000L

        // 4分 (240,000ms) の場合: X (3分) は発動、YouTube (10分) は未発動
        val xResult = appLimitManager.shouldTriggerAppLimitLock("com.twitter.android", 240_000L, now)
        val ytResult = appLimitManager.shouldTriggerAppLimitLock("com.google.android.youtube", 240_000L, now)

        assertNotNull(xResult)
        assertEquals(3L, xResult?.limitMinutes)
        assertNull(ytResult)

        // YouTube が 11分 (660,000ms) に達したら発動
        val ytReached = appLimitManager.shouldTriggerAppLimitLock("com.google.android.youtube", 660_000L, now)
        assertNotNull(ytReached)
        assertEquals(10L, ytReached?.limitMinutes)
        assertEquals(300, ytReached?.blockDurationSeconds)
    }

    @Test
    fun testStartAppBlockAndRemainingTime() {
        val now = 1_000_000_000L
        val pkg = "com.twitter.android"

        assertFalse(appLimitManager.isAppBlocked(pkg, now))
        assertEquals(0, appLimitManager.getBlockRemainingSeconds(pkg, now))

        // 3分（180秒）ブロック開始
        appLimitManager.startAppBlock(pkg, 180, now)
        assertTrue(appLimitManager.isAppBlocked(pkg, now))
        assertEquals(180, appLimitManager.getBlockRemainingSeconds(pkg, now))

        // 100秒後
        val hundredSecLater = now + 100_000L
        assertTrue(appLimitManager.isAppBlocked(pkg, hundredSecLater))
        assertEquals(80, appLimitManager.getBlockRemainingSeconds(pkg, hundredSecLater))

        // 180秒経過
        val endedTime = now + 180_000L
        assertFalse(appLimitManager.isAppBlocked(pkg, endedTime))
        assertEquals(0, appLimitManager.getBlockRemainingSeconds(pkg, endedTime))
    }

    @Test
    fun testClearAppBlock_unblocksImmediately() {
        val now = 1_000_000_000L
        val pkg = "com.twitter.android"

        appLimitManager.startAppBlock(pkg, 180, now)
        assertTrue(appLimitManager.isAppBlocked(pkg, now))

        appLimitManager.clearAppBlock(pkg)
        assertFalse(appLimitManager.isAppBlocked(pkg, now))
        assertEquals(0, appLimitManager.getBlockRemainingSeconds(pkg, now))
    }

    @Test
    fun testEmergencyUnlock_clearsActiveBlock() {
        val now = 1_000_000_000L
        val pkg = "com.twitter.android"

        appLimitManager.startAppBlock(pkg, 180, now)
        assertTrue(appLimitManager.isAppBlocked(pkg, now))

        appLimitManager.recordAppEmergencyUnlock(pkg, now)
        assertFalse(appLimitManager.isAppBlocked(pkg, now))
    }

    @Test
    fun testIndependentAppBlocking_onlyTargetAppIsBlocked() {
        val now = 1_000_000_000L
        val xPkg = "com.twitter.android"
        val ytPkg = "com.google.android.youtube"

        // Xだけをブロック
        appLimitManager.startAppBlock(xPkg, 180, now)

        assertTrue(appLimitManager.isAppBlocked(xPkg, now))
        assertFalse(appLimitManager.isAppBlocked(ytPkg, now)) // YouTubeはブロックされない
    }
}
