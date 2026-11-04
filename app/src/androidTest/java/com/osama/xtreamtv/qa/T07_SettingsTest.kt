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
        // With two playlists and none set to open at start, the app asks which one first.
        if (Qa.waitUntil(8000) { Qa.current() is HomeActivity || Qa.current() is com.osama.xtreamtv.PlaylistsActivity } &&
            Qa.current() is com.osama.xtreamtv.PlaylistsActivity) {
            Qa.focusText("Demo TV")
            Qa.ok()
        }
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

    /** Option rows (title + value) in the shown section, also inside the Glass cards. */
    private fun rowViews(): List<View> {
        val body = Qa.current()?.findViewById<ViewGroup>(R.id.section_body) ?: return emptyList()
        val found = ArrayList<View>()
        Qa.walk(body) { v ->
            if (v is ViewGroup && v.isClickable && v.findViewById<TextView>(R.id.title) != null && v.findViewById<View>(R.id.subtitle) != null) found.add(v)
        }
        // Keep the innermost rows only.
        return found.filter { row -> found.none { other -> other !== row && Qa.isAncestor(row, other) } }
    }

    private fun optionRows(): List<String> = Qa.main { rowViews().map { it.findViewById<TextView>(R.id.title).text.toString() } }

    /** The value a row shows; for Glass on/off switches (no value text) "switch". */
    private fun rowValue(title: String): String? = Qa.main {
        val row = rowViews().firstOrNull { it.findViewById<TextView>(R.id.title)?.text?.toString() == title } ?: return@main null
        val sub = row.findViewById<TextView>(R.id.subtitle)
        // Glass shows the value as a small "value ▾" button.
        if (sub.visibility != View.VISIBLE) "switch" else sub.text?.toString()?.removeSuffix("▾")?.trim()
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
            if (original == "switch") {
                // Glass on/off switch: OK flips it at once; flip twice to keep the setting.
                Qa.focusText(title, 60)
                Qa.ok()
                Qa.waitUntil(5000) { Qa.current() is SettingsActivity }
                Qa.settle(600)
                Qa.shot("switch_${title.take(20)}", "$section › $title flipped")
                Qa.focusText(title, 60)
                Qa.ok()
                Qa.waitUntil(5000) { Qa.current() is SettingsActivity }
                Qa.step("$section › $title: switch flipped and back")
                continue
            }
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
        for ((i, name) in com.osama.xtreamtv.Appearance.HOME_NAMES.withIndex()) {
            Qa.focusText(name)
            Qa.ok()
            Qa.settle(500)
            assertEquals("Home layout saved", i, Qa.prefs().getInt("home_style", -1))
        }
        Qa.shot("home_layout_chosen", "Home layout: last one (Dock) chosen")
        crawl("Home layout")
        openSection(R.id.menu_browse, "Movies and series")
        for ((i, name) in listOf("Sidebar", "Folding", "Chips", "Preview").withIndex()) {
            Qa.focusText(name)
            Qa.ok()
            Qa.settle(500)
            assertEquals("Browse layout saved", i, Qa.prefs().getInt("browse_style", -1))
        }
        Qa.shot("browse_layout_chosen", "Movies and series layout: last one chosen")
        Qa.step("Home rebuilds with the new layout after Back")
        Qa.backTo(HomeActivity::class.java, "Home")
        Qa.settle(1500)
        Qa.shot("home_after_layout_change", "Home after choosing the last layout (Preview browse, Dock home)")
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

    /** Buttons and swatches identified by their tag (Glass settings). */
    private fun pickTagged(tagName: String, label: String) {
        Qa.focus(label, 60) { Qa.current()?.findViewById<ViewGroup>(R.id.section_body)?.findViewWithTag<View>(tagName) }
        Qa.ok()
        Qa.waitUntil(6000) { Qa.current() is SettingsActivity }
        Qa.settle(800)
    }

    @Test
    fun glassLookOptions() {
        Qa.reset(settings = mapOf("theme" to com.osama.xtreamtv.Appearance.THEME_GLASS))
        openSettings()
        openSection(R.id.menu_color, "Look (Glass)")
        Qa.step("every background")
        for ((i, name) in com.osama.xtreamtv.Appearance.SCENE_NAMES.withIndex()) {
            pickTagged("scene$i", "background $name")
            assertEquals("Background $name saved", i, Qa.prefs().getInt("glass_scene", 0))
            Qa.shot("glass_scene_$name", "Look › Background: $name")
        }
        pickTagged("scene0", "background Aurora")
        Qa.step("every glass style")
        for ((i, name) in com.osama.xtreamtv.Appearance.GLASS_STYLE_NAMES.withIndex()) {
            pickTagged("glass_style$i", "glass style $name")
            assertEquals("Glass style $name saved", i, Qa.prefs().getInt("glass_style", 0))
            Qa.shot("glass_style_$name", "Look › Glass style: $name")
        }
        Qa.step("every highlight style")
        for ((i, name) in com.osama.xtreamtv.Appearance.FOCUS_NAMES.withIndex()) {
            pickTagged("focus_style$i", "highlight $name")
            assertEquals("Highlight style $name saved", i, Qa.prefs().getInt("focus_style", 0))
            Qa.shot("glass_focus_${name.replace(' ', '_')}", "Look › Selected item: $name")
        }
        pickTagged("glass_style0", "glass style Clear")
        pickTagged("focus_style0", "highlight Accent ring")
        crawl("Look (Glass)", skip = setOf("Theme"))
        Qa.step("Theme row: Default, Cinema, Glass")
        openOption("Theme")
        val themes = dialogChoices()
        Qa.shot("theme_choices", "Theme choices")
        Qa.back()
        assertEquals("Themes offered", listOf("Default", "Cinema", "Glass"), themes)
    }

    @Test
    fun glassPlayerOptions() {
        Qa.reset(settings = mapOf("theme" to com.osama.xtreamtv.Appearance.THEME_GLASS))
        openSettings()
        openSection(R.id.menu_player, "Player (Glass)")
        for ((i, name) in listOf("Small pop-up", "Side panel", "Cards").withIndex()) {
            pickTagged("player_menu$i", "player menu $name")
            assertEquals("Player menu $name saved", i, Qa.prefs().getInt("player_menu_style", 0))
        }
        Qa.shot("glass_player_menu_cards", "Player › Player menu: Cards")
        pickTagged("player_menu0", "player menu Small pop-up")
        crawl("Player (Glass)")
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
