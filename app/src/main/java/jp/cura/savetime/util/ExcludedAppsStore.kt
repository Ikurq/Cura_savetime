package jp.cura.savetime.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 監視から除外するアプリのパッケージ名を永続管理するストア
 */
class ExcludedAppsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cura_savetime_exclusions"
        private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

        // デフォルト除外
        val DEFAULT_EXCLUSIONS = setOf(
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
    }

    fun getExcludedPackages(): Set<String> {
        val saved = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null) ?: emptySet()
        return DEFAULT_EXCLUSIONS + saved
    }

    fun isExcluded(packageName: String): Boolean {
        return getExcludedPackages().contains(packageName)
    }

    fun addExclusion(packageName: String) {
        val current = (prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null) ?: emptySet()).toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, current).apply()
    }

    fun removeExclusion(packageName: String) {
        val current = (prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null) ?: emptySet()).toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, current).apply()
    }

    fun getCustomExcludedPackages(): Set<String> {
        return prefs.getStringSet(KEY_EXCLUDED_PACKAGES, null) ?: emptySet()
    }
}
