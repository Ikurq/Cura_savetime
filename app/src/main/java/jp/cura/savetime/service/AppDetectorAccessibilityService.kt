package jp.cura.savetime.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * リアルタイムにウィンドウの切り替え（フォアグラウンドアプリ）を検知するユーザー補助サービス
 */
class AppDetectorAccessibilityService : AccessibilityService() {

    companion object {
        var isServiceConnected: Boolean = false
            private set

        /**
         * ユーザー補助サービスが有効になっているかを判定するユーティリティ
         */
        fun isAccessibilitySettingsOn(context: Context): Boolean {
            var accessibilityEnabled = 0
            val service = "${context.packageName}/${AppDetectorAccessibilityService::class.java.canonicalName}"
            try {
                accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (_: Exception) {
            }
            val stringColonSplitter = TextUtils.SimpleStringSplitter(':')
            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    stringColonSplitter.setString(settingValue)
                    while (stringColonSplitter.hasNext()) {
                        val accessibilityService = stringColonSplitter.next()
                        if (accessibilityService.equals(service, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
            return false
        }
    }

    private lateinit var packageManagerHelper: PackageManager
    private var defaultLauncherPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        packageManagerHelper = packageManager
        resolveDefaultLauncher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // 自身のアプリは無視
        if (packageName == this.packageName) {
            return
        }

        // デフォルトランチャー（ホーム画面）の更新
        if (defaultLauncherPackage == null) {
            resolveDefaultLauncher()
        }

        val appName = getAppName(packageName)
        val timestamp = System.currentTimeMillis()

        // UsageMonitorServiceにリアルタイム通知
        UsageMonitorService.notifyAppForeground(packageName, appName, timestamp, isLauncher = (packageName == defaultLauncherPackage))
    }

    override fun onInterrupt() {
        // 中断処理
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
    }

    private fun resolveDefaultLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            defaultLauncherPackage = resolveInfo?.activityInfo?.packageName
        } catch (_: Exception) {}
    }

    private fun getAppName(packageName: String): String {
        return jp.cura.savetime.util.AppNameResolver.resolveAppName(this, packageName)
    }
}
