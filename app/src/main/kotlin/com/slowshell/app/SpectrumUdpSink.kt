package com.slowshell.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sink for SpectrumPipeline frames.
 *
 * Frame layout (56 bytes, little-endian) — must match phone-spectrum-tap.rs:
 *   off  size  field
 *   0    2     magic = 0x5350 ("SP", little-endian → bytes 'P','S')
 *   2    1     version = 1
 *   3    1     flags        bit 0 = beat onset
 *   4    4     seq          u32 monotonic counter
 *   8    1     level        u8  0..=100
 *   9    2     reserved = 0
 *   11   45    bands[45]    u8  0..=100
 */
class SpectrumUdpSink(
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
        buf[0] = 'P'.code.toByte()       // little-endian: low byte first
        buf[1] = 'S'.code.toByte()
        buf[2] = 1                       // version
    }

    fun send(frame: SpectrumPipeline.Frame) {
        buf[3] = if (frame.beatOnset) 1 else 0
        seq++
        buf[4] = (seq and 0xFF).toByte()
        buf[5] = ((seq shr 8) and 0xFF).toByte()
        buf[6] = ((seq shr 16) and 0xFF).toByte()
        buf[7] = ((seq shr 24) and 0xFF).toByte()
        buf[8] = frame.level.coerceIn(0, 100).toByte()
        buf[9] = 0
        buf[10] = 0
        for (i in 0 until frame.bands.size.coerceAtMost(45)) {
            buf[11 + i] = frame.bands[i].coerceIn(0, 100).toByte()
        }
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
        const val FRAME_SIZE = 56
        private const val TAG = "SpectrumUdpSink"
    }
}
