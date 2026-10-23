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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import jp.cura.savetime.databinding.ActivityMainBinding
import jp.cura.savetime.service.UsageMonitorService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        if (session != null && !session.isBufferActive) {
            binding.tvActiveAppName.text = session.appName

            val totalSec = session.continuousDurationMs / 1000L
            val hours = totalSec / 3600
            val minutes = (totalSec % 3600) / 60
            val seconds = totalSec % 60
            binding.tvContinuousTimer.text = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

            binding.tvBufferStatus.text = "[ 連続計測中 ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_crimson))
        } else if (session != null && session.isBufferActive) {
            binding.tvActiveAppName.text = "${session.appName} (一時中断)"
            binding.tvBufferStatus.text = "[ バッファ猶予中 (60秒) ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
        } else {
            binding.tvActiveAppName.text = getString(R.string.no_active_session)
            binding.tvContinuousTimer.text = "00:00:00"
            binding.tvBufferStatus.text = "[ 待機中 ]"
            binding.tvBufferStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        renderRankingList()
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
