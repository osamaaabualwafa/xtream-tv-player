package com.osama.xtreamtv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Library data (agreed with the owner 2026-11-10): a backup of what the app has looked up, so another
 * TV does not have to look it all up again. Only the titles database goes in the file: which title is
 * which movie or series, and each one's scores, cast, genres, year, length and poster link.
 *
 * Never in the file: playlist names, logins, server addresses, the MDBList or TMDB key, anything
 * watched. A playlist is written as a fingerprint (a one-way scramble of its server and username):
 * another TV with the same playlist recognises it, and nobody can read the name or address out of it.
 *
 * Restore only adds: nothing this TV already has is removed, the newer copy of a title's scores wins,
 * and a "wrong match" mark made here stays. A playlist that is not on this TV is kept for later and
 * used when that playlist is added. Before Restore or Clear changes anything, the database as it was
 * is copied aside (the last one only), for "Undo last change".
 */
object Library {
    const val FILE_NAME = "xtream-tv-library.xtlib"
    private const val SCHEMA = "2"

    /** Used when the TV has no file picker: the app's own folder (no permission needed). */
    fun fallbackFile(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    private fun dbFile(context: Context) = context.getDatabasePath("titles.db")
    private fun undoFile(context: Context) = File(context.filesDir, "library-undo.db")
    private fun sp(context: Context) = context.getSharedPreferences("ratings_fill", Context.MODE_PRIVATE)

    class Cancelled : Exception()

    /** A playlist as the file knows it: "fp:" and 20 characters, the same on every TV that has this playlist. */
    fun fingerprint(p: Playlist): String {
        val text = "xtreamtv-library|" + p.base.trim().lowercase().trimEnd('/') + "|" + p.username
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return "fp:" + hash.joinToString("") { "%02x".format(it) }.take(20)
    }

    /** Android TV ships a placeholder file picker that only says "no app can do this": check for a real one. */
    fun hasFilePicker(context: Context, action: String): Boolean {
        val intent = Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        val pkg = info.packageName.lowercase()
        if ("stub" in pkg) return false
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return !isTv || "documentsui" in pkg || pkg == "android"
    }

    /** The database as it is now, copied whole while nothing writes to it. */
    private fun copyDatabase(context: Context, to: File) {
        Ratings.flush()
        val db = Ratings.db.writableDatabase
        db.beginTransaction()
        try {
            dbFile(context).copyTo(to, overwrite = true)
        } finally {
            db.endTransaction()
        }
    }

    // ---- Back up ----

    /** Writes the backup into [out]. Call off the screen's thread. */
    fun export(context: Context, out: File) = synchronized(RatingsFill.gate) {
        copyDatabase(context, out)
        val x = SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // Playlists become fingerprints; this TV's own names for them never leave it.
            for (p in Prefs(context).playlists()) {
                val fp = fingerprint(p)
                x.execSQL("UPDATE OR REPLACE titles SET pl = ? WHERE pl = ?", arrayOf(fp, p.id))
                for (t in listOf("titles", "works", "work_people", "work_genres")) {
                    x.execSQL("UPDATE OR IGNORE $t SET ref = replace(ref, ?, ?) WHERE ref LIKE ?", arrayOf("local/${p.id}/", "local/$fp/", "local/${p.id}/%"))
                }
            }
            // Titles of playlists no longer on this TV (and not kept from a restore) are left out.
            x.execSQL("DELETE FROM titles WHERE pl NOT LIKE 'fp:%'")
            x.execSQL("DELETE FROM playlists")
            x.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)")
            x.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES ('schema', ?)", arrayOf(SCHEMA))
            x.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES ('made_at', ?)", arrayOf(System.currentTimeMillis().toString()))
            x.execSQL("VACUUM")
        } finally {
            x.close()
        }
        File(out.path + "-journal").delete()
        Diag.log("ratings", "library data backed up")
    }

    // ---- Restore ----

    /** One playlist of the backup, as the Settings card shows it. [playlist]: this TV's id for it, or null when it is not on this TV. */
    class Section(val playlist: String?, val movies: Int, val moviesOf: Int, val series: Int, val seriesOf: Int, val titles: Int) {
        fun toJson() = JSONObject().put("p", playlist ?: "").put("m", movies).put("mo", moviesOf).put("s", series).put("so", seriesOf).put("t", titles)
    }

    class Summary(val at: Long, val backupAt: Long, val sections: List<Section>, val worksNew: Int, val worksHad: Int) {
        fun toJson() = JSONObject().put("a", at).put("b", backupAt).put("n", worksNew).put("h", worksHad).put("s", JSONArray(sections.map { it.toJson() }))
    }

    fun lastSummary(context: Context): Summary? = runCatching {
        val o = JSONObject(sp(context).getString("last_restore", null) ?: return null)
        val a = o.getJSONArray("s")
        Summary(o.optLong("a"), o.optLong("b"), (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            Section(s.optString("p").takeIf { it.isNotEmpty() }, s.optInt("m"), s.optInt("mo"), s.optInt("s"), s.optInt("so"), s.optInt("t"))
        }, o.optInt("n"), o.optInt("h"))
    }.getOrNull()

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Adds the backup in [file] to this TV's database. [onProgress]: 0..100 and what is being matched
     * (this TV's own playlist names only). [cancelled]: checked between steps; a cancelled restore
     * changes nothing. Call off the screen's thread.
     */
    fun import(context: Context, file: File, onProgress: (Int, String) -> Unit, cancelled: () -> Boolean): Summary = synchronized(RatingsFill.gate) {
        // Is it one of ours?
        val backupAt = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { b ->
                val schema = b.rawQuery("SELECT v FROM meta WHERE k = 'schema'", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (schema != SCHEMA) throw IOException("This file is from another version of the app")
                b.rawQuery("SELECT v FROM meta WHERE k = 'made_at'", null).use { c -> if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0L else 0L }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            throw IOException("This is not a library data file")
        }
        onProgress(2, "Saving a safety copy")
        copyDatabase(context, undoFile(context))

        val prefs = Prefs(context)
        val mine = prefs.playlists().associate { fingerprint(it) to it }
        val cols = "ref, imdb, rt, at, year, minutes, trailer, trailer_ok, award, award_ok, poster, poster_ok"
        val db = Ratings.db.writableDatabase
        db.execSQL("ATTACH DATABASE ? AS b", arrayOf(file.path))
        val sections = ArrayList<Section>()
        var worksNew = 0
        var worksHad = 0
        try {
            db.beginTransaction()
            try {
                fun step(percent: Int, label: String) {
                    if (cancelled()) throw Cancelled()
                    onProgress(percent, label)
                }
                step(6, "Scores and details")
                val inBackup = count(db, "SELECT COUNT(*) FROM b.works WHERE ref NOT LIKE 'local/%'")
                worksHad = count(db, "SELECT COUNT(*) FROM b.works bw JOIN works w ON w.ref = bw.ref WHERE bw.ref NOT LIKE 'local/%'")
                worksNew = inBackup - worksHad
                // Where both have a title, the newer scores win; a year or length this TV lacks is filled in.
                db.execSQL(
                    "INSERT OR REPLACE INTO works ($cols) SELECT bw.ref, bw.imdb, bw.rt, bw.at, CASE WHEN bw.year > 0 THEN bw.year ELSE w.year END, " +
                        "CASE WHEN bw.minutes > 0 THEN bw.minutes ELSE w.minutes END, bw.trailer, bw.trailer_ok, bw.award, bw.award_ok, bw.poster, bw.poster_ok " +
                        "FROM b.works bw JOIN works w ON w.ref = bw.ref WHERE bw.at > w.at",
                )
                db.execSQL("INSERT OR IGNORE INTO works ($cols) SELECT $cols FROM b.works")
                db.execSQL("UPDATE works SET year = (SELECT bw.year FROM b.works bw WHERE bw.ref = works.ref) WHERE year = 0 AND ref IN (SELECT ref FROM b.works WHERE year > 0)")
                db.execSQL("UPDATE works SET minutes = (SELECT bw.minutes FROM b.works bw WHERE bw.ref = works.ref) WHERE minutes = 0 AND ref IN (SELECT ref FROM b.works WHERE minutes > 0)")
                step(22, "Cast and directors")
                db.execSQL("INSERT OR IGNORE INTO people (name) SELECT name FROM b.people")
                db.execSQL(
                    "INSERT OR IGNORE INTO work_people (ref, pid, role) SELECT bl.ref, p.pid, bl.role FROM b.work_people bl " +
                        "JOIN b.people bp ON bp.pid = bl.pid JOIN people p ON p.name = bp.name",
                )
                step(34, "Genres")
                db.execSQL("INSERT OR IGNORE INTO genres (name) SELECT name FROM b.genres")
                db.execSQL(
                    "INSERT OR IGNORE INTO work_genres (ref, gid) SELECT bl.ref, g.gid FROM b.work_genres bl " +
                        "JOIN b.genres bg ON bg.gid = bl.gid JOIN genres g ON g.name = bg.name",
                )
                val theirs = ArrayList<String>()
                db.rawQuery("SELECT DISTINCT pl FROM b.titles", null).use { c -> while (c.moveToNext()) theirs.add(c.getString(0)) }
                val tcols = "pl, type, id, ref, how, at, state, seen"
                var other = 0
                theirs.forEachIndexed { index, fp ->
                    val here = mine[fp]
                    val target = here?.id ?: fp
                    val name = here?.name ?: "Another playlist${if (other++ > 0) " $other" else ""}"
                    val found = IntArray(2)
                    val of = IntArray(2)
                    for ((t, type) in listOf(ContentType.MOVIE, ContentType.SERIES).withIndex()) {
                        step(40 + (55 * (index * 2 + t)) / (theirs.size * 2), "$name · ${if (type == ContentType.MOVIE) "movies" else "series"}")
                        // A title this TV has not answered yet takes the backup's answer; an answered one is kept as it is.
                        db.execSQL(
                            "INSERT OR REPLACE INTO titles ($tcols) SELECT ?, bt.type, bt.id, bt.ref, bt.how, bt.at, bt.state, bt.seen FROM b.titles bt " +
                                "JOIN titles t ON t.pl = ? AND t.type = bt.type AND t.id = bt.id " +
                                "WHERE bt.pl = ? AND bt.type = ? AND t.how IS NULL AND t.ref IS NULL AND (bt.how IS NOT NULL OR bt.ref IS NOT NULL)",
                            arrayOf(target, target, fp, type.name),
                        )
                        db.execSQL("INSERT OR IGNORE INTO titles ($tcols) SELECT ?, type, id, ref, how, at, state, seen FROM b.titles WHERE pl = ? AND type = ?", arrayOf(target, fp, type.name))
                        // Matched: of the titles this TV lists (when its list is loaded), how many now know their movie or series.
                        val list = if (here != null && here.id == prefs.activeId) Session.streamCache["${type.name}:__all__"] else null
                        if (list != null) {
                            val known = HashSet<String>()
                            db.rawQuery("SELECT id FROM titles WHERE pl = ? AND type = ? AND ref IS NOT NULL AND ref NOT LIKE 'local/%'", arrayOf(target, type.name)).use { c ->
                                while (c.moveToNext()) known.add(c.getString(0))
                            }
                            of[t] = list.size
                            found[t] = list.count { it.id in known }
                        } else {
                            found[t] = count(db, "SELECT COUNT(*) FROM b.titles WHERE pl = ? AND type = ? AND ref IS NOT NULL AND ref NOT LIKE 'local/%'", fp, type.name)
                        }
                    }
                    sections.add(Section(here?.id, found[0], of[0], found[1], of[1], count(db, "SELECT COUNT(*) FROM b.titles WHERE pl = ?", fp)))
                }
                step(97, "Finishing")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            runCatching { db.execSQL("DETACH DATABASE b") }
        }
        val summary = Summary(System.currentTimeMillis(), backupAt, sections, worksNew, worksHad)
        sp(context).edit().putString("last_restore", summary.toJson().toString()).apply()
        Ratings.reload()
        RatingsFill.rebuild()
        // Counts only: never names, ids or addresses.
        Diag.log("ratings", "library data restored: ${sections.size} playlists (${sections.count { it.playlist != null }} on this TV), $worksNew new titles' details, $worksHad already here")
        onProgress(100, "Done")
        summary
    }

    /** A playlist added after a restore: the rows kept for it become its rows. True when there were any. */
    fun adoptKept(context: Context, playlist: Playlist): Boolean {
        val fp = fingerprint(playlist)
        val db = Ratings.db.writableDatabase
        if (count(db, "SELECT COUNT(*) FROM titles WHERE pl = ?", fp) == 0) return false
        db.beginTransaction()
        try {
            db.execSQL("UPDATE OR IGNORE titles SET pl = ? WHERE pl = ?", arrayOf(playlist.id, fp))
            db.execSQL("DELETE FROM titles WHERE pl = ?", arrayOf(fp))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Ratings.reload()
        Diag.log("ratings", "library data: a playlist's kept titles are now in use")
        return true
    }

    // ---- Clear, and Undo last change ----

    /** Empties the database (after a safety copy). Playlists, favourites, settings and keys are not touched. */
    fun clear(context: Context) = synchronized(RatingsFill.gate) {
        copyDatabase(context, undoFile(context))
        val db = Ratings.db.writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("titles", "works", "people", "work_people", "genres", "work_genres", "playlists")) db.execSQL("DELETE FROM $t")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        sp(context).edit().remove("last_restore").apply()
        Ratings.reload()
        RatingsFill.rebuild()
        Diag.log("ratings", "library data cleared")
    }

    fun hasUndo(context: Context) = undoFile(context).exists()

    /** Puts back the database as it was before the last Restore or Clear. */
    fun undo(context: Context): Boolean = synchronized(RatingsFill.gate) {
        val saved = undoFile(context)
        if (!saved.exists()) return false
        Ratings.flush()
        Ratings.db.close()
        val live = dbFile(context)
        File(live.path + "-journal").delete()
        saved.copyTo(live, overwrite = true)
        saved.delete()
        sp(context).edit().remove("last_restore").apply()
        Ratings.reload()
        RatingsFill.rebuild()
        Diag.log("ratings", "library data: last change undone")
        true
    }

    /** For the Settings card: titles on this TV's playlists that know their movie or series, and movies and series with scores. */
    fun sizes(): Pair<Int, Int> {
        val db = Ratings.db.readableDatabase
        return count(db, "SELECT COUNT(*) FROM titles WHERE ref IS NOT NULL AND ref NOT LIKE 'local/%'") to
            count(db, "SELECT COUNT(*) FROM works WHERE imdb IS NOT NULL OR rt IS NOT NULL")
    }
}
