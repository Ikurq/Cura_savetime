package jp.cura.savetime.tracker

import jp.cura.savetime.model.AppSession
import jp.cura.savetime.model.ContinuousRecord
import jp.cura.savetime.model.ThresholdAlert
import jp.cura.savetime.model.TimelineEntry

/**
 * アプリの利用時間および「端末全体の連続スクリーンタイム」を追跡するトラッカー
 *
 * 特徴:
 * 1. 【1時間ごとの定時リセット】: 毎正時（例: 14:00, 15:00...）に累計セッションを自動確定・リセット。
 * 2. 【タイムライン履歴】: 直近のアプリ利用遷移と滞在秒数を保持（最大50件）。
 * 3. 【除外パッケージの動的判定】: 設定された除外アプリを無視。
 * 4. 【ザッピング累積】: スマホ操作中のアプリ行き来をすべて累積。
 */
class UsageSessionTracker(
    val bufferDurationMs: Long = 60_000L,
    val screenRestBufferMs: Long = 60_000L, // 画面消灯で休憩とみなす時間 (60秒)
    val thresholdMinutes: List<Long> = listOf(30L, 45L, 60L),
    var ignoredPackages: Set<String> = emptySet()
) {
    // 端末全体の連続スクリーンセッション
    private var screenSessionStartedAtMs: Long = 0L
    private var isScreenOff: Boolean = false
    private var screenOffStartedAtMs: Long = 0L

    // 1時間ごとのリセット用トラッキング（エポックミリ秒を 3600_000 で割った時間インデックス）
    private var currentHourBucket: Long = -1L

    // このスマホ操作セッション内で使用したアプリごとの累積セッション (パッケージ名 -> セッション)
    private val activeSessions = mutableMapOf<String, AppSession>()
    private var currentActivePackage: String? = null

    // タイムライン履歴（直近50件）
    private val timelineEntries = mutableListOf<TimelineEntry>()
    private var lastAppSwitchedAtMs: Long = 0L
    private var lastActiveAppInfo: Pair<String, String>? = null // (packageName, appName)

    // 過去の確定レコード & アラートトリガー履歴
    private val records = mutableMapOf<String, ContinuousRecord>()
    private val triggeredThresholds = mutableMapOf<String, MutableSet<Long>>()
    private val triggeredScreenThresholds = mutableSetOf<Long>()

    fun getCurrentSession(): AppSession? {
        val currentPkg = currentActivePackage ?: return null
        return activeSessions[currentPkg]
    }

    fun getTotalContinuousScreenDurationMs(now: Long = System.currentTimeMillis()): Long {
        if (screenSessionStartedAtMs == 0L) return 0L
        if (isScreenOff && (now - screenOffStartedAtMs > screenRestBufferMs)) {
            return 0L
        }
        return (now - screenSessionStartedAtMs).coerceAtLeast(0L)
    }

    fun getRecords(): List<ContinuousRecord> {
        return records.values.sortedByDescending { it.maxContinuousMinutes }
    }

    fun getTimeline(): List<TimelineEntry> {
        return timelineEntries.toList()
    }

    /**
     * アプリがフォアグラウンドに起動または復帰した時のハンドラ
     */
    fun onAppForeground(packageName: String, appName: String, timestampMs: Long): ThresholdAlert? {
        checkHourlyReset(timestampMs)

        // スクリーンセッションの開始または休憩明け復旧
        if (screenSessionStartedAtMs == 0L || (isScreenOff && timestampMs - screenOffStartedAtMs > screenRestBufferMs)) {
            resetAllSessions(timestampMs)
            screenSessionStartedAtMs = timestampMs
            triggeredScreenThresholds.clear()
        }
        isScreenOff = false

        if (ignoredPackages.contains(packageName)) {
            // 除外アプリ（ランチャーやユーザー設定除外）
            recordPreviousAppTimeline(timestampMs)
            currentActivePackage = null
            lastActiveAppInfo = null
            return null
        }

        val previousPkg = currentActivePackage
        if (previousPkg != null && previousPkg != packageName) {
            recordPreviousAppTimeline(timestampMs)

            val prevSession = activeSessions[previousPkg]
            if (prevSession != null) {
                prevSession.isBufferActive = true
                prevSession.bufferStartedAtMs = timestampMs
            }
        }

        currentActivePackage = packageName
        lastActiveAppInfo = Pair(packageName, appName)
        lastAppSwitchedAtMs = timestampMs

        var session = activeSessions[packageName]
        if (session != null) {
            // ザッピングによる再訪問
            session.isBufferActive = false
            session.lastActiveAtMs = timestampMs
        } else {
            // この1時間/セッションで初めて開いたアプリ
            session = AppSession(
                packageName = packageName,
                appName = appName,
                startedAtMs = timestampMs,
                lastActiveAtMs = timestampMs,
                continuousDurationMs = 0L,
                isBufferActive = false
            )
            activeSessions[packageName] = session
        }

        return checkThresholdAlert(timestampMs)
    }

    /**
     * アプリがバックグラウンドに移行した時のハンドラ
     */
    fun onAppBackground(packageName: String, timestampMs: Long) {
        val session = activeSessions[packageName]
        if (session != null) {
            session.isBufferActive = true
            session.bufferStartedAtMs = timestampMs
        }
        if (currentActivePackage == packageName) {
            recordPreviousAppTimeline(timestampMs)
        }
    }

    /**
     * 画面消灯時のハンドラ
     */
    fun onScreenOff(timestampMs: Long) {
        isScreenOff = true
        screenOffStartedAtMs = timestampMs
        recordPreviousAppTimeline(timestampMs)
        val currentPkg = currentActivePackage
        if (currentPkg != null) {
            val session = activeSessions[currentPkg]
            session?.isBufferActive = true
            session?.bufferStartedAtMs = timestampMs
        }
    }

    /**
     * 画面点灯時のハンドラ
     */
    fun onScreenOn(timestampMs: Long) {
        checkHourlyReset(timestampMs)
        if (isScreenOff && (timestampMs - screenOffStartedAtMs <= screenRestBufferMs)) {
            isScreenOff = false
        } else if (isScreenOff) {
            resetAllSessions(timestampMs)
            screenSessionStartedAtMs = timestampMs
            isScreenOff = false
            triggeredScreenThresholds.clear()
        }
        onTick(timestampMs)
    }

    /**
     * 定周期ポーリングでの時間進行としきい値チェック
     */
    fun onTick(timestampMs: Long): ThresholdAlert? {
        checkHourlyReset(timestampMs)

        if (isScreenOff) {
            if (timestampMs - screenOffStartedAtMs > screenRestBufferMs) {
                resetAllSessions(screenOffStartedAtMs)
            }
            return null
        }

        val currentPkg = currentActivePackage
        if (currentPkg != null) {
            val session = activeSessions[currentPkg]
            if (session != null && !session.isBufferActive) {
                session.continuousDurationMs += 1000L
                session.lastActiveAtMs = timestampMs
            }
        }

        return checkThresholdAlert(timestampMs)
    }

    /**
     * 1時間ごとのリセット判定（毎正時またぎでリセット）
     */
    private fun checkHourlyReset(timestampMs: Long) {
        val bucket = timestampMs / (60 * 60 * 1000L)
        if (currentHourBucket == -1L) {
            currentHourBucket = bucket
            return
        }
        if (bucket != currentHourBucket) {
            currentHourBucket = bucket
            resetAllSessions(timestampMs)
            screenSessionStartedAtMs = timestampMs
        }
    }

    private fun recordPreviousAppTimeline(timestampMs: Long) {
        val info = lastActiveAppInfo ?: return
        val durationMs = timestampMs - lastAppSwitchedAtMs
        val durationSec = durationMs / 1000L
        if (durationSec >= 3) { // 3秒以上の滞在をタイムラインに記録
            val entry = TimelineEntry(
                packageName = info.first,
                appName = info.second,
                timestampMs = lastAppSwitchedAtMs,
                durationSeconds = durationSec
            )
            timelineEntries.add(0, entry)
            if (timelineEntries.size > 50) {
                timelineEntries.removeAt(timelineEntries.lastIndex)
            }
        }
        lastActiveAppInfo = null
    }

    private fun checkThresholdAlert(timestampMs: Long): ThresholdAlert? {
        val currentPkg = currentActivePackage
        val session = if (currentPkg != null) activeSessions[currentPkg] else null

        if (session != null) {
            val durationMinutes = session.continuousDurationMs / (60 * 1000L)
            val pkgTriggered = triggeredThresholds.getOrPut(session.packageName) { mutableSetOf() }

            for (threshold in thresholdMinutes.sorted()) {
                if (durationMinutes >= threshold && !pkgTriggered.contains(threshold)) {
                    pkgTriggered.add(threshold)
                    return ThresholdAlert(
                        thresholdMinutes = threshold,
                        packageName = session.packageName,
                        appName = session.appName,
                        continuousMinutes = durationMinutes,
                        message = "${session.appName}を連続（累計）で${threshold}分使用中です。"
                    )
                }
            }
        }

        val screenDurationMinutes = getTotalContinuousScreenDurationMs(timestampMs) / (60 * 1000L)
        for (threshold in thresholdMinutes.sorted()) {
            if (screenDurationMinutes >= threshold && !triggeredScreenThresholds.contains(threshold)) {
                triggeredScreenThresholds.add(threshold)
                return ThresholdAlert(
                    thresholdMinutes = threshold,
                    packageName = "all.screen.continuity",
                    appName = "スマホ全体（連続操作）",
                    continuousMinutes = screenDurationMinutes,
                    message = "スマホを連続${threshold}分使用しています！目を休めましょう。"
                )
            }
        }

        return null
    }

    private fun commitSessionToRecord(session: AppSession) {
        val durationMinutes = session.continuousDurationMs / (60 * 1000L)
        if (durationMinutes <= 0) return

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
    }

    private fun resetAllSessions(timestampMs: Long) {
        for (session in activeSessions.values) {
            commitSessionToRecord(session)
        }
        activeSessions.clear()
        currentActivePackage = null
        screenSessionStartedAtMs = 0L
        triggeredThresholds.clear()
        triggeredScreenThresholds.clear()
    }
}
