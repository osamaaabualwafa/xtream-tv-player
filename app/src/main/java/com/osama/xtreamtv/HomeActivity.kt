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
            onBindExtra = { vh, item ->
                // 1.1E: a followed series with an episode due within two weeks says when.
                if (item.type == ContentType.SERIES) RatingsExtras.cardLine(RatingsExtras.saved(item.type, item.id))?.let { line ->
                    vh.subtitle.text = line
                    vh.subtitle.setTextColor(0xFF9FD8B8.toInt())
                    vh.subtitle.visibility = View.VISIBLE
                }
            })
        recentAdapter = row(R.id.row_recent, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
        recentSeriesAdapter = row(R.id.row_recent_series, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
        newAdapter = row(R.id.row_new, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
        topRatedAdapter = row(R.id.row_top_rated, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
        topSeriesAdapter = row(R.id.row_top_series, R.layout.item_poster_row, { _, item -> startActivity(DetailsActivity.intent(this, item)) })
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
        loadNewEpisodes()
        prepareSections()
        // Scores found while away (a title's page, pre-fill) show on the cards already there.
        for (a in listOf(newAdapter, recentAdapter, recentSeriesAdapter, topRatedAdapter, topSeriesAdapter)) a.notifyItemRangeChanged(0, a.itemCount, "scores")
        // Pre-fill: once 20 s after opening (if nothing is pressed), then each idle minute.
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

    private fun lookKey() = "${prefs.homeStyle}/${prefs.themeIndex}/${prefs.glassScene}/${prefs.glassStyle}/${prefs.focusStyle}/${prefs.movingBackground}/${prefs.paletteIndex}/${prefs.accentIndex}/${prefs.backgroundStyle}/${prefs.textSize}/${prefs.uiSize}/${prefs.backdropFollows}/${prefs.featuredBanner}/${prefs.homeRowOrder.joinToString(",")}|${prefs.homeRowHidden.sorted().joinToString(",")}/${prefs.activeId?.let { prefs.avatar(it) + prefs.avatarColor(it) }}"

    override fun onPause() {
        super.onPause()
        prefillJob?.cancel()
        window.decorView.removeCallbacks(prefillCheck)
        MiniPlayerBar.detach(this)
        findViewById<View?>(R.id.featured)?.removeCallbacks(rotateFeatured)
        if (::clock.isInitialized) clock.removeCallbacks(clockTicker)
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
        // Held down, INFO marks a problem instead (see Diag), so act on release and only when short.
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
    private val prefillCheck = object : Runnable {
        override fun run() {
            val idle = android.os.SystemClock.uptimeMillis() - lastKeyAt >= 60_000
            if (idle && prefillJob?.isActive != true && Ratings.isOn) {
                val items = buildList {
                    for (a in listOf(continueAdapter, favoritesAdapter, newAdapter, recentAdapter, recentSeriesAdapter)) addAll(a.items)
                    addAll(prefs.favorites(ContentType.MOVIE))
                    addAll(prefs.favorites(ContentType.SERIES))
                }
                prefillJob = lifecycleScope.launch {
                    Ratings.prefill(api, items)
                    loadTopRated()
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
        val rows = listOf(R.id.row_new, R.id.row_continue, R.id.row_channels, R.id.row_favorites, R.id.row_top_rated, R.id.row_top_series, R.id.row_recent, R.id.row_recent_series)
            .map { focusMap.section(roots = views(it)) }
            .plus(listOfNotNull(favSwitch?.let { s -> focusMap.section(roots = { listOf(s) }) }))
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
    ): ItemAdapter {
        val adapter = ItemAdapter(
            grid = layout == R.layout.item_poster_row, onClick = onClick, onLongClick = onLongClick, layout = layout,
            onFocusItem = { item -> if (item.type != ContentType.LIVE) backdrop?.show(item.image) },
            onBindExtra = onBindExtra,
        )
        findViewById<RecyclerView>(viewId).apply {
            layoutManager = RowLayoutManager(this@HomeActivity)
            this.adapter = adapter
        }
        return adapter
    }

    private fun showRow(viewId: Int, titleId: Int, adapter: ItemAdapter, items: List<StreamItem>) {
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
                setOnFocusChangeListener { _, has -> if (has && prefs.homeFavoritesKind != kind) { prefs.homeFavoritesKind = kind; loadLocalRows() } }
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
        lifecycleScope.launch {
            try {
                val movies = allStreams(ContentType.MOVIE).await().filter { it.added > 0 }.sortedByDescending { it.added }.take(5)
                val series = allStreams(ContentType.SERIES).await()
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

    private fun loadNewEpisodes() {
        lifecycleScope.launch {
            try {
                val all = allStreams(ContentType.SERIES).await()
                val ids = prefs.newEpisodeSeries(all)
                val items = all.filter { it.id in ids }.sortedByDescending { it.added }
                newAdapter.newIds = ids
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

    /** Top rated movies and Top rated series (1.1E): IMDb 7.5 and up from saved scores; 6 titles at least. */
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
                val top = Ratings.topRated(allStreams(type).await(), 20)
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
                val all = allStreams(type).await()
                val recent = all.filter { it.added > 0 }.sortedByDescending { it.added }.take(25)
                adapter.favorites = prefs.favoriteIds(type)
                showRow(rowId, titleId, adapter, recent)
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
