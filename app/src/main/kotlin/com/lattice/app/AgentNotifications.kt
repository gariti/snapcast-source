package com.lattice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * An agent on the desktop asks for the human: `{t:"notify", id, title, body, from}`
 * from lattice-link (the desktop's `phone-notify`). One heads-up per message on
 * its own IMPORTANCE_HIGH channel, "Agent requests", so it can be silenced
 * without silencing fingerprint requests.
 *
 * It carries text only — no buttons, no actions. A tap opens the Lattice app
 * and nothing else. The desktop validates and rate-limits before it sends
 * (one line each, title ≤ 60, body ≤ 300, 1 per 10 s, 20 per hour); this side
 * clips again anyway.
 *
 * Every message is answered with `{t:"notifya", id, shown}` once posted, so the
 * agent learns whether it actually reached a screen. `shown` is false when the
 * app's notifications or this channel are switched off.
 */
object AgentNotifications {
    private const val TAG = "AgentNotify"
    const val CHANNEL_ID = "agent_requests"
    private const val ID_BASE = 4300
    private const val ACCENT = 0xFF7DD7DB.toInt()
    /** An unread request older than this is stale; the agent has moved on. */
    private const val TIMEOUT_MS = 30 * 60 * 1000L

    private var collector: Job? = null

    fun attach(context: Context, client: ControlChannelClient, scope: CoroutineScope) {
        val app = context.applicationContext
        collector?.cancel()
        collector = scope.launch(Dispatchers.Default) {
            client.messages.collect { msg ->
                if (msg["t"] != "notify") return@collect
                val id = msg["id"] as? Long ?: return@collect
                val shown = try {
                    show(
                        app,
                        id,
                        (msg["title"] as? String).orEmpty().take(60),
                        (msg["body"] as? String).orEmpty().take(300),
                        (msg["from"] as? String).orEmpty().take(64),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "notify #$id failed", e)
                    false
                }
                client.send(mapOf("t" to "notifya", "id" to id, "shown" to shown))
            }
        }
    }

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "An agent on the desktop needs you, and says why."
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }

    /** Post it; true when the user can actually see it. */
    private fun show(context: Context, id: Long, title: String, body: String, from: String): Boolean {
        ensureChannel(context)
        val mgr = context.getSystemService(NotificationManager::class.java)
        val notifId = ID_BASE + (id.hashCode() and 0x7fff)
        val openPi = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lattice)
            .setColor(ACCENT)
            .setContentTitle(title.ifBlank { "An agent needs you" })
            .setContentText(body)
            .setSubText(from.ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        mgr.notify(notifId, n)
        val channelOn = mgr.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        return mgr.areNotificationsEnabled() && channelOn
    }
}
