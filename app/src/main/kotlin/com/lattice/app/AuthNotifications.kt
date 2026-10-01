package com.lattice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat

/**
 * One heads-up notification per open desktop challenge, with the two answers
 * as BUTTONS: `approve` opens AuthPromptActivity, which raises the fingerprint
 * prompt straight away, and `deny` answers from the shade. A tap on the body
 * is approve too. Swiping it away does NOTHING — an invisible gesture must
 * never be the destructive answer. The notification times itself out at the
 * challenge's expiry and is cancelled on `authc`.
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
    /** The design system's accent, as an ARGB int for the notification tint. */
    private val ACCENT: Int get() = com.lattice.app.lx.Roles.lattice.accent.toArgb()

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_asks), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notif_channel_asks_desc)
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
        val host = Names.host(c.host)
        val question = when (c.kind) {
            "unlock" -> context.getString(R.string.unlock_q, host)
            "sudo" -> context.getString(R.string.sudo_q, host)
            "test" -> context.getString(R.string.test_q, host)
            else -> context.getString(R.string.polkit_q, c.message.ifBlank { c.action })
        }
        val body = buildString {
            append(question)
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
            .setContentTitle(context.getString(R.string.notif_asks, host))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n${c.action}"))
            .addAction(R.drawable.ic_stat_fingerprint, context.getString(R.string.deny), denyPi)
            .addAction(R.drawable.ic_stat_fingerprint, context.getString(R.string.approve), openPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
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

/** The notification's deny button. */
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
