package io.github.realkhfan.foco

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps Foco running while photos are still being processed, so swiping the app away right after the shutter
 * can't lose them (Pixel Camera does the same). A "short service": Android allows it up to 3 minutes.
 * All calls happen on the main thread.
 */
class SavingService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Saving photos", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Saving photo…")
            .setOngoing(true)
            .build()
        // Android requires startForeground right away, even if the photo is already saved.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(ID, notification)
        }
        running = this
        if (!wanted) finish()
        return START_NOT_STICKY
    }

    /** The 3 minutes are up: stop (the photo itself keeps processing as long as the app lives). */
    override fun onTimeout(startId: Int) = finish()

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    private fun finish() {
        if (running === this) running = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "saving"
        private const val ID = 1
        private var wanted = false
        private var running: SavingService? = null

        /** Photos are being processed. Must be called while Foco is on screen (a shutter press). */
        fun start(context: Context) {
            wanted = true
            if (running == null) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, SavingService::class.java)) }
            }
        }

        /** Everything is saved. Stopping goes through the service, never stopService(): that could crash
         * if Android hasn't run onStartCommand yet. */
        fun done() {
            wanted = false
            running?.finish()
        }
    }
}
