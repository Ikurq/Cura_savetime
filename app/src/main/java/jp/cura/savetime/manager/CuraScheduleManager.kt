package jp.cura.savetime.manager

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * テンプレート種別（平日/授業あり vs 休日/自由）
 */
enum class ScheduleTemplateType(val displayName: String) {
    WEEKDAY_CLASS("平日・授業あり"),
    HOLIDAY_FREE("休日・フリー")
}

/**
 * テンプレート自動判定モード
 */
enum class ScheduleMode(val displayName: String) {
    AUTO("自動（カレンダー/曜日連動）"),
    FORCE_WEEKDAY("平日固定"),
    FORCE_HOLIDAY("休日固定")
}

/**
 * 内部カレンダー・ToDo予定データ
 */
data class ScheduleEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String, // "YYYY-MM-DD"
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 17,
    val endMinute: Int = 0,
    val isClassOrWork: Boolean = true
)

/**
 * Cura内部カレンダー/ToDoデータと連携し、その日のスケジュール種別を判定するマネージャー
 */
class CuraScheduleManager(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cura_schedule_prefs"
        private const val KEY_SCHEDULE_EVENTS = "schedule_events_json"
        private const val KEY_SCHEDULE_MODE = "schedule_mode"

        fun formatDate(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                this.timeZone = timeZone
            }
            return sdf.format(nowMs)
        }
    }

    private val memoryEvents = mutableListOf<ScheduleEvent>()
    private var memoryMode = ScheduleMode.AUTO

    init {
        loadEvents()
    }

    private fun loadEvents() {
        val p = prefs ?: return
        val modeStr = p.getString(KEY_SCHEDULE_MODE, ScheduleMode.AUTO.name) ?: ScheduleMode.AUTO.name
        memoryMode = try {
            ScheduleMode.valueOf(modeStr)
        } catch (_: Exception) {
            ScheduleMode.AUTO
        }

        val jsonStr = p.getString(KEY_SCHEDULE_EVENTS, null) ?: return
        try {
            memoryEvents.clear()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                memoryEvents.add(
                    ScheduleEvent(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        date = obj.optString("date", ""),
                        startHour = obj.optInt("startHour", 9),
                        startMinute = obj.optInt("startMinute", 0),
                        endHour = obj.optInt("endHour", 17),
                        endMinute = obj.optInt("endMinute", 0),
                        isClassOrWork = obj.optBoolean("isClassOrWork", true)
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun saveEvents() {
        val p = prefs ?: return
        try {
            val array = JSONArray()
            for (ev in memoryEvents) {
                val obj = JSONObject().apply {
                    put("id", ev.id)
                    put("title", ev.title)
                    put("date", ev.date)
                    put("startHour", ev.startHour)
                    put("startMinute", ev.startMinute)
                    put("endHour", ev.endHour)
                    put("endMinute", ev.endMinute)
                    put("isClassOrWork", ev.isClassOrWork)
                }
                array.put(obj)
            }
            p.edit()
                .putString(KEY_SCHEDULE_EVENTS, array.toString())
                .putString(KEY_SCHEDULE_MODE, memoryMode.name)
                .apply()
        } catch (_: Exception) {}
    }

    fun getScheduleMode(): ScheduleMode = memoryMode

    fun setScheduleMode(mode: ScheduleMode) {
        memoryMode = mode
        saveEvents()
    }

    fun addEvent(event: ScheduleEvent) {
        memoryEvents.removeAll { it.id == event.id }
        memoryEvents.add(event)
        saveEvents()
    }

    fun removeEvent(id: String) {
        memoryEvents.removeAll { it.id == id }
        saveEvents()
    }

    fun getEventsForDate(dateStr: String): List<ScheduleEvent> {
        return memoryEvents.filter { it.date == dateStr }
    }

    /**
     * 指定時刻のスケジュールテンプレート種別を判定
     */
    fun determineTemplate(
        nowMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ScheduleTemplateType {
        when (memoryMode) {
            ScheduleMode.FORCE_WEEKDAY -> return ScheduleTemplateType.WEEKDAY_CLASS
            ScheduleMode.FORCE_HOLIDAY -> return ScheduleTemplateType.HOLIDAY_FREE
            ScheduleMode.AUTO -> {
                // 1. カレンダー登録予定の確認
                val dateStr = formatDate(nowMs, timeZone)
                val dayEvents = getEventsForDate(dateStr)
                if (dayEvents.isNotEmpty()) {
                    val hasClassOrWork = dayEvents.any { it.isClassOrWork }
                    return if (hasClassOrWork) {
                        ScheduleTemplateType.WEEKDAY_CLASS
                    } else {
                        ScheduleTemplateType.HOLIDAY_FREE
                    }
                }

                // 2. 登録予定がない場合は曜日で判定（月〜金: 平日, 土日: 休日）
                val cal = Calendar.getInstance(timeZone).apply {
                    timeInMillis = nowMs
                }
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                return when (dayOfWeek) {
                    Calendar.SATURDAY, Calendar.SUNDAY -> ScheduleTemplateType.HOLIDAY_FREE
                    else -> ScheduleTemplateType.WEEKDAY_CLASS
                }
            }
        }
    }
}
