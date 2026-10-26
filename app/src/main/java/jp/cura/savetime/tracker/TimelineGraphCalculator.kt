package jp.cura.savetime.tracker

import jp.cura.savetime.manager.DailyGoalManager
import jp.cura.savetime.model.TimelineEntry
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * 1日（86,400秒）の横軸と秒単位の縦軸にマッピングされたタイムライングラフ要素
 */
data class TimelineBar(
    val packageName: String,
    val appName: String,
    val startSecondOfDay: Int,
    val endSecondOfDay: Int,
    val durationSeconds: Long,
    val timestampMs: Long
)

/**
 * アプリごとの1日合計秒数サマリ（凡例用）
 */
data class AppDaySummary(
    val packageName: String,
    val appName: String,
    val totalSeconds: Long,
    val colorIndex: Int
)

/**
 * 横軸 86,400秒（24h × 60m × 60s）・縦軸 秒（s）のタイムライングラフ座標計算ロジック
 */
object TimelineGraphCalculator {

    const val SECONDS_PER_DAY: Int = 86_400
    const val MILLIS_PER_DAY: Long = 86_400_000L

    private val NICE_SECOND_SCALES = longArrayOf(
        60L, 120L, 180L, 300L, 600L, 900L, 1200L, 1800L, 3600L, 7200L
    )

    /**
     * 指定時刻のローカル当日（朝4時基準の論理日）の開始エポックミリ秒を取得
     */
    fun getStartOfDayMs(
        nowMs: Long = System.currentTimeMillis(),
        baseHour: Int = DailyGoalManager.LOGICAL_DAY_BASE_HOUR,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        return DailyGoalManager.getLogicalDayStartMs(nowMs, baseHour, timeZone)
    }

    /**
     * タイムスタンプを当日の経過秒数（0 .. 86400）に変換
     */
    fun toSecondOfDay(timestampMs: Long, startOfDayMs: Long): Int {
        val diffSec = (timestampMs - startOfDayMs) / 1000L
        return diffSec.coerceIn(0L, SECONDS_PER_DAY.toLong()).toInt()
    }

    /**
     * タイムライン履歴を当日の 0..86400 秒座標のバー一覧に変換（時系列昇順）
     */
    fun buildDayBars(
        entries: List<TimelineEntry>,
        startOfDayMs: Long
    ): List<TimelineBar> {
        val endOfDayMs = startOfDayMs + MILLIS_PER_DAY
        val result = mutableListOf<TimelineBar>()

        for (entry in entries) {
            val rawStartMs = entry.timestampMs
            val rawDurationSec = entry.durationSeconds.coerceAtLeast(1L)
            val rawEndMs = rawStartMs + rawDurationSec * 1000L

            // 当日の [startOfDayMs, endOfDayMs] と重ならないエントリは除外
            if (rawEndMs <= startOfDayMs || rawStartMs >= endOfDayMs) {
                continue
            }

            val clampedStartMs = rawStartMs.coerceAtLeast(startOfDayMs)
            val clampedEndMs = rawEndMs.coerceAtMost(endOfDayMs)
            val startSec = ((clampedStartMs - startOfDayMs) / 1000L).toInt().coerceIn(0, SECONDS_PER_DAY - 1)
            val clampedDurationSec = ((clampedEndMs - clampedStartMs) / 1000L).coerceAtLeast(1L)
            val endSec = (startSec + clampedDurationSec.toInt()).coerceIn(startSec + 1, SECONDS_PER_DAY)

            result.add(
                TimelineBar(
                    packageName = entry.packageName,
                    appName = entry.appName,
                    startSecondOfDay = startSec,
                    endSecondOfDay = endSec,
                    durationSeconds = clampedDurationSec,
                    timestampMs = clampedStartMs
                )
            )
        }

        result.sortBy { it.startSecondOfDay }
        return result
    }

    /**
     * 縦軸（単位：秒）の最大目盛り値を算出する（最小60秒）
     */
    fun computeYAxisMaxSeconds(bars: List<TimelineBar>): Long {
        val maxSec = bars.maxOfOrNull { it.durationSeconds } ?: 0L
        if (maxSec <= 60L) return 60L
        for (scale in NICE_SECOND_SCALES) {
            if (maxSec <= scale) return scale
        }
        // 7200秒（2時間）を超える場合は 1800秒単位で切り上げ
        return ((maxSec + 1799L) / 1800L) * 1800L
    }

    /**
     * 横軸の秒位置（0..86400）に対応するバーを検索する（タップ・スクラブ検査用）
     */
    fun findBarAtSecond(
        bars: List<TimelineBar>,
        targetSecond: Int,
        toleranceSeconds: Int
    ): TimelineBar? {
        if (bars.isEmpty()) return null

        // 1. 区間内に直接含まれるバーがあれば優先（複数重なる場合はより新しい/長い方）
        val directMatches = bars.filter { targetSecond in it.startSecondOfDay..it.endSecondOfDay }
        if (directMatches.isNotEmpty()) {
            return directMatches.maxByOrNull { it.durationSeconds }
        }

        // 2. 許容誤差内で最も近いバーを返す
        var bestBar: TimelineBar? = null
        var minDistance = Int.MAX_VALUE
        for (bar in bars) {
            val dist = when {
                targetSecond < bar.startSecondOfDay -> bar.startSecondOfDay - targetSecond
                targetSecond > bar.endSecondOfDay -> targetSecond - bar.endSecondOfDay
                else -> 0
            }
            if (dist <= toleranceSeconds && dist < minDistance) {
                minDistance = dist
                bestBar = bar
            }
        }
        return bestBar
    }

    /**
     * アプリ別の当日累計秒数とカラーインデックスを集計
     */
    fun buildAppSummaries(bars: List<TimelineBar>, paletteSize: Int): List<AppDaySummary> {
        val map = linkedMapOf<String, Pair<String, Long>>()
        for (bar in bars) {
            val prev = map[bar.packageName]
            if (prev == null) {
                map[bar.packageName] = bar.appName to bar.durationSeconds
            } else {
                map[bar.packageName] = prev.first to (prev.second + bar.durationSeconds)
            }
        }
        return map.entries
            .sortedByDescending { it.value.second }
            .map { (pkg, pair) ->
                AppDaySummary(
                    packageName = pkg,
                    appName = pair.first,
                    totalSeconds = pair.second,
                    colorIndex = colorIndexForPackage(pkg, paletteSize)
                )
            }
    }

    fun colorIndexForPackage(packageName: String, paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        return abs(packageName.hashCode()) % paletteSize
    }

    /**
     * 0..86400 の経過秒数を "HH:mm:ss" 形式にフォーマット
     * baseHour を指定することで論理日（例: 朝4時開始なら 0s -> 04:00:00）に対応
     */
    fun formatSecondOfDay(secondOfDay: Int, baseHour: Int = 0): String {
        val clamped = secondOfDay.coerceIn(0, SECONDS_PER_DAY)
        if (baseHour == 0 && clamped == SECONDS_PER_DAY) return "24:00:00"
        val totalSec = clamped + baseHour * 3600
        val h = (totalSec / 3600) % 24
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /**
     * 0..86400 の経過秒数を "HH:mm" 形式にフォーマット
     * baseHour を指定することで論理日（例: 朝4時開始なら 0s -> 04:00）に対応
     */
    fun formatHourMinute(secondOfDay: Int, baseHour: Int = 0): String {
        val clamped = secondOfDay.coerceIn(0, SECONDS_PER_DAY)
        if (baseHour == 0 && clamped == SECONDS_PER_DAY) return "24:00"
        val totalSec = clamped + baseHour * 3600
        val h = (totalSec / 3600) % 24
        val m = (totalSec % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    /**
     * 重複表記（例: "76秒 (1m16s)"）を排し、統一された単一フォーマット（"1分16秒" / "45秒"）に変換する
     */
    fun formatDurationClean(seconds: Long): String {
        val clamped = seconds.coerceAtLeast(0L)
        val hours = clamped / 3600L
        val mins = (clamped % 3600L) / 60L
        val secs = clamped % 60L
        return when {
            hours > 0L -> "${hours}時間${String.format(Locale.US, "%02d", mins)}分"
            mins > 0L && secs > 0L -> "${mins}分${secs}秒"
            mins > 0L -> "${mins}分"
            else -> "${secs}秒"
        }
    }

    /**
     * タイムラインログを「5秒以上の主要ログ」と「5秒未満の極小切り替えログ」に分離する
     */
    fun partitionTimelineLogs(
        entries: List<TimelineEntry>,
        minDurationSeconds: Long = 5L
    ): Pair<List<TimelineEntry>, List<TimelineEntry>> {
        val mainLogs = mutableListOf<TimelineEntry>()
        val microLogs = mutableListOf<TimelineEntry>()
        for (entry in entries) {
            if (entry.durationSeconds >= minDurationSeconds) {
                mainLogs.add(entry)
            } else {
                microLogs.add(entry)
            }
        }
        return mainLogs to microLogs
    }
}
