package jp.cura.savetime.model

import kotlinx.serialization.Serializable

/**
 * アプリの連続利用状態を表すデータモデル
 */
@Serializable
data class AppSession(
    val packageName: String,
    val appName: String,
    val startedAtMs: Long,
    var lastActiveAtMs: Long,
    var continuousDurationMs: Long = 0L,
    var isBufferActive: Boolean = false,
    var bufferStartedAtMs: Long = 0L
)

/**
 * アプリごとの集計記録（最大連続使用時間など）
 */
@Serializable
data class ContinuousRecord(
    val packageName: String,
    val appName: String,
    var maxContinuousMinutes: Long = 0L,
    var totalUsageMinutes: Long = 0L,
    var sessionCount: Int = 0
)

/**
 * 連続利用しきい値警告
 */
data class ThresholdAlert(
    val thresholdMinutes: Long,
    val packageName: String,
    val appName: String,
    val continuousMinutes: Long,
    val message: String
)
