package com.osama.xtreamtv.qa

import com.osama.xtreamtv.HomeActivity
import com.osama.xtreamtv.LoginActivity
import com.osama.xtreamtv.PlaylistsActivity
import com.osama.xtreamtv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** First start, every kind of login answer, and managing several playlists. */
class T02_LoginAndPlaylistsTest : QaTest() {

    private fun pressLogin() {
        Qa.focusId(R.id.login, "Log in button")
        Qa.ok()
    }

    private fun expectError(contains: String, what: String) {
        val ok = Qa.waitUntil(30_000) { Qa.text(R.id.error)?.contains(contains, ignoreCase = true) == true }
        Qa.shot("login_error_${contains.take(12)}", what)
        assertTrue("Login screen should say \"$contains\" for $what, it says: ${Qa.text(R.id.error)}", ok)
        assertTrue("Still on the login screen", Qa.current() is LoginActivity)
    }

    @Test
    fun firstStartShowsAddPlaylist() {
        Qa.reset(users = emptyList())
        Qa.launchApp()
        Qa.expect(LoginActivity::class.java, "Add playlist screen")
        Qa.shot("first_start", "First start: no playlist yet")
        assertTrue("Name field is highlighted first", Qa.focusedDescription().startsWith("name"))
        Qa.back()
        Qa.expect(PlaylistsActivity::class.java, "playlist chooser after Back")
        Qa.shot("chooser_empty", "Back from Add playlist: Restore is offered")
    }

    @Test
    fun loginMistakesShowClearMessages() {
        Qa.reset(users = emptyList())
        Qa.launchApp()
        Qa.expect(LoginActivity::class.java, "Add playlist screen")

        Qa.step("press Log in with everything empty")
        pressLogin()
        expectError("server address", "empty form")

        Qa.type(R.id.server, Qa.server)
        pressLogin()
        expectError("username and password", "no username or password")

        Qa.type(R.id.username, "demo")
        Qa.type(R.id.password, "wrongpass")
        pressLogin()
        expectError("Wrong username or password", "wrong password")

        Qa.type(R.id.username, "expired")
        Qa.type(R.id.password, "expired")
        pressLogin()
        expectError("Expired", "expired subscription")

        Qa.type(R.id.username, "banned")
        Qa.type(R.id.password, "banned")
        pressLogin()
        expectError("Banned", "banned account")

        Qa.step("server that does not answer")
        Qa.type(R.id.server, "http://10.0.2.2:9")
        Qa.type(R.id.username, "demo")
        Qa.type(R.id.password, "demo")
        val t = System.currentTimeMillis()
        pressLogin()
        expectError("Login failed", "server not reachable")
        Qa.metric("unreachable_server_error_ms", System.currentTimeMillis() - t)

        Qa.step("Show / Hide password")
        Qa.focusId(R.id.show_password, "Show password")
        Qa.ok()
        Qa.shot("password_shown", "Password shown in clear text")
    }

    @Test
    fun loginWithHostOnly() {
        Qa.reset(users = emptyList())
        Qa.launchApp()
        Qa.expect(LoginActivity::class.java, "Add playlist screen")
        Qa.type(R.id.name, "My TV")
        Qa.type(R.id.server, Qa.server.removePrefix("http://") + "/")
        Qa.type(R.id.username, "demo")
        Qa.type(R.id.password, "demo")
        Qa.shot("login_filled", "Login form filled in (address without http://)")
        pressLogin()
        Qa.expect(HomeActivity::class.java, "Home after login")
        Qa.shot("home_after_login", "Home right after the first login")
        assertEquals(1, Qa.prefs().getString("playlists", "[]")!!.let { org.json.JSONArray(it).length() })
    }

    @Test
    fun loginWithPastedM3uLink() {
        Qa.reset(users = emptyList())
        Qa.launchApp()
        Qa.expect(LoginActivity::class.java, "Add playlist screen")
        Qa.type(R.id.server, "${Qa.server}/get.php?username=demo&password=demo&type=m3u_plus&output=ts")
        pressLogin()
        Qa.expect(HomeActivity::class.java, "Home after pasting an M3U link")
    }

    @Test
    fun expiringAccountWarns() {
        Qa.reset(users = listOf("expiring"))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.waitUntil(8000) { Qa.visible(R.id.expiry_warning) || Qa.visible(R.id.expiry_chip) }
        Qa.shot("expiry_warning", "Account that ends in 2 days")
        if (!Qa.visible(R.id.expiry_warning) && !Qa.visible(R.id.expiry_chip)) {
            Qa.finding("warning", "No expiry warning for an account that ends in 2 days")
        }
    }

    @Test
    fun severalPlaylists() {
        Qa.reset(users = listOf("demo"))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home (only one playlist opens straight away)")

        Qa.step("open the playlist chooser from Home")
        Qa.focusId(R.id.playlist_chip, "playlist chip")
        Qa.ok()
        Qa.expect(PlaylistsActivity::class.java, "playlist chooser")
        Qa.shot("chooser_one", "Chooser with one playlist")

        Qa.step("add a second playlist")
        Qa.focusText("Add playlist")
        Qa.ok()
        Qa.expect(LoginActivity::class.java, "Add playlist")
        Qa.type(R.id.name, "Second account")
        Qa.type(R.id.server, Qa.server)
        Qa.type(R.id.username, "second")
        Qa.type(R.id.password, "second")
        pressLogin()
        Qa.expect(HomeActivity::class.java, "Home of the second playlist")
        Qa.shot("home_second", "Home of the second playlist")

        Qa.step("switch back to the first playlist")
        Qa.focusId(R.id.playlist_chip, "playlist chip")
        Qa.ok()
        Qa.expect(PlaylistsActivity::class.java, "playlist chooser")
        Qa.waitUntil(8000) { Qa.findText("Active") != null || Qa.findText("until") != null }
        Qa.shot("chooser_two", "Chooser with two playlists and their account status")
        Qa.focusText("Demo TV")
        Qa.ok()
        Qa.expect(HomeActivity::class.java, "Home of the first playlist")

        Qa.step("playlist options: long-press OK")
        Qa.focusId(R.id.playlist_chip, "playlist chip")
        Qa.ok()
        Qa.expect(PlaylistsActivity::class.java, "playlist chooser")
        Qa.focusText("Second account")
        Qa.longOk()
        Qa.dialogShowing("Delete")
        Qa.shot("playlist_options", "Options for a playlist")
        Qa.dialogPick("Change avatar")
        Qa.settle(800)
        Qa.shot("avatar_picker", "Avatar picker")
        Qa.back()

        Qa.step("make it open at start")
        Qa.focusText("Second account")
        Qa.longOk()
        if (!Qa.dialogShowing("Delete", 4000)) {
            Qa.step("options didn't open on the first long-press; trying once more")
            Qa.focusText("Second account")
            Qa.longOk()
        }
        Qa.dialogPick("Open at start")
        Qa.settle()
        Qa.shot("open_at_start", "Second playlist marked to open at start")
        assertEquals("qa_second", Qa.prefs().getString("default_playlist", null))

        Qa.step("delete it")
        Qa.focusText("Second account")
        Qa.longOk()
        Qa.dialogPick("Delete")
        Qa.dialogShowing("Cancel")
        Qa.shot("delete_confirm", "Delete confirmation")
        Qa.dialogPick("Delete")
        Qa.settle()
        Qa.shot("after_delete", "Chooser after deleting")
        assertEquals(1, org.json.JSONArray(Qa.prefs().getString("playlists", "[]")).length())

        Qa.step("edit the remaining playlist")
        Qa.focusText("Demo TV")
        Qa.longOk()
        Qa.dialogPick("Edit")
        Qa.expect(LoginActivity::class.java, "Edit playlist")
        Qa.shot("edit_playlist", "Edit playlist (fields filled in)")
        assertEquals("demo", Qa.text(R.id.username))
        Qa.type(R.id.name, "Renamed playlist")
        pressLogin()
        Qa.expect(HomeActivity::class.java, "Home after saving")
        assertTrue(Qa.prefs().getString("playlists", "")!!.contains("Renamed playlist"))
    }

    @Test
    fun logoutAndReopen() {
        Qa.reset(users = listOf("demo"))
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home")
        Qa.focusId(R.id.logout, "Log out / switch")
        Qa.ok()
        Qa.expect(PlaylistsActivity::class.java, "playlist chooser after logging out")
        Qa.shot("after_logout", "After logging out")
        Qa.step("open the app again from the launcher: login is remembered")
        Qa.finishAll()
        Qa.launchApp()
        Qa.expect(HomeActivity::class.java, "Home without typing the login again")
    }
}
