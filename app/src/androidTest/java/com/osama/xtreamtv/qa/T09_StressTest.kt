package com.osama.xtreamtv.qa

import android.os.SystemClock
import android.view.KeyEvent
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SearchActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/** A provider with 10,000 channels, 50,000 movies and 5,000 series; very fast remote use. */
class T09_StressTest : QaTest() {

    private fun timeToOpen(tile: Int, label: String): Long {
        Qa.focusId(tile, label)
        val t = SystemClock.elapsedRealtime()
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, label)
        val ok = Qa.waitUntil(60_000, 200) { Qa.listCount(R.id.items) > 0 }
        val ms = SystemClock.elapsedRealtime() - t
        Qa.metric("huge_open_${label.lowercase().replace(' ', '_')}_ms", ms)
        Qa.shot("huge_$label", "$label with a huge provider (${ms / 1000.0} s to show)")
        assertTrue("$label never showed its list (huge provider)", ok)
        if (ms > 20_000) Qa.finding("problem", "$label takes ${ms / 1000} s to open with a big provider")
        else if (ms > 8_000) Qa.finding("warning", "$label takes ${ms / 1000} s to open with a big provider")
        return ms
    }

    private fun hammer(label: String, keys: List<Int>, presses: Int) {
        val t = SystemClock.elapsedRealtime()
        repeat(presses) { i -> Qa.device.pressKeyCode(keys[i % keys.size]); SystemClock.sleep(40) }
        val ms = SystemClock.elapsedRealtime() - t
        Qa.settle(1500)
        Qa.metric("${label}_presses_ms", ms)
        Qa.metric("${label}_memory_mb", Qa.memoryMb())
        Qa.shot("${label}_after", "After $presses fast remote presses")
        assertTrue("App still open after fast presses ($label)", Qa.current() != null)
    }

    @Test
    fun hugeProvider() {
        Qa.reset(users = listOf("huge"))
        val t = SystemClock.elapsedRealtime()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home", 60_000)
        Qa.waitUntil(60_000) { Qa.text(R.id.tile_movies_count)?.isNotBlank() == true }
        Qa.metric("huge_home_ready_ms", SystemClock.elapsedRealtime() - t)
        Qa.shot("huge_home", "Home with a huge provider")
        Qa.metric("memory_mb_home", Qa.memoryMb())

        timeToOpen(R.id.tile_live, "Live TV")
        hammer("live_scroll", listOf(KeyEvent.KEYCODE_DPAD_DOWN), 300)
        Qa.ok()
        Qa.waitUntil(10_000) { Qa.current() is PlayerActivity }
        if (Qa.current() is PlayerActivity) {
            Qa.expectPlaying("channel deep in a huge list")
            hammer("huge_zap", listOf(KeyEvent.KEYCODE_DPAD_UP), 60)
            Qa.leavePlayer()
        }
        Qa.back()
        if (Qa.current() !is HomeActivity) Qa.back()
        Qa.expect(HomeActivity::class.java, "Home")

        timeToOpen(R.id.tile_movies, "Movies")
        hammer("movies_grid", listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT), 300)
        Qa.metric("memory_mb_movies", Qa.memoryMb())
        Qa.back()
        if (Qa.current() !is HomeActivity) Qa.back()
        if (Qa.current() !is HomeActivity) Qa.back()
        Qa.expect(HomeActivity::class.java, "Home")

        timeToOpen(R.id.tile_series, "Series")
        hammer("series_grid", listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT), 200)
        Qa.back()
        if (Qa.current() !is HomeActivity) Qa.back()
        if (Qa.current() !is HomeActivity) Qa.back()

        Qa.step("search in 65,000 items")
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_search, "Search tile")
        Qa.ok()
        Qa.expect(SearchActivity::class.java, "Search")
        val s = SystemClock.elapsedRealtime()
        Qa.type(R.id.search_query, "Movie 4999")
        Qa.waitUntil(30_000) { Qa.findText("Movie 49990") != null || Qa.findText("Movie 4999") != null && !Qa.visible(R.id.search_progress) }
        Qa.metric("huge_search_ms", SystemClock.elapsedRealtime() - s)
        Qa.shot("huge_search", "Search in a huge provider")
        val mem = Qa.memoryMb()
        Qa.metric("memory_mb_end", mem)
        if (mem > 600) Qa.finding("warning", "Memory use is high with a huge provider", "$mem MB (TVs often have 1-2 GB in total)")
    }

    @Test
    fun randomRemoteKeys() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val keys = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_CHANNEL_UP,
        )
        val rnd = java.util.Random(20261104)
        val screens = HashSet<String>()
        repeat(800) { i ->
            val k = keys[rnd.nextInt(keys.size)]
            // Never leave the app: if Back would close it, go Home instead.
            val here = Qa.current()
            if (here == null) {
                Qa.step("app closed after ${i} random keys; reopening")
                Qa.launchApp()
                Qa.waitUntil(15_000) { Qa.current() != null }
            } else {
                screens.add(here.javaClass.simpleName)
                Qa.device.pressKeyCode(k)
                SystemClock.sleep(150)
            }
            if (i % 200 == 199) Qa.shot("random_$i", "After ${i + 1} random remote keys")
        }
        Qa.step("screens visited by random keys: $screens")
        Qa.metric("random_screens_visited", screens.size)
        Qa.metric("memory_mb_after_random", Qa.memoryMb())
    }
}
