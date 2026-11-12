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
    Diag.log("message", "$text · on ${Diag.screen}")
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
                write("crash", "${error.javaClass.simpleName}: ${error.message ?: ""} · on $screen · at $where" + problemContext(), System.currentTimeMillis())
                // For the message on the next open and the red dot until the report is shared.
                sp.edit().putLong("diag_crash_at", System.currentTimeMillis()).putBoolean("diag_crash_prompted", false).commit()
            }
            previous?.uncaughtException(thread, error)
        }
        io.execute { prune() }
        // The last session was in front of you and didn't end normally (no crash recorded): Android
        // closed it, usually for memory (2026-11-07: only guessed from a freeze followed by silence).
        if (sp.getBoolean("diag_alive", false)) {
            val crashAt = sp.getLong("diag_crash_at", 0L)
            val lastSeen = sp.getLong("diag_last_seen", 0L)
            if (lastSeen > 0 && crashAt < lastSeen - 60_000) {
                val at = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(lastSeen))
                log("ended", "the last session ended unexpectedly on ${sp.getString("diag_last_screen", "?")} (about $at, probably closed by Android)" +
                    " · last memory: ${sp.getString("diag_last_mem", "not measured")} · keys: ${sp.getString("diag_last_keys", "none")}" +
                    " · [${sp.getString("diag_last_tag", "?")}]")
            }
        }
        sp.edit().putBoolean("diag_alive", false).apply()
        application.registerActivityLifecycleCallbacks(Screens)
        application.registerComponentCallbacks(Memory)
        sp.registerOnSharedPreferenceChangeListener(settingsWatcher)
        watchNetwork()
        startWatchdog()
    }

    /** Adds a line; nothing happens while Diagnostics is off. Problem lines also get the keys pressed
     *  just before and the look and player settings in use (to replay it and tie it to a setting). */
    fun log(kind: String, text: String) {
        if (!enabled) return
        val time = System.currentTimeMillis()
        val full = if (kind in PROBLEMS) text + problemContext() else text
        io.execute { write(kind, full, time) }
    }

    private val PROBLEMS = setOf("ui", "freeze", "error")

    /** " · keys: ↓ ↓ → OK · [Glass·Midnight·home 2·browse 3·size 3·text 1·focus 2]". */
    fun problemContext(): String = " · keys: ${trail(6)} · [${settingsTag()}]"

    /** The last [n] keys pressed, as arrows. */
    fun trail(n: Int): String = synchronized(keys) { keys.toList().takeLast(n) }.joinToString(" ") {
        when (it) { "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"; "CENTER" -> "OK"; "BACK" -> "Back"; else -> it }
    }.ifEmpty { "none" }

    /** The look (and, on the player, the player) settings in use: never logins or titles. */
    fun settingsTag(): String = runCatching {
        val p = Prefs(app)
        val theme = Appearance.THEMES.firstOrNull { it.first == p.themeIndex }?.second ?: "theme ${p.themeIndex}"
        val scene = if (p.themeIndex == Appearance.THEME_GLASS) "·" + Appearance.SCENE_NAMES.getOrElse(p.glassScene) { "?" } else ""
        val player = if (screen == "Player") "·decoder ${p.decoderMode}·buffer ${p.bufferSize}" else ""
        "$theme$scene·home ${p.homeStyle}·browse ${p.browseStyle}·size ${p.uiSize}·text ${p.textSize}·focus ${p.focusStyle}$player"
    }.getOrDefault("?")

    /** How long a screen took to show a part of its content ("Details: scores ready after 1.2 s"). */
    fun ready(screenName: String, part: String, sinceUptime: Long) {
        val s = (SystemClock.uptimeMillis() - sinceUptime) / 100 / 10.0
        log("speed", "$screenName: $part ready after $s s")
    }

    // ---- Text too small: one line per screen with a count, not one per label ----
    private val smallText = HashMap<String, Pair<Int, String>>()

    fun smallText(quote: String) {
        synchronized(smallText) {
            val (n, first) = smallText[screen] ?: (0 to quote)
            smallText[screen] = (n + 1) to first
        }
    }

    private fun flushSmallText(name: String) {
        val (n, first) = synchronized(smallText) { smallText.remove(name) } ?: return
        log("ui", "text too small to read on $name: $n ${if (n == 1) "item" else "items"} (e.g. $first)")
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

    /** Test builds: the report as it would be shared, written to files/debug_report.txt. */
    fun reportForTests() {
        if (!BuildConfig.DEBUG) return
        io.execute { runCatching { File(app.filesDir, "debug_report.txt").writeText(report("Test report", sp.getLong("diag_shared_upto", 0L)).first) } }
    }

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
        val recent = lines.filter { (it.substringBefore('\t').removePrefix("#").toLongOrNull() ?: 0L) > since }
        out.append(summary(recent.ifEmpty { lines }, if (recent.isEmpty()) "all entries" else "since the last share"))
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        for (line in lines) {
            val parts = line.split('\t', limit = 4)
            if (parts.size < 4) continue
            out.append(parts[0]).append("  ").append(time.format(Date(parts[1].toLongOrNull() ?: 0))).append("  ")
                .append(parts[2].padEnd(10)).append(' ').append(parts[3]).append('\n')
        }
        return out.toString() to last
    }

    /**
     * The report's summary (2026-11-07): what went wrong and how fast things were, in one block, with
     * stable names ("  crashes: 0") so the computer can compare it with the previous report.
     */
    private fun summary(lines: List<String>, scope: String): String {
        val entries = lines.mapNotNull { l -> l.split('\t', limit = 4).takeIf { it.size == 4 }?.let { it[2] to it[3] } }
        fun count(kind: String, test: (String) -> Boolean = { true }) = entries.count { it.first == kind && test(it.second) }
        fun ui(text: String) = count("ui") { it.contains(text) }
        val out = StringBuilder("Summary ($scope: ${entries.size} entries)\n")
        fun line(name: String, value: Any) { out.append("  $name: $value\n") }
        line("crashes", count("crash"))
        line("ended unexpectedly", count("ended"))
        line("freezes", count("freeze"))
        line("highlight moved by itself", ui("moved by itself"))
        line("came back on another item", ui("came back to"))
        line("long-press problems", ui("long-press"))
        line("key pressed 3 times, nothing moved", ui("pressed 3 times"))
        line("nothing highlighted", ui("nothing highlighted"))
        line("More like this not shown", ui("More like this not shown"))
        line("text cut", ui("text cut"))
        line("items on top of each other", ui("on top of each other"))
        line("partly off screen", ui("partly off screen"))
        line("highlight cut", ui("highlight cut"))
        line("posters failed", entries.filter { it.first == "ui" && it.second.contains("posters failed") }.sumOf { Regex("^(\\d+)").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0 })
        line("errors", count("error"))
        // Slowest opening per screen.
        val opens = entries.filter { it.first == "screen" }.mapNotNull { Regex("^(\\w+) took (\\d+) ms to open").find(it.second)?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }
        opens.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (name, ms) -> line("slowest open $name", "${ms.max()} ms (${ms.size}x over 0.7 s)") }
        // Content ready: median and worst.
        val ready = entries.filter { it.first == "speed" }.mapNotNull { Regex("^(.+?) ready after ([\\d.]+) s").find(it.second)?.let { m -> m.groupValues[1] to m.groupValues[2].toDouble() } }
        ready.groupBy({ it.first }, { it.second }).toSortedMap().forEach { (name, s) ->
            val sorted = s.sorted()
            line("ready $name", "median ${sorted[sorted.size / 2]} s, worst ${sorted.last()} s (${s.size}x)")
        }
        val stutters = entries.filter { it.first == "smooth" }.mapNotNull { Regex("^(\\w+): \\d+ stutters \\(worst (\\d+) ms\\)").find(it.second)?.let { m -> m.groupValues[1] to m.groupValues[2].toInt() } }
        stutters.maxByOrNull { it.second }?.let { line("worst stutter", "${it.second} ms on ${it.first}") }
        val mem = entries.filter { it.first == "memory" }.mapNotNull { Regex("app (\\d+) MB · TV free (\\d+) MB").find(it.second)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
        if (mem.isNotEmpty()) line("memory", "app up to ${mem.maxOf { it.first }} MB · TV free down to ${mem.minOf { it.second }} MB")
        line("TV low on memory warnings", count("memory") { it.contains("low on memory") || it.contains("(low)") })
        // The whole-playlist ratings fill, in counts (2.2.1).
        RatingsFill.summaryLine()?.let { line("ratings fill", it) }
        line("ratings fill groups in this report", count("ratings") { it.startsWith("fill: group of") && it.contains("found") })
        // Problems by the settings they happened with.
        val tags = entries.filter { it.first in setOf("ui", "freeze", "error", "crash", "ended") }
            .mapNotNull { Regex("\\[([^\\]]+)\\]\\s*$").find(it.second)?.groupValues?.get(1) }
        tags.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).forEach { line("problems with [${it.key}]", it.value) }
        // The messages you saw, most frequent first.
        entries.filter { it.first == "message" }.map { it.second.substringBefore(" · on ") }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6)
            .forEach { line("message \"${it.key.take(40)}\"", "${it.value}x") }
        return out.append("\n").toString()
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
                    if (event.action == KeyEvent.ACTION_DOWN) lastKeyAt = SystemClock.uptimeMillis()
                    if (enabled && event.action == KeyEvent.ACTION_DOWN && id != lastKeyId) {
                        lastKeyId = id
                        // A held OK on a card: did anything happen (a message, a question, another screen)?
                        if ((event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) && event.repeatCount == 1) watchLongPress(activity)
                        // Mark shortcuts: the red button, or Menu held down (some TVs keep Menu for themselves).
                        // Holding INFO no longer marks (owner, 2026-11-12): in Movies and Series it opens the filter.
                        val markKey = (event.keyCode == KeyEvent.KEYCODE_PROG_RED && event.repeatCount == 0) ||
                            (event.keyCode == KeyEvent.KEYCODE_MENU && event.repeatCount == 1)
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
            remember()
            resumedAt = SystemClock.uptimeMillis()
            movedAlone = 0
            if (!enabled) return
            // Back on a screen: is the highlight where it was when the screen was left?
            leftPlace.remove(activity)?.let { expected ->
                val back = resumedAt
                activity.window.decorView.postDelayed({
                    if (activity.isFinishing || !activity.hasWindowFocus() || lastKeyAt > back) return@postDelayed
                    val now = place(activity.currentFocus)
                    if (now != expected) log("ui", "came back to $screen on another item: should land on $expected, landed on $now")
                }, 2500)
            }
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
            // Nothing highlighted when leaving: an older place must not be expected on return.
            val focus = activity.currentFocus
            if (focus != null && !activity.isFinishing) leftPlace[activity] = place(focus) else leftPlace.remove(activity)
            flushSmallText(screen)
            remember()
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
            if (started == 0) {
                sp.edit().putBoolean("diag_alive", true).apply()
                main.removeCallbacks(memoryTick)
                main.postDelayed(memoryTick, 20_000)
            }
            started++
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                log("app", "went to the background from $screen")
                sp.edit().putBoolean("diag_alive", false).apply()
                main.removeCallbacks(memoryTick)
            }
        }

        private var firstStart = true
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // ---- The session's last known state (for "ended unexpectedly") and memory every 2 minutes ----

    private fun remember() {
        if (!::app.isInitialized) return
        sp.edit().putString("diag_last_screen", screen).putLong("diag_last_seen", System.currentTimeMillis())
            .putString("diag_last_keys", trail(6)).putString("diag_last_tag", settingsTag()).apply()
    }

    /** An update is about to replace the app: its end is expected, not "unexpected". */
    fun expectExit() {
        if (::app.isInitialized) sp.edit().putBoolean("diag_alive", false).commit()
    }

    private var memoryLoggedAt = 0L
    private var memoryLoggedApp = 0L

    private val memoryTick: Runnable = object : Runnable {
        override fun run() {
            if (enabled) io.execute { sampleMemory() }
            main.postDelayed(this, 120_000)
        }
    }

    /**
     * The app's memory and the TV's free memory. Saved every 2 minutes (for "ended unexpectedly");
     * written to the report when it moved by 25 MB or more, when the TV is low, or every 10 minutes.
     */
    private fun sampleMemory() {
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val appMb = android.os.Debug.getPss() / 1024
            val freeMb = info.availMem / (1024 * 1024)
            val text = "app $appMb MB · TV free $freeMb MB${if (info.lowMemory) " (low)" else ""} · on $screen"
            sp.edit().putString("diag_last_mem", text).putLong("diag_last_seen", System.currentTimeMillis())
                .putString("diag_last_keys", trail(6)).apply()
            val now = System.currentTimeMillis()
            if (kotlin.math.abs(appMb - memoryLoggedApp) >= 25 || info.lowMemory || now - memoryLoggedAt >= 600_000) {
                memoryLoggedAt = now
                memoryLoggedApp = appMb
                log("memory", text)
            }
        }
    }

    // ---- Unexpected movements of the highlight (owner, 2026-11-07): where it should be, where it landed ----

    @Volatile var lastKeyAt = 0L
        private set
    @Volatile private var resumedAt = 0L
    private var movedAlone = 0
    /** Where the highlight was when each screen was left. */
    /**
     * Per screen as it stands, not per kind of screen: another title's page, or Settings opened anew,
     * is a different screen and starts where it starts (TV, 2026-11-12: 10 false "came back on another
     * item", every one a newly opened page compared with the last page of its kind).
     */
    private val leftPlace = java.util.WeakHashMap<Activity, String>()

    /**
     * Where the highlight is, in words: "row_favorites, item 2" (plus the title when titles are
     * included in the report) for a card in a list, else the item's name ("tile_live", "play").
     */
    fun place(v: View?): String {
        v ?: return "nothing"
        var child: View = v
        var p = v.parent
        while (p is View && p !is androidx.recyclerview.widget.RecyclerView) { child = p; p = p.parent }
        val list = p as? androidx.recyclerview.widget.RecyclerView ?: return viewName(v)
        val name = if (list.id != View.NO_ID) runCatching { list.resources.getResourceEntryName(list.id) }.getOrDefault("a list") else "a list"
        val pos = list.getChildAdapterPosition(child)
        val text = (child as? ViewGroup)?.findViewById<TextView?>(R.id.title)?.text?.toString()
        return "$name, item ${pos + 1}" + title(text)
    }

    /**
     * The highlight moved with no key pressed (a list refilled, a row hidden…), well after the screen
     * opened: logged up to 5 times per visit to a screen. Not the player (its controls come and go).
     */
    fun movedByItself(old: View, new: View) {
        if (!enabled || screen == "Player") return
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyAt < 1500 || now - resumedAt < 3000 || movedAlone >= 5) return
        movedAlone++
        log("ui", "highlight moved by itself on $screen (no key pressed): should stay on ${place(old)}, landed on ${place(new)}")
    }

    /** A held OK on a card: nothing happened 1.5 s later (no message, no question, no other screen). */
    private fun watchLongPress(activity: Activity) {
        val focus = activity.currentFocus ?: return
        if (place(focus).let { !it.contains(", item ") }) return
        val at = SystemClock.uptimeMillis()
        val where = screen
        val card = place(focus)
        activity.window.decorView.postDelayed({
            when {
                MessagePill.lastShownAt >= at -> Unit                        // a message: it did something
                screen != where -> log("ui", "long-press OK on $where ($card) has no action of its own: it opened $screen like a normal OK")
                !activity.isFinishing && activity.hasWindowFocus() -> log("ui", "long-press OK did nothing on $where ($card)")
            }
        }, 1500)
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
                val slept = SystemClock.elapsedRealtime()
                runCatching { Thread.sleep(1000) }
                // The TV slept, or Android paused the whole app in the background: this thread stood
                // still too. That is not the screen failing to answer (TV, 2026-11-12: a night's sleep
                // was reported as an 11-hour freeze). Start counting again.
                if (SystemClock.elapsedRealtime() - slept > 4000 || started == 0) {
                    beat = SystemClock.elapsedRealtime()
                    stalledSince = 0L
                    continue
                }
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
