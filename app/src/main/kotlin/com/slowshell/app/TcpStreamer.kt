package com.slowshell.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Streams PCM to the desktop over TCP. [write] is called from the audio capture
 * thread on every buffer, so it MUST NOT block: a failed/absent connection
 * schedules an asynchronous reconnect on [scope] (with exponential backoff, cut
 * short by any network change — see NetworkMonitor) and the audio thread simply
 * drops frames until the link is back. UI state copies (bytesSent/rms) are
 * throttled to ~1 Hz so the high-frequency stream doesn't trigger a Compose
 * recomposition on every audio buffer.
 */
class TcpStreamer(
    context: Context,
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var totalBytesSent: Long = 0
    @Volatile private var lastRms: Float = 0f
    private var attempt = 0
    private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var stopped = false
    private var lastUiUpdateMs = 0L

    /** Blocking single connection attempt (used once at startup). Never sleeps. */
    fun connect() {
        attempt++
        _state.value = if (attempt == 1) {
            ConnectionState.Connecting(host, port)
        } else {
            val current = _state.value
            val lastErr = if (current is ConnectionState.Reconnecting) current.lastError else ""
            ConnectionState.Reconnecting(host, port, attempt, lastErr)
        }

        // Bounded connect: `Socket(host, port)` waits out the OS-level SYN
        // timeout (minutes) on a black-holed route, which stalls the retry loop
        // exactly when the route is dead and retries matter most.
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.soTimeout = 5_000
            socket = s
            out = s.getOutputStream()
            backoffMs = INITIAL_BACKOFF_MS
            attempt = 0
            _state.value = ConnectionState.Connected(host, port, totalBytesSent, lastRms)
            Log.i(TAG, "Connected to $host:$port")
        } catch (e: IOException) {
            runCatching { s.close() }
            val reason = categorizeIoError(e.message)
            Log.w(TAG, "Connect failed: $reason")
            _state.value = ConnectionState.Reconnecting(host, port, attempt, reason)
        }
    }

    fun write(buf: ByteArray, len: Int, rms: Float) {
        if (stopped) return
        lastRms = rms
        val stream = out
        if (stream == null) {
            scheduleReconnect("Not connected")
            return
        }
        try {
            stream.write(buf, 0, len)
            totalBytesSent += len
            val now = System.currentTimeMillis()
            if (now - lastUiUpdateMs >= UI_UPDATE_MS) {
                lastUiUpdateMs = now
                val current = _state.value
                if (current is ConnectionState.Connected) {
                    _state.value = current.copy(bytesSent = totalBytesSent, rms = rms)
                }
            }
        } catch (e: IOException) {
            val reason = categorizeIoError(e.message)
            Log.w(TAG, "Write failed: $reason")
            scheduleReconnect(reason)
        }
    }

    /**
     * Tear down the dead socket and kick off a background reconnect loop. Returns
     * immediately — NEVER blocks the audio thread. At most one retry loop runs.
     */
    @Synchronized
    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        if (reconnectJob?.isActive == true) return
        close()
        _state.value = ConnectionState.Reconnecting(host, port, attempt, reason)
        reconnectJob = scope.launch(Dispatchers.IO) {
            // Same blindness the control channel had: a timer-only loop ignores
            // the VPN/Wi-Fi coming back. Wake on either.
            val network = NetworkMonitor.subscribe(appContext)
            try {
                while (isActive && out == null) {
                    if (network.awaitChange(backoffMs)) {
                        Log.i(TAG, "network changed — reconnecting now")
                        backoffMs = INITIAL_BACKOFF_MS
                    } else {
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                    network.drainPending()
                    connect()
                }
            } finally {
                network.close()
            }
        }
    }

    fun close() {
        runCatching { out?.close() }
        runCatching { socket?.close() }
        out = null
        socket = null
        if (_state.value !is ConnectionState.Failed) {
            _state.value = ConnectionState.Idle
        }
    }

    fun stop() {
        // Terminal: a stopped streamer must never write or re-arm a reconnect,
        // even if the audio thread fires one last write() after cancellation.
        // Without this a torn-down session can resurrect its socket and fight
        // the new session for the mixer slot.
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        close()
    }

    companion object {
        private const val TAG = "TcpStreamer"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val INITIAL_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
        private const val UI_UPDATE_MS = 1_000L
    }
}
