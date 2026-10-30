package com.osama.xtreamtv

import android.content.Intent
import android.content.res.ColorStateList
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var accountView: TextView
    private lateinit var expiryWarning: TextView
    private lateinit var clock: TextView
    /** One download per full list, shared by the tile counts and "Recently added". */
    private val allLists = HashMap<ContentType, Deferred<List<StreamItem>>>()
    private val clockTicker = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            // Next tick right after the minute changes.
            clock.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }
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
        clock = findViewById(R.id.clock)
        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME}"

        val tiles = mapOf(
            R.id.tile_live to ContentType.LIVE,
            R.id.tile_movies to ContentType.MOVIE,
            R.id.tile_series to ContentType.SERIES,
        )
        for ((id, type) in tiles) {
            val tile = findViewById<View>(id)
            tile.onFocusChangeListener = scaleOnFocus
            tile.setOnClickListener {
                startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, type.name))
            }
        }
        findViewById<View>(R.id.tile_search).apply {
            onFocusChangeListener = scaleOnFocus
            setOnClickListener { startActivity(Intent(this@HomeActivity, SearchActivity::class.java)) }
        }
        findViewById<View>(R.id.tile_live).requestFocus()

        val openPlaylists = View.OnClickListener {
            Session.clear()
            startActivity(PlaylistsActivity.manageIntent(this))
            finish()
        }
        findViewById<View>(R.id.logout).setOnClickListener(openPlaylists)
        findViewById<View>(R.id.playlist_chip).setOnClickListener(openPlaylists)
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
        clock.removeCallbacks(clockTicker)
        clockTicker.run()
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

    override fun onPause() {
        super.onPause()
        if (::clock.isInitialized) clock.removeCallbacks(clockTicker)
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

    /**
     * The whole list of one type, downloaded once (the tile count and "Recently added" both
     * need the movie list; two parallel downloads of a big list could fail or time out).
     */
    private fun allStreams(type: ContentType): Deferred<List<StreamItem>> {
        allLists[type]?.let { if (!it.isCancelled) return it } // a failed download is tried again
        val key = "${type.name}:__all__"
        return lifecycleScope.async {
            Session.streamCache[key] ?: run {
                // One retry: big lists sometimes fail on the first try.
                val list = try {
                    api.streams(type, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    api.streams(type, null)
                }
                list.also { Session.streamCache[key] = it }
            }
        }.also { allLists[type] = it }
    }

    /** "5,537 channels" under each tile's name (lists come from the device cache). */
    private fun loadCounts() {
        val tiles = listOf(
            R.id.tile_live_count to ContentType.LIVE,
            R.id.tile_movies_count to ContentType.MOVIE,
            R.id.tile_series_count to ContentType.SERIES,
        )
        for ((id, type) in tiles) {
            val countView = findViewById<TextView>(id)
            countView.text = "Loading…"
            lifecycleScope.launch {
                countView.text = try {
                    countLabel(type, allStreams(type).await().size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
            }
        }
    }

    private fun loadRecentlyAdded() {
        lifecycleScope.launch {
            try {
                val all = allStreams(ContentType.MOVIE).await()
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
        allLists.clear()
        Toast.makeText(this, "Lists will reload from the server", Toast.LENGTH_SHORT).show()
        loadCounts()
        loadRecentlyAdded()
    }

    private fun showAccount() {
        val info = Session.userInfo
        val host = api.account.base.substringAfter("://")
        val playlistName = prefs.activePlaylist?.name ?: host
        // Top bar: playlist chip and expiry chip.
        findViewById<TextView>(R.id.playlist_name).text = playlistName
        findViewById<TextView>(R.id.playlist_initial).apply {
            text = playlistName.trim().firstOrNull()?.uppercase() ?: "?"
            backgroundTintList = ColorStateList.valueOf(avatarColor(prefs.activePlaylist?.id ?: host))
        }
        findViewById<TextView>(R.id.expiry_chip).apply {
            val exp = info?.expDate
            text = if (exp != null) "Expires " + SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(exp * 1000)) else "No expiry date"
            visibility = if (info == null) View.GONE else View.VISIBLE
        }
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
