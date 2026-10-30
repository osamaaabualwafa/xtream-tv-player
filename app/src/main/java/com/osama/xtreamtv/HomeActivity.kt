package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var accountView: TextView
    private lateinit var expiryWarning: TextView
    private lateinit var continueAdapter: ItemAdapter
    private lateinit var channelsAdapter: ItemAdapter
    private lateinit var favoritesAdapter: ItemAdapter
    private lateinit var recentAdapter: ItemAdapter
    private var expiryShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        setContentView(R.layout.activity_home)
        accountView = findViewById(R.id.account)
        expiryWarning = findViewById(R.id.expiry_warning)
        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME}"

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
        findViewById<TextView>(R.id.tile_search).apply {
            onFocusChangeListener = scaleOnFocus
            setOnClickListener { startActivity(Intent(this@HomeActivity, SearchActivity::class.java)) }
        }
        findViewById<TextView>(R.id.tile_live).requestFocus()

        findViewById<TextView>(R.id.logout).setOnClickListener {
            Session.clear()
            startActivity(PlaylistsActivity.manageIntent(this))
            finish()
        }
        findViewById<View>(R.id.refresh_lists).setOnClickListener { refreshLists() }
        findViewById<View>(R.id.check_updates).setOnClickListener { Updates.checkNow(this, lifecycleScope) }

        continueAdapter = row(R.id.row_continue, R.layout.item_poster_row, { _, item -> resume(item.id) }) { _, item ->
            confirmRemoveProgress(this, prefs, item) { loadLocalRows() }
        }
        channelsAdapter = row(R.id.row_channels, R.layout.item_channel_card, { index, _ ->
            Session.zapList = channelsAdapter.items
            Session.zapTitle = "Favorites"
            startActivity(PlayerActivity.liveIntent(this, index))
        })
        favoritesAdapter = row(R.id.row_favorites, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
        recentAdapter = row(R.id.row_recent, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })

        showAccount()
        loadCounts()
        loadRecentlyAdded()
        Updates.checkQuietly(this, lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        if (!::continueAdapter.isInitialized) return
        loadLocalRows()
        lifecycleScope.launch {
            try {
                Session.userInfo = api.userInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last known info.
            }
            showAccount()
        }
    }

    private fun row(
        viewId: Int,
        layout: Int,
        onClick: (Int, StreamItem) -> Unit,
        onLongClick: ((Int, StreamItem) -> Unit)? = null,
    ): ItemAdapter {
        val adapter = ItemAdapter(grid = layout == R.layout.item_poster_row, onClick = onClick, onLongClick = onLongClick, layout = layout)
        findViewById<RecyclerView>(viewId).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter
        }
        return adapter
    }

    private fun showRow(viewId: Int, titleId: Int, adapter: ItemAdapter, items: List<StreamItem>) {
        adapter.submit(items)
        val visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(viewId).visibility = visibility
        findViewById<View>(titleId).visibility = visibility
    }

    /** Rows stored on the device: Continue watching and favorites. */
    private fun loadLocalRows() {
        val watching = (prefs.continueWatching(ContentType.MOVIE) + prefs.continueWatching(ContentType.SERIES))
            .sortedByDescending { it.updated }
            .map { e ->
                StreamItem(e.type, e.key, listOfNotNull(e.title, e.subtitle).joinToString("  ·  "), e.image, null, e.ext, null, null, note = e.progressText())
            }
        showRow(R.id.row_continue, R.id.row_continue_title, continueAdapter, watching)
        val channels = prefs.favorites(ContentType.LIVE)
        channelsAdapter.favorites = emptySet()
        showRow(R.id.row_channels, R.id.row_channels_title, channelsAdapter, channels)
        val favorites = prefs.favorites(ContentType.MOVIE) + prefs.favorites(ContentType.SERIES)
        showRow(R.id.row_favorites, R.id.row_favorites_title, favoritesAdapter, favorites)
    }

    /** "Live TV" + a small grey "5,537 channels" line on each tile (lists come from the device cache). */
    private fun loadCounts() {
        val tiles = listOf(
            Triple(R.id.tile_live, ContentType.LIVE, "Live TV"),
            Triple(R.id.tile_movies, ContentType.MOVIE, "Movies"),
            Triple(R.id.tile_series, ContentType.SERIES, "Series"),
        )
        for ((id, type, name) in tiles) {
            lifecycleScope.launch {
                try {
                    val key = "${type.name}:__all__"
                    val all = Session.streamCache[key] ?: api.streams(type, null).also { Session.streamCache[key] = it }
                    findViewById<TextView>(id).text = tileText(name, countLabel(type, all.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the plain name.
                }
            }
        }
    }

    private fun tileText(name: String, count: String): CharSequence {
        val text = android.text.SpannableString("$name\n$count")
        val start = name.length + 1
        text.setSpan(android.text.style.RelativeSizeSpan(0.6f), start, text.length, 0)
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, text.length, 0)
        text.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)), start, text.length, 0)
        return text
    }

    private fun loadRecentlyAdded() {
        lifecycleScope.launch {
            try {
                val all = Session.streamCache["MOVIE:__all__"]
                    ?: api.streams(ContentType.MOVIE, null).also { Session.streamCache["MOVIE:__all__"] = it }
                val recent = all.filter { it.added > 0 }.sortedByDescending { it.added }.take(25)
                recentAdapter.favorites = prefs.favoriteIds(ContentType.MOVIE)
                showRow(R.id.row_recent, R.id.row_recent_title, recentAdapter, recent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row just stays hidden.
            }
        }
    }

    private fun resume(key: String) {
        val entry = (prefs.continueWatching(ContentType.MOVIE) + prefs.continueWatching(ContentType.SERIES))
            .firstOrNull { it.key == key } ?: return
        val url = if (entry.type == ContentType.MOVIE) api.movieUrl(entry.id, entry.ext) else api.episodeUrl(entry.id, entry.ext ?: "mp4")
        val title = listOfNotNull(entry.title, entry.subtitle).joinToString("  ·  ")
        startActivity(PlayerActivity.vodIntent(this, url, title, entry.key, entry.position, entry))
    }

    /** Throw away the lists saved on the device so everything reloads from the server. */
    private fun refreshLists() {
        api.clearListCache()
        Session.streamCache.clear()
        Session.categoryCache.clear()
        Toast.makeText(this, "Lists will reload from the server", Toast.LENGTH_SHORT).show()
        loadRecentlyAdded()
    }

    private fun showAccount() {
        val info = Session.userInfo
        val host = api.account.base.substringAfter("://")
        val playlistName = prefs.activePlaylist?.name ?: host
        if (info == null) {
            accountView.text = "$playlistName  ·  ${api.account.username} @ $host"
            return
        }
        val expiry = info.expDate?.let { formatDate(it) } ?: "Unlimited"
        val connections = "${info.activeCons ?: "0"} / ${info.maxConnections ?: "?"}"
        accountView.text = "$playlistName  ·  ${api.account.username} @ $host\n" +
            "Status: ${info.status ?: "Unknown"}   ·   Expires: $expiry   ·   Connections: $connections"
        showExpiryWarning(info.expDate)
    }

    /** Warn when the subscription ends within a week (banner, plus a one-time dialog per launch). */
    private fun showExpiryWarning(expDate: Long?) {
        if (expDate == null) {
            expiryWarning.visibility = View.GONE
            return
        }
        val daysLeft = ((expDate * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt()
        if (daysLeft > 7) {
            expiryWarning.visibility = View.GONE
            return
        }
        val text = when {
            daysLeft <= 0 -> "⚠  Your subscription ends today (${formatDate(expDate)}). Renew it to keep watching."
            daysLeft == 1 -> "⚠  Your subscription ends tomorrow (${formatDate(expDate)})."
            else -> "⚠  Your subscription ends in $daysLeft days (${formatDate(expDate)})."
        }
        expiryWarning.text = text
        expiryWarning.visibility = View.VISIBLE
        if (!expiryShown) {
            expiryShown = true
            AlertDialog.Builder(this)
                .setTitle("Subscription ending soon")
                .setMessage(text.removePrefix("⚠  "))
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
