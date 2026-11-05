package jp.cura.savetime.manager

import android.content.Context
import android.content.SharedPreferences
import jp.cura.savetime.model.AppLimitConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * アプリごとの利用上限時間とブロック発動の判定・永続化を管理するマネージャー
 */
class AppLimitManager(
    private val prefs: SharedPreferences? = null,
    var cooldownMinutes: Long = DEFAULT_COOLDOWN_MINUTES
) {
    companion object {
        const val PREFS_NAME = "cura_app_limits_prefs"
        const val KEY_APP_LIMITS_JSON = "key_app_limits_json"
        const val DEFAULT_COOLDOWN_MINUTES = 5L // ロック発動後の再発動防止クールダウン（5分間）

        private val json = Json { ignoreUnknownKeys = true }

        fun fromContext(context: Context): AppLimitManager {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val manager = AppLimitManager(prefs)
            manager.loadFromPrefs()
            return manager
        }
    }

    private val limitsMap = mutableMapOf<String, AppLimitConfig>()
    private val lastTriggerMsMap = mutableMapOf<String, Long>()
    private val lastEmergencyUnlockMsMap = mutableMapOf<String, Long>()

    fun loadFromPrefs() {
        if (prefs == null) return
        val rawJson = prefs.getString(KEY_APP_LIMITS_JSON, null) ?: return
        try {
            val list = json.decodeFromString<List<AppLimitConfig>>(rawJson)
            limitsMap.clear()
            list.forEach { config ->
                limitsMap[config.packageName] = config
            }
        } catch (_: Exception) {
            // パース失敗時は現状を維持
        }
    }

    private fun saveToPrefs() {
        if (prefs == null) return
        val list = limitsMap.values.toList()
        try {
            val serialized = json.encodeToString(list)
            prefs.edit().putString(KEY_APP_LIMITS_JSON, serialized).apply()
        } catch (_: Exception) {
            // シリアライズ失敗時は無視
        }
    }

    fun getLimit(packageName: String): AppLimitConfig? {
        return limitsMap[packageName]
    }

    fun getAllLimits(): List<AppLimitConfig> {
        return limitsMap.values.sortedBy { it.appName }
    }

    fun setLimit(config: AppLimitConfig) {
        limitsMap[config.packageName] = config
        saveToPrefs()
    }

    fun removeLimit(packageName: String) {
        limitsMap.remove(packageName)
        lastTriggerMsMap.remove(packageName)
        lastEmergencyUnlockMsMap.remove(packageName)
        saveToPrefs()
    }

    fun toggleLimit(packageName: String, isEnabled: Boolean) {
        val current = limitsMap[packageName] ?: return
        limitsMap[packageName] = current.copy(isEnabled = isEnabled)
        saveToPrefs()
    }

    /**
     * アプリ制限ブロックを発動すべきかを判定する
     *
     * @param packageName 判定対象アプリのパッケージ名
     * @param continuousDurationMs 該当アプリの現在の連続使用時間（ミリ秒）
     * @param nowMs 現在時刻（ミリ秒）
     * @param isInCall 通話中フラグ
     * @param isAlarmActive アラーム鳴動中フラグ
     * @return トリガー条件を満たした場合は AppLimitConfig、それ以外は null
     */
    fun shouldTriggerAppLimitLock(
        packageName: String,
        continuousDurationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        isInCall: Boolean = false,
        isAlarmActive: Boolean = false
    ): AppLimitConfig? {
        val config = limitsMap[packageName] ?: return null
        if (!config.isEnabled) return null
        if (isInCall || isAlarmActive) return null

        val limitMs = config.limitMinutes * 60_000L
        if (continuousDurationMs < limitMs) return null

        val cooldownMs = cooldownMinutes * 60_000L
        val lastTrigger = lastTriggerMsMap[packageName] ?: 0L
        if (nowMs - lastTrigger < cooldownMs) return null

        val lastUnlock = lastEmergencyUnlockMsMap[packageName] ?: 0L
        if (nowMs - lastUnlock < cooldownMs) return null

        return config
    }

    /**
     * ロック発動の記録（クールダウン開始）
     */
    fun recordAppLockTriggered(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        lastTriggerMsMap[packageName] = nowMs
    }

    /**
     * 緊急解除の記録（クールダウン開始）
     */
    fun recordAppEmergencyUnlock(packageName: String, nowMs: Long = System.currentTimeMillis()) {
        lastEmergencyUnlockMsMap[packageName] = nowMs
    }

    /**
     * クールダウンと発動履歴のリセット
     */
    fun resetAppSessionCooldown(packageName: String) {
        lastTriggerMsMap.remove(packageName)
        lastEmergencyUnlockMsMap.remove(packageName)
    }
}
