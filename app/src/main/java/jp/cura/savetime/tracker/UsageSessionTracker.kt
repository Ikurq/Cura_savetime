package jp.cura.savetime.tracker

import jp.cura.savetime.model.AppSession
import jp.cura.savetime.model.ContinuousRecord
import jp.cura.savetime.model.ThresholdAlert

/**
 * アプリの「連続使用時間」を判定・追跡するコアトラッカー
 */
class UsageSessionTracker(
    val bufferDurationMs: Long = 60_000L,
    val thresholdMinutes: List<Long> = listOf(30L, 45L, 60L),
    val ignoredPackages: Set<String> = emptySet()
) {
    private var currentSession: AppSession? = null
    private val records = mutableMapOf<String, ContinuousRecord>()
    private val triggeredThresholds = mutableSetOf<Long>()

    fun getCurrentSession(): AppSession? = currentSession

    fun getRecords(): List<ContinuousRecord> {
        return records.values.sortedByDescending { it.maxContinuousMinutes }
    }

    /**
     * アプリがフォアグラウンドに起動または復帰した時のハンドラ
     */
    fun onAppForeground(packageName: String, appName: String, timestampMs: Long): ThresholdAlert? {
        if (ignoredPackages.contains(packageName)) {
            // ランチャー等の無視対象アプリを開いた場合、既存セッションをバッファモードへ移行
            if (currentSession != null && !currentSession!!.isBufferActive) {
                currentSession!!.isBufferActive = true
                currentSession!!.bufferStartedAtMs = timestampMs
            }
            return null
        }

        val session = currentSession
        if (session != null) {
            if (session.packageName == packageName) {
                // 同一アプリへの復帰
                session.isBufferActive = false
                session.lastActiveAtMs = timestampMs
                return checkThresholdAlert(timestampMs)
            } else {
                // 別アプリへの切り替え: 旧セッションを確定して新セッション開始
                finishSession(timestampMs)
            }
        }

        // 新規セッション開始
        currentSession = AppSession(
            packageName = packageName,
            appName = appName,
            startedAtMs = timestampMs,
            lastActiveAtMs = timestampMs
        )
        triggeredThresholds.clear()
        return null
    }

    /**
     * アプリがバックグラウンドに移行した時のハンドラ
     */
    fun onAppBackground(packageName: String, timestampMs: Long) {
        val session = currentSession
        if (session != null && session.packageName == packageName) {
            session.isBufferActive = true
            session.bufferStartedAtMs = timestampMs
        }
    }

    /**
     * 画面消灯時のハンドラ
     */
    fun onScreenOff(timestampMs: Long) {
        val session = currentSession
        if (session != null && !session.isBufferActive) {
            session.isBufferActive = true
            session.bufferStartedAtMs = timestampMs
        }
    }

    /**
     * 画面点灯時のハンドラ
     */
    fun onScreenOn(timestampMs: Long) {
        // 画面復帰時はタイマーの継続判定を行う
        onTick(timestampMs)
    }

    /**
     * 定周期ポーリング（例: 5秒ごと）での時間更新としきい値チェック
     */
    fun onTick(timestampMs: Long): ThresholdAlert? {
        val session = currentSession ?: return null

        if (session.isBufferActive) {
            // バッファ待機中
            val elapsedBuffer = timestampMs - session.bufferStartedAtMs
            if (elapsedBuffer > bufferDurationMs) {
                // バッファ時間（60秒）を超過 -> セッション終了
                finishSession(session.bufferStartedAtMs)
                return null
            }
            return null
        }

        // 通常継続中
        session.continuousDurationMs = (timestampMs - session.startedAtMs).coerceAtLeast(0L)
        session.lastActiveAtMs = timestampMs

        return checkThresholdAlert(timestampMs)
    }

    private fun checkThresholdAlert(timestampMs: Long): ThresholdAlert? {
        val session = currentSession ?: return null
        val durationMinutes = session.continuousDurationMs / (60 * 1000L)

        for (threshold in thresholdMinutes.sorted()) {
            if (durationMinutes >= threshold && !triggeredThresholds.contains(threshold)) {
                triggeredThresholds.add(threshold)
                return ThresholdAlert(
                    thresholdMinutes = threshold,
                    packageName = session.packageName,
                    appName = session.appName,
                    continuousMinutes = durationMinutes,
                    message = "${session.appName}を連続で${threshold}分使用中です。目を休めませんか？"
                )
            }
        }
        return null
    }

    private fun finishSession(endTimestampMs: Long) {
        val session = currentSession ?: return
        val totalMs = (endTimestampMs - session.startedAtMs).coerceAtLeast(0L)
        val durationMinutes = totalMs / (60 * 1000L)

        val record = records.getOrPut(session.packageName) {
            ContinuousRecord(
                packageName = session.packageName,
                appName = session.appName
            )
        }

        record.sessionCount += 1
        record.totalUsageMinutes += durationMinutes
        if (durationMinutes > record.maxContinuousMinutes) {
            record.maxContinuousMinutes = durationMinutes
        }

        currentSession = null
        triggeredThresholds.clear()
    }
}
