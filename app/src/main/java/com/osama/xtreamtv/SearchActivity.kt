package com.osama.xtreamtv

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One search box across live channels, movies and series. */
class SearchActivity : AppCompatActivity() {

    companion object {
        private const val MAX_RESULTS = 300
    }

    private lateinit var api: XtreamApi
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: RecyclerView
    private lateinit var adapter: ItemAdapter

    private var library: List<StreamItem>? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        Appearance.apply(this)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.search_query)
        status = findViewById(R.id.search_status)
        progress = findViewById(R.id.search_progress)
        results = findViewById(R.id.search_results)

        adapter = ItemAdapter(grid = false, onClick = { _, item -> open(item) }, onLongClick = null)
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = adapter

        query.doAfterTextChanged { scheduleSearch() }
        query.setOnEditorActionListener { v, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            results.post { (results.layoutManager?.findViewByPosition(0) ?: results).requestFocus() }
            true
        }
        query.requestFocus()
        loadLibrary()
    }

    /** All channels, movies and series (from the device cache when possible). */
    private fun loadLibrary() {
        progress.visibility = View.VISIBLE
        status.text = "Loading your channels, movies and series…"
        lifecycleScope.launch {
            try {
                val parts = ContentType.values().map { type ->
                    async {
                        val key = "${type.name}:__all__"
                        Session.streamCache[key] ?: api.streams(type, null).also { Session.streamCache[key] = it }
                    }
                }.map { it.await() }
                library = parts.flatten()
                progress.visibility = View.GONE
                status.text = "Type at least 2 letters to search ${parts[0].size} channels, ${parts[1].size} movies and ${parts[2].size} series."
                scheduleSearch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "Couldn't load the library. ${e.message ?: ""}"
            }
        }
    }

    private fun scheduleSearch() {
        val all = library ?: return
        val text = query.text.toString().trim()
        searchJob?.cancel()
        if (text.length < 2) {
            adapter.submit(emptyList())
            return
        }
        searchJob = lifecycleScope.launch {
            delay(300)
            val found = withContext(Dispatchers.Default) {
                val matches = all.filter { it.name.contains(text, ignoreCase = true) }
                // Names that start with the text first, then by type: channels, movies, series.
                matches.sortedWith(compareBy({ !it.name.startsWith(text, ignoreCase = true) }, { it.type.ordinal }))
            }
            val shown = found.take(MAX_RESULTS).map { it.copy(note = typeLabel(it.type)) }
            adapter.submit(shown)
            results.scrollToPosition(0)
            status.text = when {
                found.isEmpty() -> "No results for \"$text\"."
                found.size > MAX_RESULTS -> "Showing the first $MAX_RESULTS of ${found.size} results for \"$text\"."
                else -> "${found.size} results for \"$text\"."
            }
        }
    }

    private fun typeLabel(type: ContentType) = when (type) {
        ContentType.LIVE -> "TV"
        ContentType.MOVIE -> "Movie"
        ContentType.SERIES -> "Series"
    }

    private fun open(item: StreamItem) {
        val clean = item.copy(note = null)
        when (item.type) {
            ContentType.LIVE -> {
                // Up/Down in the player zaps through the matching channels.
                val channels = adapter.items.filter { it.type == ContentType.LIVE }.map { it.copy(note = null) }
                Session.zapList = channels
                Session.zapTitle = "Search: ${query.text.toString().trim()}"
                startActivity(PlayerActivity.liveIntent(this, channels.indexOfFirst { it.id == item.id }.coerceAtLeast(0)))
            }
            ContentType.MOVIE, ContentType.SERIES -> startActivity(DetailsActivity.intent(this, clean))
        }
    }
}
