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
            screenRestBufferMs = 60_000L,
            thresholdMinutes = listOf(30L, 45L, 60L),
            ignoredPackages = setOf("com.android.launcher", "com.android.systemui", "jp.cura.savetime")
        )
    }

    @Test
    fun testZappingAppAccumulation() {
        var now = 1_000_000L

        // YouTube 30秒
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // X 30秒
        now += 1000L
        tracker.onAppForeground("com.twitter.android", "X", now)
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // YouTubeに戻る -> 30秒保持
        now += 1000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        assertEquals(30_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // さらに30秒見る -> 60秒
        for (i in 1..30) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(60_000L, tracker.getCurrentSession()?.continuousDurationMs)
    }

    @Test
    fun testHourlyReset_clearsSessionsWhenPassingNextHourBucket() {
        var now = 3_600_000L // 1:00:00

        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..60) {
            now += 1000L
            tracker.onTick(now)
        }
        assertEquals(60_000L, tracker.getCurrentSession()?.continuousDurationMs)

        // 1時間経過して 2:00:01 にジャンプ
        now = 7_200_001L
        tracker.onTick(now)

        // リセットされていること
        assertNull(tracker.getCurrentSession())
    }

    @Test
    fun testTimelineRecording_addsEntryImmediatelyAndUpdatesDuration() {
        var now = 1_000_000L

        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        // 開いた瞬間にタイムライン先頭に登録されること
        assertEquals(1, tracker.getTimeline().size)
        assertEquals("YouTube", tracker.getTimeline()[0].appName)

        now += 15_000L // 15秒滞在して次のアプリへ
        tracker.onAppForeground("com.twitter.android", "X", now)

        val timeline = tracker.getTimeline()
        assertEquals(2, timeline.size)
        assertEquals("X", timeline[0].appName)
        assertEquals("YouTube", timeline[1].appName)
        assertEquals(15L, timeline[1].durationSeconds)
    }

    @Test
    fun testIgnoredPackages_excludedFromTracking() {
        tracker.ignoredPackages = tracker.ignoredPackages + "com.example.excluded"
        val startMs = 1_000_000L
        tracker.onAppForeground("com.example.excluded", "除外アプリ", startMs)
        assertNull(tracker.getCurrentSession())
    }

    @Test
    fun testTimelineRetainsMoreThan50EntriesWithin24Hours() {
        var now = 10_000_000L
        for (i in 1..120) {
            val pkg = if (i % 2 == 0) "com.google.android.youtube" else "com.twitter.android"
            val name = if (i % 2 == 0) "YouTube" else "X"
            tracker.onAppForeground(pkg, name, now)
            now += 10_000L
        }
        assertEquals(120, tracker.getTimeline().size)
    }

    @Test
    fun testTimelineCreatesNewEntryAfterLauncherPause() {
        var now = 1_000_000L
        // YouTube を 20秒見る
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        now += 20_000L
        // ランチャー（ホーム画面）に 40秒戻る
        tracker.onAppForeground("com.android.launcher", "Launcher", now)
        now += 40_000L
        // 再び YouTube を 15秒見る
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        now += 15_000L
        tracker.onTick(now)

        val timeline = tracker.getTimeline()
        assertEquals(2, timeline.size)
        // 最新エントリは 15秒、前回エントリは 20秒（ランチャー滞在の40秒が混入しないこと）
        assertEquals(15L, timeline[0].durationSeconds)
        assertEquals(20L, timeline[1].durationSeconds)
    }

    @Test
    fun testDuplicateTicksWithinSameSecond_doNotAccelerateAppTimer() {
        var now = 1_000_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)

        // 1秒（1000ms）の間に5回 onTick が呼ばれても、実経過時間の1000msしか加算されないこと
        for (i in 1..5) {
            now += 200L
            tracker.onTick(now)
        }
        assertEquals(1_000L, tracker.getCurrentSession()?.continuousDurationMs)
    }

    @Test
    fun testTodayAppAndPhoneUsageSeconds_accumulatesAcrossHoursWithinSameDay() {
        val startOfDayMs = TimelineGraphCalculator.getStartOfDayMs(1_700_000_000_000L)
        var now = startOfDayMs + 10 * 3600_000L // 10:00:00

        // 10:00 から YouTube を 300秒（5分）使用
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..300) {
            now += 1000L
            tracker.onTick(now)
        }

        // 10:05 から Chrome を 180秒（3分）使用
        tracker.onAppForeground("com.android.chrome", "Chrome", now)
        for (i in 1..180) {
            now += 1000L
            tracker.onTick(now)
        }

        // 次の時間帯（11:10:00）にジャンプして再び YouTube を 120秒（2分）使用
        now = startOfDayMs + 11 * 3600_000L + 600_000L
        tracker.onAppForeground("com.google.android.youtube", "YouTube", now)
        for (i in 1..120) {
            now += 1000L
            tracker.onTick(now)
        }

        // 1日のアプリ累計（YouTube）= 300s + 120s = 420s
        assertEquals(420L, tracker.getTodayAppUsageSeconds("com.google.android.youtube", now))
        // 1日のアプリ累計（Chrome）= 180s
        assertEquals(180L, tracker.getTodayAppUsageSeconds("com.android.chrome", now))
        // 画面連続（11:10からの連続）= 120s
        assertEquals(120_000L, tracker.getTotalContinuousScreenDurationMs(now))
        // 1日の累計スマホ使用時間 = 300s + 180s + 120s = 600s
        assertEquals(600L, tracker.getTodayTotalPhoneUsageSeconds(now))
    }
}
