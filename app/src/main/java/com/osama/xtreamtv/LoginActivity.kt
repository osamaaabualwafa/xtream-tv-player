package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
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

class LoginActivity : AppCompatActivity() {

    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var loginButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        loginButton = findViewById(R.id.login)
        progress = findViewById(R.id.progress)
        error = findViewById(R.id.error)

        loginButton.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        val saved = Prefs(this).account
        if (saved != null) {
            server.setText(saved.base)
            username.setText(saved.username)
            password.setText(saved.password)
            login(saved, auto = true)
        } else {
            server.requestFocus()
        }
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
        login(Account(parsed.base, user, pass), auto = false)
    }

    private fun login(account: Account, auto: Boolean) {
        setBusy(true)
        error.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val api = XtreamApi(account)
                val info = api.userInfo()
                val problem = accountProblem(info)
                if (problem != null) {
                    setBusy(false)
                    showError(problem)
                    return@launch
                }
                val prefs = Prefs(this@LoginActivity)
                prefs.account = account
                prefs.formats = info.formats.joinToString(",")
                Session.start(api, info)
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false)
                val reason = e.message ?: e.javaClass.simpleName
                showError(if (auto) "Couldn't reconnect: $reason" else "Login failed: $reason")
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginButton.text = if (busy) "" else "Log in"
        loginButton.isEnabled = !busy
        if (!busy) loginButton.requestFocus()
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }
}
