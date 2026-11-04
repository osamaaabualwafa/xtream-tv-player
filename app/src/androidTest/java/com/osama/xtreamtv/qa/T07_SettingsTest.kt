package com.osama.xtreamtv.qa

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.uiautomator.By
import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every settings section, every option, every value: open it, pick each choice with the
 * remote, check the row shows it, then put the original value back.
 */
class T07_SettingsTest : QaTest() {

    private fun openSettings() {
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.settings_button, "Settings button")
        Qa.ok()
        Qa.expect(SettingsActivity::class.java, "Settings")
    }

    private fun openSection(menuId: Int, label: String) {
        if (Qa.current() !is SettingsActivity) Qa.expect(SettingsActivity::class.java, "Settings")
        Qa.focusId(menuId, label)
        Qa.settle(800)
        Qa.shot("section_$label", "Settings: $label")
    }

    /** Titles of the option rows in the shown section. */
    private fun optionRows(): List<String> = Qa.main {
        val body = Qa.current()?.findViewById<ViewGroup>(R.id.section_body) ?: return@main emptyList()
        val out = ArrayList<String>()
        Qa.walk(body) { v ->
            val title = v.findViewById<TextView>(R.id.title)
            if (v.isClickable && v !== title && title != null && v.findViewById<View>(R.id.subtitle) != null && v.parent === body) {
                out.add(title.text.toString())
            }
        }
        out
    }

    private fun rowValue(title: String): String? = Qa.main {
        val body = Qa.current()?.findViewById<ViewGroup>(R.id.section_body) ?: return@main null
        var value: String? = null
        for (i in 0 until body.childCount) {
            val row = body.getChildAt(i)
            if (row.findViewById<TextView>(R.id.title)?.text?.toString() == title) value = row.findViewById<TextView>(R.id.subtitle)?.text?.toString()
        }
        value
    }

    private fun openOption(title: String) {
        if (Qa.current() !is SettingsActivity) Qa.waitUntil(5000) { Qa.current() is SettingsActivity }
        Qa.focusText(title, 60)
        Qa.ok()
        Qa.dialogShowing("Cancel")
    }

    private fun dialogChoices(): List<String> =
        Qa.device.findObjects(By.clazz("android.widget.CheckedTextView")).mapNotNull { it.text }

    /** Tries every value of every option in the shown section. */
    private fun crawl(section: String, skip: Set<String> = emptySet()) {
        val rows = optionRows()
        Qa.step("$section has ${rows.size} options: $rows")
        if (rows.isEmpty()) Qa.finding("warning", "$section: no options found to test")
        for (title in rows) {
            if (title in skip) continue
            val original = rowValue(title) ?: continue
            openOption(title)
            val choices = dialogChoices()
            Qa.shot("option_${title.take(20)}", "$section › $title (current: $original)")
            Qa.back()
            if (choices.isEmpty()) {
                Qa.finding("warning", "$section › $title: the choice list didn't open")
                continue
            }
            for (choice in choices) {
                openOption(title)
                Qa.dialogPick(choice)
                Qa.waitUntil(6000) { Qa.current() is SettingsActivity && rowValue(title) != null }
                Qa.settle(400)
                val shown = rowValue(title)
                if (shown != choice) Qa.finding("problem", "$section › $title: picked \"$choice\" but the row shows \"$shown\"")
            }
            Qa.step("$section › $title: tried ${choices.size} values")
            // Back to how it was.
            if (rowValue(title) != original) {
                openOption(title)
                Qa.dialogPick(original)
                Qa.waitUntil(6000) { rowValue(title) == original }
            }
        }
    }

    @Test
    fun homeAndBrowseLayouts() {
        Qa.reset()
        openSettings()
        openSection(R.id.menu_home, "Home layout")
        for ((i, name) in listOf("Hero", "Side menu", "Big tiles", "Top tabs").withIndex()) {
            Qa.focusText(name)
            Qa.ok()
            Qa.settle(500)
            assertEquals("Home layout saved", i, Qa.prefs().getInt("home_style", -1))
        }
        Qa.shot("home_layout_chosen", "Home layout: last one chosen")
        openSection(R.id.menu_browse, "Movies and series")
        for ((i, name) in listOf("Sidebar", "Folding", "Chips", "Preview").withIndex()) {
            Qa.focusText(name)
            Qa.ok()
            Qa.settle(500)
            assertEquals("Browse layout saved", i, Qa.prefs().getInt("browse_style", -1))
        }
        Qa.shot("browse_layout_chosen", "Movies and series layout: last one chosen")
        Qa.step("Home rebuilds with the new layout after Back")
        Qa.back()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.settle(1500)
        Qa.shot("home_after_layout_change", "Home after choosing Top tabs")
    }

    @Test
    fun playerOptions() {
        Qa.reset()
        openSettings()
        openSection(R.id.menu_player, "Player")
        crawl("Player")
    }

    @Test
    fun lookOptions() {
        Qa.reset()
        openSettings()
        openSection(R.id.menu_color, "Look")
        crawl("Look")
        Qa.step("colour dots and palettes (Default theme)")
        for (name in listOf("Purple", "Teal", "Orange", "Pink", "Green", "Red", "Gold", "Blue")) {
            val dot = Qa.findText(name)
            if (dot != null) {
                Qa.focusText(name)
                Qa.ok()
                Qa.settle(1200)
            }
        }
        Qa.shot("look_colours", "Look section after trying the colours")
    }

    @Test
    fun playlistsConnectionAbout() {
        Qa.reset(users = listOf("demo", "second"))
        openSettings()
        openSection(R.id.menu_avatars, "Playlists")
        crawl("Playlists")
        openSection(R.id.menu_connection, "Connection check")
        Qa.right()
        Qa.ok()
        Qa.waitUntil(40_000) { Qa.findText("Download speed") != null }
        Qa.settle(1000)
        Qa.shot("connection_result", "Connection check result")
        if (Qa.findText("Download speed") == null) Qa.finding("warning", "Connection check didn't finish within 40 s")
        openSection(R.id.menu_about, "About")
    }
}
