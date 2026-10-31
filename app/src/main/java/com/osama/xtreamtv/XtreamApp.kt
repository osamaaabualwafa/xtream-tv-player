package com.osama.xtreamtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/** App-wide settings: settings protection on update, and how posters are loaded and kept. */
class XtreamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Settings stay exactly as the user left them, whatever a new version's defaults are.
        Prefs(this).pinSettings(BuildConfig.VERSION_CODE)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Posters without transparency decode at half the memory (no visible difference on a TV):
        // faster to decode and more of them fit in memory while scrolling. Logos keep transparency.
        .allowRgb565(true)
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
