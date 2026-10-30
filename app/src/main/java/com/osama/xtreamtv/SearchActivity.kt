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
        if (granted) startListening() else Toast.makeText(this, "Allow the microphone to search by voice", Toast.LENGTH_LONG).show()
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
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> startListening()
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
                message?.let { Toast.makeText(this@SearchActivity, it, Toast.LENGTH_SHORT).show() }
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
            Toast.makeText(this, "Voice search isn't available on this TV", Toast.LENGTH_SHORT).show()
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

    override fun onPause() {
        super.onPause()
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
        status = findViewById(R.id.search_status)
        progress = findViewById(R.id.search_progress)
        results = findViewById(R.id.search_results)

        adapter = ItemAdapter(grid = false, onClick = { _, item -> open(item) }, onLongClick = null)
        sections = findViewById(R.id.search_sections)
        chips = findViewById(R.id.search_chips)
        // "All": a row per section (channel cards, posters), like Home.
        val rows = mapOf(
            ContentType.LIVE to (R.id.sec_live to R.layout.item_channel_card),
            ContentType.MOVIE to (R.id.sec_movies to R.layout.item_poster_row),
            ContentType.SERIES to (R.id.sec_series to R.layout.item_poster_row),
        )
        for ((type, ids) in rows) {
            val rowAdapter = ItemAdapter(grid = type != ContentType.LIVE, onClick = { _, item -> open(item) }, onLongClick = null, layout = ids.second)
            findViewById<RecyclerView>(ids.first).apply {
                layoutManager = RowLayoutManager(this@SearchActivity)
                this.adapter = rowAdapter
            }
            rowAdapters[type] = rowAdapter
        }
        // One section: channels as a list, movies / series as a poster grid.
        channelList = ItemAdapter(grid = false, onClick = { _, item -> open(item) }, onLongClick = null)
        posterGrid = ItemAdapter(grid = true, onClick = { _, item -> open(item) }, onLongClick = null)
        listOf(R.id.chip_all to null, R.id.chip_live to ContentType.LIVE, R.id.chip_movies to ContentType.MOVIE, R.id.chip_series to ContentType.SERIES)
            .forEach { (id, type) -> findViewById<View>(id).setOnClickListener { showSection(type) } }

        query.doAfterTextChanged { scheduleSearch() }
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
                val parts = ContentType.values().map { type ->
                    async {
                        val key = "${type.name}:__all__"
                        Session.streamCache[key] ?: api.streams(type, null).also { Session.streamCache[key] = it }
                    }
                }.map { it.await() }
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
                all.filter { it.name.contains(text, ignoreCase = true) }
                    .sortedBy { !it.name.startsWith(text, ignoreCase = true) }
                    .groupBy { it.type }
            }
            val total = found.values.sumOf { it.size }
            status.text = if (total == 0) "No results for \"$text\"." else "$total results for \"$text\"."
            // The chosen section has no results this time: back to All.
            if (section != null && found[section].isNullOrEmpty()) section = null
            showResults()
        }
    }

    /** Chips with counts, then either the section rows (All) or one section's full list. */
    private fun showResults() {
        val total = found.values.sumOf { it.size }
        chips.visibility = if (total == 0) View.GONE else View.VISIBLE
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
                rowAdapters.getValue(type).submit(items.take(ROW_RESULTS))
            }
        } else {
            sections.visibility = View.GONE
            results.visibility = View.VISIBLE
            val items = found[one].orEmpty().take(MAX_RESULTS)
            if (one == ContentType.LIVE) {
                if (results.adapter !== channelList) {
                    results.layoutManager = LinearLayoutManager(this)
                    results.adapter = channelList
                }
                channelList.submit(items)
            } else {
                if (results.adapter !== posterGrid) {
                    results.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 8)
                    results.adapter = posterGrid
                }
                posterGrid.submit(items)
            }
            results.scrollToPosition(0)
        }
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
