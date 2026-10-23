package jp.cura.savetime.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import jp.cura.savetime.R
import jp.cura.savetime.limiter.FlexibleUsageLimitManager
import jp.cura.savetime.util.CuraMessageCatalog
import kotlin.math.abs

/**
 * 画面端からCuraが覗き込む常駐オーバーレイマネージャー
 * - アプリ別 5分ごと / 画面全体 5分ごとのメッセージ枠表示
 * - ゲーム・指定アプリでの「半透明＋当たり判定なし（FLAG_NOT_TOUCHABLE）」モード切り替え
 */
class CuraOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val limitManager = FlexibleUsageLimitManager()
    private var messageCatalog: CuraMessageCatalog = CuraMessageCatalog.load(context)

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var layoutBalloon: LinearLayout? = null
    private var tvBalloonAppName: TextView? = null
    private var tvBalloonContinuousDuration: TextView? = null
    private var tvBalloonDialogue: TextView? = null
    private var tvBalloonBudgetRemaining: TextView? = null
    private var tvBalloonRuleTag: TextView? = null
    private var tvBalloonAlertWarning: TextView? = null
    private var layoutSideHandle: FrameLayout? = null
    private var ivCuraPeeking: ImageView? = null

    private var isBalloonVisible = false
    private var currentGhostMode = false
    private val autoHideRunnable = Runnable { hideBalloon() }

    private var lastAppName: String = "待機中"
    private var lastAppMinutes: Long = 0L
    private var lastScreenMinutes: Long = 0L
    private var lastRemainingBudget: Long = 30L

    fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun reloadMessageCatalog() {
        messageCatalog = CuraMessageCatalog.load(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay() {
        if (overlayView != null || !canDrawOverlay()) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_cura_overlay, null)
        overlayView = view

        layoutBalloon = view.findViewById(R.id.layoutBalloon)
        tvBalloonAppName = view.findViewById(R.id.tvBalloonAppName)
        tvBalloonContinuousDuration = view.findViewById(R.id.tvBalloonContinuousDuration)
        tvBalloonDialogue = view.findViewById(R.id.tvBalloonDialogue)
        tvBalloonBudgetRemaining = view.findViewById(R.id.tvBalloonBudgetRemaining)
        tvBalloonRuleTag = view.findViewById(R.id.tvBalloonRuleTag)
        tvBalloonAlertWarning = view.findViewById(R.id.tvBalloonAlertWarning)
        layoutSideHandle = view.findViewById(R.id.layoutSideHandle)
        ivCuraPeeking = view.findViewById(R.id.ivCuraPeeking)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 350
        }
        params = layoutParams

        setupTouchHandling(view, layoutParams)
        windowManager.addView(view, layoutParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling(rootView: View, layoutParams: WindowManager.LayoutParams) {
        var initialY = 0
        var initialTouchY = 0f
        var isDragging = false

        layoutSideHandle?.setOnTouchListener { _, event ->
            if (currentGhostMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams.y
                    initialTouchY = event.rawY
                    isDragging = false
                    ivCuraPeeking?.animate()?.scaleX(0.92f)?.scaleY(0.92f)?.setDuration(80)?.start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaY) > 15) {
                        isDragging = true
                        layoutParams.y = initialY + deltaY
                        windowManager.updateViewLayout(rootView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ivCuraPeeking?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                    if (!isDragging && event.action == MotionEvent.ACTION_UP) {
                        // タップ時: タップ用メッセージを表示して吹き出し開閉
                        tvBalloonDialogue?.text = messageCatalog.formatTapMessage(
                            appName = lastAppName,
                            appMinutes = lastAppMinutes,
                            screenMinutes = lastScreenMinutes,
                            remainingBudgetMinutes = lastRemainingBudget
                        )
                        toggleBalloon()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * ゲーム中や指定アプリ使用時に「半透明＋当たり判定なし（タッチ透過）」を切り替える
     */
    private fun applyGhostModeState(isGhost: Boolean) {
        val view = overlayView ?: return
        val lp = params ?: return

        if (currentGhostMode != isGhost) {
            currentGhostMode = isGhost
            lp.flags = if (isGhost) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Exception) {}
        }

        // ゴーストモード時はキャラクターを35%半透明、5分通知吹き出しも72%半透明にして背面を透過
        ivCuraPeeking?.alpha = if (isGhost) 0.35f else 1.0f
        layoutBalloon?.alpha = if (isGhost) 0.72f else 0.96f
    }

    private fun toggleBalloon() {
        if (isBalloonVisible) {
            hideBalloon()
        } else {
            showBalloon(autoHideSec = 6)
        }
    }

    private fun showBalloon(autoHideSec: Int = 0) {
        mainHandler.removeCallbacks(autoHideRunnable)
        layoutBalloon?.visibility = View.VISIBLE
        isBalloonVisible = true

        if (autoHideSec > 0) {
            mainHandler.postDelayed(autoHideRunnable, autoHideSec * 1000L)
        }
    }

    private fun hideBalloon() {
        mainHandler.removeCallbacks(autoHideRunnable)
        layoutBalloon?.visibility = View.GONE
        isBalloonVisible = false
    }

    /**
     * トラッカーからの状態更新を受け取り、
     * 1) ゲーム等の半透明・当たり判定なしモード制御
     * 2) アプリ5分ごと・画面5分ごとのメッセージ表示
     * を実行する
     */
    fun updateState(
        packageName: String? = null,
        appName: String,
        continuousDurationMs: Long,
        totalScreenDurationMs: Long,
        isBuffer: Boolean,
        isGhostMode: Boolean = false
    ) {
        mainHandler.post {
            if (overlayView == null) return@post

            applyGhostModeState(isGhostMode)

            val appMinutes = continuousDurationMs / (60 * 1000L)
            val appSeconds = (continuousDurationMs / 1000L) % 60
            val screenMinutes = totalScreenDurationMs / (60 * 1000L)
            val screenSeconds = (totalScreenDurationMs / 1000L) % 60

            val activeRule = limitManager.getActiveRule()
            val effectiveMinutes = maxOf(appMinutes, screenMinutes)
            val remainingBudget = limitManager.calculateRemainingHourlyBudget(effectiveMinutes)
            val isExceeded = limitManager.isContinuousLimitExceeded(effectiveMinutes)

            lastAppName = appName
            lastAppMinutes = appMinutes
            lastScreenMinutes = screenMinutes
            lastRemainingBudget = remainingBudget

            val modeSuffix = if (isGhostMode) " [透過]" else ""
            tvBalloonAppName?.text = "$appName$modeSuffix"
            tvBalloonRuleTag?.text = "[${activeRule.name}]"
            tvBalloonContinuousDuration?.text =
                "アプリ: ${appMinutes}分${appSeconds}秒\n画面連続: ${screenMinutes}分${screenSeconds}秒"
            tvBalloonBudgetRemaining?.text = "1時間の残枠: ${remainingBudget}分"

            if (isExceeded) {
                tvBalloonAlertWarning?.visibility = View.VISIBLE
                tvBalloonAlertWarning?.text = "連続上限（${activeRule.maxContinuousMinutes}分）超過"
                tvBalloonContinuousDuration?.setTextColor(ContextCompat.getColor(context, R.color.accent_crimson))
            } else {
                tvBalloonAlertWarning?.visibility = View.GONE
                tvBalloonContinuousDuration?.setTextColor(ContextCompat.getColor(context, R.color.accent_lime))
            }

            // 1. アプリ単体の5分ごと判定（5分, 10分, 15分...）
            val appTriggered = if (!isBuffer && !packageName.isNullOrBlank()) {
                limitManager.shouldTriggerAppFiveMinuteReminder(packageName, appMinutes)
            } else {
                false
            }

            // 2. 画面全体の5分ごと判定（5分, 10分, 15分...）
            val screenTriggered = limitManager.shouldTriggerScreenFiveMinuteReminder(screenMinutes)

            if (appTriggered || screenTriggered) {
                val messages = mutableListOf<String>()
                if (appTriggered) {
                    messages.add(
                        messageCatalog.formatAppIntervalMessage(
                            appName = appName,
                            appMinutes = appMinutes,
                            screenMinutes = screenMinutes,
                            remainingBudgetMinutes = remainingBudget
                        )
                    )
                }
                if (screenTriggered) {
                    messages.add(
                        messageCatalog.formatScreenIntervalMessage(
                            appName = appName,
                            appMinutes = appMinutes,
                            screenMinutes = screenMinutes,
                            remainingBudgetMinutes = remainingBudget
                        )
                    )
                }
                tvBalloonDialogue?.text = messages.joinToString("\n")
                // ゴーストモード（ゲーム中）でも当たり判定なし＆半透明のまま6秒間5分通知を表示する
                showBalloon(autoHideSec = 6)
            }
        }
    }

    fun removeOverlay() {
        mainHandler.removeCallbacks(autoHideRunnable)
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }
}
