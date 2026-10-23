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

        // アプリではなくシステムUIやキーボードなど一時的に重なるだけのパッケージ（現在のアプリ計測を中断させない）
        private val TRANSIENT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.google.android.inputmethod.japanese",
            "jp.co.omronsoft.openwnn",
            "jp.co.sony.tablet.inputmethod",
            "com.sonymobile.pobox",
            "com.samsung.android.honeyboard",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter",
            "android"
        )

        /**
         * ユーザー補助サービスが有効になっているかを判定するユーティリティ
         */
        fun isAccessibilitySettingsOn(context: Context): Boolean {
            if (isServiceConnected) return true

            var accessibilityEnabled = 0
            try {
                accessibilityEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (_: Exception) {
            }
            if (accessibilityEnabled == 1) {
                val settingValue = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (settingValue != null) {
                    val splitter = TextUtils.SimpleStringSplitter(':')
                    splitter.setString(settingValue)
                    while (splitter.hasNext()) {
                        val component = splitter.next()
                        if (component.contains(context.packageName, ignoreCase = true) &&
                            component.contains(AppDetectorAccessibilityService::class.java.simpleName, ignoreCase = true)
                        ) {
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

        // 自身のアプリや、通知バー・キーボード等の一時的なシステムオーバーレイは無視（現在のアプリ計測を止めない）
        if (packageName == this.packageName || TRANSIENT_SYSTEM_PACKAGES.contains(packageName)) {
            return
        }
        if (packageName.contains("inputmethod", ignoreCase = true) ||
            packageName.contains("keyboard", ignoreCase = true) ||
            packageName.contains("systemui", ignoreCase = true)
        ) {
            return
        }

        if (defaultLauncherPackage == null) {
            resolveDefaultLauncher()
        }

        val appName = getAppName(packageName)
        val timestamp = System.currentTimeMillis()

        // UsageMonitorServiceにリアルタイム通知
        UsageMonitorService.notifyAppForeground(
            packageName = packageName,
            appName = appName,
            timestampMs = timestamp,
            isLauncher = (packageName == defaultLauncherPackage)
        )
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
