package jp.cura.savetime.manager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.TimeZone

/**
 * 朝4時リセット、先週実績ベースの自動目標設定、および朝イチ起床ロックの管理クラス
 */
object DailyGoalManager {

    private const val PREFS_NAME = "cura_daily_goal_prefs"
    private const val KEY_TODAY_GOAL_SECONDS = "today_goal_seconds"
    private const val KEY_LAST_GOAL_CALCULATED_DAY_MS = "last_goal_calculated_day_ms"
    private const val KEY_LAST_MORNING_LOCK_DAY_MS = "last_morning_lock_day_ms"

    const val LOGICAL_DAY_BASE_HOUR = 4
    const val DEFAULT_FALLBACK_GOAL_SECONDS = 3 * 3600L // 3時間 (10800秒)
    const val MINIMUM_GOAL_SECONDS = 30 * 60L // 最小目標: 30分
    const val REDUCTION_RATE = 0.10 // 先週から10%削減

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 朝4:00を基準とした「現在の論理日（Logical Day）」の開始エポックミリ秒を算出
     * 例: 2026-10-27 03:59 -> 2026-10-26 04:00:00.000
     * 例: 2026-10-27 04:00 -> 2026-10-27 04:00:00.000
     */
    fun getLogicalDayStartMs(
        nowMs: Long = System.currentTimeMillis(),
        baseHour: Int = LOGICAL_DAY_BASE_HOUR,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
        }
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        if (currentHour < baseHour) {
            // 朝4時前なら前日の4:00
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, baseHour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 保存されている本日の目標スマホ使用時間（秒）を取得
     */
    fun getTodayGoalSeconds(context: Context): Long {
        return getPrefs(context).getLong(KEY_TODAY_GOAL_SECONDS, DEFAULT_FALLBACK_GOAL_SECONDS)
    }

    /**
     * 必要に応じて今日の目標使用時間を自動算出・保存する
     * 朝4時を跨いだタイミングまたは未計算の場合に実行
     * - ちょうど7日前の4:00〜翌4:00のスマホ合計使用時間を取得
     * - 先週の実績から10%を削減した値を「今日の目標スマホ使用時間」として設定
     */
    fun updateDailyGoalIfNeeded(
        context: Context,
        usageStatsManager: UsageStatsManager?,
        nowMs: Long = System.currentTimeMillis(),
        ignoredPackages: Set<String> = emptySet()
    ): Long {
        val currentLogicalDayStart = getLogicalDayStartMs(nowMs)
        val prefs = getPrefs(context)
        val lastCalculatedDay = prefs.getLong(KEY_LAST_GOAL_CALCULATED_DAY_MS, 0L)

        if (lastCalculatedDay == currentLogicalDayStart) {
            return prefs.getLong(KEY_TODAY_GOAL_SECONDS, DEFAULT_FALLBACK_GOAL_SECONDS)
        }

        // 7日前の4:00〜翌4:00の区間
        val pastWeekStartMs = currentLogicalDayStart - 7 * 86_400_000L
        val pastWeekEndMs = pastWeekStartMs + 86_400_000L

        val pastUsageSec = calculatePastUsageSeconds(
            usageStatsManager = usageStatsManager,
            startMs = pastWeekStartMs,
            endMs = pastWeekEndMs,
            ignoredPackages = ignoredPackages
        )

        val newGoalSec = calculateGoalFromPastUsage(pastUsageSec)

        prefs.edit()
            .putLong(KEY_TODAY_GOAL_SECONDS, newGoalSec)
            .putLong(KEY_LAST_GOAL_CALCULATED_DAY_MS, currentLogicalDayStart)
            .apply()

        return newGoalSec
    }

    /**
     * 先週実績から10%を削減した目標秒数を算出（実績なし時はデフォルト、最小30分）
     */
    fun calculateGoalFromPastUsage(pastUsageSec: Long): Long {
        return if (pastUsageSec > 0) {
            val reduced = (pastUsageSec * (1.0 - REDUCTION_RATE)).toLong()
            maxOf(reduced, MINIMUM_GOAL_SECONDS)
        } else {
            DEFAULT_FALLBACK_GOAL_SECONDS
        }
    }

    /**
     * 指定期間の端末合計使用秒数を集計
     */
    @Suppress("DEPRECATION")
    fun calculatePastUsageSeconds(
        usageStatsManager: UsageStatsManager?,
        startMs: Long,
        endMs: Long,
        ignoredPackages: Set<String> = emptySet()
    ): Long {
        if (usageStatsManager == null || startMs >= endMs) return 0L
        val events = usageStatsManager.queryEvents(startMs, endMs) ?: return 0L
        val event = UsageEvents.Event()

        var totalSeconds = 0L
        var currentActiveStartMs: Long = 0L
        var currentPkg: String? = null

        fun closeSegment(timeMs: Long) {
            if (currentActiveStartMs > 0L && currentPkg != null && !ignoredPackages.contains(currentPkg)) {
                val durSec = ((timeMs - currentActiveStartMs) / 1000L).coerceIn(0L, 7200L)
                totalSeconds += durSec
            }
            currentActiveStartMs = 0L
            currentPkg = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    closeSegment(time)
                    if (!ignoredPackages.contains(pkg)) {
                        currentActiveStartMs = time
                        currentPkg = pkg
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    closeSegment(time)
                }
            }
        }
        closeSegment(endMs)
        return totalSeconds
    }

    /**
     * 朝4時以降の「その日最初のロック解除」として起床ロックを発動すべきか判定
     */
    fun shouldTriggerMorningWakeupLock(
        lastLockDayMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val logicalDayStart = getLogicalDayStartMs(nowMs)
        // 今日の論理日ですでに発動済みでなければ発動可能
        return lastLockDayMs < logicalDayStart
    }

    /**
     * 朝4時以降の「その日最初のロック解除」として起床ロックを発動すべきか判定
     */
    fun shouldTriggerMorningWakeupLock(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val lastLockDay = getPrefs(context).getLong(KEY_LAST_MORNING_LOCK_DAY_MS, 0L)
        return shouldTriggerMorningWakeupLock(lastLockDay, nowMs)
    }

    /**
     * 朝イチ起床ロックを発動したことを記録
     */
    fun recordMorningWakeupLockTriggered(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val logicalDayStart = getLogicalDayStartMs(nowMs)
        getPrefs(context).edit()
            .putLong(KEY_LAST_MORNING_LOCK_DAY_MS, logicalDayStart)
            .apply()
    }
}
