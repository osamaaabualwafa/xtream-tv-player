package com.osama.xtreamtv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
    }

    private lateinit var api: XtreamApi
    private lateinit var prefs: Prefs
    private lateinit var item: StreamItem
    private var ext: String? = null
    private var posterUrl: String? = null

    private lateinit var poster: ImageView
    private lateinit var title: TextView
    private lateinit var meta: android.widget.LinearLayout
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
        Appearance.apply(this)
        setContentView(if (item.type == ContentType.SERIES) R.layout.activity_details_series else R.layout.activity_details)

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
        setMeta(listOfNotNull(displayRating(item.rating)?.let { "★ $it" }))
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

    /** Trailer button when the provider has a YouTube id (or link) for this title. */
    private fun showTrailer(trailer: String?) {
        val value = trailer?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val button = findViewById<Button>(R.id.trailer)
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            val isLink = value.startsWith("http")
            val intents = if (isLink) {
                listOf(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            } else {
                listOf(
                    Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$value")),
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$value")),
                )
            }
            val started = intents.any { intent ->
                try {
                    startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                }
            }
            if (!started) Toast.makeText(this, "Install YouTube to watch trailers", Toast.LENGTH_LONG).show()
        }
    }

    /** No gap on the left of the first visible button. */
    private fun alignButtons() {
        val row = buttons as android.view.ViewGroup
        var first = true
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i)
            if (b.visibility != View.VISIBLE) continue
            (b.layoutParams as android.view.ViewGroup.MarginLayoutParams).leftMargin = if (first) 0 else (12 * resources.displayMetrics.density).toInt()
            b.requestLayout()
            first = false
        }
    }

    private fun updateFavoriteButton() {
        buttons.post { alignButtons() }
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
        findViewById<ImageView>(R.id.backdrop).load(url) { crossfade(true) }
    }

    /** Rating, year, length, genre as glass chips. */
    private fun setMeta(values: List<String>) {
        meta.removeAllViews()
        val density = resources.displayMetrics.density
        for (v in values.filter { it.isNotBlank() }) {
            meta.addView(TextView(this).apply {
                text = v
                setTextColor(getColor(R.color.text))
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (320 * density).toInt()
                setBackgroundResource(R.drawable.bg_meta_chip)
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = (8 * density).toInt() }
            })
        }
    }

    private fun showCredits(text: String?) {
        findViewById<TextView>(R.id.credits).apply {
            this.text = text
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    // ---- Movies ----

    private fun movieKey() = "movie_${item.id}"

    private fun setupMovie() {
        playButton.setOnClickListener { playMovie(0L) }
        // Resume was an explicit choice here, so the player doesn't ask again.
        resumeButton.setOnClickListener { playMovie(prefs.position(movieKey())) }
        updateResumeButton()
        playButton.requestFocus()

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.vodInfo(item.id)
                info.name?.let { title.text = it }
                if (info.ext != null) ext = info.ext
                setMeta(listOfNotNull(
                    displayRating(info.rating)?.let { "★ $it" },
                    info.releaseDate?.take(4),
                    info.duration?.let { durationText(it) },
                    info.genre,
                ))
                plot.text = info.plot
                showCredits(listOfNotNull(
                    info.director?.let { "Director: $it" },
                    info.cast?.let { "Cast: $it" },
                ).joinToString("\n").ifEmpty { null })
                showTrailer(info.trailer)
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
            resumeButton.text = "▶  Resume ${formatDuration(pos)}"
            resumeButton.visibility = View.VISIBLE
            buttons.post { alignButtons() }
        } else {
            resumeButton.visibility = View.GONE
            buttons.post { alignButtons() }
        }
    }

    private fun playMovie(startMs: Long) {
        val name = title.text.toString()
        val entry = WatchEntry(movieKey(), ContentType.MOVIE, item.id, ext, name, null, posterUrl)
        startActivity(PlayerActivity.vodIntent(this, api.movieUrl(item.id, ext), name, movieKey(), startMs, entry, askResume = false))
    }

    // ---- Series ----

    private fun setupSeries() {
        playButton.visibility = View.GONE
        findViewById<View>(R.id.episodes_card).visibility = View.VISIBLE

        seasonAdapter = TextAdapter(
            R.layout.item_category_chip,
            bind = { vh, season ->
                // Many seasons: short, smaller tabs so they all fit on one line.
                val many = seasons.size > 6
                vh.title.text = when {
                    season == 0 -> "Extras"
                    many -> "S$season"
                    else -> "Season $season"
                }
                vh.subtitle?.text = ""
                vh.title.textSize = if (many) 15f else 16f
                val d = resources.displayMetrics.density
                vh.itemView.setPadding(((if (many) 14 else 18) * d).toInt(), 0, ((if (many) 14 else 18) * d).toInt(), 0)
                (vh.itemView.layoutParams as android.view.ViewGroup.MarginLayoutParams).rightMargin = ((if (many) 6 else 10) * d).toInt()
            },
            onClick = { index, season -> showSeason(index, season) },
        )
        seasonsView.layoutManager = RowLayoutManager(this)
        seasonsView.adapter = seasonAdapter

        episodeAdapter = TextAdapter(
            R.layout.item_episode_card,
            bind = { vh, ep -> bindEpisode(vh, ep) },
            onClick = { _, ep -> playEpisode(ep) },
            onLongClick = { index, ep ->
                // Long-press: mark as watched / not watched.
                val key = episodeKey(ep)
                val watched = !prefs.isWatched(key)
                prefs.setWatched(key, watched)
                if (watched) prefs.removeProgress(key)
                Toast.makeText(this, if (watched) "Marked as watched" else "Marked as not watched", Toast.LENGTH_SHORT).show()
                episodeAdapter.notifyItemChanged(index, "watched")
            },
        )
        episodesView.layoutManager = RowLayoutManager(this)
        episodesView.adapter = episodeAdapter

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = api.seriesInfo(item.id)
                setMeta(listOfNotNull(
                    displayRating(info.rating)?.let { "★ $it" },
                    info.releaseDate?.take(4),
                    info.genre,
                ))
                plot.text = info.plot
                showCredits(info.cast?.let { "Cast: $it" })
                showTrailer(info.trailer)
                loadPoster(info.cover)
                seasons = info.seasons
                if (seasons.isEmpty()) {
                    showMessage("No episodes available.")
                } else {
                    seasonsView.visibility = View.VISIBLE
                    episodesView.visibility = View.VISIBLE
                    seasonAdapter.submit(seasons.keys.toList())
                    // Open on the season of the episode being watched, with Resume ready.
                    val watching = prefs.continueWatching(ContentType.SERIES).firstOrNull { it.seriesId == item.id }
                    val resumeEp = watching?.let { w -> seasons.values.flatten().firstOrNull { it.id == w.id } }
                    val startSeason = resumeEp?.season?.takeIf { it in seasons } ?: seasons.keys.first()
                    showSeason(seasons.keys.indexOf(startSeason), startSeason)
                    if (resumeEp != null) {
                        // Episode row starts at the one being watched.
                        (seasons[startSeason] ?: emptyList()).indexOfFirst { it.id == resumeEp.id }.takeIf { it > 0 }
                            ?.let { (episodesView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(it, 0) }
                        resumeButton.text = "▶  Resume ${resumeEp.label().take(28)}"
                        resumeButton.visibility = View.VISIBLE
                        buttons.post { alignButtons() }
                        resumeButton.setOnClickListener { playEpisode(resumeEp) }
                        resumeButton.requestFocus()
                    } else {
                        episodesView.post { episodesView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                    }
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
        val prefix = if (ep.number != null && !ep.hasNumberInTitle()) "E${ep.number}  ·  " else ""
        vh.title.text = prefix + ep.title
        val key = episodeKey(ep)
        val pos = prefs.position(key)
        val watched = prefs.isWatched(key)
        val length = parseLength(ep.duration)
        vh.subtitle?.text = listOfNotNull(
            when {
                pos > 0 && length != null && length > pos -> "${(length - pos + 59_999) / 60_000} min left"
                pos > 0 -> "▶ Resume ${formatDuration(pos)}"
                watched -> "✓ Watched"
                else -> null
            },
            ep.duration?.let { durationText(it) },
        ).joinToString("   ·   ")
        vh.itemView.findViewById<ImageView?>(R.id.image)?.load(posterUrl) {
            crossfade(false)
            error(R.drawable.placeholder)
        }
        vh.itemView.findViewById<ProgressBar?>(R.id.watch_progress)?.apply {
            val fraction = when {
                watched -> 1f
                pos > 0 && length != null && length > 0 -> (pos.toFloat() / length).coerceIn(0f, 1f)
                else -> -1f
            }
            visibility = if (fraction >= 0f) View.VISIBLE else View.GONE
            progress = (fraction.coerceAtLeast(0f) * 1000).toInt()
        }
    }

    /** "01:48:53" -> "1 h 48 min", "00:35:23" -> "35 min" (other text unchanged). */
    private fun durationText(text: String): String {
        val ms = parseLength(text) ?: return text
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    private fun parseLength(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return null }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> null
        }
    }

    private fun playEpisode(ep: Episode) {
        val episodeLabel = ep.label()
        val seriesName = title.text.toString()
        val key = episodeKey(ep)
        // The player uses this for next/previous episode and its episode list.
        Session.seriesQueue = SeriesQueue(item.id, seriesName, posterUrl, seasons.values.flatten())
        val entry = WatchEntry(key, ContentType.SERIES, ep.id, ep.ext, seriesName, episodeLabel, posterUrl, seriesId = item.id)
        startActivity(PlayerActivity.vodIntent(this, api.episodeUrl(ep.id, ep.ext), "$seriesName  ·  $episodeLabel", key, prefs.position(key), entry))
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }
}
