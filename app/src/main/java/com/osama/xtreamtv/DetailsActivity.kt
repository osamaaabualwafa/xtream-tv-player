package com.osama.xtreamtv

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
        const val EXTRA_TYPE = "type"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_EXT = "ext"
        const val EXTRA_RATING = "rating"
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var type: ContentType
    private lateinit var itemId: String
    private lateinit var name: String
    private var ext: String? = null

    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var buttons: View
    private lateinit var playButton: Button
    private lateinit var resumeButton: Button
    private lateinit var seasonsView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    private lateinit var seasonAdapter: TextAdapter<Int>
    private lateinit var episodeAdapter: TextAdapter<Episode>
    private var seasons: Map<Int, List<Episode>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        prefs = Prefs(this)
        type = if (intent.getStringExtra(EXTRA_TYPE) == ContentType.SERIES.name) ContentType.SERIES else ContentType.MOVIE
        itemId = intent.getStringExtra(EXTRA_ID) ?: return finish()
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        ext = intent.getStringExtra(EXTRA_EXT)
        setContentView(R.layout.activity_details)

        poster = findViewById(R.id.poster)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        plot = findViewById(R.id.plot)
        buttons = findViewById(R.id.buttons)
        playButton = findViewById(R.id.play)
        resumeButton = findViewById(R.id.resume)
        seasonsView = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        title.text = name
        meta.text = intent.getStringExtra(EXTRA_RATING)?.let { "★ $it" } ?: ""
        loadPoster(intent.getStringExtra(EXTRA_IMAGE))

        if (type == ContentType.MOVIE) setupMovie() else setupSeries()
    }

    override fun onResume() {
        super.onResume()
        if (type == ContentType.MOVIE) {
            updateResumeButton()
        } else if (::episodeAdapter.isInitialized) {
            // A payload rebinds rows in place, so the focused episode keeps focus.
            episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.itemCount, "progress")
        }
    }

    private fun loadPoster(url: String?) {
        if (url.isNullOrBlank()) return
        poster.load(url) {
            crossfade(true)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }
    }

    // ---- Movies ----

    private fun movieKey() = "movie_$itemId"

    private fun setupMovie() {
        buttons.visibility = View.VISIBLE
        playButton.setOnClickListener { playMovie(0L) }
        resumeButton.setOnClickListener { playMovie(prefs.position(movieKey())) }
        updateResumeButton()
        playButton.requestFocus()

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.vodInfo(itemId)
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
        if (type != ContentType.MOVIE || !::resumeButton.isInitialized) return
        val pos = prefs.position(movieKey())
        if (pos > 0) {
            resumeButton.text = "Resume from ${formatDuration(pos)}"
            resumeButton.visibility = View.VISIBLE
        } else {
            resumeButton.visibility = View.GONE
        }
    }

    private fun playMovie(startMs: Long) {
        startActivity(PlayerActivity.vodIntent(this, api.movieUrl(itemId, ext), title.text.toString(), movieKey(), startMs))
    }

    // ---- Series ----

    private fun setupSeries() {
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
                val info = api.seriesInfo(itemId)
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
        val prefix = ep.number?.let { "E$it  ·  " } ?: ""
        vh.title.text = prefix + ep.title
        val pos = prefs.position(episodeKey(ep))
        vh.subtitle?.text = listOfNotNull(
            if (pos > 0) "▶ Resume ${formatDuration(pos)}" else null,
            ep.duration,
            ep.plot,
        ).joinToString("   ·   ")
    }

    private fun playEpisode(ep: Episode) {
        val label = "$name  ·  S${ep.season} E${ep.number ?: "?"}  ${ep.title}"
        val key = episodeKey(ep)
        startActivity(PlayerActivity.vodIntent(this, api.episodeUrl(ep.id, ep.ext), label, key, prefs.position(key)))
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
