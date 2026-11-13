package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Add a new playlist, or edit one (EXTRA_PLAYLIST_ID). */
class LoginActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        /** The share of the screen height kept clear of keyboards. */
        private const val KEYBOARD_LINE = 0.52f
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        /** Open with the phone page showing (Playlists: hold OK → Edit on my phone). */
        const val EXTRA_PHONE = "phone"
        /** The phone page for playlists listens here (the ratings keys use 8096). */
        private const val PHONE_PORT = 8097

        fun addIntent(context: Context) = Intent(context, LoginActivity::class.java)

        fun editIntent(context: Context, playlistId: String, phone: Boolean = false) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId).putExtra(EXTRA_PHONE, phone)
    }

    private lateinit var prefs: Prefs
    private lateinit var name: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var loginButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView
    private var editing: Playlist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Appearance.apply(this)
        setContentView(R.layout.activity_login)
        name = findViewById(R.id.name)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        loginButton = findViewById(R.id.login)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        // While the keyboard is open, the card moves only as much as the active field needs: its bottom
        // edge stays above a line in the middle of the screen, where keyboards don't reach, so the fields
        // above it stay in view. With the keyboard closed the card stays where it is.
        val scroll = findViewById<android.widget.ScrollView>(R.id.login_scroll)
        val form = scroll.getChildAt(0)
        var keyboardOpen = false
        fun place() {
            val field = currentFocus as? EditText
            val line = resources.displayMetrics.heightPixels * KEYBOARD_LINE
            val at = IntArray(2)
            val lift = if (!keyboardOpen || field == null) 0f else {
                scroll.scrollTo(0, 0)
                field.getLocationOnScreen(at)
                (at[1] + field.height - form.translationY - line).coerceAtLeast(0f)
            }
            form.animate().translationY(-lift).setDuration(180).start()
        }
        fun setKeyboard(open: Boolean) {
            if (open == keyboardOpen) return
            keyboardOpen = open
            scroll.post { place() }
        }
        val fields = listOf(name, server, username, password)
        fields.forEach { field ->
            (field as KeyboardEditText).onKeyboard = ::setKeyboard
            // The keyboard's Next key moves to the next field with the keyboard still open.
            field.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && keyboardOpen) scroll.post { place() } }
        }
        // Newer Android versions also report the keyboard directly, which catches any other way of closing it.
        var reportedOpen = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val open = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            if (open != reportedOpen) {
                reportedOpen = open
                setKeyboard(open)
            }
            insets
        }
        // Moving away from the fields: the keyboard is gone.
        loginButton.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) setKeyboard(false) }

        loginButton.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                form.animate().translationY(0f).setDuration(180).start()
                submit()
                true
            } else {
                false
            }
        }

        // Show / hide the password (keeps the cursor at the end).
        findViewById<TextView>(R.id.show_password).setOnClickListener { v ->
            val hidden = password.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            password.inputType = InputType.TYPE_CLASS_TEXT or
                if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.setSelection(password.text.length)
            (v as TextView).text = if (hidden) "Hide" else "Show"
        }

        val editId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
        editing = editId?.let { id -> prefs.playlists().firstOrNull { it.id == id } }
        editing?.let { p ->
            findViewById<TextView>(R.id.heading).text = "Edit playlist"
            name.setText(p.name)
            server.setText(p.base)
            username.setText(p.username)
            password.setText(p.password)
            loginButton.text = "Save"
        }
        name.requestFocus()
        addPhoneButton()
        if (intent.getBooleanExtra(EXTRA_PHONE, false)) window.decorView.post { openPhone() }
    }

    // ---- Package 5, item 1 (approved 2026-11-13): add or edit a playlist from the phone ----

    private var phone: PhonePage? = null
    private var phoneCard: DiagUi.Card? = null
    /** What the phone page shows after Send: the TV's answer. */
    @Volatile private var phoneStatus = ""
    /** The phone page's answer: null while the TV checks, then a tick or a cross. */
    @Volatile private var phoneOk: Boolean? = null

    /** "Use my phone" as the first choice, above the fields. */
    private fun addPhoneButton() {
        val parent = name.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        val b = TextView(this).apply {
            // The app's own glass phone icon, in its line style and theme colour, right before the words (owner, 2026-11-13).
            val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_glass_phone)?.mutate()
            text = if (icon == null) "Use Phone" else android.text.SpannableString("   Use Phone").apply {
                val size = (22 * d).toInt()
                icon.setBounds(0, 0, size, size)
                androidx.core.graphics.drawable.DrawableCompat.setTint(icon, themeColor(R.attr.appText))
                val align = if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.ImageSpan.ALIGN_CENTER else android.text.style.ImageSpan.ALIGN_BASELINE
                setSpan(android.text.style.ImageSpan(icon, align), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 17f
            gravity = android.view.Gravity.CENTER
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_glass_choice)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            setOnClickListener { openPhone() }
        }
        parent.addView(b, parent.indexOfChild(name), android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (14 * d).toInt() })
        if (!intent.getBooleanExtra(EXTRA_PHONE, false) && editing == null) b.post { b.requestFocus() }
    }

    private fun openPhone() {
        phone?.stop()
        phoneStatus = ""
        phoneOk = null
        val page = PhonePage(PHONE_PORT, 5 * 60_000L, handle = { path, method, fields, _ -> phoneAnswer(path, method, fields) })
        val url = page.address()
        if (url == null || !page.start()) {
            showError("The phone page needs the TV on your home Wi-Fi. You can type the details here instead.")
            return
        }
        phone = page
        if (BuildConfig.DEBUG) android.util.Log.d("PlaylistPhone", "page /p/${page.token}")
        phoneCard = DiagUi.card(this, 760) { c ->
            c.title(if (editing != null) "Edit this playlist on your phone" else "Add a playlist from your phone", R.drawable.ic_glass_phone, c.color(R.attr.appAccent))
            c.line("Your phone must be on the same Wi-Fi as this TV", 13f)
            val row = c.row(c.dp(14))
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(PhonePage.qr(url, c.dp(184)))
                layoutParams = android.widget.LinearLayout.LayoutParams(c.dp(184), c.dp(184)).apply { rightMargin = c.dp(20) }
            })
            val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            row.addView(col, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun text(t: String, size: Float, color: Int, top: Int = 0) = TextView(this).apply {
                this.text = t; textSize = size; setTextColor(color); setPadding(0, c.dp(top), 0, 0); col.addView(this)
            }
            text("1. Scan the code with your phone's camera.", 15f, c.color(R.attr.appText))
            text("2. Fill in the server, username and password, or paste an M3U link.", 15f, c.color(R.attr.appText), 8)
            text("3. Tap Send to TV. The TV checks the login straight away.", 15f, c.color(R.attr.appText), 8)
            text("No camera? Open $url on a phone on the same Wi-Fi.", 12f, c.color(R.attr.appTextSecondary), 12)
            val timer = text("Works once · 5:00 left", 12f, c.color(R.attr.appTextSecondary), 4)
            val status = c.line("Waiting for your phone…", 15f, top = 12)
            val buttons = c.row(c.dp(16))
            c.pill(buttons, "Type it here instead") { c.dialog.dismiss() }.also { it.post { it.requestFocus() } }
            c.dialog.setOnDismissListener { phone?.stop(); phone = null; phoneCard = null }
            val tick = object : Runnable {
                override fun run() {
                    if (phoneCard?.dialog?.isShowing != true) return
                    val left = (page.endsAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    timer.text = if (left == 0L) "Closed · open it again for a new code" else "Works once · ${left / 60_000}:${"%02d".format((left / 1000) % 60)} left"
                    if (phoneStatus.isNotEmpty()) status.text = phoneStatus
                    window.decorView.postDelayed(this, 1000)
                }
            }
            window.decorView.post(tick)
        }
    }

    /** The phone page: the form (server and username filled when editing, never the password), then the TV's answer. */
    private fun phoneAnswer(path: String, method: String, fields: Map<String, String>): PhonePage.Response {
        if (path == "/status") return PhonePage.Response(phoneResult(), "text/plain; charset=utf-8")
        if (method == "POST") {
            val m3u = fields["m3u"].orEmpty().trim()
            val srv = fields["server"].orEmpty().trim()
            if (m3u.isEmpty() && (srv.isEmpty() || fields["user"].isNullOrBlank() || fields["pass"].isNullOrEmpty())) {
                return PhonePage.Response(phoneForm("Fill in the server, username and password, or paste an M3U link."))
            }
            phoneStatus = "Received · the TV is checking the login…"
            phoneOk = null
            window.decorView.post {
                fields["name"]?.trim()?.takeIf { it.isNotEmpty() }?.let { name.setText(it) }
                server.setText(m3u.ifEmpty { srv })
                if (m3u.isEmpty()) { username.setText(fields["user"].orEmpty().trim()); password.setText(fields["pass"].orEmpty()) }
                Diag.log("account", "playlist details received from the phone")
                submit()
            }
            return PhonePage.Response(PhonePage.page("Sent", "<div id=s>${phoneResult()}</div>" +
                "<p class=small>The TV's answer shows here. Keep this page open for a moment.</p>",
                "const t=setInterval(()=>fetch(location.pathname.replace(/[/]?$/,'/status')).then(r=>r.text()).then(h=>{s.innerHTML=h;if(h.indexOf('res good')>=0)clearInterval(t)}).catch(()=>{}),1500)",
                symbol = "playlist", heading = "Sent to your TV"))
        }
        return PhonePage.Response(phoneForm(null))
    }

    private fun phoneForm(problem: String?): String {
        val e = editing
        fun v(s: String?) = PhonePage.esc(s.orEmpty())
        return PhonePage.page(
            "Add playlist",
            (problem?.let { PhonePage.result(false, it) } ?: "<p>Type the details from your provider. They go straight to your TV.</p>") +
                "<form method=post class=card>" +
                "<label>Name (optional)</label><input name=name value='${v(e?.name)}' autocomplete=off>" +
                "<label>Server</label><input name=server value='${v(e?.base)}' placeholder='http://example.com:8080' autocomplete=off autocapitalize=off autocorrect=off spellcheck=false>" +
                "<label>Username</label><input name=user value='${v(e?.username)}' autocomplete=off autocapitalize=off autocorrect=off spellcheck=false>" +
                "<label>Password</label><input name=pass type=password autocomplete=off>" +
                "<label>Or paste an M3U link</label><input name=m3u placeholder='get.php?username=…&amp;password=…' autocomplete=off autocapitalize=off autocorrect=off spellcheck=false>" +
                "<button>Send to TV</button></form><p class=small>Works once, for 5 minutes, only on your home Wi-Fi. Nothing goes through the internet.</p>",
            symbol = "playlist", heading = if (e != null) "Edit playlist" else "Add a playlist",
        )
    }

    override fun onDestroy() {
        phone?.stop()
        super.onDestroy()
    }

    private fun submit() {
        val parsed = parseServerInput(server.text.toString())
        if (parsed == null) {
            showError("Enter the server address, for example http://example.com:8080")
            return
        }
        // A pasted M3U link carries its own username and password.
        val user = parsed.username ?: username.text.toString().trim()
        val pass = parsed.password ?: password.text.toString()
        server.setText(parsed.base)
        username.setText(user)
        password.setText(pass)
        if (user.isEmpty() || pass.isEmpty()) {
            showError("Enter your username and password")
            return
        }
        val displayName = name.text.toString().trim().ifEmpty { parsed.base.substringAfter("://") }
        // Re-adding the same login updates it instead of creating a duplicate.
        val id = editing?.id
            ?: prefs.playlists().firstOrNull { it.base == parsed.base && it.username == user }?.id
            ?: System.currentTimeMillis().toString(36)
        login(Playlist(id, displayName, parsed.base, user, pass))
    }

    private fun login(playlist: Playlist) {
        setBusy(true)
        error.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val api = XtreamApi(playlist.toAccount(), cacheDir)
                val info = api.userInfo()
                val problem = accountProblem(info)
                if (problem != null) {
                    setBusy(false)
                    showError(problem)
                    return@launch
                }
                prefs.savePlaylist(playlist)
                prefs.activeId = playlist.id
                if (phone != null) { phoneStatus = "Logged in · " + (info.expDate?.let { "expires " + java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(java.util.Date(it * 1000)) } ?: "no expiry date") + ". You can close this page."; phoneOk = true }
                phone?.let { p -> window.decorView.postDelayed({ p.stop() }, 10_000) }
                prefs.formats = info.formats.joinToString(",")
                Session.start(api, info)
                startActivity(
                    Intent(this@LoginActivity, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false)
                showError("Login failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginButton.text = if (busy) "" else if (editing != null) "Save" else "Log in"
        loginButton.isEnabled = !busy
        if (!busy) loginButton.requestFocus()
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
        // Sent from the phone: the answer shows there too, so it can be fixed and sent again.
        if (phone?.running == true) { phoneStatus = message; phoneOk = false }
    }

    /** The phone page's answer box: waiting, a tick, or a cross with Try again. */
    private fun phoneResult(): String = when (phoneOk) {
        true -> PhonePage.result(true, PhonePage.esc(phoneStatus))
        false -> PhonePage.result(false, PhonePage.esc(phoneStatus)) + "<button onclick=\"location.href=location.pathname\">Try again</button>"
        null -> "<div class='res wait'>${PhonePage.icon("phone")}<span>${PhonePage.esc(phoneStatus.ifEmpty { "The TV is checking the login…" })}</span></div>"
    }
}
