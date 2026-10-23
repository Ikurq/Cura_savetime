package jp.cura.savetime.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 他のAIにセリフを生成させてそのまま流し込めるメッセージ枠定義（JSONシリアライズ対応）
 *
 * 使用可能なプレースホルダー:
 * - {app}       : 現在開いているアプリ名（例: YouTube, X）
 * - {appMin}    : そのアプリの累計使用分数（例: 5, 10, 15...）
 * - {screenMin} : スマホ画面全体の連続操作分数（例: 5, 10, 15...）
 * - {remain}    : 1時間あたりの残り予算分数（例: 20）
 */
@Serializable
data class CuraMessageCatalogData(
    val appIntervalMessages: Map<String, List<String>> = emptyMap(),
    val screenIntervalMessages: Map<String, List<String>> = emptyMap(),
    val tapMessages: List<String> = emptyList()
)

class CuraMessageCatalog(val data: CuraMessageCatalogData) {

    fun formatAppIntervalMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long
    ): String {
        val candidates = data.appIntervalMessages[appMinutes.toString()]
            ?.takeIf { it.isNotEmpty() }
            ?: data.appIntervalMessages["default"]?.takeIf { it.isNotEmpty() }
            ?: listOf("[アプリ${appMinutes}分枠] {app} の使用が {appMin}分 経過しました。")

        val template = candidates.random()
        return applyPlaceholders(template, appName, appMinutes, screenMinutes, remainingBudgetMinutes)
    }

    fun formatScreenIntervalMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long
    ): String {
        val candidates = data.screenIntervalMessages[screenMinutes.toString()]
            ?.takeIf { it.isNotEmpty() }
            ?: data.screenIntervalMessages["default"]?.takeIf { it.isNotEmpty() }
            ?: listOf("[画面連続${screenMinutes}分枠] スマホ全体の連続使用が {screenMin}分 経過しました。")

        val template = candidates.random()
        return applyPlaceholders(template, appName, appMinutes, screenMinutes, remainingBudgetMinutes)
    }

    fun formatTapMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long
    ): String {
        val candidates = data.tapMessages.takeIf { it.isNotEmpty() }
            ?: listOf("監視中：{app}（{appMin}分） / 画面連続 {screenMin}分")
        val template = candidates.random()
        return applyPlaceholders(template, appName, appMinutes, screenMinutes, remainingBudgetMinutes)
    }

    private fun applyPlaceholders(
        template: String,
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long
    ): String {
        return template
            .replace("{app}", appName)
            .replace("{appMin}", appMinutes.toString())
            .replace("{screenMin}", screenMinutes.toString())
            .replace("{remain}", remainingBudgetMinutes.toString())
    }

    companion object {
        private const val PREFS_NAME = "cura_custom_messages_prefs"
        private const val KEY_CUSTOM_JSON = "custom_messages_json"
        private const val ASSET_FILE_NAME = "cura_messages.json"

        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun defaultCatalog(): CuraMessageCatalog {
            return CuraMessageCatalog(
                CuraMessageCatalogData(
                    appIntervalMessages = mapOf(
                        "5" to listOf("[アプリ5分枠] {app} を使い始めて {appMin}分 経過しました。"),
                        "10" to listOf("[アプリ10分枠] {app} の使用が {appMin}分 になりました。"),
                        "15" to listOf("[アプリ15分枠] {app} を {appMin}分 使用中です（残枠 {remain}分）。"),
                        "20" to listOf("[アプリ20分枠] {app} が {appMin}分 に達しました。"),
                        "25" to listOf("[アプリ25分枠] {app} を {appMin}分 継続しています。"),
                        "30" to listOf("[アプリ30分枠] {app} の使用が {appMin}分 に到達しました。一区切りつけましょう。"),
                        "35" to listOf("[アプリ35分枠] {app} を {appMin}分 使用しています。"),
                        "40" to listOf("[アプリ40分枠] {app} を {appMin}分 使用しています。"),
                        "45" to listOf("[アプリ45分枠] {app} の使用が {appMin}分 を超えました。"),
                        "50" to listOf("[アプリ50分枠] {app} を {appMin}分 使用しています。"),
                        "55" to listOf("[アプリ55分枠] {app} を {appMin}分 使用しています。"),
                        "60" to listOf("[アプリ60分枠] {app} を1時間（{appMin}分）使い続けています。"),
                        "default" to listOf("[アプリ{appMin}分枠] {app} を {appMin}分 使用中です。")
                    ),
                    screenIntervalMessages = mapOf(
                        "5" to listOf("[画面5分枠] スマホを開いてから連続 {screenMin}分 経過しました。"),
                        "10" to listOf("[画面10分枠] 画面の連続操作が {screenMin}分 になりました。"),
                        "15" to listOf("[画面15分枠] スマホ全体の連続時間が {screenMin}分 です（残枠 {remain}分）。"),
                        "20" to listOf("[画面20分枠] 画面を連続 {screenMin}分 見続けています。"),
                        "25" to listOf("[画面25分枠] スマホ連続操作 {screenMin}分 経過です。"),
                        "30" to listOf("[画面30分枠] 画面連続 {screenMin}分 に到達しました。目を休めましょう。"),
                        "35" to listOf("[画面35分枠] 画面連続 {screenMin}分 経過しています。"),
                        "40" to listOf("[画面40分枠] 画面連続 {screenMin}分 経過しています。"),
                        "45" to listOf("[画面45分枠] 画面連続 {screenMin}分 を超えました。休憩を取りましょう。"),
                        "50" to listOf("[画面50分枠] 画面連続 {screenMin}分 経過しています。"),
                        "55" to listOf("[画面55分枠] 画面連続 {screenMin}分 経過しています。"),
                        "60" to listOf("[画面60分枠] スマホを連続1時間（{screenMin}分）操作しています。"),
                        "default" to listOf("[画面{screenMin}分枠] スマホを連続 {screenMin}分 使用中です。")
                    ),
                    tapMessages = listOf(
                        "現在 {app} を {appMin}分 / 画面連続 {screenMin}分 使用中です（残枠 {remain}分）。"
                    )
                )
            )
        }

        fun fromJsonOrDefault(jsonStr: String): CuraMessageCatalog {
            return try {
                val parsed = jsonFormat.decodeFromString<CuraMessageCatalogData>(jsonStr)
                CuraMessageCatalog(parsed)
            } catch (_: Exception) {
                defaultCatalog()
            }
        }

        fun toPrettyJson(catalog: CuraMessageCatalog = defaultCatalog()): String {
            return jsonFormat.encodeToString(catalog.data)
        }

        fun load(context: Context): CuraMessageCatalog {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedJson = prefs.getString(KEY_CUSTOM_JSON, null)
            if (!savedJson.isNullOrBlank()) {
                return fromJsonOrDefault(savedJson)
            }
            return try {
                val assetJson = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
                fromJsonOrDefault(assetJson)
            } catch (_: Exception) {
                defaultCatalog()
            }
        }

        fun saveCustomJson(context: Context, jsonStr: String): Boolean {
            return try {
                val parsed = jsonFormat.decodeFromString<CuraMessageCatalogData>(jsonStr)
                if (parsed.appIntervalMessages.isEmpty() && parsed.screenIntervalMessages.isEmpty()) {
                    return false
                }
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CUSTOM_JSON, jsonStr)
                    .apply()
                true
            } catch (_: Exception) {
                false
            }
        }

        fun resetCustomJson(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CUSTOM_JSON)
                .apply()
        }

        /**
         * 他のAI（ChatGPT / Claude / Gemini等）にセリフを書かせるためのコピペ用プロンプト
         */
        val AI_GENERATION_PROMPT: String = """
あなたはスマートフォン使いすぎ防止アプリの常駐キャラクター「Cura（キュラ）」のセリフライターです。
画面右端からひょっこり覗き込んで、ユーザーに「アプリ単体の使用時間（5分刻み）」と「スマホ画面全体の連続時間（5分刻み）」を知らせる短いセリフ（1セリフ45文字以内）を作成してください。

【キャラクター設定（ここを好みに書き換えて指示してください）】
・名前：Cura（キュラ）
・性格・口調：（例：ダウナー系で少し呆れつつも心配してくれる後輩 / クールな秘書 / 毒舌な相棒 など）

【利用可能な変数（プレースホルダー）】
・{app}       : 現在使っているアプリ名（例: YouTube, X）
・{appMin}    : そのアプリの累計使用分数（5, 10, 15...）
・{screenMin} : スマホ全体の連続操作分数（5, 10, 15...）
・{remain}    : 1時間あたりの残り予算分数（0〜30）

【出力形式】
解説は不要です。以下のJSON形式のコードブロックのみをそのまま出力してください（各分数キーに1〜3個ずつバリエーションを入れてください）。

```json
{
  "appIntervalMessages": {
    "5": ["{app}を開いて5分経過。"],
    "10": ["{app}が10分になったよ。"],
    "15": ["{app}を15分使用中。残り枠は{remain}分。"],
    "20": ["{app}を20分見てるよ。"],
    "25": ["{app}が25分経過。そろそろ区切る？"],
    "30": ["{app}で30分経過。一度目を休めよう。"],
    "35": ["{app}が35分を超えたよ。"],
    "40": ["{app}を40分見続けてる。"],
    "45": ["{app}が45分経過。"],
    "50": ["{app}が50分経過。"],
    "55": ["{app}が55分経過。もうすぐ1時間だよ。"],
    "60": ["{app}を1時間（{appMin}分）使い続けてるよ。"],
    "default": ["{app}を{appMin}分使用中。"]
  },
  "screenIntervalMessages": {
    "5": ["スマホを触り始めて5分経過。"],
    "10": ["画面連続10分経過。ザッピングしててもカウント中。"],
    "15": ["スマホ連続15分経過（残り枠{remain}分）。"],
    "20": ["スマホを連続20分見てるよ。"],
    "25": ["画面連続25分。あと5分で30分だよ。"],
    "30": ["スマホ連続30分到達。画面を閉じて休憩しよう。"],
    "35": ["画面連続35分経過。"],
    "40": ["画面連続40分経過。"],
    "45": ["画面連続45分経過。"],
    "50": ["画面連続50分経過。"],
    "55": ["画面連続55分経過。"],
    "60": ["スマホを連続1時間（{screenMin}分）触り続けてるよ。"],
    "default": ["スマホ連続{screenMin}分経過。"]
  },
  "tapMessages": [
    "今は{app}を{appMin}分、画面全体で{screenMin}分使ってるよ（残枠{remain}分）。"
  ]
}
```
        """.trimIndent()
    }
}
