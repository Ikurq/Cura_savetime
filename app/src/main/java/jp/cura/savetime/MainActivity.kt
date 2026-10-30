package jp.cura.savetime

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import jp.cura.savetime.databinding.ActivityMainBinding
import jp.cura.savetime.manager.DailyGoalManager
import jp.cura.savetime.model.TimelineEntry
import jp.cura.savetime.service.UsageMonitorService
import jp.cura.savetime.tracker.TimelineGraphCalculator
import jp.cura.savetime.util.AppNameResolver
import jp.cura.savetime.util.AppUpdateManager
import jp.cura.savetime.util.CuraMessageCatalog
import jp.cura.savetime.util.ExcludedAppsStore
import jp.cura.savetime.util.GhostAppsStore
import jp.cura.savetime.util.RelaxActionCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var excludedAppsStore: ExcludedAppsStore
    private lateinit var ghostAppsStore: GhostAppsStore
    private var currentTabIndex: Int = 0
    private var isMicroLogExpanded: Boolean = false
    private var isUpdatingSwitchUi: Boolean = false
    private var pendingServiceState: Boolean? = null

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
        ghostAppsStore = GhostAppsStore(this)
        UsageMonitorService.refreshIgnoredPackages(this)
        binding.switchAutoGameGhost.isChecked = ghostAppsStore.isAutoDetectGameCategoryEnabled()
        setupListeners()
        binding.hudFlavorText.isSelected = true
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        requestNotificationPermissionIfNeeded()

        // 初回起動時に使用状況アクセス権限があれば自動的に常駐サービスを開始
        if (hasUsageStatsPermission() && !UsageMonitorService.isRunning) {
            startMonitorService()
        }
    }

    private var currentMicroAction: String = "1回だけ深呼吸する"
    private var lastMicroActionRefreshMs: Long = 0L

    private fun refreshMicroAction(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastMicroActionRefreshMs >= 30_000L) {
            lastMicroActionRefreshMs = now
            currentMicroAction = RelaxActionCatalog.getRandomAction(this)
            binding.btnHeroBlock30s.text = currentMicroAction
            binding.btnQuickBlock30s.text = "$currentMicroAction (30秒ブロック)"
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUiState()
        refreshMicroAction(force = true)

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
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            animateNavBulge(item.itemId)
            when (item.itemId) {
                R.id.nav_home -> switchTab(0)
                R.id.nav_logs -> switchTab(1)
                R.id.nav_settings -> switchTab(2)
            }
            true
        }

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
            if (isUpdatingSwitchUi) return@setOnCheckedChangeListener

            if (isChecked) {
                if (!hasUsageStatsPermission()) {
                    isUpdatingSwitchUi = true
                    binding.switchMonitorDaemon.isChecked = false
                    isUpdatingSwitchUi = false
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                pendingServiceState = true
                updateServiceStatusText(true)
                if (!UsageMonitorService.isRunning) {
                    startMonitorService()
                }
            } else {
                pendingServiceState = false
                updateServiceStatusText(false)
                if (UsageMonitorService.isRunning) {
                    stopMonitorService()
                }
            }
        }

        binding.btnExcludeCurrentApp.setOnClickListener {
            val session = UsageMonitorService.tracker.getCurrentSession()
            if (session != null) {
                showAppActionSheet(session.packageName, session.appName)
            }
        }

        binding.btnHeroBlock30s.setOnClickListener {
            triggerThirtySecondBlock()
        }

        binding.btnHeroSleep1h.setOnClickListener {
            triggerOneHourSleepBlock()
        }

        binding.tvHeroCuraMessage.setOnClickListener {
            showTapSummaryInHero()
        }

        binding.btnQuickBlock30s.setOnClickListener {
            triggerThirtySecondBlock()
        }

        binding.btnQuickSleep1h.setOnClickListener {
            triggerOneHourSleepBlock()
        }

        binding.tvMicroLogHeader.setOnClickListener {
            isMicroLogExpanded = !isMicroLogExpanded
            binding.layoutMicroLogList.visibility = if (isMicroLogExpanded) View.VISIBLE else View.GONE
            renderTimelineList()
        }

        binding.btnAddExclusion.setOnClickListener {
            showAppExclusionPickerDialog()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForAppUpdate()
        }

        binding.btnZoom24h.setOnClickListener { applyTimelineZoom(86_400) }
        binding.btnZoom6h.setOnClickListener { applyTimelineZoom(21_600) }
        binding.btnZoom1h.setOnClickListener { applyTimelineZoom(3_600) }
        binding.btnZoom15m.setOnClickListener { applyTimelineZoom(900) }

        binding.timelineGraphView.onInspectionChanged = { bar, inspectedSec ->
            updateInspectorText(bar, inspectedSec)
        }

        binding.switchAutoGameGhost.setOnCheckedChangeListener { _, isChecked ->
            ghostAppsStore.setAutoDetectGameCategoryEnabled(isChecked)
        }

        binding.btnAddGhostApp.setOnClickListener {
            showGhostAppPickerDialog()
        }

        binding.btnCopyAiPrompt.setOnClickListener {
            copyAiPromptToClipboard()
        }

        binding.btnEditDialogueJson.setOnClickListener {
            showEditDialogueJsonDialog()
        }

        binding.btnQuickSettings.setOnClickListener {
            binding.bottomNavigation.selectedItemId = R.id.nav_settings
        }
    }


    private fun switchTab(tabIndex: Int) {
        currentTabIndex = tabIndex
        binding.tabContainerHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.tabContainerLogs.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.tabContainerSettings.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.mainScrollView.scrollTo(0, 0)
    }

    private fun animateNavBulge(itemId: Int) {
        val nav = binding.bottomNavigation
        val bulge = binding.navBulgeCircle

        // 1. 実際に表示されているメニュー項目のみを抽出
        val visibleItems = mutableListOf<Int>()
        for (i in 0 until nav.menu.size()) {
            val item = nav.menu.getItem(i)
            if (item.isVisible) {
                visibleItems.add(item.itemId)
            }
        }
        val visibleCount = visibleItems.size
        if (visibleCount == 0) return

        val index = visibleItems.indexOf(itemId).coerceAtLeast(0)

        nav.post {
            val totalWidth = nav.width
            val itemWidth = totalWidth / visibleCount
            val targetX = (itemWidth * index) + (itemWidth / 2) - (bulge.width / 2)

            // 2. 突起サークルのスライドアニメーション
            bulge.animate()
                .translationX(targetX.toFloat())
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

            // 3. アイコン・文字のリフトアップ処理（クリッピングを解除しながら上方向へ移動）
            val menuView = nav.getChildAt(0) as? android.view.ViewGroup ?: return@post
            menuView.clipChildren = false
            menuView.clipToPadding = false

            var visibleViewIndex = 0
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
                itemView.clipChildren = false
                itemView.clipToPadding = false

                if (itemView.visibility != View.VISIBLE) continue

                if (itemView.childCount > 0) {
                    val icon = itemView.getChildAt(0)
                    if (icon is android.view.ViewGroup) {
                        icon.clipChildren = false
                        icon.clipToPadding = false
                    }

                    if (visibleViewIndex == index) {
                        // 選択アイテム: アイコンを持ち上げ、ラベルもわずかに引き上げる
                        icon.animate().translationY(-64f).setDuration(300).start()
                        if (itemView.childCount > 1) {
                            val label = itemView.getChildAt(1)
                            label.animate().translationY(-12f).setDuration(300).start()
                        }
                    } else {
                        // 非選択アイテム: 通常の基準位置へ戻す
                        icon.animate().translationY(0f).setDuration(250).start()
                        if (itemView.childCount > 1) {
                            val label = itemView.getChildAt(1)
                            label.animate().translationY(0f).setDuration(250).start()
                        }
                    }
                }
                visibleViewIndex++
            }
        }
    }

    private fun triggerThirtySecondBlock() {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (!canDraw) {
            Toast.makeText(this, "「他のアプリの上に重ねて表示」権限を許可してください", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        if (!UsageMonitorService.isRunning && hasUsageStatsPermission()) {
            startMonitorService()
        }
        UsageMonitorService.shouldTriggerThirtySecondBlock = true
        Toast.makeText(this, "${currentMicroAction}（30秒間ロック）を開始します", Toast.LENGTH_SHORT).show()
        refreshMicroAction(force = true)
    }

    private fun triggerOneHourSleepBlock() {
        val canDraw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (!canDraw) {
            Toast.makeText(this, "「他のアプリの上に重ねて表示」権限を許可してください", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        if (!UsageMonitorService.isRunning && hasUsageStatsPermission()) {
            startMonitorService()
        }
        UsageMonitorService.shouldTriggerOneHourSleepBlock = true
        Toast.makeText(this, "「今日は眠る」モード（1時間スマホロック）を開始します", Toast.LENGTH_SHORT).show()
    }

    private fun showAppActionSheet(pkg: String, appName: String) {
        val isGhost = ghostAppsStore.getGhostPackages().contains(pkg)
        val ghostActionText = if (isGhost) {
            "ゲーム透過モードを解除する (現在: 透過中)"
        } else {
            "ゲーム透過モードに設定する (半透明・タッチ透過)"
        }

        val options = arrayOf(
            ghostActionText,
            "監視除外リストに追加する",
            "今すぐ30秒間画面をブロックして休む"
        )

        AlertDialog.Builder(this)
            .setTitle("$appName の操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (isGhost) {
                            ghostAppsStore.removeGhostPackage(pkg)
                            Toast.makeText(this, "$appName の透過モードを解除しました", Toast.LENGTH_SHORT).show()
                            updateUiState()
                        } else {
                            addGhostModePackage(pkg, appName)
                        }
                    }
                    1 -> excludePackage(pkg, appName)
                    2 -> triggerThirtySecondBlock()
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
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
        val baseHour = jp.cura.savetime.manager.DailyGoalManager.LOGICAL_DAY_BASE_HOUR
        if (bar != null) {
            val startStr = TimelineGraphCalculator.formatSecondOfDay(bar.startSecondOfDay, baseHour)
            val endStr = TimelineGraphCalculator.formatSecondOfDay(bar.endSecondOfDay, baseHour)
            val cleanDur = TimelineGraphCalculator.formatDurationClean(bar.durationSeconds)
            binding.tvTimelineInspector.text =
                "[$startStr〜$endStr] ${bar.appName} : $cleanDur"
            binding.tvTimelineInspector.setTextColor(ContextCompat.getColor(this, R.color.accent_lime))
        } else {
            val timeStr = TimelineGraphCalculator.formatSecondOfDay(inspectedSec, baseHour)
            binding.tvTimelineInspector.text =
                "[$timeStr] 該当時刻の利用記録なし"
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

    private fun addGhostModePackage(pkg: String, appName: String) {
        ghostAppsStore.addGhostPackage(pkg)
        Toast.makeText(this, "$appName を半透明・当たり判定なしに設定しました", Toast.LENGTH_SHORT).show()
        updateUiState()
    }

    private fun showGhostAppPickerDialog() {
        Toast.makeText(this, "アプリ一覧を読み込み中...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                AppNameResolver.getLaunchableApps(this@MainActivity)
            }
            val currentGhost = ghostAppsStore.getGhostPackages()
            val selectableApps = allApps.filter { !currentGhost.contains(it.first) }

            if (selectableApps.isEmpty()) {
                Toast.makeText(this@MainActivity, "追加できるアプリがありません", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = selectableApps.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("半透明・当たり判定なしにするアプリを選択")
                .setItems(names) { _, which ->
                    val selected = selectableApps[which]
                    addGhostModePackage(selected.first, selected.second)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private fun copyAiPromptToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Cura AI Prompt", CuraMessageCatalog.AI_GENERATION_PROMPT)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            this,
            "AI用プロンプトをコピーしました。他のAIに貼り付けてJSONを作成させてください",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showEditDialogueJsonDialog() {
        val currentCatalog = CuraMessageCatalog.load(this)
        val editText = EditText(this).apply {
            setText(CuraMessageCatalog.toPrettyJson(currentCatalog))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_void))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 10
            maxLines = 18
            setPadding(24, 20, 24, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("5分ごとセリフ枠 (JSON) の貼付・編集")
            .setView(editText)
            .setPositiveButton("保存して反映") { _, _ ->
                val raw = editText.text.toString()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                if (CuraMessageCatalog.saveCustomJson(this, raw)) {
                    UsageMonitorService.shouldReloadMessageCatalog = true
                    Toast.makeText(this, "セリフ枠を更新しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "JSON形式が不正です。構造を確認してください", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("初期枠に戻す") { _, _ ->
                CuraMessageCatalog.resetCustomJson(this)
                UsageMonitorService.shouldReloadMessageCatalog = true
                Toast.makeText(this, "標準のセリフ枠に戻しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
                val statusMsg = if (isNewer) "新しいバージョンが利用可能です。" else "現在最新バージョンを使用中です。"

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
        UsageMonitorService.isRunning = true
        pendingServiceState = true
        val intent = Intent(this, UsageMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateUiState()
    }

    private fun stopMonitorService() {
        UsageMonitorService.isRunning = false
        pendingServiceState = false
        val stopIntent = Intent(this, UsageMonitorService::class.java).apply {
            action = UsageMonitorService.ACTION_STOP_SERVICE
        }
        startService(stopIntent)
        stopService(stopIntent)
        updateUiState()
    }

    private fun updateServiceStatusText(isRunning: Boolean) {
        if (isRunning) {
            binding.welcomeTitle.text = "CURA_SYSTEM: ACTIVE"
            binding.welcomeTitle.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.hudFlavorText.text = "OBSERVATION MODE / ACTIVE"
        } else {
            binding.welcomeTitle.text = "CURA_SYSTEM: STANDBY"
            binding.welcomeTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.hudFlavorText.text = "OBSERVATION MODE / STANDBY"
        }
    }

    private fun formatHms(totalSec: Long): String {
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun updateUiState() {
        refreshMicroAction(force = false)

        val isSleepTime = DailyGoalManager.isSleepBlockAvailable()
        binding.btnHeroSleep1h.visibility = if (isSleepTime) View.VISIBLE else View.GONE
        binding.btnQuickSleep1h.visibility = if (isSleepTime) View.VISIBLE else View.GONE

        val isRunning = UsageMonitorService.isRunning
        if (pendingServiceState == isRunning) {
            pendingServiceState = null
        }
        val effectiveRunning = pendingServiceState ?: isRunning

        if (binding.switchMonitorDaemon.isChecked != effectiveRunning) {
            isUpdatingSwitchUi = true
            try {
                binding.switchMonitorDaemon.isChecked = effectiveRunning
            } finally {
                isUpdatingSwitchUi = false
            }
        }

        updateServiceStatusText(effectiveRunning)

        // First feed timeline data to graph so app summaries are up to date
        val timeline = UsageMonitorService.tracker.getTimeline()
        binding.timelineGraphView.setTimelineData(timeline)

        val session = UsageMonitorService.tracker.getCurrentSession()
        val recentOrActive = UsageMonitorService.tracker.getMostRecentOrActiveApp()
        val targetPkg = session?.packageName ?: recentOrActive?.first
        val targetAppName = session?.appName ?: recentOrActive?.second
        val totalScreenMs = UsageMonitorService.tracker.getTotalContinuousScreenDurationMs()
        val screenSec = totalScreenMs / 1000L
        val screenContinuityStr = formatHms(screenSec)

        // Calculate today's total phone usage time & today's app cumulative usage time
        val todayAppSec = UsageMonitorService.tracker.getTodayAppUsageSeconds(targetPkg)
        val todayTotalSec = UsageMonitorService.tracker.getTodayTotalPhoneUsageSeconds()
        binding.tvTodayTotalTimer.text = formatHms(todayTotalSec)

        if (session != null && !session.isBufferActive) {
            binding.tvActiveAppName.text = "${session.appName} (1日累計)"
            binding.btnExcludeCurrentApp.visibility = View.VISIBLE
            binding.btnExcludeCurrentApp.text = "設定"

            binding.tvContinuousTimer.text = formatHms(todayAppSec)
            binding.tvBufferStatus.text = "[ 画面連続: $screenContinuityStr ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_lime))
        } else if (session != null && session.isBufferActive) {
            binding.tvActiveAppName.text = "${session.appName} (1日累計 / 中断)"
            binding.btnExcludeCurrentApp.visibility = View.VISIBLE
            binding.btnExcludeCurrentApp.text = "設定"

            binding.tvContinuousTimer.text = formatHms(todayAppSec)
            binding.tvBufferStatus.text = "[ 画面連続: $screenContinuityStr ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else if (targetAppName != null && todayAppSec > 0L) {
            binding.tvActiveAppName.text = "直近: $targetAppName (1日累計)"
            binding.btnExcludeCurrentApp.visibility = View.GONE
            binding.tvContinuousTimer.text = formatHms(todayAppSec)
            binding.tvBufferStatus.text = if (screenSec > 0) "[ 画面連続: $screenContinuityStr ]" else "[ 監視待機中 ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvActiveAppName.text = "現在: 待機中 (別アプリを開くと計測)"
            binding.btnExcludeCurrentApp.visibility = View.GONE
            binding.tvContinuousTimer.text = "00:00:00"
            binding.tvBufferStatus.text = if (screenSec > 0) "[ 画面連続: $screenContinuityStr ]" else "[ 監視待機中 ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        updateHeroAssistantMessage(targetAppName, todayAppSec, screenSec, todayTotalSec)
        renderTimelineList()
        renderRankingList()
        renderExclusionsList()
        renderGhostAppsList()
    }

    private fun showTapSummaryInHero() {
        val recentOrActive = UsageMonitorService.tracker.getMostRecentOrActiveApp()
        val appName = recentOrActive?.second ?: "スマホ"
        val catalog = CuraMessageCatalog.load(this)
        binding.tvHeroCuraMessage.text = catalog.formatShortTapMessage(appName)
    }

    private fun updateHeroAssistantMessage(
        targetAppName: String?,
        todayAppSec: Long,
        screenSec: Long,
        todayTotalSec: Long
    ) {
        val appName = targetAppName ?: "スマホ"
        val screenMin = screenSec / 60L
        val appMin = todayAppSec / 60L
        val catalog = CuraMessageCatalog.load(this)

        val message = when {
            screenMin >= 30L -> {
                catalog.formatScreenIntervalMessage(
                    appName = appName,
                    appMinutes = appMin,
                    screenMinutes = screenMin,
                    remainingBudgetMinutes = (30L - screenMin).coerceAtLeast(0L),
                    todayAppSeconds = todayAppSec,
                    continuousScreenSeconds = screenSec,
                    todayTotalPhoneSeconds = todayTotalSec
                )
            }
            screenMin >= 15L -> {
                catalog.formatScreenIntervalMessage(
                    appName = appName,
                    appMinutes = appMin,
                    screenMinutes = screenMin,
                    remainingBudgetMinutes = (30L - screenMin).coerceAtLeast(0L),
                    todayAppSeconds = todayAppSec,
                    continuousScreenSeconds = screenSec,
                    todayTotalPhoneSeconds = todayTotalSec
                )
            }
            targetAppName != null && targetAppName != "待機中" && appMin >= 15L -> {
                catalog.formatAppIntervalMessage(
                    appName = appName,
                    appMinutes = appMin,
                    screenMinutes = screenMin,
                    remainingBudgetMinutes = (30L - screenMin).coerceAtLeast(0L),
                    todayAppSeconds = todayAppSec,
                    continuousScreenSeconds = screenSec,
                    todayTotalPhoneSeconds = todayTotalSec
                )
            }
            else -> {
                catalog.formatShortTapMessage(appName)
            }
        }
        binding.tvHeroCuraMessage.text = message
    }

    private fun renderTimelineList() {
        binding.layoutTimelineList.removeAllViews()
        binding.layoutMicroLogList.removeAllViews()
        binding.layoutTimelineLegend.removeAllViews()
        val timeline = UsageMonitorService.tracker.getTimeline()

        if (timeline.isEmpty()) {
            binding.layoutMicroLogAccordion.visibility = View.GONE
            binding.tvTimelineInspector.text = "グラフ待機中（04:00〜翌04:00）"
            binding.tvTimelineInspector.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            val emptyTv = TextView(this).apply {
                text = "まだアプリ切り替え履歴がありません（別アプリを開くと記録されます）"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 6, 0, 6)
            }
            binding.layoutTimelineList.addView(emptyTv)
            return
        }

        updateInspectorText(
            binding.timelineGraphView.getSelectedBar(),
            binding.timelineGraphView.getInspectedSecond()
        )

        // アプリ別カラー凡例と当日累計（上位6件を2列表示、表記は簡素化した時間表記）
        val summaries = binding.timelineGraphView.getAppSummaries().take(6)
        val palette = jp.cura.savetime.ui.TimelineGraphView.APP_COLOR_PALETTE
        summaries.chunked(2).forEach { pair ->
            val legendRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 2, 0, 2)
            }
            for (summary in pair) {
                val cleanDur = TimelineGraphCalculator.formatDurationClean(summary.totalSeconds.toLong())
                val itemTv = TextView(this).apply {
                    text = "■ ${summary.appName} ($cleanDur)"
                    setTextColor(palette[summary.colorIndex])
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                legendRow.addView(itemTv)
            }
            binding.layoutTimelineLegend.addView(legendRow)
        }

        // Partition logs into >= 5s (main) and < 5s (micro switches)
        val (mainLogs, microLogs) = TimelineGraphCalculator.partitionTimelineLogs(timeline, minDurationSeconds = 5L)

        if (microLogs.isNotEmpty()) {
            binding.layoutMicroLogAccordion.visibility = View.VISIBLE
            val microTotalSec = microLogs.sumOf { it.durationSeconds }
            val arrow = if (isMicroLogExpanded) "▾" else "▸"
            binding.tvMicroLogHeader.text =
                "$arrow その他の短時間切り替え（5秒未満：計 ${microLogs.size}回・${TimelineGraphCalculator.formatDurationClean(microTotalSec)}）"
            binding.layoutMicroLogList.visibility = if (isMicroLogExpanded) View.VISIBLE else View.GONE

            if (isMicroLogExpanded) {
                microLogs.take(25).forEach { entry ->
                    binding.layoutMicroLogList.addView(createCleanLogRow(entry, palette, isCompact = true))
                }
            }
        } else {
            binding.layoutMicroLogAccordion.visibility = View.GONE
        }

        val logsToShow = if (mainLogs.isNotEmpty()) mainLogs.take(30) else timeline.take(15)
        logsToShow.forEach { entry ->
            binding.layoutTimelineList.addView(createCleanLogRow(entry, palette, isCompact = false))
        }
    }

    private fun createCleanLogRow(
        entry: TimelineEntry,
        palette: IntArray,
        isCompact: Boolean
    ): View {
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        val isGhost = ghostAppsStore.getGhostPackages().contains(entry.packageName)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, if (isCompact) 6 else 10, 4, if (isCompact) 6 else 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showAppActionSheet(entry.packageName, entry.appName)
            }
        }

        val colorIdx = TimelineGraphCalculator.colorIndexForPackage(entry.packageName, palette.size)

        val timeTv = TextView(this).apply {
            text = timeFormatter.format(Date(entry.timestampMs))
            setTextColor(palette[colorIdx])
            textSize = if (isCompact) 10.5f else 11.5f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(155, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val nameTv = TextView(this).apply {
            text = if (isGhost) "${entry.appName} [透過]" else entry.appName
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isCompact) R.color.text_secondary else R.color.text_primary
                )
            )
            textSize = if (isCompact) 11.5f else 12.5f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val durationTv = TextView(this).apply {
            text = TimelineGraphCalculator.formatDurationClean(entry.durationSeconds)
            val durColor = when {
                entry.durationSeconds >= 900L -> R.color.accent_crimson
                entry.durationSeconds >= 300L -> R.color.accent_amber
                else -> R.color.accent_lime
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, durColor))
            textSize = if (isCompact) 11f else 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 0, 8, 0)
        }

        val chevronTv = TextView(this).apply {
            text = "›"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 14f
            setPadding(4, 0, 4, 0)
        }

        row.addView(timeTv)
        row.addView(nameTv)
        row.addView(durationTv)
        row.addView(chevronTv)
        return row
    }

    private fun renderExclusionsList() {
        binding.layoutExclusionsList.removeAllViews()
        val customExclusions = excludedAppsStore.getCustomExcludedPackages()

        if (customExclusions.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "除外指定されたアプリはありません（「+ アプリ追加」または各アプリ行のタップから追加できます）"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 11.5f
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

    private fun renderGhostAppsList() {
        binding.layoutGhostAppsList.removeAllViews()
        val ghostPkgs = ghostAppsStore.getGhostPackages()

        if (ghostPkgs.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "個別指定されたアプリはありません（「+ アプリ追加」または各アプリ行のタップからゲーム等を追加できます）"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 11.5f
                setPadding(0, 4, 0, 4)
            }
            binding.layoutGhostAppsList.addView(emptyTv)
            return
        }

        ghostPkgs.forEach { pkg ->
            val appName = AppNameResolver.resolveAppName(this, pkg)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }

            val nameTv = TextView(this).apply {
                text = "$appName [半透明・タッチ透過]"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))
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
                    ghostAppsStore.removeGhostPackage(pkg)
                    Toast.makeText(this@MainActivity, "$appName の透過モードを解除しました", Toast.LENGTH_SHORT).show()
                    updateUiState()
                }
            }

            row.addView(nameTv)
            row.addView(removeBtn)
            binding.layoutGhostAppsList.addView(row)
        }
    }

    private fun renderRankingList() {
        binding.layoutRankingList.removeAllViews()
        val summaries = binding.timelineGraphView.getAppSummaries()
        val activeSessions = UsageMonitorService.tracker.getActiveSessions()
        val records = UsageMonitorService.tracker.getRecords()

        val mergedMap = linkedMapOf<String, Pair<String, Long>>() // pkg -> (appName, totalSec)

        for (summary in summaries) {
            mergedMap[summary.packageName] = summary.appName to summary.totalSeconds.toLong()
        }
        for (s in activeSessions) {
            val sec = s.continuousDurationMs / 1000L
            val existing = mergedMap[s.packageName]?.second ?: 0L
            mergedMap[s.packageName] = s.appName to maxOf(existing, sec)
        }
        for (r in records) {
            val sec = r.maxContinuousMinutes * 60L
            val existing = mergedMap[r.packageName]?.second ?: 0L
            mergedMap[r.packageName] = r.appName to maxOf(existing, sec)
        }

        val mergedList = mergedMap.map { Triple(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.third }
            .take(5)

        if (mergedList.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.no_records)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutRankingList.addView(emptyTv)
            return
        }

        val maxSec = mergedList.firstOrNull()?.third?.coerceAtLeast(1L) ?: 1L
        val todayTotalPhoneSec = UsageMonitorService.tracker.getTodayTotalPhoneUsageSeconds().coerceAtLeast(1L)
        val density = resources.displayMetrics.density

        mergedList.forEachIndexed { index, item ->
            val (pkg, appName, totalSec) = item
            val barRatio = ((totalSec * 100L) / maxSec).toInt().coerceIn(3, 100)
            val dayPct = ((totalSec * 100L) / todayTotalPhoneSec).toInt()
            val pctLabel = if (dayPct < 1 && totalSec > 0) "<1%" else "${dayPct}%"
            val isGhost = ghostAppsStore.getGhostPackages().contains(pkg)

            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(4, (8 * density).toInt(), 4, (8 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showAppActionSheet(pkg, appName)
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val rankTv = TextView(this).apply {
                text = "#${index + 1}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_cyan))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val iconView = ImageView(this).apply {
                val iconSize = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (8 * density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                try {
                    setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (_: Exception) {
                    setImageResource(R.drawable.cura_peeking_chibi)
                }
            }

            val nameTv = TextView(this).apply {
                text = if (isGhost) "$appName [透過]" else appName
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val durationColorRes = when {
                totalSec >= 1800L -> R.color.accent_crimson
                totalSec >= 900L -> R.color.accent_amber
                else -> R.color.accent_lime
            }
            val durationColor = ContextCompat.getColor(this, durationColorRes)

            val durationTv = TextView(this).apply {
                text = "${TimelineGraphCalculator.formatDurationClean(totalSec)} ($pctLabel)"
                setTextColor(durationColor)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 0, 4, 0)
            }

            topRow.addView(rankTv)
            topRow.addView(iconView)
            topRow.addView(nameTv)
            topRow.addView(durationTv)

            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (5 * density).toInt()
                ).apply {
                    topMargin = (6 * density).toInt()
                }
                max = 100
                progress = barRatio
                progressTintList = ColorStateList.valueOf(durationColor)
                progressBackgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.bg_surface)
            }

            itemContainer.addView(topRow)
            itemContainer.addView(bar)
            binding.layoutRankingList.addView(itemContainer)
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
