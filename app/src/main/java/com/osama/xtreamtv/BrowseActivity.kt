package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.view.View
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
            bind = { vh, cat -> vh.title.text = cat.name },
            onClick = { _, cat -> selectCategory(cat) },
        )
        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.adapter = categoryAdapter

        val live = type == ContentType.LIVE
        val favoriteToggle: (Int, StreamItem) -> Unit = { _, item -> toggleFavorite(item) }
        itemAdapter = ItemAdapter(
            grid = !live,
            onClick = { index, item -> open(index, item) },
            onLongClick = if (live) favoriteToggle else null,
        )
        itemsView.layoutManager = if (live) LinearLayoutManager(this) else GridLayoutManager(this, 5)
        itemsView.adapter = itemAdapter

        search.doAfterTextChanged { applyFilter() }

        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        if (type == ContentType.LIVE) {
            itemAdapter.favorites = prefs.favorites()
            itemAdapter.notifyItemRangeChanged(0, itemAdapter.itemCount, "favorites")
        }
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            showLoading(true)
            try {
                val cats = Session.categoryCache[type] ?: api.categories(type).also { Session.categoryCache[type] = it }
                val list = ArrayList<Category>()
                list.add(Category(CAT_ALL, "All"))
                if (type == ContentType.LIVE) list.add(Category(CAT_FAV, "★ Favorites"))
                list.addAll(cats)
                categoryAdapter.submit(list)
                // Start on the first real category: "All" can be tens of thousands of items.
                val firstIndex = if (cats.isEmpty()) 0 else list.size - cats.size
                selectCategory(list[firstIndex])
                categoriesView.scrollToPosition(firstIndex)
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

    private fun selectCategory(category: Category) {
        currentCategory = category
        categoryAdapter.activeIndex = categoryAdapter.items.indexOf(category)
        categoryTitle.text = category.name
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showLoading(true)
            message.visibility = View.GONE
            itemAdapter.submit(emptyList())
            try {
                allItems = when (category.id) {
                    CAT_ALL -> loadStreams(null)
                    CAT_FAV -> {
                        val favs = prefs.favorites()
                        loadStreams(null).filter { it.id in favs }
                    }
                    else -> loadStreams(category.id)
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

    private suspend fun loadStreams(categoryId: String?): List<StreamItem> {
        val key = "${type.name}:${categoryId ?: CAT_ALL}"
        Session.streamCache[key]?.let { return it }
        // Reuse a full list if we already have it instead of hitting the server again.
        if (categoryId != null) {
            Session.streamCache["${type.name}:$CAT_ALL"]?.let { all ->
                return all.filter { it.categoryId == categoryId }.also { Session.streamCache[key] = it }
            }
        }
        return api.streams(type, categoryId).also { Session.streamCache[key] = it }
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        val list = if (query.isEmpty()) allItems else allItems.filter { it.name.contains(query, ignoreCase = true) }
        itemAdapter.favorites = if (type == ContentType.LIVE) prefs.favorites() else emptySet()
        itemAdapter.submit(list)
        itemsView.scrollToPosition(0)
        when {
            progress.visibility == View.VISIBLE -> message.visibility = View.GONE
            list.isEmpty() && currentCategory?.id == CAT_FAV && query.isEmpty() ->
                showMessage("No favorites yet.\nLong-press OK on a channel to add it.")
            list.isEmpty() -> showMessage(if (query.isEmpty()) "Nothing here." else "No results for \"$query\".")
            else -> message.visibility = View.GONE
        }
    }

    private fun open(index: Int, item: StreamItem) {
        when (item.type) {
            ContentType.LIVE -> {
                Session.zapList = itemAdapter.items
                startActivity(PlayerActivity.liveIntent(this, index))
            }
            ContentType.MOVIE, ContentType.SERIES -> startActivity(
                Intent(this, DetailsActivity::class.java)
                    .putExtra(DetailsActivity.EXTRA_TYPE, item.type.name)
                    .putExtra(DetailsActivity.EXTRA_ID, item.id)
                    .putExtra(DetailsActivity.EXTRA_NAME, item.name)
                    .putExtra(DetailsActivity.EXTRA_IMAGE, item.image)
                    .putExtra(DetailsActivity.EXTRA_EXT, item.ext)
                    .putExtra(DetailsActivity.EXTRA_RATING, item.rating)
            )
        }
    }

    private fun toggleFavorite(item: StreamItem) {
        val added = prefs.toggleFavorite(item.id)
        Toast.makeText(this, if (added) "Added ${item.name} to favorites" else "Removed ${item.name} from favorites", Toast.LENGTH_SHORT).show()
        if (!added && currentCategory?.id == CAT_FAV) {
            allItems = allItems.filter { it.id != item.id }
            applyFilter()
        } else {
            itemAdapter.favorites = prefs.favorites()
            val pos = itemAdapter.items.indexOf(item)
            if (pos >= 0) itemAdapter.notifyItemChanged(pos)
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
