package com.osama.xtreamtv.qa

import android.view.KeyEvent
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.GuideActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.Prefs
import com.osama.xtreamtv.R
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV the way people use it: browse, play, zap, channel numbers, guide, favorites. */
class T03_LiveTvTest : QaTest() {

    private fun openLiveTv() {
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_live, "Live TV tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Live TV list")
        assertTrue("Channels appear", Qa.waitUntil(30_000) { Qa.listCount(R.id.items) > 0 })
        Qa.settle(1500)
    }

    private fun playFocusedOrFirstChannel(name: String) {
        Qa.focusText(name, 60)
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "player for $name")
    }

    @Test
    fun browseCategoriesAndPlay() {
        Qa.reset()
        openLiveTv()
        Qa.shot("live_list", "Live TV: categories and channels")

        Qa.step("walk through every category with the remote")
        Qa.focusText("All")
        Qa.shot("cat_all", "All channels")
        for (cat in listOf("News", "Sports", "Kids", "قنوات عربية", "A very long category", "Empty category")) {
            Qa.focusText(cat)
            Qa.settle(1200)
            Qa.shot("cat_${cat.take(12)}", "Category: $cat")
        }
        val emptyMsg = Qa.waitUntil(5000) { Qa.text(R.id.message)?.isNotBlank() == true }
        if (!emptyMsg) Qa.finding("warning", "Empty category shows no message", "Expected something like \"Nothing here.\"")

        Qa.step("pick a channel")
        Qa.focusText("News")
        Qa.settle(1500)
        Qa.focusText("World News HD")
        Qa.settle(2500)
        Qa.shot("channel_preview", "Highlighted channel with its preview and now/next")
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "live player")
        Qa.expectPlaying("World News HD")
        Qa.shot("live_playing", "Live channel playing")

        Qa.step("channel info shows when a channel starts; OK shows it again")
        if (!Qa.visible(R.id.overlay)) Qa.ok()
        val shown = Qa.waitVisible(R.id.overlay, 5000)
        val name = Qa.text(R.id.overlay_name)
        val now = Qa.text(R.id.overlay_now)
        Qa.shot("live_overlay", "Channel info (name, logo, now / next)")
        if (!shown) Qa.finding("problem", "OK doesn't show the channel info on a live channel")
        else if (name == null || !name.contains("World News")) Qa.finding("problem", "Channel info shows the wrong name", "Shows: $name")
        if (now.isNullOrBlank()) Qa.finding("warning", "Channel info has no \"Now\" programme", "The fake server sends one for every channel")

        Qa.backTo(BrowseActivity::class.java, "back on the channel list")
    }

    @Test
    fun zapping() {
        Qa.reset()
        openLiveTv()
        playFocusedOrFirstChannel("News 24")
        Qa.expectPlaying("News 24")
        val mem0 = Qa.memoryMb()

        Qa.step("Up / Down change the channel")
        Qa.up()
        Qa.settle(1500)
        val after = Qa.player()?.title
        Qa.shot("zap_up", "After pressing Up once")
        Qa.expectPlaying("next channel after Up")
        Qa.down()
        Qa.expectPlaying("channel after Down")

        Qa.step("fast zapping: 20 presses quickly, then wait")
        Qa.key(KeyEvent.KEYCODE_DPAD_UP, 20, 120)
        Qa.settle(2000)
        Qa.shot("after_fast_zap", "After 20 quick channel changes")
        assertTrue("App still in the player after fast zapping", Qa.current() is PlayerActivity)
        Qa.key(KeyEvent.KEYCODE_CHANNEL_DOWN, 5, 400)
        Qa.key(KeyEvent.KEYCODE_CHANNEL_UP, 2, 400)
        Qa.settle(1500)
        Qa.metric("memory_mb_before_zapping", mem0)
        Qa.metric("memory_mb_after_zapping", Qa.memoryMb())
        Qa.step("title after first Up was: $after")

        Qa.step("type channel number 7 on the remote")
        Qa.key(KeyEvent.KEYCODE_7, 1, 200)
        Qa.shot("number_entry", "Typing a channel number")
        Qa.settle(4000)
        Qa.expectPlaying("channel 7 typed with the number keys")
        val t = Qa.player()?.title ?: ""
        if (!t.contains("Sports 1")) Qa.finding("warning", "Typing 7 didn't open channel 7 (Sports 1 HD)", "Now showing: $t")

        Qa.step("Last channel key goes back")
        Qa.key(KeyEvent.KEYCODE_LAST_CHANNEL)
        Qa.settle(2000)
        Qa.expectPlaying("previous channel")

        Qa.step("pause / play live")
        Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        Qa.settle(1500)
        Qa.shot("live_paused", "Live channel paused")
        Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        Qa.expectPlaying("live after un-pausing")
    }

    @Test
    fun brokenAndSlowChannels() {
        Qa.reset()
        openLiveTv()
        playFocusedOrFirstChannel("News 24")
        Qa.expectPlaying("News 24")

        Qa.step("channel 13 is broken on the server (404)")
        Qa.key(KeyEvent.KEYCODE_1, 1, 150)
        Qa.key(KeyEvent.KEYCODE_3, 1, 150)
        val gotMessage = Qa.waitUntil(40_000) { Qa.player()?.errorText != null }
        Qa.shot("broken_channel", "A channel that doesn't work")
        assertTrue("App still open on a broken channel", Qa.current() is PlayerActivity)
        if (!gotMessage) Qa.finding("problem", "No message for a channel that doesn't play", "Channel 13 returns 404; the screen should say it can't play")

        Qa.step("Up from the broken channel recovers")
        Qa.up()
        Qa.expectPlaying("channel after the broken one", 45_000)

        Qa.step("slow channel (6 s before the server answers)")
        Qa.key(KeyEvent.KEYCODE_1, 1, 150)
        Qa.key(KeyEvent.KEYCODE_4, 1, 150)
        Qa.settle(2500)
        Qa.shot("slow_channel_loading", "Slow channel while it loads")
        Qa.expectPlaying("slow channel", 45_000)
    }

    @Test
    fun guideMenusAndChannelList() {
        Qa.reset()
        openLiveTv()
        playFocusedOrFirstChannel("News 24")
        Qa.expectPlaying("News 24")

        Qa.step("Left opens the channel list inside the player")
        Qa.left()
        Qa.waitVisible(R.id.channels_panel, 5000)
        Qa.shot("player_channel_list", "Channel list over the video")
        Qa.down(3)
        Qa.ok()
        Qa.expectPlaying("channel picked from the list")

        Qa.step("Right opens the live options")
        Qa.right()
        Qa.settle(1000)
        Qa.shot("live_options", "Live options menu")
        Qa.back()
        Qa.settle(1000)
        Qa.shot("options_closed", "After Back on the options menu")

        Qa.step("Guide key on the remote")
        Qa.key(KeyEvent.KEYCODE_GUIDE)
        if (!Qa.waitUntil(4000) { Qa.current() is GuideActivity }) {
            // The emulator's system keeps the Guide key for itself; real remotes may differ.
            Qa.step("Guide key didn't reach the app on this emulator; opening the guide from the options menu")
            Qa.right()
            Qa.dialogShowing("TV guide", 3000)
            Qa.focusText("TV guide")
            Qa.ok()
        }
        Qa.expect(GuideActivity::class.java, "TV guide")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.guide_programs) > 0 }
        Qa.shot("guide", "TV guide from the player")
        Qa.down(2)
        Qa.right()
        Qa.shot("guide_moved", "Moving in the guide")
        Qa.back()
        Qa.expect(PlayerActivity::class.java, "back in the player")
    }

    @Test
    fun catchUpFromGuide() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val channels = Qa.loadZapList()
        val index = channels.indexOfFirst { it.archiveDays > 0 }
        assertTrue(index >= 0)
        Qa.start(GuideActivity.intent(Qa.ctx, index, false))
        Qa.expect(GuideActivity::class.java, "TV guide of a catch-up channel")
        Qa.waitUntil(15_000) { Qa.listCount(R.id.guide_programs) > 0 }
        Qa.shot("guide_catchup", "Guide of a channel with catch-up (past shows can be played)")
        Qa.step("go to the programme list, then up to a past programme")
        Qa.focusTextIn(R.id.guide_programs, "On now")
        Qa.up(2)
        Qa.shot("guide_past_programme", "A past programme highlighted")
        Qa.ok()
        Qa.settle(2000)
        if (Qa.current() is PlayerActivity) {
            // GitHub's emulator sometimes can't decode the test stream's sound (its software AAC decoder
            // fails): a warning there, not a failed check (QA run 2026-11-12). Any other error still fails.
            if (!Qa.waitPlaying(35_000) && Qa.player()?.error?.contains("DECODING_FAILED") == true) {
                Qa.shot("catchup_decoder")
                Qa.finding("warning", "Catch-up: the emulator's sound decoder failed", Qa.player()?.errorText.orEmpty())
                return
            }
            Qa.expectPlaying("catch-up programme")
            Qa.shot("catchup_playing", "Past programme playing")
        } else {
            Qa.shot("catchup_not_opened", "OK on a past programme")
            Qa.finding("warning", "OK on a past programme with catch-up didn't start it", "Screen: ${Qa.current()?.javaClass?.simpleName}")
        }
    }

    @Test
    fun favorites() {
        Qa.reset()
        openLiveTv()
        Qa.focusText("Sports")
        Qa.settle(1200)
        Qa.focusText("Football Live")
        Qa.step("long-press OK adds to favorites")
        Qa.longOk()
        Qa.shot("favorite_added", "Long-press OK on a channel")
        val favs = Prefs(Qa.ctx).favoriteIds(ContentType.LIVE)
        assertTrue("Channel saved as favorite (favorites: $favs)", favs.isNotEmpty())

        Qa.focusText("★ Favorites")
        Qa.settle(1500)
        Qa.shot("favorites_category", "★ Favorites category")
        assertTrue("Favorite shows in ★ Favorites", Qa.findText("Football Live") != null)

        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.waitUntil(5000) { Qa.visible(R.id.row_channels) }
        Qa.shot("home_favorite_channels", "Home with the favorite channels row")
        if (!Qa.visible(R.id.row_channels)) Qa.finding("warning", "Favorite channel not shown on Home", "Expected the favorite channels row")

        Qa.step("remove it again")
        Qa.focusId(R.id.tile_live, "Live TV tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Live TV")
        Qa.focusText("★ Favorites")
        Qa.settle(1500)
        Qa.focusText("Football Live")
        Qa.longOk()
        Qa.settle(800)
        Qa.shot("favorite_removed", "After removing the favorite")
        assertTrue("Favorite removed", Prefs(Qa.ctx).favoriteIds(ContentType.LIVE).isEmpty())
    }

    @Test
    fun searchInsideLiveList() {
        Qa.reset()
        openLiveTv()
        Qa.focusText("All")
        Qa.settle(1500)
        val all = Qa.listCount(R.id.items)
        Qa.focusId(R.id.search, "search box")
        Qa.type(R.id.search, "sports")
        Qa.settle(1500)
        Qa.shot("live_filter", "Filtering the channel list with \"sports\"")
        val filtered = Qa.listCount(R.id.items)
        assertTrue("Filter narrows the list ($all -> $filtered)", filtered in 1 until all)
        Qa.type(R.id.search, "zzzz-nothing")
        Qa.settle(1200)
        Qa.shot("live_filter_none", "Filter with no result")
        Qa.type(R.id.search, "")
        Qa.settle(800)
    }

    @Test
    fun tsOnlyAccountPlays() {
        Qa.reset(users = listOf("tsonly"))
        openLiveTv()
        playFocusedOrFirstChannel("News 24")
        Qa.expectPlaying("channel on an account without HLS (MPEG-TS only)", 45_000)
        Qa.shot("ts_only_playing", "MPEG-TS only account")
    }
}
