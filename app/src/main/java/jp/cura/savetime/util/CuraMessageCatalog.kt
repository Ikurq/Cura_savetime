package jp.cura.savetime.util

import android.content.Context
import jp.cura.savetime.tracker.TimelineGraphCalculator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 他のAIにセリフを生成させてそのまま流し込めるメッセージ枠定義（JSONシリアライズ対応）
 *
 * 使用可能なプレースホルダー:
 * - {app}        : 現在開いているアプリ名（例: YouTube, X）
 * - {appDay}     : そのアプリの1日累計使用時間（例: 25分10秒 / 1時間15分）
 * - {screenCont} : 画面連続使用時間（例: 15分20秒）
 * - {phoneDay}   : 1日の累計スマホ使用時間（例: 2時間30分）
 * - {appMin}     : そのアプリの累計使用分数（例: 5, 10, 15...）
 * - {screenMin}  : スマホ画面全体の連続操作分数（例: 5, 10, 15...）
 * - {todayMin}   : 1日の累計スマホ使用分数（例: 120）
 * - {remain}     : 1時間あたりの残り予算分数（例: 20）
 */
@Serializable
data class CuraMessageCatalogData(
    val appIntervalMessages: Map<String, List<String>> = emptyMap(),
    val screenIntervalMessages: Map<String, List<String>> = emptyMap(),
    val tapMessages: List<String> = emptyList(),
    val shortTapMessages: List<String> = emptyList()
)

class CuraMessageCatalog(val data: CuraMessageCatalogData) {

    fun formatAppIntervalMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long,
        todayAppSeconds: Long = appMinutes * 60L,
        continuousScreenSeconds: Long = screenMinutes * 60L,
        todayTotalPhoneSeconds: Long = maxOf(todayAppSeconds, continuousScreenSeconds)
    ): String {
        val candidates = data.appIntervalMessages[appMinutes.toString()]
            ?.takeIf { it.isNotEmpty() }
            ?: data.appIntervalMessages["default"]?.takeIf { it.isNotEmpty() }
            ?: listOf("[アプリ${appMinutes}分枠] {app} の使用が {appMin}分 経過しました。")

        val template = candidates.random()
        return applyPlaceholders(
            template = template,
            appName = appName,
            appMinutes = appMinutes,
            screenMinutes = screenMinutes,
            remainingBudgetMinutes = remainingBudgetMinutes,
            todayAppSeconds = todayAppSeconds,
            continuousScreenSeconds = continuousScreenSeconds,
            todayTotalPhoneSeconds = todayTotalPhoneSeconds
        )
    }

    fun formatScreenIntervalMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long,
        todayAppSeconds: Long = appMinutes * 60L,
        continuousScreenSeconds: Long = screenMinutes * 60L,
        todayTotalPhoneSeconds: Long = maxOf(todayAppSeconds, continuousScreenSeconds)
    ): String {
        val candidates = data.screenIntervalMessages[screenMinutes.toString()]
            ?.takeIf { it.isNotEmpty() }
            ?: data.screenIntervalMessages["default"]?.takeIf { it.isNotEmpty() }
            ?: listOf("[画面連続${screenMinutes}分枠] スマホ全体の連続使用が {screenMin}分 経過しました。")

        val template = candidates.random()
        return applyPlaceholders(
            template = template,
            appName = appName,
            appMinutes = appMinutes,
            screenMinutes = screenMinutes,
            remainingBudgetMinutes = remainingBudgetMinutes,
            todayAppSeconds = todayAppSeconds,
            continuousScreenSeconds = continuousScreenSeconds,
            todayTotalPhoneSeconds = todayTotalPhoneSeconds
        )
    }

    fun formatTapMessage(
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long,
        todayAppSeconds: Long = appMinutes * 60L,
        continuousScreenSeconds: Long = screenMinutes * 60L,
        todayTotalPhoneSeconds: Long = maxOf(todayAppSeconds, continuousScreenSeconds)
    ): String {
        val candidates = data.tapMessages.takeIf { it.isNotEmpty() }
            ?: listOf("今日の{app}累計は{appDay}、画面連続{screenCont}、1日のスマホ累計は{phoneDay}ですよっ！")
        val template = candidates.random()
        return applyPlaceholders(
            template = template,
            appName = appName,
            appMinutes = appMinutes,
            screenMinutes = screenMinutes,
            remainingBudgetMinutes = remainingBudgetMinutes,
            todayAppSeconds = todayAppSeconds,
            continuousScreenSeconds = continuousScreenSeconds,
            todayTotalPhoneSeconds = todayTotalPhoneSeconds
        )
    }

    /**
     * 詳細が閉じている時（通常時）用の「数字を含まない短いメッセージ」を生成する
     */
    fun formatShortTapMessage(appName: String): String {
        val candidates = data.shortTapMessages.takeIf { it.isNotEmpty() }
            ?: listOf(
                "えへへ、呼びましたか？無理せずマイペースでいきましょうねっ！",
                "ひょこっ！マスター、お目々は疲れてませんか？",
                "はーいっ！キュラが隣で見守ってますよ〜！ひと息つきますかっ？"
            )
        val template = candidates.random()
        return template.replace("{app}", appName)
    }

    /**
     * アコーディオン開閉状態に応じてセリフ枠のテキストを条件分岐する
     * - isDetailsExpanded == true  : 3つの時間（appDay, phoneDay, screenCont）を読み上げる詳細メッセージ
     * - isDetailsExpanded == false : 数字を含まない短い通常メッセージ
     */
    fun formatBalloonTapMessage(
        isDetailsExpanded: Boolean,
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long,
        todayAppSeconds: Long = appMinutes * 60L,
        continuousScreenSeconds: Long = screenMinutes * 60L,
        todayTotalPhoneSeconds: Long = maxOf(todayAppSeconds, continuousScreenSeconds)
    ): String {
        return if (isDetailsExpanded) {
            formatTapMessage(
                appName = appName,
                appMinutes = appMinutes,
                screenMinutes = screenMinutes,
                remainingBudgetMinutes = remainingBudgetMinutes,
                todayAppSeconds = todayAppSeconds,
                continuousScreenSeconds = continuousScreenSeconds,
                todayTotalPhoneSeconds = todayTotalPhoneSeconds
            )
        } else {
            formatShortTapMessage(appName)
        }
    }

    private fun applyPlaceholders(
        template: String,
        appName: String,
        appMinutes: Long,
        screenMinutes: Long,
        remainingBudgetMinutes: Long,
        todayAppSeconds: Long,
        continuousScreenSeconds: Long,
        todayTotalPhoneSeconds: Long
    ): String {
        val appDayStr = TimelineGraphCalculator.formatDurationClean(todayAppSeconds)
        val screenContStr = TimelineGraphCalculator.formatDurationClean(continuousScreenSeconds)
        val phoneDayStr = TimelineGraphCalculator.formatDurationClean(todayTotalPhoneSeconds)
        val todayMin = todayTotalPhoneSeconds / 60L

        return template
            .replace("{app}", appName)
            .replace("{appDay}", appDayStr)
            .replace("{screenCont}", screenContStr)
            .replace("{phoneDay}", phoneDayStr)
            .replace("{appMin}", appMinutes.toString())
            .replace("{screenMin}", screenMinutes.toString())
            .replace("{todayMin}", todayMin.toString())
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
                        "5" to listOf(
                            "マスター、{app}を始めて5分ですよっ！",
                            "ひょこっ！{app}、調べものですか？"
                        ),
                        "10" to listOf(
                            "{app}が10分経ちました！順調ですか？",
                            "10分経過ですよ〜！キリのいいところで教えてくださいねっ。"
                        ),
                        "15" to listOf(
                            "{app}を15分見てますよ！残り枠はあと{remain}分ですっ！",
                            "マスター、15分ですよ！楽しいと時間があっという間ですね！"
                        ),
                        "20" to listOf(
                            "{app}が20分経過です！お目々は疲れてませんか？",
                            "もう20分経ちましたよ！一緒にぎゅーって伸びしましょう！"
                        ),
                        "25" to listOf(
                            "{app}で25分ですっ。そろそろ区切りにしませんか？",
                            "マスター、25分ですよ！あと少しで30分になっちゃいます〜！"
                        ),
                        "30" to listOf(
                            "{app}が30分到達です！一度画面を閉じて休憩しましょうっ？",
                            "30分経ちましたよ！お目々をパチパチ休ませてくださいねっ。"
                        ),
                        "35" to listOf(
                            "{app}で35分ですよ〜！ついつい見ちゃいますよね…！",
                            "35分経過ですっ！お水飲んでひと息ついてくださいね？"
                        ),
                        "40" to listOf(
                            "{app}を40分見てますよ！マスター、肩凝ってませんか？",
                            "40分突破です！ちょっとだけ画面を伏せてみませんか？"
                        ),
                        "45" to listOf(
                            "{app}が45分です！残り枠もあと{remain}分しかないですよっ！",
                            "マスター、45分経ちました！そろそろおしまいにしませんか？"
                        ),
                        "50" to listOf(
                            "{app}で50分です！さすがに心配になってきちゃいました…！",
                            "もう50分ですよっ。ぎゅっと目を閉じて休憩してくださいね！"
                        ),
                        "55" to listOf(
                            "わわっ、{app}が55分です！もうすぐ1時間になっちゃいます！",
                            "55分経過です！次のキリで絶対に画面を閉じましょうね！"
                        ),
                        "60" to listOf(
                            "{app}を1時間（{appMin}分）使ってますよ！絶対休憩ですっ！",
                            "1時間経っちゃいました！マスターの目を大切にしてくださいね。"
                        ),
                        "default" to listOf(
                            "{app}で{appMin}分ですよ！マスター、無理してませんか？",
                            "ひょっこり！{app}を{appMin}分見てますよっ。"
                        )
                    ),
                    screenIntervalMessages = mapOf(
                        "5" to listOf(
                            "画面をつけて5分です！今日も一緒にがんばりましょうねっ！",
                            "マスター、操作5分経過です！チラッと応援してますよ〜！"
                        ),
                        "10" to listOf(
                            "画面連続10分ですよ！寄り道しすぎてませんか？",
                            "10分見っぱなしです〜！姿勢、丸まってないですか？"
                        ),
                        "15" to listOf(
                            "スマホ連続15分ですっ！（残り枠はあと{remain}分ですよ！）",
                            "連続15分経過です！瞬きパチパチしてみてくださいねっ。"
                        ),
                        "20" to listOf(
                            "連続20分見てますよ！一回遠くの景色を見ませんか？",
                            "スマホ20分経過です！肩をぐるぐる回してみてくださいね！"
                        ),
                        "25" to listOf(
                            "画面連続25分です！あと5分で30分になっちゃいますよ〜！",
                            "25分見っぱなしですっ。そろそろ休憩タイムにしませんか？"
                        ),
                        "30" to listOf(
                            "連続30分到達です！スマホを置いて深呼吸しましょうっ！",
                            "30分画面つけっぱなしですよ！お茶でも飲んできてくださいね！"
                        ),
                        "35" to listOf(
                            "スマホ連続35分です！ちょっと見すぎサインですよ〜！",
                            "35分連続操作中です！マスター、首が痛くなっちゃいますよ〜！"
                        ),
                        "40" to listOf(
                            "連続40分経過です！画面から一回目を離してくださいねっ？",
                            "40分見てますよ！軽く立ち上がって伸びをしてみませんか？"
                        ),
                        "45" to listOf(
                            "連続45分ですよ〜！残り枠はあと{remain}分ですっ！",
                            "スマホ45分見っぱなしです！マスター、疲れてませんか？"
                        ),
                        "50" to listOf(
                            "連続50分です！もうすぐ1時間ですよ、心配です…！",
                            "50分経過です！そろそろスマホを置いて休憩してくださいね？"
                        ),
                        "55" to listOf(
                            "わわっ、連続55分です！スマホを置く準備をしましょうっ！",
                            "55分連続ですよ！もう画面を閉じちゃってくださいね！"
                        ),
                        "60" to listOf(
                            "連続1時間（{screenMin}分）です！絶対にお休みしてくださいね！",
                            "1時間見っぱなしです！マスター、目をぎゅーっと休ませて！"
                        ),
                        "default" to listOf(
                            "画面連続{screenMin}分ですよ！休み休み使ってくださいねっ！",
                            "スマホ連続{screenMin}分です！キュラが見守ってますよっ。"
                        )
                    ),
                    tapMessages = listOf(
                        "えへへ、呼びましたか？今日の{app}累計は{appDay}、画面連続{screenCont}、1日のスマホ累計は{phoneDay}ですよっ！",
                        "ひょこっ！{app}の1日累計が{appDay}、画面連続{screenCont}、今日のスマホ累計は{phoneDay}です！無理しないでね！",
                        "はーいっ！本日{app}を累計{appDay}、画面連続{screenCont}、スマホ全体で1日累計{phoneDay}使ってますよ〜！"
                    ),
                    shortTapMessages = listOf(
                        "えへへ、呼びましたか？無理せずマイペースでいきましょうねっ！",
                        "ひょこっ！マスター、お目々は疲れてませんか？",
                        "はーいっ！キュラが隣で見守ってますよ〜！ひと息つきますかっ？"
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
・{app}        : 現在使っているアプリ名（例: YouTube, X）
・{appDay}     : そのアプリの1日累計使用時間（例: 25分10秒 / 1時間15分）
・{screenCont} : 画面連続使用時間（例: 15分20秒）
・{phoneDay}   : 1日の累計スマホ使用時間（例: 2時間30分）
・{appMin}     : そのアプリの累計使用分数（5, 10, 15...）
・{screenMin}  : スマホ全体の連続操作分数（5, 10, 15...）
・{remain}     : 1時間あたりの残り予算分数（0〜30）

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
    "今日の{app}累計は{appDay}、画面連続{screenCont}、1日のスマホ累計は{phoneDay}だよ（残枠{remain}分）。"
  ]
}
```
        """.trimIndent()
    }
}
