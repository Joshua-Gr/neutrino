package io.github.joshuagr.neutrino.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.Notification
import io.github.joshuagr.neutrino.MainActivity

/** User-visible outcome of uploads that need attention: conflicts and failures. Successes stay silent. */
class UploadNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Uploads needing attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Saves that could not be uploaded to Drive, or were saved as a conflict copy."
            },
        )
    }

    fun conflict(documentId: String, fileName: String, copyName: String) = post(
        documentId,
        "Saved as a conflict copy",
        "\"$fileName\" changed in Drive since it was opened, so this save was uploaded as \"$copyName\".",
    )

    fun failed(documentId: String, fileName: String, reason: String) = post(
        documentId,
        "Save not uploaded yet",
        "\"$fileName\" is saved on this phone but could not be uploaded: $reason. It will be retried.",
    )

    fun clear(documentId: String) = manager.cancel(documentId.hashCode())

    private fun post(documentId: String, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(documentId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "uploads"
    }
}
