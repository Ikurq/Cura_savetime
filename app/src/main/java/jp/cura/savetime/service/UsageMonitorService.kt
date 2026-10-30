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
import androidx.core.app.NotificationCompat
import jp.cura.savetime.MainActivity
import jp.cura.savetime.R
import jp.cura.savetime.manager.DailyGoalManager
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
 * ユーザー補助サービス（即時検知）と UsageStatsManager（フォールバック）のハイブリッド構成
 */
class UsageMonitorService : Service() {

    companion object {
        const val CHANNEL_MONITOR = "savetime_monitor_channel"
        const val CHANNEL_ALERT = "savetime_alert_channel"
        const val NOTIFICATION_ID_MONITOR = 1001
        const val ACTION_STATE_CHANGED = "jp.cura.savetime.ACTION_STATE_CHANGED"

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
            private set

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
        }

        fun refreshIgnoredPackages(context: Context) {
            val store = jp.cura.savetime.util.ExcludedAppsStore(context)
            dynamicIgnoredPackages.clear()
            dynamicIgnoredPackages.addAll(store.getExcludedPackages())
            tracker.ignoredPackages = dynamicIgnoredPackages
        }

        @Volatile
        var shouldReloadMessageCatalog = false
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var monitorLoopJob: Job? = null
    private var usageStatsManager: UsageStatsManager? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var packageManagerHelper: PackageManager
    private lateinit var overlayManager: CuraOverlayManager
    private lateinit var ghostAppsStore: jp.cura.savetime.util.GhostAppsStore

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> tracker.onScreenOff(now)
                Intent.ACTION_SCREEN_ON -> tracker.onScreenOn(now)
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

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
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
            DailyGoalManager.updateDailyGoalIfNeeded(
                context = this@UsageMonitorService,
                usageStatsManager = usageStatsManager,
                nowMs = initNow,
                ignoredPackages = dynamicIgnoredPackages
            )

            // 初回起動時は当日00:00:00（86,400秒軸の先頭）からのイベント履歴をバックフィルしてグラフに反映
            backfillDayTimelineFromUsageStats(initNow)

            // 直近15分間の履歴から現在のアクティブセッション状態も復元
            var lastQueryTime = initNow - (15 * 60 * 1000L)

            while (isActive) {
                val now = System.currentTimeMillis()

                DailyGoalManager.updateDailyGoalIfNeeded(
                    context = this@UsageMonitorService,
                    usageStatsManager = usageStatsManager,
                    nowMs = now,
                    ignoredPackages = dynamicIgnoredPackages
                )

                // ユーザー補助サービス未接続時、または現在セッションが空の時は UsageStatsManager で補完
                if (!AppDetectorAccessibilityService.isServiceConnected || tracker.getCurrentSession() == null) {
                    val lookbackStart = if (tracker.getCurrentSession() == null) now - 60_000L else lastQueryTime
                    pollUsageEvents(lookbackStart, now)
                }
                lastQueryTime = now

                val alert = tracker.onTick(now)
                if (alert != null) {
                    showThresholdAlertNotification(alert)
                }

                updateMonitorNotification()
                broadcastState()

                val session = tracker.getCurrentSession()
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

                delay(1000L) // 1秒間隔でUI/オーバーレイ更新
            }
        }

        return START_STICKY
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

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

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
                    val appName = getAppName(pkg)
                    val alert = tracker.onAppForeground(pkg, appName, time)
                    if (alert != null) {
                        showThresholdAlertNotification(alert)
                    }
                }
            }
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

    override fun onDestroy() {
        isRunning = false
        serviceJob.cancel()
        overlayManager.removeOverlay()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
