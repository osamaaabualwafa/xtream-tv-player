package com.osama.xtreamtv

import android.app.Activity

/**
 * The mini player is only for the TV's own screens (the remote's Home button). When the app
 * comes back while a video is in the corner, the video comes back full screen; Back then closes
 * it and shows the app.
 */
object MiniPlayerBar {

    /** One of the app's own screens is on screen (between its onResume and onPause). */
    var appScreen: java.lang.ref.WeakReference<Activity>? = null
        private set

    /** Call in onResume of the app's screens. */
    fun attach(activity: Activity) {
        appScreen = java.lang.ref.WeakReference(activity)
        if (PlayerActivity.miniTitle() != null) PlayerActivity.expandMini(activity)
    }

    /** Call in onPause. */
    fun detach(activity: Activity) {
        if (appScreen?.get() === activity) appScreen = null
    }
}
