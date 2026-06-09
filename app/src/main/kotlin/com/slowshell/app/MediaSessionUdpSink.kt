package com.slowshell.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sink for the "media session is playing" beacon.
 *
 * Frame layout (16 bytes, little-endian) — must match phone-media-tap.rs:
 *   off  size  field
 *   0    2     magic = 0x4D53 ("MS", little-endian → bytes 'S','M')
 *   2    1     version = 1
 *   3    1     flags        bit 0 = is_playing, bit 1 = has_metadata
 *   4    4     seq          u32 monotonic counter
 *   8    4     session_count u32
 *   12   4     reserved = 0
 */
class MediaSessionUdpSink(
    private val host: String,
    private val port: Int,
) {
    private val socket: DatagramSocket = DatagramSocket()
    private val addr: InetAddress = InetAddress.getByName(host)
    private val buf = ByteArray(FRAME_SIZE)
    private val packet = DatagramPacket(buf, FRAME_SIZE, addr, port)
    private var seq: Int = 0

    init {
        // Magic + version are stable across the connection.
        buf[0] = 'S'.code.toByte()       // little-endian: low byte first
        buf[1] = 'M'.code.toByte()
        buf[2] = 1                       // version
        // bytes 12..15 stay zero (reserved)
    }

    fun send(isPlaying: Boolean, sessionCount: Int) {
        var flags = 0
        if (isPlaying) flags = flags or 0x01
        buf[3] = flags.toByte()
        seq++
        buf[4] = (seq and 0xFF).toByte()
        buf[5] = ((seq shr 8) and 0xFF).toByte()
        buf[6] = ((seq shr 16) and 0xFF).toByte()
        buf[7] = ((seq shr 24) and 0xFF).toByte()
        val count = sessionCount.coerceAtLeast(0)
        buf[8] = (count and 0xFF).toByte()
        buf[9] = ((count shr 8) and 0xFF).toByte()
        buf[10] = ((count shr 16) and 0xFF).toByte()
        buf[11] = ((count shr 24) and 0xFF).toByte()
        try {
            socket.send(packet)
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed: ${e.message}")
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val FRAME_SIZE = 16
        private const val TAG = "MediaSessionUdpSink"
    }
}
