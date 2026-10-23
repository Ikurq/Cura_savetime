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
 * 2. 【リアルタイム・タイムライン履歴】: アプリを開いた瞬間からタイムライン先頭に配置し、滞在秒数をリアルタイム更新（最大50件）。
 * 3. 【リアルタイム・ランキング連動】: セッション確定前でも現在の1時間の累計利用状況をランキングに即座に反映。
 * 4. 【除外パッケージの動的判定】: 設定された除外アプリを即時除外・クリーンアップ。
 * 5. 【ザッピング累積】: スマホ操作中のアプリ行き来をすべて累積。
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

    // タイムライン履歴（当日24時間＝86,400秒分を保持）
    private val timelineEntries = mutableListOf<TimelineEntry>()
    private var lastAppSwitchedAtMs: Long = 0L
    private var isTimelineTopEntryOpen: Boolean = false

    // 過去の確定レコード & アラートトリガー履歴
    private val records = mutableMapOf<String, ContinuousRecord>()
    private val triggeredThresholds = mutableMapOf<String, MutableSet<Long>>()
    private val triggeredScreenThresholds = mutableSetOf<Long>()

    fun getCurrentSession(): AppSession? {
        val currentPkg = currentActivePackage ?: return null
        return activeSessions[currentPkg]
    }

    /**
     * 現在開いているすべてのアプリセッション一覧（累計時間順）
     */
    fun getActiveSessions(): List<AppSession> {
        return activeSessions.values
            .filter { !ignoredPackages.contains(it.packageName) && it.continuousDurationMs > 0 }
            .sortedByDescending { it.continuousDurationMs }
    }

    fun getTotalContinuousScreenDurationMs(now: Long = System.currentTimeMillis()): Long {
        if (screenSessionStartedAtMs == 0L) return 0L
        if (isScreenOff && (now - screenOffStartedAtMs > screenRestBufferMs)) {
            return 0L
        }
        return (now - screenSessionStartedAtMs).coerceAtLeast(0L)
    }

    /**
     * 確定済みレコードと現在進行中のセッションを統合したランキングリストを取得
     */
    fun getRecords(): List<ContinuousRecord> {
        val combined = mutableMapOf<String, ContinuousRecord>()

        for ((pkg, rec) in records) {
            if (!ignoredPackages.contains(pkg)) {
                combined[pkg] = rec.copy()
            }
        }

        for ((pkg, session) in activeSessions) {
            if (ignoredPackages.contains(pkg)) continue
            val currentMins = session.continuousDurationMs / (60 * 1000L)
            val currentSecs = session.continuousDurationMs / 1000L
            if (currentSecs <= 0L) continue

            val existing = combined[pkg]
            if (existing != null) {
                if (currentMins > existing.maxContinuousMinutes) {
                    existing.maxContinuousMinutes = currentMins
                }
            } else {
                combined[pkg] = ContinuousRecord(
                    packageName = pkg,
                    appName = session.appName,
                    maxContinuousMinutes = currentMins,
                    totalUsageMinutes = currentMins,
                    sessionCount = 1
                )
            }
        }

        return combined.values.sortedWith(
            compareByDescending<ContinuousRecord> { it.maxContinuousMinutes }
                .thenByDescending { activeSessions[it.packageName]?.continuousDurationMs ?: 0L }
        )
    }

    fun getTimeline(): List<TimelineEntry> {
        return timelineEntries.filter { !ignoredPackages.contains(it.packageName) }
    }

    /**
     * UsageStatsManager等から復元した当日の過去タイムライン履歴をシードする（重複除外・降順整列）
     */
    fun seedHistoricalTimeline(historicalEntries: List<TimelineEntry>) {
        val existingTimestamps = timelineEntries.map { it.timestampMs }.toHashSet()
        for (entry in historicalEntries) {
            if (!ignoredPackages.contains(entry.packageName) && !existingTimestamps.contains(entry.timestampMs)) {
                timelineEntries.add(entry)
                existingTimestamps.add(entry.timestampMs)
            }
        }
        timelineEntries.sortByDescending { it.timestampMs }
        if (timelineEntries.size > 5000) {
            timelineEntries.subList(5000, timelineEntries.size).clear()
        }
    }

    /**
     * 特定のパッケージを除外した際に、既存のセッション・タイムラインからも削除する
     */
    fun removePackageData(packageName: String) {
        activeSessions.remove(packageName)
        records.remove(packageName)
        timelineEntries.removeAll { it.packageName == packageName }
        if (currentActivePackage == packageName) {
            currentActivePackage = null
            isTimelineTopEntryOpen = false
        }
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
            // 除外アプリ（ランチャーやユーザー設定除外）に移行した場合は直前のアプリを一時中断状態として保持する
            val previousPkg = currentActivePackage
            if (previousPkg != null) {
                finalizeTopTimelineEntry(timestampMs)
                val prevSession = activeSessions[previousPkg]
                if (prevSession != null) {
                    prevSession.isBufferActive = true
                    prevSession.bufferStartedAtMs = timestampMs
                }
            }
            return null
        }

        val previousPkg = currentActivePackage
        if (previousPkg != null && previousPkg != packageName) {
            finalizeTopTimelineEntry(timestampMs)

            val prevSession = activeSessions[previousPkg]
            if (prevSession != null) {
                prevSession.isBufferActive = true
                prevSession.bufferStartedAtMs = timestampMs
            }
        }

        // 別アプリからの遷移、またはランチャー/消灯による中断からの復帰時は新規タイムラインエントリを追加
        if (previousPkg != packageName || !isTimelineTopEntryOpen) {
            currentActivePackage = packageName
            lastAppSwitchedAtMs = timestampMs
            isTimelineTopEntryOpen = true

            val newEntry = TimelineEntry(
                packageName = packageName,
                appName = appName,
                timestampMs = timestampMs,
                durationSeconds = 1L
            )
            timelineEntries.add(0, newEntry)
            // 24時間（86,400秒）を超える古い履歴・上限超過分のみクリーンアップ
            val cutoffMs = timestampMs - 86_400_000L * 2
            timelineEntries.removeAll { it.timestampMs < cutoffMs }
            if (timelineEntries.size > 5000) {
                timelineEntries.removeAt(timelineEntries.lastIndex)
            }
        }

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
            finalizeTopTimelineEntry(timestampMs)
        }
    }

    /**
     * 画面消灯時のハンドラ
     */
    fun onScreenOff(timestampMs: Long) {
        isScreenOff = true
        screenOffStartedAtMs = timestampMs
        finalizeTopTimelineEntry(timestampMs)
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

                // タイムライン先頭が現在オープンかつアクティブなアプリなら滞在秒数をリアルタイム更新
                if (isTimelineTopEntryOpen && timelineEntries.isNotEmpty() && timelineEntries[0].packageName == currentPkg) {
                    val elapsedSec = ((timestampMs - timelineEntries[0].timestampMs) / 1000L).coerceAtLeast(1L)
                    timelineEntries[0] = timelineEntries[0].copy(durationSeconds = elapsedSec)
                }
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

    private fun finalizeTopTimelineEntry(timestampMs: Long) {
        if (!isTimelineTopEntryOpen) return
        val currentPkg = currentActivePackage ?: return
        if (timelineEntries.isNotEmpty() && timelineEntries[0].packageName == currentPkg) {
            val elapsedSec = ((timestampMs - timelineEntries[0].timestampMs) / 1000L).coerceAtLeast(1L)
            timelineEntries[0] = timelineEntries[0].copy(durationSeconds = elapsedSec)
        }
        isTimelineTopEntryOpen = false
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
        finalizeTopTimelineEntry(timestampMs)
        for (session in activeSessions.values) {
            commitSessionToRecord(session)
        }
        activeSessions.clear()
        currentActivePackage = null
        isTimelineTopEntryOpen = false
        screenSessionStartedAtMs = 0L
        triggeredThresholds.clear()
        triggeredScreenThresholds.clear()
    }
}
