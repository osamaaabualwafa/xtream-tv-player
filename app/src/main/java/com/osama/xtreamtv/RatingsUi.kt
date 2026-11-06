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

    /**
     * Tests a key from the phone or the remote and saves it when MDBList accepts it. [say] shows how
     * it went; [onWorks] runs once the key is saved.
     */
    fun checkKey(activity: Activity, candidate: String, from: String, say: (String, Int) -> Unit, onWorks: () -> Unit) {
        if (!Ratings.looksLikeKey(candidate)) {
            say("That doesn't look like an MDBList key. Copy the whole key and try again.", DiagUi.RED)
            return
        }
        say("Testing the key…", DiagUi.BLUE)
        (activity as LifecycleOwner).lifecycleScope.launch {
            when (Ratings.test(candidate)) {
                true -> {
                    Ratings.key = candidate
                    Diag.log("ratings", "key added ($from)")
                    val u = Ratings.usage()
                    say("Key works" + (u?.let { " · ${it.used} of ${"%,d".format(it.limit)} lookups used today" } ?: ""), DiagUi.GREEN)
                    onWorks()
                }
                false -> say("MDBList didn't accept this key. Check it and send it again.", DiagUi.RED)
                null -> say("Couldn't reach MDBList. Check the TV's internet, then try again.", DiagUi.YELLOW)
            }
        }
    }

    /** [onClose] runs when the window closes (Settings shows the new state). [typing]: opens on typing with the remote. */
    fun keyWindow(activity: Activity, typing: Boolean = false, onClose: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var drop: KeyDrop? = null
        var done = false
        lateinit var status: TextView
        lateinit var c: DiagUi.Card

        fun say(text: String, tint: Int) {
            status.text = text
            status.setTextColor(tint)
            status.visibility = View.VISIBLE
        }

        fun tryKey(candidate: String, from: String) {
            if (done) return
            checkKey(activity, candidate, from, ::say) {
                done = true
                drop?.stop()
                c.body.findViewWithTag<View>("qr_area")?.visibility = View.GONE
                c.body.findViewWithTag<View>("type_area")?.visibility = View.GONE
                val buttons = c.body.findViewWithTag<LinearLayout>("buttons")
                buttons.removeAllViews()
                c.pill(buttons, "Done") { c.dialog.dismiss() }.also { it.post { it.requestFocus() } }
            }
        }

        c = DiagUi.card(activity, 760) { card ->
            card.title(if (Ratings.hasKey) "Change your MDBList key" else "Add your MDBList key", R.drawable.ic_glass_key, card.color(R.attr.appAccent))
            card.line("Get it free at mdblist.com/preferences → API Access", 13f)

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
                drop = KeyDrop(token) { key -> main.post { tryKey(key, "phone") } }.also { if (!it.start()) area.visibility = View.GONE }
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
                hint = "Your MDBList key"
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
            if (Ratings.hasKey) card.pill(buttons, "Remove key") {
                Ratings.removeKey()
                Diag.log("ratings", "key removed")
                card.dialog.dismiss()
                showToast(activity, "Ratings are off and the key is deleted", android.widget.Toast.LENGTH_SHORT)
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
    private class KeyDrop(private val token: String, private val onKey: (String) -> Unit) {
        @Volatile private var socket: ServerSocket? = null
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
                path != "/k/$token" -> "404 Not Found" to page("Not found", "<p>This address isn't open. On the TV, open Add or change key for a new code.</p>")
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
                    if (Ratings.looksLikeKey(key)) {
                        onKey(key)
                        "200 OK" to page("Sent", "<div class=\"big ok\">&#10003;</div><h1>Sent</h1><p>Your TV is testing the key now and shows the result. You can close this page.</p>")
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

        private fun form(problem: String?) = page(
            "Send your key",
            "<h1>Send your MDBList key to the TV</h1><p>Paste the key from <b>mdblist.com/preferences</b> &rarr; <b>API Access</b>.</p>" +
                (problem?.let { "<p class=bad>$it</p>" } ?: "") +
                "<form method=post><input name=key placeholder=\"Paste the key here\" autocomplete=off autocapitalize=off autocorrect=off spellcheck=false>" +
                "<button>Send to TV</button></form><p class=small>Works once, for 5 minutes, only on your home Wi-Fi. The key goes straight to your TV.</p>",
        )

        private fun page(title: String, content: String) = """<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>$title · Xtream TV</title><style>
body{font-family:system-ui,-apple-system,Roboto,sans-serif;background:#f6f7f9;color:#16191d;margin:0;padding:24px 18px;max-width:460px;margin:auto}
.top{font-size:13px;color:#6b7480}h1{font-size:21px;font-weight:600;margin:8px 0}p{font-size:15px;line-height:1.5;color:#3d4550}
input{box-sizing:border-box;width:100%;font-size:17px;padding:14px;border:1px solid #c5ccd4;border-radius:12px;margin:8px 0 12px}
button{width:100%;font-size:17px;font-weight:600;padding:14px;border:0;border-radius:12px;background:#2f6fd6;color:#fff}
.small{font-size:13px;color:#6b7480}.bad{color:#b3261e}.big{font-size:48px;text-align:center;margin-top:30px}.ok{color:#1a7f4b}
</style></head><body><div class=top>Xtream TV &middot; on your TV</div>$content</body></html>"""
    }

    // ---- How it works ----

    fun help(activity: Activity, onAddKey: (() -> Unit)?) {
        DiagUi.card(activity, 880) { c ->
            c.title("Show IMDb and Rotten Tomatoes ratings", R.drawable.ic_glass_star, c.color(R.attr.appAccent))
            c.line("The app gets real scores from MDBList, a free service. It needs a free key from MDBList: a kind of password that lets the app ask for ratings.", 13f)
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
            left.part("1. Get your free key (about 2 minutes)", "On your phone or computer, sign in at mdblist.com, open mdblist.com/preferences → API Access, and copy the key.")
            left.part("2. Add the key", "Add key shows a code: scan it with your phone, paste the key, tap Send. Or choose Type key instead and use the remote. The app tests the key as soon as it's saved.")
            left.part("3. Choose what you see", "Show on posters: scores under each poster's name. Off: only on the details page and the Home banner.\nFind by name: titles without an ID are searched by name and year, and only accepted when the poster matches. Off: IDs only.")
            val right = column(0)
            right.part("If something looks wrong", "Wrong scores on a title? Open it and choose Wrong scores: that title is never matched again.\nCheck matching shows how many titles were found by ID, by name, or not found.\nRefresh saved scores asks again now instead of waiting.")
            right.part("Good to know", "• 1,000 lookups a day. Today shows how many are used; it turns yellow past 800.\n• Scores are kept for a week for this year's and last year's titles, and a month for older ones, so most titles need no new lookup.\n• Your key stays on this TV. MDBList only gets a title's ID, or its name and year, never your playlist login or what you watch.\n• Remove key switches ratings off and deletes the key.")
            c.line("Ratings provided by MDBList. IMDb and Rotten Tomatoes scores belong to their owners.", 11.5f, top = 12)
            val buttons = c.row(c.dp(14))
            val first = if (onAddKey != null) c.pill(buttons, "Add key now") { c.dialog.dismiss(); onAddKey() } else null
            val close = c.pill(buttons, "Close") { c.dialog.dismiss() }
            (first ?: close).let { v -> v.post { v.requestFocus() } }
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
