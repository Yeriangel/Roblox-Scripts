package com.diagnosepro.app.service

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
import com.diagnosepro.app.MainActivity
import com.diagnosepro.app.R

/**
 * Keeps the process (and therefore the singleton ObdManager and its Bluetooth link) alive
 * while connected, so polling and session logging continue with the screen off.
 * The connection itself lives in ObdManager; this service only holds the foreground state.
 */
class ObdForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val notification = build(this, name)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Missing permission or background-start restriction: run without foreground state.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_NAME = "name"

        fun start(context: Context, deviceName: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ObdForegroundService::class.java).putExtra(EXTRA_NAME, deviceName))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ObdForegroundService::class.java)) }
        }

        private fun build(context: Context, name: String): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_car)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_connected, name))
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
