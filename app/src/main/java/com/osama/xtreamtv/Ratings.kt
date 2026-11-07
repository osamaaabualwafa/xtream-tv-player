package com.osama.xtreamtv

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Review item 1.1E: real IMDb and Rotten Tomatoes scores from MDBList, with the owner's own free key.
 *
 * - A title is found by its TMDB or IMDb id when the playlist gives one. Without an id (and with
 *   "Find by name" on) it is searched by its name and year, and accepted only when MDBList returns
 *   exactly one title with that name and its poster is the same picture as the playlist's.
 * - Scores are kept 7 days (this year's and last year's titles) or 30 days (older); the matched id is
 *   kept for good, so a refresh never searches again.
 * - "Mark wrong match" puts a title on a never-match list.
 * - Posters only show scores already known; lookups come from the screen showing that title.
 *
 * Never logged or reported: the key, titles, ids, addresses. Diagnostics get only how a title matched.
 * The key lives in its own preferences file, so backups and the settings log never see it.
 */
object Ratings {
    private const val BASE = "https://api.mdblist.com"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * How long a saved answer is trusted: a week for titles from this year or last (their scores still
     * move) and for "not found"; 30 days for older titles, whose scores hardly change.
     */
    private fun isFresh(e: Entry): Boolean {
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val keep = if (e.how != How.NONE && e.year in 1900..(thisYear - 2)) MONTH_MS else WEEK_MS
        return System.currentTimeMillis() - e.at < keep
    }
    /** Below this many lookups left today, only the title on screen is looked up. */
    private const val KEEP_FOR_SCREEN = 100

    private lateinit var app: Application
    private val sp by lazy { app.getSharedPreferences("ratings", Context.MODE_PRIVATE) }
    private val cacheFile by lazy { File(app.filesDir, "ratings-cache.json") }

    fun init(application: Application) {
        app = application
        RatingsExtras.init(application)
    }

    // ---- Settings ----

    var key: String?
        get() = sp.getString("key", null)
        set(value) {
            sp.edit().putString("key", value?.trim()?.takeIf { it.isNotEmpty() }).apply()
        }

    val hasKey get() = key != null

    /** Show ratings: the main switch (the key and saved scores stay when it is off). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    /** On, with a key: scores are shown and asked for. */
    private val active get() = hasKey && enabled

    /** Ratings are on (a key, and Show ratings on). */
    val isOn get() = active

    /** The key's last 4 characters, to tell keys apart on screen. */
    val keyEnd get() = key?.takeLast(4)

    /** False once MDBList refused the key (401); true after a test or an answer that worked. */
    val keyWorks get() = sp.getBoolean("key_ok", true)

    var showOnPosters: Boolean
        get() = sp.getBoolean("posters", true)
        set(value) = sp.edit().putBoolean("posters", value).apply()

    var findByName: Boolean
        get() = sp.getBoolean("by_name", true)
        set(value) = sp.edit().putBoolean("by_name", value).apply()

    /** A key typed or pasted: letters, digits, - and _. */
    fun looksLikeKey(text: String): Boolean = text.trim().length in 10..80 && text.trim().all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Remove key: ratings off, the key and every saved score gone. */
    fun removeKey() {
        sp.edit().clear().apply()
        synchronized(cache) { cache.clear() }
        scoresVersion++
        cacheFile.delete()
        RatingsExtras.clear()
    }

    // ---- Today's usage (from MDBList's own counters) ----

    class Usage(val used: Int, val limit: Int, val resetsAt: Long)

    fun usage(): Usage? {
        val limit = sp.getInt("u_limit", 0)
        if (limit <= 0) return null
        val resetsAt = sp.getLong("u_reset", 0L)
        val used = if (resetsAt in 1..System.currentTimeMillis()) 0 else sp.getInt("u_used", 0)
        // MDBList's day ends at 00:00 UTC.
        val next = if (resetsAt > System.currentTimeMillis()) resetsAt else nextUtcMidnight()
        return Usage(used, limit, next)
    }

    private fun nextUtcMidnight(): Long {
        val day = 24L * 60 * 60 * 1000
        return (System.currentTimeMillis() / day + 1) * day
    }

    private fun saveUsage(used: Int, limit: Int, resetsAt: Long?) {
        sp.edit().putInt("u_used", used).putInt("u_limit", limit).putLong("u_reset", resetsAt ?: nextUtcMidnight()).apply()
    }

    private fun left(): Int {
        val u = usage() ?: return Int.MAX_VALUE
        return u.limit - u.used
    }

    /** Daily limit reached (429): nothing more until MDBList's day ends. */
    private fun blockedUntil() = sp.getLong("blocked_until", 0L)

    // ---- Saved scores ----

    /** How a title was matched. */
    enum class How { ID, NAME, NONE, WRONG }

    class Entry(
        val imdb: String?, val tomatoes: Int?, val at: Long, val how: How, val ref: String?,
        val trailer: String? = null,
        /** False for titles saved before trailers were kept (no answer about a trailer yet). */
        val trailerChecked: Boolean = true,
        /** The title's year from MDBList (how long the scores are kept); 0 when unknown. */
        val year: Int = 0,
        /** Its award ("Oscar winner", …), from the same answer; null when none. */
        val award: String? = null,
        /** False for titles saved before awards were kept: posters ask once more (in a group). */
        val awardChecked: Boolean = true,
    ) {
        val scores get() = RatingBadges.Scores(imdb, tomatoes).takeIf { !it.isEmpty }
        fun toJson() = JSONObject().put("i", imdb).put("t", tomatoes).put("a", at).put("h", how.name).put("r", ref)
            .apply { if (trailerChecked) put("v", trailer ?: "") }
            .apply { if (year > 0) put("y", year) }
            .apply { if (awardChecked) put("w", award ?: "") }
    }

    private val cache: HashMap<String, Entry> by lazy {
        val map = HashMap<String, Entry>()
        runCatching {
            val o = JSONObject(cacheFile.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                map[k] = Entry(
                    e.optString("i").takeIf { it.isNotEmpty() && it != "null" },
                    if (e.isNull("t") || !e.has("t")) null else e.optInt("t"),
                    e.optLong("a"),
                    runCatching { How.valueOf(e.optString("h")) }.getOrDefault(How.NONE),
                    e.optString("r").takeIf { it.isNotEmpty() && it != "null" },
                    e.optString("v").takeIf { it.isNotEmpty() && it != "null" },
                    e.has("v"),
                    e.optInt("y", 0),
                    e.optString("w").takeIf { it.isNotEmpty() && it != "null" },
                    e.has("w"),
                )
            }
        }
        map
    }

    /**
     * Saves the cache soon, on one background thread: changes made close together are written once
     * (a screen of posters used to rewrite the whole file 25 times), and two writes never mix in the
     * same file (a save from the main thread during a lookup could leave a broken file: every score lost).
     */
    private fun save() {
        if (!saveQueued.compareAndSet(false, true)) return
        saver.execute {
            Thread.sleep(400)
            saveQueued.set(false)
            val text = synchronized(cache) { JSONObject().apply { for ((k, e) in cache) put(k, e.toJson()) }.toString() }
            writeFile(cacheFile, text)
        }
    }

    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The one thread that writes the ratings' files ([RatingsExtras] too). */
    internal val saver: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Write to a side file, then swap it in, so a crash mid-write never leaves half a file. */
    internal fun writeFile(file: File, text: String) {
        runCatching {
            val part = File(file.path + ".part")
            part.writeText(text)
            if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
        }
    }

    /** One title of one playlist. */
    private fun cacheKey(type: ContentType, id: String) = "${Prefs(app).activeId ?: "legacy"}:${type.name}:$id"

    /** The key a title is saved under (this playlist, type, id); shared with [RatingsExtras]. */
    fun keyOf(type: ContentType, id: String) = cacheKey(type, id)

    // ---- Lists built from saved scores ----

    /** A title's saved IMDb score as a number (0 when none): sorting and Top rated. */
    fun scoreOf(item: StreamItem): Double = known(item.type, item.ratingId ?: item.id)?.imdb?.toDoubleOrNull() ?: 0.0

    private fun tomatoesOf(item: StreamItem): Int = known(item.type, item.ratingId ?: item.id)?.tomatoes ?: 0

    /** Bumped whenever saved scores change: lists made from them are made again. */
    @Volatile private var scoresVersion = 0L

    /**
     * Each item's IMDb score and Rotten Tomatoes score (0 when none) in one pass: one lock and the
     * open playlist read once. Looking titles up one by one inside a sort read the playlist and made a
     * new name for every comparison: hundreds of thousands for 21,815 movies, which froze a TV that was
     * short of memory for 22 s (diagnostics report, 2026-11-07).
     */
    private fun scoreArrays(items: List<StreamItem>): Pair<DoubleArray, IntArray> {
        val imdb = DoubleArray(items.size)
        val rt = IntArray(items.size)
        if (!active) return imdb to rt
        val prefix = "${Prefs(app).activeId ?: "legacy"}:"
        synchronized(cache) {
            for (i in items.indices) {
                val it = items[i]
                val e = cache[prefix + it.type.name + ":" + (it.ratingId ?: it.id)] ?: continue
                imdb[i] = e.imdb?.toDoubleOrNull() ?: 0.0
                rt[i] = e.tomatoes ?: 0
            }
        }
        return imdb to rt
    }

    /** The last Top rated list made: made again only for another list, playlist or new scores. */
    private var topMemo: Triple<List<StreamItem>, String, List<StreamItem>>? = null
    private val topLock = Any()

    /**
     * Top rated: IMDb 7.5 and up among [all], best first (ties: Rotten Tomatoes). Saved scores only.
     * Can take a moment on a big playlist: call it off the main thread.
     */
    fun topRated(all: List<StreamItem>, limit: Int = Int.MAX_VALUE): List<StreamItem> {
        if (!active) return emptyList()
        val stamp = "${Prefs(app).activeId}:$scoresVersion"
        synchronized(topLock) { topMemo?.takeIf { it.first === all && it.second == stamp }?.let { return it.third.take(limit) } }
        val (imdb, rt) = scoreArrays(all)
        val seen = HashSet<String>()
        val result = all.indices
            .filter { all[it].type != ContentType.LIVE && imdb[it] >= 7.5 && seen.add("${all[it].type.name}:${all[it].id}") }
            .sortedWith(compareByDescending<Int> { imdb[it] }.thenByDescending { rt[it] })
            .map { all[it] }
        synchronized(topLock) { topMemo = Triple(all, stamp, result) }
        return result.take(limit)
    }

    /** Sort by IMDb score: titles with a score first (best first), the rest in their order. */
    fun sortByScore(list: List<StreamItem>): List<StreamItem> {
        val (imdb, rt) = scoreArrays(list)
        val scored = list.indices.filter { imdb[it] > 0 }.sortedWith(compareByDescending<Int> { imdb[it] }.thenByDescending { rt[it] })
        return (scored + list.indices.filter { imdb[it] <= 0 }).map { list[it] }
    }

    /** The TV is short of memory: let go of what can be made again (the name index, Top rated). */
    fun trimMemory() {
        synchronized(nameIndexLock) { nameIndex = null }
        synchronized(topLock) { topMemo = null }
    }

    /** This playlist's titles of [type] whose TMDB id is known from a lookup: TMDB id -> stream id. */
    fun idsByTmdb(type: ContentType): Map<String, String> {
        val prefix = "${Prefs(app).activeId ?: "legacy"}:${type.name}:"
        val kind = if (type == ContentType.MOVIE) "tmdb/movie/" else "tmdb/show/"
        return synchronized(cache) {
            cache.entries.filter { it.key.startsWith(prefix) && it.value.ref?.startsWith(kind) == true }
                .associate { it.value.ref!!.removePrefix(kind) to it.key.removePrefix(prefix) }
        }
    }

    /**
     * The full MDBList answer for a title already matched (its saved id), with recommendations, for
     * [RatingsExtras]; also refreshes the title's scores. Null when not matched, no lookups left or offline.
     */
    internal suspend fun fullAnswer(type: ContentType, id: String): JSONObject? {
        val k = key ?: return null
        if (!active || System.currentTimeMillis() < blockedUntil() || left() < 1) return null
        val e = entry(type, id) ?: return null
        val ref = e.ref ?: return null
        if (e.how == How.WRONG) return null
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val (code, body) = runCatching { get("/$ref", k, "append_to_response" to "recommendations") }.getOrDefault(-1 to null)
                if (code != 200 || body == null) return@withContext null
                // An unexpected answer (an error page, an outage) is "nothing new", never a crash.
                val media = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
                put(type, id, entryFrom(media, e.how, ref))
                media
            }
        }
    }

    private fun entry(type: ContentType, id: String) = synchronized(cache) { cache[cacheKey(type, id)] }

    private fun put(type: ContentType, id: String, e: Entry) {
        synchronized(cache) { cache[cacheKey(type, id)] = e }
        scoresVersion++
        save()
    }

    /** Scores already known for a title (any age), or null. */
    fun known(type: ContentType, id: String): RatingBadges.Scores? = if (active) entry(type, id)?.scores else null

    fun how(type: ContentType, id: String): How? = if (active) entry(type, id)?.how else null

    /** A title found before trailers were kept: its page asks MDBList once more, for the trailer. */
    fun needsTrailer(type: ContentType, id: String): Boolean =
        active && entry(type, id)?.let { it.ref != null && it.how != How.WRONG && !it.trailerChecked } == true

    /** MDBList's trailer link for a title, for when the provider has none. */
    fun trailer(type: ContentType, id: String): String? = if (active) entry(type, id)?.takeIf { it.how != How.WRONG }?.trailer else null

    /** A title found before awards were kept, by its TMDB id: its poster asks once more, in a group. */
    private fun needsAward(type: ContentType, id: String): Boolean =
        active && entry(type, id)?.let { !it.awardChecked && it.how != How.WRONG && it.ref?.startsWith("tmdb/") == true } == true

    /** True when a lookup would ask MDBList (no saved answer still fresh: see isFresh), so it's worth getting the title's ids. */
    fun needsLookup(type: ContentType, id: String): Boolean {
        if (!active || System.currentTimeMillis() < blockedUntil()) return false
        val e = entry(type, id) ?: return true
        return e.how != How.WRONG && !isFresh(e)
    }

    /** A screen showing a list item (Home banner, info strip): its scores, asking MDBList when needed. */
    suspend fun lookupItem(api: XtreamApi, item: StreamItem, onScreen: Boolean): RatingBadges.Scores? {
        if (item.type == ContentType.LIVE || !active) return null
        if (!needsLookup(item.type, item.id)) return known(item.type, item.id)
        if (!onScreen && left() < KEEP_FOR_SCREEN) return known(item.type, item.id)
        return lookup(titleFor(api, item), onScreen)
    }

    /**
     * A poster's award ribbon: winners only, when scores show on posters. From the saved scores, or the
     * title's page extras for titles saved before awards were kept.
     */
    fun awardFor(item: StreamItem): AwardArt.Kind? {
        if (!active || !showOnPosters || item.type == ContentType.LIVE) return null
        val id = item.ratingId ?: item.id
        val e = entry(item.type, id)?.takeIf { it.how != How.WRONG } ?: return null
        val badge = e.award ?: RatingsExtras.saved(item.type, id)?.awards?.let { RatingsExtras.awardBadge(it) }
        return AwardArt.parse(badge)?.takeIf { it.winner }?.kind
    }

    /** For posters: known scores when "Show on posters" is on (test builds: sample scores). */
    fun forPoster(context: Context, item: StreamItem): RatingBadges.Scores? {
        RatingBadges.sample(context, item.id)?.let { return it }
        if (!showOnPosters) return null
        return known(item.type, item.ratingId ?: item.id)
    }

    /** Test builds: the shape of a full MDBList answer (field names, short values), to "RatingsMatch". */
    fun probeForTests(path: String) {
        if (!BuildConfig.DEBUG) return
        val k = key ?: return
        Thread {
            runCatching {
                val (code, body) = get(path, k, "append_to_response" to "recommendations")
                trace("probe $path -> $code")
                val o = JSONObject(body ?: return@runCatching)
                for (name in o.keys()) {
                    val v = o.opt(name)
                    val text = when (v) {
                        is JSONArray -> "[${v.length()}] " + (v.opt(0)?.let { f -> if (f is JSONObject) "{" + f.keys().asSequence().joinToString(",") + "} " + f.toString().take(160) else f.toString().take(80) } ?: "")
                        is JSONObject -> v.toString().take(220)
                        else -> v.toString().take(90)
                    }
                    trace("  $name = $text")
                }
            }.onFailure { trace("probe failed: ${it.javaClass.simpleName}") }
        }.start()
    }

    /** Test builds: "SERIES:123" of the titles that found nothing. */
    fun notFoundIdsForTests(): Set<String> = synchronized(cache) {
        cache.filterValues { it.how == How.NONE }.keys.map { it.substringAfter(':') }.toSet()
    }

    /** Test builds: forget the answers that found nothing, so the sample can try them again. */
    fun forgetNotFoundForTests(): Int {
        if (!BuildConfig.DEBUG) return 0
        val n = synchronized(cache) {
            val gone = cache.filterValues { it.how == How.NONE }.keys
            gone.forEach { cache.remove(it) }
            gone.size
        }
        scoresVersion++
        save()
        return n
    }

    /** Mark wrong match: no scores for this title, and never matched again. */
    fun markWrong(type: ContentType, id: String) {
        put(type, id, Entry(null, null, System.currentTimeMillis(), How.WRONG, null))
        Diag.log("ratings", "marked a wrong match")
    }

    /** Refresh saved scores: every title is asked again the next time it is shown (matches are kept). */
    fun refreshAll() {
        synchronized(cache) {
            for ((k, e) in cache.entries.toList()) if (e.how != How.WRONG) cache[k] = Entry(e.imdb, e.tomatoes, 0L, e.how, e.ref, e.trailer, e.trailerChecked, e.year, e.award, e.awardChecked)
        }
        save()
    }

    class Matching(val byId: Int, val byName: Int, val notFound: Int, val wrong: Int)

    /** Check matching: the titles of the open playlist, by how they were matched. */
    fun matching(): Matching {
        val prefix = "${Prefs(app).activeId ?: "legacy"}:"
        val list = synchronized(cache) { cache.filterKeys { it.startsWith(prefix) }.values.toList() }
        return Matching(list.count { it.how == How.ID }, list.count { it.how == How.NAME }, list.count { it.how == How.NONE }, list.count { it.how == How.WRONG })
    }

    // ---- Posters on screen ----

    // A failure while filling posters is logged, never a crash.
    private val main = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Diag.log("ratings", "posters: ${e.javaClass.simpleName}") },
    )

    /** Titles whose details had no id this session (not read again until the app restarts). */
    private val noId = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Titles whose list id belongs to a film of another year: not asked in poster groups again this session. */
    private val wrongListId = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * A poster list fills in the scores of the posters on screen by itself: their ids (from the list
     * when it has them, else from the title's details, read once: tmdb_id, then the backdrop number),
     * then one MDBList request for the whole group.
     * Titles without an id are left to the highlight and the details page (a name search each).
     */
    fun attachPosters(rv: androidx.recyclerview.widget.RecyclerView, adapter: ItemAdapter) {
        var job: kotlinx.coroutines.Job? = null
        var again = false
        fun schedule() {
            if (!active || !showOnPosters) return
            if (job?.isActive == true) { again = true; return }
            job = main.launch {
                kotlinx.coroutines.delay(800)
                if (!rv.isAttachedToWindow) return@launch
                val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@launch
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first < 0 || last < first) return@launch
                val end = minOf(adapter.items.size, last + 1 + 8)
                val shown = adapter.items.subList(first, end).toList()
                val api = Session.api(rv.context) ?: return@launch
                val changed = fill(api, shown)
                if (changed.isNotEmpty() && rv.isAttachedToWindow) {
                    adapter.items.forEachIndexed { i, item -> if ("${item.type.name}:${item.ratingId ?: item.id}" in changed) adapter.notifyItemChanged(i, "scores") }
                }
            }
            job?.invokeOnCompletion { if (again) { again = false; main.launch { schedule() } } }
        }
        rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) schedule()
            }
        })
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = schedule()
        })
        rv.post { schedule() }
    }

    /** Pre-fill uses at most this many lookups a day, and none when fewer than [PREFILL_KEEP] are left. */
    private const val PREFILL_DAY = 200
    private const val PREFILL_KEEP = 300

    /**
     * Pre-fill (1.1E): while Home sits idle, the scores of [items] (Continue watching, favorites, the
     * Home rows) are asked for in groups, so they show at once later. Titles with an id only (a name
     * search is left to the highlight and the page). Stops at its own daily share; cancelled by a key press.
     */
    suspend fun prefill(api: XtreamApi, items: List<StreamItem>) {
        if (!active) return
        val today = java.text.SimpleDateFormat("yyyyDDD", java.util.Locale.US).format(java.util.Date())
        if (sp.getString("pf_day", "") != today) sp.edit().putString("pf_day", today).putInt("pf_used", 0).apply()
        val todo = items.filter { it.type != ContentType.LIVE && needsLookup(it.type, it.ratingId ?: it.id) }
            .distinctBy { "${it.type.name}:${it.ratingId ?: it.id}" }
        var done = 0
        for (chunk in todo.chunked(25)) {
            if (left() < PREFILL_KEEP || sp.getInt("pf_used", 0) >= PREFILL_DAY) break
            val before = usage()?.used ?: 0
            done += fill(api, chunk).size
            val spent = ((usage()?.used ?: before) - before).coerceAtLeast(0)
            sp.edit().putInt("pf_used", sp.getInt("pf_used", 0) + spent).apply()
        }
        if (done > 0) Diag.log("ratings", "pre-fill: $done titles")
    }

    /** "TYPE:id" of the titles whose scores changed. */
    private suspend fun fill(api: XtreamApi, shown: List<StreamItem>): Set<String> = withContext(Dispatchers.IO) {
        val k = key ?: return@withContext emptySet()
        if (!active || left() < KEEP_FOR_SCREEN || System.currentTimeMillis() < blockedUntil()) return@withContext emptySet()
        fun rid(it: StreamItem) = it.ratingId ?: it.id
        val need = shown.filter { it.type != ContentType.LIVE && (it.note == null || it.ratingId != null) && "${it.type.name}:${rid(it)}" !in wrongListId && (needsLookup(it.type, rid(it)) || needsAward(it.type, rid(it))) }
            .distinctBy { "${it.type.name}:${rid(it)}" }.take(40)
        if (need.isEmpty()) return@withContext emptySet()
        // Each title's TMDB id: the list's, a saved one, or (movies) its details, read once.
        // Details are read 5 at a time (one after another took about 10 s for a screen of movies).
        val gate = kotlinx.coroutines.sync.Semaphore(5)
        val withIds = kotlinx.coroutines.coroutineScope {
            need.map { item ->
                async {
                    val id = rid(item)
                    val saved = entry(item.type, id)?.ref?.takeIf { it.startsWith("tmdb/") }?.substringAfterLast('/')
                    val key = "${item.type.name}:$id"
                    // A Continue watching card: the id from the full list, when it is loaded.
                    val listed = item.tmdb ?: if (item.ratingId != null) Session.streamCache["${item.type.name}:__all__"]?.firstOrNull { it.id == id }?.tmdb else null
                    val tmdb = listed ?: saved ?: if (key !in noId) {
                        gate.withPermit {
                            runCatching { if (item.type == ContentType.MOVIE) api.vodInfo(id).tmdb else api.seriesInfo(id).tmdb }.getOrNull()
                        }.also { if (it == null) noId.add(key) }
                    } else null
                    item to tmdb
                }
            }.map { it.await() }
        }
        val ids = HashMap<ContentType, LinkedHashMap<String, StreamItem>>()
        for ((item, tmdb) in withIds) if (tmdb != null) ids.getOrPut(item.type) { LinkedHashMap() }[tmdb] = item
        trace("posters: ${shown.size} on screen (${shown.count { it.note != null }} with a note), ${need.size} to look up, ${ids.values.sumOf { it.size }} with an id")
        val changed = HashSet<String>()
        lock.withLock {
            for ((type, group) in ids) {
                val kind = mediaType(type)
                for (chunk in group.keys.toList().chunked(25)) {
                    val numbers = chunk.mapNotNull { it.toLongOrNull() }
                    if (numbers.isEmpty()) continue
                    val body = JSONObject().put("ids", JSONArray(numbers)).toString()
                    val (code, text) = runCatching { request("/tmdb/$kind", k, body) }.getOrDefault(-1 to null)
                    if (code != 200 || text == null) {
                        trace("posters: group of ${chunk.size} ${type.name} -> answer $code")
                        if (code == 429 || code == 401) return@withLock
                        continue
                    }
                    val answers = runCatching { JSONArray(text) }.getOrNull() ?: run {
                        trace("posters: group answer was not a list")
                        null
                    } ?: continue
                    if (answers.length() > 0) trace("  group answer fields: ${answers.optJSONObject(0)?.keys()?.asSequence()?.toList()} · ids: ${answers.optJSONObject(0)?.optJSONObject("ids")}")
                    val found = HashSet<String>()
                    for (i in 0 until answers.length()) {
                        val media = answers.optJSONObject(i) ?: continue
                        val idsObj = media.optJSONObject("ids")
                        val tmdb = listOf(idsObj?.opt("tmdb"), idsObj?.opt("tmdbid"), media.opt("tmdb_id"), media.opt("tmdbid"))
                            .firstNotNullOfOrNull { v -> v?.toString()?.takeIf { it.isNotEmpty() && it != "null" && it != "0" } } ?: continue
                        val item = group[tmdb] ?: continue
                        found.add(tmdb)
                        val id = rid(item)
                        // The list's id points at a film of another year: left to the title's page,
                        // which searches by name (see ask).
                        if (entry(item.type, id)?.ref == null && contradictsYear(item.type, item.year, media.optInt("year", 0))) {
                            wrongListId.add("${item.type.name}:$id")
                            continue
                        }
                        val how = if (entry(item.type, id)?.how == How.NAME) How.NAME else How.ID
                        val e = entryFrom(media, how, "tmdb/$kind/$tmdb")
                        put(item.type, id, e)
                        changed.add("${item.type.name}:$id")
                    }
                    // Ids MDBList doesn't know: nothing to show for a week (the saved id stays).
                    for (tmdb in chunk) if (tmdb !in found) group[tmdb]?.let { item ->
                        put(item.type, rid(item), Entry(null, null, System.currentTimeMillis(), How.NONE, "tmdb/$kind/$tmdb"))
                    }
                    trace("posters: group of ${chunk.size} ${type.name} -> ${found.size} found")
                }
            }
        }
        if (changed.isNotEmpty()) Diag.log("ratings", "posters: ${changed.size} found by id in a group")
        changed
    }

    // ---- Looking up ----

    /** What the lookup knows about a title. */
    class Title(
        val type: ContentType,
        val id: String,
        val name: String,
        val year: String?,
        val tmdb: String?,
        val imdb: String?,
        val poster: String?,
        /** The provider's original name, when it differs (movies: "o_name"). */
        val originalName: String? = null,
    )

    /** A title from the list, with its ids from its details when the list has none. */
    suspend fun titleFor(api: XtreamApi, item: StreamItem): Title {
        if (item.tmdb != null) return Title(item.type, item.id, item.name, item.year, item.tmdb, null, item.image)
        return if (item.type == ContentType.MOVIE) {
            val info = runCatching { api.vodInfo(item.id) }.getOrNull()
            Title(item.type, item.id, info?.name ?: item.name, item.year ?: info?.releaseDate?.let(::yearOf), info?.tmdb, info?.imdb, item.image ?: info?.cover,
                info?.originalName?.takeIf { it != (info.name ?: item.name) })
        } else {
            val info = runCatching { api.seriesInfo(item.id) }.getOrNull()
            Title(item.type, item.id, item.name, item.year ?: info?.releaseDate?.let(::yearOf), info?.tmdb, info?.imdb, item.image ?: info?.cover)
        }
    }

    private fun yearOf(text: String) = Regex("(19|20)\\d{2}").find(text)?.value

    private val lock = Mutex()

    /**
     * The title's scores: saved ones while still fresh (7 or 30 days), else asked from MDBList. [onScreen]:
     * the title is being shown now (details page, Home banner), so it may use the last lookups of the day.
     */
    suspend fun lookup(title: Title, onScreen: Boolean, fresh: Boolean = false): RatingBadges.Scores? {
        val k = key ?: return null
        if (!enabled) return null
        val saved = entry(title.type, title.id)
        if (saved != null && (saved.how == How.WRONG || (!fresh && isFresh(saved)))) return saved.scores
        if (System.currentTimeMillis() < blockedUntil()) return saved?.scores
        if (left() < (if (onScreen) 1 else KEEP_FOR_SCREEN)) return saved?.scores
        return lock.withLock {
            withContext(Dispatchers.IO) {
                // Another screen may have asked while this one waited.
                if (!fresh) entry(title.type, title.id)?.takeIf { isFresh(it) }?.let { return@withContext it.scores }
                val result = runCatching { ask(k, title, saved?.ref) }.getOrNull() ?: return@withContext saved?.scores
                put(title.type, title.id, result)
                Diag.log("ratings", when (result.how) {
                    How.ID -> "found by id"
                    How.NAME -> "found by name and year"
                    else -> "not found"
                })
                result.scores
            }
        }
    }

    private fun mediaType(type: ContentType) = if (type == ContentType.MOVIE) "movie" else "show"

    /**
     * Test builds only: how each title was matched, to the emulator's own log ("RatingsMatch"). Titles
     * and poster file names, never the key, addresses or whole links. Never in diagnostics reports.
     */
    private fun trace(text: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("RatingsMatch", text)
    }

    /** Null when MDBList couldn't be asked (no network, limit, bad key): try again later. */
    private fun ask(key: String, t: Title, ref: String?): Entry? {
        val kind = mediaType(t.type)
        val idRef = ref ?: t.tmdb?.let { "tmdb/$kind/$it" } ?: t.imdb?.let { "imdb/$kind/$it" }
        val label = "${t.type.name} \"${t.name}\""
        if (idRef != null) {
            val (code, body) = get("/$idRef", key)
            if (code == 200 && body != null) {
                val media = JSONObject(body)
                // The provider's id is checked against the title's year (a saved match is not): an id
                // pointing at another film ("Blade Runner 2049" carrying the 1982 film's id) is replaced
                // by a search by name.
                if (ref == null && contradictsYear(t.type, t.year, media.optInt("year", 0))) {
                    trace("$label · by id $idRef -> year ${media.optInt("year")} isn't ${t.year}: searched by name instead")
                    return if (findByName) byName(key, t) else Entry(null, null, System.currentTimeMillis(), How.NONE, null)
                }
                return entryFrom(media, if (ref != null && t.tmdb == null && t.imdb == null) How.NAME else How.ID, idRef)
                    .also { trace("$label · by id $idRef -> imdb ${it.imdb ?: "-"} · rt ${it.tomatoes ?: "-"}") }
            }
            trace("$label · by id $idRef -> answer $code")
            if (code != 404) return null
            if (ref != null || !findByName) return Entry(null, null, System.currentTimeMillis(), How.NONE, null)
        }
        if (!findByName) return Entry(null, null, System.currentTimeMillis(), How.NONE, null).also { trace("$label · no id, Find by name off") }
        return byName(key, t) ?: return null
    }

    /**
     * By name, safest first. Names tried in order, each only when the one before found nothing:
     * 1. the name cleaned as before (what matched until 2026-11-06 still matches the same way);
     * 2. the name cleaned further (a bare year at the end, "Multi"/"VF"/… tags, dots between words);
     * 3. movies: the original name the provider gives ("o_name": "La Guerre des étoiles" -> "Star Wars").
     * With a year: exactly one MDBList title with the same name and year (a year either side only when
     * none has the exact year); the poster needn't be the same (a series has several official posters).
     * Without a year: the same name and the identical poster file (2026-11-06, a provider that leaves
     * series years empty but uses TMDB's own posters). At most 6 MDBList requests for one title.
     */
    private fun byName(key: String, t: Title): Entry? {
        val none = Entry(null, null, System.currentTimeMillis(), How.NONE, null)
        val posterFile = fileName(t.poster)
        val kind = mediaType(t.type)
        var asked = 0
        val tried = HashSet<String>()
        val names = buildList {
            add(cleanName(t.name))
            add(cleanFurther(t.name))
            t.originalName?.let { add(cleanName(it)); add(cleanFurther(it)) }
        }
        for ((name, nameYear) in names) {
            // Exactly the same text tried already (not "the same once punctuation is dropped":
            // "Glass.Heart" and "Glass Heart" are different searches).
            if (name.length < 2 || !tried.add(name.lowercase())) continue
            val year = t.year ?: nameYear
            val label = "${t.type.name} \"${t.name}\" -> searched \"$name\""
            if (year == null && posterFile == null) { trace("$label · no year and no poster: not searched"); continue }
            if (asked >= 6) break
            val params = if (year != null) arrayOf("query" to name, "year" to year, "limit" to "10") else arrayOf("query" to name, "limit" to "10")
            val (code, body) = get("/search/$kind", key, *params)
            asked++
            if (code != 200 || body == null) {
                trace("$label ${year ?: "(no year)"} · search answer $code")
                if (code == 404) continue else return null
            }
            val results = JSONObject(body).optJSONArray("search") ?: JSONArray()
            val all = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            trace("$label ${year ?: "(no year)"} · ${all.size} results: " + all.take(6).joinToString(" | ") { "${it.optString("title")} (${it.optString("year")})" })
            val sameName = all.filter { r -> normal(r.optString("title")) == normal(name) }
            if (year != null) {
                val y = year.toInt()
                val exact = sameName.filter { it.optInt("year") == y }
                val same = exact.ifEmpty { sameName.filter { kotlin.math.abs(it.optInt("year") - y) == 1 } }
                if (same.size != 1) { trace("  -> ${same.size} with that name and year"); continue }
                val idRef = idRefOf(same[0], kind) ?: run { trace("  -> one match but no id in it"); null } ?: continue
                val (c2, b2) = get("/$idRef", key)
                asked++
                if (c2 != 200 || b2 == null) { trace("  -> title answer $c2"); if (c2 == 404) continue else return null }
                val media = JSONObject(b2)
                val poster = if (posterFile != null && fileName(media.optString("poster")) == posterFile) "same poster" else "other poster"
                return entryFrom(media, How.NAME, idRef).also { trace("  -> MATCHED $idRef ($poster, year ${same[0].optInt("year")}) · imdb ${it.imdb ?: "-"} · rt ${it.tomatoes ?: "-"}") }
            }
            // No year: each title with exactly this name, until one has the identical poster file.
            if (sameName.isEmpty()) { trace("  -> none with that name"); continue }
            for (hit in sameName.take(3)) {
                if (asked >= 6) break
                val idRef = idRefOf(hit, kind) ?: continue
                val (c2, b2) = get("/$idRef", key)
                asked++
                if (c2 != 200 || b2 == null) { if (c2 == 404) continue else return null }
                val media = JSONObject(b2)
                val theirs = fileName(media.optString("poster"))
                if (theirs == posterFile) {
                    return entryFrom(media, How.NAME, idRef).also { trace("  -> MATCHED $idRef (no year, same poster, year ${hit.optInt("year")}) · imdb ${it.imdb ?: "-"} · rt ${it.tomatoes ?: "-"}") }
                }
                trace("  -> ${hit.optString("title")} (${hit.optInt("year")}): poster differs (ours $posterFile · theirs $theirs)")
            }
        }
        return none.also { trace("${t.type.name} \"${t.name}\" -> not matched") }
    }

    /**
     * True when the provider's year and MDBList's year clearly disagree: more than a year apart for a
     * film; for a series only when MDBList's is later (providers often give the latest season's year,
     * MDBList the first). Unknown years never disagree.
     */
    private fun contradictsYear(type: ContentType, providerYear: String?, theirs: Int): Boolean {
        val ours = providerYear?.toIntOrNull() ?: return false
        if (theirs <= 0) return false
        return if (type == ContentType.MOVIE) kotlin.math.abs(theirs - ours) > 1 else theirs > ours + 1
    }

    private fun idRefOf(hit: JSONObject, kind: String): String? {
        val ids = hit.optJSONObject("ids")
        val tmdb = ids?.optString("tmdb")?.takeIf { it.isNotEmpty() && it != "null" } ?: ids?.optString("tmdbid")?.takeIf { it.isNotEmpty() && it != "null" }
        val imdb = ids?.optString("imdb")?.takeIf { it.startsWith("tt") } ?: ids?.optString("imdbid")?.takeIf { it.startsWith("tt") }
        return tmdb?.let { "tmdb/$kind/$it" } ?: imdb?.let { "imdb/$kind/$it" }
    }

    /**
     * The second try (only when [cleanName] found nothing): "Monster Hunter 2020 Multi" -> ("Monster
     * Hunter", "2020"); "Glass.Heart" -> "Glass Heart"; language and version tags removed. "S.W.A.T."
     * and "Mr. Robot" keep their dots; "Blade Runner 2049" was already tried whole by [cleanName].
     */
    fun cleanFurther(raw: String): Pair<String, String?> {
        val (first, year0) = cleanName(raw)
        var s = first
        // A language tag joined with an underscore: "EN_The Hateful Eight".
        s = s.replace(RX_UNDERSCORE_TAG, "")
        s = s.replace(RX_VERSION_TAGS, " ")
        // Dots between two words of 2+ letters: "Glass.Heart" (not "S.W.A.T.", not "Mr. Robot").
        s = s.replace(RX_WORD_DOT, " ")
        s = s.replace(RX_SPACES, " ").trim()
        var year = year0
        // A bare year at the end: "Monster Hunter 2020".
        RX_TRAILING_YEAR.find(s)?.let { m ->
            if (year == null) year = m.groupValues[2]
            s = m.groupValues[1]
        }
        s = s.replace(RX_SPACES, " ").trim(' ', '-', '|', ':')
        return (if (s.isEmpty()) first else s) to year
    }

    private fun entryFrom(media: JSONObject, how: How, ref: String): Entry {
        var imdb: String? = null
        var tomatoes: Int? = null
        val ratings = media.optJSONArray("ratings") ?: JSONArray()
        for (i in 0 until ratings.length()) {
            val r = ratings.optJSONObject(i) ?: continue
            if (r.isNull("value")) continue
            val value = r.optDouble("value", Double.NaN)
            if (value.isNaN() || value <= 0.0) continue
            when (r.optString("source")) {
                "imdb" -> imdb = "%.1f".format(java.util.Locale.US, value)
                "tomatoes" -> tomatoes = value.toInt().coerceIn(0, 100)
            }
        }
        // The trailer comes in the same answer: a YouTube link (used when the provider has none).
        val trailer = media.optString("trailer").takeIf { it.startsWith("http") && ("youtube" in it || "youtu.be" in it) }
        return Entry(imdb, tomatoes, System.currentTimeMillis(), how, ref, trailer, year = media.optInt("year", 0),
            award = RatingsExtras.awardBadge(media.optString("awards").takeIf { it.isNotBlank() && it != "null" }))
    }

    /** "EN - The Matrix (1999) 4K" -> ("The Matrix", "1999"). */
    fun cleanName(raw: String): Pair<String, String?> {
        var s = raw.trim()
        val year = RX_BRACKET_YEAR.find(s)?.groupValues?.get(1)
        s = s.replace(RX_BRACKETS, " ")
        // A language or quality tag in front: "EN - ", "|AR| ", "4K - " (a colon is left: "IT: Chapter Two").
        s = s.replace(RX_FRONT_TAG, "")
        s = s.replace(RX_QUALITY_TAGS, " ")
        s = s.replace(RX_ARABIC_TAGS, " ")
        // "Bayoumi Afandi بيومي افندي": the same name twice; MDBList knows the English one.
        val latin = s.count { it in 'A'..'Z' || it in 'a'..'z' }
        val arabic = s.count { it in '\u0600'..'\u06FF' }
        if (latin >= 3 && arabic > 0) s = s.replace(RX_ARABIC_WORDS, " ")
        s = s.replace(RX_SPACES, " ").trim(' ', '-', '|', ':')
        return s to year
    }

    private fun normal(s: String) = s.lowercase().replace("&", "and").replace(RX_NOT_LETTERS, "")

    // The name-cleaning patterns, made once. Made again for every name, cleaning a 21,815-movie list for
    // More like this kept two threads busy for over a minute and filled the memory with throwaway
    // objects (2026-11-07: the row never appeared on the TV).
    private val RX_UNDERSCORE_TAG = Regex("^(?:[A-Z]{2,3})_+")
    private val RX_VERSION_TAGS = Regex("\\b(MULTI|VFF|VFQ|VF|VOSTFR|VOST|TRUEFRENCH|SUBFRENCH|FRENCH|DUAL|LATINO|CASTELLANO)\\b", RegexOption.IGNORE_CASE)
    private val RX_WORD_DOT = Regex("(?<=\\p{L}{2})\\.(?=\\p{L}{2})")
    private val RX_SPACES = Regex("\\s+")
    private val RX_TRAILING_YEAR = Regex("^(.*\\S)\\s+((?:19|20)\\d{2})$")
    private val RX_BRACKET_YEAR = Regex("[(\\[]((?:19|20)\\d{2})[)\\]]")
    private val RX_BRACKETS = Regex("[(\\[][^)\\]]*[)\\]]")
    private val RX_FRONT_TAG = Regex("^\\s*\\|?\\s*(?:[A-Z]{2,3}|4K|UHD|FHD|HD)\\s*[-|]\\s*")
    private val RX_QUALITY_TAGS = Regex("\\b(4K|UHD|FHD|HD|SD|HEVC|H265|MULTI[- ]?SUB|VOSTFR|DUBBED|SUBBED)\\b", RegexOption.IGNORE_CASE)
    private val RX_ARABIC_TAGS = Regex("(مدبلج|مدبلجة|مترجم|مترجمة)")
    private val RX_ARABIC_WORDS = Regex("[\\u0600-\\u06FF]+")
    private val RX_NOT_LETTERS = Regex("[^\\p{L}\\p{N}]+")

    /** A name to compare titles by: tags, years and punctuation left out ("The Matrix (1999) 4K" -> "thematrix"). */
    fun nameKey(name: String) = normal(cleanFurther(name).first)

    /**
     * Name -> title for a whole list, made once per list (cleaning 20,000 names took seconds per page).
     * Only the list in use is kept (found by the list itself, not by comparing 20,000 items): keeping
     * several 20,000-title lists in memory was too much for TV sticks.
     */
    private var nameIndex: Pair<List<StreamItem>, Map<String, StreamItem>>? = null
    private val nameIndexLock = Any()

    /** Only one index is made at a time: a second asker waits for it instead of making it again. */
    private val nameIndexBuild = Any()

    fun nameIndex(all: List<StreamItem>): Map<String, StreamItem> {
        synchronized(nameIndexLock) { nameIndex?.takeIf { it.first === all }?.let { return it.second } }
        synchronized(nameIndexBuild) {
            synchronized(nameIndexLock) { nameIndex?.takeIf { it.first === all }?.let { return it.second } }
            val map = HashMap<String, StreamItem>(all.size * 2)
            for (s in all) nameKey(s.name).takeIf { it.length >= 3 }?.let { map.putIfAbsent(it, s) }
            synchronized(nameIndexLock) { nameIndex = all to map }
            return map
        }
    }

    /** "https://image.tmdb.org/t/p/w600/AbC123.jpg" -> "abc123"; null for none or too short to trust. */
    private fun fileName(url: String?): String? {
        val path = url?.substringBefore('?')?.trimEnd('/') ?: return null
        val name = path.substringAfterLast('/').substringBeforeLast('.').lowercase()
            .replace(Regex("[_-](big|small|medium|large|original|thumb|w\\d+|\\d+x\\d+)$"), "")
        return name.takeIf { it.length >= 10 }
    }

    /** Test the key: true when it works (and today's usage is saved), false when refused, null when unreachable. */
    suspend fun test(candidate: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = get("/user", candidate.trim())
            when {
                code == 200 && body != null -> {
                    val o = JSONObject(body)
                    val limit = o.optInt("api_requests", 0)
                    if (limit > 0) saveUsage(o.optInt("api_requests_count", 0), limit, null)
                    sp.edit().putBoolean("key_ok", true).apply()
                    true
                }
                code == 401 || code == 403 -> false
                else -> null
            }
        }.getOrNull()
    }

    /** GET on MDBList. The key goes in the address MDBList asks for; it is never logged. */
    private fun get(path: String, key: String, vararg params: Pair<String, String>): Pair<Int, String?> = request(path, key, null, *params)

    /** GET, or POST with a JSON [body] (the batch lookup, which MDBList counts as one read). */
    private fun request(path: String, key: String, body: String?, vararg params: Pair<String, String>): Pair<Int, String?> {
        val query = (params.toList() + ("apikey" to key)).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("$BASE$path?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "XtreamTV/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            // The saved key refused (changed or deleted on mdblist.com): Settings shows it.
            if (code == 401 && key == Ratings.key) sp.edit().putBoolean("key_ok", false).apply()
            val limit = conn.getHeaderField("X-RateLimit-Limit")?.toIntOrNull()
            val remaining = conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
            val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
            if (limit != null && remaining != null && code != 429) saveUsage(limit - remaining, limit, reset)
            if (code == 429) {
                val wait = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: 60_000L
                sp.edit().putLong("blocked_until", System.currentTimeMillis() + wait).apply()
                Diag.log("ratings", "MDBList limit reached")
                return code to null
            }
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
            return code to body
        } finally {
            conn.disconnect()
        }
    }
}
