package com.osama.xtreamtv.qa

import android.os.SystemClock
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.DetailsActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.R
import org.junit.Assert.assertTrue
import org.junit.Test

/** Series: seasons, episodes, next episode, both episode-list formats, an empty series. */
class T05_SeriesTest : QaTest() {

    private fun openSeries(id: String) {
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val s = Qa.series().first { it.id == id }
        Qa.start(DetailsActivity.intent(Qa.ctx, s))
        Qa.expect(DetailsActivity::class.java, "series page ${s.name}")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.episodes) > 0 || Qa.text(R.id.message)?.isNotBlank() == true }
        Qa.settle(1500)
    }

    @Test
    fun browseSeriesFromHome() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_series, "Series tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Series")
        assertTrue(Qa.waitUntil(30_000) { Qa.listCount(R.id.items) > 0 })
        Qa.settle(2000)
        Qa.shot("series_grid", "Series screen")
        for (cat in listOf("Drama", "Kids", "Anime")) {
            Qa.focusText(cat)
            Qa.settle(1500)
            Qa.shot("series_${cat}", "Series category: $cat")
        }
        Qa.focus("first poster") { Qa.current()?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.items)?.getChildAt(0) }
        Qa.ok()
        Qa.expect(DetailsActivity::class.java, "series page")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.episodes) > 0 }
        Qa.shot("series_page", "Series page with seasons and episodes")
    }

    @Test
    fun seasonsAndNextEpisode() {
        Qa.reset(settings = mapOf("next_countdown" to 10, "auto_next" to true))
        openSeries("201") // 3 seasons, episodes sent as an object
        Qa.shot("series_3_seasons", "Series with 3 seasons")
        assertTrue("Episodes listed", Qa.listCount(R.id.episodes) > 0)

        Qa.step("change season with the remote")
        Qa.focusText("Season 2")
        Qa.ok()
        Qa.settle(1500)
        Qa.shot("season_2", "Season 2 selected")
        Qa.focusText("Season 1")
        Qa.ok()
        Qa.settle(1500)

        Qa.step("play episode 1")
        Qa.focus("first episode") { Qa.current()?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.episodes)?.getChildAt(0) }
        Qa.ok()
        Qa.settle(1500)
        if (Qa.current() !is PlayerActivity) {
            // Some layouts ask "Resume or start over" first.
            Qa.shot("before_episode", "Before the episode starts")
            Qa.ok()
        }
        Qa.expect(PlayerActivity::class.java, "episode player")
        Qa.expectPlaying("S01E01")
        Qa.shot("episode_playing", "Episode playing")
        val firstTitle = Qa.player()?.title

        Qa.step("jump near the end: the next-episode prompt appears, then it plays on its own")
        Qa.right(3, 500)
        val prompt = Qa.waitUntil(25_000) { Qa.visible(R.id.action_card) }
        Qa.shot("next_episode_prompt", "Next episode countdown")
        if (!prompt) Qa.finding("problem", "No \"Next episode\" prompt near the end of an episode")
        val moved = Qa.waitUntil(45_000) { Qa.player()?.title.let { it != null && it != firstTitle } }
        Qa.settle(2000)
        Qa.shot("next_episode_started", "Next episode started by itself")
        if (!moved) Qa.finding("problem", "Next episode did not start automatically", "Title stayed \"$firstTitle\"")
        else Qa.expectPlaying("S01E02")

        Qa.step("episodes list inside the player")
        // The controls hide after a few seconds; OK brings them back (every key keeps them up).
        if (!Qa.visible(R.id.vc_episodes)) Qa.ok()
        if (Qa.waitVisible(R.id.vc_episodes, 4000)) {
            Qa.focusId(R.id.vc_episodes, "Episodes button")
            Qa.ok()
            Qa.settle(1200)
            Qa.shot("player_episodes", "Episodes list in the player")
            Qa.back()
        }
        Qa.leavePlayer()
        Qa.expect(DetailsActivity::class.java, "series page after watching")
        Qa.settle(1500)
        Qa.shot("series_after_watching", "Series page after watching (resume / watched marks)")
    }

    @Test
    fun otherEpisodeFormatAndLongSeason() {
        Qa.reset()
        openSeries("202") // episodes sent as an array of arrays
        Qa.shot("series_array_format", "Series whose episodes come in the other format")
        assertTrue("Episodes listed for the array format", Qa.listCount(R.id.episodes) > 0)
        openSeries("204") // 12 episodes in a season
        Qa.focus("first episode") { Qa.current()?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.episodes)?.getChildAt(0) }
        Qa.down(10, 250)
        Qa.shot("long_season_scrolled", "Scrolled down a 12-episode season")
    }

    @Test
    fun emptySeries() {
        Qa.reset()
        openSeries("215")
        Qa.shot("empty_series", "Series with no episodes")
        assertTrue("App still open", Qa.current() is DetailsActivity)
        if (Qa.visible(R.id.play)) {
            Qa.focusId(R.id.play, "Play button")
            SystemClock.sleep(300)
            Qa.ok()
            Qa.settle(2000)
            Qa.shot("empty_series_play", "Pressing Play on a series with no episodes")
        }
        assertTrue("App still open", Qa.current() != null)
    }
}
