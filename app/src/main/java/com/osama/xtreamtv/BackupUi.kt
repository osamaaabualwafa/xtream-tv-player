package com.osama.xtreamtv

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Backup and Restore windows (Package 6, approved 2026-11-13 with pictures): three ways to move
 * (this TV / USB, another TV, the phone), a code or password to protect the file, Merge or Replace when
 * restoring, a summary, and Undo last restore.
 */
object BackupUi {

    /** The screen saves a file through the TV's file picker (or its own folder) and says where, or null when it failed. */
    fun interface FileSaver { fun save(bytes: ByteArray, done: (String?) -> Unit) }

    /** The screen opens a file the person picks, or null when they cancelled. */
    fun interface FileOpener { fun open(done: (ByteArray?) -> Unit) }

    /** The one open transfer page on each port: a new window takes over from an old one still finishing. */
    private val open = HashMap<Int, Transfer.Server>()

    private fun startServer(s: Transfer.Server, port: Int): Boolean {
        synchronized(open) { open.remove(port)?.stop() }
        val ok = s.start() || run { Thread.sleep(300); s.start() }
        if (ok) synchronized(open) { open[port] = s }
        return ok
    }

    // ---- Small pieces ----

    private fun field(c: DiagUi.Card, hint: String, kind: String): EditText = EditText(c.activity).apply {
        this.hint = hint
        textSize = 18f
        isSingleLine = true
        inputType = when (kind) {
            "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            "digits" -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (kind == "code" || kind == "digits") { gravity = Gravity.CENTER; letterSpacing = 0.18f }
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(c.color(R.attr.appText))
        setHintTextColor(c.color(R.attr.appTextSecondary))
        background = DiagUi.buttonBackground(c.activity, 14)
        setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = c.dp(10); leftMargin = c.dp(5); rightMargin = c.dp(5)
        }
        if (kind == "code") addTextChangedListener(CodeDashes(this))
        if (kind == "digits") filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        c.body.addView(this)
    }

    /** The backup code shows as K7P-29Q-M4X while it's typed: no dashes to type, small letters fine. */
    private class CodeDashes(private val f: EditText) : TextWatcher {
        private var busy = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (busy || s == null) return
            val shown = Backup.showCode(Backup.cleanCode(s.toString()).take(9))
            if (shown != s.toString()) {
                busy = true
                f.setText(shown)
                f.setSelection(shown.length)
                busy = false
            }
        }
    }

    private fun stacked(c: DiagUi.Card, name: String, detail: String, top: Int = 10, onClick: () -> Unit): View =
        c.choice(c.row(c.dp(top)), name, detail, onClick)

    private fun big(c: DiagUi.Card, text: String): TextView = TextView(c.activity).apply {
        this.text = text
        textSize = 34f
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        setTextColor(c.color(R.attr.appAccent))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setBackgroundResource(R.drawable.bg_value_chip)
        setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(14) }
        c.body.addView(this)
    }

    private fun focusLater(v: View) = v.post { v.requestFocus() }

    private fun timeLeft(endsAt: Long): String {
        val left = (endsAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return "Works once · ${left / 60_000}:${"%02d".format((left / 1000) % 60)} left"
    }

    private fun failed(a: AppCompatActivity, title: String, message: String) {
        DiagUi.card(a, 600) { c ->
            c.title(title, R.drawable.ic_glass_flag, DiagUi.RED)
            c.line(message, 15f, top = 10)
            focusLater(c.pill(c.row(c.dp(18)), "OK") { c.dialog.dismiss() })
        }
    }

    // ---- Backup ----

    /**
     * Which playlists (owner, 2026-11-13): each by name with a tick, OK ticks or unticks, then Next.
     * Skipped when there is only one.
     */
    private fun choosePlaylists(a: AppCompatActivity, title: String, hint: String, inc: Backup.Incoming, then: (Set<String>) -> Unit) {
        val all = inc.playlists
        if (all.size <= 1) { then(all.map { it.id }.toSet()); return }
        val chosen = LinkedHashSet(all.map { it.id })
        val prefs = Prefs(a)
        DiagUi.card(a, 640) { c ->
            c.title(title, R.drawable.ic_glass_user, c.color(R.attr.appAccent))
            c.line(hint, 13f)
            lateinit var next: TextView
            val names = HashMap<String, TextView>()
            fun show() {
                for (p in all) names[p.id]?.text = (if (p.id in chosen) "☑   " else "☐   ") + (prefs.avatar(p.id)?.let { "$it  " } ?: "") + p.name
                next.text = if (chosen.isEmpty()) "Choose at least one" else "Next · ${chosen.size} playlist${if (chosen.size == 1) "" else "s"}"
            }
            var first: View? = null
            for ((i, p) in all.withIndex()) {
                val (fav, cw) = inc.counts(p)
                val v = stacked(c, p.name, "$fav favourite${if (fav == 1) "" else "s"} · $cw in Continue watching", if (i == 0) 14 else 8) {
                    if (!chosen.remove(p.id)) chosen.add(p.id)
                    show()
                }
                names[p.id] = (v as LinearLayout).getChildAt(0) as TextView
                if (first == null) first = v
            }
            val row = c.row(c.dp(14))
            c.pill(row, "Select all") { chosen.addAll(all.map { it.id }); show() }
            next = c.pill(row, "") { if (chosen.isNotEmpty()) { c.dialog.dismiss(); then(chosen.toSet()) } }
            show()
            first?.let { focusLater(it) }
        }
    }

    fun backupMenu(a: AppCompatActivity, saver: FileSaver) {
        choosePlaylists(a, "Which playlists?", "Press OK to tick or untick. Only the ticked ones go in the backup.", Backup.current(a)) { ids -> backupWays(a, saver, ids) }
    }

    private fun backupWays(a: AppCompatActivity, saver: FileSaver, ids: Set<String>) {
        var library = true
        DiagUi.card(a, 640) { c ->
            c.title("Backup", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            val now = Backup.current(a).only(ids)
            c.line(now.describe() + " (" + now.playlists.joinToString(", ") { it.name } + ")", 13f)
            focusLater(stacked(c, "Save to this TV / USB", "A locked file you can copy to a USB stick", 14) { c.dialog.dismiss(); protect(a, phone = false) { lock, secret -> saveToFile(a, saver, library, ids, lock, secret) } })
            stacked(c, "Send to another TV", "Straight over the home Wi-Fi, with a 6-digit code") { c.dialog.dismiss(); sendToTv(a, BACKUP) { Backup.make(a, library, ids) } }
            stacked(c, "Send to my phone", "Scan a code; the phone keeps the file") { c.dialog.dismiss(); protect(a, phone = true) { lock, secret -> sendToPhone(a, library, ids, lock, secret) } }
            val tick = c.pill(c.row(c.dp(12)), "") { }
            fun show() { tick.text = (if (library) "☑  " else "☐  ") + "Include library data (scores, cast, matches)" }
            show()
            tick.setOnClickListener { library = !library; show() }
            c.note("Never included: the MDBList and TMDB keys, and problem reports.", c.color(R.attr.appTextSecondary), R.drawable.ic_glass_key)
        }
    }

    /** How to protect the file: a code made for you (recommended), your own password, or none. */
    private fun protect(a: AppCompatActivity, phone: Boolean, then: (Backup.Lock, String?) -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title("Protect this backup", R.drawable.ic_glass_key, c.color(R.attr.appAccent))
            if (phone) c.line("The file stays on your phone, so protect it.", 13f)
            focusLater(stacked(c, "Make a code for me (recommended)", "A new code for this backup. You type it when restoring.", 14) { c.dialog.dismiss(); then(Backup.Lock.CODE, Backup.newCode()) })
            stacked(c, "Use my own password", "Typed twice. It can't be recovered if forgotten.") { c.dialog.dismiss(); ownPassword(a) { then(Backup.Lock.PASSWORD, it) } }
            stacked(c, "No protection", "Only stops a casual look: the app's code is public.") { c.dialog.dismiss(); then(Backup.Lock.NONE, null) }
        }
    }

    private fun ownPassword(a: AppCompatActivity, then: (String) -> Unit) {
        DiagUi.card(a, 600) { c ->
            c.title("Your backup password", R.drawable.ic_glass_key, c.color(R.attr.appAccent))
            c.line("At least 4 characters. It can't be recovered if forgotten.", 13f)
            val one = field(c, "Password", "password")
            val two = field(c, "Type it again", "password")
            val problem = c.line("", 14f, top = 8, tint = DiagUi.RED).apply { visibility = View.GONE }
            val row = c.row(c.dp(16))
            c.pill(row, "Cancel") { c.dialog.dismiss() }
            c.pill(row, "Save") {
                val p = one.text.toString()
                when {
                    p.length < 4 -> { problem.text = "Use at least 4 characters."; problem.visibility = View.VISIBLE }
                    p != two.text.toString() -> { problem.text = "The two don't match. Type them again."; problem.visibility = View.VISIBLE }
                    else -> { c.dialog.dismiss(); then(p) }
                }
            }
            focusLater(one)
        }
    }

    /** A small "working" window while the backup is made (the library data can take a few seconds). */
    private fun working(a: AppCompatActivity, text: String): DiagUi.Card = DiagUi.card(a, 520) { c ->
        c.title(text, R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
        c.line("A few seconds…", 14f, top = 8)
        c.dialog.setCancelable(false)
    }

    private fun saveToFile(a: AppCompatActivity, saver: FileSaver, library: Boolean, ids: Set<String>, lock: Backup.Lock, secret: String?) {
        val w = working(a, "Making the backup")
        a.lifecycleScope.launch {
            val bytes = runCatching { withContext(Dispatchers.IO) { Backup.seal(Backup.make(a, library, ids), lock, secret) } }
            w.dialog.dismiss()
            val b = bytes.getOrElse { failed(a, "Backup failed", it.message ?: it.javaClass.simpleName); return@launch }
            saver.save(b) { where ->
                if (where == null) failed(a, "Backup failed", "The file couldn't be saved there.")
                else {
                    Diag.log("backup", "saved to a file · ${lock.name.lowercase()}")
                    saved(a, "Backup saved", if (where.isEmpty()) "Your backup file is saved." else "Saved to:\n$where\n\nCopy this file to restore on another TV.", lock, secret)
                }
            }
        }
    }

    /** After saving: where it went and, with a code, the code (shown once). */
    private fun saved(a: AppCompatActivity, title: String, message: String, lock: Backup.Lock, secret: String?) {
        DiagUi.card(a, 640) { c ->
            c.title(title, R.drawable.ic_glass_refresh, DiagUi.GREEN)
            c.line(message, 14f, top = 8)
            when (lock) {
                Backup.Lock.CODE -> {
                    c.line("Your backup code", 15f, top = 14, tint = c.color(R.attr.appText))
                    big(c, Backup.showCode(secret.orEmpty()))
                    c.line("Write it down or take a photo. You'll need it to restore. It can't be shown again.", 13f, top = 10)
                    focusLater(c.pill(c.row(c.dp(16)), "I've saved the code") { c.dialog.dismiss() })
                }
                Backup.Lock.PASSWORD -> { c.line("Restoring asks for your password.", 13f, top = 10); focusLater(c.pill(c.row(c.dp(16)), "Done") { c.dialog.dismiss() }) }
                else -> { c.line("Without protection the file only stops a casual look.", 13f, top = 10); focusLater(c.pill(c.row(c.dp(16)), "Done") { c.dialog.dismiss() }) }
            }
        }
    }

    // ---- TV to TV ----

    /** What travels: a backup ("b") or library data ("l"); each kind has its own address, so they never mix. */
    private class Kind(val id: String, val otherTv: String, val oldTv: String)
    private val BACKUP = Kind("b", "On the other TV: Playlists → Restore → Receive from another TV.", "On the old TV choose Backup → Send to another TV and type this code.")
    private val LIBRARY = Kind("l", "On the other TV: Settings → Ratings → Library data → Restore → Receive from another TV.",
        "On the old TV choose Settings → Ratings → Library data → Back up → Send to another TV, and type this code.")

    /** Sending TV: the code from the other TV, then [make] (the unlocked bytes) goes over, locked with that code. */
    private fun sendToTv(a: AppCompatActivity, kind: Kind, make: () -> ByteArray) {
        DiagUi.card(a, 640) { c ->
            c.title("Send to another TV", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            c.step(1, kind.otherTv)
            c.step(2, "Type the 6-digit code it shows.")
            val code = field(c, "6-digit code", "digits")
            val status = c.line("", 14f, top = 10).apply { visibility = View.GONE }
            val row = c.row(c.dp(16))
            c.pill(row, "Cancel") { c.dialog.dismiss() }
            lateinit var sendButton: TextView
            var sending = false
            fun say(text: String, tint: Int) { status.text = text; status.setTextColor(tint); status.visibility = View.VISIBLE }
            sendButton = c.pill(row, "Send") {
                val digits = code.text.toString().filter { it.isDigit() }
                if (digits.length != 6) { say("Type the 6 digits from the other TV.", DiagUi.YELLOW); return@pill }
                if (sending) return@pill
                sending = true
                say("Looking for the TV on this Wi-Fi…", c.color(R.attr.appTextSecondary))
                a.lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { Transfer.find(digits, kind.id) }
                    if (found == null) {
                        sending = false
                        say("No TV is showing this code on this Wi-Fi. Check the code, and that both TVs are on the same Wi-Fi.", DiagUi.RED)
                        return@launch
                    }
                    say("Found “${found.second}” · sending…", c.color(R.attr.appText))
                    val answer = runCatching { withContext(Dispatchers.IO) { Transfer.send(found.first, digits, Backup.seal(make(), Backup.Lock.TRANSFER, digits), kind.id) } }
                    sending = false
                    when (answer.getOrNull()) {
                        "ok" -> {
                            Diag.log("backup", "sent to another TV (${kind.id})")
                            say(if (kind === BACKUP) "✓  Sent. The other TV asks Merge or Replace." else "✓  Sent. The other TV is adding the library data.", DiagUi.GREEN)
                            sendButton.visibility = View.GONE
                            (row.getChildAt(0) as TextView).text = "Done"
                            focusLater(row.getChildAt(0))
                        }
                        else -> say("The other TV didn't accept it. Open Receive from another TV there again and use the new code.", DiagUi.RED)
                    }
                }
            }
            code.setOnEditorActionListener { _, _, _ -> sendButton.performClick(); true }
            focusLater(code)
        }
    }

    /** The receiving TV: a 6-digit code; [then] gets the unlocked bytes on the screen's thread. */
    private fun receiveFromTv(a: AppCompatActivity, kind: Kind, then: (ByteArray) -> Unit) {
        if (PhonePage.localAddress() == null) { failed(a, "Receive from another TV", "This needs the TV on your home Wi-Fi."); return }
        val code = Transfer.newTvCode()
        var server: Transfer.Server? = null
        lateinit var card: DiagUi.Card
        server = Transfer.Server(Transfer.TV_PORT, Transfer.tokenFor(code, kind.id)) { r ->
            when (r.path) {
                "/hello" -> Transfer.Reply("xtv-receive:" + Transfer.deviceName(a))
                "/send" -> {
                    val plain = runCatching { Backup.openRaw(r.body, code) }.getOrNull()
                    if (plain == null) Transfer.Reply("bad") else {
                        server?.let { s -> a.window.decorView.postDelayed({ s.stop() }, 2000) }
                        a.runOnUiThread {
                            card.dialog.setOnDismissListener(null)
                            card.dialog.dismiss()
                            Diag.log("backup", "received from another TV (${kind.id})")
                            then(plain)
                        }
                        Transfer.Reply("ok")
                    }
                }
                else -> Transfer.Reply("")
            }
        }
        if (!startServer(server!!, Transfer.TV_PORT)) { failed(a, "Receive from another TV", "Couldn't open the receiving page. Try again in a moment."); return }
        card = DiagUi.card(a, 600) { c ->
            c.title("Receive from another TV", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            big(c, code.substring(0, 3) + " " + code.substring(3))
            c.line(kind.oldTv, 15f, top = 12, tint = c.color(R.attr.appText))
            val time = c.line(timeLeft(server!!.endsAt), 13f, top = 8)
            focusLater(c.pill(c.row(c.dp(16)), "Cancel") { c.dialog.dismiss() })
            c.dialog.setOnDismissListener { server?.stop() }
            val tick = object : Runnable {
                override fun run() {
                    if (!c.dialog.isShowing) return
                    time.text = if (server?.running == true) timeLeft(server!!.endsAt) else "The code has expired. Close and open it again for a new one."
                    a.window.decorView.postDelayed(this, 1000)
                }
            }
            a.window.decorView.post(tick)
        }
    }

    // ---- Phone ----

    private fun qrRow(c: DiagUi.Card, url: String, lines: List<String>): TextView {
        val row = c.row(c.dp(14))
        row.addView(ImageView(c.activity).apply {
            setImageBitmap(PhonePage.qr(url, c.dp(180)))
            layoutParams = LinearLayout.LayoutParams(c.dp(180), c.dp(180)).apply { rightMargin = c.dp(18) }
        })
        val text = TextView(c.activity).apply {
            text = lines.joinToString("\n")
            textSize = 15f
            setTextColor(c.color(R.attr.appText))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(text)
        return text
    }

    private fun sendToPhone(a: AppCompatActivity, library: Boolean, ids: Set<String>, lock: Backup.Lock, secret: String?) {
        val w = working(a, "Making the backup")
        a.lifecycleScope.launch {
            val made = runCatching { withContext(Dispatchers.IO) { Backup.make(a, library, ids) } }
            w.dialog.dismiss()
            val plain = made.getOrElse { failed(a, "Backup failed", it.message ?: ""); return@launch }
            val bytes = withContext(Dispatchers.IO) { Backup.seal(plain, lock, secret) }
            val summary = runCatching { Backup.parsePlain(plain).describe() }.getOrDefault("")
            val name = Backup.fileName()
            val locked = when (lock) {
                Backup.Lock.CODE -> "Locked with a code. After saving, the TV shows it: write it down."
                Backup.Lock.PASSWORD -> "Locked with your password."
                else -> "Locked without protection."
            }
            phoneDownload(a, bytes, name, summary, locked) { saved(a, "Saved on your phone", "The file is on your phone ($name).", lock, secret) }
        }
    }

    /** The phone downloads [bytes] as [name]; [after] runs on the TV once it has. */
    private fun phoneDownload(a: AppCompatActivity, bytes: ByteArray, name: String, summary: String, note: String, after: () -> Unit) {
        val got = java.util.concurrent.atomic.AtomicBoolean(false)
        val token = PhonePage.newToken()
        var server: Transfer.Server? = null
        server = Transfer.Server(Transfer.PHONE_PORT, token) { r ->
            when (r.path) {
                "/file" -> {
                    got.set(true)
                    server?.let { s -> a.window.decorView.postDelayed({ s.stop() }, 30_000) }
                    Transfer.Reply(bytes, "application/octet-stream", "Content-Disposition: attachment; filename=\"$name\"\r\n")
                }
                else -> Transfer.Reply(PhonePage.page("Save to phone",
                    "<div class=card><p><b>${PhonePage.esc(summary)}</b></p><p>${PhonePage.esc(note)}</p>" +
                        "<a href='file' download='$name' style='text-decoration:none'><button>Save to phone</button></a></div>" +
                        "<p class=small>Works once, for 5 minutes, only on your home Wi-Fi. Nothing goes through the internet.</p>",
                    symbol = "phone", heading = "Save to phone"), "text/html; charset=utf-8")
            }
        }
        val url = PhonePage.localAddress()?.let { "http://$it:${Transfer.PHONE_PORT}/p/$token/" }
        if (url == null) { failed(a, "Send to my phone", "This needs the TV on your home Wi-Fi."); return }
        if (!startServer(server!!, Transfer.PHONE_PORT)) { failed(a, "Send to my phone", "Couldn't open the phone page. Try again in a moment."); return }
        // Test builds: the page's path for the test tools (works once, 5 minutes).
        if (BuildConfig.DEBUG) android.util.Log.d("BackupPhone", "page /p/$token/")
        DiagUi.card(a, 700) { c ->
            c.title("Send to my phone", R.drawable.ic_glass_phone, c.color(R.attr.appAccent))
            val text = qrRow(c, url, listOf("Scan with your phone's camera.", "Tap Save to phone.", "Same Wi-Fi as this TV."))
            val time = c.line(timeLeft(server!!.endsAt), 13f, top = 10)
            focusLater(c.pill(c.row(c.dp(16)), "Close") { c.dialog.dismiss() })
            c.dialog.setOnDismissListener { if (!got.get()) server?.stop() }
            val tick = object : Runnable {
                override fun run() {
                    if (!c.dialog.isShowing) return
                    if (got.get()) {
                        Diag.log("backup", "saved to the phone")
                        c.dialog.setOnDismissListener(null)
                        c.dialog.dismiss()
                        after()
                        return
                    }
                    time.text = if (server?.running == true) timeLeft(server!!.endsAt) else "The code has expired. Close and choose Send to my phone again."
                    text.alpha = if (server?.running == true) 1f else 0.4f
                    a.window.decorView.postDelayed(this, 1000)
                }
            }
            a.window.decorView.post(tick)
        }
    }

    /**
     * The phone sends a file: [accept] checks it's the right kind (null = fine, else the reason for the phone);
     * [then] gets it on the screen's thread, with the code or password typed on the phone (when [askSecret]).
     */
    private fun phoneUpload(a: AppCompatActivity, title: String, askSecret: Boolean, nextStep: String, accept: (ByteArray) -> String?, then: (ByteArray, String?) -> Unit) {
        val token = PhonePage.newToken()
        lateinit var card: DiagUi.Card
        var server: Transfer.Server? = null
        server = Transfer.Server(Transfer.PHONE_PORT, token) { r ->
            if (r.path == "/send" && r.method == "POST") {
                val problem = runCatching { accept(r.body) }.getOrElse { "That isn't the right kind of file." }
                if (problem != null) Transfer.Reply(problem) else {
                    val secret = r.headers["x-xtv-secret"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }
                    server?.let { s -> a.window.decorView.postDelayed({ s.stop() }, 2000) }
                    a.runOnUiThread {
                        card.dialog.setOnDismissListener(null)
                        card.dialog.dismiss()
                        Diag.log("backup", "received from the phone")
                        then(r.body, secret)
                    }
                    Transfer.Reply("ok")
                }
            } else Transfer.Reply(PhonePage.page("Send to TV",
                "<div class=card><label>File</label><input type=file id=f>" +
                    (if (askSecret) "<label>Code or password (if it has one)</label><input id=k autocomplete=off autocapitalize=characters autocorrect=off spellcheck=false placeholder='K7P-29Q-M4X'>" else "<input id=k type=hidden>") +
                    "<button onclick=go()>Send to TV</button></div><div id=s></div>" +
                    "<p class=small>Works once, for 5 minutes, only on your home Wi-Fi. Nothing goes through the internet.</p>",
                """async function go(){const file=f.files[0];if(!file){s.innerHTML=`${PhonePage.result(false, "Choose the file first.")}`;return}
s.innerHTML="<div class='res wait'>${PhonePage.icon("phone")}<span>Sending…</span></div>";
try{const r=await fetch(location.pathname.replace(/[/]?$/,'/send'),{method:'POST',headers:{'X-Xtv-Secret':encodeURIComponent(k.value)},body:file});const t=await r.text();
s.innerHTML=t=='ok'?`${PhonePage.result(true, PhonePage.esc(nextStep))}`:`${PhonePage.result(false, "")}`.replace('</span>',t+'</span>')}catch(e){s.innerHTML=`${PhonePage.result(false, "Can’t reach the TV. Is it on the same Wi-Fi?")}`}}""",
                symbol = "phone", heading = title), "text/html; charset=utf-8")
        }
        val url = PhonePage.localAddress()?.let { "http://$it:${Transfer.PHONE_PORT}/p/$token/" }
        if (url == null) { failed(a, "From my phone", "This needs the TV on your home Wi-Fi."); return }
        if (!startServer(server!!, Transfer.PHONE_PORT)) { failed(a, "From my phone", "Couldn't open the phone page. Try again in a moment."); return }
        if (BuildConfig.DEBUG) android.util.Log.d("BackupPhone", "page /p/$token/")
        card = DiagUi.card(a, 700) { c ->
            c.title("From my phone", R.drawable.ic_glass_phone, c.color(R.attr.appAccent))
            qrRow(c, url, listOf("Scan with your phone's camera.", "Choose the file.", "Tap Send to TV."))
            val time = c.line(timeLeft(server!!.endsAt), 13f, top = 10)
            focusLater(c.pill(c.row(c.dp(16)), "Cancel") { c.dialog.dismiss() })
            c.dialog.setOnDismissListener { server?.stop() }
            val tick = object : Runnable {
                override fun run() {
                    if (!c.dialog.isShowing) return
                    time.text = if (server?.running == true) timeLeft(server!!.endsAt) else "The code has expired. Close and open it again."
                    a.window.decorView.postDelayed(this, 1000)
                }
            }
            a.window.decorView.post(tick)
        }
    }

    // ---- Library data (Settings → Ratings): the same three ways, no code (it holds no logins) ----

    private fun libraryBytes(a: AppCompatActivity): ByteArray {
        val tmp = File(a.cacheDir, "library-move.tmp")
        try { Library.export(a, tmp); return tmp.readBytes() } finally { tmp.delete() }
    }

    /** A library data file starts like every SQLite database. */
    private fun isLibrary(b: ByteArray) = b.size > 16 && String(b, 0, 15, Charsets.ISO_8859_1) == "SQLite format 3"

    /** Settings → Library data → Back up: this TV / USB ([saveFile]), another TV, or the phone. */
    fun libraryBackupMenu(a: AppCompatActivity, saveFile: () -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title("Back up library data", R.drawable.ic_glass_download, c.color(R.attr.appAccent))
            c.line("Scores, cast and which title is which. No logins, addresses or keys.", 13f)
            focusLater(stacked(c, "Save to this TV / USB", "A file you can copy to a USB stick", 14) { c.dialog.dismiss(); saveFile() })
            stacked(c, "Send to another TV", "Straight over the home Wi-Fi, with a 6-digit code") { c.dialog.dismiss(); sendToTv(a, LIBRARY) { libraryBytes(a) } }
            stacked(c, "Send to my phone", "Scan a code; the phone keeps the file") {
                c.dialog.dismiss()
                val w = working(a, "Getting the library data ready")
                a.lifecycleScope.launch {
                    val bytes = runCatching { withContext(Dispatchers.IO) { libraryBytes(a) } }
                    w.dialog.dismiss()
                    val b = bytes.getOrElse { failed(a, "Back up failed", it.message ?: ""); return@launch }
                    val (titles, works) = runCatching { Library.sizes() }.getOrDefault(0 to 0)
                    phoneDownload(a, b, Library.fileName(), "Library data · ${"%,d".format(titles)} titles · ${"%,d".format(works)} movies and series",
                        "No logins, addresses or keys are in this file.") {
                        DiagUi.card(a, 560) { d ->
                            d.title("Saved on your phone", R.drawable.ic_glass_download, DiagUi.GREEN)
                            d.line("On another TV: Settings → Ratings → Library data → Restore → From my phone.", 14f, top = 8)
                            focusLater(d.pill(d.row(d.dp(16)), "Done") { d.dialog.dismiss() })
                        }
                    }
                }
            }
        }
    }

    /** Settings → Library data → Restore: from this TV / USB ([pickFile]), another TV or the phone; [restore] adds it. */
    fun libraryRestoreMenu(a: AppCompatActivity, pickFile: () -> Unit, restore: (ByteArray) -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title("Restore library data", R.drawable.ic_glass_file, c.color(R.attr.appAccent))
            c.line("Only adds: nothing on this TV is removed, and Undo puts it back.", 13f)
            focusLater(stacked(c, "From this TV / USB", "A library data file on this TV or a USB stick", 14) { c.dialog.dismiss(); pickFile() })
            stacked(c, "Receive from another TV", "This TV shows a code; type it on the other TV") {
                c.dialog.dismiss()
                receiveFromTv(a, LIBRARY) { plain -> if (isLibrary(plain)) restore(plain) else failed(a, "Can't restore", "That wasn't library data.") }
            }
            stacked(c, "From my phone", "Scan a code and send the file from your phone") {
                c.dialog.dismiss()
                phoneUpload(a, "Send library data", askSecret = false, nextStep = "The TV is adding the library data.",
                    accept = { if (isLibrary(it)) null else "That isn't a library data file (.xtlib)." }) { bytes, _ -> restore(bytes) }
            }
        }
    }

    // ---- Restore ----

    fun restoreMenu(a: AppCompatActivity, opener: FileOpener, done: () -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title("Restore", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            c.line("Next you choose Merge (keep everything) or Replace.", 13f)
            focusLater(stacked(c, "From this TV / USB", "A backup file on this TV or a USB stick", 14) {
                c.dialog.dismiss()
                opener.open { bytes -> if (bytes != null) continueRestore(a, bytes, null, done) }
            })
            stacked(c, "Receive from another TV", "This TV shows a code; type it on the other TV") {
                c.dialog.dismiss()
                receiveFromTv(a, BACKUP) { plain ->
                    val inc = runCatching { Backup.parsePlain(plain) }.getOrNull()
                    if (inc == null) failed(a, "Can't restore", "That wasn't an Xtream TV backup.") else chooseMode(a, inc, old = false, done = done)
                }
            }
            stacked(c, "From my phone", "Scan a code and send the file from your phone") {
                c.dialog.dismiss()
                phoneUpload(a, "Send a backup", askSecret = true, nextStep = "The TV has it. Choose Merge or Replace there.",
                    accept = { b -> runCatching { Backup.lockOf(b); null }.getOrDefault("That isn't an Xtream TV backup file.") }) { b, secret -> continueRestore(a, b, secret, done) }
            }
        }
    }

    /** A backup's bytes: opened (asking for the code or password when needed), then Merge or Replace. */
    fun continueRestore(a: AppCompatActivity, bytes: ByteArray, secret: String?, done: () -> Unit) {
        val lock = try { Backup.lockOf(bytes) } catch (e: Exception) { failed(a, "Can't restore", e.message ?: "This isn't an Xtream TV backup file."); return }
        if (lock == null || lock == Backup.Lock.NONE) {
            val inc = runCatching { Backup.open(bytes, null) }.getOrElse { failed(a, "Can't restore", it.message ?: ""); return }
            chooseMode(a, inc, old = lock == null, done = done)
            return
        }
        a.lifecycleScope.launch {
            val first = secret?.let { s -> withContext(Dispatchers.Default) { runCatching { Backup.open(bytes, s) }.getOrNull() } }
            if (first != null) chooseMode(a, first, old = false, done = done) else askSecret(a, bytes, lock, done)
        }
    }

    private fun askSecret(a: AppCompatActivity, bytes: ByteArray, lock: Backup.Lock, done: () -> Unit) {
        val code = lock == Backup.Lock.CODE
        DiagUi.card(a, 600) { c ->
            c.title(if (code) "Enter the backup code" else "Enter your backup password", R.drawable.ic_glass_key, c.color(R.attr.appAccent))
            c.line(if (code) "The code shown when this backup was made. No dashes needed." else "The password set when this backup was made.", 13f)
            val f = field(c, if (code) "K7P-29Q-M4X" else "Password", if (code) "code" else "password")
            val problem = c.line("", 14f, top = 8, tint = DiagUi.RED).apply { visibility = View.GONE }
            val row = c.row(c.dp(16))
            c.pill(row, "Cancel") { c.dialog.dismiss() }
            var trying = false
            val ok = c.pill(row, "OK") {
                if (trying) return@pill
                trying = true
                problem.visibility = View.GONE
                a.lifecycleScope.launch {
                    val inc = withContext(Dispatchers.Default) { runCatching { Backup.open(bytes, f.text.toString()) }.getOrNull() }
                    trying = false
                    if (inc == null) {
                        problem.text = if (code) "Wrong code · nothing changed. Try again." else "Wrong password · nothing changed. Try again."
                        problem.visibility = View.VISIBLE
                    } else { c.dialog.dismiss(); chooseMode(a, inc, old = false, done = done) }
                }
            }
            f.setOnEditorActionListener { _, _, _ -> ok.performClick(); true }
            focusLater(f)
        }
    }

    /** Restore: which of the backup's playlists to bring (all ticked), then Merge or Replace. */
    private fun chooseMode(a: AppCompatActivity, whole: Backup.Incoming, old: Boolean, done: () -> Unit) {
        choosePlaylists(a, "Which playlists to restore?", "Press OK to tick or untick. Only the ticked ones are restored.", whole) { ids ->
            mergeOrReplace(a, whole.only(ids), old, done)
        }
    }

    private fun mergeOrReplace(a: AppCompatActivity, inc: Backup.Incoming, old: Boolean, done: () -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title("How should this backup be used?", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            c.line(inc.describe(), 13f)
            focusLater(stacked(c, "Merge (recommended)", "Keep everything on this TV and add the backup's.", 14) { c.dialog.dismiss(); mergeQuestions(a, inc, old, done) })
            stacked(c, "Replace", "This TV becomes an exact copy of the backup.") { c.dialog.dismiss(); confirmReplace(a, inc, old, done) }
            c.line("Merge only adds: something deleted on one TV comes back from the other.", 13f, top = 12)
        }
    }

    private fun confirmReplace(a: AppCompatActivity, inc: Backup.Incoming, old: Boolean, done: () -> Unit) {
        DiagUi.card(a, 600) { c ->
            c.title("Replace everything?", R.drawable.ic_glass_flag, DiagUi.RED)
            c.line("This TV's playlists, favourites, Continue watching and settings are replaced by the backup's. You can undo this with Undo restore.", 15f, top = 10)
            val row = c.row(c.dp(18))
            focusLater(c.pill(row, "Cancel") { c.dialog.dismiss() })
            c.pill(row, "Replace") { c.dialog.dismiss(); apply(a, inc, old, done) { Backup.replace(a, inc) } }.setTextColor(0xFFF2A7A7.toInt())
        }
    }

    /** Merge asks only what differs: the settings once, then each playlist whose login differs. */
    private fun mergeQuestions(a: AppCompatActivity, inc: Backup.Incoming, old: Boolean, done: () -> Unit) {
        val q = Backup.questions(a, inc)
        val useLogin = HashSet<String>()
        fun logins(i: Int, useSettings: Boolean) {
            if (i >= q.loginConflicts.size) { apply(a, inc, old, done) { Backup.merge(a, inc, useSettings, useLogin) }; return }
            val (tv, bk) = q.loginConflicts[i]
            DiagUi.card(a, 600) { c ->
                c.title("“${tv.name}” has a different login", R.drawable.ic_glass_key, c.color(R.attr.appAccent))
                c.line("This TV and the backup have different login details for this playlist.", 13f)
                focusLater(stacked(c, "Keep this TV's login", "Nothing changes for this playlist's login", 14) { c.dialog.dismiss(); logins(i + 1, useSettings) })
                stacked(c, "Use the backup's login", "The backup's server, username and password") { c.dialog.dismiss(); useLogin.add(bk.id); logins(i + 1, useSettings) }
            }
        }
        if (!q.settingsDiffer) { logins(0, false); return }
        DiagUi.card(a, 600) { c ->
            c.title("Settings", R.drawable.ic_glass_spark, c.color(R.attr.appAccent))
            c.line("Theme, player and look differ between this TV and the backup.", 13f)
            focusLater(stacked(c, "Keep this TV's settings", "Only playlists and what you watched are merged", 14) { c.dialog.dismiss(); logins(0, false) })
            stacked(c, "Use the backup's settings", "This TV takes the backup's theme, player and look") { c.dialog.dismiss(); logins(0, true) }
        }
    }

    private fun apply(a: AppCompatActivity, inc: Backup.Incoming, old: Boolean, done: () -> Unit, work: () -> Backup.Result) {
        val w = working(a, "Restoring")
        a.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work() } }
            w.dialog.dismiss()
            val r = result.getOrElse { failed(a, "Restore failed", it.message ?: it.javaClass.simpleName); return@launch }
            Diag.log("backup", if (r.replaced) "restored (replace)" else "restored (merge) · +${r.playlistsAdded} playlists · +${r.favouritesAdded} favourites · ${r.placesUpdated} places")
            done()
            summary(a, inc, r, old, done)
        }
    }

    private fun summary(a: AppCompatActivity, inc: Backup.Incoming, r: Backup.Result, old: Boolean, done: () -> Unit) {
        DiagUi.card(a, 640) { c ->
            c.title(if (r.replaced) "Backup restored" else "Backup merged", R.drawable.ic_glass_refresh, DiagUi.GREEN)
            for (l in r.lines()) c.line("•  $l", 15f, top = 6, tint = c.color(R.attr.appText))
            if (!r.replaced) c.line("Nothing on this TV was removed.", 13f, top = 10)
            val lib = if (inc.library != null) c.line("Adding library data…", 13f, top = 10) else null
            if (old) c.note("This was an old unprotected backup file. Make a new protected one with Backup.", DiagUi.YELLOW, R.drawable.ic_glass_key)
            c.note("On a friend's TV? Remove your playlist when you leave: Playlists → hold OK → Delete.", c.color(R.attr.appAccent), R.drawable.ic_glass_user)
            val row = c.row(c.dp(16))
            focusLater(c.pill(row, "Done") { c.dialog.dismiss() })
            c.pill(row, "Undo restore") {
                c.dialog.dismiss()
                if (Backup.undo(a)) { showToast(a, "Restore undone", android.widget.Toast.LENGTH_SHORT); done() }
            }
            val data = inc.library
            if (lib != null && data != null) a.lifecycleScope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        val tmp = File(a.cacheDir, "restore-library.tmp").apply { writeBytes(data) }
                        try { Library.import(a, tmp, { _, _ -> }, { false }) } finally { tmp.delete() }
                    }
                    "Library data added"
                }.getOrElse { "Library data couldn't be added: ${it.message ?: ""}" }
                lib.text = text
            }
        }
    }

    /** Playlists → Undo restore. */
    fun confirmUndo(a: AppCompatActivity, done: () -> Unit) {
        DiagUi.card(a, 600) { c ->
            c.title("Undo last restore?", R.drawable.ic_glass_refresh, c.color(R.attr.appAccent))
            c.line("This TV goes back to how it was just before the last restore.", 15f, top = 10)
            val row = c.row(c.dp(18))
            focusLater(c.pill(row, "Cancel") { c.dialog.dismiss() })
            c.pill(row, "Undo") {
                c.dialog.dismiss()
                val ok = runCatching { Backup.undo(a) }.getOrDefault(false)
                showToast(a, if (ok) "Restore undone" else "Nothing to undo", android.widget.Toast.LENGTH_SHORT)
                done()
            }
        }
    }
}
