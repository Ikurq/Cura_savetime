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
import kotlin.math.abs

/**
 * Xperia サイドセンス風の「横からCuraが覗き込む」常駐オーバーレイマネージャー
 */
class CuraOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val limitManager = FlexibleUsageLimitManager()

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var layoutBalloon: LinearLayout? = null
    private var tvBalloonAppName: TextView? = null
    private var tvBalloonContinuousDuration: TextView? = null
    private var tvBalloonBudgetRemaining: TextView? = null
    private var tvBalloonRuleTag: TextView? = null
    private var tvBalloonAlertWarning: TextView? = null
    private var layoutSideHandle: FrameLayout? = null
    private var ivCuraPeeking: ImageView? = null

    private var isBalloonVisible = false
    private val autoHideRunnable = Runnable { hideBalloon() }

    fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
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
                        // タップ判定: 吹き出しの開閉トグル
                        toggleBalloon()
                    }
                    true
                }
                else -> false
            }
        }
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
     * トラッカーからの状態更新を受け取り、吹き出し・5分周期通知を制御
     */
    fun updateState(appName: String, continuousDurationMs: Long, totalScreenDurationMs: Long, isBuffer: Boolean) {
        mainHandler.post {
            if (overlayView == null) return@post

            val appMinutes = continuousDurationMs / (60 * 1000L)
            val appSeconds = (continuousDurationMs / 1000L) % 60
            val screenMinutes = totalScreenDurationMs / (60 * 1000L)
            val screenSeconds = (totalScreenDurationMs / 1000L) % 60

            val activeRule = limitManager.getActiveRule()
            // アプリ単体または画面連続時間の大きい方で予算と制限を判定
            val effectiveMinutes = maxOf(appMinutes, screenMinutes)
            val remainingBudget = limitManager.calculateRemainingHourlyBudget(effectiveMinutes)
            val isExceeded = limitManager.isContinuousLimitExceeded(effectiveMinutes)

            tvBalloonAppName?.text = appName
            tvBalloonRuleTag?.text = "[${activeRule.name}]"
            tvBalloonContinuousDuration?.text = "アプリ: ${appMinutes}分${appSeconds}秒\n画面連続: ${screenMinutes}分${screenSeconds}秒"
            tvBalloonBudgetRemaining?.text = "1時間の残枠: ${remainingBudget}分"

            if (isExceeded) {
                tvBalloonAlertWarning?.visibility = View.VISIBLE
                tvBalloonAlertWarning?.text = "⚠️ 連続上限（${activeRule.maxContinuousMinutes}分）超過！"
                tvBalloonContinuousDuration?.setTextColor(ContextCompat.getColor(context, R.color.accent_crimson))
            } else {
                tvBalloonAlertWarning?.visibility = View.GONE
                tvBalloonContinuousDuration?.setTextColor(ContextCompat.getColor(context, R.color.accent_lime))
            }

            // 5分ごとの定期通知チェック（画面連続時間が5分, 10分, 15分...）
            if (limitManager.shouldTriggerFiveMinuteReminder(effectiveMinutes)) {
                showBalloon(autoHideSec = 5) // 5秒後に自動消滅する控えめな吹き出し
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
