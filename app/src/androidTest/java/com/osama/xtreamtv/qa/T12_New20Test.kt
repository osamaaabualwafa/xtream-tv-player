package com.osama.xtreamtv.qa

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.osama.xtreamtv.BrowseActivity
import com.osama.xtreamtv.ContentType

import com.osama.xtreamtv.HomeRowsActivity
import com.osama.xtreamtv.Prefs
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New in 2.0.0: the Sort button in Movies/Series, Arrange Home rows, the themed message pill,
 * Ratings' Get started page (no MDBList key here: the key never goes into tests) and the HD logo.
 */
class T12_New20Test : QaTest() {

    private fun openMovies() {
        Qa.reset()
        Qa.start(Intent(Qa.ctx, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, ContentType.MOVIE.name))
        Qa.expect(BrowseActivity::class.java, "Movies")
        assertTrue("Posters appear", Qa.waitUntil(30_000) { Qa.listCount(R.id.items) > 0 })
        Qa.settle(1500)
        Qa.focus("first poster") { Qa.current()?.findViewById<RecyclerView>(R.id.items)?.getChildAt(0) }
    }

    private fun focusedId(): Int = Qa.main { Qa.current()?.currentFocus?.id ?: View.NO_ID }

    @Test
    fun sortButton() {
        openMovies()
        Qa.step("Up from the first row of posters lands on the search box")
        Qa.up()
        Qa.settle(500)
        assertEquals("Up from the posters lands on Search", R.id.search, focusedId())
        Qa.hideKeyboard()
        Qa.step("Left from the search box reaches Sort")
        Qa.left()
        Qa.settle(500)
        assertEquals("Left from Search lands on Sort", R.id.sort_button, focusedId())
        assertEquals("Sort shows the provider's order at first", "Sort", Qa.text(R.id.sort_button))
        Qa.shot("sort_button", "Movies: the Sort button highlighted")
        Qa.step("OK opens the themed list under the button; Down twice picks A to Z")
        Qa.ok()
        Qa.settle(800)
        Qa.shot("sort_list", "Movies: the Sort list")
        Qa.down(2)
        Qa.ok()
        Qa.settle(1200)
        assertEquals("A to Z saved for Movies", 2, Prefs(Qa.ctx).browseSort(ContentType.MOVIE))
        assertEquals("The button says the order", "Sort · A to Z", Qa.text(R.id.sort_button))
        val names = Qa.main {
            val a = Qa.current() as? BrowseActivity ?: return@main emptyList<String>()
            val rv = a.findViewById<RecyclerView>(R.id.items)
            (0 until minOf(rv.childCount, 6)).mapNotNull { i ->
                rv.getChildAt(i).findViewById<android.widget.TextView?>(R.id.title)?.text?.toString()
            }
        }
        Qa.step("first posters after A to Z: $names")
        assertTrue("Posters are in A to Z order", names.size < 2 || names.map { it.lowercase() } == names.map { it.lowercase() }.sorted())
        Qa.shot("sort_az", "Movies sorted A to Z")
    }

    @Test
    fun arrangeHomeRows() {
        Qa.reset()
        val before = Prefs(Qa.ctx).homeRowOrder
        assertEquals("Every Home row is listed", Prefs.HOME_ROWS.toSet(), before.toSet())
        Qa.start(Intent(Qa.ctx, HomeRowsActivity::class.java))
        Qa.expect(HomeRowsActivity::class.java, "Arrange Home")
        Qa.settle(1200)
        Qa.shot("arrange", "Arrange Home rows")
        Qa.step("Right hides the first row")
        Qa.right()
        Qa.settle(600)
        assertTrue("First row hidden", before[0] in Prefs(Qa.ctx).homeRowHidden)
        Qa.right()
        Qa.settle(600)
        assertTrue("First row shown again", before[0] !in Prefs(Qa.ctx).homeRowHidden)
        Qa.step("OK picks the second row up, Down moves it, OK drops it")
        Qa.down()
        Qa.ok()
        Qa.down()
        Qa.ok()
        Qa.settle(800)
        val after = Prefs(Qa.ctx).homeRowOrder
        Qa.step("order before $before, after $after")
        assertEquals("The second row moved down one place", before[1], after[2])
        Qa.shot("arrange_moved", "Arrange Home: a row moved")
        Qa.back()
    }

    @Test
    fun themedMessagePill() {
        openMovies()
        Qa.step("Long-press OK on a poster adds it to favorites, with the themed pill")
        Qa.longOk()
        val shown = Qa.waitUntil(3000, 100) { Qa.main { Qa.current()?.window?.decorView?.findViewWithTag<View>("message_pill") != null } }
        Qa.shot("message_pill", "Movies: the favorites message")
        assertTrue("The themed message pill appears", shown)
        assertTrue("A movie is now a favorite", Prefs(Qa.ctx).favoriteIds(ContentType.MOVIE).isNotEmpty())
        Qa.settle(2500)
        Qa.longOk()
        Qa.settle(800)
        assertTrue("Long-press again removes it", Prefs(Qa.ctx).favoriteIds(ContentType.MOVIE).isEmpty())
    }

    @Test
    fun ratingsGetStarted() {
        Qa.reset()
        Qa.start(Intent(Qa.ctx, SettingsActivity::class.java))
        Qa.expect(SettingsActivity::class.java, "Settings")
        Qa.focusId(R.id.menu_ratings, "Ratings")
        Qa.settle(1000)
        Qa.shot("ratings_get_started", "Settings > Ratings without a key")
        assertTrue("Get started shows without a key", Qa.findText("Get started") != null)
    }

    @Test
    fun hdLogo() {
        val res = Qa.ctx.resources
        val density = res.displayMetrics.density
        val banner = res.getDrawable(R.drawable.banner, null)
        Qa.step("banner ${banner.intrinsicWidth}x${banner.intrinsicHeight} px at density $density")
        assertTrue("The banner is at least the full banner size (320x180 dp)", banner.intrinsicWidth >= (320 * density).toInt() && banner.intrinsicHeight >= (180 * density).toInt())
        val bmp = (banner as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bmp != null) assertTrue("The banner picture has room to stay sharp (${bmp.width} px wide)", bmp.width >= (2 * 320 * density).toInt())
        val icon = res.getDrawable(R.mipmap.ic_launcher, null)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertTrue("The launcher icon is an adaptive icon", icon is android.graphics.drawable.AdaptiveIconDrawable)
        }
    }
}
