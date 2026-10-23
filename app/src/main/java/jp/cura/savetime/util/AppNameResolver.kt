package jp.cura.savetime.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * パッケージ名からユーザーに親しみやすい日本語/標準アプリ名へ解決するリゾルバー
 * - Package Visibility (Android 11+) が許可されている場合は PackageManager から正確なアプリ表示名を取得
 * - 万が一 NameNotFoundException になった場合でも主要アプリ辞書・フォールバックで綺麗な名前を返却
 */
object AppNameResolver {

    private val KNOWN_APP_NAMES = mapOf(
        // 主要SNS・動画・エンタメ
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.twitter.android" to "X (Twitter)",
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "jp.naver.line.android" to "LINE",
        "com.discord" to "Discord",
        "tv.twitch.android.app" to "Twitch",
        "com.netflix.mediaclient" to "Netflix",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.spotify.music" to "Spotify",
        "jp.nicovideo.android" to "ニコニコ動画",
        "jp.co.mixi.monsterstrike" to "モンスターストライク",
        "com.miHoYo.GenshinImpact" to "原神",
        "com.HoYoverse.hkrpgoversea" to "崩壊：スターレイル",
        "com.aniplex.fategrandorder" to "FGO",
        "jp.co.cydas.uma" to "ウマ娘",
        "jp.co.cygames.umamusume" to "ウマ娘 プリティーダービー",

        // ブラウザ
        "com.android.chrome" to "Chrome",
        "org.mozilla.firefox" to "Firefox",
        "com.microsoft.emmx" to "Microsoft Edge",
        "com.opera.browser" to "Opera",
        "com.brave.browser" to "Brave",

        // Google / ツール系
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.maps" to "Google マップ",
        "com.google.android.apps.photos" to "Google フォト",
        "com.google.android.googlequicksearchbox" to "Google",
        "com.android.vending" to "Google Play ストア",
        "com.google.android.apps.docs" to "Google ドライブ",
        "com.google.android.calendar" to "Google カレンダー",

        // 買い物・生活
        "com.amazon.mShop.android.shopping" to "Amazon",
        "jp.co.rakuten.android" to "楽天市場",
        "jp.co.yahoo.android.yjtop" to "Yahoo! JAPAN",
        "jp.mercari.app.android" to "メルカリ"
    )

    fun resolveAppName(context: Context, packageName: String): String {
        // 1. PackageManagerから取得を試みる
        try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            if (label.isNotBlank() && label != packageName) {
                return label
            }
        } catch (_: Exception) {
            // NameNotFoundException やセキュリティ例外
        }

        // 2. 既知の辞書から取得
        val known = KNOWN_APP_NAMES[packageName]
        if (known != null) {
            return known
        }

        // 3. パッケージ名末尾から推測（例: "com.example.game" -> "Game"）
        val rawSegment = packageName.substringAfterLast('.')
        return formatFallbackName(rawSegment)
    }

    private fun formatFallbackName(raw: String): String {
        if (raw.isBlank()) return "アプリ"
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * 端末にインストールされているランチャー起動可能なアプリ一覧（パッケージ名, 表示名）を取得
     */
    fun getLaunchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val map = mutableMapOf<String, String>()
        for (info in resolveList) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (_: Exception) {
                resolveAppName(context, pkg)
            }
            map[pkg] = label
        }
        return map.entries
            .map { it.key to it.value }
            .sortedBy { it.second.lowercase() }
    }
}
