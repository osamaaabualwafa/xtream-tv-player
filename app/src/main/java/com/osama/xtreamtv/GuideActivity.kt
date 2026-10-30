package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TV guide for the channels in the current list: pick a channel on the left, see its programmes
 * on the right. OK on what's on now plays it live; OK on a past programme plays it as catch-up
 * when the server keeps an archive for that channel.
 */
class GuideActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_FROM_PLAYER = "from_player"

        fun intent(context: Context, index: Int, fromPlayer: Boolean): Intent =
            Intent(context, GuideActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_FROM_PLAYER, fromPlayer)
    }

    private lateinit var api: XtreamApi
    private lateinit var channels: List<StreamItem>
    private lateinit var channelsView: RecyclerView
    private lateinit var programsView: RecyclerView
    private lateinit var channelName: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var programAdapter: TextAdapter<GuideEntry>
    private var fromPlayer = false
    private var selected = 0
    private var loadJob: Job? = null

    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        channels = Session.zapList
        if (channels.isEmpty()) return finish()
        fromPlayer = intent.getBooleanExtra(EXTRA_FROM_PLAYER, false)
        Appearance.apply(this)
        setContentView(R.layout.activity_guide)

        channelsView = findViewById(R.id.guide_channels)
        programsView = findViewById(R.id.guide_programs)
        channelName = findViewById(R.id.guide_channel_name)
        hint = findViewById(R.id.guide_hint)
        progress = findViewById(R.id.guide_progress)
        message = findViewById(R.id.guide_message)
        findViewById<TextView>(R.id.guide_list_title).text = Session.zapTitle ?: "Channels"

        val channelAdapter = ItemAdapter(
            grid = false,
            onClick = { index, _ -> selectChannel(index, focusPrograms = true) },
            onLongClick = null,
        )
        channelAdapter.favorites = Prefs(this).favoriteIds(ContentType.LIVE)
        channelAdapter.submit(channels.map { if (it.archiveDays > 0) it.copy(note = (it.num ?: "") + " ⏪") else it })
        channelsView.layoutManager = RowLayoutManager(this, LinearLayoutManager.VERTICAL, center = true)
        channelsView.adapter = channelAdapter

        programAdapter = TextAdapter(
            R.layout.item_episode,
            bind = { vh, e -> bindProgram(vh, e) },
            onClick = { _, e -> openProgram(e) },
        )
        programsView.layoutManager = RowLayoutManager(this, LinearLayoutManager.VERTICAL, center = true)
        programsView.adapter = programAdapter

        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, channels.size - 1)
        selectChannel(start, focusPrograms = true)
        (channelsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, 200)
    }

    private fun now() = System.currentTimeMillis() / 1000

    private fun selectChannel(index: Int, focusPrograms: Boolean) {
        selected = index
        val channel = channels[index]
        channelName.text = listOfNotNull(channel.num, channel.name).joinToString("   ")
        hint.text = if (channel.archiveDays > 0) {
            "OK on a past programme to watch it (catch-up: ${channel.archiveDays} days)  ·  OK on what's on now to watch live"
        } else {
            "OK on what's on now to watch live  ·  no catch-up on this channel"
        }
        loadJob?.cancel()
        programAdapter.submit(emptyList())
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        loadJob = lifecycleScope.launch {
            try {
                val now = now()
                val keepFrom = now - maxOf(channel.archiveDays, 1) * 86_400L
                val list = api.fullEpg(channel.id).filter { it.end > keepFrom && it.start < now + 2 * 86_400L }
                progress.visibility = View.GONE
                programAdapter.submit(list)
                if (list.isEmpty()) {
                    message.text = "No programme guide for this channel."
                    message.visibility = View.VISIBLE
                    return@launch
                }
                // Start at what's on now.
                val current = list.indexOfFirst { it.end > now }.coerceAtLeast(0)
                (programsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(current, 120)
                if (focusPrograms) {
                    programsView.post { programsView.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.visibility = View.GONE
                message.text = "Couldn't load the guide.\n${e.message ?: ""}"
                message.visibility = View.VISIBLE
            }
        }
    }

    private fun canCatchUp(channel: StreamItem, e: GuideEntry): Boolean =
        e.end <= now() && (e.hasArchive || (channel.archiveDays > 0 && e.start >= now() - channel.archiveDays * 86_400L))

    private fun bindProgram(vh: TextAdapter.VH, e: GuideEntry) {
        val channel = channels[selected]
        val now = now()
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val day = Calendar.getInstance().apply { timeInMillis = e.start * 1000 }.get(Calendar.DAY_OF_YEAR)
        val dayLabel = if (day == today) "" else dayFormat.format(Date(e.start * 1000)) + "   "
        vh.title.text = "$dayLabel${formatClock(e.start)}–${formatClock(e.end)}     ${e.title}"
        val status = when {
            e.start <= now && now < e.end -> "▶ On now"
            canCatchUp(channel, e) -> "⏪ Catch-up"
            else -> null
        }
        vh.subtitle?.text = listOfNotNull(status, e.description.takeIf { it.isNotBlank() }).joinToString("   ·   ")
    }

    private fun openProgram(e: GuideEntry) {
        val channel = channels[selected]
        val now = now()
        when {
            e.start <= now && now < e.end -> playLive()
            canCatchUp(channel, e) -> {
                val minutes = ((e.end - e.start + 59) / 60).toInt().coerceAtLeast(1)
                val url = api.timeshiftUrl(channel.id, e.timeshiftStart(), minutes)
                startActivity(PlayerActivity.catchupIntent(this, url, "${channel.name}  ·  ${e.title}"))
            }
            e.end <= now -> Toast.makeText(this, "Catch-up isn't available for this programme", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(this, "This programme hasn't started yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playLive() {
        if (fromPlayer) {
            // The live player underneath switches to this channel when it comes back.
            Session.pendingChannel = selected
            finish()
        } else {
            startActivity(PlayerActivity.liveIntent(this, selected))
        }
    }
}
