package com.osama.xtreamtv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException

/**
 * The whole-playlist fill (work list Sections 1-7, 2026-11-10): the scores of every movie and series
 * that has an id, asked for quietly in the background, so they are there before a title is shown.
 * It replaces the Home-only pre-fill.
 *
 * Stability first:
 * - One low-priority thread does everything; nothing here runs on the screen's thread.
 * - One request at a time, and one step at a time: a step is either one MDBList group request
 *   ([BATCH] ids, counted by MDBList as one) or one provider request for a movie's details (its id,
 *   cast, director, genre, year and length).
 * - Pace: a step every 5 s while the app is idle (no key for 10 s); nothing while the owner is
 *   browsing; a step every 30 s during steady playback (or none, by the setting); nothing while a
 *   video buffers or starts; nothing in the background.
 * - Any error is logged (counts only) and tried again later; it never reaches the app.
 * - [RESERVE] lookups a day are left for browsing.
 *
 * Privacy: MDBList gets ids, the media type and the key, nothing else. The order (Continue watching
 * and favourites first) is only used here. Logs carry counts, never ids, titles, the key or addresses.
 * Where the fill stopped is not stored separately: a title is done when its answer is in the database,
 * so closing the app or restarting the TV loses nothing.
 */
object RatingsFill {
    /** The largest group MDBList accepted as one request (tested 2026-11-10: 25, 50, 100 and 200 all one request). */
    const val BATCH = 200
    /** Lookups left for normal browsing each day. */
    const val RESERVE = 100

    private const val IDLE_AFTER_KEY_MS = 10_000L
    private const val IDLE_STEP_MS = 5_000L
    /** TMDB steps are small (a search and a title's details): one every 1.5 s while idle. */
    private const val TMDB_STEP_MS = 1_500L
    private const val PLAYING_STEP_MS = 30_000L
    private const val STEADY_MS = 15_000L
    private val RETRY_MS = longArrayOf(30_000L, 120_000L, 600_000L)

    enum class State {
        OFF, STARTING, FILLING, BROWSING, PLAYBACK, TOMORROW, OFFLINE, DONE, BAD_KEY, STOPPED,
        /** Every title has scores; old ones are being asked again (after Refresh saved scores, or when they expire). */
        REFRESHING,
        /** The day's lookups are used, but ids are still being found for tomorrow. */
        IDS_ONLY,
    }

    private class Job(val type: ContentType, val id: String, val ref: String?, val year: String?, val rank: Long, val refresh: Boolean = false)

    // This session, for the diagnostics report's summary (counts only).
    @Volatile private var groupsSent = 0
    @Volatile private var titlesAsked = 0
    @Volatile private var titlesFound = 0
    @Volatile private var providerAsked = 0

    /** One line for the report's summary; null when the fill has done nothing since the app started. */
    fun summaryLine(): String? {
        if (total == 0 && groupsSent == 0) return null
        return "${state.name.lowercase()} · $ready of $total ready · since the app started: $groupsSent groups ($titlesAsked titles, $titlesFound found) · " +
            "TMDB named $tmdbFound of $tmdbAsked · provider asked $providerAsked · $noId without an id"
    }

    private lateinit var app: Application
    @Volatile private var foreground = 0
    @Volatile private var thread: Thread? = null

    @Volatile var state = State.OFF
        private set

    // The player: open, playing, buffering; and since when it has played steadily.
    @Volatile private var playerOpen = false
    @Volatile private var playing = false
    @Volatile private var buffering = false
    @Volatile private var steadySince = 0L

    // The queue: only the worker thread touches it.
    private val idQueue = ArrayDeque<Job>()
    private val refQueue = ArrayList<Job>()
    /** Movies TMDB didn't recognise: their details are asked from the provider, one every 5 s. */
    private val providerQueue = ArrayDeque<Job>()
    private var lastProviderAt = 0L
    private var tmdbAsked = 0
    private var tmdbFound = 0
    // For the time estimate: titles taken for TMDB, how many went on to the provider, and the time it took.
    @Volatile private var tmdbTried = 0
    @Volatile private var tmdbPassedOn = 0
    @Volatile private var tmdbSpentMs = 0L
    private var builtFor: String? = null
    private var builtAt = 0L
    private var builtLists: List<Any?> = emptyList()
    @Volatile private var stale = true

    // Progress, for Settings.
    @Volatile private var total = 0
    @Volatile private var ready = 0
    @Volatile private var noId = 0
    @Volatile private var idsLeft = 0
    @Volatile private var refsLeft = 0
    /** Of [idsLeft], how many wait for TMDB (the rest wait for the provider). */
    @Volatile private var quickLeft = 0

    private var lastStepAt = 0L
    private var lastBatchAt = 0L
    private var fails = 0
    private var retryAt = 0L
    private var stopped = false

    /** Held for each step, and by the library backup, restore, clear and undo: they never overlap. */
    internal val gate = Any()

    /** Playlists already checked this session for titles kept from a restore. */
    private val adopted = HashSet<String>()

    /** Home's rows: their titles go right after Continue watching and favourites ("TYPE:id"). */
    @Volatile private var homeFirst: Set<String> = emptySet()

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foreground++
                if (thread == null) start()
            }
            override fun onActivityStopped(activity: Activity) { foreground = (foreground - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @Synchronized private fun start() {
        if (thread != null) return
        thread = Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            // The app's start (lists, Home) comes first.
            lastStepAt = SystemClock.uptimeMillis() + 20_000
            while (true) {
                try {
                    Thread.sleep(1000)
                    tick()
                } catch (t: Throwable) {
                    // Never the app's problem: logged without private data, tried again later.
                    note("fill: ${t.javaClass.simpleName}, will try again")
                    failed()
                }
            }
        }, "ratings-fill").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    // ---- What the rest of the app tells the fill ----

    /** The player's state: [open] while its screen shows; the fill slows down or waits. */
    fun player(open: Boolean, isPlaying: Boolean, isBuffering: Boolean) {
        if (isPlaying && !playing) steadySince = SystemClock.uptimeMillis()
        playerOpen = open
        playing = open && isPlaying
        buffering = open && isBuffering
    }

    /** The queue is made again (a refresh, another playlist, new titles). */
    fun rebuild() { stale = true }

    /** Remove key: the fill stops and its queue is emptied. */
    fun reset() {
        stale = true
        total = 0; ready = 0; noId = 0; idsLeft = 0; refsLeft = 0
        state = State.OFF
    }

    /** Home's rows, so their titles are filled early. Kept on the TV only. */
    fun homeRows(items: List<StreamItem>) {
        val set = HashSet<String>()
        for (i in items) if (i.type != ContentType.LIVE) set.add("${i.type.name}:${i.ratingId ?: i.id}")
        if (set != homeFirst) { homeFirst = set; if (state == State.DONE) return else stale = true }
    }

    /** A category was opened: the ones opened most are filled before the rest. Kept on the TV only. */
    fun noteCategory(type: ContentType, categoryId: String?) {
        if (type == ContentType.LIVE || categoryId == null || !Ratings.isOn) return
        val pl = Prefs(app).activeId ?: return
        runCatching {
            val sp = app.getSharedPreferences("ratings_fill", android.content.Context.MODE_PRIVATE)
            val o = JSONObject(sp.getString("opens_$pl", "{}") ?: "{}")
            val k = "${type.name}:$categoryId"
            o.put(k, o.optInt(k) + 1)
            sp.edit().putString("opens_$pl", o.toString()).apply()
        }
    }

    // ---- For Settings ----

    class Progress(val state: State, val ready: Int, val total: Int, val noId: Int, val secondsLeft: Long, val needsTomorrow: Boolean)

    fun progress(): Progress {
        val batches = (refsLeft + idsLeft + BATCH - 1) / BATCH
        val quick = if (Tmdb.usable) quickLeft else 0
        // Of the titles TMDB has been asked about this session, the share it could not name goes on to
        // the provider (one every 5 s). Before 30 titles have been tried, half is assumed.
        val toProvider = if (tmdbTried >= 30) tmdbPassedOn.toDouble() / tmdbTried else 0.5
        val perQuick = (TMDB_STEP_MS + if (tmdbTried > 0) tmdbSpentMs / tmdbTried else 1000L) / 1000.0
        val seconds = (quick * perQuick + (idsLeft - quick + quick * toProvider) * (IDLE_STEP_MS / 1000) + batches * (IDLE_STEP_MS / 1000)).toLong()
        val allowance = (Ratings.left() - RESERVE).coerceAtLeast(0)
        return Progress(state, ready, total, noId, seconds, batches > allowance)
    }

    // ---- The worker ----

    private fun tick() {
        if (foreground == 0) return
        if (!Ratings.isOn || !Ratings.fillWhole) { state = State.OFF; return }
        if (!Ratings.keyWorks) { state = State.BAD_KEY; return }
        val now = SystemClock.uptimeMillis()
        if (now - Diag.lastKeyAt < IDLE_AFTER_KEY_MS) { if (state != State.DONE) state = State.BROWSING; return }
        if (playerOpen) {
            if (!Ratings.fillDuringPlayback && (playing || buffering)) { state = State.PLAYBACK; return }
            if (buffering || (playing && now - steadySince < STEADY_MS)) { state = State.PLAYBACK; return }
        }
        val quick = !playing && Tmdb.usable && idQueue.isNotEmpty()
        if (now - lastStepAt < (if (playing) PLAYING_STEP_MS else if (quick) TMDB_STEP_MS else IDLE_STEP_MS)) return
        if (stopped) { state = State.STOPPED; return }
        if (now < retryAt) { state = State.OFFLINE; return }

        val pl = Prefs(app).activeId ?: return
        val lists = listOf(ContentType.MOVIE, ContentType.SERIES).map { Session.streamCache["${it.name}:__all__"] }
        // New lists (another playlist, a reload with new titles): the queue is made again.
        if (stale || builtFor != pl || lists.indices.any { lists[it] !== builtLists.getOrNull(it) } || now - builtAt > 6 * 60 * 60 * 1000L) {
            if (lists.all { it == null }) { state = State.STARTING; lastStepAt = now; return }
            build(pl, lists)
            builtLists = lists
            lastStepAt = now
            return
        }
        if (idQueue.isEmpty() && refQueue.isEmpty() && providerQueue.isEmpty()) { state = State.DONE; return }

        val noIdsLeft = idQueue.isEmpty() && providerQueue.isEmpty()
        val batchDue = refQueue.isNotEmpty() && now - lastBatchAt >= IDLE_STEP_MS && (refQueue.size >= BATCH || noIdsLeft || now - lastBatchAt > 10 * 60 * 1000L)
        val canAsk = System.currentTimeMillis() >= Ratings.blockedUntil() && Ratings.left() > RESERVE
        synchronized(gate) {
            // The library data may have been restored or cleared while this waited: start from a new queue.
            if (stale) return
            when {
                batchDue && canAsk -> batchStep(pl)
                idQueue.isNotEmpty() && Tmdb.usable -> tmdbStep(pl)
                !noIdsLeft && !Ratings.askProvider -> {
                    // "Ask the provider for missing IDs" is off: these titles are left to their own pages
                    // (nothing is saved about them, so they are tried again when the switch goes back on).
                    val left = providerQueue.size + if (Tmdb.usable) 0 else idQueue.size
                    providerQueue.clear()
                    if (!Tmdb.usable) idQueue.clear()
                    total -= left; noId += left
                    counts()
                    return
                }
                !noIdsLeft -> {
                    // The provider is asked no more than once every 5 s (30 s during playback).
                    if (now - lastProviderAt < (if (playing) PLAYING_STEP_MS else IDLE_STEP_MS)) return
                    lastProviderAt = now
                    idStep(pl)
                }
                !canAsk -> { state = State.TOMORROW; lastStepAt = now; return }
                else -> return
            }
        }
        // Out of lookups for today while ids are still being found: said plainly.
        if (!canAsk && refQueue.isNotEmpty() && state == State.FILLING) state = State.IDS_ONLY
        lastStepAt = SystemClock.uptimeMillis()
    }

    /** The queue: type, id, reference and year only, never whole titles; in the owner's order. */
    private fun build(pl: String, lists: List<List<StreamItem>?>) {
        idQueue.clear(); refQueue.clear(); providerQueue.clear()
        val tmdb = Tmdb.hasKey && Tmdb.keyWorks
        val prefs = Prefs(app)
        // A playlist added after a library restore: the titles kept for it are used now.
        if (adopted.add(pl)) prefs.activePlaylist?.let { p -> synchronized(gate) { runCatching { Library.adoptKept(app, p) } } }
        val first = HashSet<String>()
        for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
            for (w in prefs.continueWatching(type)) first.add("${type.name}:${w.seriesId ?: w.id}")
            for (f in prefs.favorites(type)) first.add("${type.name}:${f.id}")
        }
        val home = homeFirst
        val opened: Map<String, Int> = runCatching {
            val o = JSONObject(app.getSharedPreferences("ratings_fill", android.content.Context.MODE_PRIVATE).getString("opens_$pl", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optInt(it) }
        }.getOrDefault(emptyMap())
        var all = 0; var done = 0; var none = 0
        // The same title already answered (another playlist, or another copy here) is not asked again.
        val known = Ratings.freshByRef()
        val reused = HashMap<String, Ratings.Entry>()
        val ids = ArrayList<Job>(); val refs = ArrayList<Job>()
        for (list in lists) {
            if (list.isNullOrEmpty()) continue
            val type = list[0].type
            if (type == ContentType.LIVE) continue
            val kind = if (type == ContentType.MOVIE) "movie" else "show"
            val details = runCatching { Ratings.db.detailIds(pl, type) }.getOrDefault(HashMap())
            for (item in list) {
                val (saved, savedRef, answered) = Ratings.fillState("$pl:${type.name}:${item.id}")
                if (saved == 2) { all++; done++; continue }
                val d = details[item.id]
                if (d?.first == RatingsDb.Detail.BAD_ID) continue
                val ref = savedRef ?: item.tmdb?.let { "tmdb/$kind/$it" } ?: d?.second
                val key = "${type.name}:${item.id}"
                val tier = when {
                    key in first -> 0L
                    key in home -> 1L
                    else -> 3L - (item.categoryIds.maxOfOrNull { opened["${type.name}:$it"] ?: 0 }?.coerceAtMost(1) ?: 0)
                }
                // Newest first inside each group (Home's "recently added" rows come from there).
                val rank = tier * 100_000_000_000L + (99_999_999_999L - item.added.coerceIn(0, 99_999_999_999L))
                val same = if (ref != null) Ratings.sameTitle(known, type, ref, if (saved == 0) item.year else null) else null
                when {
                    same != null -> { all++; done++; reused["$pl:${type.name}:${item.id}"] = same }
                    // An old answer being asked again stays counted as ready: its scores are still shown.
                    ref != null -> { all++; if (saved == 1 && answered) done++; refs.add(Job(type, item.id, ref, item.year, rank, refresh = saved == 1 && answered)) }
                    // Asked before, no id; or (without a TMDB key) a series with no id in the list: left to its page.
                    d != null || (type != ContentType.MOVIE && !tmdb) -> none++
                    else -> { all++; ids.add(Job(type, item.id, null, item.year, rank)) }
                }
            }
        }
        ids.sortBy { it.rank }; refs.sortBy { it.rank }
        Ratings.adopt(reused)
        if (reused.isNotEmpty()) note("fill: ${reused.size} titles reused answers already saved")
        idQueue.addAll(ids); refQueue.addAll(refs)
        total = all; ready = done; noId = none; idsLeft = ids.size; refsLeft = refs.size; quickLeft = if (tmdb) ids.size else 0
        builtFor = pl; builtAt = SystemClock.uptimeMillis(); stale = false
        state = if (ids.isEmpty() && refs.isEmpty()) State.DONE else State.FILLING
        note("fill: queue made, $done of $all ready, ${refs.size} with an id waiting, ${ids.size} ids to find, $none without an id")
        if (BuildConfig.DEBUG) android.util.Log.d("RatingsFill", "database (titles, works, people, genres): " + Ratings.dbCountsForTests())
    }

    /** One MDBList request: up to [BATCH] titles of one type and one id family. */
    private fun batchStep(pl: String) {
        val head = refQueue[0]
        val family = head.ref!!.substringBefore('/')
        val taken = ArrayList<Job>(BATCH)
        val it = refQueue.iterator()
        while (it.hasNext() && taken.size < BATCH) {
            val j = it.next()
            if (j.type == head.type && j.ref!!.startsWith("$family/")) { taken.add(j); it.remove() }
        }
        state = if (taken.all { it.refresh } && idQueue.isEmpty() && providerQueue.isEmpty()) State.REFRESHING else State.FILLING
        val r = Ratings.fillBatch(pl, head.type, taken.map { Triple(it.id, it.ref!!, it.year) })
        lastBatchAt = SystemClock.uptimeMillis()
        if (r.code != 200) {
            refQueue.addAll(0, taken)
            when (r.code) {
                401 -> state = State.BAD_KEY
                429 -> state = State.TOMORROW
                0 -> state = State.OFF
                else -> failed()
            }
            note("fill: group of ${taken.size} not answered (${if (r.code > 0) r.code.toString() else "no connection"})")
            return
        }
        fails = 0
        for (id in r.badIds) runCatching { Ratings.db.markBadId("$pl:${head.type.name}:$id") }
        ready += taken.count { !it.refresh } - r.badIds.size
        total -= r.badIds.size
        groupsSent++; titlesAsked += taken.size; titlesFound += r.found
        refsLeft = refQueue.size
        Ratings.announce(r.changed)
        note("fill: group of ${taken.size}: ${r.found} found")
        if (idQueue.isEmpty() && refQueue.isEmpty() && providerQueue.isEmpty()) { state = State.DONE; note("fill: done, $ready of $total ready") }
    }

    /**
     * One title asked from TMDB: its name and year, checked against the poster. Found: its id, cast,
     * director, genres, year and length are saved and it joins the next MDBList group. Not found: a
     * movie goes to the provider's queue; a series is left to its page, as before.
     */
    private fun tmdbStep(pl: String) {
        val job = idQueue.removeFirst()
        val item = Session.streamCache["${job.type.name}:__all__"]?.firstOrNull { it.id == job.id }
        if (item == null) { total--; counts(); return }
        state = State.FILLING
        val startedAt = SystemClock.uptimeMillis()
        // Nothing TMDB could accept (no year, not one of its posters): straight to the provider.
        val r = if (Tmdb.canTry(item.name, item.year, item.image)) Tmdb.lookup(job.type, item.name, item.year, item.image) else Tmdb.Result(Tmdb.Outcome.NONE)
        when (r.outcome) {
            Tmdb.Outcome.OFFLINE -> { idQueue.addFirst(job); failed(); return }
            Tmdb.Outcome.BLOCKED -> { idQueue.addFirst(job); return }
            Tmdb.Outcome.NONE -> {
                if (job.type == ContentType.MOVIE && Ratings.askProvider) tmdbPassedOn++
                if (job.type == ContentType.MOVIE && Ratings.askProvider) providerQueue.add(job)
                else if (job.type == ContentType.MOVIE) { total--; noId++ }
                else {
                    runCatching { Ratings.db.putDetail("$pl:${job.type.name}:${job.id}", RatingsDb.Detail(RatingsDb.Detail.NO_ID, null, null, null, null, null, 0, 0, "tmdb")) }
                    total--; noId++
                }
            }
            Tmdb.Outcome.FOUND -> {
                val f = r.found!!
                tmdbFound++
                val key = "$pl:${job.type.name}:${job.id}"
                runCatching { Ratings.db.putDetail(key, RatingsDb.Detail(RatingsDb.Detail.OK, f.id, null, f.cast?.take(600), f.director?.take(200), f.genres?.take(200), f.year, f.minutes, "tmdb")) }
                val ref = "tmdb/${if (job.type == ContentType.MOVIE) "movie" else "show"}/${f.id}"
                Ratings.noteTmdb(key, ref)
                gotRef(pl, job, ref, job.year ?: f.year.takeIf { it > 0 }?.toString())
            }
        }
        fails = 0
        tmdbTried++
        tmdbSpentMs += SystemClock.uptimeMillis() - startedAt
        counts()
        if (++tmdbAsked % 200 == 0) note("fill: TMDB recognised $tmdbFound of $tmdbAsked titles so far")
        else if (BuildConfig.DEBUG && tmdbAsked % 25 == 0) android.util.Log.d("RatingsFill", "TMDB recognised $tmdbFound of $tmdbAsked (${job.type.name.lowercase()} now), ${providerQueue.size} left for the provider")
    }

    private fun counts() {
        idsLeft = idQueue.size + providerQueue.size
        quickLeft = idQueue.size
        refsLeft = refQueue.size
    }

    /** A title's id is known now: the same title's saved answer is used, or it joins the next MDBList group. */
    private fun gotRef(pl: String, job: Job, ref: String, year: String?) {
        val named = Ratings.howAt("$pl:${job.type.name}:${job.id}") == Ratings.How.TMDB
        val same = Ratings.sameTitle(Ratings.freshByRefCached(), job.type, ref, year, if (named) Ratings.How.TMDB else Ratings.How.ID)
        if (same != null) {
            Ratings.adopt(mapOf("$pl:${job.type.name}:${job.id}" to same))
            Ratings.announce(setOf("${job.type.name}:${job.id}"))
            ready++
        } else {
            refQueue.add(Job(job.type, job.id, ref, year, job.rank))
        }
    }

    /** One provider request: a movie's details, for its id; what else it says is kept too. */
    private fun idStep(pl: String) {
        val job = providerQueue.removeFirstOrNull() ?: idQueue.removeFirst()
        // Without TMDB, a series with no id is left to its page.
        if (job.type != ContentType.MOVIE) { total--; noId++; counts(); return }
        val api = Session.api(app) ?: run { providerQueue.addFirst(job); return }
        state = State.FILLING
        providerAsked++
        val info = try {
            kotlinx.coroutines.runBlocking { api.vodInfo(job.id) }
        } catch (e: IOException) {
            if (e.message == "No details available") null else { providerQueue.addFirst(job); failed(); return }
        } catch (e: Exception) {
            null
        }
        fails = 0
        val key = "$pl:${job.type.name}:${job.id}"
        val year = info?.releaseDate?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        val has = info?.tmdb != null || info?.imdb != null
        runCatching {
            Ratings.db.putDetail(key, RatingsDb.Detail(if (has) RatingsDb.Detail.OK else RatingsDb.Detail.NO_ID, info?.tmdb, info?.imdb,
                info?.cast?.take(600), info?.director?.take(200), info?.genre?.take(200), year?.toIntOrNull() ?: 0, minutes(info?.duration), "provider"))
        }
        val ref = info?.tmdb?.let { "tmdb/movie/$it" } ?: info?.imdb?.let { "imdb/movie/$it" }
        if (ref != null) gotRef(pl, job, ref, job.year ?: year) else { total--; noId++ }
        counts()
    }

    /** "01:45:00", "105 min", "105" -> 105; 0 when unknown. */
    private fun minutes(text: String?): Int {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return 0
        val p = t.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when {
            t.contains(':') && p.size == 3 -> p[0] * 60 + p[1]
            t.contains(':') && p.size == 2 -> p[0] * 60 + p[1]
            else -> Regex("\\d+").find(t)?.value?.toIntOrNull()?.takeIf { it < 1000 } ?: 0
        }
    }

    /** Counts only: never ids, titles, the key or addresses. */
    private fun note(text: String) {
        Diag.log("ratings", text)
        if (BuildConfig.DEBUG) android.util.Log.d("RatingsFill", text)
    }

    /** A request failed: wait 30 s, then 2 min, then 10 min; after that, until the app starts again. */
    private fun failed() {
        fails++
        if (fails > RETRY_MS.size) { stopped = true; state = State.STOPPED; return }
        retryAt = SystemClock.uptimeMillis() + RETRY_MS[fails - 1]
        state = State.OFFLINE
    }
}
