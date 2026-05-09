package com.snapcastsource

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

class TcpStreamer(
    private val host: String,
    private val port: Int
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var totalBytesSent: Long = 0
    @Volatile private var lastRms: Float = 0f
    private var attempt = 0
    private var backoffMs = INITIAL_BACKOFF_MS

    fun connect() {
        attempt++
        _state.value = if (attempt == 1) {
            ConnectionState.Connecting(host, port)
        } else {
            val current = _state.value
            val lastErr = if (current is ConnectionState.Reconnecting) current.lastError else ""
            ConnectionState.Reconnecting(host, port, attempt, lastErr)
        }

        try {
            val s = Socket(host, port).apply {
                tcpNoDelay = true
                soTimeout = 5_000
            }
            socket = s
            out = s.getOutputStream()
            backoffMs = INITIAL_BACKOFF_MS
            attempt = 0
            _state.value = ConnectionState.Connected(host, port, totalBytesSent, lastRms)
            Log.i(TAG, "Connected to $host:$port")
        } catch (e: IOException) {
            val reason = categorizeIoError(e.message)
            Log.w(TAG, "Connect failed: $reason")
            _state.value = ConnectionState.Reconnecting(host, port, attempt, reason)
            sleepBackoff()
        }
    }

    fun write(buf: ByteArray, len: Int, rms: Float) {
        lastRms = rms
        val stream = out
        if (stream == null) {
            reconnect("Not connected")
            return
        }
        try {
            stream.write(buf, 0, len)
            totalBytesSent += len
            val current = _state.value
            if (current is ConnectionState.Connected) {
                _state.value = current.copy(bytesSent = totalBytesSent, rms = rms)
            }
        } catch (e: IOException) {
            val reason = categorizeIoError(e.message)
            Log.w(TAG, "Write failed: $reason")
            reconnect(reason)
        }
    }

    private fun reconnect(reason: String) {
        close()
        _state.value = ConnectionState.Reconnecting(host, port, attempt, reason)
        sleepBackoff()
        connect()
    }

    private fun sleepBackoff() {
        try {
            Thread.sleep(backoffMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
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

    companion object {
        private const val TAG = "TcpStreamer"
        private const val INITIAL_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
    }
}
