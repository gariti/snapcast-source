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
 * One heads-up notification per open desktop challenge. One tap opens
 * AuthPromptActivity, which raises the fingerprint prompt straight away;
 * swiping the notification away is a deny (its delete intent). No buttons —
 * the two gestures are the two answers. The notification times itself out at
 * the challenge's expiry and is cancelled on `authc` (a programmatic cancel
 * does not fire the delete intent, so a withdrawn challenge is not "denied").
 *
 * Its own channel, IMPORTANCE_HIGH: the beacon's channel is deliberately LOW
 * (a silent "Control Lattice" pill), and a request you just made at the desktop
 * has to buzz.
 *
 * NOT used for `unlock`. The lock screen re-arms its fingerprint helper for as
 * long as the desktop is locked, so notifying it would buzz once a minute
 * unprompted; that one is pulled instead, by opening the app
 * (`MainActivity.armUnlockPrompt`). See DesktopAuth.dispatch.
 */
object AuthNotifications {
    const val CHANNEL_ID = "desktop_auth"
    private const val ID_BASE = 4100
    /** The app's accent (Theme.kt `Cyan`), as an ARGB int for the notification tint. */
    private const val ACCENT = 0xFF7DD7DB.toInt()

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Desktop requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The desktop asks for your fingerprint: sudo, permissions."
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
        // The status-bar glyph is tiny and grey on recent Android; the picture
        // people actually see is the large icon, so the fingerprint goes there
        // too, in the app's accent.
        val glyph = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_stat_fingerprint)
        val large = glyph?.let { d ->
            val px = (64 * context.resources.displayMetrics.density).toInt()
            val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, px, px)
            d.setTint(ACCENT)
            d.draw(canvas)
            bmp
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fingerprint)
            .setColor(ACCENT)
            .setLargeIcon(large)
            .setContentTitle(c.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (c.message.isBlank()) body else "$body\n${c.message}"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .setDeleteIntent(denyPi)
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

/** The notification was swiped away: that is a deny. */
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
