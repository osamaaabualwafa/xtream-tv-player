package com.osama.xtreamtv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while the ratings fill works under the TV's screensaver (approved 2026-11-12).
 * It does no work itself: [RatingsFill] starts it when the screensaver takes the screen from the app
 * and stops it when the screensaver ends, the screen goes off, the app returns or nothing is left.
 * Android asks for a notice while it runs; it says what the app is doing and nothing else.
 */
class FillService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notice = notice()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTICE_ID, notice)
        } catch (e: Exception) {
            // Not allowed on this TV: the fill simply waits for the app to be opened, as before.
            Diag.log("ratings", "fill: the TV refused the background notice (${e.javaClass.simpleName})")
            RatingsFill.serviceRefused()
            stopSelf()
        }
        // Not started again by Android after it closes the app: the fill starts with the app.
        return START_NOT_STICKY
    }

    /** Android 15: this kind of work may run 6 hours a day. Stop cleanly; the fill goes on when the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        RatingsFill.serviceTimedOut()
        stopSelf()
    }

    private fun notice(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ratings fill", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ratings are filled under the screensaver"
                    setShowBadge(false)
                })
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glass_star)
            .setContentTitle("Xtream TV is filling ratings")
            .setContentText("It stops when the screen goes off or the app is opened")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "ratings_fill"
        private const val NOTICE_ID = 4101

        /** True when Android accepted the start. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, FillService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FillService::class.java)) }
        }
    }
}
