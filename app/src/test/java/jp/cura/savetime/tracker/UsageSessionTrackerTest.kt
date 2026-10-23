package jp.cura.savetime.tracker

import jp.cura.savetime.model.ThresholdAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsageSessionTrackerTest {

    private lateinit var tracker: UsageSessionTracker

    @Before
    fun setUp() {
        tracker = UsageSessionTracker(
            bufferDurationMs = 60_000L, // 60秒バッファ
            thresholdMinutes = listOf(30L, 45L, 60L),
            ignoredPackages = setOf("com.android.launcher", "com.android.systemui", "jp.cura.savetime")
        )
    }

    @Test
    fun testAppForeground_startsNewSession() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", startMs)

        val session = tracker.getCurrentSession()
        assertNotNull(session)
        assertEquals("com.google.android.youtube", session?.packageName)
        assertEquals("YouTube", session?.appName)
        assertEquals(startMs, session?.startedAtMs)
        assertEquals(0L, session?.continuousDurationMs)
    }

    @Test
    fun testOnTick_incrementsContinuousDuration() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.twitter.android", "X", startMs)

        // 10分後
        val tenMinutesMs = startMs + 10 * 60 * 1000L
        val alert = tracker.onTick(tenMinutesMs)

        assertNull(alert) // 10分時点では30分しきい値未達のためアラートなし
        val session = tracker.getCurrentSession()
        assertEquals(10 * 60 * 1000L, session?.continuousDurationMs)
    }

    @Test
    fun testThresholdAlert_triggersExactlyOncePerThreshold() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", startMs)

        // 30分経過時
        val thirtyMinMs = startMs + 30 * 60 * 1000L
        val alert30 = tracker.onTick(thirtyMinMs)
        assertNotNull(alert30)
        assertEquals(30L, alert30?.thresholdMinutes)
        assertEquals("YouTube", alert30?.appName)

        // 31分経過時 (重複発火しないこと)
        val thirtyOneMinMs = startMs + 31 * 60 * 1000L
        val alert31 = tracker.onTick(thirtyOneMinMs)
        assertNull(alert31)

        // 45分経過時
        val fortyFiveMinMs = startMs + 45 * 60 * 1000L
        val alert45 = tracker.onTick(fortyFiveMinMs)
        assertNotNull(alert45)
        assertEquals(45L, alert45?.thresholdMinutes)
    }

    @Test
    fun testGracePeriodBuffer_within60Seconds_keepsSessionActive() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", startMs)

        // 15分経過後にホーム画面へ抜ける（バックグラウンド移行）
        val exitMs = startMs + 15 * 60 * 1000L
        tracker.onAppBackground("com.google.android.youtube", exitMs)

        val session = tracker.getCurrentSession()
        assertTrue(session?.isBufferActive == true)

        // 30秒後（バッファ内）に再びYouTubeへ復帰
        val returnMs = exitMs + 30 * 1000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", returnMs)

        val resumedSession = tracker.getCurrentSession()
        assertNotNull(resumedSession)
        assertEquals(false, resumedSession?.isBufferActive)
        assertEquals(startMs, resumedSession?.startedAtMs) // 開始時刻が保持されている
    }

    @Test
    fun testBufferExpiration_finishesSessionAndRecordsMaxDuration() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", startMs)

        // 40分経過後に画面OFF
        val exitMs = startMs + 40 * 60 * 1000L
        tracker.onScreenOff(exitMs)

        // 61秒経過（バッファ60秒を超過）
        val expiredMs = exitMs + 61 * 1000L
        tracker.onTick(expiredMs)

        // セッションが終了していること
        assertNull(tracker.getCurrentSession())

        // レコードに40分の連続記録が反映されていること
        val records = tracker.getRecords()
        assertEquals(1, records.size)
        assertEquals("com.google.android.youtube", records[0].packageName)
        assertEquals(40L, records[0].maxContinuousMinutes)
        assertEquals(1, records[0].sessionCount)
    }

    @Test
    fun testSwitchingToAnotherApp_recordsOldAndStartsNewSession() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", startMs)

        // 20分後にTwitterへ直接切り替え
        val switchMs = startMs + 20 * 60 * 1000L
        tracker.onAppForeground("com.twitter.android", "X", switchMs)

        val currentSession = tracker.getCurrentSession()
        assertEquals("com.twitter.android", currentSession?.packageName)
        assertEquals(switchMs, currentSession?.startedAtMs)

        // 前のYouTube記録が確定していること
        val records = tracker.getRecords()
        val youtubeRecord = records.find { it.packageName == "com.google.android.youtube" }
        assertNotNull(youtubeRecord)
        assertEquals(20L, youtubeRecord?.maxContinuousMinutes)
    }

    @Test
    fun testIgnoredPackages_doNotTriggerNewSession() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.android.launcher", "ホーム", startMs)
        assertNull(tracker.getCurrentSession())
    }
}
