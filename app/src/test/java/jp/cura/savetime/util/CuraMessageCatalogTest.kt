package jp.cura.savetime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraMessageCatalogTest {

    @Test
    fun testDefaultCatalog_resolvesPlaceholdersForAppAndScreenIntervals() {
        val catalog = CuraMessageCatalog.defaultCatalog()

        val appMsg = catalog.formatAppIntervalMessage(
            appName = "YouTube",
            appMinutes = 15L,
            screenMinutes = 25L,
            remainingBudgetMinutes = 5L
        )
        assertTrue(appMsg.contains("YouTube"))
        assertTrue(appMsg.contains("15"))

        val screenMsg = catalog.formatScreenIntervalMessage(
            appName = "X",
            appMinutes = 10L,
            screenMinutes = 30L,
            remainingBudgetMinutes = 0L
        )
        assertTrue(screenMsg.contains("30"))
    }

    @Test
    fun testParseJson_loadsCustomAiGeneratedMessages() {
        val customJson = """
            {
              "appIntervalMessages": {
                "5": ["{app}を始めて5分です。"],
                "10": ["{app}が10分経過しました（画面全体:{screenMin}分）。"],
                "default": ["{app}を{appMin}分使用中です。"]
              },
              "screenIntervalMessages": {
                "5": ["スマホ連続5分経過です。"],
                "default": ["スマホ連続{screenMin}分（残り枠:{remain}分）"]
              },
              "tapMessages": [
                "現在{app}を{appMin}分使用中です。"
              ]
            }
        """.trimIndent()

        val parsed = CuraMessageCatalog.fromJsonOrDefault(customJson)
        assertEquals(
            "YouTubeを始めて5分です。",
            parsed.formatAppIntervalMessage("YouTube", 5L, 5L, 25L)
        )
        assertEquals(
            "YouTubeが10分経過しました（画面全体:15分）。",
            parsed.formatAppIntervalMessage("YouTube", 10L, 15L, 15L)
        )
        assertEquals(
            "スマホ連続5分経過です。",
            parsed.formatScreenIntervalMessage("YouTube", 2L, 5L, 25L)
        )
        assertEquals(
            "スマホ連続20分（残り枠:10分）",
            parsed.formatScreenIntervalMessage("YouTube", 5L, 20L, 10L)
        )
    }
}
