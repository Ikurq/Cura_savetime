package jp.cura.savetime.manager

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 連続利用による強制休憩画面ロック、深夜自動就寝ロック、および緊急解除クールダウンの管理クラス
 */
class ScreenLockManager(
    var intervalTriggerMinutes: Long = DEFAULT_INTERVAL_TRIGGER_MINUTES,
    var intervalDurationSeconds: Int = DEFAULT_INTERVAL_DURATION_SECONDS,
    var nightTriggerMinutes: Long = DEFAULT_NIGHT_TRIGGER_MINUTES,
    var nightDurationSeconds: Int = DEFAULT_NIGHT_DURATION_SECONDS,
    var cooldownMinutes: Long = DEFAULT_COOLDOWN_MINUTES,
    var isIntervalLockEnabled: Boolean = true,
    var isNightLockEnabled: Boolean = true
) {
    companion object {
        const val PREFS_NAME = "cura_screen_lock_prefs"
        const val KEY_INTERVAL_ENABLED = "key_interval_enabled"
        const val KEY_INTERVAL_TRIGGER_MINUTES = "key_interval_trigger_minutes"
        const val KEY_INTERVAL_DURATION_SECONDS = "key_interval_duration_seconds"
        const val KEY_NIGHT_ENABLED = "key_night_enabled"
        const val KEY_ACTIVE_LOCK_END_TIME_MS = "key_active_lock_end_time_ms"
        const val KEY_LAST_LOCK_TRIGGER_MS = "key_last_lock_trigger_ms"
        const val KEY_LAST_EMERGENCY_UNLOCK_MS = "key_last_emergency_unlock_ms"

        const val DEFAULT_INTERVAL_TRIGGER_MINUTES = 45L
        const val DEFAULT_INTERVAL_DURATION_SECONDS = 120 // 2分間
        const val DEFAULT_NIGHT_TRIGGER_MINUTES = 20L
        const val DEFAULT_NIGHT_DURATION_SECONDS = 180 // 3分間
        const val DEFAULT_COOLDOWN_MINUTES = 15L // 15分間再発動防止

        const val NIGHT_START_HOUR = 23
        const val NIGHT_END_HOUR = 6

        fun fromContext(context: Context): ScreenLockManager {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mgr = ScreenLockManager(
                intervalTriggerMinutes = prefs.getLong(KEY_INTERVAL_TRIGGER_MINUTES, DEFAULT_INTERVAL_TRIGGER_MINUTES),
                intervalDurationSeconds = prefs.getInt(KEY_INTERVAL_DURATION_SECONDS, DEFAULT_INTERVAL_DURATION_SECONDS),
                nightTriggerMinutes = DEFAULT_NIGHT_TRIGGER_MINUTES,
                nightDurationSeconds = DEFAULT_NIGHT_DURATION_SECONDS,
                cooldownMinutes = DEFAULT_COOLDOWN_MINUTES,
                isIntervalLockEnabled = prefs.getBoolean(KEY_INTERVAL_ENABLED, true),
                isNightLockEnabled = prefs.getBoolean(KEY_NIGHT_ENABLED, true)
            )
            mgr.lastLockTriggerMs = prefs.getLong(KEY_LAST_LOCK_TRIGGER_MS, 0L)
            mgr.lastEmergencyUnlockMs = prefs.getLong(KEY_LAST_EMERGENCY_UNLOCK_MS, 0L)
            mgr.activeLockEndTimeMs = prefs.getLong(KEY_ACTIVE_LOCK_END_TIME_MS, 0L)
            return mgr
        }

        fun saveActiveLockEndTime(context: Context, endTimeMs: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_ACTIVE_LOCK_END_TIME_MS, endTimeMs)
                .apply()
        }

        fun getActiveLockEndTime(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_ACTIVE_LOCK_END_TIME_MS, 0L)
        }

        fun clearActiveLockEndTime(context: Context) {
            saveActiveLockEndTime(context, 0L)
        }
    }

    var lastLockTriggerMs: Long = 0L
    var lastEmergencyUnlockMs: Long = 0L
    var activeLockEndTimeMs: Long = 0L

    /**
     * 連続使用時間に基づくインターバル強制休憩ロックの発動判定
     */
    fun shouldTriggerIntervalLock(
        continuousMinutes: Long,
        nowMs: Long = System.currentTimeMillis(),
        isInCall: Boolean = false,
        isAlarmActive: Boolean = false
    ): Boolean {
        if (!isIntervalLockEnabled) return false
        if (isInCall || isAlarmActive) return false
        if (continuousMinutes < intervalTriggerMinutes) return false

        // クールダウン期間内は発動を抑止
        val cooldownMs = cooldownMinutes * 60 * 1000L
        if (nowMs - lastLockTriggerMs < cooldownMs) return false
        if (nowMs - lastEmergencyUnlockMs < cooldownMs) return false

        return true
    }

    /**
     * 深夜帯（23:00 - 06:00）における就寝自動ロックの発動判定
     */
    fun shouldTriggerNightLock(
        continuousMinutes: Long,
        calendar: Calendar = Calendar.getInstance(),
        nowMs: Long = System.currentTimeMillis(),
        isInCall: Boolean = false,
        isAlarmActive: Boolean = false
    ): Boolean {
        if (!isNightLockEnabled) return false
        if (isInCall || isAlarmActive) return false

        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val isNightTime = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        if (!isNightTime) return false

        if (continuousMinutes < nightTriggerMinutes) return false

        val cooldownMs = cooldownMinutes * 60 * 1000L
        if (nowMs - lastLockTriggerMs < cooldownMs) return false
        if (nowMs - lastEmergencyUnlockMs < cooldownMs) return false

        return true
    }

    /**
     * ロック発動の記録
     */
    fun recordLockTriggered(nowMs: Long = System.currentTimeMillis(), context: Context? = null) {
        lastLockTriggerMs = nowMs
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(KEY_LAST_LOCK_TRIGGER_MS, nowMs)
            ?.apply()
    }

    /**
     * ユーザーによる緊急解除の記録（再発動を防止するためのクールダウン開始）
     */
    fun recordEmergencyUnlock(nowMs: Long = System.currentTimeMillis(), context: Context? = null) {
        lastEmergencyUnlockMs = nowMs
        activeLockEndTimeMs = 0L
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putLong(KEY_LAST_EMERGENCY_UNLOCK_MS, nowMs)
            ?.putLong(KEY_ACTIVE_LOCK_END_TIME_MS, 0L)
            ?.apply()
    }

    /**
     * ロック終了予定時刻に基づく残り秒数の計算
     */
    fun calculateRemainingSeconds(endTimeMs: Long, nowMs: Long = System.currentTimeMillis()): Int {
        if (endTimeMs <= nowMs) return 0
        return ((endTimeMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()
    }

    /**
     * 設定の保存
     */
    fun saveSettings(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_INTERVAL_ENABLED, isIntervalLockEnabled)
            .putLong(KEY_INTERVAL_TRIGGER_MINUTES, intervalTriggerMinutes)
            .putInt(KEY_INTERVAL_DURATION_SECONDS, intervalDurationSeconds)
            .putBoolean(KEY_NIGHT_ENABLED, isNightLockEnabled)
            .apply()
    }
}
