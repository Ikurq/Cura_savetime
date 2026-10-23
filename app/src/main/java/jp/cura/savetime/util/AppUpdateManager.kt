package jp.cura.savetime.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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

    private const val GITHUB_API_LATEST_RELEASE = "https://api.github.com/repos/Ikurq/Cura_savetime/releases/latest"

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_LATEST_RELEASE)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "CuraSaveTime-App")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val tagName = root.optString("tag_name", "")
                val body = root.optString("body", "")

                val assets = root.optJSONArray("assets")
                var downloadUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (downloadUrl.isNotBlank()) {
                    return@withContext UpdateInfo(tagName, downloadUrl, body)
                }
            }
        } catch (_: Exception) {}
        return@withContext null
    }

    suspend fun downloadApk(context: Context, downloadUrl: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "CuraSaveTime-App")
            conn.connectTimeout = 10000
            conn.readTimeout = 30000

            val cacheDir = context.cacheDir
            val destFile = File(cacheDir, "update.apk")
            if (destFile.exists()) {
                destFile.delete()
            }

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            return@withContext destFile
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
}
