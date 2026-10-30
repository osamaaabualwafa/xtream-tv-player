package com.osama.xtreamtv

import android.app.Activity

/**
 * The mini player is only for the TV's own screens (the remote's Home button). When the app
 * comes back while a video is in the corner, the video comes back full screen; Back then closes
 * it and shows the app.
 */
object MiniPlayerBar {

    /** Call in onResume of the app's screens. */
    fun attach(activity: Activity) {
        if (PlayerActivity.miniTitle() != null) PlayerActivity.expandMini(activity)
    }

    /** Call in onPause (nothing to undo at the moment). */
    @Suppress("UNUSED_PARAMETER")
    fun detach(activity: Activity) {}
}
