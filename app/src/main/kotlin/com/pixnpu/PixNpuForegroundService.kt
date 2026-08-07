package com.pixnpu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the process alive (and a partial wake lock
 * held) while a generation runs or the API server is up, so both keep working
 * when the app loses focus or the screen turns off.
 *
 * Lifecycle is reference-counted by [MainViewModel]: it calls [start] when a
 * generation begins or the API server starts, [stop] when either ends, and the
 * service self-terminates (stopping the wake lock) once both are gone.
 */
class PixNpuForegroundService : Service() {

    companion object {
        private const val TAG = "PixNpuFgService"
        private const val CHANNEL_ID = "pixnpu_background"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PixNpuForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PixNpuForegroundService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Keep the CPU awake when the screen is off so the NPU/CPU generation
        // (and the API server) keep running in deep sleep.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pixnpu:generation")
            .also { it.acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // Not sticky: if the process dies, the generation/viewModel is gone too;
        // an empty restarted service would just hold a pointless wake lock.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release wake lock", e)
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Background work",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PixNPU is working")
            .setContentText("Generation and the API server keep running in the background")
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }
}
