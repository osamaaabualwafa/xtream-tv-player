package com.osama.xtreamtv.qa

import android.view.KeyEvent
import com.osama.xtreamtv.Appearance
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.GuideActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SearchActivity
import com.osama.xtreamtv.Session
import com.osama.xtreamtv.SettingsActivity
import org.junit.Assert.assertTrue
import org.junit.Test

/** New in the Glass version: Dock Home layout, quick drawer on the Menu key, Cards player menu. */
class T11_NewIn12Test : QaTest() {

    private fun openHome(settings: Map<String, Any>) {
        Qa.reset(settings = settings)
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.waitUntil(15_000) { Qa.text(R.id.tile_movies_count)?.isNotBlank() == true }
        Qa.settle(1200)
    }

    private fun drawerOpen() = Qa.visible(R.id.drawer_panel)

    @Test
    fun dockHome() {
        for (theme in listOf(0, Appearance.THEME_GLASS)) {
            openHome(mapOf("home_style" to Appearance.HOME_DOCK, "theme" to theme))
            Qa.shot("dock_$theme", "Dock Home (theme $theme)")
            for ((id, label, screen) in listOf(
                Triple(R.id.tile_live, "Live TV", BrowseActivity::class.java),
                Triple(R.id.tile_movies, "Movies", BrowseActivity::class.java),
                Triple(R.id.tile_series, "Series", BrowseActivity::class.java),
                Triple(R.id.tile_search, "Search", SearchActivity::class.java),
                Triple(R.id.tile_settings, "Settings", SettingsActivity::class.java),
            )) {
                Qa.focusId(id, "Dock tile $label")
                Qa.shot("dock_focus_${label.replace(' ', '_')}_$theme", "Dock: $label highlighted")
                Qa.ok()
                Qa.expect(screen, label)
                Qa.backTo(HomeActivity::class.java, "Home")
            }
            Qa.focusId(R.id.tile_favorites, "Dock tile Favorites")
            Qa.ok()
            Qa.settle(2000)
            Qa.shot("dock_favorites_$theme", "Dock: Favorites tile opened")
            assertTrue("App still open after the Favorites tile", Qa.current() != null)
            Qa.backTo(HomeActivity::class.java, "Home")
            Qa.step("walk down the Dock rows")
            Qa.down(3)
            Qa.shot("dock_rows_$theme", "Dock: rows below")
            Qa.up(5)
        }
    }

    /** Is the highlighted item really on screen (not a hidden view)? */
    private fun highlightVisible(): Boolean = Qa.main {
        val f = Qa.current()?.currentFocus ?: return@main false
        val r = android.graphics.Rect()
        f.isShown && f.getGlobalVisibleRect(r) && r.width() > 4 && r.height() > 4
    }

    @Test
    fun switchToDockFromSettings() {
        openHome(mapOf("home_style" to Appearance.HOME_TILES))
        Qa.step("on Big tiles Home: open Settings with the gear button")
        Qa.focusId(R.id.settings_button, "Settings button")
        Qa.ok()
        Qa.expect(SettingsActivity::class.java, "Settings")
        Qa.focusId(R.id.menu_home, "Home layout")
        Qa.right() // into the section's first card
        Qa.focusText("Dock")
        Qa.ok()
        Qa.settle(800)
        Qa.shot("dock_chosen", "Home layout: Dock chosen")
        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.settle(1500)
        val visibleAfterBack = highlightVisible()
        Qa.shot("dock_after_settings", "Home rebuilt as Dock after Back (highlight: ${Qa.focusedDescription()})")
        Qa.step("highlight on screen after Back: $visibleAfterBack (${Qa.focusedDescription()})")
        Qa.down()
        Qa.settle(800)
        val visibleAfterKey = highlightVisible()
        Qa.shot("dock_after_one_key", "After one Down press (highlight: ${Qa.focusedDescription()})")
        Qa.step("highlight on screen after one Down: $visibleAfterKey")
        assertTrue("After switching to Dock and pressing Back, an item on Home is highlighted " +
            "(highlight was on ${Qa.focusedDescription()}; after one Down press it is ${if (visibleAfterKey) "visible" else "still hidden"})", visibleAfterBack)
    }

    @Test
    fun quickDrawer() {
        for (home in Appearance.HOME_NAMES.indices) {
            openHome(mapOf("home_style" to home, "theme" to Appearance.THEME_GLASS))
            Qa.key(KeyEvent.KEYCODE_MENU)
            val open = Qa.waitUntil(4000) { drawerOpen() }
            Qa.shot("drawer_${Appearance.HOME_NAMES[home].replace(' ', '_')}", "Quick drawer (Menu key) on ${Appearance.HOME_NAMES[home]}")
            assertTrue("Menu key opens the quick drawer on ${Appearance.HOME_NAMES[home]}", open)
            Qa.back()
            Qa.waitUntil(3000) { !drawerOpen() }
            assertTrue("Back closes the drawer and stays on Home", !drawerOpen() && Qa.current() is HomeActivity)
        }

        Qa.step("drawer buttons with the remote")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(4000) { drawerOpen() }
        Qa.right(6, 250)
        Qa.shot("drawer_right_end", "Drawer: Right to the end (stops at the last button)")
        assertTrue("Right stays inside the drawer", drawerOpen())
        Qa.left(6, 250)
        Qa.up()
        Qa.down()
        assertTrue("Up/Down keep the drawer open", drawerOpen())
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(3000) { !drawerOpen() }
        assertTrue("Menu again closes the drawer", !drawerOpen())

        Qa.step("Settings and playlists from the drawer")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(4000) { drawerOpen() }
        Qa.focusId(R.id.drawer_settings, "drawer Settings")
        Qa.ok()
        Qa.expect(SettingsActivity::class.java, "Settings from the drawer")
        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(4000) { drawerOpen() }
        Qa.focusId(R.id.drawer_refresh, "drawer Refresh")
        Qa.ok()
        Qa.settle(3000)
        Qa.shot("drawer_refresh", "After Refresh lists in the drawer")
        assertTrue(Qa.current() is HomeActivity)

        Qa.step("setting: Menu button does nothing")
        openHome(mapOf("home_menu_button" to 1))
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.settle(1500)
        assertTrue("Menu does nothing when switched off", !drawerOpen())
    }

    @Test
    fun quickDrawerGuide() {
        openHome(mapOf("theme" to Appearance.THEME_GLASS))
        Qa.step("right after opening the app: Menu, then TV guide")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(4000) { drawerOpen() }
        Qa.focusId(R.id.drawer_guide, "drawer TV guide")
        Qa.shot("drawer_guide_highlighted", "Quick drawer with TV guide highlighted")
        Qa.ok()
        val openedFirst = Qa.waitUntil(8000) { Qa.current() is GuideActivity }
        Qa.shot("drawer_guide_result_first", "After OK on TV guide (Live TV not opened yet)")
        if (openedFirst) Qa.backTo(HomeActivity::class.java, "Home")

        Qa.step("after visiting Live TV once: Menu, then TV guide")
        Qa.focusId(R.id.tile_live, "Live TV tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Live TV")
        Qa.waitUntil(20_000) { Qa.listCount(R.id.items) > 0 }
        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitUntil(4000) { drawerOpen() }
        Qa.focusId(R.id.drawer_guide, "drawer TV guide")
        Qa.ok()
        val openedAfter = Qa.waitUntil(8000) { Qa.current() is GuideActivity }
        Qa.shot("drawer_guide_result_after_live", "After OK on TV guide (Live TV opened before)")
        Qa.step("guide opened: right after start=$openedFirst, after visiting Live TV=$openedAfter")
        assertTrue("TV guide in the quick drawer opens the guide right after starting the app " +
            "(after visiting Live TV it ${if (openedAfter) "does" else "doesn't either"})", openedFirst)
    }

    private fun cards(theme: Int) {
        openHome(mapOf("player_menu_style" to 2, "theme" to theme))
        Qa.loadZapList()
        Qa.start(PlayerActivity.liveIntent(Qa.ctx, 0))
        Qa.expect(PlayerActivity::class.java, "live player")
        Qa.expectPlaying("live channel")
        Qa.right()
        Qa.waitVisible(R.id.player_menu, 4000)
        Qa.shot("cards_live_$theme", "Cards menu on a live channel")
        assertTrue("Right opens the Cards menu", Qa.visible(R.id.player_menu))
        Qa.right(3, 250)
        Qa.down(2, 250)
        Qa.shot("cards_live_moved_$theme", "Cards menu: moving between cards")
        Qa.left(4, 250)
        Qa.up(3, 250)
        Qa.focusText("TV guide")
        Qa.ok()
        Qa.expect(GuideActivity::class.java, "TV guide from the Cards menu")
        Qa.backTo(PlayerActivity::class.java, "player")
        Qa.finishAll()

        val url = Session.api(Qa.ctx)!!.movieUrl("101", "mp4")
        Qa.start(PlayerActivity.vodIntent(Qa.ctx, url, "Cards check movie", "movie_101", 0, null, false))
        Qa.expect(PlayerActivity::class.java, "movie player")
        Qa.expectPlaying("movie")
        Qa.key(KeyEvent.KEYCODE_MENU)
        Qa.waitVisible(R.id.player_menu, 4000)
        Qa.shot("cards_movie_$theme", "Cards menu in a movie (Menu key)")
        assertTrue("Menu opens the Cards menu in a movie", Qa.visible(R.id.player_menu))
        Qa.focusText("Speed")
        Qa.ok()
        Qa.settle(800)
        Qa.shot("cards_movie_speed_$theme", "Cards: Speed (tiles apply at once)")
        // Back closes whatever is open on top of the video, one step at a time, never the player itself here.
        repeat(3) { if (Qa.visible(R.id.player_menu)) { Qa.back(); Qa.settle(600) } }
        Qa.shot("cards_closed_$theme", "After Back on the Cards menu")
        assertTrue("Back closes the Cards menu", !Qa.visible(R.id.player_menu))
        assertTrue("Still in the player after closing the menu", Qa.current() is PlayerActivity)
        Qa.expectPlaying("movie after closing the menu")
    }

    @Test fun cardsPlayerMenuDefault() = cards(0)
    @Test fun cardsPlayerMenuGlass() = cards(Appearance.THEME_GLASS)
}
