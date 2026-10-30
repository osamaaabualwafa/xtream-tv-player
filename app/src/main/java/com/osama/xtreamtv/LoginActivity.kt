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
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun addIntent(context: Context) = Intent(context, LoginActivity::class.java)

        fun editIntent(context: Context, playlistId: String) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
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

        // Keyboard up (the screen shrinks above it): the ScrollView keeps the focused field in
        // view; when a field is picked, bring it near the top once so the next one shows too.
        val scroll = findViewById<android.widget.ScrollView>(R.id.login_scroll)
        val inner = scroll.getChildAt(0)
        fun fieldToTop() {
            val v = currentFocus as? EditText ?: return
            val field = IntArray(2).also { v.getLocationOnScreen(it) }
            val area = IntArray(2).also { scroll.getLocationOnScreen(it) }
            // Field near the top of what is visible, with a little space above it.
            scroll.smoothScrollTo(0, scroll.scrollY + field[1] - area[1] - (resources.displayMetrics.density * 24).toInt())
        }
        // Keyboard open = the screen above it gets shorter. Then give the form room below the
        // card (so it can scroll that far) and jump once to the field; also when moving between
        // fields. Keyboard closed: back to the centred card.
        val baseBottom = inner.paddingBottom
        var keyboardOpen = false
        fun setKeyboard(open: Boolean) {
            if (open == keyboardOpen) return
            keyboardOpen = open
            inner.setPadding(inner.paddingLeft, inner.paddingTop, inner.paddingRight, if (open) baseBottom + scroll.height else baseBottom)
            // After Android's own scroll to the field has finished, or it would undo this one.
            if (open) scroll.postDelayed({ fieldToTop() }, 250)
        }
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) scroll.post { setKeyboard(h < scroll.rootView.height * 3 / 4) }
        }
        val toTop = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus && keyboardOpen) scroll.postDelayed({ fieldToTop() }, 250)
        }
        listOf(name, server, username, password).forEach { it.onFocusChangeListener = toTop }

        loginButton.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
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
    }
}
