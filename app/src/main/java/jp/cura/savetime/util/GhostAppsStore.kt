package jp.cura.savetime.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * ゲーム中などにオーバーレイを「半透明＋当たり判定なし（タッチ透過）」にする対象アプリを管理するストア
 * - 5分ごとのメッセージ表示は維持しつつ、タッチイベントを100%背面のゲームへ透過させる。
 */
class GhostAppsStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "savetime_ghost_apps"
        private const val KEY_GHOST_PACKAGES = "ghost_packages"
        private const val KEY_AUTO_GAME_CATEGORY = "auto_game_category"
    }

    fun getGhostPackages(): Set<String> {
        return prefs.getStringSet(KEY_GHOST_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun addGhostPackage(packageName: String) {
        val current = getGhostPackages().toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_GHOST_PACKAGES, current).apply()
    }

    fun removeGhostPackage(packageName: String) {
        val current = getGhostPackages().toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_GHOST_PACKAGES, current).apply()
    }

    fun isAutoDetectGameCategoryEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_GAME_CATEGORY, true)
    }

    fun setAutoDetectGameCategoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_GAME_CATEGORY, enabled).apply()
    }

    /**
     * 指定パッケージが「半透明＋当たり判定なし（ゴーストモード）」対象か判定
     */
    fun isGhostModeApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (getGhostPackages().contains(packageName)) return true

        if (isAutoDetectGameCategoryEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                if (appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                ) {
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }
}
