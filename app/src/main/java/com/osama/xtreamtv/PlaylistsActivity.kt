package com.osama.xtreamtv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch
import java.io.IOException

/** Start screen: pick one of the saved playlists, add a new one, or edit/delete with a long-press. */
class PlaylistsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANAGE = "manage"
        private const val ADD_ID = "__add__"
        private const val BACKUP_ID = "__backup__"
        private const val RESTORE_ID = "__restore__"

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
            R.layout.item_episode,
            bind = { vh, p ->
                when (p.id) {
                    ADD_ID -> {
                        vh.title.text = "＋  Add playlist"
                        vh.subtitle?.text = "Xtream Codes server, username and password"
                    }
                    BACKUP_ID -> {
                        vh.title.text = "⤓  Back up playlists and settings"
                        vh.subtitle?.text = "Save playlists, favorites and progress to a file"
                    }
                    RESTORE_ID -> {
                        vh.title.text = "⤒  Restore from a backup"
                        vh.subtitle?.text = "Load a backup file made with this app"
                    }
                    else -> {
                    vh.title.text = p.name
                        vh.subtitle?.text = "${p.username}  @  ${p.base.substringAfter("://")}"
                    }
                }
            },
            onClick = { _, p ->
                when (p.id) {
                    ADD_ID -> addPlaylist()
                    BACKUP_ID -> backUp()
                    RESTORE_ID -> restore()
                    else -> open(p)
                }
            },
            onLongClick = { _, p -> if (p.id !in setOf(ADD_ID, BACKUP_ID, RESTORE_ID)) showOptions(p) },
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
        val extras = listOfNotNull(
            Playlist(ADD_ID, "", "", "", ""),
            if (playlists.isNotEmpty()) Playlist(BACKUP_ID, "", "", "", "") else null,
            Playlist(RESTORE_ID, "", "", "", ""),
        )
        adapter.submit(playlists + extras)
        // Focus the playlist used last time.
        val focusIndex = playlists.indexOfFirst { it.id == prefs.activeId }.coerceAtLeast(0)
        listView.post { listView.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus() }
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
