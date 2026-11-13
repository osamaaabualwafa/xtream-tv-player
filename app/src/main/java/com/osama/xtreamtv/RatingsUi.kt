package com.osama.xtreamtv

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * The ratings screens (1.1E): Add or change key (a QR code for the phone, or typing with the remote),
 * the help card, and Check matching. Built from the same Glass card as the diagnostics screens.
 */
object RatingsUi {
    private const val PORT = 8096
    private const val PHONE_MS = 5 * 60 * 1000L

    // ---- Add or change key ----

    /** Which service a key is for: its name, where to get the key, and how it is tested and kept. */
    class Kind(
        val name: String, val where: String,
        val has: () -> Boolean, val looksLike: (String) -> Boolean,
        val test: suspend (String) -> Boolean?, val save: (String) -> Unit, val worked: () -> String,
        val remove: () -> Unit, val removed: String,
    )

    val MDBLIST = Kind(
        "MDBList", "mdblist.com/preferences → API Access",
        { Ratings.hasKey }, { Ratings.looksLikeKey(it) }, { Ratings.test(it) }, { Ratings.key = it },
        { "Key works" + (Ratings.usage()?.let { " · ${it.used} of ${"%,d".format(it.limit)} lookups used today" } ?: "") },
        { Ratings.removeKey(keepTmdb = true) }, "Ratings are off and the MDBList key is deleted",
    )

    val TMDB = Kind(
        "TMDB", "themoviedb.org → Settings → API → API Key",
        { Tmdb.hasKey }, { Tmdb.looksLikeKey(it) }, { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Tmdb.test(it) } }, { Tmdb.key = it },
        { "Key works · titles without an ID will be recognised by name and year" },
        { Tmdb.removeKey() }, "The TMDB key is deleted",
    )

    /**
     * Tests a key from the phone or the remote and saves it when MDBList accepts it. [say] shows how
     * it went; [onWorks] runs once the key is saved.
     */
    fun checkKey(activity: Activity, candidate: String, from: String, say: (String, Int) -> Unit, kind: Kind = MDBLIST, onWorks: () -> Unit) {
        if (!kind.looksLike(candidate)) {
            say("That doesn't look like ${if (kind === MDBLIST) "an" else "a"} ${kind.name} key. Copy the whole key and try again.", DiagUi.RED)
            return
        }
        say("Testing the key…", DiagUi.BLUE)
        (activity as LifecycleOwner).lifecycleScope.launch {
            when (kind.test(candidate.trim())) {
                true -> {
                    kind.save(candidate.trim())
                    Diag.log("ratings", "${kind.name} key added ($from)")
                    say(kind.worked(), DiagUi.GREEN)
                    onWorks()
                }
                false -> say("${kind.name} didn't accept this key. Check it and send it again.", DiagUi.RED)
                null -> say("Couldn't reach ${kind.name}. Check the TV's internet, then try again.", DiagUi.YELLOW)
            }
        }
    }

    /** [onClose] runs when the window closes (Settings shows the new state). [typing]: opens on typing with the remote. */
    fun keyWindow(activity: Activity, typing: Boolean = false, kind: Kind = MDBLIST, onClose: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var drop: KeyDrop? = null
        var done = false
        lateinit var status: TextView
        lateinit var c: DiagUi.Card

        fun say(text: String, tint: Int) {
            status.text = text
            status.setTextColor(tint)
            status.visibility = View.VISIBLE
            // The phone shows the TV's answer too (owner, 2026-11-13).
            drop?.answer = (when (tint) { DiagUi.GREEN -> true; DiagUi.RED -> false; else -> null }) to text
        }

        fun tryKey(candidate: String, from: String) {
            if (done) return
            checkKey(activity, candidate, from, ::say, kind) {
                done = true
                // A moment longer, so the phone page shows the tick.
                drop?.let { d -> main.postDelayed({ d.stop() }, 12_000) }
                c.body.findViewWithTag<View>("qr_area")?.visibility = View.GONE
                c.body.findViewWithTag<View>("type_area")?.visibility = View.GONE
                val buttons = c.body.findViewWithTag<LinearLayout>("buttons")
                buttons.removeAllViews()
                c.pill(buttons, "Done") { c.dialog.dismiss() }.also { it.post { it.requestFocus() } }
            }
        }

        c = DiagUi.card(activity, 760) { card ->
            card.title(if (kind.has()) "Change your ${kind.name} key" else "Add your ${kind.name} key", R.drawable.ic_glass_key, card.color(R.attr.appAccent))
            card.line("Get it free at ${kind.where}", 13f)

            // The QR code and the three steps beside it.
            val ip = localAddress()
            val token = token()
            val area = card.row(card.dp(14))
            area.tag = "qr_area"
            area.gravity = Gravity.TOP
            if (ip != null && !typing) {
                val url = "http://$ip:$PORT/k/$token"
                area.addView(ImageView(activity).apply {
                    setImageBitmap(qr(url, card.dp(184)))
                    layoutParams = LinearLayout.LayoutParams(card.dp(184), card.dp(184)).apply { rightMargin = card.dp(20) }
                })
                val steps = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                area.addView(steps)
                fun step(n: Int, text: String) = steps.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, if (n == 1) 0 else card.dp(10), 0, 0)
                    addView(TextView(activity).apply {
                        this.text = n.toString()
                        gravity = Gravity.CENTER
                        textSize = 13f
                        setTextColor(card.color(R.attr.appAccent))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor((card.color(R.attr.appAccent) and 0x00FFFFFF) or 0x2E000000)
                        }
                        layoutParams = LinearLayout.LayoutParams(card.dp(26), card.dp(26)).apply { rightMargin = card.dp(12) }
                    })
                    addView(TextView(activity).apply {
                        this.text = text
                        textSize = 15f
                        setTextColor(card.color(R.attr.appText))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })
                step(1, "Scan the code with your phone's camera.")
                step(2, "Paste your key into the page that opens.")
                step(3, "Tap Send to TV. The TV tests it straight away.")
                steps.addView(TextView(activity).apply {
                    text = "No camera? Open $url on a phone or computer on the same Wi-Fi."
                    textSize = 12f
                    setTextColor(card.color(R.attr.appTextSecondary))
                    setPadding(0, card.dp(12), 0, 0)
                })
                steps.addView(TextView(activity).apply {
                    tag = "phone_time"
                    text = "Works once, for 5 minutes, only on your home Wi-Fi."
                    textSize = 12f
                    setTextColor(card.color(R.attr.appTextSecondary))
                    setPadding(0, card.dp(4), 0, 0)
                })
                drop = KeyDrop(token, kind) { key -> main.post { tryKey(key, "phone") } }.also { if (!it.start()) area.visibility = View.GONE }
                // Test builds: the page's address for the test tools (the token only opens this page, for 5 minutes).
                if (BuildConfig.DEBUG) android.util.Log.d("RatingsKey", "page /k/$token")
            } else {
                area.visibility = View.GONE
            }

            // Typing with the remote, shown on request.
            val typeArea = card.row(card.dp(14))
            typeArea.tag = "type_area"
            typeArea.visibility = if (area.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            val field = EditText(activity).apply {
                hint = "Your ${kind.name} key"
                textSize = 16f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(card.color(R.attr.appText))
                setHintTextColor(card.color(R.attr.appTextSecondary))
                background = DiagUi.buttonBackground(activity, 14)
                setPadding(card.dp(16), card.dp(10), card.dp(16), card.dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = card.dp(5) }
                setOnEditorActionListener { v, _, _ -> tryKey(v.text.toString(), "typed"); true }
            }
            typeArea.addView(field)
            card.pill(typeArea, "Save", weight = 0f) { tryKey(field.text.toString(), "typed") }.apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = card.dp(120) }
            }

            status = card.line("", 14f, top = 12).apply { visibility = View.GONE }
            if (!typing && (ip == null || drop == null)) say("The phone page needs the TV on your home Wi-Fi. You can type the key instead.", DiagUi.YELLOW)

            val buttons = card.row(card.dp(16))
            buttons.tag = "buttons"
            var typeButton: TextView? = null
            if (area.visibility == View.VISIBLE) {
                typeButton = card.pill(buttons, "Type key instead") {
                    typeArea.visibility = View.VISIBLE
                    typeButton?.visibility = View.GONE
                    field.requestFocus()
                }
            }
            if (kind.has()) card.pill(buttons, "Remove key") {
                kind.remove()
                Diag.log("ratings", "${kind.name} key removed")
                card.dialog.dismiss()
                showToast(activity, kind.removed, android.widget.Toast.LENGTH_SHORT)
            }.setTextColor(0xFFF2A7A7.toInt())
            val close = card.pill(buttons, "Close") { card.dialog.dismiss() }
            (typeButton ?: field.takeIf { typeArea.visibility == View.VISIBLE } ?: close).let { v -> v.post { v.requestFocus() } }

            card.dialog.setOnDismissListener {
                drop?.stop()
                onClose()
            }
        }

        // The phone page's 5 minutes, then it stops (Close and open again for a new code).
        val started = SystemClock.elapsedRealtime()
        val tick = object : Runnable {
            override fun run() {
                if (done || !c.dialog.isShowing || drop == null) return
                val left = PHONE_MS - (SystemClock.elapsedRealtime() - started)
                val time = c.body.findViewWithTag<TextView>("phone_time")
                if (left <= 0) {
                    drop?.stop()
                    time?.text = "The code has expired. Close this window and open it again for a new one."
                    time?.setTextColor(DiagUi.YELLOW)
                    c.body.findViewWithTag<View>("qr_area")?.alpha = 0.35f
                    return
                }
                time?.text = "Works once, only on your home Wi-Fi · ${left / 60_000}:${"%02d".format((left / 1000) % 60)} left"
                main.postDelayed(this, 1000)
            }
        }
        main.post(tick)
    }

    private fun token(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
        )
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * The phone page: a tiny web page served by the TV on the home Wi-Fi at /k/TOKEN, where the
     * phone pastes the key. Only that address answers; it stops once a key works, after 5 minutes,
     * or when the window closes. The key goes straight to the TV and is never logged.
     */
    private class KeyDrop(private val token: String, private val kind: Kind, private val onKey: (String) -> Unit) {
        @Volatile private var socket: ServerSocket? = null
        /** The TV's answer for the phone page: null while testing, then a tick or a cross. */
        @Volatile var answer: Pair<Boolean?, String>? = null
        private val endsAt = SystemClock.elapsedRealtime() + PHONE_MS

        fun start(): Boolean {
            val s = runCatching { ServerSocket(PORT).apply { soTimeout = 1000 } }.getOrNull() ?: return false
            socket = s
            Thread {
                while (socket === s && SystemClock.elapsedRealtime() < endsAt) {
                    val client = try { s.accept() } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                    runCatching { serve(client) }
                    runCatching { client.close() }
                }
                runCatching { s.close() }
            }.apply { isDaemon = true }.start()
            return true
        }

        fun stop() {
            val s = socket
            socket = null
            runCatching { s?.close() }
        }

        private fun serve(client: java.net.Socket) {
            client.soTimeout = 8000
            val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = input.readLine() ?: return
            var length = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val method = request.substringBefore(' ')
            val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: ""
            val (code, page) = when {
                path == "/k/$token/status" -> "200 OK" to status()
                path != "/k/$token" -> "404 Not Found" to PhonePage.page("Not found", "<p>This address isn't open. On the TV, open Add or change key for a new code.</p>")
                method == "POST" -> {
                    val body = CharArray(length.coerceIn(0, 4096)).let { buf ->
                        var read = 0
                        while (read < buf.size) {
                            val n = input.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buf, 0, read)
                    }
                    val key = body.split('&').firstOrNull { it.startsWith("key=") }?.let { URLDecoder.decode(it.removePrefix("key="), "UTF-8").trim() } ?: ""
                    if (kind.looksLike(key)) {
                        onKey(key)
                        "200 OK" to PhonePage.page("Sent", "<div id=s>${status()}</div><p class=small>The TV's answer shows here.</p>",
                            "const t=setInterval(()=>fetch(location.pathname.replace(/[/]?$/,'/status')).then(r=>r.text()).then(h=>{s.innerHTML=h;if(h.indexOf('res wait')<0)clearInterval(t)}).catch(()=>{}),1500)",
                            symbol = "key", heading = "Sent to your TV")
                    } else {
                        "200 OK" to form("That doesn't look like a key. Copy the whole key and try again.")
                    }
                }
                else -> "200 OK" to form(null)
            }
            val bytes = page.toByteArray(Charsets.UTF_8)
            client.getOutputStream().apply {
                write("HTTP/1.0 $code\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        private fun status(): String {
            val a = answer
            return when (a?.first) {
                true -> PhonePage.result(true, PhonePage.esc(a.second))
                false -> PhonePage.result(false, PhonePage.esc(a.second)) + "<button onclick=\"location.href=location.pathname.replace('/status','')\">Try again</button>"
                null -> "<div class='res wait'>${PhonePage.icon("key")}<span>${PhonePage.esc(a?.second ?: "The TV is testing the key…")}</span></div>"
            }
        }

        private fun form(problem: String?) = PhonePage.page(
            "Send your key",
            "<p>Paste the key from <b>${kind.where.replace("→", "&rarr;")}</b>. It stays on your TV.</p>" +
                (problem?.let { PhonePage.result(false, it) } ?: "") +
                "<form method=post class=card><label>${kind.name} key</label><input name=key placeholder=\"Paste the key here\" autocomplete=off autocapitalize=off autocorrect=off spellcheck=false>" +
                "<button>Send to TV</button></form><p class=small>Works once, for 5 minutes, only on your home Wi-Fi. Nothing goes through the internet.</p>",
            symbol = "key", heading = "${kind.name} key",
        )

    }

    // ---- How it works ----

    /**
     * How it works, in two pages (the size rule: at the biggest text size one page no longer fitted
     * the screen, 2026-11-11): the keys first, then the fill, the library data and good to know.
     */
    fun help(activity: Activity, onAddKey: (() -> Unit)?) {
        DiagUi.card(activity, 880) { c ->
            fun page(second: Boolean) {
                c.clear()
                c.title("Show IMDb and Rotten Tomatoes ratings", R.drawable.ic_glass_star, c.color(R.attr.appAccent))
                c.line(
                    if (!second) "The app gets real scores from MDBList, and works out which movie a title is with TMDB. Both are free, and each needs a free key: a kind of password that lets the app ask."
                    else "Once a key is in, the app fills in the scores by itself.",
                    13f,
                )
                val columns = c.row(c.dp(10))
                columns.gravity = Gravity.TOP
                fun column(right: Int) = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = right }
                    columns.addView(this)
                }
                fun LinearLayout.part(head: String, text: String) {
                    addView(TextView(activity).apply {
                        this.text = head
                        textSize = 14f
                        setTextColor(c.color(R.attr.appText))
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        setPadding(0, c.dp(8), 0, c.dp(2))
                    })
                    addView(TextView(activity).apply {
                        this.text = text
                        textSize = 12.5f
                        setLineSpacing(0f, 1.15f)
                        setTextColor(c.color(R.attr.appTextSecondary))
                    })
                }
                val left = column(c.dp(24))
                val right = column(0)
                if (!second) {
                    left.part("1. MDBList key: the scores (needed)", "On your phone or computer, sign in at mdblist.com, open mdblist.com/preferences → API Access, and copy the key.")
                    left.part("2. TMDB key: titles without an ID (optional)", "Many playlists don't say which movie a title is. With a TMDB key the app works it out from the name, year and poster. Sign in at themoviedb.org, open Settings → API, and copy the API Key (32 letters and digits).")
                    right.part("3. Add a key", "Add key shows a code: scan it with your phone, paste the key, tap Send. Or choose Type key instead and use the remote. The key is tested as soon as it's saved.")
                    right.part("4. Choose what you see", "Show on posters: scores under each poster's name. Off: only on the details page and the Home banner.\nFind by name: a title's own page may search by name and year, accepted only when the poster matches.")
                } else {
                    left.part("Whole playlist", "Scores for every title fill in quietly: only while the remote rests, and slowly during a video. Progress and Status show how far it is. The first time takes a day or more.\nAsk the provider for missing IDs: off, only TMDB is used.")
                    left.part("Library data", "Back up saves everything looked up to one file, with no logins, keys or playlist names in it. Restore adds it on another TV, so that TV need not ask again.")
                    right.part("If something looks wrong", "Wrong scores on a title? Open it and choose Wrong scores: that title is never matched again.\nCheck matching shows how titles were found.\nRefresh saved scores asks for every score again; the Whole playlist progress starts over.")
                    right.part("Good to know", "• MDBList allows 1,000 lookups a day; 100 are kept for your browsing.\n• Scores are kept a week for recent titles, a month for older ones.\n• Both keys stay on this TV. MDBList gets a title's ID; TMDB gets its name and year. Never your login or what you watch.\n• Remove keys lets you choose which key to delete.")
                    c.line("Ratings provided by MDBList. This app uses the TMDB API but is not endorsed or certified by TMDB. IMDb and Rotten Tomatoes scores belong to their owners.", 11.5f, top = 12)
                }
                val buttons = c.row(c.dp(14))
                val turn = c.pill(buttons, if (!second) "Next: filling and good to know" else "Back: the keys") { page(!second) }
                if (!second && onAddKey != null) c.pill(buttons, "Add key now") { c.dialog.dismiss(); onAddKey() }
                c.pill(buttons, "Close") { c.dialog.dismiss() }
                turn.post { turn.requestFocus() }
            }
            page(false)
        }
    }

    // ---- Check matching ----

    fun matching(activity: Activity) {
        val m = Ratings.matching()
        DiagUi.card(activity) { c ->
            c.title("Check matching", R.drawable.ic_glass_star, c.color(R.attr.appAccent))
            c.line("The titles of this playlist looked up so far", 13f)
            val boxes = c.row(c.dp(14))
            c.box(boxes, "By ID", m.byId.toString(), 1f)
            if (m.byTmdb > 0) c.box(boxes, "By TMDB", m.byTmdb.toString(), 1f)
            c.box(boxes, "By name", m.byName.toString(), 1f)
            c.box(boxes, "Not found", m.notFound.toString(), 1f)
            if (m.wrong > 0) c.box(boxes, "Marked wrong", m.wrong.toString(), 1.2f)
            c.note(
                "Not found: the playlist gave no ID, and no title with exactly that name, year and poster was found. Better no score than a wrong one." +
                    if (!Ratings.findByName) " Find by name is off." else "",
                DiagUi.BLUE, top = 14,
            )
            val buttons = c.row(c.dp(14))
            c.pill(buttons, "Close") { c.dialog.dismiss() }.also { it.post { it.requestFocus() } }
        }
    }
}
