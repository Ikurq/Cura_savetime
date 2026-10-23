package jp.cura.savetime.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class UsageSessionTrackerTest {

    private lateinit var tracker: UsageSessionTracker

    @Before
    fun setUp() {
        tracker = UsageSessionTracker(
            bufferDurationMs = 60_000L,
            screenRestBufferMs = 60_000L, // 画面を消して60秒休むまでセッション継続
            thresholdMinutes = listOf(30L, 45L, 60L),
            ignoredPackages = setOf("com.android.launcher", "com.android.systemui", "jp.cura.savetime")
        )
    }

    @Test
    fun testZappingAppAccumulation_accumulatesDurationAcrossAnyNumberOfAppSwitches() {
        var now = 1_000_000L

        // 1. YouTubeを30秒見る
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 2. X（Twitter）に切り替えて30秒見る
        now += 1000L
        tracker.onAppForeground("com.twitter.android", "X", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals("X", tracker.getCurrentSession()?.appName)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 3. ブラウザ（Chrome）に切り替えて30秒見る
        now += 1000L
        tracker.onAppForeground("com.android.chrome", "Chrome", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals("Chrome", tracker.getCurrentSession()?.appName)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 4. 再びYouTubeに戻る -> 30秒保持されている
        now += 1000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        assertEquals("YouTube", tracker.getCurrentSession()?.appName)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 5. さらにYouTubeを30秒見る -> 合計60秒に累積される！
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(60_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 6. 再びXに戻る -> Xの30秒も消えずに残っている
        now += 1000L
        tracker.onAppForeground("com.twitter.android", "X", now)
        assertEquals("X", tracker.getCurrentSession()?.appName)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 7. さらにXを30秒見る -> Xも合計60秒に累積される！
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(60_000L, tracker.getCurrentSession()?.continuousDurationMs)
    }

    @Test
    fun testScreenRest_resetsAccumulatedSessionsAfterSixtySecondsOff() {
        var now = 1_000_000L

        // YouTube 2分使用
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..120) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(120_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 画面消灯して90秒放置（休憩）
        tracker.onScreenOff(now)
        now += 90_000L
        tracker.onScreenOn(now)

        // 休憩後はリセットされ、YouTubeを開くと0秒から新規スタート
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        assertEquals(0L, tracker.getCurrentSession()?.continuousDurationMs)
    }

    @Test
    fun testIgnoredPackages_doNotTriggerNewSession() {
        val startMs = 1_000_000L
        tracker.onAppForeground("com.android.launcher", "ホーム", startMs)
        assertNull(tracker.getCurrentSession())
    }
}
