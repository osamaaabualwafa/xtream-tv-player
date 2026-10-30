package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class DetailsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_IMAGE = "image"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_RATING = "rating"
        private const val EXTRA_CATEGORY = "category"

        fun intent(context: Context, item: StreamItem): Intent =
            Intent(context, DetailsActivity::class.java)
                .putExtra(EXTRA_TYPE, item.type.name)
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_IMAGE, item.image)
                .putExtra(EXTRA_EXT, item.ext)
                .putExtra(EXTRA_RATING, item.rating)
                .putExtra(EXTRA_CATEGORY, item.categoryId)

        private val EPISODE_TAG = Regex("(?i)\\bS\\d+\\s*E\\d+|\\bE\\d+\\b")
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var item: StreamItem
    private var ext: String? = null
    private var posterUrl: String? = null

    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var buttons: View
    private lateinit var playButton: Button
    private lateinit var resumeButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var seasonsView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    private lateinit var seasonAdapter: TextAdapter<Int>
    private lateinit var episodeAdapter: TextAdapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()

    private val isMovie get() = item.type == ContentType.MOVIE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        val type = if (intent.getStringExtra(EXTRA_TYPE) == ContentType.SERIES.name) ContentType.SERIES else ContentType.MOVIE
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        item = StreamItem(
            type = type,
            id = id,
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            image = intent.getStringExtra(EXTRA_IMAGE),
            categoryId = intent.getStringExtra(EXTRA_CATEGORY),
            ext = intent.getStringExtra(EXTRA_EXT),
            num = null,
            rating = intent.getStringExtra(EXTRA_RATING),
        )
        ext = item.ext
        posterUrl = item.image
        setContentView(R.layout.activity_details)

        poster = findViewById(R.id.poster)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        buttons = findViewById(R.id.buttons)
        playButton = findViewById(R.id.play)
        resumeButton = findViewById(R.id.resume)
        favoriteButton = findViewById(R.id.favorite)
        seasonsView = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        title.text = item.name
        meta.text = item.rating?.let { "★ $it" } ?: ""
        loadPoster(item.image)

        buttons.visibility = View.VISIBLE
        favoriteButton.setOnClickListener {
            prefs.toggleFavorite(item)
            updateFavoriteButton()
        }
        updateFavoriteButton()

        if (isMovie) setupMovie() else setupSeries()
    }

    override fun onResume() {
        super.onResume()
        if (!::item.isInitialized || !::resumeButton.isInitialized) return
        if (isMovie) {
            updateResumeButton()
        } else if (::episodeAdapter.isInitialized) {
            // A payload rebinds rows in place, so the focused episode keeps focus.
            episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
        }
    }

    private fun updateFavoriteButton() {
        val fav = item.id in prefs.favoriteIds(item.type)
        favoriteButton.text = if (fav) "★  Favorite" else "☆  Add to favorites"
    }

    private fun loadPoster(url: String?) {
        if (url.isNullOrBlank()) return
        posterUrl = url
        poster.load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }
    }

    // ---- Movies ----

    private fun movieKey() = "movie_${item.id}"

    private fun setupMovie() {
        playButton.setOnClickListener { playMovie(0L) }
        resumeButton.setOnClickListener { playMovie(prefs.position(movieKey())) }
        updateResumeButton()
        playButton.requestFocus()

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.vodInfo(item.id)
                info.name?.let { title.text = it }
                if (info.ext != null) ext = info.ext
                meta.text = listOfNotNull(
                    info.rating?.let { "★ $it" },
                    info.releaseDate?.take(4),
                    info.duration,
                    info.genre,
                ).joinToString("   ·   ")
                val extra = listOfNotNull(
                    info.director?.let { "Director: $it" },
                    info.cast?.let { "Cast: $it" },
                ).joinToString("\n")
                plot.text = listOfNotNull(info.plot, extra.ifEmpty { null }).joinToString("\n\n")
                loadPoster(info.cover)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                plot.text = "Details unavailable."
            }
            progress.visibility = View.GONE
        }
    }

    private fun updateResumeButton() {
        val pos = prefs.position(movieKey())
        if (pos > 0) {
            resumeButton.text = "Resume from ${formatDuration(pos)}"
            resumeButton.visibility = View.VISIBLE
        } else {
            resumeButton.visibility = View.GONE
        }
    }

    private fun playMovie(startMs: Long) {
        val name = title.text.toString()
        val entry = WatchEntry(movieKey(), ContentType.MOVIE, item.id, ext, name, null, posterUrl)
        startActivity(PlayerActivity.vodIntent(this, api.movieUrl(item.id, ext), name, movieKey(), startMs, entry))
    }

    // ---- Series ----

    private fun setupSeries() {
        playButton.visibility = View.GONE
        plot.maxLines = 3

        seasonAdapter = TextAdapter(
            R.layout.item_season,
            bind = { vh, season -> vh.title.text = if (season == 0) "Extras" else "Season $season" },
            onClick = { index, season -> showSeason(index, season) },
        )
        seasonsView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        seasonsView.adapter = seasonAdapter

        episodeAdapter = TextAdapter(
            R.layout.item_episode,
            bind = { vh, ep -> bindEpisode(vh, ep) },
            onClick = { _, ep -> playEpisode(ep) },
        )
        episodesView.layoutManager = LinearLayoutManager(this)
        episodesView.adapter = episodeAdapter

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.seriesInfo(item.id)
                meta.text = listOfNotNull(
                    info.rating?.let { "★ $it" },
                    info.releaseDate?.take(4),
                    info.genre,
                ).joinToString("   ·   ")
                plot.text = info.plot ?: ""
                loadPoster(info.cover)
                seasons = info.seasons
                if (seasons.isEmpty()) {
                    showMessage("No episodes available.")
                } else {
                    seasonsView.visibility = View.VISIBLE
                    episodesView.visibility = View.VISIBLE
                    seasonAdapter.submit(seasons.keys.toList())
                    showSeason(0, seasons.keys.first())
                    seasonsView.post { seasonsView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Couldn't load episodes.\n${e.message ?: ""}")
            }
            progress.visibility = View.GONE
        }
    }

    private fun showSeason(index: Int, season: Int) {
        seasonAdapter.activeIndex = index
        episodeAdapter.submit(seasons[season] ?: emptyList())
        episodesView.scrollToPosition(0)
    }

    private fun episodeKey(ep: Episode) = "ep_${ep.id}"

    private fun bindEpisode(vh: TextAdapter.VH, ep: Episode) {
        // Many panels already put "S01 E02" in the title; don't repeat the number.
        val prefix = if (ep.number != null && !EPISODE_TAG.containsMatchIn(ep.title)) "E${ep.number}  ·  " else ""
        vh.title.text = prefix + ep.title
        val pos = prefs.position(episodeKey(ep))
        vh.subtitle?.text = listOfNotNull(
            if (pos > 0) "▶ Resume ${formatDuration(pos)}" else null,
            ep.duration,
            ep.plot,
        ).joinToString("   ·   ")
    }

    private fun playEpisode(ep: Episode) {
        val episodeLabel = if (EPISODE_TAG.containsMatchIn(ep.title)) ep.title else "S${ep.season} E${ep.number ?: "?"}  ${ep.title}"
        val seriesName = title.text.toString()
        val key = episodeKey(ep)
        val entry = WatchEntry(key, ContentType.SERIES, ep.id, ep.ext, seriesName, episodeLabel, posterUrl)
        startActivity(PlayerActivity.vodIntent(this, api.episodeUrl(ep.id, ep.ext), "$seriesName  ·  $episodeLabel", key, prefs.position(key), entry))
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
