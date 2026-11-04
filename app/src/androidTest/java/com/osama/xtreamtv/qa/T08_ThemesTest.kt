package com.osama.xtreamtv.qa

import android.content.Intent
import com.osama.xtreamtv.Appearance
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.DetailsActivity
import com.osama.xtreamtv.GuideActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.LoginActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.PlaylistsActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SearchActivity
import com.osama.xtreamtv.Session
import com.osama.xtreamtv.SettingsActivity
import org.junit.Test

/**
 * Every theme, layout, colour, background and size: each screen is opened and photographed,
 * and the automatic check looks for cut-off, overlapping, hidden or hard-to-read text.
 */
class T08_ThemesTest : QaTest() {

    private var tag = ""

    private fun home(label: String = "home") {
        Qa.finishAll()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.waitUntil(15_000) { Qa.text(R.id.tile_movies_count)?.isNotBlank() == true }
        Qa.settle(1500)
        Qa.shot("${tag}_$label", "$tag · Home")
    }

    private fun browse(type: ContentType, label: String) {
        Qa.start(Intent(Qa.ctx, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, type.name))
        Qa.expect(BrowseActivity::class.java, "$type list")
        Qa.waitUntil(20_000) { Qa.listCount(R.id.items) > 0 }
        Qa.settle(1500)
        Qa.shot("${tag}_$label", "$tag · ${type.name.lowercase()} list")
        Qa.right(2)
        Qa.down(1)
        Qa.shot("${tag}_${label}_moved", "$tag · ${type.name.lowercase()} list, highlight moved")
        Qa.finishAll()
    }

    private fun detailsScreens() {
        val movie = Qa.movies().first { it.id == "109" } // Arabic title
        Qa.start(DetailsActivity.intent(Qa.ctx, movie))
        Qa.expect(DetailsActivity::class.java, "movie details")
        Qa.waitUntil(10_000) { Qa.text(R.id.plot)?.isNotBlank() == true }
        Qa.shot("${tag}_movie_details", "$tag · movie details")
        Qa.finishAll()
        val series = Qa.series().first { it.id == "201" }
        Qa.start(DetailsActivity.intent(Qa.ctx, series))
        Qa.expect(DetailsActivity::class.java, "series details")
        Qa.waitUntil(10_000) { Qa.listCount(R.id.episodes) > 0 }
        Qa.settle(1000)
        Qa.shot("${tag}_series_details", "$tag · series details")
        Qa.finishAll()
    }

    private fun otherScreens() {
        Qa.start(Intent(Qa.ctx, SearchActivity::class.java))
        Qa.expect(SearchActivity::class.java, "Search")
        Qa.type(R.id.search_query, "de")
        Qa.waitUntil(15_000) { !Qa.visible(R.id.search_progress) }
        Qa.settle(1500)
        Qa.shot("${tag}_search", "$tag · search results")
        Qa.finishAll()

        Qa.loadZapList()
        Qa.start(GuideActivity.intent(Qa.ctx, 3, false))
        Qa.expect(GuideActivity::class.java, "Guide")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.guide_programs) > 0 }
        Qa.shot("${tag}_guide", "$tag · TV guide")
        Qa.finishAll()

        Qa.start(Intent(Qa.ctx, SettingsActivity::class.java))
        Qa.expect(SettingsActivity::class.java, "Settings")
        Qa.focusId(R.id.menu_color, "Look")
        Qa.shot("${tag}_settings", "$tag · settings")
        Qa.focusId(R.id.menu_player, "Player")
        Qa.shot("${tag}_settings_player", "$tag · settings, player")
        Qa.finishAll()

        Qa.start(PlaylistsActivity.manageIntent(Qa.ctx))
        Qa.expect(PlaylistsActivity::class.java, "Playlists")
        Qa.settle(2500)
        Qa.shot("${tag}_playlists", "$tag · playlist chooser")
        Qa.finishAll()

        Qa.start(LoginActivity.addIntent(Qa.ctx))
        Qa.expect(LoginActivity::class.java, "Add playlist")
        Qa.shot("${tag}_login", "$tag · add playlist")
        Qa.finishAll()
    }

    private fun playerScreens() {
        Qa.loadZapList()
        Qa.start(PlayerActivity.liveIntent(Qa.ctx, 1))
        Qa.expect(PlayerActivity::class.java, "live player")
        Qa.waitPlaying()
        Qa.ok()
        Qa.settle(800)
        Qa.shot("${tag}_live_info", "$tag · live channel info")
        Qa.left()
        Qa.settle(800)
        Qa.shot("${tag}_live_channels", "$tag · channel list in the player")
        Qa.finishAll()

        val url = Session.api(Qa.ctx)!!.movieUrl("101", "mp4")
        Qa.start(PlayerActivity.vodIntent(Qa.ctx, url, "Theme check movie", "movie_101", 0, null, false))
        Qa.expect(PlayerActivity::class.java, "movie player")
        Qa.waitPlaying()
        Qa.ok()
        Qa.settle(800)
        Qa.shot("${tag}_vod_controls", "$tag · movie controls")
        Qa.finishAll()
    }

    private fun theme(index: Int) {
        tag = "theme_${Appearance.THEME_NAMES[index]}"
        for (home in 0..3) {
            Qa.reset(settings = mapOf("theme" to index, "home_style" to home))
            home("home_${Appearance.HOME_NAMES[home].replace(' ', '_')}")
        }
        for (b in 0..3) {
            Qa.reset(settings = mapOf("theme" to index, "browse_style" to b))
            Qa.launchApp()
            Qa.expect(HomeActivity::class.java, "Home")
            Qa.finishAll()
            val name = Appearance.BROWSE_NAMES[b]
            browse(ContentType.MOVIE, "movies_$name")
            browse(ContentType.SERIES, "series_$name")
            if (b != Appearance.BROWSE_PREVIEW) browse(ContentType.LIVE, "live_$name")
        }
        Qa.reset(settings = mapOf("theme" to index))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.finishAll()
        detailsScreens()
        otherScreens()
        playerScreens()
    }

    @Test fun themeDefault() = theme(0)
    @Test fun themeCinema() = theme(1)
    @Test fun themeCoastal() = theme(2)
    @Test fun themeCoconut() = theme(3)

    @Test
    fun colours() {
        for ((i, name) in Appearance.ACCENT_NAMES.withIndex()) {
            tag = "colour_$name"
            Qa.reset(settings = mapOf("theme" to 0, "accent" to i))
            home()
            browse(ContentType.MOVIE, "movies")
        }
    }

    @Test
    fun palettesAndBackgrounds() {
        for ((i, name) in Appearance.PALETTE_NAMES.withIndex()) {
            tag = "palette_$name"
            Qa.reset(settings = mapOf("theme" to 0, "palette" to i))
            home()
            browse(ContentType.MOVIE, "movies")
            detailsScreens()
        }
        for (i in 0..3) {
            tag = "background_$i"
            Qa.reset(settings = mapOf("theme" to 0, "background_style" to i))
            home()
            browse(ContentType.SERIES, "series")
        }
    }

    @Test
    fun sizes() {
        // ui_size values: 3 Small, 0 Compact, 1 Normal, 2 Large. text_size: 0 Small .. 3 Extra large.
        for ((ui, text) in listOf(3 to 0, 2 to 3, 0 to 3, 1 to 2, 2 to 2)) {
            tag = "size_ui${ui}_text$text"
            Qa.reset(settings = mapOf("ui_size" to ui, "text_size" to text))
            home()
            browse(ContentType.MOVIE, "movies")
            browse(ContentType.LIVE, "live")
            detailsScreens()
            Qa.start(Intent(Qa.ctx, SettingsActivity::class.java))
            Qa.expect(SettingsActivity::class.java, "Settings")
            Qa.focusId(R.id.menu_player, "Player")
            Qa.shot("${tag}_settings_player", "$tag · settings")
            Qa.finishAll()
            playerScreens()
        }
    }

    @Test
    fun effects() {
        val combos = listOf(
            "glow_off" to mapOf<String, Any>("poster_glow" to false),
            "glow_on_backdrop" to mapOf<String, Any>("poster_glow" to true, "backdrop_follows" to true),
            "featured_banner" to mapOf<String, Any>("featured_banner" to true),
        )
        for ((name, s) in combos) {
            tag = "effects_$name"
            Qa.reset(settings = s)
            home()
            browse(ContentType.MOVIE, "movies")
        }
    }
}
