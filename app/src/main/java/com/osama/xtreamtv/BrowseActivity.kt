package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_browse)

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
            R.layout.item_category,
            bind = { vh, cat ->
                vh.title.text = cat.name
                vh.subtitle?.text = counts[cat.id]?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) } ?: ""
            },
            onClick = { _, cat -> selectCategory(cat) },
        )
        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.adapter = categoryAdapter

        val live = type == ContentType.LIVE
        itemAdapter = ItemAdapter(
            grid = !live,
            onClick = { index, item -> open(index, item) },
            onLongClick = { _, item -> onLongPress(item) },
        )
        itemsView.layoutManager = if (live) LinearLayoutManager(this) else GridLayoutManager(this, 5)
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

    private fun loadCategories() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                val cats = Session.categoryCache[type] ?: api.categories(type).also { Session.categoryCache[type] = it }
                val list = ArrayList<Category>()
                if (type != ContentType.LIVE) list.add(Category(CAT_CONTINUE, "▶ Continue watching"))
                list.add(Category(CAT_FAV, "★ Favorites"))
                list.add(Category(CAT_ALL, "All"))
                list.addAll(cats)
                categoryAdapter.submit(list)
                loadCounts()
                // Open "Continue watching" when there is something to resume, otherwise the
                // first real category ("All" can be tens of thousands of items).
                val firstIndex = when {
                    type != ContentType.LIVE && prefs.continueWatching(type).isNotEmpty() -> 0
                    cats.isEmpty() -> list.indexOfFirst { it.id == CAT_ALL }
                    else -> list.size - cats.size
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
