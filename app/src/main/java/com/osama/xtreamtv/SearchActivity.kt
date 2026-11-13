package com.osama.xtreamtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import coil.load
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One search box across live channels, movies and series. */
class SearchActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Look.wrap(newBase))

    companion object {
        /** Per section; "All" rows show fewer. */
        private const val MAX_RESULTS = 300
        private const val ROW_RESULTS = 40
    }

    private lateinit var api: XtreamApi
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: RecyclerView
    private lateinit var adapter: ItemAdapter

    private var library: List<StreamItem>? = null

    /** Results per section, and the section shown (null = All). */
    private var found: Map<ContentType, List<StreamItem>> = emptyMap()
    private var section: ContentType? = null

    // ---- People (package 2, item 6, approved 2026-11-12): after Movies and Series, always ----
    private var people: List<RatingsDb.FoundPerson> = emptyList()
    /** The People tab is chosen (instead of a section of titles). */
    private var peopleTab = false
    private var peopleChip: TextView? = null
    private var peopleTitle: TextView? = null
    private var peopleBox: FlowRow? = null
    private val prefs by lazy { Prefs(this) }
    private lateinit var sections: View
    private lateinit var chips: View
    private val rowAdapters = HashMap<ContentType, ItemAdapter>()
    private lateinit var channelList: ItemAdapter
    private lateinit var posterGrid: ItemAdapter

    // ---- Voice search: listened to inside the app (no pop-up); words appear in the box as you speak.
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listening = false
    private lateinit var bars: List<View>

    private val askMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else showToast(this, "Allow the microphone to search by voice", Toast.LENGTH_LONG)
    }

    /** Fallback for TVs without an in-app recogniser: the voice service's own window. */
    private val speechWindow = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        showListening(false)
        val words = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !words.isNullOrBlank()) useWords(words, final = true)
    }

    private val stopOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopListening()
    }

    /**
     * Speech in Arabic or English, detected by the TV's voice service: the TV's language first,
     * the other one as an extra language, and automatic detection where Android offers it (14+).
     */
    private fun speechIntent(): Intent {
        val arabicFirst = java.util.Locale.getDefault().language == "ar"
        val primary = if (arabicFirst) "ar" else "en-US"
        val other = if (arabicFirst) "en-US" else "ar"
        return Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, primary)
            .putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(other))
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a channel, movie or series name · قل اسم القناة أو الفيلم")
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    putExtra(android.speech.RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("en-US", "ar"))
                }
            }
    }

    private fun onMicPressed() {
        when {
            listening -> stopListening()
            !android.speech.SpeechRecognizer.isRecognitionAvailable(this) -> openSpeechWindow()
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> startListening()
            else -> askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val r = recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = showListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = showLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { useWords(it, final = false) }
            }
            override fun onResults(results: Bundle?) {
                showListening(false)
                results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { useWords(it, final = true) }
            }
            override fun onError(error: Int) {
                showListening(false)
                val message = when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic and try again."
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs the internet."
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to search by voice."
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> null // stopped by the user
                    else -> "Voice search isn't working right now."
                }
                message?.let { showToast(this@SearchActivity, it, Toast.LENGTH_SHORT) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showListening(true)
        r.startListening(speechIntent())
    }

    private fun stopListening() {
        recognizer?.cancel()
        showListening(false)
    }

    private fun openSpeechWindow() {
        try {
            showListening(true)
            // Several voice services: use Google's (the TV's own) instead of asking every time.
            val intent = speechIntent()
            val services = packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
            val preferred = listOf("com.google.android.katniss", "com.google.android.googlequicksearchbox", "com.google.android.tts")
            (preferred.firstOrNull { it in services } ?: services.singleOrNull())?.let { intent.setPackage(it) }
            speechWindow.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            showListening(false)
            showToast(this, "Voice search isn't available on this TV", Toast.LENGTH_SHORT)
        }
    }

    /** Words heard so far go into the box (the search runs on its own); at the end, move to the results. */
    private fun useWords(words: String, final: Boolean) {
        query.setText(words)
        query.setSelection(words.length)
        if (final) results.postDelayed({ focusFirstResult() }, 500)
    }

    /** Listening: red mic, sound bars, "Listening…" in the box, and Back stops instead of leaving. */
    private fun showListening(on: Boolean) {
        listening = on
        stopOnBack.isEnabled = on
        val mic = findViewById<View>(R.id.voice_mic)
        mic.isActivated = on
        // The recogniser's mic indicator can take the highlight away: keep it on the mic,
        // so the next OK doesn't open the keyboard.
        mic.post { if (currentFocus == null || currentFocus === window.decorView) mic.requestFocus() }
        findViewById<View>(R.id.voice_bars).visibility = if (on) View.VISIBLE else View.GONE
        query.hint = if (on) "Listening… speak in Arabic or English" else "Channel, movie or series name"
        if (on) showLevel(0f)
    }

    /** Sound bars follow the voice level (about -2 .. 10 dB). */
    private fun showLevel(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        val shape = floatArrayOf(0.5f, 0.85f, 1f, 0.7f, 0.45f)
        val max = 18 * resources.displayMetrics.density
        val min = 4 * resources.displayMetrics.density
        bars.forEachIndexed { i, bar ->
            val h = (min + (max - min) * level * shape[i]).toInt()
            if (bar.layoutParams.height != h) bar.layoutParams = bar.layoutParams.apply { height = h }
        }
    }

    override fun onResume() {
        super.onResume()
        MiniPlayerBar.attach(this)
    }

    override fun onPause() {
        super.onPause()
        MiniPlayerBar.detach(this)
        if (listening) stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Session.api(this) ?: return goToLogin()
        Appearance.apply(this)
        setContentView(R.layout.activity_search)
        query = findViewById(R.id.search_query)
        // Package 5, item 3: the field stays above the TV's keyboard.
        query.post { KeyboardGuard.attach(window, (findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0), listOf(query)) }
        status = findViewById(R.id.search_status)
        progress = findViewById(R.id.search_progress)
        results = findViewById(R.id.search_results)

        adapter = ItemAdapter(grid = false, onClick = { _, item -> open(item) }, onLongClick = ::toggleFavorite)
        sections = findViewById(R.id.search_sections)
        chips = findViewById(R.id.search_chips)
        // "All": a row per section (channel cards, posters), like Home.
        val rows = mapOf(
            ContentType.LIVE to (R.id.sec_live to R.layout.item_channel_card),
            ContentType.MOVIE to (R.id.sec_movies to R.layout.item_poster_row),
            ContentType.SERIES to (R.id.sec_series to R.layout.item_poster_row),
        )
        for ((type, ids) in rows) {
            val rowAdapter = ItemAdapter(grid = type != ContentType.LIVE, onClick = { _, item -> open(item) }, onLongClick = ::toggleFavorite, layout = ids.second)
            findViewById<RecyclerView>(ids.first).apply {
                layoutManager = RowLayoutManager(this@SearchActivity)
                this.adapter = rowAdapter
            }
            rowAdapters[type] = rowAdapter
        }
        // One section: channels as a list, movies / series as a poster grid.
        channelList = ItemAdapter(grid = false, onClick = { _, item -> open(item) }, onLongClick = ::toggleFavorite)
        posterGrid = ItemAdapter(grid = true, onClick = { _, item -> open(item) }, onLongClick = ::toggleFavorite)
        listOf(R.id.chip_all to null, R.id.chip_live to ContentType.LIVE, R.id.chip_movies to ContentType.MOVIE, R.id.chip_series to ContentType.SERIES)
            .forEach { (id, type) -> findViewById<View>(id).setOnClickListener { peopleTab = false; showSection(type) } }
        setupPeople()

        query.doAfterTextChanged { scheduleSearch() }
        // Debug builds: a word left in files/debug_search.txt is searched for once (the test tool
        // that types on the emulator cannot type Arabic).
        if (BuildConfig.DEBUG) java.io.File(filesDir, "debug_search.txt").takeIf { it.exists() }?.let { f ->
            query.setText(f.readText().trim())
            f.delete()
        }
        query.setOnEditorActionListener { v, _, _ ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            results.post { focusFirstResult() }
            true
        }
        val bar = findViewById<android.view.ViewGroup>(R.id.voice_bars)
        bars = (0 until bar.childCount).map { bar.getChildAt(it) }
        onBackPressedDispatcher.addCallback(this, stopOnBack)
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(this) || packageManager.queryIntentActivities(speechIntent(), 0).isNotEmpty()) {
            findViewById<View>(R.id.voice_mic).apply {
                visibility = View.VISIBLE
                setOnClickListener { onMicPressed() }
            }
        }
        query.requestFocus()
        loadLibrary()
    }

    /** All channels, movies and series (from the device cache when possible). */
    private fun loadLibrary() {
        progress.visibility = View.VISIBLE
        status.text = "Loading your channels, movies and series…"
        lifecycleScope.launch {
            try {
                // coroutineScope: one list failing lands in the catch below, instead of closing the app.
                val parts = kotlinx.coroutines.coroutineScope {
                    ContentType.values().map { type ->
                        async { Session.all(api, type) }
                    }.map { it.await() }
                }
                library = parts.flatten()
                progress.visibility = View.GONE
                status.text = "Type at least 2 letters to search ${parts[0].size} channels, ${parts[1].size} movies and ${parts[2].size} series."
                scheduleSearch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "Couldn't load the library. ${e.message ?: ""}"
            }
        }
    }

    private fun scheduleSearch() {
        val all = library ?: return
        val text = query.text.toString().trim()
        searchJob?.cancel()
        if (text.length < 2) {
            found = emptyMap()
            showResults()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(300)
            found = withContext(Dispatchers.Default) {
                // Names that start with the text first.
                val byType = all.filter { it.name.contains(text, ignoreCase = true) }
                    .sortedBy { !it.name.startsWith(text, ignoreCase = true) }
                    .groupBy { it.type }
                // Copies of one movie or series are one card, as in Movies and Series (owner, 2026-11-13:
                // search showed The Odyssey twice).
                val facts = runCatching { TitleFilter.facts(this@SearchActivity) }.getOrNull()
                val learned = Versions.learned(this@SearchActivity)
                if (facts == null) byType else byType.mapValues { (type, list) ->
                    if (type == ContentType.LIVE) return@mapValues list
                    fun ref(s: StreamItem) = facts["${s.type.name}:${s.id}"]?.ref
                    fun name(s: StreamItem) = runCatching { Versions.shownName(s) }.getOrDefault(s.name).lowercase()
                    // A copy not matched yet joins a matched copy with exactly the same name (only when
                    // that name belongs to one title).
                    val byName = list.mapNotNull { s -> ref(s)?.let { name(s) to it } }.groupBy({ it.first }, { it.second })
                        .mapValues { (_, refs) -> refs.distinct().singleOrNull() }
                    Versions.group(list, { s -> ref(s) ?: byName[name(s)] }, learned)
                }
            }
            // Actors and directors whose name has a word starting with the text (from the notebook, on the TV).
            people = withContext(Dispatchers.IO) {
                val pl = prefs.activeId
                if (pl == null) emptyList() else runCatching { Ratings.db.searchPeople(pl, text, 200) }.getOrDefault(emptyList())
            }
            val total = found.values.sumOf { it.size }
            val who = if (people.isEmpty()) "" else " and ${people.size} ${if (people.size == 1) "person" else "people"}"
            status.text = if (total == 0 && people.isEmpty()) "No results for \"$text\"." else "$total ${if (total == 1) "title" else "titles"}$who for \"$text\"."
            Diag.log("search", "${text.length} letters · $total results · ${people.size} people${Diag.title(text)}")
            // The chosen section has no results this time: back to All.
            if (section != null && found[section].isNullOrEmpty()) section = null
            if (peopleTab && people.isEmpty()) peopleTab = false
            showResults()
        }
    }

    /** Chips with counts, then either the section rows (All) or one section's full list. */
    /**
     * Long-press on a result: add or remove a favorite, with the star, as in Movies, Series and Live TV
     * (16.1: search results had no long-press and no star).
     */
    private fun toggleFavorite(@Suppress("UNUSED_PARAMETER") position: Int, item: StreamItem) {
        val added = prefs.toggleFavorite(item)
        showToast(this, if (added) "Added ${item.name} to favorites" else "Removed ${item.name} from favorites", Toast.LENGTH_SHORT)
        val ids = prefs.favoriteIds(item.type)
        val shown = listOfNotNull(rowAdapters[item.type], if (item.type == ContentType.LIVE) channelList else posterGrid.takeIf { section == item.type })
        for (a in shown) {
            a.favorites = ids
            a.notifyItemRangeChanged(0, a.itemCount, "fav")
        }
    }

    private fun showResults() {
        val total = found.values.sumOf { it.size }
        chips.visibility = if (total == 0 && people.isEmpty()) View.GONE else View.VISIBLE
        showPeople(total)
        if (peopleTab) return
        val count = { type: ContentType -> found[type]?.size ?: 0 }
        val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
        findViewById<TextView>(R.id.chip_all).text = "All  ·  ${nf.format(total)}"
        findViewById<TextView>(R.id.chip_live).text = "Live TV  ·  ${nf.format(count(ContentType.LIVE))}"
        findViewById<TextView>(R.id.chip_movies).text = "Movies  ·  ${nf.format(count(ContentType.MOVIE))}"
        findViewById<TextView>(R.id.chip_series).text = "Series  ·  ${nf.format(count(ContentType.SERIES))}"
        mapOf(R.id.chip_live to ContentType.LIVE, R.id.chip_movies to ContentType.MOVIE, R.id.chip_series to ContentType.SERIES)
            .forEach { (id, type) -> findViewById<View>(id).visibility = if (count(type) == 0) View.GONE else View.VISIBLE }
        findViewById<View>(R.id.chip_all).isActivated = section == null
        findViewById<View>(R.id.chip_live).isActivated = section == ContentType.LIVE
        findViewById<View>(R.id.chip_movies).isActivated = section == ContentType.MOVIE
        findViewById<View>(R.id.chip_series).isActivated = section == ContentType.SERIES

        val one = section
        if (total == 0) {
            sections.visibility = View.GONE
            results.visibility = View.GONE
        } else if (one == null) {
            results.visibility = View.GONE
            sections.visibility = View.VISIBLE
            sections.scrollTo(0, 0)
            val titles = mapOf(ContentType.LIVE to R.id.sec_live_title, ContentType.MOVIE to R.id.sec_movies_title, ContentType.SERIES to R.id.sec_series_title)
            val lists = mapOf(ContentType.LIVE to R.id.sec_live, ContentType.MOVIE to R.id.sec_movies, ContentType.SERIES to R.id.sec_series)
            for (type in ContentType.values()) {
                val items = found[type].orEmpty()
                val visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                findViewById<TextView>(titles.getValue(type)).apply {
                    this.visibility = visibility
                    text = "${typeName(type)}  ·  ${nf.format(items.size)}"
                }
                findViewById<RecyclerView>(lists.getValue(type)).apply {
                    this.visibility = visibility
                    scrollToPosition(0)
                }
                rowAdapters.getValue(type).favorites = prefs.favoriteIds(type)
                rowAdapters.getValue(type).submit(items.take(ROW_RESULTS))
            }
        } else {
            sections.visibility = View.GONE
            results.visibility = View.VISIBLE
            val items = found[one].orEmpty().take(MAX_RESULTS)
            if (one == ContentType.LIVE) {
                if (results.adapter !== channelList) {
                    results.layoutManager = RowLayoutManager(this, LinearLayoutManager.VERTICAL)
                    results.adapter = channelList
                }
                channelList.favorites = prefs.favoriteIds(ContentType.LIVE)
                channelList.submit(items)
            } else {
                if (results.adapter !== posterGrid) {
                    results.layoutManager = CenterGridLayoutManager(this, 8)
                    results.adapter = posterGrid
                }
                posterGrid.favorites = prefs.favoriteIds(one)
                posterGrid.submit(items)
            }
            results.scrollToPosition(0)
        }
    }

    /** The People chip after Series, and the People block after the Series row. */
    private fun setupPeople() {
        val d = resources.displayMetrics.density
        val series = findViewById<TextView>(R.id.chip_series)
        peopleChip = TextView(this, null, 0, R.style.SearchChip).apply {
            text = "People"
            visibility = View.GONE
            setOnClickListener {
                peopleTab = true
                section = null
                showResults()
                peopleBox?.post { (0 until (peopleBox?.childCount ?: 0)).map { peopleBox!!.getChildAt(it) }.firstOrNull()?.requestFocus() }
            }
        }
        (series.parent as android.view.ViewGroup).addView(peopleChip, (series.parent as android.view.ViewGroup).indexOfChild(series) + 1, series.layoutParams)
        val row = findViewById<View>(R.id.sec_series)
        val holder = row.parent as android.view.ViewGroup
        val title = findViewById<TextView>(R.id.sec_series_title)
        peopleTitle = TextView(this, null, 0, R.style.HomeRowTitle).apply { visibility = View.GONE }
        holder.addView(peopleTitle, holder.indexOfChild(row) + 1, android.widget.LinearLayout.LayoutParams(title.layoutParams as android.view.ViewGroup.MarginLayoutParams))
        peopleBox = FlowRow(this).apply {
            visibility = View.GONE
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
        }
        holder.addView(peopleBox, holder.indexOfChild(peopleTitle) + 1, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** People: up to 10 after Movies and Series in All; every one of them on the People tab. */
    private fun showPeople(totalTitles: Int) {
        val nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US)
        val chip = peopleChip ?: return
        chip.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
        // Left and Right go along the chips shown, in order (Right from a chip jumped up to the microphone).
        // Once every chip's visibility is settled for these results.
        if (chip.id == View.NO_ID) chip.id = View.generateViewId()
        chips.post {
            val row = listOf(R.id.chip_all, R.id.chip_live, R.id.chip_movies, R.id.chip_series).map { findViewById<View>(it) }
                .plus(chip).filter { it.visibility == View.VISIBLE }
            row.forEachIndexed { i, v ->
                v.nextFocusRightId = row.getOrNull(i + 1)?.id ?: v.id
                v.nextFocusLeftId = row.getOrNull(i - 1)?.id ?: v.id
            }
        }
        chip.text = "People  ·  ${nf.format(people.size)}"
        chip.isActivated = peopleTab
        if (peopleTab) {
            listOf(R.id.chip_all, R.id.chip_live, R.id.chip_movies, R.id.chip_series).forEach { findViewById<View>(it).isActivated = false }
            results.visibility = View.GONE
            sections.visibility = View.VISIBLE
            for (id in listOf(R.id.sec_live_title, R.id.sec_live, R.id.sec_movies_title, R.id.sec_movies, R.id.sec_series_title, R.id.sec_series)) findViewById<View>(id).visibility = View.GONE
        }
        val shownPeople = if (peopleTab) people else people.take(10)
        val show = people.isNotEmpty() && (peopleTab || section == null)
        peopleTitle?.apply { visibility = if (show) View.VISIBLE else View.GONE; text = "People  ·  ${nf.format(people.size)}" }
        val box = peopleBox ?: return
        box.visibility = if (show) View.VISIBLE else View.GONE
        box.removeAllViews()
        if (!show) return
        val d = resources.displayMetrics.density
        for (p in shownPeople) {
            val role = when {
                p.acts && p.directs -> "Actor and director"
                p.directs -> "Director"
                else -> "Actor"
            }
            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_glass_choice)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
                isFocusable = true
                isClickable = true
                setOnClickListener { startActivity(PersonActivity.intent(this@SearchActivity, p.name)) }
            }
            val size = (44 * d).toInt()
            val face = android.widget.FrameLayout(this)
            face.addView(TextView(this).apply {
                text = p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                setTextColor(themeColor(R.attr.appText))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x33FFFFFF) }
            }, android.widget.FrameLayout.LayoutParams(size, size))
            Tmdb.photoUrl(p.photo)?.let { url ->
                val img = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                face.addView(img, android.widget.FrameLayout.LayoutParams(size, size))
                img.load(url) { transformations(coil.transform.CircleCropTransformation()); crossfade(false); listener(onError = { _, _ -> img.visibility = View.GONE }) }
            }
            card.addView(face, android.widget.LinearLayout.LayoutParams(size, size))
            val words = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((12 * d).toInt(), 0, 0, 0) }
            words.addView(TextView(this).apply {
                text = p.name
                textSize = 17f
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SearchActivity, R.color.on_focus_text))
                isDuplicateParentStateEnabled = true
            })
            words.addView(TextView(this).apply {
                text = "$role · ${p.titles} ${if (p.titles == 1) "title" else "titles"} in your playlist"
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SearchActivity, R.color.on_focus_text))
                isDuplicateParentStateEnabled = true
            })
            card.addView(words)
            box.addView(card, android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (12 * d).toInt(); bottomMargin = (12 * d).toInt()
            })
        }
        if (totalTitles == 0 && !peopleTab) sections.visibility = View.VISIBLE
    }

    private fun showSection(type: ContentType?) {
        section = type
        showResults()
        results.post { focusFirstResult() }
    }

    /** The first result: in All, the first item of the first section shown. */
    private fun focusFirstResult() {
        if (section == null) {
            for (id in listOf(R.id.sec_live, R.id.sec_movies, R.id.sec_series)) {
                val row = findViewById<RecyclerView>(id)
                if (row.visibility == View.VISIBLE) {
                    (row.layoutManager?.findViewByPosition(0) ?: row).requestFocus()
                    return
                }
            }
        } else {
            (results.layoutManager?.findViewByPosition(0) ?: results).requestFocus()
        }
    }

    private fun typeName(type: ContentType) = when (type) {
        ContentType.LIVE -> "Live TV"
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
    }

    private fun typeLabel(type: ContentType) = when (type) {
        ContentType.LIVE -> "TV"
        ContentType.MOVIE -> "Movie"
        ContentType.SERIES -> "Series"
    }

    private fun open(item: StreamItem) {
        val clean = item.copy(note = null)
        when (item.type) {
            ContentType.LIVE -> {
                // Up/Down in the player zaps through the matching channels.
                val channels = found[ContentType.LIVE].orEmpty().take(MAX_RESULTS).map { it.copy(note = null) }
                Session.zapList = channels
                Session.zapTitle = "Search: ${query.text.toString().trim()}"
                startActivity(PlayerActivity.liveIntent(this, channels.indexOfFirst { it.id == item.id }.coerceAtLeast(0)))
            }
            ContentType.MOVIE, ContentType.SERIES -> startActivity(DetailsActivity.intent(this, clean))
        }
    }
}
