package com.osama.xtreamtv

import android.app.Application
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/** App-wide settings: settings protection on update, and how posters are loaded and kept. */
class XtreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Ratings.init(this)
        // The TV is short of memory (2026-11-07: Android closed the app on a TV warning 5 times a minute):
        // let go of what can be made again. In the background, the downloaded lists go too (they are
        // read again from the server when needed); never while a screen shows them.
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Ratings.trimMemory()
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Session.streamCache.clear()
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() = Ratings.trimMemory()
        })
        // A message left by a screen that closed (the player's "Next episode airs …") shows on the next one.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) = MessagePill.showPending(activity)
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        if (BuildConfig.DEBUG) {
            RemoteHold.enabled = getSharedPreferences("xtream", MODE_PRIVATE).getBoolean("dbg_hold_mode", true)
            frameStats()
            holdKeyForTests()
            idCoverageForTests()
            ratingsSampleForTests()
            fieldsProbeForTests()
            // Test builds: open a title's page by its exact name (active playlist), no key route needed.
            //   adb shell am broadcast -a com.osama.xtreamtv.debug.OPEN --es name "I'm Game" --es type MOVIE
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val name = intent.getStringExtra("name") ?: return
                    val type = runCatching { ContentType.valueOf(intent.getStringExtra("type") ?: "MOVIE") }.getOrDefault(ContentType.MOVIE)
                    val api = Session.api(context) ?: return
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { api.streams(type, null) }.getOrElse { emptyList() } }
                        val item = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: all.firstOrNull { it.name.contains(name, ignoreCase = true) } ?: return@launch android.util.Log.d("Recos", "no title named $name").let { }
                        startActivity(DetailsActivity.intent(context, item).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.OPEN"), if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
            // Test builds: what the provider really has for a series' last season. Logs episode numbers,
            // lengths, dates and the server's answer (code, size) for each file; never the address.
            //   adb shell am broadcast -a com.osama.xtreamtv.debug.EPCHECK --es name "Dexter Resurrection"
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val name = intent.getStringExtra("name") ?: return
                    val api = Session.api(context) ?: return
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val tag = "EpCheck"
                        val all = runCatching { api.streams(ContentType.SERIES, null) }.getOrElse { emptyList() }
                        val hits = all.filter { it.name.contains(name, ignoreCase = true) }
                        android.util.Log.d(tag, "series named like \"$name\": ${hits.map { it.name }}")
                        for (s in hits) {
                            val info = runCatching { api.seriesInfo(s.id) }.getOrNull() ?: continue
                            val last = info.seasons.keys.maxOrNull() ?: continue
                            android.util.Log.d(tag, "== ${s.name}: seasons ${info.seasons.keys}, last season $last")
                            for (ep in info.seasons[last].orEmpty()) {
                                val (code, size) = runCatching {
                                    val conn = java.net.URL(api.episodeUrl(ep.id, ep.ext)).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 8000
                                    conn.readTimeout = 8000
                                    conn.instanceFollowRedirects = true
                                    conn.setRequestProperty("Range", "bytes=0-1023")
                                    try { conn.responseCode to conn.getHeaderField("Content-Range")?.substringAfter('/')?.takeIf { it != "*" } } finally { conn.disconnect() }
                                }.getOrElse { -1 to it.javaClass.simpleName }
                                android.util.Log.d(tag, "  S${ep.season} E${ep.number} \"${ep.title}\" · length ${ep.duration ?: "-"} · file answer $code · size ${size ?: "-"} bytes")
                            }
                        }
                        android.util.Log.d(tag, "done")
                    }
                }
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.EPCHECK"), if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
            // Test builds: adb shell am broadcast -a com.osama.xtreamtv.debug.ARRANGE (opens Arrange Home rows).
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    startActivity(android.content.Intent(context, HomeRowsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.ARRANGE"), if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
            // Test builds: adb shell am broadcast -a com.osama.xtreamtv.debug.MDBL --es path /tmdb/show/1396
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    intent.getStringExtra("path")?.let { Ratings.probeForTests(it) }
                }
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.MDBL"), if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
            // Test builds: how names are cleaned for the name search (files/debug_names.txt, one per line).
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val f = java.io.File(filesDir, "debug_names.txt")
                    if (!f.exists()) return
                    for (n in f.readLines().filter { it.isNotBlank() }) {
                        val a = Ratings.cleanName(n)
                        val b = Ratings.cleanFurther(n)
                        android.util.Log.d("NameClean", "$n  =>  1st \"${a.first}\" ${a.second ?: "-"}  |  2nd \"${b.first}\" ${b.second ?: "-"}")
                    }
                }
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.NAMES").also { }, if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
            // Test builds: the diagnostics report (with its summary) to files/debug_report.txt.
            registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) = Diag.reportForTests()
            }, android.content.IntentFilter("com.osama.xtreamtv.debug.REPORT"), if (android.os.Build.VERSION.SDK_INT >= 33) android.content.Context.RECEIVER_EXPORTED else 0)
        }
        // Settings stay exactly as the user left them, whatever a new version's defaults are.
        Prefs(this).pinSettings(BuildConfig.VERSION_CODE)
    }

    /**
     * Debug builds: "hold" a remote button, for the scrolling measurements. The emulator gives tools no
     * way to hold a key, so the app plays what a held button sends: one press, then after 400 ms a
     * repeat every 50 ms, then the release. From the computer:
     *   adb shell am broadcast -a com.osama.xtreamtv.debug.HOLD --ei key 20 --ei ms 10000
     */
    /**
     * Test builds (review 1.1E, step 1): how many movies and series carry a TMDB or IMDb ID in their
     * detail page. Samples a few dozen titles of the active playlist and logs only counts and field
     * names ("IdCoverage"), never titles, ids or addresses. From the computer:
     *   adb shell am broadcast -a com.osama.xtreamtv.debug.IDS --ei sample 60
     */
    private fun idCoverageForTests() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val sample = intent.getIntExtra("sample", 60)
                val api = Session.api(context) ?: return android.util.Log.d("IdCoverage", "no playlist").let { }
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    fun present(v: Any?): Boolean {
                        val s = v?.toString()?.trim() ?: return false
                        return s.isNotEmpty() && s != "0" && s != "null" && s != "tt" && s != "[]"
                    }
                    for (type in listOf(ContentType.MOVIE, ContentType.SERIES)) {
                        val all = runCatching { api.streams(type, null) }.getOrElse { emptyList() }
                        val picked = all.shuffled().take(sample)
                        var withAny = 0
                        var answered = 0
                        val perField = java.util.TreeMap<String, Int>()
                        for (item in picked) {
                            val root = runCatching {
                                if (type == ContentType.MOVIE) api.debugRaw("action" to "get_vod_info", "vod_id" to item.id)
                                else api.debugRaw("action" to "get_series_info", "series_id" to item.id)
                            }.getOrNull() as? org.json.JSONObject ?: continue
                            answered++
                            var any = false
                            for (part in listOf("info", "movie_data")) {
                                val o = root.optJSONObject(part) ?: continue
                                for (k in o.keys()) {
                                    val lk = k.lowercase()
                                    if (("tmdb" in lk || "imdb" in lk) && present(o.opt(k))) {
                                        perField["$part.$k"] = (perField["$part.$k"] ?: 0) + 1
                                        any = true
                                    }
                                }
                            }
                            if (any) withAny++
                        }
                        android.util.Log.d("IdCoverage", "${type.name}: list ${all.size}, sampled ${picked.size}, answered $answered, with an id $withAny (${if (answered > 0) withAny * 100 / answered else 0}%), by field $perField")
                    }
                    android.util.Log.d("IdCoverage", "done")
                }
            }
        }
        val filter = android.content.IntentFilter("com.osama.xtreamtv.debug.IDS")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    /**
     * Test builds (1.1E): looks up a sample of the active playlist's titles with the saved key, so the
     * matching can be read in the "RatingsMatch" log. Uses real lookups (about 1-2 per title).
     *   adb shell am broadcast -a com.osama.xtreamtv.debug.RATINGS --ei sample 20 --es type SERIES
     */
    private fun ratingsSampleForTests() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val sample = intent.getIntExtra("sample", 20)
                val types = intent.getStringExtra("type")?.let { listOf(ContentType.valueOf(it)) } ?: listOf(ContentType.MOVIE, ContentType.SERIES)
                val api = Session.api(context) ?: return android.util.Log.d("RatingsMatch", "no playlist").let { }
                if (!Ratings.hasKey) return android.util.Log.d("RatingsMatch", "no key").let { }
                // --ez retry true: the titles that found nothing before, again (after a matching change).
                val retry = intent.getBooleanExtra("retry", false)
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val before = if (retry) Ratings.notFoundIdsForTests() else emptySet()
                    if (retry) android.util.Log.d("RatingsMatch", "retrying ${before.size}, forgot ${Ratings.forgetNotFoundForTests()}")
                    for (type in types) {
                        val all = runCatching { api.streams(type, null) }.getOrElse { emptyList() }
                        val picked = if (retry) all.filter { "${it.type.name}:${it.id}" in before }
                            else all.filter { Ratings.needsLookup(it.type, it.id) }.shuffled().take(sample)
                        android.util.Log.d("RatingsMatch", "== ${type.name}: ${all.size} in the list (${all.count { it.tmdb != null }} with a TMDB id in the list), looking up ${picked.size}")
                        for (item in picked) runCatching { Ratings.lookupItem(api, item, onScreen = true) }
                    }
                    val m = Ratings.matching()
                    android.util.Log.d("RatingsMatch", "done · totals: ${m.byId} by id · ${m.byName} by name · ${m.notFound} not found · usage ${Ratings.usage()?.let { "${it.used} of ${it.limit}" }}")
                }
            }
        }
        val filter = android.content.IntentFilter("com.osama.xtreamtv.debug.RATINGS")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    /**
     * Test builds (1.1E): what a series carries besides its name, year and poster, to find something
     * that matches it exactly. Logs field names and short values to "FieldsProbe"; links only as
     * "tmdb:" or "provider:" plus the file name, never an address.
     *   adb shell am broadcast -a com.osama.xtreamtv.debug.FIELDS --ei sample 12
     */
    private fun fieldsProbeForTests() {
        fun show(v: Any?): String {
            val s = v?.toString()?.trim() ?: return "null"
            if (s.startsWith("http")) {
                val host = s.substringAfter("://").substringBefore('/')
                val file = s.substringBefore('?').substringAfterLast('/')
                return (if ("tmdb.org" in host) "tmdb:" else if ("youtube" in host || "youtu.be" in host) "youtube:" else "provider:") + file.take(50)
            }
            if (s.startsWith("[") && s.contains("http")) return "[list of ${s.count { it == ',' } + 1} links, first " + show(Regex("https?://[^\"]+").find(s.replace("\\/", "/"))?.value) + "]"
            return s.replace("\n", " ").take(70)
        }
        fun dump(tag: String, o: org.json.JSONObject?) {
            o ?: return
            val parts = o.keys().asSequence().toList().map { k -> "$k=" + (o.optJSONObject(k)?.let { "{${it.keys().asSequence().toList().joinToString(",")}}" } ?: show(o.opt(k))) }
            android.util.Log.d("FieldsProbe", "  $tag: " + parts.joinToString(" | "))
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val sample = intent.getIntExtra("sample", 12)
                val api = Session.api(context) ?: return
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    if (intent.getStringExtra("type") == "MOVIE") {
                        val movies = runCatching { api.debugRaw("action" to "get_vod_streams") }.getOrNull() as? org.json.JSONArray ?: return@launch
                        val all = (0 until movies.length()).mapNotNull { movies.optJSONObject(it) }
                        val counts = java.util.TreeMap<String, Int>()
                        for (o in all) for (k in o.keys()) if (o.optString(k).let { it.isNotBlank() && it != "0" && it != "null" && it != "[]" }) counts[k] = (counts[k] ?: 0) + 1
                        android.util.Log.d("FieldsProbe", "MOVIE list ${all.size}, filled fields: $counts")
                        for (o in all.shuffled().take(sample)) {
                            android.util.Log.d("FieldsProbe", "== ${o.optString("name")}")
                            dump("list", o)
                            val info = runCatching { api.debugRaw("action" to "get_vod_info", "vod_id" to o.optString("stream_id")) }.getOrNull() as? org.json.JSONObject ?: continue
                            dump("info", info.optJSONObject("info"))
                            dump("movie_data", info.optJSONObject("movie_data"))
                        }
                        android.util.Log.d("FieldsProbe", "done")
                        return@launch
                    }
                    val raw = runCatching { api.debugRaw("action" to "get_series") }.getOrNull() as? org.json.JSONArray ?: return@launch
                    val list = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
                    val arabic = list.filter { o -> o.optString("name").any { it in '\u0600'..'\u06FF' } }.shuffled().take(sample / 2)
                    val latin = list.filter { o -> o.optString("name").none { it in '\u0600'..'\u06FF' } }.shuffled().take(sample - sample / 2)
                    for (o in latin + arabic) {
                        android.util.Log.d("FieldsProbe", "== ${o.optString("name")}")
                        dump("list", o)
                        val info = runCatching { api.debugRaw("action" to "get_series_info", "series_id" to o.optString("series_id")) }.getOrNull() as? org.json.JSONObject ?: continue
                        android.util.Log.d("FieldsProbe", "  parts: ${info.keys().asSequence().toList()}")
                        dump("info", info.optJSONObject("info"))
                        // The first episode, and its own info.
                        val eps = info.opt("episodes")
                        val first = when (eps) {
                            is org.json.JSONObject -> eps.keys().asSequence().firstOrNull()?.let { eps.optJSONArray(it)?.optJSONObject(0) }
                            is org.json.JSONArray -> eps.optJSONArray(0)?.optJSONObject(0)
                            else -> null
                        }
                        dump("episode", first)
                        dump("episode.info", first?.optJSONObject("info"))
                        info.optJSONArray("seasons")?.optJSONObject(0)?.let { dump("season", it) }
                    }
                    android.util.Log.d("FieldsProbe", "done")
                }
            }
        }
        val filter = android.content.IntentFilter("com.osama.xtreamtv.debug.FIELDS")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    private fun holdKeyForTests() {
        var resumed: java.lang.ref.WeakReference<android.app.Activity>? = null
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { resumed = java.lang.ref.WeakReference(activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val code = intent.getIntExtra("key", android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                val ms = intent.getIntExtra("ms", 10_000).toLong()
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val down = android.os.SystemClock.uptimeMillis()
                val direction = when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
                    else -> 0
                }
                var repeat = 0
                fun send(action: Int, count: Int) {
                    val activity = resumed?.get() ?: return
                    val now = android.os.SystemClock.uptimeMillis()
                    val event = android.view.KeyEvent(down, now, action, code, count, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD)
                    val used = activity.window.callback?.dispatchKeyEvent(event) == true
                    // A real key goes on to Android's own step when the screen didn't use it: the
                    // arrow moves the highlight. Handing keys to the screen skips that step, so do it
                    // here (without it, rows that leave the moving to Android, like episodes, stayed put).
                    if (!used && action == android.view.KeyEvent.ACTION_DOWN && direction != 0) {
                        activity.currentFocus?.let { it.focusSearch(direction)?.requestFocus(direction) }
                    }
                }
                send(android.view.KeyEvent.ACTION_DOWN, 0)
                val step = object : Runnable {
                    override fun run() {
                        if (android.os.SystemClock.uptimeMillis() - down >= ms) {
                            send(android.view.KeyEvent.ACTION_UP, 0)
                            return
                        }
                        send(android.view.KeyEvent.ACTION_DOWN, ++repeat)
                        handler.postDelayed(this, 50)
                    }
                }
                handler.postDelayed(step, 400)
            }
        }
        val filter = android.content.IntentFilter("com.osama.xtreamtv.debug.HOLD")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    /**
     * Debug builds: once a second, how many frames were drawn and how many were late (over 33 ms, so
     * a visible stutter on a 60 Hz TV), for the scrolling measurements (scroll_perf.py).
     */
    private fun frameStats() {
        val choreographer = android.view.Choreographer.getInstance()
        var last = 0L
        var frames = 0
        var late = 0
        var worst = 0L
        var windowStart = 0L
        val gaps = ArrayList<Long>()
        choreographer.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (last != 0L) {
                    val gap = (now - last) / 1_000_000
                    // Over 1 s means nothing was changing on screen: not a frame of scrolling.
                    if (gap < 1000) {
                        frames++
                        gaps += gap
                        if (gap > 33) late++
                        if (gap > worst) worst = gap
                    }
                }
                last = now
                if (windowStart == 0L) windowStart = now
                if (now - windowStart >= 1_000_000_000L) {
                    if (frames > 0) android.util.Log.d("FrameStat", "FrameStat frames=$frames late=$late worst=$worst gaps=${gaps.joinToString(",")}")
                    frames = 0
                    late = 0
                    worst = 0
                    gaps.clear()
                    windowStart = now
                }
                choreographer.postFrameCallback(this)
            }
        })
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Posters without transparency decode at half the memory (no visible difference on a TV):
        // faster to decode and more of them fit in memory while scrolling. Logos keep transparency.
        .allowRgb565(true)
        .eventListener(object : coil.EventListener {
            // Debug builds: how long each picture takes, for the scrolling measurements (scroll_perf.py).
            private val started = java.util.Collections.synchronizedMap(java.util.WeakHashMap<coil.request.ImageRequest, Long>())
            override fun onStart(request: coil.request.ImageRequest) {
                if (BuildConfig.DEBUG) started[request] = android.os.SystemClock.elapsedRealtime()
            }
            override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                if (!BuildConfig.DEBUG) return
                val t = started.remove(request) ?: return
                android.util.Log.d("ImageTime", "ImageTime ${android.os.SystemClock.elapsedRealtime() - t} ms ${result.dataSource}")
            }
            override fun onError(request: coil.request.ImageRequest, result: coil.request.ErrorResult) = Diag.posterFailed()
        })
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(posterStorageBytes())
                .build()
        }
        .build()

    /**
     * Posters kept on the device between sessions: up to 250 MB, but never more than a tenth of
     * the free space (TVs often have little storage) and never less than 60 MB.
     */
    private fun posterStorageBytes(): Long {
        val mb = 1024L * 1024
        val free = runCatching { cacheDir.usableSpace }.getOrDefault(0L)
        return (free / 10).coerceIn(60 * mb, 250 * mb)
    }
}
