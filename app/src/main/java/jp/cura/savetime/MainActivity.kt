package jp.cura.savetime

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import jp.cura.savetime.databinding.ActivityMainBinding
import jp.cura.savetime.service.UsageMonitorService
import jp.cura.savetime.util.AppNameResolver
import jp.cura.savetime.util.AppUpdateManager
import jp.cura.savetime.util.ExcludedAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        UsageMonitorService.refreshIgnoredPackages(this)
        setupListeners()
        requestNotificationPermissionIfNeeded()

        // 初回起動時に使用状況アクセス権限があれば自動的に常駐サービスを開始
        if (hasUsageStatsPermission() && !UsageMonitorService.isRunning) {
            startMonitorService()
        }
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
                excludePackage(session.packageName, session.appName)
            }
        }

        binding.btnAddExclusion.setOnClickListener {
            showAppExclusionPickerDialog()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForAppUpdate()
        }

        binding.btnZoom24h.setOnClickListener {
            applyTimelineZoom(86_400)
        }
        binding.btnZoom6h.setOnClickListener {
            applyTimelineZoom(21_600)
        }
        binding.btnZoom1h.setOnClickListener {
            applyTimelineZoom(3_600)
        }
        binding.btnZoom15m.setOnClickListener {
            applyTimelineZoom(900)
        }

        binding.timelineGraphView.onInspectionChanged = { bar, inspectedSec ->
            updateInspectorText(bar, inspectedSec)
        }
    }

    private fun applyTimelineZoom(spanSeconds: Int) {
        binding.timelineGraphView.setZoomWindowSeconds(spanSeconds)
        val activeColor = ContextCompat.getColor(this, R.color.accent_cyan)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)
        binding.btnZoom24h.setTextColor(if (spanSeconds == 86_400) activeColor else inactiveColor)
        binding.btnZoom6h.setTextColor(if (spanSeconds == 21_600) activeColor else inactiveColor)
        binding.btnZoom1h.setTextColor(if (spanSeconds == 3_600) activeColor else inactiveColor)
        binding.btnZoom15m.setTextColor(if (spanSeconds == 900) activeColor else inactiveColor)
    }

    private fun updateInspectorText(
        bar: jp.cura.savetime.tracker.TimelineBar?,
        inspectedSec: Int
    ) {
        if (bar != null) {
            val startStr = jp.cura.savetime.tracker.TimelineGraphCalculator.formatSecondOfDay(bar.startSecondOfDay)
            val endStr = jp.cura.savetime.tracker.TimelineGraphCalculator.formatSecondOfDay(bar.endSecondOfDay)
            binding.tvTimelineInspector.text =
                "[$startStr-$endStr (${bar.startSecondOfDay}/86400s)] ${bar.appName} : ${bar.durationSeconds}秒"
            binding.tvTimelineInspector.setTextColor(ContextCompat.getColor(this, R.color.accent_lime))
        } else {
            val timeStr = jp.cura.savetime.tracker.TimelineGraphCalculator.formatSecondOfDay(inspectedSec)
            binding.tvTimelineInspector.text =
                "[$timeStr ($inspectedSec/86400s)] 使用記録なし (0秒)"
            binding.tvTimelineInspector.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun excludePackage(pkg: String, appName: String) {
        excludedAppsStore.addExclusion(pkg)
        UsageMonitorService.refreshIgnoredPackages(this)
        UsageMonitorService.tracker.removePackageData(pkg)
        Toast.makeText(this, "$appName を監視除外に設定しました", Toast.LENGTH_SHORT).show()
        updateUiState()
    }

    private fun showAppExclusionPickerDialog() {
        Toast.makeText(this, "アプリ一覧を読み込み中...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                AppNameResolver.getLaunchableApps(this@MainActivity)
            }
            val currentExcluded = excludedAppsStore.getExcludedPackages()
            val selectableApps = allApps.filter { !currentExcluded.contains(it.first) }

            if (selectableApps.isEmpty()) {
                Toast.makeText(this@MainActivity, "追加できるアプリがありません", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = selectableApps.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("監視から除外するアプリを選択")
                .setItems(names) { _, which ->
                    val selected = selectableApps[which]
                    excludePackage(selected.first, selected.second)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private fun checkForAppUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "確認中..."

        lifecycleScope.launch {
            val update = AppUpdateManager.checkLatestRelease()
            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
            } catch (_: Exception) { "1.0.0" }

            binding.btnCheckUpdate.text = "更新確認"
            binding.btnCheckUpdate.isEnabled = true

            if (update != null) {
                val isNewer = update.tagName != "v$currentVersion" && update.tagName != currentVersion
                val notes = if (update.releaseNotes.isNotBlank()) "\n\n【更新内容】\n${update.releaseNotes.take(300)}" else ""
                val statusMsg = if (isNewer) "新しいバージョンが利用可能です！" else "現在最新バージョンを使用中です。"

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("アップデート確認 (${update.tagName})")
                    .setMessage("$statusMsg\n現在: v$currentVersion / 最新: ${update.tagName}$notes")
                    .setPositiveButton("アプリ内で自動更新") { _, _ ->
                        downloadAndInstallUpdate(update.downloadUrl, update.tagName)
                    }
                    .setNeutralButton("ブラウザでDL") { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                        startActivity(intent)
                    }
                    .setNegativeButton("閉じる", null)
                    .show()
            } else {
                AppUpdateManager.showFallbackBrowserDialog(this@MainActivity)
            }
        }
    }

    private fun downloadAndInstallUpdate(downloadUrl: String, tagName: String) {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = "DL中..."
        Toast.makeText(this, "$tagName をダウンロードしています...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val apkFile = AppUpdateManager.downloadApk(this@MainActivity, downloadUrl)
            binding.btnCheckUpdate.text = "更新確認"
            binding.btnCheckUpdate.isEnabled = true

            if (apkFile != null) {
                AppUpdateManager.promptInstall(this@MainActivity, apkFile)
            } else {
                Toast.makeText(this@MainActivity, "直接DLに失敗したためブラウザを開きます", Toast.LENGTH_SHORT).show()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                startActivity(intent)
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

            val totalSec = session.continuousDurationMs / 1000L
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            binding.tvContinuousTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

            binding.tvBufferStatus.text = "[ 一時中断中 / 画面合計: $screenContinuityStr ]"
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
        binding.layoutTimelineLegend.removeAllViews()
        val timeline = UsageMonitorService.tracker.getTimeline()

        binding.timelineGraphView.setTimelineData(timeline)

        if (timeline.isEmpty()) {
            binding.tvTimelineInspector.text = "グラフ待機中（横軸: 0〜86400秒 / 縦軸: 秒）"
            binding.tvTimelineInspector.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            val emptyTv = TextView(this).apply {
                text = "まだアプリ切り替え履歴がありません（別アプリを開くと即座に記録されます）"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 4, 0, 4)
            }
            binding.layoutTimelineList.addView(emptyTv)
            return
        }

        updateInspectorText(
            binding.timelineGraphView.getSelectedBar(),
            binding.timelineGraphView.getInspectedSecond()
        )

        // アプリ別カラー凡例と当日累計秒数（上位6件を2列または横並びで表示）
        val summaries = binding.timelineGraphView.getAppSummaries().take(6)
        val palette = jp.cura.savetime.ui.TimelineGraphView.APP_COLOR_PALETTE
        summaries.chunked(2).forEach { pair ->
            val legendRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 2, 0, 2)
            }
            for (summary in pair) {
                val itemTv = TextView(this).apply {
                    text = "■ ${summary.appName} (${summary.totalSeconds}s)"
                    setTextColor(palette[summary.colorIndex])
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                legendRow.addView(itemTv)
            }
            binding.layoutTimelineLegend.addView(legendRow)
        }

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        timeline.take(20).forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }

            val colorIdx = jp.cura.savetime.tracker.TimelineGraphCalculator.colorIndexForPackage(
                entry.packageName,
                palette.size
            )

            val timeTv = TextView(this).apply {
                text = timeFormatter.format(Date(entry.timestampMs))
                setTextColor(palette[colorIdx])
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
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
                text = if (mins > 0) "${entry.durationSeconds}秒 (${mins}m${secs}s)" else "${entry.durationSeconds}秒"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_lime))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 0, 12, 0)
            }

            val excludeBtn = Button(this).apply {
                text = "除外"
                textSize = 9f
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_surface)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                layoutParams = LinearLayout.LayoutParams(110, 72)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    excludePackage(entry.packageName, entry.appName)
                }
            }

            row.addView(timeTv)
            row.addView(nameTv)
            row.addView(durationTv)
            row.addView(excludeBtn)
            binding.layoutTimelineList.addView(row)
        }
    }

    private fun renderExclusionsList() {
        binding.layoutExclusionsList.removeAllViews()
        val customExclusions = excludedAppsStore.getCustomExcludedPackages()

        if (customExclusions.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "除外指定されたアプリはありません（「+ アプリを選んで除外」またはタイムライン横の「除外」から追加できます）"
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
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
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
                layoutParams = LinearLayout.LayoutParams(120, 76)
                setPadding(0, 0, 0, 0)
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
        val activeSessions = UsageMonitorService.tracker.getActiveSessions()
        val records = UsageMonitorService.tracker.getRecords()

        if (activeSessions.isEmpty() && records.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.no_records)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutRankingList.addView(emptyTv)
            return
        }

        // 現在進行中の1時間セッションと確定レコードを統合表示（秒単位でも見えるようにする）
        val mergedList = mutableListOf<Triple<String, String, Long>>() // (pkg, name, durationSec)
        val seenPkgs = mutableSetOf<String>()

        for (s in activeSessions) {
            val sec = s.continuousDurationMs / 1000L
            val recMaxSec = (records.find { it.packageName == s.packageName }?.maxContinuousMinutes ?: 0L) * 60L
            mergedList.add(Triple(s.packageName, s.appName, maxOf(sec, recMaxSec)))
            seenPkgs.add(s.packageName)
        }
        for (r in records) {
            if (!seenPkgs.contains(r.packageName)) {
                mergedList.add(Triple(r.packageName, r.appName, r.maxContinuousMinutes * 60L))
            }
        }

        mergedList.sortByDescending { it.third }

        mergedList.take(10).forEachIndexed { index, item ->
            val (pkg, appName, totalSec) = item
            val mins = totalSec / 60L
            val secs = totalSec % 60L

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val rankTv = TextView(this).apply {
                text = "#${index + 1}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(65, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val nameTv = TextView(this).apply {
                text = appName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val durationTv = TextView(this).apply {
                text = if (mins > 0) "${mins}分${secs}秒" else "${secs}秒"
                setTextColor(
                    if (mins >= 30) {
                        ContextCompat.getColor(this@MainActivity, R.color.accent_crimson)
                    } else {
                        ContextCompat.getColor(this@MainActivity, R.color.accent_lime)
                    }
                )
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 0, 12, 0)
            }

            val excludeBtn = Button(this).apply {
                text = "除外"
                textSize = 9f
                backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_surface)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_amber))
                layoutParams = LinearLayout.LayoutParams(110, 72)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    excludePackage(pkg, appName)
                }
            }

            row.addView(rankTv)
            row.addView(nameTv)
            row.addView(durationTv)
            row.addView(excludeBtn)
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
