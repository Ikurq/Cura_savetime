package jp.cura.savetime.tracker

import jp.cura.savetime.model.AppSession
import jp.cura.savetime.model.ContinuousRecord
import jp.cura.savetime.model.ThresholdAlert

/**
 * アプリの利用時間および「端末全体の連続スクリーンタイム」を追跡するトラッカー
 *
 * 特徴:
 * 1. 【端末全体の連続使用時間（Screen Continuity）】:
 *    「Aアプリを30秒 -> Bアプリを30秒 -> Cアプリを30秒」と高速ザッピングしても、
 *    画面を消灯したり休憩（screenRestBufferMs）しない限り「スマホを連続で何分使っているか」をカウントし続けます。
 * 2. 【アプリ別セッション追跡（App Continuity with Quick-Switch Memory）】:
 *    Aアプリから別アプリへ浮気しても、appSwitchBufferMs（例: 2分）以内にAアプリへ戻ってきた場合、
 *    Aアプリのセッションをリセットせず「実利用時間（正味時間）」を累積してカウントします。
 * 3. 【ザッピング検出（Monster Switching Alert）】:
 *    短時間でのアプリ高速巡回（反復ザッピング）を検知可能。
 */
class UsageSessionTracker(
    val bufferDurationMs: Long = 60_000L, // アプリ個別バッファ (60秒)
    val appSwitchBufferMs: Long = 120_000L, // 別アプリ経由でも再開とみなす猶予時間 (2分)
    val screenRestBufferMs: Long = 60_000L, // 画面消灯で休憩とみなす時間 (60秒)
    val thresholdMinutes: List<Long> = listOf(30L, 45L, 60L),
    val ignoredPackages: Set<String> = emptySet()
) {
    // 端末全体の連続スクリーンセッション
    private var screenSessionStartedAtMs: Long = 0L
    private var lastScreenActiveAtMs: Long = 0L
    private var isScreenOff: Boolean = false
    private var screenOffStartedAtMs: Long = 0L

    // アプリごとの現在/直近セッション履歴 (パッケージ名 -> セッション)
    private val activeSessions = mutableMapOf<String, AppSession>()
    private var currentActivePackage: String? = null

    // 累積レコード & アラートトリガー履歴
    private val records = mutableMapOf<String, ContinuousRecord>()
    private val triggeredThresholds = mutableSetOf<Long>()
    private val triggeredScreenThresholds = mutableSetOf<Long>()

    // アプリ切り替えカウンタ（ザッピング監視用）
    private var recentSwitchCount: Int = 0
    private var lastSwitchWindowStartMs: Long = 0L

    fun getCurrentSession(): AppSession? {
        val currentPkg = currentActivePackage ?: return null
        return activeSessions[currentPkg]
    }

    /**
     * 端末全体の連続使用時間（ミリ秒）を取得
     */
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

    /**
     * アプリがフォアグラウンドに起動または復帰した時のハンドラ
     */
    fun onAppForeground(packageName: String, appName: String, timestampMs: Long): ThresholdAlert? {
        // スクリーンセッションの開始または復旧
        if (screenSessionStartedAtMs == 0L || (isScreenOff && timestampMs - screenOffStartedAtMs > screenRestBufferMs)) {
            screenSessionStartedAtMs = timestampMs
            triggeredScreenThresholds.clear()
        }
        isScreenOff = false
        lastScreenActiveAtMs = timestampMs

        if (ignoredPackages.contains(packageName)) {
            // ランチャーや自アプリ等の無視対象アプリ
            return null
        }

        // ザッピング回数判定 (1分間に何回アプリを切り替えたか)
        if (timestampMs - lastSwitchWindowStartMs > 60_000L) {
            recentSwitchCount = 1
            lastSwitchWindowStartMs = timestampMs
        } else {
            recentSwitchCount++
        }

        val previousPkg = currentActivePackage
        if (previousPkg != null && previousPkg != packageName) {
            // 前のアプリをバックグラウンド状態として保存
            val prevSession = activeSessions[previousPkg]
            if (prevSession != null) {
                prevSession.isBufferActive = true
                prevSession.bufferStartedAtMs = timestampMs
            }
        }

        currentActivePackage = packageName
        var session = activeSessions[packageName]

        if (session != null) {
            // 過去に開いた同一アプリへの再アクセス
            val awayDuration = timestampMs - session.bufferStartedAtMs
            if (session.isBufferActive && awayDuration <= appSwitchBufferMs) {
                // 2分以内の浮気からの復帰: 連続セッションをキープ（離脱していた時間は正味利用時間に加算しない）
                session.isBufferActive = false
                session.lastActiveAtMs = timestampMs
            } else if (session.isBufferActive) {
                // 猶予超過: 前のセッションを記録して新規セッションに更新
                commitSessionToRecord(session)
                session = AppSession(
                    packageName = packageName,
                    appName = appName,
                    startedAtMs = timestampMs,
                    lastActiveAtMs = timestampMs
                )
                activeSessions[packageName] = session
                triggeredThresholds.clear()
            } else {
                session.lastActiveAtMs = timestampMs
            }
        } else {
            // 初回起動
            session = AppSession(
                packageName = packageName,
                appName = appName,
                startedAtMs = timestampMs,
                lastActiveAtMs = timestampMs
            )
            activeSessions[packageName] = session
            triggeredThresholds.clear()
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
    }

    /**
     * 画面消灯時のハンドラ
     */
    fun onScreenOff(timestampMs: Long) {
        isScreenOff = true
        screenOffStartedAtMs = timestampMs
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
        if (isScreenOff && (timestampMs - screenOffStartedAtMs <= screenRestBufferMs)) {
            // 休憩時間未満で画面が再点灯されたらセッション継続
            isScreenOff = false
        } else if (isScreenOff) {
            // 画面消灯で十分な休憩（60秒超）をとったためリセット
            resetAllSessions(timestampMs)
            screenSessionStartedAtMs = timestampMs
            isScreenOff = false
            triggeredScreenThresholds.clear()
        }
        onTick(timestampMs)
    }

    /**
     * 定周期ポーリングでの時間更新としきい値チェック
     */
    fun onTick(timestampMs: Long): ThresholdAlert? {
        if (isScreenOff) {
            if (timestampMs - screenOffStartedAtMs > screenRestBufferMs) {
                resetAllSessions(screenOffStartedAtMs)
            }
            return null
        }

        // 期限切れセッションのクリーンアップ (別アプリに浮気したままappSwitchBufferMsを超過したもの)
        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            if (session.isBufferActive && entry.key != currentActivePackage) {
                val elapsed = timestampMs - session.bufferStartedAtMs
                if (elapsed > appSwitchBufferMs) {
                    commitSessionToRecord(session)
                    iterator.remove()
                }
            }
        }

        // 現在アクティブなアプリの時間を進行
        val currentPkg = currentActivePackage
        if (currentPkg != null) {
            val session = activeSessions[currentPkg]
            if (session != null && !session.isBufferActive) {
                // 1秒進める、あるいは実経過時間を反映
                session.continuousDurationMs += 1000L
                session.lastActiveAtMs = timestampMs
            }
        }

        return checkThresholdAlert(timestampMs)
    }

    private fun checkThresholdAlert(timestampMs: Long): ThresholdAlert? {
        val currentPkg = currentActivePackage
        val session = if (currentPkg != null) activeSessions[currentPkg] else null

        // 1. 個別アプリのしきい値チェック
        if (session != null) {
            val durationMinutes = session.continuousDurationMs / (60 * 1000L)
            for (threshold in thresholdMinutes.sorted()) {
                if (durationMinutes >= threshold && !triggeredThresholds.contains(threshold)) {
                    triggeredThresholds.add(threshold)
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

        // 2. 端末全体の連続スクリーンタイムのしきい値チェック（アプリを次々替えても逃げられない）
        val screenDurationMinutes = getTotalContinuousScreenDurationMs(timestampMs) / (60 * 1000L)
        for (threshold in thresholdMinutes.sorted()) {
            if (screenDurationMinutes >= threshold && !triggeredScreenThresholds.contains(threshold)) {
                triggeredScreenThresholds.add(threshold)
                return ThresholdAlert(
                    thresholdMinutes = threshold,
                    packageName = "all.screen.continuity",
                    appName = "スマホ全体（連続操作）",
                    continuousMinutes = screenDurationMinutes,
                    message = "アプリを切り替えつつスマホを連続${threshold}分使用しています！目を休めましょう。"
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
