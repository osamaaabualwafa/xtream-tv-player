package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Start screen: pick one of the saved playlists, add a new one, or edit/delete with a long-press. */
class PlaylistsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANAGE = "manage"
        private const val ADD_ID = "__add__"

        /** Opened from Home to switch playlists: never auto-opens the only playlist. */
        fun manageIntent(context: Context): Intent =
            Intent(context, PlaylistsActivity::class.java)
                .putExtra(EXTRA_MANAGE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private lateinit var prefs: Prefs
    private lateinit var listView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView
    private lateinit var adapter: TextAdapter<Playlist>
    private var busy = false
    private var autoOpenChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_playlists)
        listView = findViewById(R.id.playlists)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        adapter = TextAdapter(
            R.layout.item_episode,
            bind = { vh, p ->
                if (p.id == ADD_ID) {
                    vh.title.text = "＋  Add playlist"
                    vh.subtitle?.text = "Xtream Codes server, username and password"
                } else {
                    vh.title.text = p.name
                    vh.subtitle?.text = "${p.username}  @  ${p.base.substringAfter("://")}"
                }
            },
            onClick = { _, p -> if (p.id == ADD_ID) addPlaylist() else open(p) },
            onLongClick = { _, p -> if (p.id != ADD_ID) showOptions(p) },
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        autoOpenChecked = savedInstanceState != null || intent.getBooleanExtra(EXTRA_MANAGE, false)
    }

    override fun onResume() {
        super.onResume()
        val playlists = prefs.playlists()
        if (!autoOpenChecked) {
            autoOpenChecked = true
            // Same behaviour as before playlists existed: no list to choose from, skip the chooser.
            when (playlists.size) {
                0 -> {
                    startActivity(LoginActivity.addIntent(this))
                    finish()
                    return
                }
                1 -> {
                    showList(playlists)
                    open(playlists[0])
                    return
                }
            }
        }
        showList(playlists)
    }

    private fun showList(playlists: List<Playlist>) {
        adapter.submit(playlists + Playlist(ADD_ID, "", "", "", ""))
        // Focus the playlist used last time.
        val focusIndex = playlists.indexOfFirst { it.id == prefs.activeId }.coerceAtLeast(0)
        listView.post { listView.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus() }
    }

    private fun addPlaylist() {
        if (!busy) startActivity(LoginActivity.addIntent(this))
    }

    private fun showOptions(p: Playlist) {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(LoginActivity.editIntent(this, p.id))
                    1 -> confirmDelete(p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("Its favorites and Continue watching list are deleted too.")
            .setPositiveButton("Delete") { _, _ ->
                prefs.deletePlaylist(p.id)
                showList(prefs.playlists())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(p: Playlist) {
        if (busy) return
        busy = true
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = XtreamApi(p.toAccount())
                val info = api.userInfo()
                val problem = accountProblem(info)
                if (problem != null) {
                    showError("${p.name}: $problem")
                    return@launch
                }
                prefs.activeId = p.id
                prefs.formats = info.formats.joinToString(",")
                Session.start(api, info)
                startActivity(Intent(this@PlaylistsActivity, HomeActivity::class.java))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Couldn't connect to ${p.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun showError(text: String) {
        busy = false
        progress.visibility = View.GONE
        message.text = "$text\nLong-press OK on it to edit."
        message.visibility = View.VISIBLE
    }
}
