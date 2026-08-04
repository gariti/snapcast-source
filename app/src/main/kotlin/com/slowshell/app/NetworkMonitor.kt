package com.slowshell.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide "the network just changed — try again NOW" signal for the
 * reconnect loops (ControlChannelClient, TcpStreamer).
 *
 * Why this exists: both loops used to sleep on a bare capped-exponential
 * `delay()`, so the single event most likely to make a dead route work — a VPN
 * tunnel coming up — produced no reaction at all. With the desktop reached over
 * Tailscale, a dropped tunnel drove backoff to its 5-minute ceiling and the link
 * then stayed down for minutes AFTER the tunnel was restored and the path was
 * provably good. Waiting on this instead of on a timer turns that into an
 * immediate re-dial, and is strictly LESS battery than shortening the ceiling:
 * it adds no wakeups, it only cancels one that was already scheduled.
 *
 * One ConnectivityManager callback is registered for the whole process, on the
 * first [subscribe], and unregistered when the last subscriber closes — leaking
 * a NetworkCallback outlives the service that armed it and eventually trips the
 * per-app callback limit.
 */
object NetworkMonitor {

    /**
     * A retry loop's handle on the signal. Not thread-safe; use it from the one
     * coroutine that owns the loop, and [close] it in that loop's `finally`.
     */
    class Subscription internal constructor() {
        // CONFLATED: a loop that was busy dialling wants "something changed",
        // not a backlog of every transition it slept through.
        internal val events = Channel<Unit>(Channel.CONFLATED)

        /**
         * Sleep up to [timeoutMs]. Returns true if a network change cut the wait
         * short — i.e. the caller should retry immediately and reset its backoff.
         */
        suspend fun awaitChange(timeoutMs: Long): Boolean =
            timeoutMs > 0 && withTimeoutOrNull(timeoutMs) { events.receive() } != null

        /**
         * Discard a change queued before the attempt that is about to start —
         * that attempt already reflects it, so it must not shortcut the wait
         * that follows.
         */
        fun drainPending() {
            events.tryReceive()
        }

        fun close() = unsubscribe(this)
    }

    fun subscribe(context: Context): Subscription {
        val sub = Subscription()
        synchronized(lock) {
            subscribers.add(sub)
            if (callback == null) register(context.applicationContext)
        }
        return sub
    }

    // ---- registration -----------------------------------------------------

    private val lock = Any()
    private val subscribers = mutableSetOf<Subscription>()
    private val fingerprints = HashMap<Network, Int>()
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastSignalMs = 0L

    private fun unsubscribe(sub: Subscription) {
        synchronized(lock) {
            if (!subscribers.remove(sub)) return
            if (subscribers.isEmpty()) unregister()
        }
    }

    /** Caller holds [lock]. */
    private fun register(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        // NetworkRequest.Builder() implies NET_CAPABILITY_NOT_VPN, so a Tailscale
        // (or any VpnService) tunnel coming up would NOT match the request — and
        // that is exactly the transition worth waking for. Drop the implied
        // capability so VPN networks are visible to us.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = signal("available")

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Fires often (bandwidth estimates, signal strength). Only the
                // reachability-relevant bits are worth a re-dial.
                val now = fingerprint(caps)
                val prev = synchronized(lock) { fingerprints.put(network, now) }
                if (prev != now) signal(describe(now))
            }

            override fun onLost(network: Network) {
                synchronized(lock) { fingerprints.remove(network) }
                // No signal: nothing to retry onto. The loop keeps backing off.
            }
        }
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            // SecurityException (missing ACCESS_NETWORK_STATE) or
            // TooManyRequestsException — degrade to plain timer backoff.
            Log.w(TAG, "registerNetworkCallback failed: ${e.message}")
            return
        }
        connectivity = cm
        callback = cb
        Log.i(TAG, "watching network changes (VPN transitions included)")
    }

    /** Caller holds [lock]. */
    private fun unregister() {
        val cm = connectivity
        val cb = callback
        connectivity = null
        callback = null
        fingerprints.clear()
        if (cm != null && cb != null) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            Log.i(TAG, "stopped watching network changes")
        }
    }

    private fun signal(reason: String) {
        val woken: List<Subscription>
        synchronized(lock) {
            // Floor between wakeups: a flapping interface must not turn into a
            // re-dial storm, since every wake also resets the caller's backoff.
            val now = SystemClock.elapsedRealtime()
            if (now - lastSignalMs < MIN_SIGNAL_INTERVAL_MS) return
            lastSignalMs = now
            woken = subscribers.toList()
        }
        Log.i(TAG, "network changed ($reason) — waking ${woken.size} retry loop(s)")
        woken.forEach { it.events.trySend(Unit) }
    }

    private fun fingerprint(caps: NetworkCapabilities): Int {
        var f = 0
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) f = f or 1
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) f = f or 2
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) f = f or 4
        return f
    }

    private fun describe(f: Int): String = buildString {
        append(if (f and 1 != 0) "internet" else "no-internet")
        if (f and 2 != 0) append("+validated")
        if (f and 4 != 0) append("+vpn")
    }

    private const val TAG = "NetworkMonitor"
    private const val MIN_SIGNAL_INTERVAL_MS = 2_000L
}
