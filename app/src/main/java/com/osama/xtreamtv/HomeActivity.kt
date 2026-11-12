package com.osama.xtreamtv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import coil.load
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

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

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
    private lateinit var recentSeriesAdapter: ItemAdapter
    private lateinit var topRatedAdapter: ItemAdapter
    private lateinit var topSeriesAdapter: ItemAdapter
    /** The Favorites row's title with its Movies / Series switch (1.1A, concept F). */
    private var favSwitch: LinearLayout? = null

    /** Home rows: key -> (title view id, row view id). */
    private val homeRows = mapOf(
        "new" to (R.id.row_new_title to R.id.row_new),
        "continue" to (R.id.row_continue_title to R.id.row_continue),
        "favorites" to (R.id.row_favorites_title to R.id.row_favorites),
        "top_movies" to (R.id.row_top_rated_title to R.id.row_top_rated),
        "top_series" to (R.id.row_top_series_title to R.id.row_top_series),
        "channels" to (R.id.row_channels_title to R.id.row_channels),
        "recent_movies" to (R.id.row_recent_title to R.id.row_recent),
        "recent_series" to (R.id.row_recent_series_title to R.id.row_recent_series),
        "picked" to (R.id.row_picked_title to R.id.row_picked),
        "trending" to (R.id.row_trending_title to R.id.row_trending),
    )
    private lateinit var newAdapter: ItemAdapter
    /** V1: background follows the highlighted poster (null when off). */
    private var backdrop: FocusBackdrop? = null
    /** V3: rotating banner at the top. */
    private var featuredItems: List<Pair<String, StreamItem>> = emptyList()
    private var featuredIndex = 0
    private val rotateFeatured = object : Runnable {
        override fun run() {
            if (featuredItems.size > 1) showFeatured(featuredIndex + 1)
            findViewById<View?>(R.id.featured)?.postDelayed(this, 7_000)
        }
    }
    private var expiryShown = false
    /** Layout and colour this screen was built with; rebuilt when Settings changes them. */
    private var builtLook = ""

    /** When the screen opened (diagnostics: how long its content took to be ready). */
    private val openedAt = android.os.SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        Appearance.apply(this)
        builtLook = lookKey()
        setContentView(
            when (prefs.homeStyle) {
                Appearance.HOME_HERO -> R.layout.activity_home_hero
                Appearance.HOME_SIDE_MENU -> R.layout.activity_home_side
                Appearance.HOME_TABS -> R.layout.activity_home_tabs
                Appearance.HOME_DOCK -> R.layout.activity_home_dock
                else -> R.layout.activity_home
            }
        )
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
            if (prefs.homeStyle != Appearance.HOME_SIDE_MENU) tile.onFocusChangeListener = scaleOnFocus
            tile.setOnClickListener {
                startActivity(Intent(this, BrowseActivity::class.java).putExtra(BrowseActivity.EXTRA_TYPE, type.name))
            }
        }
        findViewById<View>(R.id.tile_search).apply {
            if (prefs.homeStyle != Appearance.HOME_SIDE_MENU) onFocusChangeListener = scaleOnFocus
            setOnClickListener { startActivity(Intent(this@HomeActivity, SearchActivity::class.java)) }
        }
        findViewById<View>(R.id.tile_live).requestFocus()
        val openPlaylists = View.OnClickListener {
            Session.clear()
            startActivity(PlaylistsActivity.manageIntent(this))
            finish()
        }
        playlistsAction = openPlaylists
        setupDock()
        setupTabBar()
        findViewById<View>(R.id.logout).setOnClickListener(openPlaylists)
        findViewById<View>(R.id.settings_button).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // The TV keeps the remote's Menu button for itself: this opens the quick drawer instead.
        findViewById<View?>(R.id.more_button)?.setOnClickListener { openDrawer() }
        findViewById<View?>(R.id.tile_more)?.setOnClickListener { openDrawer() }
        findViewById<View>(R.id.playlist_chip).setOnClickListener(openPlaylists)
        findViewById<View>(R.id.refresh_lists).setOnClickListener { refreshLists() }
        findViewById<View>(R.id.check_updates).setOnClickListener { Updates.checkNow(this, lifecycleScope) }
        hideBottomOfHome()
        pinPageEdges()
        setupFavoritesSwitch()
        addSuggestionRows()
        applyRowOrder()
        setupFocusMap()
        // Rebuilt in another layout: the restored highlight may sit on a view this layout hides.
        findViewById<View>(android.R.id.content).post {
            if (currentFocus?.isShown != true) findViewById<View>(R.id.tile_live).requestFocus()
        }

        // Named: as a trailing lambda it became the new onBindExtra and asked "Remove…?" on every
        // draw of the row (2026-11-07).
        continueAdapter = row(R.id.row_continue, R.layout.item_poster_row, { _, item -> resume(item.id) },
            onLongClick = { _, item -> confirmRemoveProgress(this, prefs, item) { loadLocalRows() } })
        channelsAdapter = row(R.id.row_channels, R.layout.item_channel_card, { index, _ ->
            Session.zapList = channelsAdapter.items
            Session.zapTitle = "Favorites"
            startActivity(PlayerActivity.liveIntent(this, index))
        })
        favoritesAdapter = row(R.id.row_favorites, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) },
            // Long-press on Home: remove from favorites, after asking (owner, 2026-11-07). In Movies,
            // Series and More like this a long-press adds or removes straight away.
            onLongClick = { _, item -> confirmRemoveFavorite(this, prefs, item) { loadLocalRows() } },
            onBindExtra = { vh, item ->
                // 1.1E: a followed series with an episode due within two weeks says when.
                if (item.type == ContentType.SERIES) RatingsExtras.cardLine(RatingsExtras.saved(item.type, item.id))?.let { line ->
                    vh.subtitle.text = line
                    vh.subtitle.setTextColor(0xFF9FD8B8.toInt())
                    vh.subtitle.visibility = View.VISIBLE
                }
            })
        recentAdapter = row(R.id.row_recent, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = ::toggleHomeFavorite)
        recentSeriesAdapter = row(R.id.row_recent_series, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = ::toggleHomeFavorite)
        newAdapter = row(R.id.row_new, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = ::toggleHomeFavorite)
        topRatedAdapter = row(R.id.row_top_rated, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = ::toggleHomeFavorite)
        topSeriesAdapter = row(R.id.row_top_series, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) }, onLongClick = ::toggleHomeFavorite)
        pickedAdapter = row(R.id.row_picked, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) },
            onLongClick = { _, item -> askNotInterested(item) }, onFocus = ::showPickedWhy)
        trendingAdapter = row(R.id.row_trending, R.layout.item_poster_row, { _, item ->
            if (Trending.isPlaceholder(item)) trendingEntries[item.id]?.let { showTrendInfo(it) } else startActivity(DetailsActivity.intent(this, item))
        }, onLongClick = null, onFocus = ::showTrendLine, onBindExtra = { vh, item -> markTrending(vh, item) })
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> clearSuggestionLines(now) }
        backdrop = FocusBackdrop.attach(this)
        setupFeatured()

        showAccount()
        loadCounts()
        loadRecentlyAdded()
        Updates.checkQuietly(this, lifecycleScope)
        // Make sure the TV home screen's "Play next" row has everything in Continue watching.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val playlist = prefs.activeId ?: "legacy"
            (prefs.continueWatching(ContentType.MOVIE) + prefs.continueWatching(ContentType.SERIES)).forEach { WatchNext.update(applicationContext, playlist, it) }
        }
        // Opened from the TV home screen's "Play next" row: continue that item right away.
        intent.getStringExtra(WatchNext.EXTRA_RESUME_KEY)?.let { key ->
            intent.removeExtra(WatchNext.EXTRA_RESUME_KEY)
            resume(key)
        }
    }

    /**
     * Long-press on a poster in Recently added, New episodes and Top rated: add or remove a favorite,
     * as in Movies and Series (16.1: these rows had no long-press, and Top rated showed no star).
     * Continue watching and the Favorites row keep their own long-press (remove, after asking).
     */
    private fun toggleHomeFavorite(@Suppress("UNUSED_PARAMETER") position: Int, item: StreamItem) {
        val added = prefs.toggleFavorite(item)
        showToast(this, if (added) "Added ${item.name} to favorites" else "Removed ${item.name} from favorites", Toast.LENGTH_SHORT)
        refreshStars()
        loadLocalRows()
    }

    /** The favorite star on every Home poster row, from the saved favorites. */
    private fun refreshStars() {
        if (!::topSeriesAdapter.isInitialized) return
        val movies = prefs.favoriteIds(ContentType.MOVIE)
        val series = prefs.favoriteIds(ContentType.SERIES)
        for ((a, ids) in listOf(recentAdapter to movies, topRatedAdapter to movies, recentSeriesAdapter to series, newAdapter to series, topSeriesAdapter to series)) {
            if (a.favorites == ids) continue
            a.favorites = ids
            a.notifyItemRangeChanged(0, a.itemCount, "fav")
        }
    }

    override fun onResume() {
        super.onResume()
        MiniPlayerBar.attach(this)
        // Diagnostics: the reminder dot on Settings, and once after a crash the message to share.
        val dot = Diag.dotColor()
        DiagUi.applyDot(findViewById(R.id.settings_button), dot, sideRow = prefs.homeStyle == Appearance.HOME_SIDE_MENU)
        DiagUi.applyDot(findViewById(R.id.tile_settings), dot)
        window.decorView.post { if (!isFinishing) DiagUi.crashPrompt(this) }
        if (!::continueAdapter.isInitialized) return
        if (lookKey() != builtLook) {
            recreate()
            return
        }
        clock.removeCallbacks(clockTicker)
        clockTicker.run()
        findViewById<View?>(R.id.featured)?.let { b ->
            b.removeCallbacks(rotateFeatured)
            if (featuredItems.size > 1) b.postDelayed(rotateFeatured, 7_000)
        }
        loadLocalRows()
        loadSuggestions()
        // Back after a while (the TV slept, another app): saved lists older than three hours are
        // checked with the server behind the screen, and the rows take the newer ones when they differ.
        resumedNow = true
        Session.listWatchers.add(listsChanged)
        api.refreshOldLists()
        if (listsSeen != Session.listsVersion) takeNewLists() else loadNewEpisodes()
        refreshStars()
        prepareSections()
        // Scores found while away (a title's page, pre-fill) show on the cards already there.
        for (a in listOf(newAdapter, recentAdapter, recentSeriesAdapter, topRatedAdapter, topSeriesAdapter)) a.notifyItemRangeChanged(0, a.itemCount, "scores")
        // Pre-fill: once 20 s after opening (if nothing is pressed), then each idle minute.
        window.decorView.post { restoreHighlight() }
        lastKeyAt = android.os.SystemClock.uptimeMillis() - 40_000
        window.decorView.removeCallbacks(prefillCheck)
        window.decorView.postDelayed(prefillCheck, 20_000)
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

    private fun lookKey() = "${prefs.homeStyle}/${prefs.themeIndex}/${prefs.glassScene}/${prefs.glassStyle}/${prefs.focusStyle}/${prefs.movingBackground}/${prefs.paletteIndex}/${prefs.accentIndex}/${prefs.backgroundStyle}/${prefs.textSize}/${prefs.uiSize}/${prefs.backdropFollows}/${prefs.featuredBanner}/${prefs.suggestRowsKind}/${prefs.officialPosters}/${prefs.homeRowOrder.joinToString(",")}|${prefs.homeRowHidden.sorted().joinToString(",")}/${prefs.activeId?.let { prefs.avatar(it) + prefs.avatarColor(it) }}"

    /** Where the highlight was when Home was left: a row's title (row, "TYPE:id", place), or a button. */
    private var leftOn: Triple<Int, String?, Int>? = null

    private fun rememberHighlight() {
        val f = currentFocus ?: return
        var child: View = f
        var p = f.parent
        while (p is View && p !is RecyclerView) { child = p; p = p.parent }
        val row = p as? RecyclerView
        val adapter = row?.adapter as? ItemAdapter
        if (row != null && adapter != null && row.id != View.NO_ID) {
            val at = row.getChildAdapterPosition(child)
            val item = adapter.items.getOrNull(at)
            leftOn = Triple(row.id, item?.let { "${it.type.name}:${it.id}" }, at)
        } else if (f.id != View.NO_ID) {
            leftOn = Triple(f.id, null, -1)
        }
    }

    /**
     * Back on Home: the highlight returns to the title it was on (or the same place in that row).
     * The rows are refilled on return, and the highlight used to fall back to the first tile or the
     * row above (TV, 2026-11-07).
     */
    private fun restoreHighlight(tries: Int = 0) {
        val (viewId, key, place) = leftOn ?: return
        val view = findViewById<View?>(viewId) ?: return
        // The screen becomes visible just after it comes back: wait for it (up to about 2 s).
        if (!view.isShown || !hasWindowFocus()) {
            if (tries < 20) window.decorView.postDelayed({ restoreHighlight(tries + 1) }, 100) else leftOn = null
            return
        }
        val row = view as? RecyclerView
        if (row == null) { if (!view.hasFocus()) view.requestFocus(); leftOn = null; return }
        val adapter = row.adapter as? ItemAdapter ?: return
        if (adapter.itemCount == 0) { if (tries < 20) row.postDelayed({ restoreHighlight(tries + 1) }, 100) else leftOn = null; return }
        val at = adapter.items.indexOfFirst { "${it.type.name}:${it.id}" == key }.takeIf { it >= 0 } ?: place.coerceIn(0, adapter.itemCount - 1)
        val card = row.layoutManager?.findViewByPosition(at)
        if (card != null) {
            if (!card.hasFocus()) card.requestFocus()
            leftOn = null
        } else if (tries < 20) {
            row.scrollToPosition(at)
            row.postDelayed({ restoreHighlight(tries + 1) }, 100)
        } else {
            leftOn = null
        }
    }

    override fun onPause() {
        rememberHighlight()
        resumedNow = false
        Session.listWatchers.remove(listsChanged)
        super.onPause()
        prefillJob?.cancel()
        window.decorView.removeCallbacks(prefillCheck)
        MiniPlayerBar.detach(this)
        findViewById<View?>(R.id.featured)?.removeCallbacks(rotateFeatured)
        if (::clock.isInitialized) clock.removeCallbacks(clockTicker)
    }

    /**
     * Off screen: this screen lets go of the big lists (its rows keep the few titles they show), so
     * they can leave memory while the fill works under the screensaver. They are read again on return.
     */
    override fun onStop() {
        super.onStop()
        allLists.clear()
    }

    private var playlistsAction: View.OnClickListener? = null

    /** Dock layout's two extra tiles. */
    private fun setupDock() {
        val favorites = findViewById<View?>(R.id.tile_favorites) ?: return
        val settings = findViewById<View>(R.id.tile_settings)
        favorites.onFocusChangeListener = scaleOnFocus
        settings.onFocusChangeListener = scaleOnFocus
        favorites.setOnClickListener {
            // The first section with favorites (Browse keeps them per section).
            val type = listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).firstOrNull { prefs.favoriteIds(it).isNotEmpty() } ?: ContentType.LIVE
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_TYPE, type.name)
                    .putExtra(BrowseActivity.EXTRA_OPEN_FAVORITES, true),
            )
        }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    /** Tabs: when the bar can't hold everything, the counts give way so every name stays whole. */
    private fun setupTabBar() {
        val bar = findViewById<android.view.ViewGroup?>(R.id.tab_bar) ?: return
        val counts = listOf(R.id.tile_live_count, R.id.tile_movies_count, R.id.tile_series_count)
        fun needs(tab: android.view.ViewGroup) = tab.paddingLeft + tab.paddingRight + (0 until tab.childCount).sumOf { i ->
            val text = tab.getChildAt(i) as? TextView
            if (text == null || text.visibility == View.GONE) 0 else {
                val margins = text.layoutParams as android.view.ViewGroup.MarginLayoutParams
                text.paint.measureText(text.text.toString()).toInt() + 1 + margins.leftMargin + margins.rightMargin
            }
        }
        // Measured from the text itself: a count can change before the bar has been laid out again.
        bar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val needed = bar.paddingLeft + bar.paddingRight +
                (0 until bar.childCount).sumOf { needs(bar.getChildAt(it) as android.view.ViewGroup) }
            val room = (bar.parent as View).width
            if (room > 0 && needed > room) bar.post { counts.forEach { findViewById<View>(it).visibility = View.GONE } }
        }
    }

    /**
     * The bottom of Home (account line, Refresh lists, Check for updates, Playlists, version) repeated
     * the quick drawer, which ☰ More and INFO open. Hidden in every layout; the views stay, because the
     * drawer copies the account line from them. (In the Side menu, Playlists is a menu entry: it stays.)
     */
    private fun hideBottomOfHome() {
        val refresh = findViewById<View?>(R.id.refresh_lists)
        val row = refresh?.parent as? android.view.ViewGroup
        for (id in intArrayOf(R.id.account, R.id.version, R.id.refresh_lists, R.id.check_updates)) {
            findViewById<View?>(id)?.visibility = View.GONE
        }
        findViewById<View?>(R.id.logout)?.let { if (it.parent === row) it.visibility = View.GONE }
        // The row that held them takes no room either (unless something else still shows in it).
        if (row != null && row.id != android.R.id.content && (0 until row.childCount).none { row.getChildAt(it).visibility == View.VISIBLE }) {
            row.visibility = View.GONE
        }
    }

    private var drawer: View? = null

    /** Menu key on Home opens the quick drawer; Back or Menu closes it. */
    private fun handleDrawerKey(event: android.view.KeyEvent): Boolean {
        val open = drawer?.visibility == View.VISIBLE
        val key = event.keyCode
        // INFO, a short press: the quick drawer (this TV keeps the Menu button for itself).
        // Acts on release and only when short: holding INFO does nothing here (problems are marked with the red button).
        if (key == android.view.KeyEvent.KEYCODE_INFO) {
            if (event.action == android.view.KeyEvent.ACTION_UP && event.eventTime - event.downTime < 500) {
                if (open) closeDrawer() else openDrawer()
            }
            return true
        }
        if (key == android.view.KeyEvent.KEYCODE_MENU && (open || prefs.homeMenuButton == 0)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (open) closeDrawer() else openDrawer()
            }
            return true
        }
        if (!open) return false
        if (key == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) closeDrawer()
            return true
        }
        return key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
    }

    private var focusBeforeDrawer: View? = null

    private fun openDrawer() {
        val view = drawer ?: layoutInflater.inflate(R.layout.view_quick_drawer, findViewById<android.view.ViewGroup>(android.R.id.content), false).also { v ->
            drawer = v
            findViewById<android.view.ViewGroup>(android.R.id.content).addView(v)
            fun tile(id: Int, action: () -> Unit) = v.findViewById<View>(id).setOnClickListener {
                closeDrawer()
                action()
            }
            tile(R.id.drawer_refresh) { refreshLists() }
            tile(R.id.drawer_updates) { Updates.checkNow(this, lifecycleScope) }
            tile(R.id.drawer_playlists) { playlistsAction?.onClick(v) }
            tile(R.id.drawer_guide) { openGuide() }
            tile(R.id.drawer_settings) { startActivity(Intent(this, SettingsActivity::class.java)) }
            tile(R.id.drawer_share) { DiagUi.shareFlow(this) }
            tile(R.id.drawer_mark) { DiagUi.mark("the quick drawer", this) }
            // The row is the whole drawer: Left and Right stop at its ends.
            v.findViewById<View>(R.id.drawer_refresh).nextFocusLeftId = R.id.drawer_refresh
            v.findViewById<View>(R.id.drawer_settings).nextFocusRightId = R.id.drawer_settings
        }
        view.findViewById<TextView>(R.id.drawer_account).text = accountView.text
        view.findViewById<TextView>(R.id.drawer_version).text = "Version ${BuildConfig.VERSION_NAME}"
        focusBeforeDrawer = currentFocus
        view.visibility = View.VISIBLE
        val panel = view.findViewById<View>(R.id.drawer_panel)
        panel.translationY = 200 * resources.displayMetrics.density
        panel.animate().translationY(0f).setDuration(180).start()
        view.findViewById<View>(R.id.drawer_refresh).requestFocus()
    }

    /** The guide lists Session.zapList, so give it every channel. */
    private fun openGuide() {
        lifecycleScope.launch {
            try {
                val channels = allStreams(ContentType.LIVE).await()
                if (channels.isEmpty()) {
                    showToast(this@HomeActivity, "No channels to show", android.widget.Toast.LENGTH_SHORT)
                    return@launch
                }
                Session.zapList = channels
                Session.zapTitle = "All channels"
                startActivity(GuideActivity.intent(this@HomeActivity, 0, fromPlayer = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(this@HomeActivity, "Couldn't load the channels", android.widget.Toast.LENGTH_SHORT)
            }
        }
    }

    private fun closeDrawer() {
        drawer?.visibility = View.GONE
        focusBeforeDrawer?.requestFocus()
    }

    private val bottomRow = intArrayOf(R.id.refresh_lists, R.id.check_updates, R.id.logout)
    private val topBar = intArrayOf(R.id.playlist_chip, R.id.more_button, R.id.settings_button)

    /**
     * Scrolling Home layouts: the bottom buttons bring the whole page end into view (account and
     * version below them), the top bar the very top; one more Down / Up there does nothing.
     */
    private fun pinPageEdges() {
        val scroll = (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as? android.widget.ScrollView) ?: return
        for (id in bottomRow) findViewById<View?>(id)?.setOnFocusChangeListener { _, has -> if (has) scroll.post { scroll.smoothScrollTo(0, scroll.getChildAt(0).height) } }
        for (id in topBar) findViewById<View?>(id)?.setOnFocusChangeListener { _, has -> if (has) scroll.post { scroll.smoothScrollTo(0, 0) } }
    }

    /** Pre-fill (1.1E): after a minute without a key press on Home, the rows' scores are asked for. */
    private var lastKeyAt = 0L
    private var prefillJob: kotlinx.coroutines.Job? = null
    private var topRatedAt = -1L
    private val prefillCheck = object : Runnable {
        override fun run() {
            val idle = android.os.SystemClock.uptimeMillis() - lastKeyAt >= 60_000
            if (idle && prefillJob?.isActive != true && Ratings.isOn) {
                val items = buildList {
                    for (a in listOf(continueAdapter, favoritesAdapter, newAdapter, recentAdapter, recentSeriesAdapter)) addAll(a.items)
                    addAll(prefs.favorites(ContentType.MOVIE))
                    addAll(prefs.favorites(ContentType.SERIES))
                }
                // The whole-playlist fill does the asking now; Home only names its rows (filled early)
                // and redraws Top rated when scores have changed.
                RatingsFill.homeRows(items)
                if (Ratings.version != topRatedAt) {
                    topRatedAt = Ratings.version
                    prefillJob = lifecycleScope.launch { loadTopRated() }
                }
            }
            window.decorView.postDelayed(this, 60_000)
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Any key: pre-fill waits (it never slows the remote down).
        lastKeyAt = android.os.SystemClock.uptimeMillis()
        prefillJob?.cancel()
        if (handleDrawerKey(event)) return true
        return focusMap.handle(event) || super.dispatchKeyEvent(event)
    }

    private val focusMap = FocusMap { currentFocus }

    /**
     * Sections of Home. Every layout: a stack from top to bottom, Left and Right stay inside a section.
     * Side menu: the menu column on the left, with Left and Right moving between it and the stack.
     */
    private fun setupFocusMap() {
        fun views(vararg ids: Int): () -> List<View> {
            val list = ids.toList().mapNotNull { findViewById<View?>(it) }
            return { list }
        }
        val rows = listOf(R.id.row_new, R.id.row_continue, R.id.row_picked, R.id.row_trending, R.id.row_channels, R.id.row_favorites, R.id.row_top_rated, R.id.row_top_series, R.id.row_recent, R.id.row_recent_series)
            .map { focusMap.section(roots = views(it)) }
            .plus(listOfNotNull(favSwitch?.let { s -> focusMap.section(roots = { listOf(s) }) }))
            .plus(listOfNotNull(pickedSwitch, trendingSwitch).map { s -> focusMap.section(roots = { listOf(s) }) })
        val featured = focusMap.section(ownsSideKeys = true, roots = views(R.id.featured))
        val stack = ArrayList(rows + featured)
        if (prefs.homeStyle == Appearance.HOME_SIDE_MENU) {
            val menu = focusMap.section(roots = views(R.id.tile_live, R.id.tile_movies, R.id.tile_series, R.id.tile_search, R.id.more_button, R.id.settings_button, R.id.logout, R.id.playlist_chip))
            stack += focusMap.section(roots = views(R.id.refresh_lists, R.id.check_updates))
            var lastInStack: FocusMap.Section? = null
            for (s in stack) s.exits[View.FOCUS_LEFT] = {
                lastInStack = s
                menu.entry()
            }
            menu.exits[View.FOCUS_RIGHT] = {
                val chosen = lastInStack?.takeIf { it.shown } ?: stack.filter { it.shown }.minByOrNull { s ->
                    val at = IntArray(2)
                    s.roots().first { it.isShown }.getLocationOnScreen(at)
                    at[1]
                }
                chosen?.entry()
            }
        } else {
            stack += focusMap.section(roots = views(R.id.playlist_chip, R.id.more_button, R.id.settings_button))
            stack += focusMap.section(roots = views(R.id.hero))
            stack += focusMap.section(roots = views(R.id.tile_live, R.id.tile_movies, R.id.tile_series, R.id.tile_search, R.id.tile_favorites, R.id.tile_settings, R.id.tile_more))
            stack += focusMap.section(roots = views(R.id.refresh_lists, R.id.check_updates, R.id.logout))
        }
        focusMap.stack(stack)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new -> focusMap.note(new) }
    }

    private fun row(
        viewId: Int,
        layout: Int,
        onClick: (Int, StreamItem) -> Unit,
        onLongClick: ((Int, StreamItem) -> Unit)? = null,
        onBindExtra: ((ItemAdapter.VH, StreamItem) -> Unit)? = null,
        onFocus: ((StreamItem) -> Unit)? = null,
    ): ItemAdapter {
        val adapter = ItemAdapter(
            grid = layout == R.layout.item_poster_row, onClick = onClick, onLongClick = onLongClick, layout = layout,
            onFocusItem = { item -> if (item.type != ContentType.LIVE) backdrop?.show(item.image); onFocus?.invoke(item) },
            onBindExtra = onBindExtra,
        )
        findViewById<RecyclerView>(viewId).apply {
            layoutManager = RowLayoutManager(this@HomeActivity)
            this.adapter = adapter
        }
        return adapter
    }

    /** The first heavy row filled since Home opened has been reported (diagnostics). */
    private var rowsReadyLogged = false

    private fun showRow(viewId: Int, titleId: Int, adapter: ItemAdapter, items: List<StreamItem>) {
        // A row still being laid out can't take new items (Android closes the app): fill it right after.
        // Seen on the TV 2026-11-07: Back to Home moved the highlight onto the Favorites switch while the
        // Favorites row was being laid out, and the switch refilled that row.
        if (findViewById<RecyclerView>(viewId)?.isComputingLayout == true) {
            window.decorView.post { showRow(viewId, titleId, adapter, items) }
            return
        }
        adapter.submit(items)
        // Switched off in Settings > Home layout > Arrange rows: never shown.
        val key = homeRows.entries.firstOrNull { it.value.second == viewId }?.key
        val hidden = key != null && key in prefs.homeRowHidden
        val visibility = if (items.isEmpty() || hidden) View.GONE else View.VISIBLE
        findViewById<View>(viewId).visibility = visibility
        titleHolder(titleId).visibility = visibility
    }

    /** A row's title, or the box holding it (Favorites: the title with its switch). */
    private fun titleHolder(titleId: Int): View {
        val t = findViewById<View>(titleId)
        return (t.parent as? View)?.takeIf { it === favSwitch } ?: t
    }

    /**
     * Rows in the order chosen in Settings > Home layout > Arrange rows. They all sit in one column:
     * each title and row pair is taken out and put back in that order where the first one was.
     */
    private fun applyRowOrder() {
        val parent = findViewById<View>(R.id.row_continue).parent as? android.view.ViewGroup ?: return
        // The tiles (and Hero's panel, the side menu, the tab bar) stay where the layout puts them.
        val groups = prefs.homeRowOrder.mapNotNull { key -> homeRows[key]?.let { (t, r) -> listOf(titleHolder(t), findViewById<View>(r)) } }
        if (groups.flatten().any { it.parent !== parent }) return
        val start = groups.flatten().minOf { parent.indexOfChild(it) }
        for (v in groups.flatten()) parent.removeView(v)
        var at = start
        for (v in groups.flatten()) parent.addView(v, at++)
    }

    /** Favorites (1.1A, concept F): one row, a Movies / Series switch in its title. */
    private fun setupFavoritesSwitch() {
        val title = findViewById<TextView?>(R.id.row_favorites_title) ?: return
        val parent = title.parent as? android.view.ViewGroup ?: return
        if (prefs.homeStyle == Appearance.HOME_DOCK) return
        val index = parent.indexOfChild(title)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = title.layoutParams
            visibility = title.visibility
        }
        parent.removeView(title)
        title.text = "Favorites"
        title.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        title.visibility = View.VISIBLE
        box.addView(title)
        val d = resources.displayMetrics.density
        for ((kind, label) in listOf("MOVIE" to "Movies", "SERIES" to "Series")) {
            box.addView(TextView(this).apply {
                tag = kind
                text = label
                textSize = 14f
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.bg_glass_chip_cat)
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
                setPadding((14 * d).toInt(), (5 * d).toInt(), (14 * d).toInt(), (5 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (12 * d).toInt() }
                // Highlighting a choice switches the row straight away.
                // (After the focus change finishes: it can happen in the middle of a row's layout.)
                setOnFocusChangeListener { v, has -> if (has && prefs.homeFavoritesKind != kind) { prefs.homeFavoritesKind = kind; v.post { loadLocalRows() } } }
                // OK: down to the posters. (The Activity's findViewById: inside this TextView's apply,
                // a bare findViewById searched the button itself and crashed, 2026-11-07.)
                setOnClickListener {
                    val row = this@HomeActivity.findViewById<RecyclerView>(R.id.row_favorites)
                    (row.layoutManager?.findViewByPosition(0) ?: row.getChildAt(0))?.requestFocus()
                }
            })
        }
        parent.addView(box, index)
        favSwitch = box
    }

    /** Rows stored on the device: Continue watching and favorites. */
    private fun loadLocalRows() {
        val entries = (prefs.continueWatching(ContentType.MOVIE) + prefs.continueWatching(ContentType.SERIES))
            .sortedByDescending { it.updated }
        showHero(entries.firstOrNull())
        val watching = entries
            .map { e ->
                StreamItem(e.type, e.key, listOfNotNull(e.title, e.subtitle).joinToString("  ·  "), e.image, null, e.ext, null, null, note = e.progressText(), progress = e.progressFraction(), ratingId = e.seriesId ?: e.id)
            }
        showRow(R.id.row_continue, R.id.row_continue_title, continueAdapter, watching)
        val channels = prefs.favorites(ContentType.LIVE)
        channelsAdapter.favorites = emptySet()
        showRow(R.id.row_channels, R.id.row_channels_title, channelsAdapter, channels)
        val movies = prefs.favorites(ContentType.MOVIE)
        val series = prefs.favorites(ContentType.SERIES)
        val favorites = if (favSwitch == null) movies + series else {
            // The chosen kind; the other one when the chosen has none.
            val kind = when {
                prefs.homeFavoritesKind == "SERIES" && series.isEmpty() && movies.isNotEmpty() -> "MOVIE"
                prefs.homeFavoritesKind == "MOVIE" && movies.isEmpty() && series.isNotEmpty() -> "SERIES"
                else -> prefs.homeFavoritesKind
            }
            favSwitch?.let { box ->
                for (i in 1 until box.childCount) (box.getChildAt(i) as TextView).apply {
                    isActivated = tag == kind
                    text = if (tag == "MOVIE") "Movies ${movies.size}" else "Series ${series.size}"
                    visibility = if ((tag == "MOVIE" && movies.isEmpty()) || (tag == "SERIES" && series.isEmpty())) View.GONE else View.VISIBLE
                }
            }
            if (kind == "SERIES") series else movies
        }
        showRow(R.id.row_favorites, R.id.row_favorites_title, favoritesAdapter, favorites)
        loadTopRated()
    }

    /**
     * The whole list of one type, downloaded once (the tile count and "Recently added" both
     * need the movie list; two parallel downloads of a big list could fail or time out).
     */
    private fun allStreams(type: ContentType): Deferred<List<StreamItem>> {
        allLists[type]?.let { if (!it.isCancelled) return it } // a failed download is tried again
        // Session.all: the quiet preparing of the sections waits for this same read instead of making its own.
        return lifecycleScope.async { Session.all(api, type) }.also { allLists[type] = it }
    }

    // ---- A newer list arrived from the server while the app was open ----

    /** The lists' version this screen last drew. */
    private var listsSeen = Session.listsVersion
    private val listsChanged: () -> Unit = { if (resumedNow) takeNewLists() }
    private var resumedNow = false

    /**
     * Draw the rows again from the newer lists: counts, banner, New episodes, Recently added, Top rated.
     * The highlight stays where it is (rows keep it on the same title when their content changes).
     */
    private fun takeNewLists() {
        if (listsSeen == Session.listsVersion || !::continueAdapter.isInitialized) return
        listsSeen = Session.listsVersion
        allLists.clear()
        loadCounts()
        loadFeatured()
        loadRecentlyAdded()
        loadNewEpisodes()
        loadTopRated()
        loadSuggestions()
        prepareSections()
    }

    /** H1 only: the last thing watched, big, with a Resume button. */
    private fun showHero(entry: WatchEntry?) {
        val hero = findViewById<View?>(R.id.hero) ?: return
        if (entry == null) {
            hero.visibility = View.GONE
            return
        }
        hero.visibility = View.VISIBLE
        findViewById<TextView>(R.id.hero_title).text = entry.title
        findViewById<TextView>(R.id.hero_meta).text = listOfNotNull(entry.subtitle, entry.progressText()).joinToString("  ·  ")
        findViewById<android.widget.ProgressBar>(R.id.hero_progress).progress = (entry.progressFraction().coerceAtLeast(0f) * 1000).toInt()
        findViewById<android.widget.ImageView>(R.id.hero_image).load(entry.image) {
            crossfade(false)
            error(R.drawable.placeholder)
        }
        findViewById<View>(R.id.hero_resume).setOnClickListener { resume(entry.key) }
    }

    /** "5,537 channels" under each tile's name (lists come from the device cache). */
    private fun loadCounts() {
        findViewById<TextView?>(R.id.tile_favorites_count)?.text =
            "${ContentType.values().sumOf { prefs.favoriteIds(it).size }} saved"
        val tiles = listOf(
            R.id.tile_live_count to ContentType.LIVE,
            R.id.tile_movies_count to ContentType.MOVIE,
            R.id.tile_series_count to ContentType.SERIES,
        )
        for ((id, type) in tiles) {
            val countView = findViewById<TextView>(id)
            countView.text = "Loading…"
            lifecycleScope.launch {
                val drawn = listsSeen
                val label = try {
                    countLabel(type, allStreams(type).await().size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                if (drawn == listsSeen) countView.text = label
            }
        }
    }

    // ---- V3: featured banner ----

    private fun setupFeatured() {
        val banner = findViewById<View?>(R.id.featured) ?: return
        if (!prefs.featuredBanner) return
        banner.clipToOutline = true
        banner.setOnClickListener { featuredItems.getOrNull(featuredIndex)?.let { startActivity(DetailsActivity.intent(this, it.second)) } }
        banner.setOnKeyListener { _, keyCode, event ->
            val step = when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                // Side-menu layout: Left goes back to the menu.
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (prefs.homeStyle == Appearance.HOME_SIDE_MENU) 0 else -1
                else -> 0
            }
            if (step == 0 || featuredItems.size < 2) return@setOnKeyListener false
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                showFeatured(featuredIndex + step)
                banner.removeCallbacks(rotateFeatured)
                banner.postDelayed(rotateFeatured, 10_000)
            }
            true
        }
        loadFeatured()
    }

    private fun loadFeatured() {
        val banner = findViewById<View?>(R.id.featured) ?: return
        if (!prefs.featuredBanner) return
        lifecycleScope.launch {
            try {
                val drawn = listsSeen
                val movies = allStreams(ContentType.MOVIE).await().filter { it.added > 0 }.sortedByDescending { it.added }.take(5)
                val series = allStreams(ContentType.SERIES).await()
                if (drawn != listsSeen) return@launch
                val newIds = prefs.newEpisodeSeries(series)
                val fresh = series.filter { it.id in newIds }.take(3)
                featuredItems = fresh.map { "New episodes" to it } + movies.map { "Recently added" to it }
                if (featuredItems.isEmpty()) return@launch
                banner.visibility = View.VISIBLE
                // Big tiles layout: tiles → banner → top bar.
                if (prefs.homeStyle == Appearance.HOME_TILES) {
                    listOf(R.id.tile_live, R.id.tile_movies, R.id.tile_series, R.id.tile_search).forEach { findViewById<View>(it).nextFocusUpId = R.id.featured }
                }
                val dots = findViewById<android.widget.LinearLayout>(R.id.featured_dots)
                dots.removeAllViews()
                val d = resources.displayMetrics.density
                repeat(featuredItems.size) {
                    dots.addView(View(this@HomeActivity).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams((6 * d).toInt(), (4 * d).toInt()).apply { rightMargin = (4 * d).toInt() }
                        setBackgroundResource(R.drawable.bg_avatar)
                    })
                }
                showFeatured(0)
                banner.removeCallbacks(rotateFeatured)
                banner.postDelayed(rotateFeatured, 7_000)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No banner.
            }
        }
    }

    private fun showFeatured(index: Int) {
        if (featuredItems.isEmpty()) return
        featuredIndex = Math.floorMod(index, featuredItems.size)
        val (kind, item) = featuredItems[featuredIndex]
        findViewById<TextView>(R.id.featured_kind).text = kind
        findViewById<TextView>(R.id.featured_title).text = item.name
        showFeaturedMeta(item)
        // 1.1E: the banner's title is on screen, so its real scores may be asked for now.
        if (Ratings.needsLookup(item.type, item.id)) lifecycleScope.launch {
            Ratings.lookupItem(api, item, onScreen = true) ?: return@launch
            if (featuredItems.getOrNull(featuredIndex)?.second?.id == item.id) showFeaturedMeta(item)
        }
        val image = findViewById<android.widget.ImageView>(R.id.featured_image)
        image.animate().cancel()
        image.alpha = 0f
        image.load(item.image) {
            crossfade(false)
            listener(onSuccess = { _, _ -> image.animate().alpha(0.75f).setDuration(400).start() })
        }
        val dots = findViewById<android.widget.LinearLayout>(R.id.featured_dots)
        val d = resources.displayMetrics.density
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).apply {
                layoutParams = layoutParams.apply { width = ((if (i == featuredIndex) 16 else 6) * d).toInt() }
                alpha = if (i == featuredIndex) 1f else 0.45f
            }
        }
    }

    /** Year, genre, kind; real IMDb / Rotten Tomatoes scores first when known, else the provider's ★. */
    private fun showFeaturedMeta(item: StreamItem) {
        val scores = Ratings.known(item.type, item.id)
        val rest = listOfNotNull(
            item.year,
            item.genre,
            if (item.type == ContentType.SERIES) "Series" else "Movie",
        ).joinToString("   ·   ")
        val view = findViewById<TextView>(R.id.featured_meta)
        view.text = if (scores == null) rest else android.text.SpannableStringBuilder()
            .append(RatingBadges.line(this, scores, view.currentTextColor)).append("   ·   ").append(rest)
    }

    /** "New episodes": followed series the provider updated since they were last opened. */
    private var prepareJob: kotlinx.coroutines.Job? = null

    /**
     * While Home is showing, quietly get Movies, Series and Live TV ready (one after another, after
     * a short pause so Home itself appears first). Skipped on TVs short of memory.
     */
    private fun prepareSections() {
        if (prepareJob?.isActive == true || Look.isLightDevice(this)) return
        prepareJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            for (type in listOf(ContentType.MOVIE, ContentType.SERIES, ContentType.LIVE)) {
                try {
                    Session.prepare(api, type)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not reachable now: the section loads as usual when opened.
                }
            }
        }
    }

    private var newEpisodesLogged = ""

    private fun loadNewEpisodes() {
        lifecycleScope.launch {
            try {
                val drawn = listsSeen
                val all = allStreams(ContentType.SERIES).await()
                if (drawn != listsSeen) return@launch
                val ids = prefs.newEpisodeSeries(all)
                val items = all.filter { it.id in ids }.sortedByDescending { it.added }
                // For the report: counts only, never a title.
                val line = "new episodes: ${ids.size} of ${prefs.followedSeriesIds().size} followed series changed since last opened"
                if (line != newEpisodesLogged) { newEpisodesLogged = line; Diag.log("lists", line) }
                newAdapter.newIds = ids
                newAdapter.favorites = prefs.favoriteIds(ContentType.SERIES)
                favoritesAdapter.newIds = ids
                favoritesAdapter.notifyItemRangeChanged(0, favoritesAdapter.itemCount, "new")
                showRow(R.id.row_new, R.id.row_new_title, newAdapter, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row just stays hidden.
            }
        }
    }

    /** "Recently added" movies and series rows, each only when switched on (Settings > Home layout). */
    private fun loadRecentlyAdded() {
        recentRow(ContentType.MOVIE, "recent_movies" !in prefs.homeRowHidden, R.id.row_recent, R.id.row_recent_title, recentAdapter)
        recentRow(ContentType.SERIES, "recent_series" !in prefs.homeRowHidden, R.id.row_recent_series, R.id.row_recent_series_title, recentSeriesAdapter)
    }

    // ---- Package 3 (approved 2026-11-13): Picked for you and Trending this week ----

    private lateinit var pickedAdapter: ItemAdapter
    private lateinit var trendingAdapter: ItemAdapter
    /** The rows' title boxes: name, the line for the highlighted card, and (when chosen) a Movies / Series switch. */
    private var pickedLine: TextView? = null
    private var trendingLine: TextView? = null
    private var pickedName: TextView? = null
    private var pickedSwitch: LinearLayout? = null
    private var trendingSwitch: LinearLayout? = null
    /** "TYPE:id" -> why it was picked. */
    private var pickedBecause: Map<String, String?> = emptyMap()
    /** Card id -> its trending entry (titles in the playlist by their own id, the others "tmdb:…"). */
    private var trendingEntries: Map<String, Trending.Entry> = emptyMap()
    private var suggestJob: kotlinx.coroutines.Job? = null

    /** The two rows, made like Continue watching's and placed right after it (Arrange rows can move them). */
    private fun addSuggestionRows() {
        val continueTitle = findViewById<TextView>(R.id.row_continue_title)
        val continueRow = findViewById<RecyclerView>(R.id.row_continue)
        val parent = continueRow.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        var at = parent.indexOfChild(continueRow) + 1
        for ((titleId, rowId) in listOf(R.id.row_picked_title to R.id.row_picked, R.id.row_trending_title to R.id.row_trending)) {
            val box = LinearLayout(this).apply {
                id = titleId
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(continueTitle.layoutParams as android.view.ViewGroup.MarginLayoutParams)
            }
            val name = TextView(this).apply {
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, continueTitle.textSize)
                setTextColor(continueTitle.textColors)
                typeface = continueTitle.typeface
                isAllCaps = continueTitle.isAllCaps
                letterSpacing = continueTitle.letterSpacing
                text = if (rowId == R.id.row_picked) "Picked for you" else "Trending this week"
            }
            box.addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (prefs.suggestRowsKind == 1) {
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                for ((kind, label) in listOf("MOVIE" to "Movies", "SERIES" to "Series")) chips.addView(TextView(this).apply {
                    tag = kind
                    text = label
                    textSize = 14f
                    isFocusable = true
                    isClickable = true
                    setBackgroundResource(R.drawable.bg_glass_chip_cat)
                    setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
                    setPadding((14 * d).toInt(), (5 * d).toInt(), (14 * d).toInt(), (5 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = (12 * d).toInt() }
                    setOnFocusChangeListener { v, has ->
                        if (!has) return@setOnFocusChangeListener
                        val picked = rowId == R.id.row_picked
                        if ((if (picked) prefs.pickedKind else prefs.trendingKind) == kind) return@setOnFocusChangeListener
                        if (picked) prefs.pickedKind = kind else prefs.trendingKind = kind
                        v.post { loadSuggestions() }
                    }
                    setOnClickListener {
                        val row = this@HomeActivity.findViewById<RecyclerView>(rowId)
                        (row.layoutManager?.findViewByPosition(0) ?: row.getChildAt(0))?.requestFocus()
                    }
                })
                box.addView(chips)
                if (rowId == R.id.row_picked) pickedSwitch = chips else trendingSwitch = chips
            }
            val line = TextView(this).apply {
                textSize = 14f
                setTextColor(themeColor(R.attr.appTextSecondary))
                setPadding((14 * d).toInt(), 0, 0, 0)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            box.addView(line, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (rowId == R.id.row_picked) { pickedLine = line; pickedName = name } else trendingLine = line
            val row = RecyclerView(this).apply {
                id = rowId
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(continueRow.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                setPadding(continueRow.paddingLeft, continueRow.paddingTop, continueRow.paddingRight, continueRow.paddingBottom)
                FocusBox.usePadding(this)
            }
            parent.addView(box, at++)
            parent.addView(row, at++)
        }
    }

    /** Fills both rows: Picked for you from what was watched, Trending from TMDB's week (saved for a day). */
    private fun loadSuggestions() {
        if (!::pickedAdapter.isInitialized) return
        suggestJob?.cancel()
        suggestJob = lifecycleScope.launch {
            try {
                val drawn = listsSeen
                val movies = allStreams(ContentType.MOVIE).await()
                val series = allStreams(ContentType.SERIES).await()
                if (drawn != listsSeen) return@launch
                val mixed = prefs.suggestRowsKind == 0
                for ((box, kind) in listOf(pickedSwitch to prefs.pickedKind, trendingSwitch to prefs.trendingKind)) box?.let { b ->
                    for (i in 0 until b.childCount) b.getChildAt(i).isActivated = b.getChildAt(i).tag == kind
                }
                if ("picked" !in prefs.homeRowHidden) {
                    val kind = if (mixed) null else ContentType.valueOf(prefs.pickedKind)
                    val picks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Taste.picked(this@HomeActivity, movies, series, kind) }
                    pickedBecause = picks.list.associate { "${it.item.type.name}:${it.item.id}" to it.because }
                    pickedName?.text = if (picks.personal) "Picked for you" else "Top picks for you"
                    showRow(R.id.row_picked, R.id.row_picked_title, pickedAdapter, picks.list.map { it.item })
                } else showRow(R.id.row_picked, R.id.row_picked_title, pickedAdapter, emptyList())
                if ("trending" !in prefs.homeRowHidden && Tmdb.hasKey) {
                    val kind = if (mixed) "all" else if (prefs.trendingKind == "SERIES") "tv" else "movie"
                    val cards = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val list = Trending.list(this@HomeActivity, kind)
                        val d = prefs.activeId?.let { Taste.data(it) }
                        // The playlist's titles by work (the first copy of each).
                        val wanted = list.map { e -> "tmdb/${if (e.trend.type == ContentType.MOVIE) "movie" else "show"}/${e.trend.id}" }.toHashSet()
                        val firsts = (movies + series).filter { s -> Taste.refOf(d, s)?.let { it in wanted } == true }.distinctBy { Taste.refOf(d, it) }
                        val byRef = Taste.cards(this@HomeActivity, firsts, movies + series, d).associateBy { Taste.refOf(d, it)!! }
                        list.map { e -> (byRef["tmdb/${if (e.trend.type == ContentType.MOVIE) "movie" else "show"}/${e.trend.id}"] ?: Trending.placeholder(e)) to e }
                    }
                    trendingEntries = cards.associate { it.first.id to it.second }
                    showRow(R.id.row_trending, R.id.row_trending_title, trendingAdapter, cards.map { it.first })
                } else showRow(R.id.row_trending, R.id.row_trending_title, trendingAdapter, emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The rows just stay as they were.
            }
        }
    }

    /** On the highlighted card of Picked for you: why it is there (Settings can turn this off). */
    private fun showPickedWhy(item: StreamItem) {
        val why = pickedBecause["${item.type.name}:${item.id}"]
        pickedLine?.text = if (prefs.pickedWhy && why != null) "·  $why" else ""
    }

    /** On the highlighted card of Trending: its place this week, and whether the playlist has it. */
    private fun showTrendLine(item: StreamItem) {
        val e = trendingEntries[item.id] ?: return
        val missing = Trending.isPlaceholder(item)
        val text = android.text.SpannableStringBuilder("·  #${e.rank} this week   ·   ")
        val start = text.length
        text.append(if (missing) "✕ Not in your playlist" else "✓ In your playlist")
        text.setSpan(android.text.style.ForegroundColorSpan(if (missing) 0xFFFF8A80.toInt() else 0xFF7EE2B8.toInt()), start, text.length, 0)
        trendingLine?.text = text
    }

    /** Leaving a row: its line empties. */
    private fun clearSuggestionLines(now: View?) {
        fun inside(id: Int): Boolean {
            var v: View? = now
            while (v != null) { if (v.id == id) return true; v = v.parent as? View }
            return false
        }
        if (!inside(R.id.row_picked)) pickedLine?.text = ""
        if (!inside(R.id.row_trending)) trendingLine?.text = ""
    }

    /** A green tick on titles the playlist has, a red cross on the others (this row only). */
    private fun markTrending(vh: ItemAdapter.VH, item: StreamItem) {
        val frame = vh.image.parent as? android.widget.FrameLayout ?: return
        val d = resources.displayMetrics.density
        val mark = frame.findViewWithTag<TextView>("trend_mark") ?: TextView(this).also { t ->
            t.tag = "trend_mark"
            t.gravity = android.view.Gravity.CENTER
            t.textSize = 14f
            t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            frame.addView(t, android.widget.FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.START).apply {
                leftMargin = (5 * d).toInt(); topMargin = (5 * d).toInt()
            })
        }
        // Glass (owner, 2026-11-13): a frosted dark disc with a thin coloured ring and a coloured mark,
        // like the app's glass chips, instead of a flat solid dot.
        val missing = Trending.isPlaceholder(item)
        val tone = if (missing) 0xFFFF8A80.toInt() else 0xFF7EE2B8.toInt()
        mark.text = if (missing) "✕" else "✓"
        mark.setTextColor(tone)
        mark.setShadowLayer(4 * d, 0f, 0f, 0x99000000.toInt())
        mark.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(0xCC2A3340.toInt(), 0xB30E1218.toInt())
            gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
            orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            setStroke((1.5f * d).toInt().coerceAtLeast(1), (tone and 0x00FFFFFF) or 0xCC000000.toInt())
        }
        mark.elevation = 3 * d
        mark.visibility = View.VISIBLE
    }

    /** Hold OK in Picked for you: "Not interested" takes it away, and alike titles get fewer points. */
    private fun askNotInterested(item: StreamItem) {
        AlertDialog.Builder(this)
            .setTitle(Versions.shownName(item))
            .setItems(arrayOf("Not interested")) { _, _ ->
                prefs.addNotInterested("${item.type.name}:${item.id}")
                Taste.forget()
                showToast(this, "Picked for you will show fewer titles like this", Toast.LENGTH_SHORT)
                loadSuggestions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A trending title the playlist doesn't have: poster, story, scores and its trailer; it can't play. */
    private fun showTrendInfo(e: Trending.Entry) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(20)) }
        val poster = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
        root.addView(poster, LinearLayout.LayoutParams(dp(160), dp(240)))
        Tmdb.cardPosterUrl(e.trend.poster)?.let { poster.load(it) { crossfade(false) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0) }
        root.addView(col, LinearLayout.LayoutParams(dp(460), LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply { text = "✕  Not in your playlist yet"; textSize = 14f; setTextColor(0xFFE24B4A.toInt()) })
        col.addView(TextView(this).apply {
            text = e.trend.name; textSize = 22f; setTextColor(themeColor(R.attr.appText))
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(6), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = listOfNotNull(e.trend.year, e.trend.genres, if (e.trend.type == ContentType.SERIES) "Series" else "Movie", "#${e.rank} this week").joinToString("   ·   ")
            textSize = 14f; setTextColor(themeColor(R.attr.appTextSecondary))
        })
        if (e.imdb != null || e.tomatoes != null) col.addView(TextView(this).apply {
            textSize = 14f; setPadding(0, dp(8), 0, 0)
            text = RatingBadges.line(this@HomeActivity, RatingBadges.Scores(e.imdb, e.tomatoes), themeColor(R.attr.appText))
        })
        e.trend.plot?.let { plot ->
            col.addView(TextView(this).apply {
                text = plot; textSize = 15f; maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(themeColor(R.attr.appText)); setPadding(0, dp(10), 0, dp(14))
            })
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(buttons)
        val dialog = android.app.Dialog(this)
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 16f; isFocusable = true; isClickable = true; gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_glass_choice)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.on_focus_text))
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { action() }
            buttons.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        }
        val trailer = button("Trailer") {
            lifecycleScope.launch {
                val key = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Tmdb.trailer(e.trend.type, e.trend.id) }.getOrNull() }
                val link = key?.let { "https://www.youtube.com/watch?v=$it" } ?: e.trailer
                if (link == null) { showToast(this@HomeActivity, "No trailer for this title", Toast.LENGTH_SHORT); return@launch }
                val intents = listOfNotNull(key?.let { Intent(Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$it")) }, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                if (intents.none { runCatching { startActivity(it) }.isSuccess }) showToast(this@HomeActivity, "Install YouTube to watch trailers", Toast.LENGTH_LONG)
            }
        }
        button("Close") { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_panel)
        dialog.setOnShowListener { trailer.requestFocus() }
        dialog.show()
        Diag.log("ui", "trending info page opened (not in the playlist)${Diag.title(e.trend.name)}")
    }

    /** Top rated movies and Top rated series (1.1E): IMDb 7.5 and up from saved scores; 6 titles at least, 100 at most. */
    private fun loadTopRated() {
        if (!::topSeriesAdapter.isInitialized) return
        topRow(ContentType.MOVIE, "top_movies", R.id.row_top_rated, R.id.row_top_rated_title, topRatedAdapter)
        topRow(ContentType.SERIES, "top_series", R.id.row_top_series, R.id.row_top_series_title, topSeriesAdapter)
    }

    private fun topRow(type: ContentType, key: String, rowId: Int, titleId: Int, adapter: ItemAdapter) {
        if (key in prefs.homeRowHidden || !Ratings.isOn) {
            showRow(rowId, titleId, adapter, emptyList())
            return
        }
        lifecycleScope.launch {
            try {
                val drawn = listsSeen
                val all = allStreams(type).await()
                if (drawn != listsSeen) return@launch
                // Up to 100 (owner, 2026-11-13); copies of one film (HD, 4K, dubbed) take one place.
                val top = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val d = prefs.activeId?.let { Taste.data(it) }
                    Taste.cards(this@HomeActivity, Ratings.topRated(all, 400).distinctBy { Taste.sameKey(d, it) }.take(100), all, d)
                }
                adapter.favorites = prefs.favoriteIds(type)
                showRow(rowId, titleId, adapter, if (top.size >= 6) top else emptyList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row just stays hidden.
            }
        }
    }

    private fun recentRow(type: ContentType, shown: Boolean, rowId: Int, titleId: Int, adapter: ItemAdapter) {
        if (!shown) {
            showRow(rowId, titleId, adapter, emptyList())
            return
        }
        lifecycleScope.launch {
            try {
                // Series: newest by when the provider last changed them (a new episode moves one up).
                val drawn = listsSeen
                val all = allStreams(type).await()
                if (drawn != listsSeen) return@launch
                val recent = all.filter { it.added > 0 }.sortedByDescending { it.added }.take(25)
                adapter.favorites = prefs.favoriteIds(type)
                showRow(rowId, titleId, adapter, recent)
                if (!rowsReadyLogged && recent.isNotEmpty()) {
                    rowsReadyLogged = true
                    Diag.ready("Home", "recently added rows", openedAt)
                }
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
        resumeWatching(entry, api, withList = true)
    }

    /** Throw away the lists saved on the device so everything reloads from the server. */
    private fun refreshLists() {
        api.clearListCache()
        Session.streamCache.clear()
        Session.categoryCache.clear()
        allLists.clear()
        showToast(this, "Lists will reload from the server", Toast.LENGTH_SHORT)
        loadCounts()
        loadRecentlyAdded()
    }

    private fun showAccount() {
        val info = Session.userInfo
        val host = api.account.base.substringAfter("://")
        val playlistName = prefs.activePlaylist?.name ?: host
        // Top bar: playlist chip and expiry chip.
        findViewById<TextView>(R.id.playlist_name).text = playlistName
        Appearance.bindAvatar(findViewById(R.id.playlist_initial), prefs, prefs.activePlaylist?.id ?: host, playlistName, letterSizeSp = 14f, emojiSizeSp = 16f)
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
