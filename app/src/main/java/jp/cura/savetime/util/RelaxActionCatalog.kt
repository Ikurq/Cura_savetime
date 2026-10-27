package jp.cura.savetime.util

import android.content.Context
import jp.cura.savetime.R
import kotlin.random.Random

/**
 * スマホ離脱の心理的ハードルを下げる「マイクロアクション（ほんの少しの簡単な行動）」文言カタログ
 */
object RelaxActionCatalog {

    val DEFAULT_ACTIONS = listOf(
        "1回だけ深呼吸する",
        "うーんと背伸びする",
        "30秒だけ目を閉じる",
        "ちょっと遠くを見る",
        "肩をぐるぐる回す",
        "お水をひと口飲む",
        "首をゆっくり回す",
        "手首をぶらぶら振る"
    )

    /**
     * 文字列リソースまたはデフォルトリストから、ランダムに1つのマイクロアクションを取得する
     */
    fun getRandomAction(context: Context? = null, random: Random = Random.Default): String {
        if (context != null) {
            try {
                val array = context.resources.getStringArray(R.array.micro_action_button_labels)
                if (array.isNotEmpty()) {
                    val index = random.nextInt(array.size)
                    return array[index]
                }
            } catch (_: Exception) {}
        }
        val index = random.nextInt(DEFAULT_ACTIONS.size)
        return DEFAULT_ACTIONS[index]
    }
}
