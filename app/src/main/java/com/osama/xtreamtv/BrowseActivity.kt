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
import kotlinx.coroutines.launch

class BrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "type"
        private const val CAT_ALL = "__all__"
        private const val CAT_FAV = "__fav__"
        private const val CAT_CONTINUE = "__continue__"
        private const val CAT_HIDDEN = "__hidden__"
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
    private var rail: View? = null

    /** The server's categories (before the user's order and hidden list are applied). */
    private var serverCategories: List<Category> = emptyList()

    private var allItems: List<StreamItem> = emptyList()
    private var currentCategory: Category? = null
    private var loadJob: Job? = null

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
        itemsView = findViewById(R.id.items)
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
        )
        categoriesView.layoutManager = LinearLayoutManager(this, if (style == Appearance.BROWSE_CHIPS) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL, false)
        categoriesView.adapter = categoryAdapter

        val live = type == ContentType.LIVE
        val preview = style == Appearance.BROWSE_PREVIEW
        itemAdapter = ItemAdapter(
            grid = !live,
            onClick = { index, item -> open(index, item) },
            onLongClick = { _, item -> onLongPress(item) },
            layout = if (preview) R.layout.item_poster_small else null,
            onFocusItem = if (live) { item -> channelPreview?.focus(item) } else { item -> showInfo(item) },
        )
        itemsView.layoutManager = when {
            live -> LinearLayoutManager(this)
            preview -> LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            style == Appearance.BROWSE_SIDEBAR || style == Appearance.BROWSE_FOLDING -> GridLayoutManager(this, 6)
            else -> GridLayoutManager(this, 8)
        }

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

        // B2 / B4: the rail opens the category list; Back from the posters opens it too.
        rail?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) openPanel() }
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

        search.doAfterTextChanged { applyFilter() }
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
        // Free the stream before the player opens (providers often allow one connection).
        channelPreview?.release()
    }

    override fun onResume() {
        super.onResume()
        if (!::itemAdapter.isInitialized) return
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

    private fun openPanel() {
        val p = panel ?: return
        p.visibility = View.VISIBLE
        rail?.visibility = View.GONE
        setGridColumns()
        categoriesView.post {
            val index = categoryAdapter.activeIndex.coerceAtLeast(0)
            (categoriesView.findViewHolderForAdapterPosition(index)?.itemView ?: categoriesView).requestFocus()
        }
    }

    /** B2 / B4: hide the category list and go back to the posters. */
    private fun closePanel() {
        val p = panel ?: return
        if (p.visibility != View.VISIBLE) return
        p.visibility = View.GONE
        rail?.visibility = View.VISIBLE
        setGridColumns()
        focusFirstItem()
    }

    /** B2: fewer columns while the list is open so the posters keep their size. */
    private fun setGridColumns() {
        if (style != Appearance.BROWSE_FOLDING) return
        (itemsView.layoutManager as? GridLayoutManager)?.spanCount = if (panel?.visibility == View.VISIBLE) 6 else 8
    }

    private fun focusFirstItem() {
        itemsView.post {
            val lm = itemsView.layoutManager as? LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            (lm?.findViewByPosition(first) ?: itemsView).requestFocus()
        }
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
        // Right from the sliding category list closes it.
        if (panel?.visibility == View.VISIBLE && categoriesView.hasFocus() &&
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) closePanel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                serverCategories = Session.categoryCache[type] ?: api.categories(type).also { Session.categoryCache[type] = it }
                val list = buildCategoryList()
                val cats = list.filter { it.id !in setOf(CAT_CONTINUE, CAT_FAV, CAT_ALL, CAT_HIDDEN) }
                categoryAdapter.submit(list)
                loadCounts()
                // Open "Continue watching" when there is something to resume, otherwise the
                // first real category ("All" can be tens of thousands of items).
                val firstIndex = when {
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
        if (cat.id in setOf(CAT_CONTINUE, CAT_FAV, CAT_ALL, CAT_HIDDEN)) return
        val ids = buildCategoryList().filter { it.id !in setOf(CAT_CONTINUE, CAT_FAV, CAT_ALL, CAT_HIDDEN) }.map { it.id }.toMutableList()
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
                        Toast.makeText(this, "Hidden. Find it under \"Hidden categories\" at the bottom.", Toast.LENGTH_LONG).show()
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
            )
        }
        else -> emptyList()
    }

    private fun selectCategory(category: Category) {
        currentCategory = category
        categoryAdapter.activeIndex = categoryAdapter.items.indexOf(category)
        showCategoryTitle(category, counts[category.id])
        loadJob?.cancel()
        message.visibility = View.GONE
        if (category.id == CAT_FAV || category.id == CAT_CONTINUE) {
            showLoading(false)
            allItems = localItems(category.id)
            showCategoryTitle(category, allItems.size)
            applyFilter()
            return
        }
        loadJob = lifecycleScope.launch {
            showLoading(true)
            itemAdapter.submit(emptyList())
            try {
                allItems = loadStreams(if (category.id == CAT_ALL) null else category.id)
                showCategoryTitle(category, allItems.size)
                // Categories the full list doesn't cover get their count once opened.
                if (counts.isNotEmpty() && counts[category.id] != allItems.size) {
                    counts = HashMap(counts).apply { put(category.id, allItems.size) }
                    categoryAdapter.items.indexOf(category).takeIf { it >= 0 }?.let { categoryAdapter.notifyItemChanged(it, "count") }
                }
                showLoading(false)
                applyFilter()
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
                val map = HashMap<String, Int>()
                for (item in all) for (id in item.categoryIds) map[id] = (map[id] ?: 0) + 1
                map[CAT_ALL] = all.size
                counts = map
                refreshLocalCounts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Categories just show without numbers.
            }
        }
    }

    /** Favorites / Continue watching change on other screens. */
    private fun refreshLocalCounts() {
        val map = HashMap(counts)
        map[CAT_FAV] = prefs.favorites(type).size
        map[CAT_HIDDEN] = prefs.hiddenCategories(type).count { id -> serverCategories.any { it.id == id } }
        if (type != ContentType.LIVE) map[CAT_CONTINUE] = prefs.continueWatching(type).size
        counts = map
        categoryAdapter.notifyItemRangeChanged(0, categoryAdapter.itemCount, "counts")
        currentCategory?.let { if (counts[it.id] != null && it.id != CAT_FAV && it.id != CAT_CONTINUE) showCategoryTitle(it, counts[it.id]) }
    }

    private suspend fun loadStreams(categoryId: String?): List<StreamItem> {
        val key = "${type.name}:${categoryId ?: CAT_ALL}"
        Session.streamCache[key]?.let { return it }
        // Reuse a full list if we already have it instead of hitting the server again.
        if (categoryId != null) {
            Session.streamCache["${type.name}:$CAT_ALL"]?.let { all ->
                val local = all.filter { categoryId in it.categoryIds }
                // Some panels list items under categories the full list doesn't mention: ask the server then.
                if (local.isNotEmpty()) return local.also { Session.streamCache[key] = it }
            }
        }
        return api.streams(type, categoryId).also { Session.streamCache[key] = it }
    }

    private fun applyFilter(scrollToTop: Boolean = true) {
        val query = search.text.toString().trim()
        val list = if (query.isEmpty()) allItems else allItems.filter { it.name.contains(query, ignoreCase = true) }
        itemAdapter.favorites = prefs.favoriteIds(type)
        itemAdapter.submit(list)
        if (scrollToTop) itemsView.scrollToPosition(0)
        if (list.isEmpty()) findViewById<View>(R.id.info_strip).visibility = if (style == Appearance.BROWSE_PREVIEW) View.INVISIBLE else View.GONE
        // The list closed before the items arrived: move to the first item once they are there.
        if (list.isNotEmpty() && panel?.visibility == View.GONE && (currentFocus == null || currentFocus == itemsView)) focusFirstItem()
        val catId = currentCategory?.id
        when {
            progress.visibility == View.VISIBLE -> message.visibility = View.GONE
            list.isEmpty() && catId == CAT_FAV && query.isEmpty() ->
                showMessage("No favorites yet.\nLong-press OK on an item to add it.")
            list.isEmpty() && catId == CAT_CONTINUE && query.isEmpty() ->
                showMessage("Nothing to continue yet.\nStart watching something and it will appear here.")
            list.isEmpty() -> showMessage(if (query.isEmpty()) "Nothing here." else "No results for \"$query\".")
            else -> message.visibility = View.GONE
        }
    }

    /** Info strip under the grid: name, then year · genre · rating, then the start of the plot. */
    private fun showInfo(item: StreamItem) {
        findViewById<View>(R.id.info_strip).visibility = View.VISIBLE
        findViewById<TextView>(R.id.info_name).text = item.name
        val meta = listOfNotNull(
            item.note,
            item.year,
            item.genre,
            displayRating(item.rating)?.let { "★ $it" },
            if (item.year == null && item.added > 0) "Added " + formatDate(item.added) else null,
        ).joinToString("  ·  ")
        findViewById<TextView>(R.id.info_meta).text = meta
        findViewById<TextView>(R.id.info_plot).apply {
            text = item.plot
            visibility = if (item.plot == null) View.GONE else View.VISIBLE
        }
        findViewById<android.widget.ImageView?>(R.id.preview_image)?.load(item.image) {
            crossfade(false)
            error(R.drawable.placeholder)
        }
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
        val url = if (entry.type == ContentType.MOVIE) api.movieUrl(entry.id, entry.ext) else api.episodeUrl(entry.id, entry.ext ?: "mp4")
        val title = listOfNotNull(entry.title, entry.subtitle).joinToString("  ·  ")
        startActivity(PlayerActivity.vodIntent(this, url, title, entry.key, entry.position, entry))
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
        Toast.makeText(this, if (added) "Added ${item.name} to favorites" else "Removed ${item.name} from favorites", Toast.LENGTH_SHORT).show()
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
