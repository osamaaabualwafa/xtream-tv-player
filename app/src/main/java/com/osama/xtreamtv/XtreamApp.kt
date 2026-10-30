package com.osama.xtreamtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/** App-wide settings: a small poster cache (TVs have little storage; Coil would take ~2% of it). */
class XtreamApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(60L * 1024 * 1024)
                .build()
        }
        .build()
}
