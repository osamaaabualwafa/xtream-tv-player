package com.osama.xtreamtv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Start screen: glass profile tiles, one per saved playlist (with its account status), plus
 * "Add playlist". Edit/delete with a long-press or the Edit button; Backup and Restore below.
 */
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
    /** Account status line under each tile, filled in as the servers answer (second = shown in red). */
    private val statuses = HashMap<String, Pair<String, Boolean>>()
    private var statusJob: Job? = null
    /** The playlist the Edit button acts on: the last one that had focus. */
    private var lastFocused: Playlist? = null

    // Backup files go through the system file picker when the TV has one.
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            writeBackup { text ->
                contentResolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) } ?: throw IOException("Can't write there")
            }
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            confirmRestore {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw IOException("Can't read that file")
            }
        }
    }
    private var autoOpenChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_playlists)
        listView = findViewById(R.id.playlists)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)

        adapter = TextAdapter(
            R.layout.item_playlist_tile,
            bind = { vh, p -> bindTile(vh, p) },
            onClick = { _, p -> if (p.id == ADD_ID) addPlaylist() else open(p) },
            onLongClick = { _, p -> if (p.id != ADD_ID) showOptions(p) },
        )
        listView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        listView.adapter = adapter
        listView.itemAnimator = null

        findViewById<View>(R.id.backup).setOnClickListener { backUp() }
        findViewById<View>(R.id.restore).setOnClickListener { restore() }
        findViewById<View>(R.id.edit).setOnClickListener {
            val playlists = prefs.playlists()
            (lastFocused ?: playlists.firstOrNull { it.id == prefs.activeId } ?: playlists.firstOrNull())?.let { showOptions(it) }
        }

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
                    // Nothing saved yet: go straight to "Add playlist" (Back returns here for Restore).
                    showList(playlists)
                    startActivity(LoginActivity.addIntent(this))
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
        val hasPlaylists = playlists.isNotEmpty()
        findViewById<View>(R.id.backup).visibility = if (hasPlaylists) View.VISIBLE else View.GONE
        findViewById<View>(R.id.edit).visibility = if (hasPlaylists) View.VISIBLE else View.GONE
        if (lastFocused?.let { f -> playlists.none { it.id == f.id } } == true) lastFocused = null
        // Focus the playlist used last time.
        val focusIndex = playlists.indexOfFirst { it.id == prefs.activeId }.coerceAtLeast(0)
        listView.post { listView.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus() }
        loadStatuses(playlists)
    }

    private fun bindTile(vh: TextAdapter.VH, p: Playlist) {
        val tile = vh.itemView.findViewById<View>(R.id.tile)
        val initial = vh.itemView.findViewById<TextView>(R.id.initial)
        if (p.id == ADD_ID) {
            tile.setBackgroundResource(R.drawable.bg_glass_tile_add)
            initial.text = "+"
            initial.backgroundTintList = ColorStateList.valueOf(0x1FFFFFFF)
            vh.title.text = "Add playlist"
            vh.subtitle?.text = ""
        } else {
            tile.setBackgroundResource(R.drawable.bg_glass_tile)
            initial.text = p.name.trim().firstOrNull()?.uppercase() ?: "?"
            initial.backgroundTintList = ColorStateList.valueOf(avatarColor(p.id))
            vh.title.text = p.name
            val status = statuses[p.id]
            vh.subtitle?.text = status?.first ?: ""
            vh.subtitle?.setTextColor(getColor(if (status?.second == true) R.color.error else R.color.text_secondary))
        }
        // The focused tile grows a little.
        vh.itemView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.08f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            if (hasFocus && p.id != ADD_ID) lastFocused = p
        }
    }

    /** Ask each server for its account status, in parallel; a slow server just shows "Can't reach server". */
    private fun loadStatuses(playlists: List<Playlist>) {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            playlists.map { p ->
                async {
                    val info = withTimeoutOrNull(8_000) {
                        try {
                            XtreamApi(p.toAccount(), cacheDir).userInfo()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    statuses[p.id] = statusLine(info)
                    val index = adapter.items.indexOfFirst { it.id == p.id }
                    if (index >= 0) adapter.notifyItemChanged(index)
                }
            }.forEach { it.await() }
        }
    }

    /** "Active · Jun 2027", "9 days left", or what is wrong with the account. */
    private fun statusLine(info: UserInfo?): Pair<String, Boolean> {
        if (info == null) return "Can't reach server" to true
        accountProblem(info)?.let { return it to true }
        val exp = info.expDate ?: return "Active" to false
        val daysLeft = ((exp * 1000 - System.currentTimeMillis()) / 86_400_000L).toInt()
        return if (daysLeft <= 14) {
            (if (daysLeft <= 1) "Expires today" else "$daysLeft days left") to true
        } else {
            "Active · " + java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(exp * 1000)) to false
        }
    }

    private fun backUp() {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle("Back up playlists and settings")
            .setMessage("The backup file includes your playlist passwords. Keep it somewhere private.")
            .setPositiveButton("Continue") { _, _ ->
                if (hasFilePicker(Intent.ACTION_CREATE_DOCUMENT)) {
                    try {
                        createBackup.launch(Backup.FILE_NAME)
                        return@setPositiveButton
                    } catch (e: ActivityNotFoundException) {
                        // fall through to the app folder
                    }
                }
                // No file picker on this TV: save in the app's own folder.
                val file = Backup.fallbackFile(this)
                writeBackup(file.absolutePath) { file.writeText(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** [where]: shown to the user when the file went into the app folder (no picker). */
    private fun writeBackup(where: String? = null, write: (String) -> Unit) {
        try {
            write(Backup.export(this))
            AlertDialog.Builder(this)
                .setTitle("Backup saved")
                .setMessage(if (where != null) "Saved to:\n$where\n\nCopy this file to restore on another TV." else "Your backup file is saved.")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            showMessage("Backup failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun restore() {
        if (busy) return
        if (hasFilePicker(Intent.ACTION_OPEN_DOCUMENT)) {
            try {
                openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                return
            } catch (e: ActivityNotFoundException) {
                // fall through to the app folder
            }
        }
        run {
            // No file picker on this TV: look in the app's own folder.
            val file = Backup.fallbackFile(this)
            if (file.exists()) {
                confirmRestore { file.readText() }
            } else {
                AlertDialog.Builder(this)
                    .setTitle("No backup found")
                    .setMessage("This TV has no file picker. Put the backup file here, then choose Restore again:\n${file.absolutePath}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Android TV ships a placeholder that "handles" the file picker but only shows
     * "You don't have an app that can do this", so check for a real picker.
     */
    private fun hasFilePicker(action: String): Boolean {
        val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val info = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        val pkg = info.packageName.lowercase()
        if ("stub" in pkg) return false
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return !isTv || "documentsui" in pkg || pkg == "android"
    }

    private fun confirmRestore(read: () -> String) {
        AlertDialog.Builder(this)
            .setTitle("Restore from backup?")
            .setMessage("Your current playlists, favorites and progress on this TV are replaced by the backup.")
            .setPositiveButton("Restore") { _, _ ->
                try {
                    val count = Backup.import(this, read())
                    Session.clear()
                    message.visibility = View.GONE
                    Toast.makeText(this, "Restored $count playlist${if (count == 1) "" else "s"}", Toast.LENGTH_LONG).show()
                    showList(prefs.playlists())
                } catch (e: Exception) {
                    showMessage("Restore failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
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
                val api = XtreamApi(p.toAccount(), cacheDir)
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
