package jp.cura.savetime.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import jp.cura.savetime.R
import jp.cura.savetime.limiter.FlexibleUsageLimitManager
import jp.cura.savetime.tracker.TimelineGraphCalculator
import jp.cura.savetime.util.CuraMessageCatalog
import java.util.Locale
import kotlin.math.abs

/**
 * 画面端からCuraが覗き込む常駐オーバーレイマネージャー
 * - 2段階開閉（アコーディオン）形式の吹き出しUI
 *   - デフォルト（閉）: ヘッダー、アプリ名＋画面連続時間＋[▼詳細]、短い通常セリフ、30秒ブロックボタン
 *   - 詳細展開（開）: 1日のアプリ累計、1日のスマホ累計、1時間の残枠/警告 ＋ 3つの時間を読み上げる詳細セリフ
 * - ゲーム・指定アプリでの「半透明＋当たり判定なし（FLAG_NOT_TOUCHABLE）」モード切り替え
 * - 吹き出し内の行動ボタン押下による「30秒フルスクリーン画面ブロック」機能
 */
class CuraOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val limitManager = FlexibleUsageLimitManager()
    private var messageCatalog: CuraMessageCatalog = CuraMessageCatalog.load(context)

    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var layoutBalloon: LinearLayout? = null
    private var layoutBalloonSummaryRow: LinearLayout? = null
    private var layoutBalloonDetails: LinearLayout? = null
    private var tvBalloonAppName: TextView? = null
    private var tvBalloonContinuousDuration: TextView? = null
    private var tvBalloonDetailToggle: TextView? = null
    private var tvBalloonAppDayTotal: TextView? = null
    private var tvBalloonPhoneDayTotal: TextView? = null
    private var tvBalloonDialogue: TextView? = null
    private var tvBalloonBudgetRemaining: TextView? = null
    private var tvBalloonRuleTag: TextView? = null
    private var tvBalloonAlertWarning: TextView? = null
    private var btnBalloonBlock30s: Button? = null
    private var layoutSideHandle: FrameLayout? = null
    private var ivCuraPeeking: ImageView? = null

    // 30秒フルスクリーンブロック用オーバーレイ
    private var blockerView: View? = null
    private var tvBlockerCountdown: TextView? = null
    private var blockerRemainingSec: Int = 0
    private val blockerTickRunnable = object : Runnable {
        override fun run() {
            blockerRemainingSec -= 1
            if (blockerRemainingSec <= 0) {
                stopThirtySecondBlock()
            } else {
                updateBlockerCountdownText()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private var isBalloonVisible = false
    private var isDetailsExpanded = false
    private var currentGhostMode = false
    private val autoHideRunnable = Runnable { hideBalloon() }

    private var lastAppName: String = "待機中"
    private var lastAppMinutes: Long = 0L
    private var lastScreenMinutes: Long = 0L
    private var lastRemainingBudget: Long = 30L
    private var lastTodayAppSeconds: Long = 0L
    private var lastContinuousScreenSeconds: Long = 0L
    private var lastTodayTotalPhoneSeconds: Long = 0L

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
        layoutBalloonSummaryRow = view.findViewById(R.id.layoutBalloonSummaryRow)
        layoutBalloonDetails = view.findViewById(R.id.layoutBalloonDetails)
        tvBalloonAppName = view.findViewById(R.id.tvBalloonAppName)
        tvBalloonContinuousDuration = view.findViewById(R.id.tvBalloonContinuousDuration)
        tvBalloonDetailToggle = view.findViewById(R.id.tvBalloonDetailToggle)
        tvBalloonAppDayTotal = view.findViewById(R.id.tvBalloonAppDayTotal)
        tvBalloonPhoneDayTotal = view.findViewById(R.id.tvBalloonPhoneDayTotal)
        tvBalloonDialogue = view.findViewById(R.id.tvBalloonDialogue)
        tvBalloonBudgetRemaining = view.findViewById(R.id.tvBalloonBudgetRemaining)
        tvBalloonRuleTag = view.findViewById(R.id.tvBalloonRuleTag)
        tvBalloonAlertWarning = view.findViewById(R.id.tvBalloonAlertWarning)
        btnBalloonBlock30s = view.findViewById(R.id.btnBalloonBlock30s)
        layoutSideHandle = view.findViewById(R.id.layoutSideHandle)
        ivCuraPeeking = view.findViewById(R.id.ivCuraPeeking)

        // 吹き出し本体または [▼詳細] サマリ行・セリフ枠のタップで詳細アコーディオンを開閉
        val detailsToggleListener = View.OnClickListener {
            if (!currentGhostMode) {
                toggleBalloonDetails()
            }
        }
        layoutBalloon?.setOnClickListener(detailsToggleListener)
        layoutBalloonSummaryRow?.setOnClickListener(detailsToggleListener)
        tvBalloonDetailToggle?.setOnClickListener(detailsToggleListener)
        tvBalloonDialogue?.setOnClickListener(detailsToggleListener)

        btnBalloonBlock30s?.setOnClickListener {
            startThirtySecondBlock(30)
        }

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

    /**
     * 吹き出し内の詳細データ枠（VISIBLE / GONE）をアニメーション付きでトグルし、
     * 開閉状態に応じてセリフ枠を「3つの時間を読み上げる詳細セリフ」と「数字を含まない短い通常セリフ」に切り替える
     */
    private fun toggleBalloonDetails() {
        setDetailsExpanded(expanded = !isDetailsExpanded, animate = true)
        updateDialogueForDetailsState()
        // 詳細を開いた時はじっくり読めるよう自動非表示タイマーを延長
        showBalloon(autoHideSec = if (isDetailsExpanded) 12 else 8)
    }

    private fun setDetailsExpanded(expanded: Boolean, animate: Boolean) {
        isDetailsExpanded = expanded
        if (animate) {
            layoutBalloon?.let { container ->
                val transition = AutoTransition().apply {
                    duration = 180L
                }
                TransitionManager.beginDelayedTransition(container, transition)
            }
        }
        layoutBalloonDetails?.visibility = if (expanded) View.VISIBLE else View.GONE
        tvBalloonDetailToggle?.text = if (expanded) "[▲詳細]" else "[▼詳細]"
    }

    /**
     * 詳細が開いている時は「3つの時間（appDay, phoneDay, screenCont）を読み上げる詳細メッセージ」、
     * 閉じている時（通常時）は「数字を含まない短いメッセージ」にセリフ枠を設定する
     */
    private fun updateDialogueForDetailsState() {
        tvBalloonDialogue?.text = messageCatalog.formatBalloonTapMessage(
            isDetailsExpanded = isDetailsExpanded,
            appName = lastAppName,
            appMinutes = lastTodayAppSeconds / 60L,
            screenMinutes = lastScreenMinutes,
            remainingBudgetMinutes = lastRemainingBudget,
            todayAppSeconds = lastTodayAppSeconds,
            continuousScreenSeconds = lastContinuousScreenSeconds,
            todayTotalPhoneSeconds = lastTodayTotalPhoneSeconds
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling(rootView: View, layoutParams: WindowManager.LayoutParams) {
        val density = context.resources.displayMetrics.density
        // タップ時の誤移動を防ぐための遊び（デッドゾーン）と長押し猶予
        val holdDragThresholdPx = 26f * density   // 250ms以上押下時は 26dp 以上の移動でドラッグ開始
        val quickDragThresholdPx = 44f * density  // 短時間でも 44dp 以上大きくスライドした場合はドラッグ開始
        val dragGraceTimeMs = 250L

        var downTimeMs = 0L
        var initialTouchY = 0f
        var dragOriginTouchY = 0f
        var dragOriginWindowY = 0
        var isDragging = false

        layoutSideHandle?.setOnTouchListener { _, event ->
            if (currentGhostMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTimeMs = event.downTime
                    initialTouchY = event.rawY
                    dragOriginTouchY = event.rawY
                    dragOriginWindowY = layoutParams.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDeltaY = event.rawY - initialTouchY
                    val heldMs = event.eventTime - downTimeMs

                    if (!isDragging) {
                        val passedGraceHold = heldMs >= dragGraceTimeMs && abs(totalDeltaY) >= holdDragThresholdPx
                        val passedLargeSlide = abs(totalDeltaY) >= quickDragThresholdPx
                        if (passedGraceHold || passedLargeSlide) {
                            isDragging = true
                            // ドラッグ開始時点の座標を基準にすることで、閾値突破時のカクつきジャンプを防止
                            dragOriginTouchY = event.rawY
                            dragOriginWindowY = layoutParams.y
                        }
                    } else {
                        val moveDeltaY = (event.rawY - dragOriginTouchY).toInt()
                        layoutParams.y = dragOriginWindowY + moveDeltaY
                        windowManager.updateViewLayout(rootView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging && event.action == MotionEvent.ACTION_UP) {
                        if (isBalloonVisible) {
                            hideBalloon()
                        } else {
                            // デフォルト状態（詳細GONE・短い通常メッセージ）で吹き出しを開く
                            setDetailsExpanded(expanded = false, animate = false)
                            updateDialogueForDetailsState()
                            showBalloon(autoHideSec = 8)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 30秒間フルスクリーンで画面全体を覆い、あらゆるタッチ・戻る操作をブロックする
     */
    @SuppressLint("ClickableViewAccessibility")
    fun startThirtySecondBlock(durationSeconds: Int = 30) {
        if (!canDrawOverlay()) return
        hideBalloon()

        if (blockerView != null) {
            blockerRemainingSec = durationSeconds
            updateBlockerCountdownText()
            return
        }

        blockerRemainingSec = durationSeconds
        val density = context.resources.displayMetrics.density

        val container = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                // 戻るキー等のキー操作も30秒間ブロック
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5080C10"))
            setPadding((28 * density).toInt(), (32 * density).toInt(), (28 * density).toInt(), (32 * density).toInt())
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            // 画面上のすべてのタッチイベントを吸収して背面へ一切通さない
            setOnTouchListener { _, _ -> true }
        }

        val tagTv = TextView(context).apply {
            text = ":: CURA // 30s_SCREEN_LOCK_ACTIVE"
            setTextColor(ContextCompat.getColor(context, R.color.accent_cyan))
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val charImage = ImageView(context).apply {
            setImageResource(R.drawable.cura_peeking_chibi)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                (150 * density).toInt(),
                (150 * density).toInt()
            ).apply {
                topMargin = (20 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        }

        val messageTv = TextView(context).apply {
            text = "マスター、30秒だけスマホを置いて\nお目々を休めましょうっ！\n\n画面を伏せて深呼吸してくださいね。"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(6f * density, 1f)
        }

        val countdownTv = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent_crimson))
            textSize = 48f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (24 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        }
        tvBlockerCountdown = countdownTv

        val noteTv = TextView(context).apply {
            text = "[ カウントが 00:00 になるまで画面操作はロックされています ]"
            setTextColor(ContextCompat.getColor(context, R.color.accent_lime))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }

        container.addView(tagTv)
        container.addView(charImage)
        container.addView(messageTv)
        container.addView(countdownTv)
        container.addView(noteTv)

        val blockerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        blockerView = container
        updateBlockerCountdownText()

        try {
            windowManager.addView(container, blockerParams)
            container.requestFocus()
            mainHandler.removeCallbacks(blockerTickRunnable)
            mainHandler.postDelayed(blockerTickRunnable, 1000L)
        } catch (_: Exception) {
            blockerView = null
        }
    }

    private fun updateBlockerCountdownText() {
        val sec = blockerRemainingSec.coerceAtLeast(0)
        tvBlockerCountdown?.text = String.format(Locale.US, "00:%02d", sec)
    }

    fun stopThirtySecondBlock() {
        mainHandler.removeCallbacks(blockerTickRunnable)
        blockerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        blockerView = null
        tvBlockerCountdown = null
        blockerRemainingSec = 0
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

        // ゴーストモード時はボタンや詳細トグルを非表示にして背面を透過、通常時は表示
        btnBalloonBlock30s?.visibility = if (isGhost) View.GONE else View.VISIBLE
        tvBalloonDetailToggle?.visibility = if (isGhost) View.GONE else View.VISIBLE
        ivCuraPeeking?.alpha = if (isGhost) 0.35f else 1.0f
        layoutBalloon?.alpha = if (isGhost) 0.72f else 0.96f
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
        setDetailsExpanded(expanded = false, animate = false)
        layoutBalloon?.visibility = View.GONE
        isBalloonVisible = false
    }

    /**
     * トラッカーからの状態更新を受け取り、
     * 1) ゲーム等の半透明・当たり判定なしモード制御
     * 2) 常時表示行（アプリ名＋画面連続時間）および詳細枠（1日アプリ累計・1日スマホ累計・残枠/警告）の更新
     * 3) アプリ5分ごと・画面5分ごとのメッセージ表示
     * を実行する
     */
    fun updateState(
        packageName: String? = null,
        appName: String,
        continuousDurationMs: Long,
        totalScreenDurationMs: Long,
        todayAppDurationSec: Long = continuousDurationMs / 1000L,
        todayTotalPhoneDurationSec: Long = maxOf(todayAppDurationSec, totalScreenDurationMs / 1000L),
        isBuffer: Boolean,
        isGhostMode: Boolean = false
    ) {
        mainHandler.post {
            if (overlayView == null) return@post

            applyGhostModeState(isGhostMode)

            val appMinutes = continuousDurationMs / (60 * 1000L)
            val screenSecondsTotal = totalScreenDurationMs / 1000L
            val screenMinutes = totalScreenDurationMs / (60 * 1000L)

            val activeRule = limitManager.getActiveRule()
            val effectiveMinutes = maxOf(appMinutes, screenMinutes)
            val remainingBudget = limitManager.calculateRemainingHourlyBudget(effectiveMinutes)
            val isExceeded = limitManager.isContinuousLimitExceeded(effectiveMinutes)

            lastAppName = appName
            lastAppMinutes = appMinutes
            lastScreenMinutes = screenMinutes
            lastRemainingBudget = remainingBudget
            lastTodayAppSeconds = todayAppDurationSec
            lastContinuousScreenSeconds = screenSecondsTotal
            lastTodayTotalPhoneSeconds = todayTotalPhoneDurationSec

            val modeSuffix = if (isGhostMode) " [透過]" else ""
            val appDayStr = TimelineGraphCalculator.formatDurationClean(todayAppDurationSec)
            val screenContStr = TimelineGraphCalculator.formatDurationClean(screenSecondsTotal)
            val phoneDayStr = TimelineGraphCalculator.formatDurationClean(todayTotalPhoneDurationSec)

            // 1. 常時表示要素の更新（ヘッダー、アプリ名、画面連続時間）
            tvBalloonAppName?.text = "$appName$modeSuffix"
            tvBalloonRuleTag?.text = "[${activeRule.name}]"
            tvBalloonContinuousDuration?.text = "画面連続: $screenContStr"

            // 2. タップ開閉する詳細データ枠の更新（1日のアプリ累計、1日のスマホ累計、残枠、上限警告）
            tvBalloonAppDayTotal?.text = "1日のアプリ累計: $appDayStr"
            tvBalloonPhoneDayTotal?.text = "1日のスマホ累計: $phoneDayStr"
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
                            remainingBudgetMinutes = remainingBudget,
                            todayAppSeconds = todayAppDurationSec,
                            continuousScreenSeconds = screenSecondsTotal,
                            todayTotalPhoneSeconds = todayTotalPhoneDurationSec
                        )
                    )
                }
                if (screenTriggered) {
                    messages.add(
                        messageCatalog.formatScreenIntervalMessage(
                            appName = appName,
                            appMinutes = appMinutes,
                            screenMinutes = screenMinutes,
                            remainingBudgetMinutes = remainingBudget,
                            todayAppSeconds = todayAppDurationSec,
                            continuousScreenSeconds = screenSecondsTotal,
                            todayTotalPhoneSeconds = todayTotalPhoneDurationSec
                        )
                    )
                }
                if (!isBalloonVisible) {
                    setDetailsExpanded(expanded = false, animate = false)
                }
                if (!isDetailsExpanded) {
                    tvBalloonDialogue?.text = messages.joinToString("\n")
                }
                // ボタンを押す余裕があるよう10秒間表示（ゲーム透過時は6秒）
                showBalloon(autoHideSec = if (isGhostMode) 6 else 10)
            }
        }
    }

    fun removeOverlay() {
        mainHandler.removeCallbacks(autoHideRunnable)
        stopThirtySecondBlock()
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }
}
