package jp.cura.savetime.tracker

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
            bufferDurationMs = 60_000L,
            appSwitchBufferMs = 120_000L, // 2分以内の浮気は許容してセッション維持
            screenRestBufferMs = 60_000L,
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
    }

    @Test
    fun testMonsterSwitching_preservesMainAppSessionIfReturnedWithinTwoMinutes() {
        var now = 1_000_000L

        // 1. YouTubeを30秒見る
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 2. 別のアプリ（X）へ切り替えて30秒見る
        now += 1000L
        tracker.onAppForeground("com.twitter.android", "X", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals("X", tracker.getCurrentSession()?.appName)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 3. さらに別のアプリ（LINE）へ切り替えて20秒見る
        now += 1000L
        tracker.onAppForeground("jp.naver.line.android", "LINE", now)
        for (i in 1..20) {
            now += 1000L
            tracker.onTick(now)
        }

        // 4. 合計80秒後（2分以内）にYouTubeに戻る
        now += 1000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)

        val youtubeSession = tracker.getCurrentSession()
        assertNotNull(youtubeSession)
        assertEquals("YouTube", youtubeSession?.appName)
        // YouTubeの以前の30秒が保持されていること！リセットされていない
        assertEquals(30_000L, youtubeSession?.continuousDurationMs)

        // 5. さらにYouTubeを20秒見る -> 50秒に累積される
        for (i in 1..20) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(50_000L, tracker.getCurrentSession()?.continuousDurationMs)
    }

    @Test
    fun testScreenContinuity_countsAcrossMultipleApps() {
        var now = 1_000_000L

        // YouTube 10分
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        now += 10 * 60 * 1000L

        // X 10分
        tracker.onAppForeground("com.twitter.android", "X", now)
        now += 10 * 60 * 1000L

        // LINE 10分
        tracker.onAppForeground("jp.naver.line.android", "LINE", now)
        now += 10 * 60 * 1000L

        // アプリを転々としても端末全体の連続スクリーンタイムは30分！
        val totalScreenDuration = tracker.getTotalContinuousScreenDurationMs(now)
        assertEquals(30 * 60 * 1000L, totalScreenDuration)

        // 画面を消して2分放置（休憩）
        tracker.onScreenOff(now)
        now += 120_000L
        tracker.onScreenOn(now)

        // 休憩後はリセットされる
        assertEquals(0L, tracker.getTotalContinuousScreenDurationMs(now))
    }

    @Test
    fun testIgnoredPackages_doNotTriggerNewSession() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.android.launcher", "ホーム", startMs)
        assertNull(tracker.getCurrentSession())
    }
}
