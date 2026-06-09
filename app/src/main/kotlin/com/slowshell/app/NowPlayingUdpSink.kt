package com.slowshell.app

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sink for the richer "now playing" frame — title, artist,
 * app, and the active session's volume. Sent alongside the 16-byte is-playing
 * beacon so the desktop can render a now-playing card and a live phone-volume
 * slider.
 *
 * Frame layout (little-endian, variable length) — must match
 * phone-nowplaying-tap.rs:
 *   off  size  field
 *   0    2     magic = bytes 'N','P'
 *   2    1     version = 1
 *   3    1     flags   bit0 = is_playing, bit1 = volume_controllable,
 *                      bit2 = volume_remote (cast)
 *   4    1     volume_percent (0..100)
 *   5    1     reserved = 0
 *   6    4     seq u32
 *   10   1     title_len   (bytes, UTF-8, truncated to 200)
 *   11   ..    title
 *   ..   1     artist_len
 *   ..   ..    artist
 *   ..   1     app_len
 *   ..   ..    app (package name)
 */
class NowPlayingUdpSink(
    host: String,
    private val port: Int,
) {
    private val socket: DatagramSocket = DatagramSocket()
    private val addr: InetAddress = InetAddress.getByName(host)
    private var seq: Int = 0

    fun send(state: MediaSessionListener.State) {
        seq++
        var flags = 0
        if (state.isPlaying) flags = flags or 0x01
        if (state.volumeControllable) flags = flags or 0x02
        if (state.volumeRemote) flags = flags or 0x04

        val out = ByteArrayOutputStream(64)
        out.write('N'.code)
        out.write('P'.code)
        out.write(1)                       // version
        out.write(flags and 0xFF)
        out.write(state.volumePercent.coerceIn(0, 100))
        out.write(0)                       // reserved
        out.write(seq and 0xFF)
        out.write((seq shr 8) and 0xFF)
        out.write((seq shr 16) and 0xFF)
        out.write((seq shr 24) and 0xFF)
        writeStr(out, state.title)
        writeStr(out, state.artist)
        writeStr(out, state.app)

        val bytes = out.toByteArray()
        try {
            socket.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed: ${e.message}")
        }
    }

    private fun writeStr(out: ByteArrayOutputStream, s: String) {
        var bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STR) bytes = bytes.copyOf(MAX_STR)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "NowPlayingUdpSink"
        private const val MAX_STR = 200
    }
}
