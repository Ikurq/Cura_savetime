package jp.cura.savetime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import jp.cura.savetime.model.TimelineEntry
import jp.cura.savetime.tracker.AppDaySummary
import jp.cura.savetime.tracker.TimelineBar
import jp.cura.savetime.tracker.TimelineGraphCalculator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 横軸を 86,400秒（24時間×60分×60秒）、縦軸の単位を「秒 (s)」として
 * いつ・どのアプリを・何秒使っていたかを可視化するタイムライングラフView
 */
class TimelineGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        val APP_COLOR_PALETTE = intArrayOf(
            Color.parseColor("#00F0FF"), // Cyan
            Color.parseColor("#39FF14"), // Lime
            Color.parseColor("#FFB800"), // Amber
            Color.parseColor("#FF2A54"), // Crimson
            Color.parseColor("#B56BFF"), // Violet
            Color.parseColor("#FF7A33"), // Orange
            Color.parseColor("#00E5A3"), // Mint
            Color.parseColor("#5CA8FF")  // Sky Blue
        )
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var dayBars: List<TimelineBar> = emptyList()
    private var appSummaries: List<AppDaySummary> = emptyList()
    private var currentSecondOfDay: Int = 0

    // 表示ウィンドウ（0 .. 86400秒のうち表示中の範囲）
    private var windowStartSec: Int = 0
    private var windowSpanSec: Int = TimelineGraphCalculator.SECONDS_PER_DAY

    // ユーザーに選択・検査されている秒位置とバー
    private var inspectedSecond: Int? = null
    private var selectedBar: TimelineBar? = null

    var onInspectionChanged: ((TimelineBar?, Int) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22303C")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D566E")
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A9BA8")
        textSize = 10f * scaledDensity
        typeface = Typeface.MONOSPACE
    }

    private val axisUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F0FF")
        textSize = 9.5f * scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val selectedBarStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private val nowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A54")
        strokeWidth = 1.2f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }

    private val cursorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val plotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B0F14")
        style = Paint.Style.FILL
    }

    private val barRect = RectF()

    private var lastTouchX = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor <= 0f) return false
                val plotLeft = getPlotLeft()
                val plotWidth = (width - plotLeft - getPlotRightMargin()).coerceAtLeast(1f)
                val focusRatio = ((detector.focusX - plotLeft) / plotWidth).coerceIn(0f, 1f)
                val focusSec = windowStartSec + (windowSpanSec * focusRatio).toInt()

                val newSpan = (windowSpanSec / factor).toInt()
                    .coerceIn(300, TimelineGraphCalculator.SECONDS_PER_DAY)
                val newStart = (focusSec - (newSpan * focusRatio).toInt())
                    .coerceIn(0, TimelineGraphCalculator.SECONDS_PER_DAY - newSpan)

                windowSpanSec = newSpan
                windowStartSec = newStart
                invalidate()
                return true
            }
        }
    )

    fun setTimelineData(entries: List<TimelineEntry>, nowMs: Long = System.currentTimeMillis()) {
        val startOfDayMs = TimelineGraphCalculator.getStartOfDayMs(nowMs)
        currentSecondOfDay = TimelineGraphCalculator.toSecondOfDay(nowMs, startOfDayMs)
        dayBars = TimelineGraphCalculator.buildDayBars(entries, startOfDayMs)
        appSummaries = TimelineGraphCalculator.buildAppSummaries(dayBars, APP_COLOR_PALETTE.size)

        // まだ検査位置が未選択なら最新のバーを自動選択
        if (inspectedSecond == null) {
            selectedBar = dayBars.lastOrNull()
        } else {
            val sec = inspectedSecond ?: currentSecondOfDay
            val tolerance = max(15, windowSpanSec / 35)
            selectedBar = TimelineGraphCalculator.findBarAtSecond(dayBars, sec, tolerance)
        }
        invalidate()
    }

    fun getAppSummaries(): List<AppDaySummary> = appSummaries

    fun getDayBars(): List<TimelineBar> = dayBars

    fun getSelectedBar(): TimelineBar? = selectedBar ?: dayBars.lastOrNull()

    fun getInspectedSecond(): Int = inspectedSecond ?: selectedBar?.startSecondOfDay ?: currentSecondOfDay

    /**
     * 表示レンジ（秒幅）を切り替える（例: 86400=24h, 21600=6h, 3600=1h, 900=15m）
     */
    fun setZoomWindowSeconds(spanSeconds: Int) {
        val clampedSpan = spanSeconds.coerceIn(300, TimelineGraphCalculator.SECONDS_PER_DAY)
        windowSpanSec = clampedSpan
        if (clampedSpan >= TimelineGraphCalculator.SECONDS_PER_DAY) {
            windowStartSec = 0
        } else {
            val centerSec = selectedBar?.startSecondOfDay ?: currentSecondOfDay
            windowStartSec = (centerSec - clampedSpan / 2)
                .coerceIn(0, TimelineGraphCalculator.SECONDS_PER_DAY - clampedSpan)
        }
        invalidate()
    }

    fun getZoomWindowSeconds(): Int = windowSpanSec

    private fun getPlotLeft(): Float = 46f * density
    private fun getPlotRightMargin(): Float = 10f * density
    private fun getPlotTop(): Float = 18f * density
    private fun getPlotBottomMargin(): Float = 26f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val plotLeft = getPlotLeft()
        val plotRight = (width - getPlotRightMargin()).coerceAtLeast(plotLeft + 10f)
        val plotTop = getPlotTop()
        val plotBottom = (height - getPlotBottomMargin()).coerceAtLeast(plotTop + 10f)
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        // プロット領域の背景
        canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, plotBgPaint)

        // 表示ウィンドウ内のバーを抽出して縦軸最大秒数を決定
        val windowEndSec = (windowStartSec + windowSpanSec).coerceAtMost(TimelineGraphCalculator.SECONDS_PER_DAY)
        val visibleBars = dayBars.filter {
            it.endSecondOfDay >= windowStartSec && it.startSecondOfDay <= windowEndSec
        }
        val yMaxSeconds = TimelineGraphCalculator.computeYAxisMaxSeconds(
            if (visibleBars.isNotEmpty()) visibleBars else dayBars
        )

        // 軸単位ヘッダ表示
        canvas.drawText("縦:秒(s)", 2f * density, 12f * density, axisUnitPaint)
        val rangeText = "横軸: ${windowStartSec}s - ${windowEndSec}s / 86400s (${TimelineGraphCalculator.formatHourMinute(windowStartSec, jp.cura.savetime.manager.DailyGoalManager.LOGICAL_DAY_BASE_HOUR)}-${TimelineGraphCalculator.formatHourMinute(windowEndSec, jp.cura.savetime.manager.DailyGoalManager.LOGICAL_DAY_BASE_HOUR)})"
        val rangeTextWidth = labelPaint.measureText(rangeText)
        canvas.drawText(
            rangeText,
            (plotRight - rangeTextWidth).coerceAtLeast(plotLeft),
            12f * density,
            labelPaint
        )

        // 縦軸（秒）グリッド線と秒ラベル (0s, 25%, 50%, 75%, 100%)
        val ySteps = 4
        for (i in 0..ySteps) {
            val ratio = i.toFloat() / ySteps
            val yValSec = (yMaxSeconds * ratio).toLong()
            val yPos = plotBottom - ratio * plotHeight

            canvas.drawLine(plotLeft, yPos, plotRight, yPos, if (i == 0) axisPaint else gridPaint)

            val yLabel = "${yValSec}s"
            val textWidth = labelPaint.measureText(yLabel)
            canvas.drawText(
                yLabel,
                (plotLeft - textWidth - 5f * density).coerceAtLeast(0f),
                yPos + 3.5f * density,
                labelPaint
            )
        }

        // 横軸（86400秒分割）グリッド線と時刻ラベル
        val xTicks = 4
        for (i in 0..xTicks) {
            val ratio = i.toFloat() / xTicks
            val secAtTick = windowStartSec + (windowSpanSec * ratio).toInt()
            val xPos = plotLeft + ratio * plotWidth

            canvas.drawLine(xPos, plotTop, xPos, plotBottom, if (i == 0) axisPaint else gridPaint)

            val timeLabel = if (windowSpanSec <= 3600) {
                TimelineGraphCalculator.formatSecondOfDay(secAtTick, jp.cura.savetime.manager.DailyGoalManager.LOGICAL_DAY_BASE_HOUR)
            } else {
                TimelineGraphCalculator.formatHourMinute(secAtTick, jp.cura.savetime.manager.DailyGoalManager.LOGICAL_DAY_BASE_HOUR)
            }
            val labelWidth = labelPaint.measureText(timeLabel)
            val alignedX = when (i) {
                0 -> xPos
                xTicks -> xPos - labelWidth
                else -> xPos - labelWidth / 2f
            }
            canvas.drawText(
                timeLabel,
                alignedX.coerceIn(0f, width - labelWidth),
                plotBottom + 15f * density,
                labelPaint
            )
        }

        // 各アプリ使用セッションバーの描画
        val minBarWidthPx = 2.8f * density
        val activeSelected = getSelectedBar()

        for (bar in visibleBars) {
            val startRatio = (bar.startSecondOfDay - windowStartSec).toFloat() / windowSpanSec.toFloat()
            val endRatio = (bar.endSecondOfDay - windowStartSec).toFloat() / windowSpanSec.toFloat()

            val rawLeft = plotLeft + startRatio * plotWidth
            val rawRight = plotLeft + endRatio * plotWidth
            val clampedLeft = rawLeft.coerceIn(plotLeft, plotRight)
            val clampedRight = max(clampedLeft + minBarWidthPx, rawRight).coerceAtMost(plotRight)

            val heightRatio = (bar.durationSeconds.toFloat() / yMaxSeconds.toFloat()).coerceIn(0.03f, 1f)
            val barTop = plotBottom - heightRatio * plotHeight

            val colorIdx = TimelineGraphCalculator.colorIndexForPackage(bar.packageName, APP_COLOR_PALETTE.size)
            barPaint.color = APP_COLOR_PALETTE[colorIdx]

            barRect.set(clampedLeft, barTop, clampedRight, plotBottom)
            canvas.drawRect(barRect, barPaint)

            if (activeSelected != null &&
                activeSelected.timestampMs == bar.timestampMs &&
                activeSelected.packageName == bar.packageName
            ) {
                canvas.drawRect(barRect, selectedBarStrokePaint)
            }
        }

        // 現在時刻（NOW）ライン
        if (currentSecondOfDay in windowStartSec..windowEndSec) {
            val nowRatio = (currentSecondOfDay - windowStartSec).toFloat() / windowSpanSec.toFloat()
            val nowX = plotLeft + nowRatio * plotWidth
            canvas.drawLine(nowX, plotTop, nowX, plotBottom, nowLinePaint)
        }

        // 検査カーソルライン
        val cursorSec = inspectedSecond ?: activeSelected?.startSecondOfDay
        if (cursorSec != null && cursorSec in windowStartSec..windowEndSec) {
            val cursorRatio = (cursorSec - windowStartSec).toFloat() / windowSpanSec.toFloat()
            val cursorX = plotLeft + cursorRatio * plotWidth
            canvas.drawLine(cursorX, plotTop, cursorX, plotBottom, cursorLinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress) {
            return true
        }

        val plotLeft = getPlotLeft()
        val plotRight = (width - getPlotRightMargin()).coerceAtLeast(plotLeft + 10f)
        val plotWidth = plotRight - plotLeft

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
                updateInspectionAtTouch(event.x, plotLeft, plotWidth)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (abs(dx) > 6f * density) {
                    isDragging = true
                }
                if (windowSpanSec < TimelineGraphCalculator.SECONDS_PER_DAY && isDragging) {
                    // ズームイン時はドラッグで横スクロールしつつ中央/タッチ位置を検査
                    val deltaSec = ((-dx / plotWidth) * windowSpanSec).toInt()
                    windowStartSec = (windowStartSec + deltaSec)
                        .coerceIn(0, TimelineGraphCalculator.SECONDS_PER_DAY - windowSpanSec)
                    lastTouchX = event.x
                }
                updateInspectionAtTouch(event.x, plotLeft, plotWidth)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateInspectionAtTouch(touchX: Float, plotLeft: Float, plotWidth: Float) {
        val ratio = ((touchX - plotLeft) / plotWidth).coerceIn(0f, 1f)
        val targetSec = (windowStartSec + (windowSpanSec * ratio).toInt())
            .coerceIn(0, TimelineGraphCalculator.SECONDS_PER_DAY)
        inspectedSecond = targetSec

        val tolerance = max(15, windowSpanSec / 30)
        selectedBar = TimelineGraphCalculator.findBarAtSecond(dayBars, targetSec, tolerance)
        onInspectionChanged?.invoke(selectedBar, targetSec)
        invalidate()
    }
}
