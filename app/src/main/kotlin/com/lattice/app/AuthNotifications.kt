package com.lattice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * One heads-up notification per open desktop challenge. Tapping it (or
 * Approve) opens AuthPromptActivity, which is where the fingerprint happens;
 * Deny answers straight from the shade. The notification times itself out at
 * the challenge's expiry and is cancelled on `authc`.
 *
 * Its own channel, IMPORTANCE_HIGH: the beacon's channel is deliberately LOW
 * (a silent "linked" pill), and a request to unlock the desktop has to buzz.
 */
object AuthNotifications {
    const val CHANNEL_ID = "desktop_auth"
    private const val ID_BASE = 4100

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Desktop requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The desktop asks for your fingerprint: sudo, permissions, unlock."
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun notifId(id: String): Int = ID_BASE + (id.hashCode() and 0x7fff)

    fun show(context: Context, c: DesktopAuth.Challenge) {
        ensureChannel(context)
        val openPi = PendingIntent.getActivity(
            context,
            notifId(c.id),
            AuthPromptActivity.intent(context, c.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val denyPi = PendingIntent.getBroadcast(
            context,
            notifId(c.id) + 1,
            Intent(context, AuthActionReceiver::class.java)
                .setAction(AuthActionReceiver.ACTION_DENY)
                .putExtra(AuthActionReceiver.EXTRA_ID, c.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = buildString {
            append(c.action)
            if (c.caller.isNotBlank()) append("  ·  ").append(c.caller)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lattice)
            .setContentTitle(c.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (c.message.isBlank()) body else "$body\n${c.message}"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .addAction(0, "Approve", openPi)
            .addAction(0, "Deny", denyPi)
            .setAutoCancel(false)
            .setOngoing(false)
            .setTimeoutAfter(c.remaining * 1000L)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId(c.id), n)
    }

    fun cancel(context: Context, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notifId(id))
    }
}

/** "Deny" from the notification shade. */
class AuthActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DENY) {
            intent.getStringExtra(EXTRA_ID)?.let { DesktopAuth.deny(context, it) }
        }
    }

    companion object {
        const val ACTION_DENY = "com.lattice.app.auth.DENY"
        const val EXTRA_ID = "id"
    }
}
