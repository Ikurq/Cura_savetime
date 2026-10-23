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
            }
            tracker.onAppForeground(packageName, appName, timestampMs)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var usageStatsManager: UsageStatsManager? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var packageManagerHelper: PackageManager
    private lateinit var overlayManager: CuraOverlayManager

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

        detectAndRegisterDefaultLauncher()
        createNotificationChannels()
        overlayManager = CuraOverlayManager(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification("監視待機中...", 0L))

        if (overlayManager.canDrawOverlay()) {
            overlayManager.showOverlay()
        }

        serviceScope.launch {
            var lastQueryTime = System.currentTimeMillis() - 10_000L

            while (isActive) {
                val now = System.currentTimeMillis()

                // アクセシビリティイベントが直近10秒以内に来ていない場合のみ UsageStatsManager でポーリング補完
                val timeSinceAccessibility = now - lastAccessibilityEventTime
                if (timeSinceAccessibility > 10_000L) {
                    pollUsageEvents(lastQueryTime, now)
                }
                lastQueryTime = now

                val alert = tracker.onTick(now)
                if (alert != null) {
                    showThresholdAlertNotification(alert)
                }

                updateMonitorNotification()
                broadcastState()

                val session = tracker.getCurrentSession()
                val totalScreenMs = tracker.getTotalContinuousScreenDurationMs(now)
                overlayManager.updateState(
                    appName = session?.appName ?: "待機中",
                    continuousDurationMs = session?.continuousDurationMs ?: 0L,
                    totalScreenDurationMs = totalScreenMs,
                    isBuffer = session?.isBufferActive ?: false
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
    private fun pollUsageEvents(startTime: Long, endTime: Long) {
        val manager = usageStatsManager ?: return
        val events = manager.queryEvents(startTime, endTime) ?: return
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val time = event.timeStamp

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    val appName = getAppName(pkg)
                    val alert = tracker.onAppForeground(pkg, appName, time)
                    if (alert != null) {
                        showThresholdAlertNotification(alert)
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    tracker.onAppBackground(pkg, time)
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
