package jp.cura.savetime

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import jp.cura.savetime.databinding.ActivityMainBinding
import jp.cura.savetime.service.UsageMonitorService
import jp.cura.savetime.util.AppNameResolver
import jp.cura.savetime.util.AppUpdateManager
import jp.cura.savetime.util.ExcludedAppsStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var excludedAppsStore: ExcludedAppsStore

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        excludedAppsStore = ExcludedAppsStore(this)
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUiState()

        val filter = IntentFilter(UsageMonitorService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnGrantOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.btnGrantAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        binding.switchMonitorDaemon.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!hasUsageStatsPermission()) {
                    binding.switchMonitorDaemon.isChecked = false
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                startMonitorService()
            } else {
                stopMonitorService()
            }
        }

        binding.btnExcludeCurrentApp.setOnClickListener {
            val session = UsageMonitorService.tracker.getCurrentSession()
            if (session != null) {
                excludedAppsStore.addExclusion(session.packageName)
                UsageMonitorService.refreshIgnoredPackages(this)
                Toast.makeText(this, "${session.appName} を除外リストに追加しました", Toast.LENGTH_SHORT).show()
                updateUiState()
            }
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForAppUpdate()
        }
    }

    private fun checkForAppUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "確認中..."

        lifecycleScope.launch {
            val update = AppUpdateManager.checkLatestRelease()
            if (update != null) {
                val currentVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName
                } catch (_: Exception) { "1.0.0" }

                if (update.tagName != "v$currentVersion" && update.tagName != currentVersion) {
                    binding.btnCheckUpdate.text = "DL中..."
                    Toast.makeText(this@MainActivity, "新バージョン ${update.tagName} をダウンロード中...", Toast.LENGTH_SHORT).show()

                    val apkFile = AppUpdateManager.downloadApk(this@MainActivity, update.downloadUrl)
                    if (apkFile != null) {
                        binding.btnCheckUpdate.text = "更新実行"
                        binding.btnCheckUpdate.isEnabled = true
                        AppUpdateManager.promptInstall(this@MainActivity, apkFile)
                    } else {
                        Toast.makeText(this@MainActivity, "ダウンロードに失敗しました", Toast.LENGTH_SHORT).show()
                        binding.btnCheckUpdate.text = "更新確認"
                        binding.btnCheckUpdate.isEnabled = true
                    }
                } else {
                    Toast.makeText(this@MainActivity, "最新バージョン（v$currentVersion）です", Toast.LENGTH_SHORT).show()
                    binding.btnCheckUpdate.text = "最新版"
                    binding.btnCheckUpdate.isEnabled = true
                }
            } else {
                Toast.makeText(this@MainActivity, "更新情報の取得に失敗しました", Toast.LENGTH_SHORT).show()
                binding.btnCheckUpdate.text = "更新確認"
                binding.btnCheckUpdate.isEnabled = true
            }
        }
    }

    private fun checkPermissions() {
        val hasUsage = hasUsageStatsPermission()
        binding.layoutPermissionBanner.visibility = if (hasUsage) View.GONE else View.VISIBLE

        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        binding.layoutOverlayPermissionBanner.visibility = if (hasOverlay) View.GONE else View.VISIBLE

        val hasAccessibility = jp.cura.savetime.service.AppDetectorAccessibilityService.isAccessibilitySettingsOn(this)
        binding.layoutAccessibilityBanner.visibility = if (hasAccessibility) View.GONE else View.VISIBLE
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startMonitorService() {
        val intent = Intent(this, UsageMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitorService() {
        val intent = Intent(this, UsageMonitorService::class.java)
        stopService(intent)
        updateUiState()
    }

    private fun updateUiState() {
        val isRunning = UsageMonitorService.isRunning
        binding.switchMonitorDaemon.isChecked = isRunning

        if (isRunning) {
            binding.tvStatusBadge.text = "[ ACTIVE ]"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_lime))
        } else {
            binding.tvStatusBadge.text = "[ STANDBY ]"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        val session = UsageMonitorService.tracker.getCurrentSession()
        val totalScreenMs = UsageMonitorService.tracker.getTotalContinuousScreenDurationMs()
        val screenSec = totalScreenMs / 1000L
        val screenHours = screenSec / 3600
        val screenMin = (screenSec % 3600) / 60
        val screenSeconds = screenSec % 60
        val screenContinuityStr = String.format(Locale.US, "%02d:%02d:%02d", screenHours, screenMin, screenSeconds)

        if (session != null && !session.isBufferActive) {
            binding.tvActiveAppName.text = session.appName
            binding.btnExcludeCurrentApp.visibility = View.VISIBLE

            val totalSec = session.continuousDurationMs / 1000L
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            binding.tvContinuousTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

            binding.tvBufferStatus.text = "[ 連続計測中 / 画面合計: $screenContinuityStr ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_crimson))
        } else if (session != null && session.isBufferActive) {
            binding.tvActiveAppName.text = "${session.appName} (一時中断)"
            binding.btnExcludeCurrentApp.visibility = View.VISIBLE
            binding.tvBufferStatus.text = "[ バッファ猶予中 / 画面合計: $screenContinuityStr ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            binding.tvActiveAppName.text = getString(R.string.no_active_session)
            binding.btnExcludeCurrentApp.visibility = View.GONE
            binding.tvContinuousTimer.text = "00:00:00"
            binding.tvBufferStatus.text = if (totalScreenMs > 0) "[ 画面連続操作: $screenContinuityStr ]" else "[ 待機中 ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        renderTimelineList()
        renderExclusionsList()
        renderRankingList()
    }

    private fun renderTimelineList() {
        binding.layoutTimelineList.removeAllViews()
        val timeline = UsageMonitorService.tracker.getTimeline()

        if (timeline.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "アプリ切り替え履歴はありません"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            binding.layoutTimelineList.addView(emptyTv)
            return
        }

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        timeline.take(15).forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }

            val timeTv = TextView(this).apply {
                text = timeFormatter.format(Date(entry.timestampMs))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(140, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val nameTv = TextView(this).apply {
                text = entry.appName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val durationTv = TextView(this).apply {
                val mins = entry.durationSeconds / 60
                val secs = entry.durationSeconds % 60
                text = if (mins > 0) "${mins}分${secs}秒" else "${secs}秒"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lime))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            row.addView(timeTv)
            row.addView(nameTv)
            row.addView(durationTv)
            binding.layoutTimelineList.addView(row)
        }
    }

    private fun renderExclusionsList() {
        binding.layoutExclusionsList.removeAllViews()
        val customExclusions = excludedAppsStore.getCustomExcludedPackages()

        if (customExclusions.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "ユーザー追加の除外アプリはありません (現在計測中アプリから除外可能)"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            binding.layoutExclusionsList.addView(emptyTv)
            return
        }

        customExclusions.forEach { pkg ->
            val appName = AppNameResolver.resolveAppName(this, pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val nameTv = TextView(this).apply {
                text = appName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = Button(this).apply {
                text = "解除"
                textSize = 10f
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_surface)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_crimson))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 80)
                setOnClickListener {
                    excludedAppsStore.removeExclusion(pkg)
                    UsageMonitorService.refreshIgnoredPackages(this@MainActivity)
                    Toast.makeText(this@MainActivity, "$appName の除外を解除しました", Toast.LENGTH_SHORT).show()
                    updateUiState()
                }
            }

            row.addView(nameTv)
            row.addView(removeBtn)
            binding.layoutExclusionsList.addView(row)
        }
    }

    private fun renderRankingList() {
        binding.layoutRankingList.removeAllViews()
        val records = UsageMonitorService.tracker.getRecords()

        if (records.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.no_records)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutRankingList.addView(emptyTv)
            return
        }

        records.take(10).forEachIndexed { index, record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10, 0, 10)
            }

            val rankTv = TextView(this).apply {
                text = "#${index + 1}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(60, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val nameTv = TextView(this).apply {
                text = record.appName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val durationTv = TextView(this).apply {
                text = "最大連続 ${record.maxContinuousMinutes}分"
                setTextColor(
                    if (record.maxContinuousMinutes >= 30) {
                        ContextCompat.getColor(this@MainActivity, R.color.accent_crimson)
                    } else {
                        ContextCompat.getColor(this@MainActivity, R.color.accent_lime)
                    }
                )
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
            }

            row.addView(rankTv)
            row.addView(nameTv)
            row.addView(durationTv)
            binding.layoutRankingList.addView(row)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
