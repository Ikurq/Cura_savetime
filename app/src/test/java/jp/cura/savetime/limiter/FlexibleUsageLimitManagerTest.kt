package jp.cura.savetime.limiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class FlexibleUsageLimitManagerTest {

    private lateinit var limitManager: FlexibleUsageLimitManager

    @Before
    fun setUp() {
        limitManager = FlexibleUsageLimitManager(
            hourlyBudgetMinutes = 30L
        )
    }

    @Test
    fun testFiveMinuteReminder_triggersAtFiveMinuteMultiples() {
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(0L))
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(3L))
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(4L))

        // 5分で発火
        assertTrue(limitManager.shouldTriggerFiveMinuteReminder(5L))
        // 5分で再呼び出しされても重複発火しない
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(5L))

        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(6L))
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(9L))

        // 10分で発火
        assertTrue(limitManager.shouldTriggerFiveMinuteReminder(10L))
        assertFalse(limitManager.shouldTriggerFiveMinuteReminder(10L))

        // 15分で発火
        assertTrue(limitManager.shouldTriggerFiveMinuteReminder(15L))
    }

    @Test
    fun testActiveRule_detectsNightTimeAcrossMidnight() {
        val cal = Calendar.getInstance()

        // 23:30 (深夜帯)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 30)
        val ruleNight1 = limitManager.getActiveRule(cal)
        assertEquals("深夜制限", ruleNight1.name)
        assertEquals(20L, ruleNight1.maxContinuousMinutes)

        // 02:15 (深夜帯)
        cal.set(Calendar.HOUR_OF_DAY, 2)
        cal.set(Calendar.MINUTE, 15)
        val ruleNight2 = limitManager.getActiveRule(cal)
        assertEquals("深夜制限", ruleNight2.name)

        // 14:00 (通常帯)
        cal.set(Calendar.HOUR_OF_DAY, 14)
        cal.set(Calendar.MINUTE, 0)
        val ruleDay = limitManager.getActiveRule(cal)
        assertEquals("通常制限", ruleDay.name)
        assertEquals(45L, ruleDay.maxContinuousMinutes)
    }

    @Test
    fun testIsContinuousLimitExceeded_appliesTimezoneRules() {
        val cal = Calendar.getInstance()

        // 深夜23:30: 上限20分
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 30)
        assertFalse(limitManager.isContinuousLimitExceeded(19L, cal))
        assertTrue(limitManager.isContinuousLimitExceeded(20L, cal))
        assertTrue(limitManager.isContinuousLimitExceeded(30L, cal))

        // 昼間14:00: 上限45分
        cal.set(Calendar.HOUR_OF_DAY, 14)
        cal.set(Calendar.MINUTE, 0)
        assertFalse(limitManager.isContinuousLimitExceeded(25L, cal))
        assertFalse(limitManager.isContinuousLimitExceeded(44L, cal))
        assertTrue(limitManager.isContinuousLimitExceeded(45L, cal))
    }

    @Test
    fun testHourlyBudgetRemaining() {
        assertEquals(30L, limitManager.calculateRemainingHourlyBudget(0L))
        assertEquals(10L, limitManager.calculateRemainingHourlyBudget(20L))
        assertEquals(0L, limitManager.calculateRemainingHourlyBudget(30L))
        assertEquals(0L, limitManager.calculateRemainingHourlyBudget(45L))
    }

    @Test
    fun testIndependentAppAndScreenFiveMinuteTriggers() {
        // 画面連続 5分、アプリA 3分 -> 画面5分のみ発火
        assertFalse(limitManager.shouldTriggerAppFiveMinuteReminder("pkg.a", 3L))
        assertTrue(limitManager.shouldTriggerScreenFiveMinuteReminder(5L))
        assertFalse(limitManager.shouldTriggerScreenFiveMinuteReminder(5L))

        // 画面連続 7分、アプリA 5分 -> アプリAの5分が発火
        assertTrue(limitManager.shouldTriggerAppFiveMinuteReminder("pkg.a", 5L))
        assertFalse(limitManager.shouldTriggerAppFiveMinuteReminder("pkg.a", 5L))
        assertFalse(limitManager.shouldTriggerScreenFiveMinuteReminder(7L))

        // 別アプリBが5分に達した場合も独立して発火
        assertTrue(limitManager.shouldTriggerAppFiveMinuteReminder("pkg.b", 5L))

        // 画面がリセット（0分）されたら再度5分で発火可能
        assertFalse(limitManager.shouldTriggerScreenFiveMinuteReminder(0L))
        assertTrue(limitManager.shouldTriggerScreenFiveMinuteReminder(5L))
    }
}
