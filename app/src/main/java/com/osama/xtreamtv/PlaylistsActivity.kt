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

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

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
    // Backup files go through the system file picker when the TV has one.
    private var pendingSave: Pair<ByteArray, (String?) -> Unit>? = null
    private var pendingOpen: ((ByteArray?) -> Unit)? = null
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (bytes, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        if (uri == null) return@registerForActivityResult
        done(runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Can't write there"); "" }.getOrNull())
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        if (uri == null) { done(null); return@registerForActivityResult }
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) showMessage("Restore failed: can't read that file") else done(bytes)
    }
    private var autoOpenChecked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back from the TV's launcher with the app already open (it started some other way: an installer,
        // an update, another app): Android starts this screen on top. Step aside, back to the open page.
        val fromLauncher = intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        if (fromLauncher && !isTaskRoot) {
            finish()
            return
        }
        prefs = Prefs(this)
        Appearance.apply(this)
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
        listView.layoutManager = RowLayoutManager(this)
        listView.adapter = adapter
        listView.itemAnimator = null

        findViewById<View>(R.id.backup).setOnClickListener { backUp() }
        findViewById<View>(R.id.restore).setOnClickListener { restore() }
        findViewById<View>(R.id.undo_restore).setOnClickListener { BackupUi.confirmUndo(this) { afterRestore() } }
        findViewById<View>(R.id.edit).setOnClickListener {
            val playlists = prefs.playlists()
            (lastFocused ?: playlists.firstOrNull { it.id == prefs.activeId } ?: playlists.firstOrNull())?.let { showOptions(it) }
        }

        autoOpenChecked = savedInstanceState != null || intent.getBooleanExtra(EXTRA_MANAGE, false)
    }

    override fun onResume() {
        super.onResume()
        val playlists = prefs.playlists()
        // Opened from the TV home screen's "Play next" row: go straight to that playlist and resume.
        val resumePlaylist = intent.getStringExtra(WatchNext.EXTRA_RESUME_PLAYLIST)
        if (!autoOpenChecked && resumePlaylist != null) {
            autoOpenChecked = true
            val p = playlists.firstOrNull { it.id == resumePlaylist }
            if (p != null) {
                showList(playlists)
                open(p, intent.getStringExtra(WatchNext.EXTRA_RESUME_KEY))
                return
            }
        }
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
                else -> {
                    // Several: the one chosen to open at start (playlist options or Settings).
                    playlists.firstOrNull { it.id == prefs.defaultPlaylistId }?.let { p ->
                        showList(playlists)
                        open(p)
                        return
                    }
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
        findViewById<View>(R.id.undo_restore).visibility = if (Backup.hasUndo(this)) View.VISIBLE else View.GONE
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
            Appearance.bindAvatar(initial, prefs, p.id, p.name, letterSizeSp = 36f, emojiSizeSp = 40f)
            vh.title.text = p.name
            val status = statuses[p.id]
            // The one that opens at start gets a star in front of its status.
            vh.subtitle?.text = (if (p.id == prefs.defaultPlaylistId) "★ Opens at start" + (status?.first?.let { "  ·  $it" } ?: "") else status?.first) ?: ""
            if (status?.second == true) vh.subtitle?.setTextColor(themeColor(R.attr.appError))
            else vh.subtitle?.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.on_focus_secondary))
        }
        // The focused tile grows a little.
        FocusFx.markGrows(vh.itemView)
        vh.itemView.setOnFocusChangeListener { v, hasFocus ->
            FocusFx.growTile(v, hasFocus)
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

    /** Backup (Package 6): this TV / USB, another TV, or the phone; locked with a code or password. */
    private fun backUp() {
        if (busy) return
        BackupUi.backupMenu(this) { bytes, done ->
            if (hasFilePicker(Intent.ACTION_CREATE_DOCUMENT)) {
                try {
                    pendingSave = bytes to done
                    createBackup.launch(Backup.fileName())
                    return@backupMenu
                } catch (e: ActivityNotFoundException) {
                    pendingSave = null
                }
            }
            // No file picker on this TV: save in the app's own folder.
            val file = Backup.fallbackFile(this)
            done(runCatching { file.writeBytes(bytes); file.absolutePath }.getOrNull())
        }
    }

    private fun restore() {
        if (busy) return
        BackupUi.restoreMenu(this, { done ->
            if (hasFilePicker(Intent.ACTION_OPEN_DOCUMENT)) {
                try {
                    pendingOpen = done
                    openBackup.launch(arrayOf("*/*"))
                    return@restoreMenu
                } catch (e: ActivityNotFoundException) {
                    pendingOpen = null
                }
            }
            // No file picker on this TV: the newest backup in the app's own folder.
            val file = Backup.newestFallback(this)
            if (file != null) done(file.readBytes()) else {
                AlertDialog.Builder(this)
                    .setTitle("No backup found")
                    .setMessage("This TV has no file picker. Put the backup file here, then choose Restore again:\n${Backup.fallbackFile(this).parent}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }) { afterRestore() }
    }

    /** After a restore or an undo: the list again, and the Undo button when there is something to undo. */
    private fun afterRestore() {
        Session.clear()
        message.visibility = View.GONE
        showList(prefs.playlists())
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

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun addPlaylist() {
        if (!busy) startActivity(LoginActivity.addIntent(this))
    }

    private fun showOptions(p: Playlist) {
        if (busy) return
        val isDefault = prefs.defaultPlaylistId == p.id
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Edit", "Edit on Phone", "Change avatar", if (isDefault) "Stop opening at start" else "Open at start", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(LoginActivity.editIntent(this, p.id))
                    1 -> startActivity(LoginActivity.editIntent(this, p.id, phone = true))
                    2 -> AvatarPicker.show(this, p) { showList(prefs.playlists()) }
                    3 -> {
                        prefs.defaultPlaylistId = if (isDefault) null else p.id
                        showToast(
                            this,
                            if (isDefault) "The app will ask which playlist to open" else "${p.name} will open at start", android.widget.Toast.LENGTH_SHORT)
                        showList(prefs.playlists())
                    }
                    4 -> confirmDelete(p)
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

    /** [resumeKey]: play this Continue watching entry once Home is open (from "Play next"). */
    private fun open(p: Playlist, resumeKey: String? = null) {
        if (busy) return
        busy = true
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val api = XtreamApi(p.toAccount(), cacheDir)
                val info = api.userInfo()
                Diag.log(
                    "account",
                    "status ${info.status ?: "?"} · expires ${info.expDate?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it * 1000)) } ?: "never"}" +
                        " · connections ${info.activeCons ?: "?"} of ${info.maxConnections ?: "?"} in use · formats ${info.formats.joinToString(",")}",
                )
                val problem = accountProblem(info)
                if (problem != null) {
                    showError("${p.name}: $problem")
                    return@launch
                }
                prefs.activeId = p.id
                prefs.formats = info.formats.joinToString(",")
                Session.start(api, info)
                startActivity(Intent(this@PlaylistsActivity, HomeActivity::class.java).putExtra(WatchNext.EXTRA_RESUME_KEY, resumeKey))
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
