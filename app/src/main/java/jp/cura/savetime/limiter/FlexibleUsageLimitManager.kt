package jp.cura.savetime.limiter

import java.util.Calendar

/**
 * 時間帯ごとの制限ルール
 */
data class TimezoneRule(
    val name: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val maxContinuousMinutes: Long
)

/**
 * 柔軟な時間制限（時間帯別・1時間あたり予算・5分刻みリマインダー）の判定ロジック
 */
class FlexibleUsageLimitManager(
    val hourlyBudgetMinutes: Long = 30L, // 1時間あたりの許容利用分数
    val timezoneRules: List<TimezoneRule> = defaultRules()
) {
    companion object {
        fun defaultRules(): List<TimezoneRule> {
            return listOf(
                // 深夜帯 (23:00 - 06:00): 連続20分まで
                TimezoneRule("深夜制限", 23, 0, 6, 0, maxContinuousMinutes = 20L),
                // 通常帯 (その他): 連続45分まで
                TimezoneRule("通常制限", 6, 0, 23, 0, maxContinuousMinutes = 45L)
            )
        }
    }

    private var lastNotifiedFiveMinMark = 0L
    private var lastNotifiedScreenFiveMinMark = 0L
    private val lastNotifiedAppFiveMinMarks = mutableMapOf<String, Long>()

    /**
     * 5分刻みのリマインダー対象か判定（後方互換用）
     */
    fun shouldTriggerFiveMinuteReminder(continuousMinutes: Long): Boolean {
        if (continuousMinutes <= 0) {
            lastNotifiedFiveMinMark = 0L
            return false
        }

        if (continuousMinutes % 5L == 0L && continuousMinutes != lastNotifiedFiveMinMark) {
            lastNotifiedFiveMinMark = continuousMinutes
            return true
        }
        return false
    }

    /**
     * 【アプリ別】5分ごとのリマインダー判定（5分, 10分, 15分...）
     */
    fun shouldTriggerAppFiveMinuteReminder(packageName: String, appMinutes: Long): Boolean {
        if (packageName.isBlank() || appMinutes <= 0L) {
            if (packageName.isNotBlank()) {
                lastNotifiedAppFiveMinMarks.remove(packageName)
            }
            return false
        }
        val prev = lastNotifiedAppFiveMinMarks[packageName] ?: 0L
        if (appMinutes % 5L == 0L && appMinutes != prev) {
            lastNotifiedAppFiveMinMarks[packageName] = appMinutes
            return true
        }
        return false
    }

    /**
     * 【画面連続】5分ごとのリマインダー判定（5分, 10分, 15分...）
     */
    fun shouldTriggerScreenFiveMinuteReminder(screenMinutes: Long): Boolean {
        if (screenMinutes <= 0L) {
            lastNotifiedScreenFiveMinMark = 0L
            lastNotifiedAppFiveMinMarks.clear()
            return false
        }
        if (screenMinutes % 5L == 0L && screenMinutes != lastNotifiedScreenFiveMinMark) {
            lastNotifiedScreenFiveMinMark = screenMinutes
            return true
        }
        return false
    }

    /**
     * 現在時刻に適用される時間帯ルールを取得
     */
    fun getActiveRule(calendar: Calendar = Calendar.getInstance()): TimezoneRule {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val currentMins = hour * 60 + minute

        for (rule in timezoneRules) {
            val startMins = rule.startHour * 60 + rule.startMinute
            val endMins = rule.endHour * 60 + rule.endMinute

            if (startMins <= endMins) {
                // 同日内（例: 06:00 - 23:00）
                if (currentMins in startMins until endMins) {
                    return rule
                }
            } else {
                // 日跨ぎ（例: 23:00 - 06:00）
                if (currentMins >= startMins || currentMins < endMins) {
                    return rule
                }
            }
        }
        return timezoneRules.firstOrNull() ?: TimezoneRule("標準", 0, 0, 24, 0, 45L)
    }

    /**
     * 現在の連続利用が時間帯ルールの上限を超過しているか判定
     */
    fun isContinuousLimitExceeded(continuousMinutes: Long, calendar: Calendar = Calendar.getInstance()): Boolean {
        val rule = getActiveRule(calendar)
        return continuousMinutes >= rule.maxContinuousMinutes
    }

    /**
     * 1時間あたりの予算に対する残枠（分）を算出
     */
    fun calculateRemainingHourlyBudget(currentHourUsageMinutes: Long): Long {
        return (hourlyBudgetMinutes - currentHourUsageMinutes).coerceAtLeast(0L)
    }
}
