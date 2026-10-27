package jp.cura.savetime.manager

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar
import java.util.TimeZone

/**
 * 24時間ウェイト配分テンプレート
 */
data class HourlyWeightTemplate(
    val type: ScheduleTemplateType,
    val weights: IntArray
) {
    val totalWeight: Int = weights.sum()

    init {
        require(weights.size == 24) { "Weights array must have exactly 24 elements (0..23 hours)" }
        require(totalWeight > 0) { "Sum of weights must be greater than zero" }
    }

    fun getWeight(hourOfDay: Int): Int {
        val clampedHour = hourOfDay.coerceIn(0, 23)
        return weights[clampedHour]
    }
}

/**
 * 時間帯別ウェイト配分と余り時間の繰り越し（貯金）管理クラス
 */
class HourlyBudgetManager(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "cura_hourly_budget_prefs"
        private const val KEY_LAST_HOUR_BUCKET = "last_hour_bucket"
        private const val KEY_LAST_LOGICAL_DAY = "last_logical_day"
        private const val KEY_SAVINGS_SECONDS = "savings_seconds"
        private const val KEY_LAST_AVAILABLE_BUDGET = "last_available_budget"

        // 平日・授業ありテンプレート:
        // 深夜0〜5時は0、朝6〜8時は1、講義9〜16時は昼休みと夕方のみ1で他0、夕方〜夜17〜21時は多めの2〜3、就寝前22〜23時は2〜1
        val TEMPLATE_WEEKDAY = HourlyWeightTemplate(
            type = ScheduleTemplateType.WEEKDAY_CLASS,
            weights = intArrayOf(
                0, 0, 0, 0, 0, 0, // 0..5時 (睡眠・早朝)
                1, 1, 1,          // 6..8時 (起床・準備)
                0, 0, 0, 1, 0, 0, 0, 1, // 9..16時 (授業・日中)
                2, 3, 3, 3, 3,    // 17..21時 (放課後・夕方〜夜自由)
                2, 1              // 22..23時 (就寝前)
            )
        )

        // 休日・フリーテンプレート:
        // 深夜0〜6時は0、朝7〜8時は1、日中9〜21時は全体的に均等な2、夜22〜23時は1
        val TEMPLATE_HOLIDAY = HourlyWeightTemplate(
            type = ScheduleTemplateType.HOLIDAY_FREE,
            weights = intArrayOf(
                0, 0, 0, 0, 0, 0, 0, // 0..6時 (睡眠)
                1, 1,                // 7..8時 (朝)
                2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // 9..21時 (日中均等)
                1, 1                 // 22..23時 (夜)
            )
        )

        fun getTemplate(type: ScheduleTemplateType): HourlyWeightTemplate {
            return when (type) {
                ScheduleTemplateType.WEEKDAY_CLASS -> TEMPLATE_WEEKDAY
                ScheduleTemplateType.HOLIDAY_FREE -> TEMPLATE_HOLIDAY
            }
        }

        /**
         * 毎時のチャージ量（秒）を計算
         * 計算式： 毎時のチャージ量 = 1日の目標時間 × (現在の時間のウェイト / 24時間分のウェイト合計)
         */
        fun calculateHourlyChargeSeconds(
            dailyGoalSeconds: Long,
            hourOfDay: Int,
            template: HourlyWeightTemplate
        ): Long {
            if (dailyGoalSeconds <= 0L) return 0L
            val weight = template.getWeight(hourOfDay)
            if (weight <= 0) return 0L
            val ratio = weight.toDouble() / template.totalWeight.toDouble()
            return (dailyGoalSeconds * ratio).toLong()
        }
    }

    private var memoryLastHourBucket: Long = -1L
    private var memoryLastLogicalDay: Long = -1L
    private var memorySavingsSeconds: Long = 0L
    private var memoryLastAvailableBudget: Long = 0L

    init {
        loadState()
    }

    private fun loadState() {
        val p = prefs ?: return
        memoryLastHourBucket = p.getLong(KEY_LAST_HOUR_BUCKET, -1L)
        memoryLastLogicalDay = p.getLong(KEY_LAST_LOGICAL_DAY, -1L)
        memorySavingsSeconds = p.getLong(KEY_SAVINGS_SECONDS, 0L)
        memoryLastAvailableBudget = p.getLong(KEY_LAST_AVAILABLE_BUDGET, 0L)
    }

    private fun saveState() {
        val p = prefs ?: return
        p.edit()
            .putLong(KEY_LAST_HOUR_BUCKET, memoryLastHourBucket)
            .putLong(KEY_LAST_LOGICAL_DAY, memoryLastLogicalDay)
            .putLong(KEY_SAVINGS_SECONDS, memorySavingsSeconds)
            .putLong(KEY_LAST_AVAILABLE_BUDGET, memoryLastAvailableBudget)
            .apply()
    }

    /**
     * 現在の繰り越し貯金（秒）
     */
    fun getSavingsSeconds(): Long = memorySavingsSeconds

    /**
     * 1時間単位の進行状態を更新し、現在の「有効予算」と「残枠」を算出
     *
     * @param nowMs 現在時刻エポックミリ秒
     * @param dailyGoalSeconds 当日の目標使用秒数
     * @param templateType 適用するテンプレート種別
     * @param previousHourUsedSeconds 前回の1時間で実際に使用された秒数
     * @param currentHourUsageSeconds 現在の1時間で使用された秒数
     * @param timeZone タイムゾーン
     */
    fun updateHourlyBudget(
        nowMs: Long = System.currentTimeMillis(),
        dailyGoalSeconds: Long,
        templateType: ScheduleTemplateType,
        previousHourUsedSeconds: Long = 0L,
        currentHourUsageSeconds: Long = 0L,
        timeZone: TimeZone = TimeZone.getDefault()
    ): HourlyBudgetStatus {
        val currentHourBucket = nowMs / (3600_000L)
        val currentLogicalDay = DailyGoalManager.getLogicalDayStartMs(nowMs, baseHour = 4, timeZone = timeZone)
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMs }
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val template = getTemplate(templateType)

        // 初回初期化
        if (memoryLastHourBucket == -1L) {
            memoryLastHourBucket = currentHourBucket
            memoryLastLogicalDay = currentLogicalDay
            val charge = calculateHourlyChargeSeconds(dailyGoalSeconds, hourOfDay, template)
            memoryLastAvailableBudget = charge + memorySavingsSeconds
            saveState()
        }

        // 1時間経過の検知（毎正時またぎ）
        if (currentHourBucket != memoryLastHourBucket) {
            // 前の時間の余り時間（使わなかった時間）を計算して繰り越し（貯金）
            val unusedSeconds = (memoryLastAvailableBudget - previousHourUsedSeconds).coerceAtLeast(0L)

            // 朝4時の論理日更新を跨いだ場合は貯金をリセットして新日開始
            if (currentLogicalDay != memoryLastLogicalDay) {
                memorySavingsSeconds = 0L
                memoryLastLogicalDay = currentLogicalDay
            } else {
                memorySavingsSeconds = unusedSeconds
            }

            memoryLastHourBucket = currentHourBucket
            val charge = calculateHourlyChargeSeconds(dailyGoalSeconds, hourOfDay, template)
            memoryLastAvailableBudget = charge + memorySavingsSeconds
            saveState()
        }

        val hourlyCharge = calculateHourlyChargeSeconds(dailyGoalSeconds, hourOfDay, template)
        val totalAvailable = hourlyCharge + memorySavingsSeconds
        memoryLastAvailableBudget = totalAvailable
        val remaining = (totalAvailable - currentHourUsageSeconds).coerceAtLeast(0L)

        return HourlyBudgetStatus(
            hourOfDay = hourOfDay,
            templateType = templateType,
            hourlyChargeSeconds = hourlyCharge,
            carriedSavingsSeconds = memorySavingsSeconds,
            totalAvailableBudgetSeconds = totalAvailable,
            usedSeconds = currentHourUsageSeconds,
            remainingSeconds = remaining
        )
    }
}

/**
 * 1時間の予算計算結果ステータス
 */
data class HourlyBudgetStatus(
    val hourOfDay: Int,
    val templateType: ScheduleTemplateType,
    val hourlyChargeSeconds: Long,
    val carriedSavingsSeconds: Long,
    val totalAvailableBudgetSeconds: Long,
    val usedSeconds: Long,
    val remainingSeconds: Long
) {
    val remainingMinutes: Long = remainingSeconds / 60L
    val savingsMinutes: Long = carriedSavingsSeconds / 60L
    val chargeMinutes: Long = hourlyChargeSeconds / 60L
    val totalAvailableMinutes: Long = totalAvailableBudgetSeconds / 60L
}
