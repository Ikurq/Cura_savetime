package jp.cura.savetime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RelaxActionCatalogTest {

    @Test
    fun testDefaultActions_containsAtLeastFiveVariations() {
        assertTrue(RelaxActionCatalog.DEFAULT_ACTIONS.size >= 5)
        for (action in RelaxActionCatalog.DEFAULT_ACTIONS) {
            assertTrue("Action must not be blank", action.isNotBlank())
            assertTrue("Action should be concise (<= 20 chars)", action.length <= 20)
        }
    }

    @Test
    fun testGetRandomAction_returnsActionFromList() {
        val deterministicRandom = Random(42)
        val action = RelaxActionCatalog.getRandomAction(context = null, random = deterministicRandom)
        assertTrue(RelaxActionCatalog.DEFAULT_ACTIONS.contains(action))
    }

    @Test
    fun testGetRandomAction_samplesMultipleDifferentActions() {
        val sampled = mutableSetOf<String>()
        val random = Random(12345)
        for (i in 0 until 50) {
            sampled.add(RelaxActionCatalog.getRandomAction(context = null, random = random))
        }
        // 50回サンプリングすれば複数のバリエーションが出現すること
        assertTrue(sampled.size >= 4)
    }
}
