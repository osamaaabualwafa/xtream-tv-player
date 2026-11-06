package com.osama.xtreamtv

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A message on screen, also kept in the diagnostics report (what the user was told). */
fun showToast(context: Context, text: CharSequence, length: Int) {
    Diag.log("message", text.toString())
    // On a screen: the themed pill (MessagePill). Without one (rare): Android's own message.
    var c: Context? = context
    while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
    val activity = c as? android.app.Activity
    if (activity != null && !activity.isFinishing && activity.window != null) MessagePill.show(activity, text, length)
    else Toast.makeText(context, text, length).show()
}

/**
 * Settings > About > Diagnostics (review item 1.1L). Keeps a report on the TV of how the app behaves:
 * playback, seeking, the server's answers, the network, the TV's video, speed, screen problems and
 * crashes. Every line is cleaned by [Redact] before it is written, so no address, login or password is
 * ever in it. "Share report" serves it over the home Wi-Fi to a computer that knows the address and code.
 */
object Diag {
    private const val MAX_BYTES = 10L * 1024 * 1024
    private const val PORT = 8095
    const val SHARE_MS = 15 * 60_000L
    private const val KEY_TRAIL = 50
    val KEEP_DAYS = listOf(7, 30, 90)
    val MARK_TYPES = listOf("Playback", "Screen", "Slow", "Other")

    private lateinit var app: Application
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val sp get() = app.getSharedPreferences("xtream", Context.MODE_PRIVATE)
    private val file get() = File(app.filesDir, "diagnostics.log")

    var enabled: Boolean
        // On by default while the app is being polished (one user, at home). Switch the default
        // back to off before anyone else uses the app.
        get() = ::app.isInitialized && sp.getBoolean("diag_on", true)
        set(value) {
            if (!value) log("diagnostics", "turned off")
            sp.edit().putBoolean("diag_on", value).apply()
            if (value) log("diagnostics", "turned on")
        }
    var includeTitles: Boolean
        get() = sp.getBoolean("diag_titles", false)
        set(value) = sp.edit().putBoolean("diag_titles", value).apply()
    var keepDays: Int
        get() = sp.getInt("diag_keep_days", 30)
        set(value) {
            sp.edit().putInt("diag_keep_days", value).apply()
            io.execute { prune() }
        }

    /** The screen in front, for freezes and the key trail. */
    @Volatile var screen = "-"
        private set
    private val keys = ArrayDeque<String>()

    fun init(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Kept even with Diagnostics off: crashes are rare and the most useful thing to know.
            runCatching {
                val where = error.stackTrace.take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                write("crash", "${error.javaClass.simpleName}: ${error.message ?: ""} · on $screen · at $where", System.currentTimeMillis())
                // For the message on the next open and the red dot until the report is shared.
                sp.edit().putLong("diag_crash_at", System.currentTimeMillis()).putBoolean("diag_crash_prompted", false).commit()
            }
            previous?.uncaughtException(thread, error)
        }
        io.execute { prune() }
        application.registerActivityLifecycleCallbacks(Screens)
        application.registerComponentCallbacks(Memory)
        sp.registerOnSharedPreferenceChangeListener(settingsWatcher)
        watchNetwork()
        startWatchdog()
    }

    /** Adds a line; nothing happens while Diagnostics is off. */
    fun log(kind: String, text: String) {
        if (!enabled) return
        val time = System.currentTimeMillis()
        io.execute { write(kind, text, time) }
    }

    /** A title for the report, only when the user allowed titles. */
    fun title(name: String?): String = if (includeTitles && !name.isNullOrBlank()) " · \"$name\"" else ""

    /**
     * Saves the moment at once (with a small picture of the app's screen when titles are allowed,
     * since a picture can show them). Returns the time, for the "What went wrong?" question.
     */
    fun mark(from: String, activity: Activity?, extra: String = ""): String {
        val at = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (!enabled) return at
        val trail = synchronized(keys) { keys.joinToString(" ") }
        val picture = if (includeTitles && activity != null) takePicture(activity) else null
        log(
            "MARK",
            "problem marked from $from · on $screen · type: not chosen" +
                (if (extra.isNotBlank()) " · $extra" else "") +
                (picture?.let { " · picture $it" } ?: "") + " · last keys: $trail",
        )
        sp.edit().putInt("diag_marks_waiting", sp.getInt("diag_marks_waiting", 0) + 1)
            .putLong("diag_last_mark", System.currentTimeMillis()).apply()
        return at
    }

    private val shotsDir get() = File(app.filesDir, "diag_shots").apply { mkdirs() }

    /** The app's own screen (a video shows as black), small: about 100 KB. */
    private fun takePicture(activity: Activity): String? = runCatching {
        val view = activity.window.decorView
        if (view.width == 0) return null
        val scale = 960f / view.width
        val bitmap = android.graphics.Bitmap.createBitmap(960, (view.height * scale).toInt(), android.graphics.Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        val name = "mark-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.jpg"
        io.execute {
            runCatching { File(shotsDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, it) } }
            bitmap.recycle()
        }
        name
    }.getOrNull()

    // ---- Warnings and reminders ----

    class Warning(val color: Int, val text: String)

    /** For the Report card: red for a crash, yellow for full or about to lose entries, blue for marks. */
    fun warnings(): List<Warning> {
        if (!::app.isInitialized) return emptyList()
        val out = ArrayList<Warning>()
        val day = SimpleDateFormat("d MMM", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US)
        val crash = sp.getLong("diag_crash_at", 0L)
        if (crash > 0) out += Warning(DiagUi.RED, "The app closed unexpectedly on ${day.format(Date(crash))} at ${time.format(Date(crash))}. Share the report to help fix it.")
        val stats = stats()
        val full = stats.kb * 1024 * 100 / MAX_BYTES
        if (full >= 80) {
            out += Warning(if (full >= 95) DiagUi.RED else DiagUi.YELLOW, "Almost full ($full%). The oldest entries will be removed soon. Share it now to keep them.")
        }
        val left = stats.daysLeft
        if (left != null && left <= 3 && stats.entries > 0) {
            val last = sp.getLong("diag_last_share_at", 0L)
            val since = if (last > 0) "Not shared for ${(System.currentTimeMillis() - last) / 86_400_000L} days. " else "Not shared yet. "
            out += Warning(DiagUi.YELLOW, "${since}Entries start being deleted in $left ${if (left == 1) "day" else "days"}. Share it now.")
        }
        val marks = sp.getInt("diag_marks_waiting", 0)
        if (marks > 0) {
            val lastMark = sp.getLong("diag_last_mark", 0L)
            out += Warning(DiagUi.BLUE, "$marks marked ${if (marks == 1) "problem" else "problems"} waiting to be shared. Last one ${day.format(Date(lastMark))} at ${time.format(Date(lastMark))}.")
        }
        return out
    }

    /** The colour of the dot on the Home Settings button, or null: only the most important one shows. */
    fun dotColor(): Int? {
        val w = warnings()
        return listOf(DiagUi.RED, DiagUi.YELLOW, DiagUi.BLUE).firstOrNull { c -> w.any { it.color == c } }
    }

    /** True once per crash: the message on the next open. */
    fun takeCrashPrompt(): Boolean {
        if (!::app.isInitialized || sp.getLong("diag_crash_at", 0L) == 0L || sp.getBoolean("diag_crash_prompted", false)) return false
        sp.edit().putBoolean("diag_crash_prompted", true).apply()
        return true
    }

    fun clear() = io.execute {
        file.delete()
        shotsDir.listFiles()?.forEach { it.delete() }
        sp.edit().putInt("diag_marks_waiting", 0).apply()
        sp.edit().remove("diag_shared_upto").apply()
    }

    class Stats(val entries: Int, val kb: Long, val daysLeft: Int?, val lastShare: String?)

    /** For Settings > About: entry count, size, days until the oldest entry goes, and the last share. */
    fun stats(): Stats {
        val f = file
        val lines = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
        val oldest = lines.firstOrNull()?.split('\t')?.getOrNull(1)?.toLongOrNull()
        val daysLeft = oldest?.let {
            val gone = it + keepDays * 86_400_000L
            ((gone - System.currentTimeMillis() + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
        }
        val pictures = shotsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return Stats(lines.size, ((if (f.exists()) f.length() else 0L) + pictures) / 1024, daysLeft, sp.getString("diag_last_share", null))
    }

    // ---- Storage ----

    private var secretsAt = 0L
    private var secrets: List<String> = emptyList()

    private fun secrets(): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - secretsAt > 60_000 || secretsAt == 0L) {
            secrets = runCatching {
                Prefs(app).playlists().flatMap { listOf(it.username, it.password) + Redact.serverParts(it.base) }
            }.getOrDefault(emptyList())
            secretsAt = now
        }
        return secrets
    }

    @Synchronized
    private fun write(kind: String, text: String, time: Long) {
        val seq = sp.getLong("diag_seq", 0L) + 1
        sp.edit().putLong("diag_seq", seq).commit()
        val line = "#$seq\t$time\t$kind\t${Redact.clean(text, secrets())}\n"
        file.appendText(line)
        if (BuildConfig.DEBUG) Log.d("Diag", line.trimEnd())
        if (file.length() > MAX_BYTES) {
            // Full: the oldest half goes.
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    private fun prune() {
        val f = file
        if (!f.exists()) return
        val oldest = System.currentTimeMillis() - keepDays * 86_400_000L
        val lines = f.readLines()
        val kept = lines.filter { (it.split('\t').getOrNull(1)?.toLongOrNull() ?: 0L) >= oldest }
        shotsDir.listFiles()?.filter { it.lastModified() < oldest }?.forEach { it.delete() }
        if (kept.size != lines.size) f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }

    // ---- The report ----

    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun report(name: String, since: Long): Pair<String, Long> {
        prune()
        val lines = if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
        val seqs = lines.mapNotNull { it.substringBefore('\t').removePrefix("#").toLongOrNull() }
        val last = seqs.maxOrNull() ?: 0L
        val kinds = lines.groupingBy { it.split('\t').getOrNull(2) ?: "?" }.eachCount()
        val prefs = Prefs(app)
        val free = runCatching { app.filesDir.usableSpace / (1024 * 1024) }.getOrDefault(-1)
        val cache = runCatching { app.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) }.getOrDefault(-1)
        val out = StringBuilder()
        out.append("$name\n")
        out.append("App ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · TV: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        out.append("Look: theme ${prefs.themeIndex}, background ${prefs.glassScene}, home ${prefs.homeStyle}, interface ${prefs.uiSize}, text ${prefs.textSize}\n")
        out.append("Player: decoder ${prefs.decoderMode}, buffer ${prefs.bufferSize}, menu ${prefs.playerMenuStyle}, picture ${prefs.pictureSize}\n")
        out.append("Storage: $free MB free, cache $cache MB · Titles ${if (includeTitles) "included" else "hidden"} · Kept $keepDays days\n")
        out.append("Entries: ${if (seqs.isEmpty()) "none" else "#${seqs.minOrNull()}–#$last"} · ${seqs.count { it > since }} new since the last share\n")
        out.append("Totals: ${kinds.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }}\n")
        out.append("Seeks that landed somewhere else: ${lines.count { it.contains("jumped") }}\n\n")
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        for (line in lines) {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) continue
            out.append(parts[0]).append("  ").append(time.format(Date(parts[1].toLongOrNull() ?: 0))).append("  ")
                .append(parts[2].padEnd(10)).append(' ').append(parts[3]).append('\n')
        }
        return out.toString() to last
    }

    // ---- Sharing over the home Wi-Fi ----

    class Share(val name: String, val address: String, val code: String, @Volatile var endsAt: Long)

    /** "+15 min" on the share screen. */
    fun extendShare(share: Share) {
        share.endsAt = maxOf(share.endsAt, SystemClock.elapsedRealtime()) + SHARE_MS
    }

    @Volatile private var server: ServerSocket? = null

    /**
     * Serves the report at http://address/report?code=CODE until [Share.endsAt] or [stopShare].
     * [clearAfter]: once it has been sent, the entries in it are deleted. [onSent] runs on the main thread.
     */
    fun startShare(clearAfter: Boolean, onSent: (String) -> Unit): Share? {
        stopShare()
        val ip = localAddress() ?: return null
        val number = sp.getInt("diag_share_no", 0) + 1
        val now = Date()
        val name = "Share #$number · ${stamp.format(now)}"
        val fileName = "share-#$number-${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(now)}.txt"
        val code = (1000..9999).random().toString()
        val share = Share(name, "$ip:$PORT", code, SystemClock.elapsedRealtime() + SHARE_MS)
        val socket = runCatching { ServerSocket(PORT).apply { soTimeout = 1000 } }.getOrNull() ?: return null
        sp.edit().putInt("diag_share_no", number).apply()
        server = socket
        log("share", "$name started (${if (clearAfter) "clear after" else "keep"})")
        // Test builds: the code the screen shows, for the test tools (the moving countdown stops them reading the screen).
        if (BuildConfig.DEBUG) Log.d("DiagShare", "code $code")
        Thread {
            var sent = false
            while (server === socket && SystemClock.elapsedRealtime() < share.endsAt) {
                val client = try { socket.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                runCatching {
                    client.soTimeout = 5000
                    val request = client.getInputStream().bufferedReader().readLine() ?: ""
                    val path = request.split(' ').getOrNull(1) ?: ""
                    val ok = (path.startsWith("/report") || path.startsWith("/shot/")) && path.contains("code=$code")
                    val body: ByteArray
                    val head: String
                    val shot = if (ok && path.startsWith("/shot/")) File(shotsDir, path.removePrefix("/shot/").substringBefore('?').substringAfterLast('/')) else null
                    if (shot != null) {
                        body = if (shot.exists()) shot.readBytes() else ByteArray(0)
                        head = "HTTP/1.0 ${if (shot.exists()) "200 OK" else "404 Not Found"}\r\nContent-Type: image/jpeg\r\n"
                    } else if (ok) {
                        val since = sp.getLong("diag_shared_upto", 0L)
                        val (text, last) = synchronized(this) { report(name, since) }
                        body = text.toByteArray(Charsets.UTF_8)
                        head = "HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Disposition: attachment; filename=\"$fileName\"\r\n"
                        if (!sent) {
                            sent = true
                            sp.edit().putLong("diag_shared_upto", last).putString("diag_last_share", name)
                                .putLong("diag_last_share_at", System.currentTimeMillis())
                                .putInt("diag_marks_waiting", 0).putLong("diag_crash_at", 0L).apply()
                            if (clearAfter) io.execute { dropUpTo(last) }
                            main.post { onSent(name) }
                        }
                    } else {
                        body = "Wrong code".toByteArray()
                        head = "HTTP/1.0 403 Forbidden\r\nContent-Type: text/plain\r\n"
                    }
                    client.getOutputStream().apply {
                        write((head + "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
                runCatching { client.close() }
            }
            runCatching { socket.close() }
            if (server === socket) server = null
        }.apply { isDaemon = true }.start()
        return share
    }

    fun stopShare() {
        val s = server
        server = null
        runCatching { s?.close() }
    }

    @Synchronized
    private fun dropUpTo(last: Long) {
        if (!file.exists()) return
        val kept = file.readLines().filter { (it.substringBefore('\t').removePrefix("#").toLongOrNull() ?: 0L) > last }
        file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    // ---- Settings changes (never logins, playlists or saved places) ----

    private val SAFE_TEXT = setOf("audio_lang", "sub_lang")
    private val SKIP = listOf("diag_", "pos_", "playlists", "active", "default_playlist", "avatar", "continue", "fav", "watched", "pinned", "last_", "update")

    private val settingsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || SKIP.any { key.startsWith(it) }) return@OnSharedPreferenceChangeListener
        val value = prefs.all[key]
        val shown = when (value) {
            is Int, is Boolean, is Float -> value.toString()
            is String -> if (key in SAFE_TEXT) value else return@OnSharedPreferenceChangeListener
            null -> "reset"
            else -> return@OnSharedPreferenceChangeListener
        }
        log("setting", "$key changed to $shown")
    }

    // ---- Screens: opening, changes, slow frames, loading circles, keys, posters ----

    private var startShown = false
    private var firstActivity = true
    private var started = 0
    @Volatile private var lastKeyId = 0L
    @Volatile private var lastKey = ""
    /** The last Up/Down press (repeats too): code, when, and how many repeats into a hold. */
    @Volatile private var arrowCode = 0
    @Volatile private var arrowAt = 0L
    @Volatile private var arrowRepeat = 0
    @Volatile private var postersFailed = 0

    /** Coil reports pictures that didn't load; counted per screen. */
    fun posterFailed() {
        if (enabled) postersFailed++
    }

    /** The list (RecyclerView) a view is in, or null. */
    private fun listAround(v: View): View? {
        var p: android.view.ViewParent? = v.parent
        while (p is View) {
            if (p is androidx.recyclerview.widget.RecyclerView) return p
            p = p.parent
        }
        return null
    }

    private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("DPAD_")

    private object Screens : Application.ActivityLifecycleCallbacks {
        private var frames: Choreographer.FrameCallback? = null
        private var slowFrames = 0
        private var worstFrame = 0L
        private var previous = ""
        private var loadingSince = 0L
        private var loadingLong = false
        private var sameKeyCount = 0

        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            val name = activity.javaClass.simpleName.removeSuffix("Activity")
            if (firstActivity) {
                firstActivity = false
                if (state != null) log("app", "Android had closed the app in the background; it reopened on $name")
            }
            val opened = SystemClock.elapsedRealtime()
            val decor = activity.window.decorView
            val draw = object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                    val ms = SystemClock.elapsedRealtime() - opened
                    if (ms >= 700) log("screen", "$name took $ms ms to open")
                    if (!startShown && name == "Home") {
                        startShown = true
                        if (Build.VERSION.SDK_INT >= 24) {
                            log("start", "Home shown ${(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 100 / 10.0} s after the app started")
                        }
                    }
                }
            }
            decor.viewTreeObserver.addOnDrawListener(draw)
            // Up/Down that takes the highlight out of a list into a list beside it (the series grid
            // into the categories, say): Android picked the nearest thing because the list didn't
            // answer in time. Moving into a list above or below is normal and not logged.
            decor.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
                if (!enabled || old == null || new == null) return@addOnGlobalFocusChangeListener
                if (SystemClock.uptimeMillis() - arrowAt > 400) return@addOnGlobalFocusChangeListener
                val from = listAround(old) ?: return@addOnGlobalFocusChangeListener
                val to = listAround(new)
                if (to === from) return@addOnGlobalFocusChangeListener
                val a = IntArray(2).also { from.getLocationOnScreen(it) }
                val b = IntArray(2).also { new.getLocationOnScreen(it) }
                val sideways = b[0] + new.width <= a[0] || b[0] >= a[0] + from.width
                if (!sideways) return@addOnGlobalFocusChangeListener
                val held = if (arrowRepeat > 0) " while held (${arrowRepeat} repeats)" else ""
                log("ui", "${keyName(arrowCode)}$held moved the highlight out of ${viewName(from)} to ${viewName(to ?: new)} on $name")
            }
            val callback = activity.window.callback ?: return
            activity.window.callback = object : Window.Callback by callback {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    RemoteHold.onKey(event)
                    // Test builds: every key with its repeat count, for the scrolling measurements.
                    if (BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) Log.v("KeyTrace", "key ${event.keyCode} repeat ${event.repeatCount}")
                    if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                        arrowCode = event.keyCode
                        arrowAt = SystemClock.uptimeMillis()
                        arrowRepeat = event.repeatCount
                    } else if (event.action == KeyEvent.ACTION_DOWN) {
                        arrowAt = 0L   // Left, Right, OK… move the highlight on purpose.
                    }
                    // A key can pass through here more than once (windows inside windows): count it once.
                    val id = event.downTime * 31 + event.eventTime * 7 + event.keyCode * 3 + event.repeatCount
                    if (enabled && event.action == KeyEvent.ACTION_DOWN && id != lastKeyId) {
                        lastKeyId = id
                        // Mark shortcuts: the red button, or INFO / Menu held down (some TVs keep Menu for themselves).
                        val markKey = (event.keyCode == KeyEvent.KEYCODE_PROG_RED && event.repeatCount == 0) ||
                            ((event.keyCode == KeyEvent.KEYCODE_INFO || event.keyCode == KeyEvent.KEYCODE_MENU) && event.repeatCount == 1)
                        if (markKey) {
                            DiagUi.mark(if (event.keyCode == KeyEvent.KEYCODE_PROG_RED) "the red button" else "a long press on ${keyName(event.keyCode)}", activity)
                            return true
                        }
                        if (event.repeatCount == 0) {
                            lastKey = keyName(event.keyCode)
                            synchronized(keys) {
                                keys.addLast(lastKey)
                                while (keys.size > KEY_TRAIL) keys.removeFirst()
                            }
                            watchFocus(activity, event.keyCode)
                        }
                    }
                    return callback.dispatchKeyEvent(event)
                }
            }
        }

        /** After an arrow key: nothing highlighted at all, or the same key again and again with nothing moving. */
        private fun watchFocus(activity: Activity, keyCode: Int) {
            if (keyCode !in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) return
            val name = activity.javaClass.simpleName.removeSuffix("Activity")
            // The player uses the arrows for seeking and the channel list, not for moving a highlight.
            if (name == "Player") return
            val before = activity.currentFocus
            activity.window.decorView.postDelayed({
                if (activity.isFinishing || !activity.hasWindowFocus()) return@postDelayed
                val now = activity.currentFocus
                when {
                    now == null || !now.isShown -> log("ui", "nothing highlighted on $name after ${keyName(keyCode)}")
                    now === before && now !is android.widget.EditText -> {
                        sameKeyCount++
                        if (sameKeyCount == 3) log("ui", "${keyName(keyCode)} pressed 3 times on $name, the highlight didn't move (${viewName(now)})")
                    }
                    else -> sameKeyCount = 0
                }
            }, 300)
        }

        override fun onActivityResumed(activity: Activity) {
            screen = activity.javaClass.simpleName.removeSuffix("Activity")
            if (previous.isNotEmpty() && previous != screen) log("screen", "$previous → $screen (last key ${lastKey.ifEmpty { "none" }})")
            previous = screen
            if (!enabled) return
            slowFrames = 0
            worstFrame = 0
            postersFailed = 0
            sameKeyCount = 0
            var last = 0L
            var nextScan = 0L
            val decor = activity.window.decorView
            val choreographer = Choreographer.getInstance()
            frames = object : Choreographer.FrameCallback {
                override fun doFrame(now: Long) {
                    if (frames !== this) return
                    if (last != 0L) {
                        val gap = (now - last) / 1_000_000
                        if (gap in 250..4999) {
                            slowFrames++
                            worstFrame = maxOf(worstFrame, gap)
                        }
                    }
                    last = now
                    // Every 2 s: is a loading circle up, and for how long.
                    val ms = now / 1_000_000
                    if (ms >= nextScan) {
                        nextScan = ms + 2000
                        scanLoading(decor)
                    }
                    choreographer.postFrameCallback(this)
                }
            }.also { choreographer.postFrameCallback(it) }
        }

        private fun scanLoading(decor: View) {
            val now = SystemClock.elapsedRealtime()
            if (hasLoadingCircle(decor)) {
                if (loadingSince == 0L) loadingSince = now
                if (!loadingLong && now - loadingSince > 5000) loadingLong = true
            } else {
                if (loadingLong) log("ui", "$screen showed the loading circle for ${(now - loadingSince) / 1000} s")
                loadingSince = 0
                loadingLong = false
            }
        }

        private fun hasLoadingCircle(v: View): Boolean {
            if (!v.isShown) return false
            if (v is ProgressBar && v.isIndeterminate) return true
            if (v is ViewGroup) for (i in 0 until v.childCount) if (hasLoadingCircle(v.getChildAt(i))) return true
            return false
        }

        override fun onActivityPaused(activity: Activity) {
            frames = null
            if (slowFrames > 0) log("smooth", "$screen: $slowFrames stutters (worst $worstFrame ms)")
            if (postersFailed > 0) log("ui", "$postersFailed posters failed to load on $screen")
            if (loadingLong) log("ui", "$screen showed the loading circle for ${(SystemClock.elapsedRealtime() - loadingSince) / 1000} s")
            slowFrames = 0
            postersFailed = 0
            loadingSince = 0
            loadingLong = false
        }

        override fun onActivityStarted(activity: Activity) {
            if (started == 0 && !firstStart) log("app", "came back from the background to ${activity.javaClass.simpleName.removeSuffix("Activity")}")
            if (started == 0 && !firstStart) Updates.noteReturn()
            firstStart = false
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) log("app", "went to the background from $screen")
        }

        private var firstStart = true
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** A short readable name for a view: its id, or its text. */
    fun viewName(v: View): String {
        if (v.id != View.NO_ID) runCatching { return v.resources.getResourceEntryName(v.id) }
        val text = (v as? TextView)?.text?.toString() ?: (v as? ViewGroup)?.let { g ->
            (0 until g.childCount).firstNotNullOfOrNull { (g.getChildAt(it) as? TextView)?.text?.toString()?.takeIf(String::isNotBlank) }
        }
        return text?.let { "\"${it.take(24)}\"" } ?: v.javaClass.simpleName
    }

    // ---- Freezes: the screen not answering for 5 seconds ----

    @Volatile private var beat = SystemClock.elapsedRealtime()

    private fun startWatchdog() {
        Thread {
            var stalledSince = 0L
            while (true) {
                runCatching { Thread.sleep(1000) }
                main.post { beat = SystemClock.elapsedRealtime() }
                val gap = SystemClock.elapsedRealtime() - beat
                if (gap > 5000 && stalledSince == 0L) stalledSince = beat
                if (stalledSince != 0L && gap < 1500) {
                    log("freeze", "screen stopped responding for ${(beat - stalledSince) / 100 / 10.0} s ($screen)")
                    stalledSince = 0L
                }
            }
        }.apply { isDaemon = true; name = "diag-watchdog" }.start()
    }

    // ---- Network and memory ----

    private fun watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        var lastKind = ""
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val kind = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cable"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        else -> "other"
                    }
                    if (kind != lastKind) {
                        lastKind = kind
                        val signal = if (Build.VERSION.SDK_INT >= 29 && caps.signalStrength != Int.MIN_VALUE) " · signal ${caps.signalStrength} dBm" else ""
                        log("network", "connected by $kind · ${caps.linkDownstreamBandwidthKbps / 1000} Mbps estimated$signal")
                    }
                }

                override fun onLost(network: Network) {
                    lastKind = ""
                    log("network", "connection lost")
                }
            })
        }
    }

    private object Memory : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) log("memory", "the TV is low on memory (level $level) · on $screen")
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java") override fun onLowMemory() = log("memory", "the TV is out of memory · on $screen")
    }
}
