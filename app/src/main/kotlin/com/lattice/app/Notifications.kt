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
import com.lattice.app.lx.Roles

/**
 * Notifications are split by job, each on its own channel so one can be
 * silenced without the others: Desktop asks (high, AuthNotifications) ·
 * Link (low: whether the desktop answers, and the window on the phone) ·
 * Say · Listen · Send, each with its own Stop. Agent requests are the
 * sibling's (AgentNotifications).
 */
object Notifications {
    const val CH_LINK = "link"
    const val CH_SAY = "say"
    const val CH_LISTEN = "listen"
    const val CH_SEND = "send"
    const val ID_SAY_RESULT = 46

    /** The design system's accent, for the notification tint. */
    val accent: Int get() = Roles.lattice.accent.toArgb()

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: Int, importance: Int, desc: Int? = null) {
            if (mgr.getNotificationChannel(id) == null) mgr.createNotificationChannel(
                NotificationChannel(id, context.getString(name), importance).apply {
                    if (desc != null) description = context.getString(desc)
                    setShowBadge(false)
                }
            )
        }
        ch(CH_LINK, R.string.notif_channel_link, NotificationManager.IMPORTANCE_LOW, R.string.notif_channel_link_desc)
        ch(CH_SAY, R.string.notif_channel_say, NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_LISTEN, R.string.notif_channel_listen, NotificationManager.IMPORTANCE_LOW)
        ch(CH_SEND, R.string.notif_channel_send, NotificationManager.IMPORTANCE_LOW)
    }

    fun openApp(context: Context, req: Int = 1): PendingIntent = PendingIntent.getActivity(
        context, req,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The Link notification: the app's front door, and it reports the REAL
     * state — "Linked to nixos-1 · Commander is on the phone · 2 fps", or
     * "nixos-1 is not answering". One action: say.
     */
    fun link(context: Context, linked: Boolean, host: String, windowTitle: String?, fps: Int): Notification {
        ensureChannels(context)
        val h = Names.host(host)
        val sayPi = PendingIntent.getForegroundService(
            context, 2,
            Intent(context, DictationService::class.java).setAction(DictationService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_lattice)
            .setColor(accent)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (linked) {
            b.setContentTitle(context.getString(R.string.notif_linked_to, h))
            if (windowTitle != null) b.setContentText(context.getString(R.string.notif_window_on_phone, windowTitle, fps))
            b.addAction(R.drawable.ic_stat_mic, context.getString(R.string.say), sayPi)
        } else {
            b.setContentTitle(context.getString(R.string.notif_not_answering, h))
        }
        return b.build()
    }

    /**
     * A Say result when the app is not in front (the quick-settings tile, the
     * Link notification's action): what was typed, and undo — which sends
     * one Backspace per character, the one undo every window understands.
     */
    fun sayResult(context: Context, windowTitle: String, text: String) {
        ensureChannels(context)
        val undoPi = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, SayUndoReceiver::class.java).putExtra(SayUndoReceiver.EXTRA_LEN, text.length),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_SAY)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setColor(accent)
            .setContentTitle(context.getString(R.string.typed_into, windowTitle))
            .setContentText("“${text.take(120)}”")
            .setStyle(NotificationCompat.BigTextStyle().bigText("“$text”"))
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stat_mic, context.getString(R.string.undo), undoPi)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_SAY_RESULT, n)
    }
}

/** Undo a Say: one Backspace per character typed, then the notification goes. */
class SayUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val n = intent.getIntExtra(EXTRA_LEN, 0).coerceIn(0, 2000)
        repeat(n) { Link.key("BackSpace") }
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SAY_RESULT)
    }
    companion object { const val EXTRA_LEN = "len" }
}
