package jp.cura.savetime.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HourlyBudgetManagerTest {

    private lateinit var manager: HourlyBudgetManager
    private val timeZone = TimeZone.getTimeZone("Asia/Tokyo")

    private fun createTimestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Before
    fun setUp() {
        manager = HourlyBudgetManager(context = null)
    }

    @Test
    fun testHourlyChargeCalculation_proportionalToWeights() {
        val dailyGoalSeconds = 10_800L // 3時間 (10800秒)
        val template = HourlyBudgetManager.TEMPLATE_WEEKDAY // totalWeight = 22

        // 深夜2時 (weight = 0) -> チャージ0
        val charge2Am = HourlyBudgetManager.calculateHourlyChargeSeconds(dailyGoalSeconds, 2, template)
        assertEquals(0L, charge2Am)

        // 朝7時 (weight = 1) -> 10800 * (1 / 22) = 490秒
        val charge7Am = HourlyBudgetManager.calculateHourlyChargeSeconds(dailyGoalSeconds, 7, template)
        val expected7Am = (10800.0 * (1.0 / 22.0)).toLong()
        assertEquals(expected7Am, charge7Am)

        // 夜19時 (weight = 3) -> 10800 * (3 / 22) = 1472秒
        val charge19Pm = HourlyBudgetManager.calculateHourlyChargeSeconds(dailyGoalSeconds, 19, template)
        val expected19Pm = (10800.0 * (3.0 / 22.0)).toLong()
        assertEquals(expected19Pm, charge19Pm)
    }

    @Test
    fun testSavingsRollover_carriesUnusedTimeToNextHour() {
        val dailyGoalSeconds = 10_800L
        val templateType = ScheduleTemplateType.WEEKDAY_CLASS

        // 17:00 (1時間目) にアクセス: weight = 2 (charge = 10800 * (2/22) = 981s)
        val time17 = createTimestamp(2026, 10, 28, 17, 10)
        val status17 = manager.updateHourlyBudget(
            nowMs = time17,
            dailyGoalSeconds = dailyGoalSeconds,
            templateType = templateType,
            previousHourUsedSeconds = 0L,
            currentHourUsageSeconds = 200L,
            timeZone = timeZone
        )

        val charge17 = status17.hourlyChargeSeconds
        assertTrue("Charge at 17:00 should be positive", charge17 > 0)
        assertEquals(0L, status17.carriedSavingsSeconds)
        assertEquals(charge17 - 200L, status17.remainingSeconds)

        // 18:00 (次の時間へ進む): 前の時間(17時)の実際の利用が 300秒だったとする
        // 余り時間 = charge17 - 300秒 が貯金として繰り越される
        val time18 = createTimestamp(2026, 10, 28, 18, 5)
        val actualUsedIn17 = 300L
        val expectedSavings = charge17 - actualUsedIn17

        val status18 = manager.updateHourlyBudget(
            nowMs = time18,
            dailyGoalSeconds = dailyGoalSeconds,
            templateType = templateType,
            previousHourUsedSeconds = actualUsedIn17,
            currentHourUsageSeconds = 50L,
            timeZone = timeZone
        )

        assertEquals(expectedSavings, status18.carriedSavingsSeconds)
        val expectedTotalAvailable18 = status18.hourlyChargeSeconds + expectedSavings
        assertEquals(expectedTotalAvailable18, status18.totalAvailableBudgetSeconds)
        assertEquals(expectedTotalAvailable18 - 50L, status18.remainingSeconds)
    }

    @Test
    fun testDayBoundaryAt4Am_resetsSavingsPool() {
        val dailyGoalSeconds = 10_800L
        val templateType = ScheduleTemplateType.WEEKDAY_CLASS

        // 深夜 03:30 (前日の論理日内): 未使用枠あり
        val time3Am = createTimestamp(2026, 10, 29, 3, 30)
        manager.updateHourlyBudget(
            nowMs = time3Am,
            dailyGoalSeconds = dailyGoalSeconds,
            templateType = templateType,
            previousHourUsedSeconds = 0L,
            currentHourUsageSeconds = 0L,
            timeZone = timeZone
        )

        // 朝 04:00 (論理日リセットの瞬間)
        val time4Am = createTimestamp(2026, 10, 29, 4, 5)
        val status4Am = manager.updateHourlyBudget(
            nowMs = time4Am,
            dailyGoalSeconds = dailyGoalSeconds,
            templateType = templateType,
            previousHourUsedSeconds = 0L,
            currentHourUsageSeconds = 0L,
            timeZone = timeZone
        )

        // 朝4時リセットにより前日の余り貯金は新日へ持ち越さず0にリセットされる
        assertEquals(0L, status4Am.carriedSavingsSeconds)
    }
}
