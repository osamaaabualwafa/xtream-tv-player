package com.osama.xtreamtv.qa

import android.os.SystemClock
import android.view.KeyEvent
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.DetailsActivity
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.Prefs
import com.osama.xtreamtv.R
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movies: browse, details, play, seek, pause, resume later, favorites, a broken file. */
class T04_MoviesTest : QaTest() {

    private fun openMovies() {
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.tile_movies, "Movies tile")
        Qa.ok()
        Qa.expect(BrowseActivity::class.java, "Movies")
        assertTrue("Posters appear", Qa.waitUntil(30_000) { Qa.listCount(R.id.items) > 0 })
        Qa.settle(2000)
    }

    @Test
    fun browseAndWatchAMovie() {
        Qa.reset(settings = mapOf("resume_mode" to 0))
        openMovies()
        Qa.shot("movies_grid", "Movies screen")

        Qa.step("move around the posters")
        Qa.focus("first poster") { Qa.current()?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.items)?.getChildAt(0) }
        Qa.right(3)
        Qa.down(2)
        Qa.shot("poster_focused", "A highlighted poster with its info")
        Qa.left(2)
        Qa.up(1)

        for (cat in listOf("Action", "Comedy", "دراما", "Documentary", "Empty movies", "▶ Continue watching", "★ Favorites")) {
            Qa.focusText(cat)
            Qa.settle(1500)
            Qa.shot("movies_${cat.take(12)}", "Movies category: $cat")
        }

        Qa.step("open a movie")
        Qa.focusText("Action")
        Qa.settle(1500)
        Qa.focus("first poster") { Qa.current()?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.items)?.getChildAt(0) }
        Qa.ok()
        Qa.expect(DetailsActivity::class.java, "movie details")
        Qa.waitUntil(10_000) { Qa.text(R.id.plot)?.isNotBlank() == true }
        Qa.shot("movie_details", "Movie details page")

        Qa.step("play it")
        Qa.focusId(R.id.play, "Play button")
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "movie player")
        Qa.expectPlaying("the movie")
        Qa.shot("movie_playing", "Movie playing")
        // Resume and Continue watching only keep a movie after 30 s of watching (shorter visits are
        // dropped, by design): most of that is watched here, the rest after the jump below.
        SystemClock.sleep(20_000)

        Qa.step("OK shows the controls")
        Qa.ok()
        Qa.settle(800)
        Qa.shot("movie_controls", "Playback controls")
        // Back closes the controls (only while they are still showing; they hide by themselves).
        if (Qa.visible(R.id.vod_controls)) Qa.back()
        Qa.waitUntil(8000) { !Qa.visible(R.id.vod_controls) }
        assertTrue("Still in the player after closing the controls", Qa.current() is PlayerActivity)

        Qa.step("Right jumps forward")
        val before = Qa.player()?.positionMs ?: 0
        Qa.right()
        Qa.settle(1500)
        val after = Qa.player()?.positionMs ?: 0
        Qa.shot("after_seek", "After pressing Right (jump forward)")
        if (after - before < 5000) Qa.finding("problem", "Right didn't jump forward in a movie", "Position $before ms -> $after ms")
        // Back again, so the 45 s test movie isn't near its end (its last 5% counts as finished).
        Qa.left()
        Qa.settle(1000)

        Qa.step("pause")
        // OK (above) shows the controls and pauses, by design: Play/Pause is only pressed when it plays.
        if (Qa.player()?.playing == true) {
            Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Qa.settle(1500)
        }
        assertTrue("Paused", Qa.player()?.playing == false)
        Qa.shot("movie_paused", "Movie paused")
        Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        Qa.expectPlaying("movie after un-pausing")
        SystemClock.sleep(8000)

        Qa.step("Back saves the position; details now offer Resume")
        Qa.leavePlayer()
        Qa.expect(DetailsActivity::class.java, "details after watching")
        Qa.waitVisible(R.id.resume, 5000)
        Qa.shot("details_resume", "Details with the Resume button")
        if (!Qa.visible(R.id.resume)) {
            Qa.finding("problem", "No Resume button after watching part of a movie")
        } else {
            Qa.focusId(R.id.resume, "Resume button")
            Qa.ok()
            Qa.expect(PlayerActivity::class.java, "resumed movie")
            Qa.expectPlaying("resumed movie")
            val pos = Qa.player()?.positionMs ?: 0
            if (pos < 8000) Qa.finding("problem", "Resume started from the beginning", "Position after Resume: $pos ms")
            Qa.shot("resumed", "Resumed where it stopped")
            Qa.leavePlayer()
        }

        Qa.step("Home shows it under Continue watching")
        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.waitUntil(6000) { Qa.visible(R.id.row_continue) }
        Qa.shot("home_continue", "Home with Continue watching")
        if (!Qa.visible(R.id.row_continue)) Qa.finding("problem", "Movie not in Continue watching on Home")
    }

    @Test
    fun movieWatchedToTheEnd() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val movie = Qa.movies().first { it.id == "104" }
        Qa.start(DetailsActivity.intent(Qa.ctx, movie))
        Qa.expect(DetailsActivity::class.java, "movie details")
        Qa.focusId(R.id.play, "Play button")
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "player")
        Qa.expectPlaying("movie")
        Qa.step("jump near the end and let it finish")
        Qa.right(4, 400)
        Qa.waitUntil(40_000) { Qa.current() !is PlayerActivity || Qa.player()?.state == androidx.media3.common.Player.STATE_ENDED }
        Qa.settle(2000)
        Qa.shot("movie_finished", "What shows when a movie ends")
        Qa.step("screen after the end: ${Qa.current()?.javaClass?.simpleName}")
    }

    @Test
    fun brokenMovieShowsMessage() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val movie = Qa.movies().first { it.id == "113" }
        Qa.start(DetailsActivity.intent(Qa.ctx, movie))
        Qa.expect(DetailsActivity::class.java, "details of a movie whose file is missing")
        Qa.shot("broken_movie_details", "Movie whose file is missing on the server")
        Qa.focusId(R.id.play, "Play button")
        Qa.ok()
        Qa.settle(2000)
        val msg = Qa.waitUntil(45_000) { Qa.player()?.errorText != null || Qa.current() !is PlayerActivity }
        Qa.shot("broken_movie", "Trying to play the missing file")
        assertTrue("App still open", Qa.current() != null)
        if (!msg || (Qa.current() is PlayerActivity && Qa.player()?.errorText == null)) {
            Qa.finding("problem", "No clear message when a movie can't play", "Server returns 404 for this movie")
        }
    }

    @Test
    fun movieFavoriteAndScreensaver() {
        Qa.reset()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        val movie = Qa.movies().first { it.id == "105" }
        Qa.start(DetailsActivity.intent(Qa.ctx, movie))
        Qa.expect(DetailsActivity::class.java, "movie details")
        Qa.focusId(R.id.favorite, "Favorite button")
        Qa.ok()
        Qa.settle()
        Qa.shot("movie_favorited", "Movie added to favorites")
        assertTrue("Movie saved as favorite", Prefs(Qa.ctx).favoriteIds(ContentType.MOVIE).contains("105"))

        Qa.focusId(R.id.play, "Play button")
        Qa.ok()
        Qa.expect(PlayerActivity::class.java, "player")
        Qa.expectPlaying("movie")
        Qa.key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        Qa.step("paused for 65 s: the screensaver should appear")
        SystemClock.sleep(65_000)
        Qa.shot("screensaver", "Screensaver after a minute on pause")
        Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY)
        if (!Qa.waitPlaying(6000)) {
            // The first key may only close the screensaver.
            Qa.shot("after_first_play_key", "After the first Play key on the screensaver")
            Qa.step("first Play key only closed the screensaver; pressing Play again")
            Qa.finding("info", "Play on the screensaver first closes it; a second Play resumes", "Design choice; check it feels right")
            Qa.key(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        Qa.expectPlaying("movie after the screensaver")
    }
}
