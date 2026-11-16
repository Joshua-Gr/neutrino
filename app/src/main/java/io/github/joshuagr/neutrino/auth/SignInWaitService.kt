package io.github.joshuagr.neutrino.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the app in the foreground-service state while the user signs in on Proton's page.
 *
 * Android cuts network access for background apps, and during sign-in the browser is in front, so
 * the wait for the session would fail until the user came back. A short foreground service keeps
 * the network open; Android limits it to a few minutes, after which the wait simply continues the
 * next time the app is in front.
 */
class SignInWaitService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "sign-in-wait"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SignInWaitService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInWaitService::class.java))
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Waiting for sign-in", NotificationManager.IMPORTANCE_LOW))
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Waiting for Proton sign-in")
                .setContentText("Finish signing in on Proton's page.")
                .setOngoing(true)
                .build()
        }
    }
}
