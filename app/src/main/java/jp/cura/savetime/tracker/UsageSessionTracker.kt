package jp.cura.savetime.tracker

import jp.cura.savetime.model.AppSession
import jp.cura.savetime.model.ContinuousRecord
import jp.cura.savetime.model.ThresholdAlert

/**
 * アプリの利用時間および「端末全体の連続スクリーンタイム」を追跡するトラッカー
 *
 * 【ザッピング対応・累積連続設計】:
 * ユーザーがスマホを操作している一連のセッション（画面消灯による十分な休憩をとるまで）の間、
 * アプリA -> アプリB -> アプリC と高速に行き来しても、
 * 各アプリの「累計連続使用時間」は絶対にリセットされずに加算（累積）され続けます。
 *
 * 例:
 * - YouTube 30秒
 * - X 30秒
 * - ブラウザ 30秒
 * - YouTube 30秒戻る -> YouTubeはリセットされず「1分00秒」に累積！
 * - X 30秒戻る -> Xも「1分00秒」に累積！
 *
 * セッション全体がリセットされるのは、ユーザーが「画面を消灯して休憩（screenRestBufferMs = 60秒）」した時のみです。
 */
class UsageSessionTracker(
    val bufferDurationMs: Long = 60_000L,
    val screenRestBufferMs: Long = 60_000L, // 画面消灯で一息入れた（休憩）とみなす時間
    val thresholdMinutes: List<Long> = listOf(30L, 45L, 60L),
    val ignoredPackages: Set<String> = emptySet()
) {
    // 端末全体の連続スクリーンセッション
    private var screenSessionStartedAtMs: Long = 0L
    private var isScreenOff: Boolean = false
    private var screenOffStartedAtMs: Long = 0L

    // このスマホ操作セッション内で使用したアプリごとの累積セッション (パッケージ名 -> セッション)
    private val activeSessions = mutableMapOf<String, AppSession>()
    private var currentActivePackage: String? = null

    // 過去の確定レコード & アラートトリガー履歴
    private val records = mutableMapOf<String, ContinuousRecord>()
    private val triggeredThresholds = mutableMapOf<String, MutableSet<Long>>() // パッケージごとのトリガー済み
    private val triggeredScreenThresholds = mutableSetOf<Long>()

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
        // スクリーンセッションの開始または休憩明け復旧
        if (screenSessionStartedAtMs == 0L || (isScreenOff && timestampMs - screenOffStartedAtMs > screenRestBufferMs)) {
            resetAllSessions(timestampMs)
            screenSessionStartedAtMs = timestampMs
            triggeredScreenThresholds.clear()
        }
        isScreenOff = false

        if (ignoredPackages.contains(packageName)) {
            // ランチャー等の無視対象アプリ
            return null
        }

        val previousPkg = currentActivePackage
        if (previousPkg != null && previousPkg != packageName) {
            val prevSession = activeSessions[previousPkg]
            if (prevSession != null) {
                prevSession.isBufferActive = true
                prevSession.bufferStartedAtMs = timestampMs
            }
        }

        currentActivePackage = packageName
        var session = activeSessions[packageName]

        if (session != null) {
            // ザッピングによる再訪問: 以前の時間をそのまま維持し、アクティブへ復帰
            session.isBufferActive = false
            session.lastActiveAtMs = timestampMs
        } else {
            // この一連の操作セッションで初めて開いたアプリ
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
            // 60秒以内の瞬時の画面点灯なら一連のセッションとして継続
            isScreenOff = false
        } else if (isScreenOff) {
            // 画面を消して60秒以上休憩したため、全アプリの連続時間をリセット
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
        if (isScreenOff) {
            if (timestampMs - screenOffStartedAtMs > screenRestBufferMs) {
                resetAllSessions(screenOffStartedAtMs)
            }
            return null
        }

        // 現在アクティブなアプリの実利用時間を1秒進める（ザッピング中も開いているアプリだけが進む）
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

    private fun checkThresholdAlert(timestampMs: Long): ThresholdAlert? {
        val currentPkg = currentActivePackage
        val session = if (currentPkg != null) activeSessions[currentPkg] else null

        // 1. 各アプリごとの連続（累計）しきい値チェック
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

        // 2. 端末全体の連続スクリーンタイムのしきい値チェック
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
