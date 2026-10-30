package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var accountView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = Session.api(this) ?: return goToLogin()
        setContentView(R.layout.activity_home)
        accountView = findViewById(R.id.account)

        val tiles = mapOf(
            R.id.tile_live to ContentType.LIVE,
            R.id.tile_movies to ContentType.MOVIE,
            R.id.tile_series to ContentType.SERIES,
        )
        for ((id, type) in tiles) {
            val tile = findViewById<TextView>(id)
            tile.onFocusChangeListener = scaleOnFocus
            tile.setOnClickListener {
                startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, type.name))
            }
        }
        findViewById<TextView>(R.id.tile_live).requestFocus()

        findViewById<TextView>(R.id.logout).setOnClickListener {
            Prefs(this).account = null
            Session.clear()
            goToLogin()
        }

        showAccount(api)
    }

    override fun onResume() {
        super.onResume()
        val api = Session.api(this) ?: return
        lifecycleScope.launch {
            try {
                Session.userInfo = api.userInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last known info.
            }
            showAccount(api)
        }
    }

    private fun showAccount(api: XtreamApi) {
        val info = Session.userInfo
        val host = api.account.base.substringAfter("://")
        if (info == null) {
            accountView.text = "${api.account.username} @ $host"
            return
        }
        val expiry = info.expDate?.let { formatDate(it) } ?: "Unlimited"
        val connections = "${info.activeCons ?: "0"} / ${info.maxConnections ?: "?"}"
        accountView.text = "${api.account.username} @ $host\n" +
            "Status: ${info.status ?: "Unknown"}   ·   Expires: $expiry   ·   Connections: $connections"
    }
}
