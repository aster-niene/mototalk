package dev.mototalk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.mototalk.R
import dev.mototalk.intercom.SessionState
import dev.mototalk.ui.MainActivity

object RideNotification {

    const val ID = 1
    private const val CHANNEL_ID = "ride"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_ride),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, state: SessionState): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 1,
            RideService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            state.intercomActive -> "Intercom active"
            else -> "MotoTalk ${state.kind?.name?.lowercase() ?: ""}".trim()
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(title)
            .setContentText("Link ${state.link} · Local ${state.localAudio} · Remote ${state.remoteAudio}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_action_stop), stop)
            .build()
    }
}
