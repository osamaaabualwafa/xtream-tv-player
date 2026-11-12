package com.osama.xtreamtv.qa

import android.os.SystemClock
import android.view.KeyEvent
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.Library
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.Ratings
import com.osama.xtreamtv.RatingsFill
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The whole-playlist ratings fill (work list Section 6), against the fake provider (20,000 movies and
 * 5,000 series, 9 in 10 with an id) and a stand-in MDBList on the same fake server. No real key, no
 * real service, no personal data: the key is a made-up word and every title is "Fill movie 00001".
 *
 * Checked: nothing is asked while the remote is in use; one group at a time, 5 s apart when idle and
 * 30 s apart during playback (none with the setting off); under the screensaver it goes on, with
 * another app in front or the screen off it does not; the daily reserve; the limit (429) and a
 * refused key (401); nothing asked twice; what a request carries (ids, the media type, the key, nothing
 * else); the highlight staying put; memory. Not covered here: the one-time move of the old scores file,
 * and a real restart of the app in the middle (the test runs inside the app).
 */
class T13_FillTest : QaTest() {
    private val key = "qafilltestkey0000"

    private fun setUp(remaining: Int = 1000) {
        Qa.reset(users = listOf("fill"))
        Qa.serverCall("/__mdb?mode=ok&remaining=$remaining&retry=20")
        Qa.serverCall("/__mdblog?clear=1")
        Ratings.baseForTests = Qa.server + "/mdb"
        Library.clear(Qa.ctx)
        Ratings.key = key
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home", 90_000)
        // No key is pressed while waiting: the fill starts by itself once the lists are loaded.
        assertTrue("The fill never made its queue", Qa.waitUntil(150_000, 1000) { RatingsFill.progress().total > 0 })
        Qa.step("fill queue: ${RatingsFill.progress().ready} of ${RatingsFill.progress().total} ready")
    }

    private class Asked(val at: Long, val path: String, val method: String, val queryKeys: List<String>, val bodyKeys: List<String>, val ids: List<Long>, val otherHeaders: List<String>)

    private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    /** What the stand-in MDBList was asked so far, and the most requests it had at once. */
    private fun asked(clear: Boolean = false): Pair<List<Asked>, Int> {
        val o = JSONObject(Qa.serverCall("/__mdblog" + if (clear) "?clear=1" else ""))
        val log = o.getJSONArray("log")
        val list = (0 until log.length()).map { i ->
            val e = log.getJSONObject(i)
            val ids = e.optJSONArray("ids")
            Asked(e.getLong("t"), e.getString("path"), e.getString("method"), strings(e.optJSONArray("query_keys")), strings(e.optJSONArray("body_keys")),
                (0 until (ids?.length() ?: 0)).map { ids!!.optLong(it) }, strings(e.optJSONArray("other_headers")))
        }
        return list to o.optInt("max_inflight")
    }

    private fun groups(list: List<Asked>) = list.filter { it.method == "POST" }

    private fun gaps(list: List<Asked>) = list.zipWithNext { a, b -> b.at - a.at }

    @Test
    fun paceIdlePrivacyAndHighlight() {
        setUp()
        Qa.metric("fill_memory_mb_start", Qa.memoryMb())

        // The remote in use: a key every 3 s. Nothing may be asked.
        Qa.serverCall("/__mdblog?clear=1")
        repeat(12) { i ->
            Qa.key(if (i % 2 == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, 1, 0)
            SystemClock.sleep(3000)
        }
        val lastKey = System.currentTimeMillis() - 3000
        val whileBrowsing = groups(asked().first)
        Qa.metric("fill_groups_while_browsing", whileBrowsing.size)
        assertEquals("Groups were asked while the remote was in use", 0, whileBrowsing.size)

        // Idle: a group every 5 s, one at a time, the first no sooner than 10 s after the last key.
        SystemClock.sleep(70_000)
        val (all, most) = asked()
        val idle = groups(all)
        Qa.metric("fill_groups_in_70s_idle", idle.size)
        Qa.metric("fill_min_gap_ms", gaps(idle).minOrNull() ?: -1)
        Qa.metric("fill_most_requests_at_once", most)
        Qa.shot("fill_idle", "Home while the fill runs")
        assertTrue("The fill asked nothing in 70 s of idle time (state ${RatingsFill.state})", idle.size >= 6)
        assertTrue("First group came ${idle.first().at - lastKey} ms after the last key (should wait 10 s)", idle.first().at - lastKey >= 9_000)
        assertTrue("Groups closer than 5 s: ${gaps(idle).minOrNull()} ms", (gaps(idle).minOrNull() ?: 5000) >= 4_500)
        assertEquals("More than one request at a time", 1, most)

        // What a request carries: the ids, the media type (in the address), the key. Nothing else.
        for (a in all) {
            assertTrue("Unexpected address ${a.path}", a.path == "/user" || Regex("^/tmdb/(movie|show)(/\\d+)?$").matches(a.path))
            assertEquals("Unexpected parts in the address", listOf("apikey"), a.queryKeys)
            if (a.method == "POST") assertEquals("Unexpected parts in the request", listOf("ids"), a.bodyKeys)
            assertTrue("Unexpected headers ${a.otherHeaders}", a.otherHeaders.isEmpty())
            assertTrue("A group of ${a.ids.size} (the limit is ${RatingsFill.BATCH})", a.ids.size <= RatingsFill.BATCH)
        }
        val everyId = idle.flatMap { g -> g.ids.map { g.path + "/" + it } }
        assertEquals("Some titles were asked twice", everyId.size, everyId.toSet().size)
        Qa.metric("fill_ready_after_idle", RatingsFill.progress().ready)

        // The queue made again (as after a restart): nothing already answered is asked again.
        RatingsFill.rebuild()
        SystemClock.sleep(25_000)
        val again = groups(asked().first).flatMap { g -> g.ids.map { g.path + "/" + it } }
        assertEquals("Titles were asked again after the queue was made again", again.size, again.toSet().size)
        // What is in memory is what the database holds.
        val inMemory = Ratings.matching().let { it.byId + it.byName + it.notFound }
        Ratings.flush()
        Ratings.reload()
        assertEquals("Saved answers differ from the ones in memory", inMemory, Ratings.matching().let { it.byId + it.byName + it.notFound })

        // The highlight: a group finishing must not move it.
        Qa.focusId(R.id.tile_movies, "Movies")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Movies")
        assertTrue("Movies never showed its list", Qa.waitUntil(60_000, 300) { Qa.listCount(R.id.items) > 0 })
        Qa.right()
        Qa.down(2)
        Qa.settle(1200)
        val before = Qa.focusedDescription()
        val groupsBefore = groups(asked().first).size
        SystemClock.sleep(24_000)
        val groupsAfter = groups(asked().first).size
        Qa.shot("fill_browse", "Movies while the fill runs: the highlight stays")
        assertTrue("No group finished while waiting on a poster", groupsAfter > groupsBefore)
        assertEquals("The highlight moved while the fill ran", before, Qa.focusedDescription())
        assertTrue("Movies closed while the fill ran", Qa.current() is BrowseActivity)

        // Fast remote use with the fill on, then off, for comparison (the fill waits for the remote anyway).
        fun scroll(label: String) {
            val t = SystemClock.elapsedRealtime()
            repeat(120) { Qa.key(KeyEvent.KEYCODE_DPAD_DOWN, 1, 40) }
            Qa.metric("fill_${label}_120_presses_ms", SystemClock.elapsedRealtime() - t)
            Qa.settle(800)
        }
        scroll("on")
        Ratings.fillWhole = false
        SystemClock.sleep(2000)
        scroll("off")
        Ratings.fillWhole = true
        Qa.metric("fill_memory_mb_end", Qa.memoryMb())

        // The diagnostics report: counts only, never the key.
        val report = File(Qa.ctx.filesDir, "diagnostics.log")
        if (report.exists()) assertTrue("The key is in the diagnostics report", !report.readText().contains(key))
    }

    /**
     * The first run (2026-11-10) scrolled with the fill on first, then off, and the first was 7 times
     * slower: the fill, or just the first scroll of a new list? Here: off, on, off, on.
     */
    @Test
    fun scrollingWithTheFillOffAndOn() {
        setUp()
        Qa.focusId(R.id.tile_movies, "Movies")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Movies")
        assertTrue("Movies never showed its list", Qa.waitUntil(60_000, 300) { Qa.listCount(R.id.items) > 0 })
        Qa.right()
        Qa.settle(1000)
        fun scroll(label: String): Long {
            val t = SystemClock.elapsedRealtime()
            repeat(120) { Qa.key(KeyEvent.KEYCODE_DPAD_DOWN, 1, 40) }
            val ms = SystemClock.elapsedRealtime() - t
            Qa.metric("fill_scroll_${label}_ms", ms)
            Qa.settle(800)
            return ms
        }
        val off = ArrayList<Long>()
        val on = ArrayList<Long>()
        repeat(2) { round ->
            Ratings.fillWhole = false
            SystemClock.sleep(3000)
            off.add(scroll("off_${round + 1}"))
            Ratings.fillWhole = true
            // Idle long enough for the fill to be at work again before the next scroll.
            val before = groups(asked().first).size
            Qa.waitUntil(40_000, 500) { groups(asked().first).size > before }
            on.add(scroll("on_${round + 1}"))
        }
        Qa.shot("fill_scroll", "Movies after scrolling with the fill off and on")
        Qa.metric("fill_scroll_memory_mb", Qa.memoryMb())
        // The first scroll of all is left out of the comparison (a new list's first pictures).
        val slowest = on.maxOrNull() ?: 0
        val base = off.last()
        if (slowest > base * 3 / 2 && slowest - base > 3000) {
            Qa.finding("problem", "Scrolling is slower with the fill on", "120 presses: ${slowest} ms with the fill on, ${base} ms with it off")
        }
        assertTrue("App closed while scrolling", Qa.current() is BrowseActivity)
    }

    @Test
    fun dailyReserveLimitAndBadKey() {
        // 104 lookups left: the fill may use 4, then keeps 100 for browsing.
        setUp(remaining = 104)
        // (Movies without an id are still asked from the provider meanwhile: only MDBList is spared.)
        assertTrue("The fill did not reach the daily reserve (state ${RatingsFill.state}, left ${Ratings.left()})",
            Qa.waitUntil(120_000, 1000) { Ratings.left() <= RatingsFill.RESERVE })
        SystemClock.sleep(8000)
        val used = groups(asked().first).size
        Qa.metric("fill_groups_before_reserve", used)
        assertTrue("The fill used $used lookups with only 4 above the reserve", used <= 5)
        SystemClock.sleep(15_000)
        assertEquals("The fill went on below the reserve", used, groups(asked().first).size)
        Qa.shot("fill_reserve", "The fill waiting: the day's reserve is kept")

        // A title's own page still gets its scores.
        val scores = runBlocking { Ratings.lookup(Ratings.Title(ContentType.MOVIE, "qa-page-title", "Page title", null, "777", null, null), onScreen = true) }
        assertTrue("A title's page got no scores while the fill waited", scores != null)

        // The next day: the allowance is back, the fill goes on.
        Qa.serverCall("/__mdb?remaining=1000")
        runBlocking { Ratings.test(key) }
        assertTrue("The fill did not go on after the allowance came back",
            Qa.waitUntil(60_000, 1000) { groups(asked().first).size > used })

        // The limit (429): stop at once, wait the time MDBList gives, then go on.
        Qa.serverCall("/__mdb?mode=429&retry=25")
        assertTrue("The fill never met the limit", Qa.waitUntil(40_000, 500) { System.currentTimeMillis() < Ratings.blockedUntil() })
        SystemClock.sleep(1000)
        val atLimit = asked().first.size
        SystemClock.sleep(12_000)
        assertEquals("Requests were sent while waiting for the limit to end", atLimit, asked().first.size)
        Qa.serverCall("/__mdb?mode=ok")
        assertTrue("The fill did not go on after the limit ended", Qa.waitUntil(70_000, 1000) { asked().first.size > atLimit })

        // A refused key (401): stop, show it, and go on once a key works again.
        Qa.serverCall("/__mdb?mode=401")
        assertTrue("The fill did not stop on a refused key", Qa.waitUntil(40_000, 500) { RatingsFill.state == RatingsFill.State.BAD_KEY })
        val atRefusal = asked().first.size
        SystemClock.sleep(12_000)
        assertEquals("Requests were sent with a refused key", atRefusal, asked().first.size)
        assertTrue("Settings would not show the key as refused", !Ratings.keyWorks)
        Qa.serverCall("/__mdb?mode=ok")
        runBlocking { Ratings.test(key) }
        assertTrue("The fill did not go on once the key worked again", Qa.waitUntil(60_000, 1000) { asked().first.size > atRefusal + 1 })
        assertTrue("App closed during the limit checks", Qa.current() != null)
    }

    @Test
    fun slowDuringPlayback() {
        setUp()
        Qa.focusId(R.id.tile_live, "Live TV")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Live TV")
        assertTrue("Live TV never showed its list", Qa.waitUntil(60_000, 300) { Qa.listCount(R.id.items) > 0 })
        Qa.right()
        Qa.ok()
        if (!Qa.waitUntil(10_000) { Qa.current() is PlayerActivity }) Qa.ok()
        Qa.expect(PlayerActivity::class.java, "Player")
        Qa.expectPlaying("a channel, with the fill on")

        // Steady playback: a group no more than once every 30 s.
        SystemClock.sleep(20_000)
        Qa.serverCall("/__mdblog?clear=1")
        val from = Qa.player()?.positionMs ?: 0
        SystemClock.sleep(95_000)
        val playing = groups(asked().first)
        Qa.metric("fill_groups_in_95s_playing", playing.size)
        Qa.metric("fill_min_gap_playing_ms", gaps(playing).minOrNull() ?: -1)
        Qa.shot("fill_playing", "A channel playing while the fill runs")
        assertTrue("The fill asked nothing in 95 s of playback (state ${RatingsFill.state})", playing.isNotEmpty())
        assertTrue("${playing.size} groups in 95 s of playback (at most one every 30 s)", playing.size <= 4)
        assertTrue("Groups closer than 30 s during playback: ${gaps(playing).minOrNull()} ms", (gaps(playing).minOrNull() ?: 30_000) >= 27_000)
        assertTrue("The channel stopped playing while the fill ran", Qa.player()?.playing == true)
        Qa.metric("fill_played_ms_in_95s", (Qa.player()?.positionMs ?: 0) - from)

        // "Fill during playback" off: nothing while the video plays.
        Ratings.fillDuringPlayback = false
        SystemClock.sleep(3000)
        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(45_000)
        val off = groups(asked().first).size
        Ratings.fillDuringPlayback = true
        Qa.metric("fill_groups_playing_setting_off", off)
        assertEquals("Groups were asked during playback with the setting off", 0, off)
        assertTrue("The channel stopped playing", Qa.player()?.playing == true)
        Qa.metric("fill_memory_mb_playing", Qa.memoryMb())
        Qa.leavePlayer()
    }

    /** The TV's own launcher comes in front, as when Home is pressed or the screensaver takes the screen. */
    private fun leaveTheApp() {
        Qa.step("the app leaves the screen")
        Qa.start(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME))
        SystemClock.sleep(1500)
    }

    /**
     * Under the screensaver the fill goes on, at the idle pace, with the big lists out of memory; it
     * stops when the screen goes off; and it starts again by itself when the app is opened.
     * (The screensaver and the screen going off are stood in for: the fill is told what the TV would tell it.)
     */
    @Test
    fun goesOnUnderTheScreensaver() {
        setUp()
        leaveTheApp()
        RatingsFill.screenEventForTests(android.content.Intent.ACTION_DREAMING_STARTED)
        assertTrue("The fill did not go on under the screensaver (state ${RatingsFill.state})", Qa.waitUntil(8000) { RatingsFill.underScreensaver })
        Qa.metric("fill_memory_mb_screensaver_start", Qa.memoryMb())

        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(45_000)
        val (log, inflight) = asked()
        val under = groups(log)
        Qa.metric("fill_groups_in_45s_screensaver", under.size)
        Qa.metric("fill_min_gap_screensaver_ms", gaps(under).minOrNull() ?: -1)
        assertTrue("Nothing was asked in 45 s under the screensaver (state ${RatingsFill.state})", under.size >= 3)
        assertTrue("Groups closer than 5 s under the screensaver: ${gaps(under).minOrNull()} ms", (gaps(under).minOrNull() ?: 5000) >= 4500)
        assertTrue("$inflight requests at once under the screensaver", inflight <= 1)

        // The big lists have left memory, and the fill works on from its own queue.
        assertTrue("The big lists stayed in memory under the screensaver", com.osama.xtreamtv.Session.streamCache.isEmpty())
        Qa.metric("fill_memory_mb_screensaver_lists_gone", Qa.memoryMb())
        val before = RatingsFill.progress().ready
        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(25_000)
        assertTrue("The fill stopped once the lists left memory (state ${RatingsFill.state})", groups(asked().first).isNotEmpty())
        assertTrue("No more titles became ready without the lists", RatingsFill.progress().ready > before)

        // The screen goes off (2026-11-13: the owner's TV keeps its Wi-Fi): the fill goes on.
        RatingsFill.screenEventForTests(android.content.Intent.ACTION_SCREEN_OFF)
        assertTrue("The fill stopped when the screen went off", RatingsFill.underScreensaver)
        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(25_000)
        assertTrue("Nothing was asked with the screen off (state ${RatingsFill.state})", groups(asked().first).isNotEmpty())

        // The app is opened again: the fill goes on from where it was, and nothing was lost.
        val ready = RatingsFill.progress().ready
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home", 90_000)
        Qa.serverCall("/__mdblog?clear=1")
        assertTrue("The fill did not start again after the app was opened (state ${RatingsFill.state})",
            Qa.waitUntil(150_000, 1000) { groups(asked().first).isNotEmpty() })
        assertTrue("Titles that were ready were lost: $ready before, ${RatingsFill.progress().ready} after", RatingsFill.progress().ready >= ready)
        Qa.metric("fill_memory_mb_back_from_screensaver", Qa.memoryMb())
    }

    /** Another app in front, a screensaver that starts later over that app, and the switch off: nothing is asked. */
    @Test
    fun nothingInTheBackgroundOtherwise() {
        setUp()
        // Another app in front, no screensaver.
        leaveTheApp()
        SystemClock.sleep(8000)
        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(30_000)
        assertEquals("Groups were asked with another app in front", 0, groups(asked().first).size)

        // The screensaver starts over that other app, long after this one left the screen.
        RatingsFill.screenEventForTests(android.content.Intent.ACTION_DREAMING_STARTED)
        SystemClock.sleep(3000)
        assertTrue("The fill went on under a screensaver that started over another app", !RatingsFill.underScreensaver)
        Qa.serverCall("/__mdblog?clear=1")
        SystemClock.sleep(20_000)
        assertEquals("Groups were asked under a screensaver over another app", 0, groups(asked().first).size)
        RatingsFill.screenEventForTests(android.content.Intent.ACTION_DREAMING_STOPPED)

        // "Keep filling when the TV is idle" off: the screensaver over this app changes nothing.
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home", 90_000)
        Ratings.fillWhenIdle = false
        try {
            SystemClock.sleep(3000)
            leaveTheApp()
            RatingsFill.screenEventForTests(android.content.Intent.ACTION_DREAMING_STARTED)
            SystemClock.sleep(6000)
            assertTrue("The fill went on under the screensaver with the switch off", !RatingsFill.underScreensaver)
            Qa.serverCall("/__mdblog?clear=1")
            SystemClock.sleep(25_000)
            assertEquals("Groups were asked under the screensaver with the switch off", 0, groups(asked().first).size)
        } finally {
            RatingsFill.screenEventForTests(android.content.Intent.ACTION_DREAMING_STOPPED)
            Ratings.fillWhenIdle = true
        }
    }
}
