package jp.cura.savetime.model

import kotlinx.serialization.Serializable

/**
 * アプリ個別利用制限設定モデル
 *
 * @param packageName 対象アプリのパッケージ名
 * @param appName 対象アプリの表示名
 * @param limitMinutes 連続利用制限時間（分）。デフォルト3分
 * @param blockDurationSeconds 制限到達時の画面ブロック時間（秒）。デフォルト180秒（3分）
 * @param isEnabled 制限の有効/無効
 */
@Serializable
data class AppLimitConfig(
    val packageName: String,
    val appName: String,
    val limitMinutes: Long = DEFAULT_LIMIT_MINUTES,
    val blockDurationSeconds: Int = DEFAULT_BLOCK_DURATION_SECONDS,
    val isEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_LIMIT_MINUTES = 3L
        const val DEFAULT_BLOCK_DURATION_SECONDS = 180 // 3分間ブロック
    }
}
