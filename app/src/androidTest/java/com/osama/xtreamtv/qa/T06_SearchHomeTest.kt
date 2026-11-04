package com.osama.xtreamtv.qa

import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.DetailsActivity
import com.osama.xtreamtv.GuideActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SearchActivity
import com.osama.xtreamtv.SettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search across everything, the Home screen's buttons and rows, the guide from the list. */
class T06_SearchHomeTest : QaTest() {

    private fun search(query: String, label: String) {
        Qa.type(R.id.search_query, query)
        Qa.waitUntil(15_000) { !Qa.visible(R.id.search_progress) }
        Qa.settle(1500)
        Qa.shot("search_$label", "Search: \"$query\"")
    }

    @Test
    fun searchEverything() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_search, "Search tile")
        Qa.ok()
        Qa.expect(SearchActivity::class.java, "Search")
        Qa.shot("search_empty", "Search screen before typing")

        search("news", "news")
        assertTrue("\"news\" finds live channels", Qa.findText("News 24") != null || Qa.findText("World News") != null)
        search("رحلة", "arabic")
        if (Qa.findText("رحلة إلى الصحراء") == null) Qa.finding("problem", "Arabic search finds nothing", "\"رحلة\" should find the movie \"رحلة إلى الصحراء\"")
        search("ninja", "series")
        if (Qa.findText("Ninja Steps") == null) Qa.finding("problem", "Search doesn't find the series \"Ninja Steps\"")
        search("zzzz-nothing-like-this", "no_results")

        Qa.step("filter chips")
        search("de", "short_query")
        for (chip in listOf(R.id.chip_movies, R.id.chip_series, R.id.chip_live, R.id.chip_all)) {
            if (Qa.visible(chip)) {
                Qa.focusId(chip, Qa.ctx.resources.getResourceEntryName(chip))
                Qa.ok()
                Qa.settle(1000)
                Qa.shot("search_${Qa.ctx.resources.getResourceEntryName(chip)}", "Search filtered")
            }
        }

        Qa.step("open a result")
        search("news 24", "open")
        Qa.focusText("News 24")
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "channel from search")
        Qa.expectPlaying("channel opened from search")
        Qa.leavePlayer()
        Qa.expect(SearchActivity::class.java, "back to search")
        search("Desert", "movie_open")
        val hit = Qa.findText("Desert")
        if (hit != null) {
            Qa.focusText("Desert")
            Qa.ok()
            Qa.waitUntil(10_000) { Qa.current() is DetailsActivity }
            Qa.shot("search_movie_details", "Movie opened from search")
        }
    }

    @Test
    fun homeButtonsAndRows() {
        Qa.reset(settings = mapOf("featured_banner" to true))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.waitUntil(15_000) { Qa.text(R.id.tile_movies_count)?.isNotBlank() == true }
        Qa.settle(2000)
        Qa.shot("home_featured", "Home with the featured banner and counts")
        val counts = listOf(R.id.tile_live_count, R.id.tile_movies_count, R.id.tile_series_count).map { Qa.text(it) }
        Qa.step("tile counts: $counts")
        if (counts.any { it.isNullOrBlank() }) Qa.finding("warning", "Some Home tiles show no count", counts.toString())

        Qa.step("walk down the Home rows")
        Qa.down(1)
        Qa.shot("home_row_1", "Home: first row")
        Qa.down(1)
        Qa.shot("home_row_2", "Home: second row")
        Qa.down(2)
        Qa.shot("home_row_4", "Home: further down")
        Qa.right(4)
        Qa.shot("home_row_right", "Home: moved right in a row")
        Qa.up(6)

        Qa.step("settings button")
        Qa.focusId(R.id.settings_button, "Settings button")
        Qa.ok()
        Qa.expect(SettingsActivity::class.java, "Settings")
        Qa.backTo(HomeActivity::class.java, "Home")

        Qa.step("refresh lists")
        if (Qa.visible(R.id.refresh_lists)) {
            Qa.focusId(R.id.refresh_lists, "Refresh lists")
            Qa.ok()
            Qa.settle(3000)
            Qa.shot("refresh_lists", "After refreshing the lists")
            assertTrue(Qa.current() is HomeActivity)
        }
    }

    @Test
    fun guideFromLiveList() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_live, "Live TV tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Live TV")
        Qa.waitUntil(20_000) { Qa.listCount(R.id.items) > 0 }
        Qa.focusId(R.id.tv_guide, "TV guide button")
        Qa.ok()
        Qa.expect(GuideActivity::class.java, "TV guide")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.guide_programs) > 0 }
        Qa.shot("guide_from_list", "TV guide")
        Qa.down(5, 250)
        Qa.shot("guide_scrolled", "TV guide, other channel")
        Qa.right()
        Qa.down(3, 250)
        Qa.shot("guide_programmes", "TV guide, programmes")
        Qa.left()
        Qa.ok()
        Qa.settle(2000)
        Qa.shot("guide_ok", "OK on a channel in the guide")
        if (Qa.current() is PlayerActivity) Qa.expectPlaying("channel from the guide")
    }
}
