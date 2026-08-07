package com.manuel.ours.data.sync

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.manuel.ours.OursApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The opt-in "keep syncing when nearby" service.
 *
 * Android will not let a Bluetooth listener idle in the background, so continuous
 * nearby sync genuinely requires a foreground service and its permanent notification.
 * That costs battery, which is why this is **off by default** and the settings toggle
 * says so plainly rather than burying it.
 */
@AndroidEntryPoint
class NearbySyncService : Service() {

    @Inject lateinit var engine: SyncEngine
    @Inject lateinit var nearbyTransport: NearbyTransport

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            while (isActive) {
                if (nearbyTransport.isAvailable()) {
                    engine.sync(nearbyTransport)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        nearbyTransport.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, OursApp.CHANNEL_SYNC)
            .setSmallIcon(com.manuel.ours.R.drawable.ic_notification)
            .setContentTitle("Nearby sync on")
            .setContentText("Syncing with your household over Bluetooth")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 4242
        private const val SCAN_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearbySyncService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NearbySyncService::class.java))
        }
    }
}
