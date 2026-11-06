package com.osama.xtreamtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/** App-wide settings: settings protection on update, and how posters are loaded and kept. */
class XtreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        if (BuildConfig.DEBUG) {
            RemoteHold.enabled = getSharedPreferences("xtream", MODE_PRIVATE).getBoolean("dbg_hold_mode", true)
            frameStats()
            holdKeyForTests()
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
