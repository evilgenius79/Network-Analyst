package com.networkanalyst.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.networkanalyst.app.MainActivity
import com.networkanalyst.app.NetworkAnalystApp
import com.networkanalyst.app.R

/**
 * Keeps the app's process active so the connection watch goes on recording while the app is
 * not on screen. Android holds back Wi-Fi state broadcasts from apps in the background; a
 * foreground service, with its visible notification, is the sanctioned way to stay active.
 *
 * The service does no work itself: the recording is done by [WifiEventMonitor].
 */
class WatchService : Service() {
    private val graph get() = (application as NetworkAnalystApp).graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: Exception) {
            // Android refuses foreground services in some states (for example when started
            // from the background). Report it instead of crashing.
            graph.watchError.value = "Android would not start background watching: ${e.message ?: e.javaClass.simpleName}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (!graph.watchingInBackground.value) {
            graph.watchingInBackground.value = true
            graph.watchError.value = null
            graph.monitor.note("Background watching started")
        }
        // Not restarted automatically if the system kills it: a restart from the background
        // could be refused, and the user can simply switch it on again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (graph.watchingInBackground.value) {
            graph.watchingInBackground.value = false
            graph.monitor.note("Background watching stopped")
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connection watch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Network Analyst is recording Wi-Fi connection attempts in the background."
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Watching Wi-Fi connections")
            .setContentText("Recording connection attempts and drops.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        internal const val CHANNEL_ID = "connection_watch"
        private const val NOTIFICATION_ID = 1001
        internal const val ACTION_STOP = "com.networkanalyst.app.action.STOP_WATCH"

        /** Must be called while the app is on screen; Android refuses otherwise. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchService::class.java))
        }
    }
}
