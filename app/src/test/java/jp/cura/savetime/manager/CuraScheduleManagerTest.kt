package jp.cura.savetime.manager

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CuraScheduleManagerTest {

    private lateinit var manager: CuraScheduleManager
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
        manager = CuraScheduleManager(context = null)
    }

    @Test
    fun testDefaultDayOfWeekDetermination_weekdaysAreWeekdayClass() {
        // 2026-10-28 は水曜日 (Wednesday)
        val wednesdayMs = createTimestamp(2026, 10, 28, 14, 0)
        val template = manager.determineTemplate(wednesdayMs, timeZone)
        assertEquals(ScheduleTemplateType.WEEKDAY_CLASS, template)
    }

    @Test
    fun testDefaultDayOfWeekDetermination_weekendsAreHolidayFree() {
        // 2026-10-25 は日曜日 (Sunday)
        val sundayMs = createTimestamp(2026, 10, 25, 12, 0)
        val template = manager.determineTemplate(sundayMs, timeZone)
        assertEquals(ScheduleTemplateType.HOLIDAY_FREE, template)
    }

    @Test
    fun testCalendarEventLinked_overridesWeekendIfClassExists() {
        // 日曜日に講義・補講の予定を登録
        val sundayDateStr = "2026-10-25"
        val sundayMs = createTimestamp(2026, 10, 25, 10, 0)

        manager.addEvent(
            ScheduleEvent(
                id = "sunday_class",
                title = "大学 集中講義",
                date = sundayDateStr,
                isClassOrWork = true
            )
        )

        val template = manager.determineTemplate(sundayMs, timeZone)
        assertEquals(ScheduleTemplateType.WEEKDAY_CLASS, template)
    }

    @Test
    fun testCalendarEventLinked_overridesWeekdayIfMarkedAsHoliday() {
        // 水曜日を休日・全日休みとして登録
        val wednesdayDateStr = "2026-10-28"
        val wednesdayMs = createTimestamp(2026, 10, 28, 10, 0)

        manager.addEvent(
            ScheduleEvent(
                id = "wednesday_dayoff",
                title = "創立記念日・全日休校",
                date = wednesdayDateStr,
                isClassOrWork = false
            )
        )

        val template = manager.determineTemplate(wednesdayMs, timeZone)
        assertEquals(ScheduleTemplateType.HOLIDAY_FREE, template)
    }

    @Test
    fun testForcedMode_overridesCalendarAndDayOfWeek() {
        val wednesdayMs = createTimestamp(2026, 10, 28, 14, 0)

        manager.setScheduleMode(ScheduleMode.FORCE_HOLIDAY)
        assertEquals(ScheduleTemplateType.HOLIDAY_FREE, manager.determineTemplate(wednesdayMs, timeZone))

        val sundayMs = createTimestamp(2026, 10, 25, 12, 0)
        manager.setScheduleMode(ScheduleMode.FORCE_WEEKDAY)
        assertEquals(ScheduleTemplateType.WEEKDAY_CLASS, manager.determineTemplate(sundayMs, timeZone))
    }
}
