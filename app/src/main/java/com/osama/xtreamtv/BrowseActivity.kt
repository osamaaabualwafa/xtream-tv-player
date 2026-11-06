package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import coil.load
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        const val EXTRA_TYPE = "type"
        /** Dock Home's Favorites tile: open on the ★ Favorites category. */
        const val EXTRA_OPEN_FAVORITES = "open_favorites"
        private const val CAT_ALL = "__all__"
        private const val CAT_FAV = "__fav__"
        private const val CAT_CONTINUE = "__continue__"
        private const val CAT_HIDDEN = "__hidden__"
        /** 1.1E: titles with IMDb 7.5 and up (saved scores), best first. */
        private const val CAT_TOP = "__top__"
    }

    private lateinit var type: ContentType
    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs

    private lateinit var categoriesView: RecyclerView
    private lateinit var itemsView: RecyclerView
    private lateinit var categoryTitle: TextView
    private lateinit var search: EditText
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    private lateinit var categoryAdapter: TextAdapter<Category>
    private lateinit var itemAdapter: ItemAdapter

    /** Settings > Movies and series layout (live channels have no preview, so B4 falls back to B1). */
    private var style = Appearance.BROWSE_SIDEBAR
    /** B2 / B4: the category list that slides over the posters, and the rail that opens it. */
    private var panel: View? = null
    /** Live TV: small muted preview of the highlighted channel. */
    private var channelPreview: ChannelPreview? = null
    /** V1: background follows the highlighted poster. */
    private var backdrop: FocusBackdrop? = null
    private var rail: View? = null

    /** The server's categories (before the user's order and hidden list are applied). */
    private var serverCategories: List<Category> = emptyList()

    private var allItems: List<StreamItem> = emptyList()
    private var currentCategory: Category? = null
    private var loadJob: Job? = null
    private var focusedCategory: Category? = null
    private val selectFocused = Runnable {
        val cat = focusedCategory
        if (cat != null && cat != currentCategory && categoriesView.hasFocus()) selectCategory(cat)
    }

    /** Items per category id (plus CAT_ALL / CAT_FAV / CAT_CONTINUE), shown next to each category. */
    private var counts: Map<String, Int> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        type = try {
            ContentType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "LIVE")
        } catch (e: IllegalArgumentException) {
            ContentType.LIVE
        }
        Appearance.apply(this)
        style = prefs.browseStyle.let { if (type == ContentType.LIVE && it == Appearance.BROWSE_PREVIEW) Appearance.BROWSE_SIDEBAR else it }
        setContentView(
            when (style) {
                Appearance.BROWSE_FOLDING -> R.layout.activity_browse_folding
                Appearance.BROWSE_CHIPS -> R.layout.activity_browse_chips
                Appearance.BROWSE_PREVIEW -> R.layout.activity_browse_preview
                else -> R.layout.activity_browse
            }
        )
        panel = findViewById(R.id.category_panel)
        rail = findViewById(R.id.category_rail)

        categoriesView = findViewById(R.id.categories)
        // No row animations: rows redrawn (highlight, counts) while the list slides would glide
        // over each other for a moment. (Rows half scrolled out fade: see softEdges below.)
        categoriesView.itemAnimator = null
        itemsView = findViewById(R.id.items)
        // No change animations: a new category replaces the posters in one step.
        itemsView.itemAnimator = null
        categoryTitle = findViewById(R.id.category_title)
        search = findViewById(R.id.search)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        findViewById<TextView>(R.id.type_title).text = when (type) {
            ContentType.LIVE -> "Live TV"
            ContentType.MOVIE -> "Movies"
            ContentType.SERIES -> "Series"
        }

        categoryAdapter = TextAdapter(
            if (style == Appearance.BROWSE_CHIPS) R.layout.item_category_chip else R.layout.item_category,
            bind = { vh, cat ->
                vh.title.text = cat.name
                vh.subtitle?.text = counts[cat.id]?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) } ?: ""
            },
            onClick = { _, cat ->
                if (cat.id == CAT_HIDDEN) {
                    showHiddenCategories()
                } else {
                    selectCategory(cat)
                    closePanel()
                }
            },
            onLongClick = { _, cat -> categoryOptions(cat) },
            // Moving through the categories shows each one's items (after a short pause, so
            // scrolling quickly past many categories doesn't load each of them).
            onFocus = { _, cat ->
                categoriesView.removeCallbacks(selectFocused)
                stopNeighbours()
                focusedCategory = cat
                if (cat.id != CAT_HIDDEN) categoryAdapter.activeIndex = categoryAdapter.items.indexOf(cat)
                if (cat.id != CAT_HIDDEN && cat != currentCategory) categoriesView.postDelayed(selectFocused, 180)
            },
        )
        // Long category lists glide like the posters (the highlight stays near the middle).
        categoriesView.layoutManager = RowLayoutManager(
            this, if (style == Appearance.BROWSE_CHIPS) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL, center = true,
        )
        categoriesView.tuneForTv()
        categoriesView.softEdges()
        categoriesView.adapter = categoryAdapter

        val live = type == ContentType.LIVE
        val preview = style == Appearance.BROWSE_PREVIEW
        itemAdapter = ItemAdapter(
            grid = !live,
            onClick = { index, item -> open(index, item) },
            onLongClick = { _, item -> onLongPress(item) },
            layout = if (preview) R.layout.item_poster_small else null,
            onFocusItem = if (live) { item -> channelPreview?.focus(item) } else { item -> showInfo(item); backdrop?.show(item.image) },
            onBindExtra = if (live) { vh, item -> bindNowPlaying(vh, item) } else null,
        )
        itemsView.layoutManager = when {
            live -> RowLayoutManager(this, LinearLayoutManager.VERTICAL, center = true)
            preview -> RowLayoutManager(this)
            style == Appearance.BROWSE_SIDEBAR || style == Appearance.BROWSE_FOLDING -> CenterGridLayoutManager(this, 6)
            else -> CenterGridLayoutManager(this, 8)
        }
        itemsView.tuneForTv()
        setupFocusMap()

        if (!live && style != Appearance.BROWSE_PREVIEW) backdrop = FocusBackdrop.attach(this)
        if (live && prefs.channelPreview) {
            // Preview in the top-right corner of the list area; the rows leave room for it.
            val p = ChannelPreview(this, api, prefs)
            (itemsView.parent as? android.widget.FrameLayout)?.let { frame ->
                frame.addView(p.view)
                val gap = ((ChannelPreview.WIDTH_DP + 16) * resources.displayMetrics.density).toInt()
                itemsView.setPadding(itemsView.paddingLeft, itemsView.paddingTop, gap, itemsView.paddingBottom)
                channelPreview = p
            }
        }
        // Glass: the channel list sits in one rounded glass panel, stopping short of the preview.
        if (live && prefs.themeIndex == Appearance.THEME_GLASS && style == Appearance.BROWSE_SIDEBAR) {
            val density = resources.displayMetrics.density
            val pad = (14 * density).toInt()
            val inset = if (channelPreview != null) itemsView.paddingRight else 0
            FocusBox.usePadding(itemsView)
            itemsView.background = android.graphics.drawable.InsetDrawable(
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_glass_card), 0, 0, inset, 0,
            )
            // After the background: setting one replaces the view's padding with the drawable's.
            itemsView.setPadding(pad, pad, inset + pad, pad)
            // Rows scrolling away disappear at the panel's rounded edge.
            itemsView.clipToOutline = true
            itemsView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, v.width - inset, v.height, 24 * density)
            }
        }

        // B2 / B4: the rail opens the category list; Back from the posters opens it too.
        rail?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !closingPanel) openPanel() }
        if (panel != null) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (panel?.visibility != View.VISIBLE) {
                        openPanel()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
        }
        itemsView.adapter = itemAdapter
        // Scores filled in on the posters: Top rated's count follows (once a burst has settled).
        val countTop = Runnable { Session.streamCache["${type.name}:$CAT_ALL"]?.let { all -> updateTopCount(Ratings.topRated(all).size) } }
        itemAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                if (payload != "scores") return
                itemsView.removeCallbacks(countTop)
                itemsView.postDelayed(countTop, 800)
            }
        })

        search.doAfterTextChanged { applyFilter() }
        // Sort (Movies and Series): left of the search box; Up from the posters lands on the search
        // box first, Left from there reaches Sort. Info on the posters opens the same list.
        if (type != ContentType.LIVE) findViewById<TextView?>(R.id.sort_button)?.setOnClickListener { sortOptions() }
        // Review 1.1B: the plate shows the highlighted item only; in the search box nothing is.
        val before = search.onFocusChangeListener
        search.setOnFocusChangeListener { v, hasFocus ->
            before?.onFocusChange(v, hasFocus)
            if (hasFocus) blankInfo()
        }
        if (live) {
            val guide = findViewById<View>(R.id.tv_guide)
            guide.visibility = View.VISIBLE
            guide.setOnClickListener {
                if (itemAdapter.items.isEmpty()) return@setOnClickListener
                Session.zapList = itemAdapter.items
                Session.zapTitle = currentCategory?.name
                startActivity(GuideActivity.intent(this, 0, fromPlayer = false))
            }
        }
        // The keyboard's search key closes it and jumps to the results, so D-pad users aren't stuck in the box.
        search.setOnEditorActionListener { v, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            itemsView.post { (itemsView.layoutManager?.findViewByPosition(0) ?: itemsView).requestFocus() }
            true
        }

        loadCategories()
    }

    override fun onPause() {
        super.onPause()
        MiniPlayerBar.detach(this)
        // Free the stream before the player opens (providers often allow one connection).
        channelPreview?.release()
    }

    override fun onResume() {
        super.onResume()
        MiniPlayerBar.attach(this)
        if (!::itemAdapter.isInitialized) return
        // Scores found while away (a title's page) show on the posters already there.
        itemAdapter.notifyItemRangeChanged(0, itemAdapter.itemCount, "scores")
        // Back from the player: start the preview of the highlighted channel again.
        currentFocus?.let { itemsView.findContainingViewHolder(it) }?.bindingAdapterPosition
            ?.let { itemAdapter.items.getOrNull(it) }?.let { channelPreview?.focus(it) }
        if (counts.isNotEmpty()) refreshLocalCounts()
        itemAdapter.favorites = prefs.favoriteIds(type)
        // Favorites and progress change on other screens: refresh the local lists in place.
        val cat = currentCategory
        if (cat != null && (cat.id == CAT_FAV || cat.id == CAT_CONTINUE)) {
            val fresh = localItems(cat.id)
            if (fresh != allItems) {
                allItems = fresh
                applyFilter(scrollToTop = false)
                return
            }
        }
        itemAdapter.notifyItemRangeChanged(0, itemAdapter.itemCount, "refresh")
    }

    // ---- Live TV: "Now: programme" and its progress under each channel (fetched as rows appear).
    private val epg = HashMap<String, List<EpgEntry>>()
    private val epgLoading = HashSet<String>()
    private val epgSlots = kotlinx.coroutines.sync.Semaphore(4)

    private fun bindNowPlaying(vh: ItemAdapter.VH, item: StreamItem) {
        val now = epg[item.id]?.firstOrNull()
        val start = now?.start
        val end = now?.end
        vh.epgNow?.apply {
            text = now?.let { "Now:  ${it.title}" } ?: ""
            visibility = if (now == null) View.GONE else View.VISIBLE
        }
        vh.epgBar?.apply {
            if (start != null && end != null && end > start) {
                val nowSec = System.currentTimeMillis() / 1000
                progress = ((nowSec - start) * 1000 / (end - start)).toInt().coerceIn(0, 1000)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        if (epg.containsKey(item.id) || !epgLoading.add(item.id)) return
        lifecycleScope.launch {
            val list = try {
                epgSlots.acquire()
                try {
                    // Scrolled past while waiting its turn: skip it (it loads again if it comes back).
                    if (!nearScreen(item.id)) {
                        epgLoading.remove(item.id)
                        return@launch
                    }
                    api.shortEpg(item.id)
                } finally {
                    epgSlots.release()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            epg[item.id] = list
            epgLoading.remove(item.id)
            if (list.isNotEmpty()) {
                // Later, not now: a saved guide arrives while this row is being laid out, and changing
                // the list during its layout closes the app.
                itemsView.post {
                    itemAdapter.items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { itemAdapter.notifyItemChanged(it, "epg") }
                }
            }
        }
    }

    /** The row is on screen or close to it. */
    private fun nearScreen(id: String): Boolean {
        val lm = itemsView.layoutManager as? LinearLayoutManager ?: return true
        val pos = itemAdapter.items.indexOfFirst { it.id == id }
        return pos >= 0 && pos in lm.findFirstVisibleItemPosition() - 4..lm.findLastVisibleItemPosition() + 4
    }

    private fun openPanel() {
        val p = panel ?: return
        p.visibility = View.VISIBLE
        // The open category takes the highlight before the rail is hidden (hiding the rail while it
        // held the highlight let Android drop it on the first category, which then opened).
        val index = categoryAdapter.activeIndex.coerceAtLeast(0)
        categoriesView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        rail?.visibility = View.GONE
        setGridColumns()
        categoriesView.post {
            val open = categoriesView.findViewHolderForAdapterPosition(index)?.itemView
            if (open != null && !open.hasFocus()) open.requestFocus() else if (open == null) categoriesView.requestFocus()
        }
    }

    /** B2 / B4: hide the category list and go back to the posters. */
    private fun closePanel() {
        val p = panel ?: return
        if (p.visibility != View.VISIBLE) return
        // The highlight goes to the posters before the list is hidden: hiding the list while it held
        // the highlight let Android drop it on the rail, which reopened the list on its first
        // category (B4 Preview: Right never reached the posters).
        closingPanel = true
        val lm = itemsView.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        (lm?.findViewByPosition(first) ?: itemsView).requestFocus()
        p.visibility = View.GONE
        rail?.visibility = View.VISIBLE
        setGridColumns()
        focusFirstItem()
        itemsView.post { closingPanel = false }
    }

    private var closingPanel = false

    /** B2: fewer columns while the list is open so the posters keep their size. */
    private fun setGridColumns() {
        if (style != Appearance.BROWSE_FOLDING) return
        (itemsView.layoutManager as? GridLayoutManager)?.spanCount = if (panel?.visibility == View.VISIBLE) 6 else 8
    }

    private var neighbourJob: Job? = null
    private val neighbourPosters = ArrayList<coil.request.Disposable>()

    private fun stopNeighbours() {
        neighbourJob?.cancel()
        neighbourJob = null
        for (d in neighbourPosters) d.dispose()
        neighbourPosters.clear()
    }

    /**
     * While a category is on screen, get the two below it (and the one above) ready: their lists
     * into memory and their first posters downloading, so landing on them shows everything at once.
     * Moving on cancels it and starts again around the new category.
     */
    private fun prepareNeighbours(category: Category) {
        stopNeighbours()
        val list = categoryAdapter.items
        val index = list.indexOf(category)
        if (index < 0) return
        val around = listOf(index + 1, index + 2, index - 1).mapNotNull { list.getOrNull(it) }
            .filter { it.id != CAT_FAV && it.id != CAT_CONTINUE && it.id != CAT_TOP && it.id != CAT_HIDDEN }
        if (around.isEmpty()) return
        val sample = (0 until itemsView.childCount).asSequence()
            .mapNotNull { itemsView.getChildAt(it).findViewById<android.widget.ImageView?>(R.id.image) }
            .firstOrNull { it.width > 0 && it.height > 0 }
        val screenful = itemsView.childCount.coerceIn(6, 24)
        neighbourJob = lifecycleScope.launch {
            // A moment's pause: while the remote is held down nothing is fetched for categories flown past.
            kotlinx.coroutines.delay(400)
            val loader = coil.Coil.imageLoader(this@BrowseActivity)
            for (c in around) {
                val items = try {
                    loadStreams(if (c.id == CAT_ALL) null else c.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                if (sample == null) continue
                for (item in items.take(screenful)) {
                    val url = item.image?.takeIf { it.isNotBlank() } ?: continue
                    neighbourPosters += loader.enqueue(coil.request.ImageRequest.Builder(this@BrowseActivity).data(url).size(sample.width, sample.height).build())
                }
            }
        }
    }

    /**
     * Load the first screenful of [list]'s posters at the size the cards draw them (so they come
     * straight from memory when the grid swaps), waiting at most about half a second.
     */
    private suspend fun warmPosters(list: List<StreamItem>) {
        val sample = (0 until itemsView.childCount).asSequence()
            .mapNotNull { itemsView.getChildAt(it).findViewById<android.widget.ImageView?>(R.id.image) }
            .firstOrNull { it.width > 0 && it.height > 0 } ?: return
        val w = sample.width
        val h = sample.height
        val urls = list.asSequence().mapNotNull { it.image?.takeIf { u -> u.isNotBlank() } }
            .take(itemsView.childCount.coerceIn(6, 24)).toList()
        if (urls.isEmpty()) return
        val loader = coil.Coil.imageLoader(this)
        kotlinx.coroutines.withTimeoutOrNull(350) {
            kotlinx.coroutines.coroutineScope {
                urls.map { url ->
                    async {
                        loader.execute(coil.request.ImageRequest.Builder(this@BrowseActivity).data(url).size(w, h).build())
                    }
                }.forEach { it.await() }
            }
        }
    }

    /** The open category's row (scrolled into view if needed). */
    private fun focusOpenCategory() {
        val index = categoryAdapter.activeIndex.coerceAtLeast(0)
        categoriesView.findViewHolderForAdapterPosition(index)?.itemView?.let { it.requestFocus(); return }
        categoriesView.scrollToPosition(index)
        categoriesView.post { categoriesView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun focusFirstItem() {
        itemsView.post {
            val lm = itemsView.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            (lm?.findViewByPosition(first) ?: itemsView).requestFocus()
        }
    }

    private val focusMap = FocusMap { currentFocus }
    private lateinit var itemsSection: FocusMap.Section

    /**
     * Sections: categories (or the rail that opens them), the search and guide buttons above the
     * items, and the items. Left from the items or the buttons returns to the open category, so moving
     * around never changes the category by itself.
     */
    private fun setupFocusMap() {
        val header = focusMap.section {
            listOfNotNull(search, findViewById<View?>(R.id.sort_button), findViewById<View?>(R.id.tv_guide)).filter { it.isShown }
        }
        val categories = focusMap.section { listOf<View>(categoriesView) }
        itemsSection = focusMap.section { listOf<View>(itemsView) }
        header.exits[View.FOCUS_DOWN] = {
            if (style == Appearance.BROWSE_CHIPS) categoryRow() else itemsSection.entry()
        }
        itemsSection.exits[View.FOCUS_UP] = {
            if (style == Appearance.BROWSE_CHIPS) categoryRow() else search.takeIf { it.isShown }
        }
        if (style == Appearance.BROWSE_CHIPS) {
            categories.exits[View.FOCUS_UP] = { search.takeIf { it.isShown } }
            categories.exits[View.FOCUS_DOWN] = { itemsSection.entry() }
        } else {
            val toCategory: () -> View? = {
                val r = rail
                if (r != null && panel?.visibility != View.VISIBLE) r else {
                    focusOpenCategory()
                    null
                }
            }
            header.exits[View.FOCUS_LEFT] = toCategory
            itemsSection.exits[View.FOCUS_LEFT] = toCategory
            categories.exits[View.FOCUS_RIGHT] = {
                if (itemAdapter.itemCount > 0) {
                    // Show the highlighted category first if it isn't open yet.
                    categoriesView.removeCallbacks(selectFocused)
                    focusedCategory?.takeIf { it != currentCategory && it.id != CAT_HIDDEN }?.let { selectCategory(it) }
                    focusFirstItem()
                }
                null
            }
            rail?.let { r ->
                val railSection = focusMap.section { listOf(r) }
                railSection.exits[View.FOCUS_RIGHT] = { itemsSection.entry() }
            }
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, new -> focusMap.note(new) }
    }

    /** The open category's chip. */
    private fun categoryRow(): View? {
        focusOpenCategory()
        return null
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Menu key on a category = the same options as holding OK.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_MENU && categoriesView.hasFocus()) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val pos = currentFocus?.let { categoriesView.findContainingViewHolder(it)?.bindingAdapterPosition } ?: -1
                categoryAdapter.items.getOrNull(pos)?.let { categoryOptions(it) }
            }
            return true
        }
        // Info key (a short press) on the posters: how they're sorted (1.1E: IMDb score). Holding
        // Info stays the diagnostics Mark shortcut.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_INFO && itemsView.hasFocus() && sortApplies()) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled && event.eventTime - event.downTime < 600) sortOptions()
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount > 0) return super.dispatchKeyEvent(event)
            return true
        }
        // Right from the sliding category list closes it.
        if (panel?.visibility == View.VISIBLE && categoriesView.hasFocus() &&
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) closePanel()
            return true
        }
        if (focusMap.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                serverCategories = Session.categoryCache[type] ?: api.categories(type).also { Session.categoryCache[type] = it }
                val list = buildCategoryList()
                val cats = list.filter { it.id !in setOf(CAT_CONTINUE, CAT_FAV, CAT_TOP, CAT_ALL, CAT_HIDDEN) }
                categoryAdapter.submit(list)
                loadCounts()
                // Open "Continue watching" when there is something to resume, otherwise the
                // first real category ("All" can be tens of thousands of items).
                val firstIndex = when {
                    intent.getBooleanExtra(EXTRA_OPEN_FAVORITES, false) && list.any { it.id == CAT_FAV } -> list.indexOfFirst { it.id == CAT_FAV }
                    type != ContentType.LIVE && prefs.continueWatching(type).isNotEmpty() -> 0
                    cats.isEmpty() -> list.indexOfFirst { it.id == CAT_ALL }
                    else -> list.indexOf(cats.first())
                }
                selectCategory(list[firstIndex])
                // No scrollToPosition: it would pin this row to the top and hide
                // Continue watching / Favorites above it.
                categoriesView.post {
                    categoriesView.findViewHolderForAdapterPosition(firstIndex)?.itemView?.requestFocus()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoading(false)
                showMessage("Couldn't load categories.\n${e.message ?: ""}")
            }
        }
    }

    /** Continue watching, Favorites, All, then the categories in the user's order, minus hidden ones. */
    private fun buildCategoryList(): List<Category> {
        val hidden = prefs.hiddenCategories(type)
        val order = prefs.categoryOrder(type)
        val visible = serverCategories.filter { it.id !in hidden }
        val rank = order.withIndex().associate { it.value to it.index }
        // Moved categories first (in their saved order), then the rest as the server lists them.
        val sorted = visible.sortedWith(compareBy { rank[it.id] ?: (order.size + visible.indexOf(it)) })
        val list = ArrayList<Category>()
        if (type != ContentType.LIVE) list.add(Category(CAT_CONTINUE, "▶ Continue watching"))
        list.add(Category(CAT_FAV, "★ Favorites"))
        if (type != ContentType.LIVE && Ratings.isOn) list.add(Category(CAT_TOP, "Top rated"))
        list.add(Category(CAT_ALL, "All"))
        list.addAll(sorted)
        if (hidden.any { id -> serverCategories.any { it.id == id } }) list.add(Category(CAT_HIDDEN, "⊘  Hidden categories"))
        return list
    }

    /** Rebuild the list after a move / hide, keeping the selection and focusing [focusId]. */
    private fun refreshCategoryList(focusId: String?) {
        val list = buildCategoryList()
        categoryAdapter.submit(list)
        currentCategory?.let { c -> categoryAdapter.activeIndex = list.indexOfFirst { it.id == c.id } }
        refreshLocalCounts()
        val index = list.indexOfFirst { it.id == focusId }.takeIf { it >= 0 } ?: return
        categoriesView.scrollToPosition(index)
        categoriesView.post { categoriesView.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    /** Hold OK (or the menu key) on a category: move it or hide it. */
    private fun categoryOptions(cat: Category) {
        if (cat.id in setOf(CAT_CONTINUE, CAT_FAV, CAT_TOP, CAT_ALL, CAT_HIDDEN)) return
        val ids = buildCategoryList().filter { it.id !in setOf(CAT_CONTINUE, CAT_FAV, CAT_TOP, CAT_ALL, CAT_HIDDEN) }.map { it.id }.toMutableList()
        val at = ids.indexOf(cat.id)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(cat.name)
            .setItems(arrayOf("Move to top", "Move up", "Move down", "Hide this category")) { _, which ->
                when (which) {
                    0 -> { ids.removeAt(at); ids.add(0, cat.id) }
                    1 -> if (at > 0) { ids.removeAt(at); ids.add(at - 1, cat.id) }
                    2 -> if (at < ids.size - 1) { ids.removeAt(at); ids.add(at + 1, cat.id) }
                    3 -> {
                        prefs.setHiddenCategories(type, prefs.hiddenCategories(type) + cat.id)
                        showToast(this, "Hidden. Find it under \"Hidden categories\" at the bottom.", Toast.LENGTH_LONG)
                        refreshCategoryList(null)
                        return@setItems
                    }
                }
                prefs.setCategoryOrder(type, ids)
                refreshCategoryList(cat.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Hidden categories": tick the ones to show again. */
    private fun showHiddenCategories() {
        val hiddenIds = prefs.hiddenCategories(type)
        val hidden = serverCategories.filter { it.id in hiddenIds }
        if (hidden.isEmpty()) return
        val checked = BooleanArray(hidden.size)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Show again")
            .setMultiChoiceItems(hidden.map { it.name }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Show") { _, _ ->
                val back = hidden.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                prefs.setHiddenCategories(type, hiddenIds - back)
                refreshCategoryList(back.firstOrNull())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lists stored on the device (no network). */
    private fun localItems(categoryId: String): List<StreamItem> = when (categoryId) {
        CAT_FAV -> prefs.favorites(type)
        CAT_CONTINUE -> prefs.continueWatching(type).map { e ->
            StreamItem(
                type = type,
                id = e.key,
                name = listOfNotNull(e.title, e.subtitle).joinToString("  ·  "),
                image = e.image,
                categoryId = null,
                ext = e.ext,
                num = null,
                rating = null,
                note = e.progressText(),
                progress = e.progressFraction(),
                ratingId = e.seriesId ?: e.id,
            )
        }
        else -> emptyList()
    }

    private fun selectCategory(category: Category) {
        currentCategory = category
        // The items are about to change: coming back lands on the first one, not an old row.
        itemsSection.forget()
        // The strip described the old category's item: blank it (keeping its space) until one is highlighted.
        findViewById<View>(R.id.info_strip).visibility = View.INVISIBLE
        categoryAdapter.activeIndex = categoryAdapter.items.indexOf(category)
        // A list already in memory changes title and posters together (after its posters are ready).
        if (cachedStreams(if (category.id == CAT_ALL) null else category.id) == null) showCategoryTitle(category, counts[category.id])
        loadJob?.cancel()
        message.visibility = View.GONE
        if (category.id == CAT_FAV || category.id == CAT_CONTINUE) {
            showLoading(false)
            allItems = localItems(category.id)
            showCategoryTitle(category, allItems.size)
            applyFilter()
            return
        }
        if (category.id == CAT_TOP) {
            loadJob = lifecycleScope.launch {
                val cached = cachedStreams(null)
                if (cached == null) showLoading(true)
                val all = cached ?: runCatching { loadStreams(null) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                showLoading(false)
                allItems = Ratings.topRated(all)
                showCategoryTitle(category, allItems.size)
                updateTopCount(allItems.size)
                applyFilter()
            }
            return
        }
        // Already on the device (most categories once the full list is in): show it at once,
        // without a loading pause or an empty grid in between.
        // Already on the device (most categories once the full list is in): no loading pause.
        cachedStreams(if (category.id == CAT_ALL) null else category.id)?.let { cached ->
            showLoading(false)
            loadJob = lifecycleScope.launch {
                // The current posters stay until the new ones are ready, then the grid swaps in one
                // go: no blank (dark) tiles flashing while moving through the categories.
                warmPosters(cached)
                allItems = cached
                showCategoryTitle(category, cached.size)
                applyFilter()
                prepareNeighbours(category)
            }
            return
        }
        loadJob = lifecycleScope.launch {
            showLoading(true)
            try {
                val loaded = loadStreams(if (category.id == CAT_ALL) null else category.id)
                warmPosters(loaded)
                allItems = loaded
                showCategoryTitle(category, allItems.size)
                // Categories the full list doesn't cover get their count once opened.
                if (counts.isNotEmpty() && counts[category.id] != allItems.size) {
                    counts = HashMap(counts).apply { put(category.id, allItems.size) }
                    categoryAdapter.items.indexOf(category).takeIf { it >= 0 }?.let { categoryAdapter.notifyItemChanged(it, "count") }
                }
                showLoading(false)
                applyFilter()
                prepareNeighbours(category)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoading(false)
                allItems = emptyList()
                showMessage("Couldn't load this category.\n${e.message ?: ""}")
            }
        }
    }

    /** "beIN Sports Vega  ·  18 channels" above the list. */
    private fun showCategoryTitle(category: Category, count: Int?) {
        // Isolate the name so Arabic names don't swap places with the English count.
        val name = "⁨${category.name}⁩"
        categoryTitle.text = if (count == null) name else "$name  ·  ${countLabel(type, count)}"
    }

    /** Count per category from the full list (cached on the device, so usually instant). */
    private fun loadCounts() {
        lifecycleScope.launch {
            try {
                val all = loadStreams(null)
                // Every category's list in one pass, in the background: moving through the
                // categories is then a lookup, not a search through tens of thousands of items.
                val byCategory = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val lists = HashMap<String, ArrayList<StreamItem>>()
                    for (item in all) for (id in item.categoryIds) lists.getOrPut(id) { ArrayList() }.add(item)
                    lists
                }
                for ((id, list) in byCategory) {
                    val key = "${type.name}:$id"
                    if (key !in Session.streamCache) Session.streamCache[key] = list
                }
                val map = HashMap<String, Int>()
                for ((id, list) in byCategory) map[id] = list.size
                map[CAT_ALL] = all.size
                counts = map
                refreshLocalCounts()
                // Categories the full list doesn't mention (like "recently added"): ask the server
                // for each one, a few at a time, so every category shows its count.
                val missing = serverCategories.filter { it.id !in map }
                for (cat in missing) {
                    val n = try { loadStreams(cat.id).size } catch (e: CancellationException) { throw e } catch (e: Exception) { continue }
                    counts = HashMap(counts).apply { put(cat.id, n) }
                    categoryAdapter.items.indexOfFirst { it.id == cat.id }.takeIf { it >= 0 }?.let { categoryAdapter.notifyItemChanged(it, "count") }
                    if (currentCategory?.id == cat.id) showCategoryTitle(cat, n)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Categories just show without numbers.
            }
        }
    }

    /** Favorites / Continue watching change on other screens. */
    /** Top rated's count in the category list, as new scores come in (it grows while browsing). */
    private fun updateTopCount(size: Int) {
        if (counts[CAT_TOP] == size) return
        counts = HashMap(counts).apply { put(CAT_TOP, size) }
        categoryAdapter.items.indexOfFirst { it.id == CAT_TOP }.takeIf { it >= 0 }?.let { categoryAdapter.notifyItemChanged(it, "count") }
    }

    private fun refreshLocalCounts() {
        val map = HashMap(counts)
        map[CAT_FAV] = prefs.favorites(type).size
        Session.streamCache["${type.name}:$CAT_ALL"]?.let { all -> map[CAT_TOP] = Ratings.topRated(all).size }
        map[CAT_HIDDEN] = prefs.hiddenCategories(type).count { id -> serverCategories.any { it.id == id } }
        if (type != ContentType.LIVE) map[CAT_CONTINUE] = prefs.continueWatching(type).size
        counts = map
        categoryAdapter.notifyItemRangeChanged(0, categoryAdapter.itemCount, "counts")
        currentCategory?.let { if (counts[it.id] != null && it.id != CAT_FAV && it.id != CAT_CONTINUE) showCategoryTitle(it, counts[it.id]) }
    }

    /** The list from memory when we have it (no network), else null. */
    /** The category's list if it is already sorted out in memory (instant, no searching). */
    private fun cachedStreams(categoryId: String?): List<StreamItem>? =
        Session.streamCache["${type.name}:${categoryId ?: CAT_ALL}"]

    private suspend fun loadStreams(categoryId: String?): List<StreamItem> {
        val key = "${type.name}:${categoryId ?: CAT_ALL}"
        Session.streamCache[key]?.let { return it }
        // Reuse a full list if we already have it instead of hitting the server again.
        if (categoryId != null) {
            Session.streamCache["${type.name}:$CAT_ALL"]?.let { all ->
                // Searching tens of thousands of items: off the screen's thread, so the remote stays responsive.
                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    all.filter { categoryId in it.categoryIds }
                }
                // Some panels list items under categories the full list doesn't mention: ask the server then.
                if (local.isNotEmpty()) return local.also { Session.streamCache[key] = it }
            }
        }
        return api.streams(type, categoryId).also { Session.streamCache[key] = it }
    }

    private fun applyFilter(scrollToTop: Boolean = true) {
        updateSortButton()
        val query = search.text.toString().trim()
        val found = if (query.isEmpty()) allItems else allItems.filter { it.name.contains(query, ignoreCase = true) }
        val list = sorted(found)
        itemAdapter.favorites = prefs.favoriteIds(type)
        if (type == ContentType.SERIES) {
            Session.streamCache["SERIES:__all__"]?.let { itemAdapter.newIds = prefs.newEpisodeSeries(it) }
        }
        itemAdapter.submit(list)
        if (scrollToTop) itemsView.scrollToPosition(0)
        if (list.isEmpty()) findViewById<View>(R.id.info_strip).visibility = if (style == Appearance.BROWSE_PREVIEW) View.INVISIBLE else View.GONE
        // Typed or cleared while no poster is highlighted: the plate described an item that may be gone.
        else if (!itemsView.hasFocus()) blankInfo()
        // The list closed before the items arrived: move to the first item once they are there.
        if (list.isNotEmpty() && panel?.visibility == View.GONE && (currentFocus == null || currentFocus == itemsView)) focusFirstItem()
        val catId = currentCategory?.id
        when {
            progress.visibility == View.VISIBLE -> message.visibility = View.GONE
            list.isEmpty() && catId == CAT_FAV && query.isEmpty() ->
                showMessage("No favorites yet.\nLong-press OK on an item to add it.")
            list.isEmpty() && catId == CAT_TOP && query.isEmpty() ->
                showMessage("No top rated titles yet.\nScores are added as you browse.")
            list.isEmpty() && catId == CAT_CONTINUE && query.isEmpty() ->
                showMessage("Nothing to continue yet.\nStart watching something and it will appear here.")
            list.isEmpty() -> showMessage(if (query.isEmpty()) "Nothing here." else "No results for \"$query\".")
            else -> message.visibility = View.GONE
        }
    }

    /** The grid's order (Menu on the grid): the provider's, IMDb score, A to Z, or recently added. */
    private fun sorted(list: List<StreamItem>): List<StreamItem> {
        val cat = currentCategory?.id
        if (cat == CAT_CONTINUE || cat == CAT_TOP || type == ContentType.LIVE) return list
        return when (prefs.browseSort(type)) {
            1 -> Ratings.sortByScore(list)
            2 -> list.sortedBy { it.name.lowercase() }
            3 -> list.sortedByDescending { it.added }
            else -> list
        }
    }

    /** "Sort · IMDb score": shown where sorting applies (not Live TV, Continue watching or Top rated). */
    /** Sorting changes this list: not Live TV, nor Continue watching or Top rated (always in their own order). */
    private fun sortApplies(): Boolean {
        val cat = currentCategory?.id
        return type != ContentType.LIVE && cat != CAT_CONTINUE && cat != CAT_TOP
    }

    private fun updateSortButton() {
        val b = findViewById<TextView?>(R.id.sort_button) ?: return
        val on = sortApplies()
        if (!on && b.hasFocus()) search.requestFocus()
        b.visibility = if (on) View.VISIBLE else View.GONE
        b.text = when (prefs.browseSort(type)) {
            1 -> "Sort · IMDb score"
            2 -> "Sort · A to Z"
            3 -> "Sort · Recently added"
            else -> "Sort"
        }
    }

    /**
     * The Sort list: a small panel in the theme's colours under the Sort button (the Info key opens
     * it too), the current order ticked. Up/Down choose, OK applies, Back closes.
     */
    private fun sortOptions() {
        val names = arrayOf("As the provider lists them", "IMDb score", "A to Z", "Recently added")
        val current = prefs.browseSort(type)
        val button = findViewById<View?>(R.id.sort_button)
        val anchor = button?.takeIf { it.isShown } ?: search
        val back = currentFocus
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val accent = themeColor(R.attr.appAccent)
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(themeColor(R.attr.appOverlay) or 0xF0000000.toInt())
                setStroke(dp(1).coerceAtLeast(1), (accent and 0x00FFFFFF) or 0xB3000000.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = "Sort " + (currentCategory?.name ?: "")
            setTextColor(themeColor(R.attr.appText))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(6), dp(14), dp(10))
        })
        val popup = android.widget.PopupWindow(box, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        val rows = names.mapIndexed { i, name ->
            TextView(this).apply {
                text = if (i == current) "$name   ✓" else name
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@BrowseActivity, R.color.on_focus_text))
                setBackgroundResource(R.drawable.bg_menu_row)
                minWidth = dp(260)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    if (i != prefs.browseSort(type)) {
                        prefs.setBrowseSort(type, i)
                        applyFilter()
                    }
                }
            }.also { box.addView(it, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }) }
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        popup.elevation = dp(12).toFloat()
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { (if (anchor === button) button else back)?.requestFocus() }
        popup.showAsDropDown(anchor, 0, dp(6), android.view.Gravity.END)
        box.post { rows[current.coerceIn(0, rows.size - 1)].requestFocus() }
    }

    /** The plate keeps its space but shows nothing until a poster is highlighted. */
    private fun blankInfo() {
        findViewById<View?>(R.id.info_strip)?.visibility = View.INVISIBLE
    }

    /** 1.1E: asks for the highlighted poster's scores once it has stayed highlighted a moment. */
    private var scoresJob: kotlinx.coroutines.Job? = null

    /** Info strip under the grid: name, then year · genre · rating, then the start of the plot. */
    private fun showInfo(item: StreamItem) {
        findViewById<View>(R.id.info_strip).visibility = View.VISIBLE
        findViewById<TextView>(R.id.info_name).text = item.name
        showInfoMeta(item)
        scoresJob?.cancel()
        if (Ratings.needsLookup(item.type, item.id)) scoresJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            Ratings.lookupItem(api, item, onScreen = false) ?: return@launch
            showInfoMeta(item)
            val at = itemAdapter.items.indexOfFirst { it.id == item.id && it.type == item.type }
            if (at >= 0) itemAdapter.notifyItemChanged(at, "scores")
            Session.streamCache["${type.name}:$CAT_ALL"]?.let { all -> updateTopCount(Ratings.topRated(all).size) }
        }
        findViewById<TextView>(R.id.info_plot).apply {
            text = item.plot
            visibility = if (item.plot == null) View.GONE else View.VISIBLE
        }
        findViewById<android.widget.ImageView?>(R.id.preview_image)?.load(item.image) {
            crossfade(false)
            error(R.drawable.placeholder)
        }
    }

    /** Real scores first when known (they replace the provider's ★), then year, genre. */
    private fun showInfoMeta(item: StreamItem) {
        val scores = if (item.note == null) Ratings.known(item.type, item.id) else null
        val rest = listOfNotNull(
            item.note,
            item.year,
            item.genre,
            if (item.year == null && item.added > 0) "Added " + formatDate(item.added) else null,
        ).joinToString("  ·  ")
        val view = findViewById<TextView>(R.id.info_meta)
        view.text = if (scores == null) rest else android.text.SpannableStringBuilder()
            .append(RatingBadges.line(this, scores, view.currentTextColor)).append(if (rest.isEmpty()) "" else "  ·  ").append(rest)
    }

    private fun open(index: Int, item: StreamItem) {
        if (currentCategory?.id == CAT_CONTINUE) {
            resume(item.id)
            return
        }
        when (item.type) {
            ContentType.LIVE -> {
                Session.zapList = itemAdapter.items
                Session.zapTitle = currentCategory?.name
                startActivity(PlayerActivity.liveIntent(this, index))
            }
            ContentType.MOVIE, ContentType.SERIES -> startActivity(DetailsActivity.intent(this, item))
        }
    }

    /** Continue-watching items play straight away from where they stopped. */
    private fun resume(key: String) {
        val entry = prefs.continueWatching(type).firstOrNull { it.key == key } ?: return
        resumeWatching(entry, api)
    }

    private fun onLongPress(item: StreamItem) {
        if (currentCategory?.id == CAT_CONTINUE) {
            confirmRemoveProgress(this, prefs, item) {
                allItems = localItems(CAT_CONTINUE)
                applyFilter(scrollToTop = false)
            }
            return
        }
        val added = prefs.toggleFavorite(item)
        if (counts.isNotEmpty()) refreshLocalCounts()
        showToast(this, if (added) "Added ${item.name} to favorites" else "Removed ${item.name} from favorites", Toast.LENGTH_SHORT)
        if (!added && currentCategory?.id == CAT_FAV) {
            allItems = localItems(CAT_FAV)
            applyFilter(scrollToTop = false)
        } else {
            itemAdapter.favorites = prefs.favoriteIds(type)
            val pos = itemAdapter.items.indexOf(item)
            if (pos >= 0) itemAdapter.notifyItemChanged(pos, "favorite")
        }
    }

    private fun showLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
