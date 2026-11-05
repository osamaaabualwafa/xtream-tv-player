package com.osama.xtreamtv.qa

import android.content.Intent
import android.os.SystemClock
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.PlaylistsActivity
import com.osama.xtreamtv.Prefs
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SearchActivity
import com.osama.xtreamtv.Session
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slow server, broken server, internet cut while watching or browsing, and app restarts. */
class T10_BadNetworkTest : QaTest() {

    @Test
    fun slowServer() {
        Qa.reset(users = listOf("slow"))
        Qa.launchApp()
        Qa.settle(1500)
        Qa.shot("slow_opening", "Opening a slow playlist (server needs 3 s)")
        Qa.expect(HomeActivity::class.java, "Home of a slow server", 45_000)
        Qa.focusId(R.id.tile_movies, "Movies tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Movies")
        Qa.settle(1500)
        Qa.shot("slow_loading", "Movies while the slow server answers")
        val loadingShown = Qa.visible(R.id.progress)
        val ok = Qa.waitUntil(60_000) { Qa.listCount(R.id.items) > 0 }
        Qa.shot("slow_loaded", "Movies after the slow server answered")
        assertTrue("Movies appear even with a slow server", ok)
        if (!loadingShown && Qa.listCount(R.id.items) > 0) Qa.step("list was ready before the loading check")
    }

    @Test
    fun brokenServer() {
        Qa.reset(users = listOf("broken"))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home of a broken server", 45_000)
        Qa.settle(3000)
        Qa.shot("broken_home", "Home when the server sends broken lists")
        for (type in ContentType.values()) {
            Qa.start(Intent(Qa.ctx, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, type.name))
            Qa.expect(BrowseActivity::class.java, "$type list")
            val msg = Qa.waitUntil(30_000) { Qa.text(R.id.message)?.isNotBlank() == true }
            Qa.shot("broken_${type.name.lowercase()}", "${type.name.lowercase()} list from a broken server")
            assertTrue("App still open", Qa.current() != null)
            if (!msg) Qa.finding("problem", "No message when the ${type.name.lowercase()} list can't load", "The server sends HTML / cut-off data")
            Qa.finishAll()
        }
        Qa.start(Intent(Qa.ctx, SearchActivity::class.java))
        Qa.expect(SearchActivity::class.java, "Search")
        Qa.type(R.id.search_query, "news")
        Qa.settle(5000)
        Qa.shot("broken_search", "Search with a broken server")
    }

    @Test
    fun internetCutWhileWatchingLive() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.loadZapList()
        Qa.start(PlayerActivity.liveIntent(Qa.ctx, 0))
        Qa.expect(PlayerActivity::class.java, "live player")
        Qa.expectPlaying("live channel")
        Qa.internetDown(25)
        SystemClock.sleep(18_000)
        Qa.shot("live_internet_cut", "Live channel 18 s after the internet was cut")
        val during = Qa.player()
        Qa.step("during the cut: playing=${during?.playing} error=${during?.error} message=${during?.errorText}")
        SystemClock.sleep(9_000)
        Qa.internetUp()
        val recovered = Qa.waitPlaying(60_000)
        Qa.shot("live_after_cut", "Live channel after the internet came back")
        assertTrue("App still open", Qa.current() != null)
        if (!recovered) Qa.finding("problem", "Live TV doesn't recover by itself after the internet comes back", "Waited 60 s after the connection returned")
    }

    @Test
    fun internetCutWhileWatchingMovie() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val url = Session.api(Qa.ctx)!!.movieUrl("101", "mp4")
        Qa.start(PlayerActivity.vodIntent(Qa.ctx, url, "Network test movie", "movie_101", 0, null, false))
        Qa.expect(PlayerActivity::class.java, "movie player")
        if (!Qa.waitPlaying(35_000) && Qa.player()?.error == "ERROR_CODE_DECODING_FAILED") {
            // The emulator's software decoder sometimes fails to start; that is not the network test.
            Qa.step("emulator decoder failed to start; opening the movie again")
            Qa.finishAll()
            Qa.start(PlayerActivity.vodIntent(Qa.ctx, url, "Network test movie", "movie_101", 0, null, false))
            Qa.expect(PlayerActivity::class.java, "movie player")
        }
        Qa.expectPlaying("movie")
        Qa.internetDown(20)
        Qa.right(2)
        SystemClock.sleep(15_000)
        Qa.shot("movie_internet_cut", "Movie while the internet is cut")
        Qa.internetUp()
        val recovered = Qa.waitPlaying(60_000)
        Qa.shot("movie_after_cut", "Movie after the internet came back")
        if (!recovered) Qa.finding("problem", "Movie doesn't continue after the internet comes back")
    }

    @Test
    fun internetCutWhileBrowsingAndStarting() {
        Qa.reset()
        Qa.internetDown(40)
        Qa.launchApp()
        val ok = Qa.waitUntil(45_000) { Qa.current() is PlaylistsActivity && Qa.visible(R.id.message) || Qa.current() is HomeActivity }
        Qa.shot("start_without_internet", "Opening the app with no connection")
        if (!ok) Qa.finding("problem", "No message after 45 s when the app starts without a connection")
        Qa.internetUp()
        Qa.step("connection back: try again")
        if (Qa.current() is PlaylistsActivity) {
            Qa.focusText("Demo TV")
            Qa.ok()
        }
        Qa.expect(HomeActivity::class.java, "Home once the connection is back", 45_000)

        Qa.step("cut while opening Movies")
        Qa.internetDown(20)
        Qa.focusId(R.id.tile_movies, "Movies tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Movies")
        Qa.settle(8000)
        Qa.shot("movies_without_internet", "Movies with no connection")
        Qa.internetUp()
        SystemClock.sleep(2000)
        Qa.backTo(HomeActivity::class.java, "Home")
        if (Qa.current() is HomeActivity) {
            Qa.focusId(R.id.tile_movies, "Movies tile")
            Qa.ok()
            val ok2 = Qa.waitUntil(30_000) { Qa.listCount(R.id.items) > 0 }
            Qa.shot("movies_after_internet_back", "Movies after the connection came back")
            if (!ok2) Qa.finding("problem", "Movies still empty after the connection came back")
        }
    }

    @Test
    fun stateSurvivesRestart() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val prefs = Prefs(Qa.ctx)
        val channel = Qa.loadZapList()[2]
        prefs.toggleFavorite(channel)
        val url = Session.api(Qa.ctx)!!.movieUrl("106", "mp4")
        Qa.start(PlayerActivity.vodIntent(Qa.ctx, url, "Restart test movie", "movie_106", 0, null, false))
        Qa.expect(PlayerActivity::class.java, "movie")
        Qa.expectPlaying("movie")
        SystemClock.sleep(12_000)
        Qa.leavePlayer()
        Qa.step("close everything and open the app again")
        Qa.finishAll()
        Session.clear()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home after reopening (no login needed)")
        Qa.settle(2000)
        Qa.shot("after_restart", "Home after reopening: favorites and Continue watching kept")
        assertTrue("Favorite kept", Prefs(Qa.ctx).favoriteIds(ContentType.LIVE).contains(channel.id))
        val pos = Prefs(Qa.ctx).position("movie_106")
        Qa.metric("saved_position_ms", pos)
        if (pos < 5000) Qa.finding("problem", "Movie position not saved after leaving the player", "Saved: $pos ms after ~15 s of watching")
    }
}
