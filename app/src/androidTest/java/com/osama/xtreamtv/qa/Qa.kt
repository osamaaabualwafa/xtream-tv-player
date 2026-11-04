package com.osama.xtreamtv.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Debug
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.services.storage.TestStorage
import androidx.test.uiautomator.UiDevice
import com.osama.xtreamtv.BuildConfig
import com.osama.xtreamtv.ContentType
import com.osama.xtreamtv.PlayerActivity
import com.osama.xtreamtv.PlaylistsActivity
import com.osama.xtreamtv.R
import com.osama.xtreamtv.Session
import com.osama.xtreamtv.StreamItem
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared helpers for the weekly QA run: app reset, remote-control keys, screenshots with an
 * automatic layout check, player checks and a findings log the morning review reads.
 *
 * Everything a test records goes to TestStorage (qa/...), which Gradle copies off the emulator.
 */
object Qa {
    const val PKG = "com.osama.xtreamtv"
    val inst: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = inst.targetContext
    val device: UiDevice get() = UiDevice.getInstance(inst)
    val server: String
        get() = InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:8080"

    private val storage by lazy { TestStorage() }

    // ---------------------------------------------------------------- per-test record

    var testName = "test"
        private set
    private var startedAt = 0L
    private var startedWall = 0L
    private var shotIndex = 0
    private val steps = JSONArray()
    private val findings = JSONArray()
    private val shots = JSONArray()
    private val metrics = JSONObject()
    var lastShot: String? = null
        private set

    fun begin(name: String) {
        testName = name
        startedAt = SystemClock.elapsedRealtime()
        startedWall = System.currentTimeMillis()
        shotIndex = 0
        lastShot = null
        listOf(steps, findings, shots).forEach { a -> while (a.length() > 0) a.remove(0) }
        metrics.keys().asSequence().toList().forEach { metrics.remove(it) }
        save("running", null)
    }

    fun end(result: String, error: Throwable?) = save(result, error)

    private fun save(result: String, error: Throwable?) {
        val o = JSONObject()
            .put("test", testName)
            .put("result", result)
            .put("started", startedWall)
            .put("seconds", (SystemClock.elapsedRealtime() - startedAt) / 1000.0)
            .put("steps", steps)
            .put("findings", findings)
            .put("shots", shots)
            .put("metrics", metrics)
        if (error != null) o.put("error", (error.message ?: error.javaClass.simpleName).take(2000))
        write("qa/tests/${safe(testName)}.json", o.toString(2).toByteArray())
    }

    fun step(text: String) {
        steps.put(JSONObject().put("t", (SystemClock.elapsedRealtime() - startedAt) / 1000.0).put("text", text))
        android.util.Log.i("QA", "[$testName] $text")
    }

    /**
     * Something a person should look at. [severity]: "problem" (very likely a bug), "warning"
     * (may be a bug or a design choice), "info" (measurements, notes).
     */
    fun finding(severity: String, title: String, detail: String = "", shot: String? = lastShot) {
        findings.put(JSONObject().put("severity", severity).put("title", title).put("detail", detail).put("shot", shot ?: ""))
        android.util.Log.w("QA", "[$testName] $severity: $title  $detail")
        save("running", null)
    }

    fun metric(name: String, value: Number) {
        metrics.put(name, value)
        step("metric $name = $value")
    }

    private fun write(path: String, bytes: ByteArray) {
        try {
            storage.openOutputFile(path).use { it.write(bytes) }
        } catch (e: Exception) {
            android.util.Log.e("QA", "Can't write $path", e)
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]+"), "_").take(90)

    // ---------------------------------------------------------------- app state

    /** Fresh app: no playlists or settings, then [users] saved as playlists ([active] opened). */
    fun reset(users: List<String> = listOf("demo"), settings: Map<String, Any> = emptyMap(), active: String? = users.firstOrNull()) {
        finishAll()
        Session.clear()
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { f ->
            ctx.getSharedPreferences(f.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit()
        }
        ctx.cacheDir.listFiles()?.forEach { if (it.name != "image_cache") it.deleteRecursively() }
        val e = ctx.getSharedPreferences("xtream", Context.MODE_PRIVATE).edit()
        if (users.isNotEmpty()) {
            val arr = JSONArray()
            users.forEach { u ->
                arr.put(JSONObject().put("id", "qa_$u").put("name", u.replaceFirstChar { it.uppercase() } + " TV")
                    .put("base", server).put("user", u).put("pass", u))
            }
            e.putString("playlists", arr.toString())
        }
        if (active != null) e.putString("active", "qa_$active")
        e.putInt("settings_pinned_for", BuildConfig.VERSION_CODE)
        for ((k, v) in settings) {
            when (v) {
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.commit()
        step("reset app: playlists=$users settings=$settings")
    }

    fun prefs() = ctx.getSharedPreferences("xtream", Context.MODE_PRIVATE)

    fun finishAll() {
        repeat(3) {
            main {
                val reg = ActivityLifecycleMonitorRegistry.getInstance()
                for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)) {
                    reg.getActivitiesInStage(stage).forEach { it.finish() }
                }
            }
            SystemClock.sleep(400)
        }
    }

    /** Opens the app the way the TV launcher does. */
    fun launchApp() {
        step("open the app from the TV launcher")
        ctx.startActivity(
            Intent(ctx, PlaylistsActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    fun start(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The live channel list the player zaps through (normally set by the Live TV screen). */
    fun loadZapList(): List<StreamItem> = runBlocking {
        val api = Session.api(ctx) ?: error("No playlist")
        val list = api.streams(ContentType.LIVE, null)
        Session.zapList = list
        Session.zapTitle = "All channels"
        list
    }

    fun movies(): List<StreamItem> = runBlocking { Session.api(ctx)!!.streams(ContentType.MOVIE, null) }
    fun series(): List<StreamItem> = runBlocking { Session.api(ctx)!!.streams(ContentType.SERIES, null) }

    // ---------------------------------------------------------------- threads and waiting

    @Suppress("UNCHECKED_CAST")
    fun <T> main(block: () -> T): T {
        // Already on the main thread (helpers calling helpers): just run it.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Any? = null
        var error: Throwable? = null
        inst.runOnMainSync {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            }
        }
        error?.let { throw it }
        return result as T
    }

    fun current(): Activity? = main {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }

    fun waitUntil(timeoutMs: Long, pollMs: Long = 250, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (try { condition() } catch (e: Exception) { false }) return true
            SystemClock.sleep(pollMs)
        }
        return try { condition() } catch (e: Exception) { false }
    }

    /** Waits for a screen; fails the test (with a screenshot) if it never appears. */
    fun <T : Activity> expect(cls: Class<T>, what: String, timeoutMs: Long = 30_000): T {
        val start = SystemClock.elapsedRealtime()
        val ok = waitUntil(timeoutMs) { cls.isInstance(current()) }
        if (!ok) {
            shot("expected_${cls.simpleName}")
            fail("Expected $what (${cls.simpleName}) but the screen is ${current()?.javaClass?.simpleName ?: "nothing (app closed?)"}")
        }
        step("on $what after ${(SystemClock.elapsedRealtime() - start)} ms")
        settle()
        @Suppress("UNCHECKED_CAST")
        return current() as T
    }

    fun settle(ms: Long = 600) {
        device.waitForIdle(3000)
        SystemClock.sleep(ms)
    }

    // ---------------------------------------------------------------- views

    fun <T : View> view(id: Int): T? = main {
        current()?.findViewById<T>(id)?.takeIf { it.isShown }
    }

    fun visible(id: Int): Boolean = view<View>(id) != null

    fun text(id: Int): String? = main { (current()?.findViewById<View>(id) as? TextView)?.takeIf { it.isShown }?.text?.toString() }

    fun waitVisible(id: Int, timeoutMs: Long = 20_000): Boolean = waitUntil(timeoutMs) { visible(id) }

    fun listCount(id: Int): Int = main { current()?.findViewById<RecyclerView>(id)?.adapter?.itemCount ?: -1 }

    /** First visible TextView (in the screen) whose text contains [text]. */
    fun findText(text: String, root: View? = null): View? = main {
        val r = root ?: current()?.window?.decorView ?: return@main null
        var found: View? = null
        walk(r) { v -> if (found == null && v is TextView && v.isShown && v.text?.toString()?.contains(text, ignoreCase = true) == true) found = v }
        found
    }

    fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
    }

    fun focusedDescription(): String = main {
        val f = current()?.currentFocus ?: return@main "nothing"
        val name = try { f.resources.getResourceEntryName(f.id) } catch (e: Exception) { f.javaClass.simpleName }
        var label = (f as? TextView)?.text?.toString()
        if (label.isNullOrBlank() && f is ViewGroup) walk(f) { v -> if (label.isNullOrBlank() && v is TextView && v.text.isNotBlank()) label = v.text.toString() }
        "$name${label?.let { " \"${it.take(40)}\"" } ?: ""}"
    }

    // ---------------------------------------------------------------- remote control

    fun key(code: Int, times: Int = 1, gapMs: Long = 300) {
        repeat(times) {
            device.pressKeyCode(code)
            SystemClock.sleep(gapMs)
        }
    }

    fun up(times: Int = 1, gap: Long = 300) = key(KeyEvent.KEYCODE_DPAD_UP, times, gap)
    fun down(times: Int = 1, gap: Long = 300) = key(KeyEvent.KEYCODE_DPAD_DOWN, times, gap)
    fun left(times: Int = 1, gap: Long = 300) = key(KeyEvent.KEYCODE_DPAD_LEFT, times, gap)
    fun right(times: Int = 1, gap: Long = 300) = key(KeyEvent.KEYCODE_DPAD_RIGHT, times, gap)
    fun ok() = key(KeyEvent.KEYCODE_DPAD_CENTER, 1, 500)
    fun back() = key(KeyEvent.KEYCODE_BACK, 1, 600)

    /** Presses Back until [cls] shows (at most [max] times); fails if it never does. */
    fun <T : Activity> backTo(cls: Class<T>, what: String, max: Int = 4) {
        repeat(max) {
            if (cls.isInstance(current())) return
            back()
            waitUntil(2500) { cls.isInstance(current()) }
        }
        expect(cls, what, 3000)
    }

    /** Back out of the player (the first Back may only close the controls). */
    fun leavePlayer() {
        repeat(3) {
            if (current() !is PlayerActivity) return
            back()
            waitUntil(3000) { current() !is PlayerActivity }
        }
    }

    /** Holds OK like a person long-pressing the remote. */
    fun longOk(code: Int = KeyEvent.KEYCODE_DPAD_CENTER) {
        val t = SystemClock.uptimeMillis()
        val ua = inst.uiAutomation
        ua.injectInputEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
        SystemClock.sleep(1300)
        val t2 = SystemClock.uptimeMillis()
        ua.injectInputEvent(KeyEvent(t, t2, KeyEvent.ACTION_DOWN, code, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD), true)
        ua.injectInputEvent(KeyEvent(t, t2 + 50, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
        SystemClock.sleep(700)
    }

    /**
     * Moves the highlight to [target] with the arrow keys only, like a person with a remote.
     * If the arrows can't get there, that is recorded (a remote-navigation problem) and the
     * highlight is placed there directly so the journey can go on.
     */
    fun focus(label: String, maxPresses: Int = 40, target: () -> View?): Boolean {
        // After a dialog or the keyboard closes, the screen takes the remote back a moment later.
        waitUntil(3000) { main { current()?.hasWindowFocus() == true } }
        hideKeyboard()
        val blocked = HashSet<Int>()
        var lastFocusId = 0
        var presses = 0
        var missing = 0
        var keyboardNote = ""
        while (presses < maxPresses) {
            val st = main {
                val a = current() ?: return@main null
                val t = target() ?: return@main null
                val f = a.currentFocus
                val done = a.hasWindowFocus() && (t.isFocused || t.hasFocus() || (f != null && isAncestor(f, t)))
                Triple(rectOf(t), f?.let { rectOf(it) }, done)
            }
            if (st == null) {
                missing++
                if (missing > 30) {
                    shot("missing_${label.take(20)}")
                    fail("\"$label\" is not on the screen (${current()?.javaClass?.simpleName})")
                }
                SystemClock.sleep(300)
                continue
            }
            val (tr, fr, done) = st
            if (done) {
                step("remote: reached $label ($presses presses)")
                return true
            }
            if (fr == null) {
                // Nothing highlighted yet: any arrow usually brings the highlight in.
                down()
                presses++
                continue
            }
            val focusId = focusIdentity()
            // A key that moved the highlight (to another item, even if a centred list kept it in place) clears the dead ends.
            if (focusId != lastFocusId) blocked.clear()
            val dx = tr.centerX() - fr.centerX()
            val dy = tr.centerY() - fr.centerY()
            val horizontal = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            val vertical = if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            val sameRow = tr.top < fr.bottom && fr.top < tr.bottom
            val sameColumn = tr.left < fr.right && fr.left < tr.right
            val order = ArrayList<Int>()
            if (sameRow) order.add(horizontal) else if (sameColumn) order.add(vertical)
            if (Math.abs(dx) >= Math.abs(dy)) {
                if (Math.abs(dx) > 4) order.add(horizontal)
                if (Math.abs(dy) > 4) order.add(vertical)
            } else {
                if (Math.abs(dy) > 4) order.add(vertical)
                if (Math.abs(dx) > 4) order.add(horizontal)
            }
            val keyCode = order.distinct().firstOrNull { it !in blocked }
            if (keyCode == null) break
            lastFocusId = focusId
            key(keyCode, 1, 250)
            presses++
            settle(150)
            var afterId = focusIdentity()
            var after = main { current()?.currentFocus?.let { rectOf(it) } }
            if (afterId == focusId && after == fr && main { current()?.currentFocus is EditText }) {
                // In a text field the on-screen keyboard can take the first arrow press: settle and try once more.
                SystemClock.sleep(700)
                key(keyCode, 1, 250)
                settle(150)
                afterId = focusIdentity()
                after = main { current()?.currentFocus?.let { rectOf(it) } }
                if (afterId == focusId && after == fr) keyboardNote = keyboardState()
            }
            if (afterId == focusId && after == fr) blocked.add(keyCode)
        }
        finding(
            "warning", "Remote can't reach \"$label\" with the arrow keys",
            "Tried $presses presses from ${focusedDescription()}. The test moved the highlight there directly to continue.$keyboardNote",
        )
        shot("cant_reach_${label.take(20)}")
        main { target()?.requestFocus() }
        SystemClock.sleep(300)
        return false
    }

    /** Which view has the highlight (changes when the highlight moves, even inside a centred list). */
    private fun focusIdentity(): Int = main {
        val f = current()?.currentFocus ?: return@main 0
        System.identityHashCode(f) * 31 + ((f as? TextView)?.text?.hashCode() ?: 0)
    }

    /** What the emulator's keyboard is doing (for remote-navigation findings in text fields). */
    private fun keyboardState(): String = try {
        val out = inst.uiAutomation.executeShellCommand("dumpsys input_method").let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val shown = Regex("mInputShown=(\\w+)").find(out)?.groupValues?.get(1) ?: "?"
        val ime = Regex("mCurMethodId=(\\S+)").find(out)?.groupValues?.get(1) ?: "?"
        " Keyboard on screen: $shown (keyboard app: $ime)."
    } catch (e: Exception) {
        ""
    }

    /** Closes the on-screen keyboard (the tests type with setText; the remote then moves between fields). */
    fun hideKeyboard() {
        main {
            val a = current() ?: return@main
            val f = a.currentFocus ?: return@main
            val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(f.windowToken, 0)
        }
        SystemClock.sleep(300)
    }

    fun focusId(id: Int, label: String, maxPresses: Int = 40) = focus(label, maxPresses) { current()?.findViewById<View>(id)?.takeIf { it.isShown } }

    fun focusText(text: String, maxPresses: Int = 40) = focus(text, maxPresses) { findTextOnMain(text) }

    private fun findTextOnMain(text: String): View? {
        val r = current()?.window?.decorView ?: return null
        var exact: View? = null
        var partial: View? = null
        walk(r) { v ->
            if (v is TextView && v !is EditText && v.isShown) {
                val t = v.text?.toString()?.trim() ?: ""
                if (exact == null && t.equals(text, ignoreCase = true)) exact = v
                if (partial == null && t.contains(text, ignoreCase = true)) partial = v
            }
        }
        val hit = exact ?: partial ?: return null
        // The clickable row around the text is what takes the highlight.
        var p: View? = hit
        while (p != null && !p.isFocusable) p = p.parent as? View
        return p ?: hit
    }

    fun rectOf(v: View): Rect {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Is [a] an ancestor of (or the same as) [v]? */
    fun isAncestor(a: View, v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === a) return true
            p = p.parent as? View
        }
        return false
    }

    /** Types into a text field the way the TV keyboard would (sets the text). */
    fun type(id: Int, value: String) {
        main {
            val e = current()?.findViewById<EditText>(id) ?: error("No text field ${ctx.resources.getResourceEntryName(id)}")
            e.setText(value)
            e.setSelection(e.text.length)
        }
        SystemClock.sleep(300)
    }

    // ---------------------------------------------------------------- dialogs (own window)

    fun dialogShowing(text: String, timeoutMs: Long = 8000): Boolean =
        device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(text)), timeoutMs) == true

    private fun focusedText(): String? {
        val any = java.util.regex.Pattern.compile(".+")
        val f = device.findObject(androidx.test.uiautomator.By.focused(true)) ?: return null
        // A focused list (dialog choices): the highlighted row is its selected child.
        if (f.className == "android.widget.ListView") {
            val sel = f.findObject(androidx.test.uiautomator.By.selected(true)) ?: return null
            return sel.text ?: sel.findObject(androidx.test.uiautomator.By.text(any))?.text
        }
        if (f.childCount == 0) return f.text
        return f.findObject(androidx.test.uiautomator.By.text(any))?.text
    }

    /** Picks [text] in a dialog with the arrow keys and OK (a tap if the arrows can't reach it). */
    fun dialogPick(text: String, timeoutMs: Long = 8000) {
        if (!dialogShowing(text, timeoutMs)) {
            shot("dialog_missing_${text.take(15)}")
            fail("Expected a choice \"$text\" on screen")
        }
        for (dir in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT)) {
            repeat(14) {
                if (focusedText() == text) {
                    ok()
                    step("picked \"$text\" with the remote")
                    return
                }
                key(dir, 1, 150)
            }
        }
        step("picked \"$text\" by tapping (arrows didn't reach it)")
        device.findObject(androidx.test.uiautomator.By.text(text))?.click()
        SystemClock.sleep(600)
    }

    // ---------------------------------------------------------------- player

    data class PlayerInfo(val state: Int, val playing: Boolean, val positionMs: Long, val error: String?, val errorText: String?, val title: String?)

    fun player(): PlayerInfo? = main {
        val a = current() as? PlayerActivity ?: return@main null
        val p: Player? = a.findViewById<PlayerView>(R.id.player_view)?.player
        val errorView = a.findViewById<View>(R.id.error)
        val errorText = (errorView as? TextView)?.takeIf { it.isShown }?.text?.toString()
            ?: errorView?.takeIf { it.isShown }?.let { ev -> var t: String? = null; walk(ev) { v -> if (t == null && v is TextView && v.text.isNotBlank()) t = v.text.toString() }; t ?: "shown" }
        val title = listOf(R.id.overlay_name, R.id.vc_episode, R.id.vc_title, R.id.title)
            .mapNotNull { (a.findViewById<View>(it) as? TextView)?.text?.toString()?.takeIf { t -> t.isNotBlank() } }
            .joinToString(" · ").ifBlank { null }
        PlayerInfo(p?.playbackState ?: -1, p?.isPlaying ?: false, p?.currentPosition ?: -1, p?.playerError?.errorCodeName, errorText, title)
    }

    /** True when the video is really moving: playing, and the position advances. */
    fun waitPlaying(timeoutMs: Long = 35_000): Boolean {
        val start = SystemClock.elapsedRealtime()
        var first: Long? = null
        val ok = waitUntil(timeoutMs, 500) {
            val p = player() ?: return@waitUntil false
            if (!p.playing || p.state != Player.STATE_READY) {
                first = null
                return@waitUntil false
            }
            val f = first
            if (f == null) {
                first = p.positionMs
                false
            } else {
                p.positionMs - f >= 1500 || p.positionMs < f // live windows can jump back
            }
        }
        if (ok) step("video moving after ${SystemClock.elapsedRealtime() - start} ms")
        return ok
    }

    fun expectPlaying(what: String, timeoutMs: Long = 35_000) {
        step("check that $what plays")
        if (!waitPlaying(timeoutMs)) {
            val p = player()
            shot("not_playing")
            fail("$what did not play within ${timeoutMs / 1000} s (player state=${p?.state}, playing=${p?.playing}, error=${p?.error}, message=${p?.errorText})")
        }
        step("$what is playing (position ${player()?.positionMs} ms)")
    }

    // ---------------------------------------------------------------- fake server control

    fun serverCall(path: String): String = try {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    fun internetDown(seconds: Int) {
        step("SERVER: connection cut for $seconds s")
        serverCall("/__down?seconds=$seconds")
    }

    fun internetUp() {
        serverCall("/__up")
        step("SERVER: connection back")
    }

    fun memoryMb(): Int = Debug.getPss().toInt() / 1024

    // ---------------------------------------------------------------- screenshots + layout check

    /**
     * Screenshot (saved for the review) plus an automatic look at the screen: nothing
     * highlighted, cut-off or overlapping text, text outside the screen, low contrast.
     */
    fun shot(name: String, caption: String = ""): String {
        settle(500)
        val raw = try { inst.uiAutomation.takeScreenshot() } catch (e: Exception) { null }
        val bmp = raw?.let { if (android.os.Build.VERSION.SDK_INT >= 26 && it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
        val file = "${safe(testName)}__${"%03d".format(++shotIndex)}_${safe(name)}"
        val activity = current()
        val warnings = try { main { Audit.run(activity, bmp) } } catch (e: Exception) { JSONArray().put(JSONObject().put("kind", "audit_error").put("text", e.toString())) }
        if (bmp != null) {
            val w = 1280
            val h = bmp.height * w / bmp.width
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            try {
                storage.openOutputFile("qa/shots/$file.jpg").use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } catch (e: Exception) {
                android.util.Log.e("QA", "screenshot write failed", e)
            }
        }
        shots.put(
            JSONObject().put("file", "$file.jpg").put("name", name).put("caption", caption)
                .put("screen", activity?.javaClass?.simpleName ?: "none")
                .put("focus", try { focusedDescription() } catch (e: Exception) { "?" })
                .put("checks", warnings)
        )
        lastShot = "$file.jpg"
        step("screenshot $name")
        save("running", null)
        return file
    }
}

/** Automatic screen checks (run on the main thread). */
object Audit {
    fun run(a: Activity?, bmp: Bitmap?): JSONArray {
        val out = JSONArray()
        if (a == null) {
            out.put(item("no_screen", "No app screen is showing", ""))
            return out
        }
        val root = a.window.decorView
        val focus = a.currentFocus
        if (focus == null) {
            out.put(item("no_focus", "Nothing is highlighted: the remote has no starting point", ""))
        } else {
            val r = Rect()
            val area = focus.width.toLong() * focus.height
            if (!focus.getGlobalVisibleRect(r) || (area > 0 && r.width().toLong() * r.height() < area * 0.6)) {
                out.put(item("focus_hidden", "The highlighted item is mostly outside the screen", label(focus)))
            }
        }

        val texts = ArrayList<Pair<TextView, Rect>>()
        Qa.walk(root) { v ->
            if (v is TextView && v.isShown && v.alpha > 0.05f && !v.text.isNullOrBlank() && v.width > 0 && v.height > 0) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r)) texts.add(v to r)
            }
        }
        var cutInLists = 0
        for ((tv, r) in texts) {
            val text = tv.text.toString().replace('\n', ' ').take(70)
            val inList = inScrollingContainer(tv)
            val layout = tv.layout
            if (layout != null) {
                var ellipsized = false
                for (i in 0 until layout.lineCount) if (layout.getEllipsisCount(i) > 0) ellipsized = true
                if (ellipsized) {
                    if (inList) cutInLists++ else out.put(item("text_cut", "Text cut short with “…”", text))
                }
                val room = tv.height - tv.compoundPaddingTop - tv.compoundPaddingBottom
                if (layout.height > room + 6 && !ellipsized) out.put(item("text_clipped", "Text is taller than its box (lines cut off)", text))
            }
            val full = tv.width.toLong() * tv.height
            if (!inList && full > 0 && r.width().toLong() * r.height() < full * 0.85) {
                out.put(item("text_offscreen", "Text partly outside the screen or its panel", text))
            }
            if (bmp != null) contrast(tv, r, bmp)?.let { ratio ->
                if (ratio < 3.0) out.put(item("low_contrast", "Hard to read: text and background too similar (%.1f:1)".format(ratio), text))
            }
        }
        if (cutInLists > 0) out.put(item("info_list_titles_cut", "$cutInLists titles in lists are shortened with “…”", ""))

        // Text drawn over other text.
        val limited = texts.take(250)
        var overlaps = 0
        for (i in limited.indices) for (j in i + 1 until limited.size) {
            if (overlaps >= 8) break
            val (a1, r1) = limited[i]
            val (a2, r2) = limited[j]
            if (Qa.isAncestor(a1, a2) || Qa.isAncestor(a2, a1)) continue
            val inter = Rect()
            if (!inter.setIntersect(r1, r2)) continue
            val small = minOf(r1.width().toLong() * r1.height(), r2.width().toLong() * r2.height())
            if (small > 0 && inter.width().toLong() * inter.height() > small * 0.3) {
                out.put(item("overlap", "Two texts drawn on top of each other", "\"${a1.text.take(40)}\" / \"${a2.text.take(40)}\""))
                overlaps++
            }
        }
        return out
    }

    private fun item(kind: String, text: String, detail: String) = JSONObject().put("kind", kind).put("text", text).put("detail", detail)

    private fun label(v: View): String = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { v.javaClass.simpleName }

    private fun inScrollingContainer(v: View): Boolean {
        var p = v.parent
        while (p is View) {
            if (p is RecyclerView || p is android.widget.ScrollView || p is android.widget.HorizontalScrollView ||
                p.javaClass.simpleName.contains("NestedScroll")) return true
            p = p.parent
        }
        return false
    }

    /** Contrast between a text's colour and the background behind it in the screenshot. */
    private fun contrast(tv: TextView, r: Rect, bmp: Bitmap): Double? {
        val x0 = r.left.coerceIn(0, bmp.width - 1)
        val x1 = r.right.coerceIn(0, bmp.width - 1)
        val y0 = r.top.coerceIn(0, bmp.height - 1)
        val y1 = r.bottom.coerceIn(0, bmp.height - 1)
        if (x1 - x0 < 8 || y1 - y0 < 6) return null
        val fg = tv.currentTextColor
        val buckets = HashMap<Int, IntArray>()
        for (gx in 0..15) for (gy in 0..5) {
            val c = bmp.getPixel(x0 + (x1 - x0) * gx / 15, y0 + (y1 - y0) * gy / 5)
            val key = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            val acc = buckets.getOrPut(key) { IntArray(4) }
            acc[0]++; acc[1] += Color.red(c); acc[2] += Color.green(c); acc[3] += Color.blue(c)
        }
        // The most common colour that isn't the text's own colour is the background.
        val bg = buckets.values.sortedByDescending { it[0] }.map { Color.rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }
            .firstOrNull { distance(it, fg) > 60 } ?: return null
        val alpha = Color.alpha(fg) / 255.0 * tv.alpha
        val blended = Color.rgb(
            (Color.red(fg) * alpha + Color.red(bg) * (1 - alpha)).toInt(),
            (Color.green(fg) * alpha + Color.green(bg) * (1 - alpha)).toInt(),
            (Color.blue(fg) * alpha + Color.blue(bg) * (1 - alpha)).toInt(),
        )
        val l1 = luminance(blended)
        val l2 = luminance(bg)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun distance(a: Int, b: Int): Int =
        Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }
}
