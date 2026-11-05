package jp.cura.savetime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import jp.cura.savetime.MainActivity
import jp.cura.savetime.R
import jp.cura.savetime.manager.DailyGoalManager
import jp.cura.savetime.manager.ScreenLockManager
import jp.cura.savetime.model.ThresholdAlert
import jp.cura.savetime.overlay.CuraOverlayManager
import jp.cura.savetime.tracker.UsageSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * フォアグラウンドアプリの連続使用時間を常駐監視するサービス
 * ユーザー補助サービス（即時検知）と UsageStatsManager（フォールバック / PayPay等互換モード）のハイブリッド構成
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_MONITOR = "savetime_monitor_channel"
        const val CHANNEL_ALERT = "savetime_alert_channel"
        const val NOTIFICATION_ID_MONITOR = 1001
        const val ACTION_STATE_CHANGED = "jp.cura.savetime.ACTION_STATE_CHANGED"
        const val ACTION_STOP_SERVICE = "jp.cura.savetime.ACTION_STOP_SERVICE"

        // 動的に追加される無視対象パッケージ（ランチャー等）
        val dynamicIgnoredPackages = mutableSetOf(
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.sonymobile.home",
            "com.sonyericsson.home",
            "com.android.systemui",
            "jp.cura.savetime"
        )

        // UIから参照可能なシングルトントラッカー
        val tracker = UsageSessionTracker(
            bufferDurationMs = 60_000L,
            thresholdMinutes = listOf(30L, 45L, 60L),
            ignoredPackages = dynamicIgnoredPackages
        )

        var isRunning = false
        @Volatile
        var activeInstance: UsageMonitorService? = null
            internal set

        @Volatile
        private var lastAccessibilityEventTime: Long = 0L

        /**
         * AppDetectorAccessibilityService からの即座のフォアグラウンド切り替え通知
         */
        fun notifyAppForeground(packageName: String, appName: String, timestampMs: Long, isLauncher: Boolean) {
            lastAccessibilityEventTime = timestampMs
            if (isLauncher) {
                dynamicIgnoredPackages.add(packageName)
                tracker.ignoredPackages = dynamicIgnoredPackages
            }
            tracker.onAppForeground(packageName, appName, timestampMs)
            activeInstance?.onAppSwitchedImmediate(packageName, appName, timestampMs)
        }

        fun refreshIgnoredPackages(context: Context) {
            val store = jp.cura.savetime.util.ExcludedAppsStore(context)
            dynamicIgnoredPackages.clear()
            dynamicIgnoredPackages.addAll(store.getExcludedPackages())
            tracker.ignoredPackages = dynamicIgnoredPackages
        }

        @Volatile
        var shouldReloadMessageCatalog = false

        @Volatile
        var shouldTriggerThirtySecondBlock = false

        @Volatile
        var shouldTriggerOneHourSleepBlock = false

        @Volatile
        var shouldReloadAppLimitSettings = false

        @Volatile
        var shouldReloadScreenLockSettings = false
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var monitorLoopJob: Job? = null
    private var usageStatsManager: UsageStatsManager? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var packageManagerHelper: PackageManager
    private lateinit var overlayManager: CuraOverlayManager
    private lateinit var ghostAppsStore: jp.cura.savetime.util.GhostAppsStore
    private lateinit var screenLockManager: ScreenLockManager
    private lateinit var appLimitManager: jp.cura.savetime.manager.AppLimitManager

    @Volatile
    private var isAlarmActive = false

    @Volatile
    private var isMorningWakeupLockPending = false

    @Volatile
    private var isScreenInteractive = true

    @Volatile
    private var lastProcessedEventTimestamp = 0L

    private fun checkIsScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: isScreenInteractive
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    tracker.onScreenOff(now)
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    tracker.onScreenOn(now)
                    resumeScreenBlockIfNeeded(now)
                }
                Intent.ACTION_USER_PRESENT -> {
                    resumeScreenBlockIfNeeded(now)
                    val currentPkg = tracker.getCurrentSession()?.packageName ?: tracker.getMostRecentOrActiveApp()?.first
                    val shouldDefer = DailyGoalManager.shouldDeferMorningWakeupLock(isAlarmActive, currentPkg)
                    if (shouldDefer) {
                        if (DailyGoalManager.shouldTriggerMorningWakeupLock(this@UsageMonitorService, now)) {
                            isMorningWakeupLockPending = true
                        }
                    } else {
                        checkAndTriggerMorningWakeupLock(now)
                    }
                }
                DailyGoalManager.ACTION_CURA_ALARM_STARTED -> {
                    isAlarmActive = true
                    if (overlayManager.isBlockerShowing) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            overlayManager.stopScreenBlock()
                        }
                    }
                    if (DailyGoalManager.shouldTriggerMorningWakeupLock(this@UsageMonitorService, now)) {
                        isMorningWakeupLockPending = true
                    }
                }
                DailyGoalManager.ACTION_CURA_ALARM_DISMISSED -> {
                    isAlarmActive = false
                    triggerPendingMorningWakeupLockIfAllowed(now)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        packageManagerHelper = packageManager

        refreshIgnoredPackages(this)
        detectAndRegisterDefaultLauncher()
        createNotificationChannels()
        ghostAppsStore = jp.cura.savetime.util.GhostAppsStore(this)
        overlayManager = CuraOverlayManager(this)
        screenLockManager = ScreenLockManager.fromContext(this)
        appLimitManager = jp.cura.savetime.manager.AppLimitManager.fromContext(this)
        activeInstance = this

        overlayManager.onEmergencyUnlockListener = {
            val nowMs = System.currentTimeMillis()
            screenLockManager.recordEmergencyUnlock(nowMs, this)
            tracker.getCurrentSession()?.let { session ->
                appLimitManager.recordAppEmergencyUnlock(session.packageName, nowMs)
                tracker.resetAppContinuousSession(session.packageName, nowMs)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this,
                    "緊急解除しました。15分間画面ロックを一時停止します",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        overlayManager.onBlockDismissedListener = {
            val nowMs = System.currentTimeMillis()
            tracker.getCurrentSession()?.let { session ->
                tracker.resetAppContinuousSession(session.packageName, nowMs)
            }
        }

        overlayManager.onAppEmergencyUnlockListener = { pkg ->
            val nowMs = System.currentTimeMillis()
            appLimitManager.recordAppEmergencyUnlock(pkg, nowMs)
            tracker.resetAppContinuousSession(pkg, nowMs)
            val appName = appLimitManager.getLimit(pkg)?.appName ?: pkg
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    this,
                    "${appName}のブロックを緊急解除しました（5分間クールダウン）",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(DailyGoalManager.ACTION_CURA_ALARM_STARTED)
            addAction(DailyGoalManager.ACTION_CURA_ALARM_DISMISSED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            if (activeInstance == this) {
                activeInstance = null
            }
            stopServiceInternal()
            return START_NOT_STICKY
        }

        isRunning = true
        broadcastState()
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification("監視待機中...", 0L))

        if (overlayManager.canDrawOverlay()) {
            overlayManager.showOverlay()
        }

        // 既に監視ループが稼働中であれば多重起動しない（タイマー倍速化の防止）
        if (monitorLoopJob?.isActive == true) {
            return START_STICKY
        }

        monitorLoopJob = serviceScope.launch {
            val initNow = System.currentTimeMillis()
            lastProcessedEventTimestamp = initNow
            DailyGoalManager.updateDailyGoalIfNeeded(
                context = this@UsageMonitorService,
                usageStatsManager = usageStatsManager,
                nowMs = initNow,
                ignoredPackages = dynamicIgnoredPackages
            )

            // 初回起動時は当日00:00:00（86,400秒軸の先頭）からのイベント履歴をバックフィルしてグラフに反映
            backfillDayTimelineFromUsageStats(initNow)

            var lastUiTickTime = 0L

            while (isActive) {
                val now = System.currentTimeMillis()
                val screenOn = checkIsScreenInteractive()

                // 画面OFF時の省電力モード（2秒待機）
                if (!screenOn) {
                    delay(2000L)
                    continue
                }

                val hasAccessibility = AppDetectorAccessibilityService.isServiceConnected
                val hasActiveBlock = appLimitManager.hasAnyActiveBlock(now)

                // ユーザー補助未接続時、またはセッション空の時は UsageStatsManager で適応型クエリ
                if (!hasAccessibility || tracker.getCurrentSession() == null) {
                    // スライディングウィンドウ（直近3秒前〜現在）で取りこぼし防止
                    val lookbackStart = (now - 3000L).coerceAtLeast(lastProcessedEventTimestamp.coerceAtLeast(now - 60_000L))
                    pollUsageEvents(lookbackStart, now)
                }

                // UI / 通知 / 目標チェックなどの重い処理は 1Hz（1秒に1回）実行
                if (now - lastUiTickTime >= 1000L) {
                    lastUiTickTime = now

                    DailyGoalManager.updateDailyGoalIfNeeded(
                        context = this@UsageMonitorService,
                        usageStatsManager = usageStatsManager,
                        nowMs = now,
                        ignoredPackages = dynamicIgnoredPackages
                    )

                    val alert = tracker.onTick(now)
                    if (alert != null) {
                        showThresholdAlertNotification(alert)
                    }

                    updateMonitorNotification()
                    broadcastState()

                    val session = tracker.getCurrentSession()
                    if ((overlayManager.isBlockerShowing || overlayManager.isAppExclusiveBlockShowing) && session != null) {
                        tracker.resetAppContinuousSession(session.packageName, now)
                    }
                    val recentOrActive = tracker.getMostRecentOrActiveApp()
                    val targetPkg = session?.packageName ?: recentOrActive?.first
                    val targetAppName = session?.appName ?: recentOrActive?.second ?: "待機中"
                    val totalScreenMs = tracker.getTotalContinuousScreenDurationMs(now)
                    val todayAppSec = tracker.getTodayAppUsageSeconds(targetPkg, now)
                    val todayTotalPhoneSec = tracker.getTodayTotalPhoneUsageSeconds(now)

                    if (shouldReloadMessageCatalog) {
                        shouldReloadMessageCatalog = false
                        overlayManager.reloadMessageCatalog()
                    }
                    if (shouldReloadScreenLockSettings) {
                        shouldReloadScreenLockSettings = false
                        screenLockManager = ScreenLockManager.fromContext(this@UsageMonitorService)
                    }
                    if (shouldReloadAppLimitSettings) {
                        shouldReloadAppLimitSettings = false
                        appLimitManager = jp.cura.savetime.manager.AppLimitManager.fromContext(this@UsageMonitorService)
                    }
                    if (shouldTriggerThirtySecondBlock) {
                        shouldTriggerThirtySecondBlock = false
                        mainHandlerPostBlock()
                    }
                    if (shouldTriggerOneHourSleepBlock) {
                        shouldTriggerOneHourSleepBlock = false
                        mainHandlerPostSleepBlock()
                    }

                    // 自動休憩ロック & 深夜就寝自動ロックの自律判定
                    val screenContinuousMins = totalScreenMs / 60_000L
                    val inCall = isInCall()

                    if (!overlayManager.isBlockerShowing && !isAlarmActive && !inCall) {
                        if (screenLockManager.shouldTriggerNightLock(screenContinuousMins, nowMs = now, isInCall = inCall, isAlarmActive = isAlarmActive)) {
                            screenLockManager.recordLockTriggered(now, this@UsageMonitorService)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                overlayManager.startNightDefenseBlock(screenLockManager.nightDurationSeconds)
                            }
                        } else if (screenLockManager.shouldTriggerIntervalLock(screenContinuousMins, nowMs = now, isInCall = inCall, isAlarmActive = isAlarmActive)) {
                            screenLockManager.recordLockTriggered(now, this@UsageMonitorService)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                overlayManager.startIntervalBreakBlock(screenLockManager.intervalDurationSeconds)
                            }
                        }
                    }

                    // Cura アラーム終了またはCura離脱時の保留中朝イチ起床ロックのフェイルセーフ発動
                    if (isMorningWakeupLockPending && !isAlarmActive && targetPkg != DailyGoalManager.CURA_ALARM_PACKAGE) {
                        triggerPendingMorningWakeupLockIfAllowed(now)
                    }

                    val isGhost = if (session != null && !session.isBufferActive) {
                        ghostAppsStore.isGhostModeApp(session.packageName)
                    } else {
                        false
                    }

                    overlayManager.updateState(
                        packageName = session?.packageName,
                        appName = targetAppName,
                        continuousDurationMs = session?.continuousDurationMs ?: 0L,
                        totalScreenDurationMs = totalScreenMs,
                        todayAppDurationSec = todayAppSec,
                        todayTotalPhoneDurationSec = todayTotalPhoneSec,
                        isBuffer = session?.isBufferActive ?: false,
                        isGhostMode = isGhost
                    )
                }

                // アプリ個別利用制限のチェック（高速周期でも判定して即応性を確保）
                val session = tracker.getCurrentSession()
                val currentPkg = session?.packageName
                val inCall = isInCall()
                if (!overlayManager.isBlockerShowing && !isAlarmActive && !inCall && session != null && currentPkg != null) {
                    if (appLimitManager.isAppBlocked(currentPkg, now)) {
                        tracker.resetAppContinuousSession(currentPkg, now)
                        val remainingSec = appLimitManager.getBlockRemainingSeconds(currentPkg, now)
                        val appName = session.appName
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            overlayManager.showAppExclusiveBlock(
                                packageName = currentPkg,
                                appName = appName,
                                remainingSeconds = remainingSec,
                                onHomeClicked = { goToHomeScreen() }
                            )
                        }
                    } else {
                        if (overlayManager.isAppExclusiveBlockShowing && overlayManager.currentBlockedPackage != currentPkg) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                overlayManager.hideAppExclusiveBlock()
                            }
                        }

                        val appLimit = appLimitManager.shouldTriggerAppLimitLock(
                            packageName = currentPkg,
                            continuousDurationMs = session.continuousDurationMs,
                            nowMs = now,
                            isInCall = inCall,
                            isAlarmActive = isAlarmActive
                        )
                        if (appLimit != null) {
                            appLimitManager.startAppBlock(appLimit.packageName, appLimit.blockDurationSeconds, now)
                            tracker.resetAppContinuousSession(appLimit.packageName, now)
                            val appName = appLimit.appName
                            val blockSeconds = appLimit.blockDurationSeconds
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                overlayManager.showAppExclusiveBlock(
                                    packageName = appLimit.packageName,
                                    appName = appName,
                                    remainingSeconds = blockSeconds,
                                    onHomeClicked = { goToHomeScreen() }
                                )
                            }
                        }
                    }
                } else if (overlayManager.isAppExclusiveBlockShowing) {
                    val currentBlocked = overlayManager.currentBlockedPackage
                    if (currentBlocked != null && (currentPkg == null || currentPkg != currentBlocked)) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            overlayManager.hideAppExclusiveBlock()
                        }
                    }
                }

                // ポーリング待機インターバル：
                // ユーザー補助有効時: 1000ms（リアルタイムプッシュ通知があるため）
                // ユーザー補助無効時（PayPay互換モード / UsageStats高速監視）:
                //   - ブロック中アプリあり（超警戒モード）: 150ms
                //   - 通常時: 250ms
                val loopDelay = if (hasAccessibility) {
                    1000L
                } else if (hasActiveBlock) {
                    150L
                } else {
                    250L
                }
                delay(loopDelay)
            }
        }

        return START_STICKY
    }

    private fun goToHomeScreen() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } catch (_: Exception) {}
            overlayManager.hideAppExclusiveBlock()
        }
    }

    private fun onAppSwitchedImmediate(packageName: String, appName: String, timestampMs: Long) {
        if (!::appLimitManager.isInitialized || !::overlayManager.isInitialized) return
        if (appLimitManager.isAppBlocked(packageName, timestampMs)) {
            val remainingSec = appLimitManager.getBlockRemainingSeconds(packageName, timestampMs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                overlayManager.showAppExclusiveBlock(
                    packageName = packageName,
                    appName = appName,
                    remainingSeconds = remainingSec,
                    onHomeClicked = { goToHomeScreen() }
                )
            }
        } else if (overlayManager.isAppExclusiveBlockShowing && overlayManager.currentBlockedPackage != packageName) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                overlayManager.hideAppExclusiveBlock()
            }
        }
    }

    private fun checkAndTriggerMorningWakeupLock(nowMs: Long) {
        if (DailyGoalManager.shouldTriggerMorningWakeupLock(this, nowMs)) {
            DailyGoalManager.recordMorningWakeupLockTriggered(this, nowMs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                overlayManager.startMorningWakeupBlock(60)
            }
        }
    }

    private fun triggerPendingMorningWakeupLockIfAllowed(nowMs: Long) {
        if (isMorningWakeupLockPending || DailyGoalManager.shouldTriggerMorningWakeupLock(this, nowMs)) {
            isMorningWakeupLockPending = false
            DailyGoalManager.recordMorningWakeupLockTriggered(this, nowMs)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                overlayManager.startMorningWakeupBlock(60)
            }, 800L)
        }
    }

    private fun mainHandlerPostBlock() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            overlayManager.startThirtySecondBlock(30)
        }
    }

    private fun mainHandlerPostSleepBlock() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            overlayManager.startOneHourSleepBlock(3600)
        }
    }

    private fun isInCall(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        return audioManager.mode == android.media.AudioManager.MODE_IN_CALL ||
                audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }

    private fun resumeScreenBlockIfNeeded(nowMs: Long) {
        val activeEndTime = ScreenLockManager.getActiveLockEndTime(this)
        if (activeEndTime > nowMs && !overlayManager.isBlockerShowing && !isAlarmActive && !isInCall()) {
            val remainingSec = ((activeEndTime - nowMs) / 1000L).coerceAtLeast(0L).toInt()
            if (remainingSec > 0) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    overlayManager.startScreenBlock(
                        durationSeconds = remainingSec,
                        tagText = ":: CURA // RESUMED_LOCK",
                        messageText = "画面ロックの残り時間があります。\n画面を伏せて少しだけ体を休めましょうね。"
                    )
                }
            }
        }
    }

    private fun detectAndRegisterDefaultLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = packageManagerHelper.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val launcherPkg = resolveInfo?.activityInfo?.packageName
            if (!launcherPkg.isNullOrBlank()) {
                dynamicIgnoredPackages.add(launcherPkg)
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun backfillDayTimelineFromUsageStats(nowMs: Long) {
        val manager = usageStatsManager ?: return
        val startOfDayMs = jp.cura.savetime.tracker.TimelineGraphCalculator.getStartOfDayMs(nowMs)
        val events = manager.queryEvents(startOfDayMs, nowMs) ?: return
        val event = UsageEvents.Event()

        val historical = mutableListOf<jp.cura.savetime.model.TimelineEntry>()
        var activePkg: String? = null
        var activeAppName: String = ""
        var activeStartMs: Long = 0L

        fun closeCurrentSegment(endMs: Long) {
            val pkg = activePkg ?: return
            if (!dynamicIgnoredPackages.contains(pkg)) {
                val durSec = ((endMs - activeStartMs) / 1000L).coerceIn(1L, 7200L)
                historical.add(
                    jp.cura.savetime.model.TimelineEntry(
                        packageName = pkg,
                        appName = activeAppName,
                        timestampMs = activeStartMs,
                        durationSeconds = durSec
                    )
                )
            }
            activePkg = null
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            if (pkg == packageName ||
                pkg.contains("systemui", ignoreCase = true) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true)
            ) {
                continue
            }

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (dynamicIgnoredPackages.contains(pkg)) {
                        closeCurrentSegment(time)
                    } else if (activePkg != pkg) {
                        closeCurrentSegment(time)
                        activePkg = pkg
                        activeAppName = getAppName(pkg)
                        activeStartMs = time
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    closeCurrentSegment(time)
                }
            }
        }
        if (activePkg != null) {
            closeCurrentSegment(nowMs)
        }
        if (historical.isNotEmpty()) {
            tracker.seedHistoricalTimeline(historical)
        }
    }

    @Suppress("DEPRECATION")
    private fun pollUsageEvents(startTime: Long, endTime: Long) {
        val manager = usageStatsManager ?: return
        val events = manager.queryEvents(startTime, endTime) ?: return
        val event = UsageEvents.Event()

        var latestEventTime = lastProcessedEventTimestamp

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            // 既に処理済みの過去イベントは重複実行防止のためスキップ
            if (time <= lastProcessedEventTimestamp) {
                continue
            }

            // システムUIやキーボード、自身のアプリによる一時イベントは無視
            if (pkg == packageName ||
                pkg.contains("systemui", ignoreCase = true) ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true)
            ) {
                continue
            }

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (time > latestEventTime) {
                        latestEventTime = time
                    }
                    val appName = getAppName(pkg)
                    val alert = tracker.onAppForeground(pkg, appName, time)
                    if (alert != null) {
                        showThresholdAlertNotification(alert)
                    }
                    // ユーザー補助オフ（PayPay等互換モード）時でも即座にブロックオーバーレイ判定を発動
                    onAppSwitchedImmediate(pkg, appName, time)
                }
            }
        }

        if (latestEventTime > lastProcessedEventTimestamp) {
            lastProcessedEventTimestamp = latestEventTime
        }
    }

    private fun getAppName(packageName: String): String {
        return jp.cura.savetime.util.AppNameResolver.resolveAppName(this, packageName)
    }

    private fun updateMonitorNotification() {
        val session = tracker.getCurrentSession()
        val totalScreenMs = tracker.getTotalContinuousScreenDurationMs()
        val screenMinutes = totalScreenMs / (60 * 1000L)

        val text = if (session != null && !session.isBufferActive) {
            val minutes = session.continuousDurationMs / (60 * 1000L)
            val seconds = (session.continuousDurationMs / 1000L) % 60
            "[${session.appName}] ${minutes}m${seconds}s (画面連続: ${screenMinutes}分)"
        } else if (screenMinutes > 0) {
            "待機中 (画面連続操作: ${screenMinutes}分)"
        } else {
            "監視中: アプリ待機中"
        }

        val notification = buildMonitorNotification(text, session?.continuousDurationMs ?: 0L)
        notificationManager.notify(NOTIFICATION_ID_MONITOR, notification)
    }

    private fun buildMonitorNotification(content: String, durationMs: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setContentTitle("CURA SAVETIME // 常駐監視中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showThresholdAlertNotification(alert: ThresholdAlert) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            alert.thresholdMinutes.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(":: SAVETIME WARNING (${alert.thresholdMinutes}分連続)")
            .setContentText(alert.message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(alert.thresholdMinutes.toInt(), notification)
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE_CHANGED)
        sendBroadcast(intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                "連続使用監視ステータス",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "フォアグラウンドアプリの連続使用時間を常時表示します"
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "連続使いすぎ警告通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "設定した連続時間を超えた際に警告を発行します"
            }

            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun stopServiceInternal() {
        isRunning = false
        monitorLoopJob?.cancel()
        serviceJob.cancel()
        if (::overlayManager.isInitialized) {
            overlayManager.removeOverlay()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (::notificationManager.isInitialized) {
            notificationManager.cancel(NOTIFICATION_ID_MONITOR)
            notificationManager.cancel(1002)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        broadcastState()
        stopSelf()
    }

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        stopServiceInternal()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
