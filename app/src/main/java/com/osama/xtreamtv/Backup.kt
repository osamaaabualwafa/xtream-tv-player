package com.osama.xtreamtv

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup (Package 6, approved 2026-11-13): every saved setting for each playlist (playlists with their
 * logins, favorites, Continue watching, watched marks, where you stopped, hidden categories, Not
 * interested) and the app's settings, optionally with the library data.
 *
 * The file is always locked (AES-GCM): with a code the TV makes, the owner's own password, or, with no
 * protection, a key built into the app (that only stops a casual look: the app's code is public). The
 * MDBList and TMDB keys and the problem reports are never in it (they live in other files).
 *
 * Restoring merges by default (only adds; the newest place in a title wins) or replaces; either way
 * this TV's state is saved first for "Undo last restore".
 */
object Backup {

    /** The old plain file (2.4 and before): still restored once. */
    const val OLD_FILE_NAME = "xtream-tv-backup.json"
    private const val GLOBAL = "xtream"
    private const val LEGACY_ID = "legacy"
    private val PLAYLIST_FILE = Regex("^playlist_[A-Za-z0-9_-]+$")
    private val MAGIC = "XTVB2\n".toByteArray()
    private const val APP_SECRET = "xtream-tv-player/backup/v2"
    /** The app's own state in the settings file, not settings to ask about. */
    private val GLOBAL_STATE = setOf("playlists", "active", "default_playlist")
    /** A playlist's saved data (also found in the settings file for the oldest single login). */
    private val DATA_KEY = Regex("^(favorites_|continue|pos_|watched|not_interested|cat_hidden_|cat_order_|series_seen_|vod_hls|vod_plain|formats).*")

    /** Never in a backup: problem report notes and this TV's home screen entries (they belong to this TV). */
    private val NEVER = Regex("^(diag_|watch_next_|settings_pinned_for).*")
    /** Added when missing, never asked about: playlist avatars. */
    private val QUIET = Regex("^avatar_.*")

    /** No O/0 or I/1: nothing to mix up when typing the code. */
    private const val CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    enum class Lock { NONE, CODE, PASSWORD, TRANSFER }

    class WrongSecret : IOException("Wrong code or password")

    // ---- Files ----

    /** "xtream-tv-backup-2026-11-13.xtb". */
    fun fileName(): String = "xtream-tv-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".xtb"

    private fun folder(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    /** Used when the TV has no file picker: the app's own folder (no permission needed). */
    fun fallbackFile(context: Context): File = File(folder(context), fileName())

    /** The newest backup in the app's folder (a new locked one, or an old plain one). */
    fun newestFallback(context: Context): File? = folder(context).listFiles()
        ?.filter { it.isFile && (it.name.endsWith(".xtb") || it.name == OLD_FILE_NAME) }
        ?.maxByOrNull { it.lastModified() }

    // ---- Codes ----

    /** A new 9-character code, shown as K7P-29Q-M4X. */
    fun newCode(): String {
        val r = SecureRandom()
        return (1..9).map { CODE_LETTERS[r.nextInt(CODE_LETTERS.length)] }.joinToString("")
    }

    /** Typed any way (small letters, spaces, dashes): the code's characters. */
    fun cleanCode(typed: String): String = typed.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun showCode(code: String): String = code.chunked(3).joinToString("-")

    // ---- Lock ----

    private fun key(secret: String, salt: ByteArray, rounds: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return SecretKeySpec(f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, rounds, 256)).encoded, "AES")
    }

    private fun secretFor(lock: Lock, secret: String?): String = when (lock) {
        Lock.NONE -> APP_SECRET
        Lock.CODE -> APP_SECRET + ":" + cleanCode(secret.orEmpty())
        Lock.PASSWORD -> APP_SECRET + ":p:" + secret.orEmpty()
        Lock.TRANSFER -> APP_SECRET + ":t:" + secret.orEmpty().filter { it.isDigit() }
    }

    /** [plain] packed and locked; [secret] is the code, password or transfer code. */
    fun seal(plain: ByteArray, lock: Lock, secret: String?): ByteArray {
        val r = SecureRandom()
        val salt = ByteArray(16).also { r.nextBytes(it) }
        val iv = ByteArray(12).also { r.nextBytes(it) }
        val rounds = if (lock == Lock.NONE) 1000 else 30000
        val packed = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(plain) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secretFor(lock, secret), salt, rounds), GCMParameterSpec(128, iv))
        val head = JSONObject().put("app", "xtream-tv-player").put("v", 2).put("lock", lock.name.lowercase())
            .put("salt", b64(salt)).put("iv", b64(iv)).put("r", rounds).toString()
        return ByteArrayOutputStream().apply {
            write(MAGIC)
            write(head.toByteArray())
            write('\n'.code)
            write(cipher.doFinal(packed))
        }.toByteArray()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun header(bytes: ByteArray): Pair<JSONObject, Int>? {
        if (bytes.size < MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        var end = MAGIC.size
        while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
        if (end >= bytes.size) return null
        return runCatching { JSONObject(String(bytes, MAGIC.size, end - MAGIC.size)) }.getOrNull()?.let { it to end + 1 }
    }

    /** How a file is locked; null for an old plain backup. Throws when it isn't a backup at all. */
    fun lockOf(bytes: ByteArray): Lock? {
        header(bytes)?.let { (h, _) -> return runCatching { Lock.valueOf(h.optString("lock").uppercase()) }.getOrNull() ?: throw IOException("This backup is from a newer version of the app") }
        if (isOldBackup(bytes)) return null
        throw IOException("This isn't an Xtream TV backup file")
    }

    private fun isOldBackup(bytes: ByteArray): Boolean =
        runCatching { JSONObject(String(bytes, Charsets.UTF_8)).optString("app") == "xtream-tv-player" }.getOrDefault(false)

    /** The backup inside [bytes]. Throws [WrongSecret] when the code or password doesn't open it. */
    fun open(bytes: ByteArray, secret: String?): Incoming = if (header(bytes) == null) parse(bytes) else parse(openRaw(bytes, secret))

    /** The unlocked contents of a locked file (a backup or library data). Throws [WrongSecret]. */
    fun openRaw(bytes: ByteArray, secret: String?): ByteArray {
        val (h, start) = header(bytes) ?: throw IOException("This file isn't locked")
        val lock = Lock.valueOf(h.optString("lock").uppercase())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(secretFor(lock, secret), unb64(h.getString("salt")), h.optInt("r", 30000)), GCMParameterSpec(128, unb64(h.getString("iv"))))
        val packed = try { cipher.doFinal(bytes, start, bytes.size - start) } catch (e: java.security.GeneralSecurityException) { throw WrongSecret() }
        return GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
    }

    /** A backup from its unlocked contents (what another TV sent). */
    fun parsePlain(plain: ByteArray): Incoming = parse(plain)

    // ---- What's in a backup ----

    /** A backup's contents: the settings files, and the library data when it was included. */
    class Incoming(val files: JSONObject, val library: ByteArray?, val created: Long) {
        val playlists: List<Playlist> get() = playlistsIn(files)
        val favourites: Int get() = playlistDataSources(files).sumOf { (_, data) ->
            data.keys().asSequence().filter { it.startsWith("favorites_") }.sumOf { k -> runCatching { JSONArray(data.getJSONObject(k).optString("v")).length() }.getOrDefault(0) }
        }
        /** A playlist's favourites and Continue watching titles in this backup. */
        fun counts(p: Playlist): Pair<Int, Int> {
            val data = playlistDataSources(files).firstOrNull { it.first.id == p.id }?.second ?: return 0 to 0
            fun len(k: String) = runCatching { JSONArray(data.getJSONObject(k).optString("v")).length() }.getOrDefault(0)
            return data.keys().asSequence().filter { it.startsWith("favorites_") }.sumOf { len(it) } to (if (data.has("continue")) len("continue") else 0)
        }

        /** Only the chosen playlists (and what was watched in them), with the settings. */
        fun only(ids: Set<String>): Incoming = Incoming(filesOnly(files, ids), library, created)

        /** "3 playlists · 41 favourites · settings". */
        fun describe(): String = listOfNotNull(
            playlists.size.let { "$it playlist${if (it == 1) "" else "s"}" },
            favourites.let { "$it favourite${if (it == 1) "" else "s"}" },
            "settings",
            if (library != null) "library data" else null,
        ).joinToString(" · ")
    }

    private fun parse(plain: ByteArray): Incoming {
        val root = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { throw IOException("This isn't an Xtream TV backup file") }
        if (root.optString("app") != "xtream-tv-player") throw IOException("This isn't an Xtream TV backup file")
        val files = root.optJSONObject("files") ?: throw IOException("The backup is empty")
        if (!files.has(GLOBAL)) throw IOException("The backup has no playlists")
        val library = root.optString("library", "").takeIf { it.isNotEmpty() }?.let { unb64(it) }
        return Incoming(files, library, root.optLong("created"))
    }

    private fun playlistsIn(files: JSONObject): List<Playlist> {
        val text = files.optJSONObject(GLOBAL)?.optJSONObject("playlists")?.optString("v") ?: return emptyList()
        return runCatching { JSONArray(text) }.getOrNull()?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::playlistFromJson) } } ?: emptyList()
    }

    /** Each playlist's data in a backup: its own file, or (the oldest single login) the data keys of the settings file. */
    private fun playlistDataSources(files: JSONObject): List<Pair<Playlist, JSONObject>> = playlistsIn(files).mapNotNull { p ->
        if (p.id == LEGACY_ID) files.optJSONObject(GLOBAL)?.let { g -> p to filtered(g) { DATA_KEY.matches(it) } }
        else files.optJSONObject("playlist_${p.id}")?.let { p to it }
    }

    private fun filesOnly(files: JSONObject, ids: Set<String>): JSONObject {
        val chosen = playlistsIn(files).filter { it.id in ids }
        val g = JSONObject((files.optJSONObject(GLOBAL) ?: JSONObject()).toString())
        g.put("playlists", JSONObject().put("t", "s").put("v", JSONArray().also { arr -> chosen.forEach { arr.put(it.toJson()) } }.toString()))
        if (LEGACY_ID !in ids) for (k in g.keys().asSequence().toList()) if (DATA_KEY.matches(k)) g.remove(k)
        val out = JSONObject().put(GLOBAL, g)
        for (p in chosen) if (p.id != LEGACY_ID) files.optJSONObject("playlist_${p.id}")?.let { out.put("playlist_${p.id}", it) }
        return out
    }

    private fun filtered(o: JSONObject, keep: (String) -> Boolean): JSONObject {
        val out = JSONObject()
        for (k in o.keys()) if (keep(k)) out.put(k, o.get(k))
        return out
    }

    // ---- Making a backup ----

    private fun typedFile(context: Context, name: String): JSONObject {
        val entries = JSONObject()
        for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            if (NEVER.matches(key)) continue
            val typed = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            }
            if (typed != null) entries.put(key, typed)
        }
        return entries
    }

    private fun currentFiles(context: Context): JSONObject {
        val files = JSONObject()
        for (name in listOf(GLOBAL) + Prefs(context).playlists().filter { it.id != LEGACY_ID }.map { "playlist_${it.id}" }) {
            files.put(name, typedFile(context, name))
        }
        return files
    }

    /** This TV's backup, unlocked (call off the screen's thread: the library data can take a few seconds). */
    fun make(context: Context, includeLibrary: Boolean, ids: Set<String>? = null): ByteArray {
        val files = currentFiles(context).let { if (ids == null) it else filesOnly(it, ids) }
        val root = JSONObject().put("app", "xtream-tv-player").put("version", 2).put("created", System.currentTimeMillis())
            .put("files", files)
        if (includeLibrary) runCatching {
            val tmp = File(context.cacheDir, "backup-library.tmp")
            Library.export(context, tmp)
            root.put("library", b64(tmp.readBytes()))
            tmp.delete()
        }
        return root.toString().toByteArray()
    }

    /** This TV as a backup would hold it, for the Backup window. */
    fun current(context: Context): Incoming = Incoming(currentFiles(context), null, 0)

    // ---- Restoring ----

    /** What restoring asks first: the settings question, and playlists whose login differs on each side. */
    class Questions(val settingsDiffer: Boolean, val loginConflicts: List<Pair<Playlist, Playlist>>)

    class Result(
        val replaced: Boolean,
        val playlistsAdded: Int = 0,
        val favouritesAdded: Int = 0,
        val watchedAdded: Int = 0,
        val placesUpdated: Int = 0,
        val settingsUsed: Boolean = false,
        val playlists: Int = 0,
    ) {
        fun lines(): List<String> = if (replaced) listOf("This TV now matches the backup", "$playlists playlist${if (playlists == 1) "" else "s"}") else listOfNotNull(
            "+$playlistsAdded playlist${if (playlistsAdded == 1) "" else "s"}".takeIf { playlistsAdded > 0 },
            "+$favouritesAdded favourite${if (favouritesAdded == 1) "" else "s"}".takeIf { favouritesAdded > 0 },
            "+$watchedAdded watched mark${if (watchedAdded == 1) "" else "s"}".takeIf { watchedAdded > 0 },
            "$placesUpdated place${if (placesUpdated == 1) "" else "s"} in titles updated (newer)".takeIf { placesUpdated > 0 },
            "Settings from the backup".takeIf { settingsUsed },
        ).ifEmpty { listOf("Nothing new: this TV already had everything in the backup") }
    }

    private fun sameLogin(a: Playlist, b: Playlist) = a.base.trimEnd('/').equals(b.base.trimEnd('/'), true) && a.username == b.username

    /** The playlist on this TV that a backup's playlist is: the same id, or the same server and username. */
    private fun match(tv: List<Playlist>, b: Playlist): Playlist? = tv.firstOrNull { it.id == b.id } ?: tv.firstOrNull { sameLogin(it, b) }

    private fun settingsOf(files: JSONObject): JSONObject = filtered(files.optJSONObject(GLOBAL) ?: JSONObject()) { it !in GLOBAL_STATE && !DATA_KEY.matches(it) && !NEVER.matches(it) && !QUIET.matches(it) }

    fun questions(context: Context, inc: Incoming): Questions {
        val tvSettings = settingsOf(currentFiles(context))
        val bk = settingsOf(inc.files)
        val differ = bk.keys().asSequence().any { k -> tvSettings.optJSONObject(k)?.toString() != bk.optJSONObject(k)?.toString() }
        val tv = Prefs(context).playlists()
        val conflicts = inc.playlists.mapNotNull { b ->
            match(tv, b)?.takeIf { t -> t.base != b.base || t.username != b.username || t.password != b.password }?.let { it to b }
        }
        return Questions(differ, conflicts)
    }

    private fun writeTyped(context: Context, name: String, entries: JSONObject, clear: Boolean) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        if (clear) editor.clear()
        for (key in entries.keys()) putTyped(editor, key, entries.optJSONObject(key) ?: continue)
        editor.commit()
    }

    private fun putTyped(editor: android.content.SharedPreferences.Editor, key: String, typed: JSONObject) {
        when (typed.optString("t")) {
            "s" -> editor.putString(key, typed.optString("v"))
            "b" -> editor.putBoolean(key, typed.optBoolean("v"))
            "i" -> editor.putInt(key, typed.optInt("v"))
            "l" -> editor.putLong(key, typed.optLong("v"))
            "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
            "set" -> {
                val arr = typed.optJSONArray("v") ?: JSONArray()
                editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
            }
        }
    }

    /** Replace: this TV becomes the backup (after saving how it was, for Undo). */
    fun replace(context: Context, inc: Incoming): Result {
        saveUndo(context, inc.library != null)
        replaceFiles(context, inc.files)
        return Result(replaced = true, playlists = Prefs(context).playlists().size)
    }

    private fun replaceFiles(context: Context, files: JSONObject) {
        for (p in Prefs(context).playlists()) {
            if (p.id != LEGACY_ID) context.getSharedPreferences("playlist_${p.id}", Context.MODE_PRIVATE).edit().clear().commit()
        }
        // This TV's own entries (report notes, home screen entries) stay.
        val own = context.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE).all.filterKeys { NEVER.matches(it) }
        for (name in files.keys().asSequence().filter { it == GLOBAL || PLAYLIST_FILE.matches(it) }.toList()) {
            writeTyped(context, name, files.optJSONObject(name) ?: continue, clear = true)
        }
        context.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE).edit().apply {
            for ((k, v) in own) when (v) {
                is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Int -> putInt(k, v)
                is Long -> putLong(k, v); is Float -> putFloat(k, v)
                is Set<*> -> putStringSet(k, v.map { it.toString() }.toSet())
            }
        }.commit()
        Session.clear()
    }

    /**
     * Merge: only adds. Favourites, watched marks, hidden categories and Not interested from both; for each
     * title the newest place wins (this TV's when no time was saved); missing playlists are added.
     * [useBackupSettings]: the answer to the settings question; [useBackupLogin]: backup playlist ids whose login wins.
     */
    fun merge(context: Context, inc: Incoming, useBackupSettings: Boolean, useBackupLogin: Set<String>): Result {
        saveUndo(context, inc.library != null)
        val prefs = Prefs(context)
        val tvList = prefs.playlists().toMutableList()
        var added = 0
        var fav = 0
        var watched = 0
        var places = 0
        for ((b, data) in playlistDataSources(inc.files) + inc.playlists.filter { p -> playlistDataSources(inc.files).none { it.first.id == p.id } }.map { it to JSONObject() }) {
            val t = match(tvList, b)
            val targetId: String
            if (t == null) {
                targetId = if (tvList.any { it.id == b.id } || b.id == LEGACY_ID) System.currentTimeMillis().toString(36) + added else b.id
                tvList.add(b.copy(id = targetId))
                added++
            } else {
                targetId = t.id
                if (b.id in useBackupLogin) {
                    val i = tvList.indexOfFirst { it.id == t.id }
                    tvList[i] = t.copy(base = b.base, username = b.username, password = b.password)
                }
            }
            val name = if (targetId == LEGACY_ID) GLOBAL else "playlist_$targetId"
            val counts = mergeData(context, name, data)
            fav += counts[0]; watched += counts[1]; places += counts[2]
        }
        // The playlists list itself (kept in the settings file).
        val arr = JSONArray()
        tvList.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE).edit().putString("playlists", arr.toString()).commit()
        if (useBackupSettings) writeTyped(context, GLOBAL, settingsOf(inc.files), clear = false)
        // Avatars of playlists this TV didn't have.
        val tvGlobal = context.getSharedPreferences(GLOBAL, Context.MODE_PRIVATE)
        writeTyped(context, GLOBAL, filtered(inc.files.optJSONObject(GLOBAL) ?: JSONObject()) { QUIET.matches(it) && !tvGlobal.contains(it) }, clear = false)
        Session.clear()
        return Result(false, added, fav, watched, places, useBackupSettings)
    }

    /** One playlist's data from the backup into this TV's file. Returns [favourites added, watched added, places updated]. */
    private fun mergeData(context: Context, name: String, bk: JSONObject): IntArray {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val tv = typedFile(context, name)
        val editor = sp.edit()
        var fav = 0
        var watched = 0
        var places = 0
        val winners = HashMap<String, Long>() // continue key -> position from the backup (it was newer)
        for (key in bk.keys()) {
            val b = bk.optJSONObject(key) ?: continue
            val t = tv.optJSONObject(key)
            when {
                key.startsWith("favorites_") -> {
                    val have = runCatching { JSONArray(t?.optString("v") ?: "[]") }.getOrDefault(JSONArray())
                    val ids = (0 until have.length()).mapNotNull { have.optJSONObject(it)?.optString("id") }.toHashSet()
                    val incoming = runCatching { JSONArray(b.optString("v")) }.getOrDefault(JSONArray())
                    for (i in 0 until incoming.length()) {
                        val o = incoming.optJSONObject(i) ?: continue
                        if (ids.add(o.optString("id"))) { have.put(o); fav++ }
                    }
                    editor.putString(key, have.toString())
                }
                key == "continue" -> {
                    val have = runCatching { JSONArray(t?.optString("v") ?: "[]") }.getOrDefault(JSONArray())
                    val byKey = LinkedHashMap<String, JSONObject>()
                    for (i in 0 until have.length()) have.optJSONObject(i)?.let { byKey[it.optString("key")] = it }
                    val incoming = runCatching { JSONArray(b.optString("v")) }.getOrDefault(JSONArray())
                    for (i in 0 until incoming.length()) {
                        val o = incoming.optJSONObject(i) ?: continue
                        val k = o.optString("key")
                        val mine = byKey[k]
                        if (mine == null || o.optLong("updated") > mine.optLong("updated")) {
                            byKey[k] = o
                            winners[k] = o.optLong("position")
                            places++
                        }
                    }
                    editor.putString(key, JSONArray(byKey.values.toList()).toString())
                }
                b.optString("t") == "set" -> {
                    val have = HashSet<String>()
                    t?.optJSONArray("v")?.let { a -> for (i in 0 until a.length()) have.add(a.optString(i)) }
                    val before = have.size
                    b.optJSONArray("v")?.let { a -> for (i in 0 until a.length()) have.add(a.optString(i)) }
                    if (key == "watched") watched += have.size - before
                    editor.putStringSet(key, have)
                }
                key.startsWith("series_seen_") -> if (t == null || b.optLong("v") > t.optLong("v")) editor.putLong(key, b.optLong("v"))
                key.startsWith("pos_") -> if (t == null) { putTyped(editor, key, b); places++ }
                else -> if (t == null) putTyped(editor, key, b)
            }
        }
        // The newer place in a title also moves its resume point.
        for ((k, pos) in winners) if (pos > 0) editor.putLong("pos_$k", pos)
        editor.commit()
        return intArrayOf(fav, watched, places)
    }

    // ---- Undo last restore ----

    private fun undoFile(context: Context) = File(context.filesDir, "restore-undo.xtb")

    fun hasUndo(context: Context) = undoFile(context).exists()

    /** This TV as it was before a restore (locked with the app's key: it holds the logins). */
    private fun saveUndo(context: Context, libraryToo: Boolean) {
        val root = JSONObject().put("app", "xtream-tv-player").put("version", 2).put("created", System.currentTimeMillis())
            .put("files", currentFiles(context)).put("libraryUndo", libraryToo)
        undoFile(context).writeBytes(seal(root.toString().toByteArray(), Lock.NONE, null))
    }

    /** Puts this TV back as it was before the last restore. */
    fun undo(context: Context): Boolean {
        val f = undoFile(context)
        if (!f.exists()) return false
        val bytes = f.readBytes()
        val inc = open(bytes, null)
        val libraryToo = runCatching {
            val (h, start) = header(bytes)!!
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(APP_SECRET, unb64(h.getString("salt")), h.optInt("r")), GCMParameterSpec(128, unb64(h.getString("iv"))))
            JSONObject(String(GZIPInputStream(ByteArrayInputStream(c.doFinal(bytes, start, bytes.size - start))).use { it.readBytes() })).optBoolean("libraryUndo")
        }.getOrDefault(false)
        // Playlists added by the restore go with their data.
        val keep = playlistsIn(inc.files).map { it.id }.toSet()
        for (p in Prefs(context).playlists()) if (p.id !in keep && p.id != LEGACY_ID) {
            context.getSharedPreferences("playlist_${p.id}", Context.MODE_PRIVATE).edit().clear().commit()
        }
        replaceFiles(context, inc.files)
        if (libraryToo && Library.hasUndo(context)) runCatching { Library.undo(context) }
        f.delete()
        Diag.log("backup", "last restore undone")
        return true
    }
}
