package jp.cura.savetime.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases から最新APKを確認・ダウンロードしてアプリ内から更新するユーティリティ
 */
object AppUpdateManager {

    private const val GITHUB_REPO = "Ikurq/Cura_savetime"
    private const val GITHUB_API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val RELEASES_WEB_URL = "https://github.com/$GITHUB_REPO/releases/latest"
    private const val FALLBACK_DOWNLOAD_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/app-debug.apk"

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String,
        val htmlUrl: String,
        val releaseNotes: String
    )

    suspend fun checkLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_LATEST_RELEASE)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "CuraSaveTime-Android-App")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val tagName = root.optString("tag_name", "")
                val body = root.optString("body", "").trim()
                val htmlUrl = root.optString("html_url", RELEASES_WEB_URL)

                val assets = root.optJSONArray("assets")
                var downloadUrl = FALLBACK_DOWNLOAD_URL
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", downloadUrl)
                            break
                        }
                    }
                }

                if (tagName.isNotBlank()) {
                    return@withContext UpdateInfo(tagName, downloadUrl, htmlUrl, body)
                }
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    suspend fun downloadApk(context: Context, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            var currentUrl = downloadUrl
            var redirects = 0
            var conn: HttpURLConnection

            while (true) {
                val url = URL(currentUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "CuraSaveTime-Android-App")
                conn.connectTimeout = 15000
                conn.readTimeout = 45000

                val status = conn.responseCode
                if (status in 301..308) {
                    val location = conn.getHeaderField("Location") ?: break
                    currentUrl = location
                    redirects++
                    if (redirects > 5) break
                } else {
                    break
                }
            }

            if (conn.responseCode != 200) return@withContext null

            val cacheDir = context.cacheDir
            val destFile = File(cacheDir, "CuraSaveTime-update.apk")
            if (destFile.exists()) {
                destFile.delete()
            }

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (destFile.exists() && destFile.length() > 100_000L) {
                return@withContext destFile
            }
            return@withContext null
        } catch (_: Exception) {
            return@withContext null
        }
    }

    fun promptInstall(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        activity.startActivity(intent)
    }

    fun showFallbackBrowserDialog(context: Context, downloadUrl: String = FALLBACK_DOWNLOAD_URL) {
        AlertDialog.Builder(context)
            .setTitle("アップデート")
            .setMessage("ブラウザ経由で最新APKをダウンロードしますか？")
            .setPositiveButton("APKを直接ダウンロード") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            .setNeutralButton("GitHub Releasesを開く") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_WEB_URL)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
