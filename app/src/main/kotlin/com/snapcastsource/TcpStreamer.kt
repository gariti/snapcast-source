package com.snapcastsource

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.Socket

class TcpStreamer(
    private val host: String,
    private val port: Int
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var backoffMs = INITIAL_BACKOFF_MS

    fun connect() {
        try {
            val s = Socket(host, port).apply {
                tcpNoDelay = true
                soTimeout = 5_000
            }
            socket = s
            out = s.getOutputStream()
            backoffMs = INITIAL_BACKOFF_MS
            Log.i(TAG, "Connected to $host:$port")
        } catch (e: IOException) {
            Log.w(TAG, "Connect failed: ${e.message}")
            sleepBackoff()
        }
    }

    fun write(buf: ByteArray, len: Int) {
        val stream = out
        if (stream == null) {
            reconnect()
            return
        }
        try {
            stream.write(buf, 0, len)
        } catch (e: IOException) {
            Log.w(TAG, "Write failed: ${e.message}, reconnecting")
            reconnect()
        }
    }

    private fun reconnect() {
        close()
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
    }

    companion object {
        private const val TAG = "TcpStreamer"
        private const val INITIAL_BACKOFF_MS = 250L
        private const val MAX_BACKOFF_MS = 5_000L
    }
}
