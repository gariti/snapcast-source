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
 *                     7=PauseCapture 8=ResumeCapture (consumer-gated streaming)
 *   4    1     arg   volume 0..100 (SetVolume), else 0
 *   5    3     reserved
 *   8    4     seq u32
 *   12   4     reserved
 *
 * Blocking receive loop — run on Dispatchers.IO. close() unblocks recv().
 *
 * SECURITY: This is an unauthenticated control channel — anything that reaches
 * port 4904 with a valid frame would drive the phone's MediaController. We
 * mitigate by accepting commands ONLY from the configured desktop host
 * ([allowedHost], the same host the beacon is sent to). Source-IP filtering
 * fits the existing config and blocks arbitrary LAN actors; it does NOT defend
 * against a host that can spoof the desktop's source IP on the local segment.
 * If a stronger model is ever needed (untrusted LAN), add an HMAC-SHA256 over
 * (magic||version||cmd||arg||seq) with a key negotiated out-of-band + a replay
 * window on seq. For a trusted home network controlling one's own media volume,
 * source filtering is the proportionate control.
 */
class CommandUdpListener(
    private val port: Int,
    private val allowedHost: String,
) {

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

        // Resolve the single host allowed to command us. Fail CLOSED: if the
        // host is blank/unresolvable we accept nothing (the beacon needs a host
        // too, so an unconfigured app is non-functional either way).
        val allowed: Set<String> = try {
            if (allowedHost.isBlank()) emptySet()
            else java.net.InetAddress.getAllByName(allowedHost)
                .mapNotNull { it.hostAddress }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "cannot resolve allowed host '$allowedHost': ${e.message}")
            emptySet()
        }
        Log.i(TAG, "command listener bound on :$port, allowed source(s)=$allowed")

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
            // Drop anything not from the configured desktop host.
            val src = packet.address?.hostAddress
            if (allowed.isEmpty() || src == null || src !in allowed) {
                Log.w(TAG, "dropping command from unauthorized source $src")
                continue
            }
            if (packet.length < FRAME_SIZE) continue
            if (buf[0] != 'S'.code.toByte() || buf[1] != 'C'.code.toByte()) continue
            if (buf[2].toInt() != 1) continue
            val cmd = buf[3].toInt() and 0xFF
            val arg = buf[4].toInt() and 0xFF
            Log.d(TAG, "cmd=$cmd arg=$arg from $src")
            when (cmd) {
                CMD_PAUSE_CAPTURE -> AudioCaptureService.setPaused(true)
                CMD_RESUME_CAPTURE -> AudioCaptureService.setPaused(false)
                else -> MediaSessionListener.applyCommand(cmd, arg)
            }
        }
        Log.i(TAG, "command listener stopped")
    }

    fun close() {
        runCatching { socket?.close() }
    }

    companion object {
        private const val TAG = "CommandUdpListener"
        const val FRAME_SIZE = 16
        private const val CMD_PAUSE_CAPTURE = 7
        private const val CMD_RESUME_CAPTURE = 8
    }
}
