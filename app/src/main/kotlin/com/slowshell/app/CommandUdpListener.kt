package com.slowshell.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP listener for desktop -> phone control commands (volume / transport).
 * The desktop (SlowShell mixer) sends a fixed 16-byte frame; we dispatch it to
 * the active MediaController via MediaSessionListener.applyCommand().
 *
 * Frame layout (little-endian, 16 bytes) — must match phone-command.rs:
 *   off  size  field
 *   0    2     magic = bytes 'S','C'
 *   2    1     version = 1
 *   3    1     cmd   1=SetVolume 2=Play 3=Pause 4=PlayPause 5=Next 6=Prev
 *   4    1     arg   volume 0..100 (SetVolume), else 0
 *   5    3     reserved
 *   8    4     seq u32
 *   12   4     reserved
 *
 * Blocking receive loop — run on Dispatchers.IO. close() unblocks recv().
 */
class CommandUdpListener(private val port: Int) {

    @Volatile
    private var socket: DatagramSocket? = null

    /** Blocking loop; returns when the socket is closed via [close]. */
    fun listen() {
        val sock = try {
            DatagramSocket(port).also { socket = it }
        } catch (e: Exception) {
            Log.e(TAG, "bind :$port failed: ${e.message}")
            return
        }
        Log.i(TAG, "command listener bound on :$port")
        val buf = ByteArray(FRAME_SIZE)
        while (!sock.isClosed) {
            val packet = DatagramPacket(buf, FRAME_SIZE)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                if (sock.isClosed) break
                Log.w(TAG, "recv error: ${e.message}")
                continue
            }
            if (packet.length < FRAME_SIZE) continue
            if (buf[0] != 'S'.code.toByte() || buf[1] != 'C'.code.toByte()) continue
            if (buf[2].toInt() != 1) continue
            val cmd = buf[3].toInt() and 0xFF
            val arg = buf[4].toInt() and 0xFF
            Log.d(TAG, "cmd=$cmd arg=$arg from ${packet.address?.hostAddress}")
            MediaSessionListener.applyCommand(cmd, arg)
        }
        Log.i(TAG, "command listener stopped")
    }

    fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        private const val TAG = "CommandUdpListener"
        const val FRAME_SIZE = 16
    }
}
