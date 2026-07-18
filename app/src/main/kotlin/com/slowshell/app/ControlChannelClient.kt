package com.slowshell.app

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The phone side of the "slink" control channel — ONE length-prefixed CBOR TCP
 * connection to the desktop's phone-link daemon (port 4906) that replaces the
 * legacy control-plane UDP zoo (4902 beacon / 4903 now-playing / 4904 commands).
 *
 * Framing: 4-byte big-endian length (1..4096) + definite-length CBOR map with
 * text keys; the "t" key is the message type. Unknown types are ignored on both
 * sides (version tolerance).
 *
 * Handshake (mutual auth on the QR-pairing PSK — see PairingCrypto):
 *   1. we send    {t:"hello", proto:1, device, app, caps, nonce(16B)}
 *   2. they send  {t:"hello", proto, caps, nonce(16B),
 *                  proof=HMAC(psk, "slink-srv"||our nonce)}   — we VERIFY
 *   3. we send    {t:"auth",  proof=HMAC(psk, "slink-cli"||their nonce)}
 *   4. they send  {t:"ready"}
 *
 * Presence on the desktop is derived from THIS connection being up — there is
 * no periodic beacon. Media / now-playing changes are pushed as events (send*
 * methods, deduped per connection); a dead desktop is noticed via send failure
 * or read EOF and the client reconnects with capped exponential backoff.
 *
 * Battery notes: zero idle traffic from the app (the desktop's kernel-level
 * TCP keepalive probes are answered by OUR kernel without waking us). While
 * the phone deep-dozes, probes go unanswered and the desktop correctly marks
 * the phone absent.
 *
 * Fail-closed: no PSK configured -> we never connect (the channel would be
 * unauthenticated). Callers fall back to the legacy UDP path in that case.
 */
class ControlChannelClient(
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int,
    private val normalizedPsk: String,
) {
    sealed class LinkState {
        data object Disconnected : LinkState()
        data object Connecting : LinkState()
        data class Connected(val proto: Long, val caps: Set<String>) : LinkState()
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private var job: Job? = null

    // Guarded by writeMutex: the socket writer + last-sent dedupe state.
    private val writeMutex = Mutex()
    private var output: DataOutputStream? = null
    private var lastMedia: Pair<Boolean, Int>? = null
    private var lastNp: List<Any?>? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        _state.value = LinkState.Disconnected
    }

    /** Push a media state change. No-op (true) when unchanged or disconnected. */
    suspend fun sendMedia(isPlaying: Boolean, sessionCount: Int): Boolean {
        if (_state.value !is LinkState.Connected) return false
        return writeMutex.withLock {
            val key = Pair(isPlaying, sessionCount)
            if (lastMedia == key) return@withLock true
            val ok = writeFrameLocked(
                mapOf("t" to "media", "playing" to isPlaying, "sessions" to sessionCount.toLong())
            )
            if (ok) lastMedia = key
            ok
        }
    }

    /** Push a now-playing change. Deduped on the visible fields. */
    suspend fun sendNowPlaying(s: MediaSessionListener.State): Boolean {
        if (_state.value !is LinkState.Connected) return false
        return writeMutex.withLock {
            val key = listOf(
                s.isPlaying, s.title, s.artist, s.app,
                s.volumePercent, s.volumeControllable, s.volumeRemote,
            )
            if (lastNp == key) return@withLock true
            val ok = writeFrameLocked(
                mapOf(
                    "t" to "np",
                    "playing" to s.isPlaying,
                    "title" to s.title.take(MAX_STR),
                    "artist" to s.artist.take(MAX_STR),
                    "app" to s.app.take(MAX_STR),
                    "vol" to s.volumePercent.coerceIn(0, 100).toLong(),
                    "ctl" to s.volumeControllable,
                    "remote" to s.volumeRemote,
                )
            )
            if (ok) lastNp = key
            ok
        }
    }

    // ---- connection loop --------------------------------------------------

    private suspend fun runLoop() {
        if (normalizedPsk.isEmpty()) {
            Log.w(TAG, "no pairing code set — control channel disabled (fail closed)")
            _state.value = LinkState.Disconnected
            return
        }
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive && job?.isActive == true) {
            _state.value = LinkState.Connecting
            val connectedAt = System.currentTimeMillis()
            try {
                connectOnce()
            } catch (e: Exception) {
                Log.i(TAG, "link down: ${e.message}")
            } finally {
                closeSocket()
                _state.value = LinkState.Disconnected
            }
            // A connection that lived a while proves the path works — reset
            // backoff so a blip reconnects fast. Repeated instant failures
            // back off up to 5 min (radio-friendly while out of reach).
            backoffMs =
                if (System.currentTimeMillis() - connectedAt > STABLE_CONNECTION_MS)
                    INITIAL_BACKOFF_MS
                else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private suspend fun connectOnce() {
        val sock = Socket()
        val input: DataInputStream
        try {
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            input = DataInputStream(sock.getInputStream().buffered())
            val out = DataOutputStream(sock.getOutputStream().buffered())

            // -- handshake (read timeout armed so a black-holed TCP path can't
            //    hang us forever; disarmed afterwards — post-handshake reads
            //    legitimately idle for hours) --
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS
            val myNonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            writeFrameTo(out, mapOf(
                "t" to "hello",
                "proto" to PROTO_VERSION,
                "device" to deviceName(),
                "app" to BuildConfig.VERSION_NAME,
                "caps" to CLIENT_CAPS,
                "nonce" to myNonce,
            ))
            val hello = readFrame(input)
            if (hello["t"] != "hello") throw Exception("expected hello, got ${hello["t"]}")
            val proto = minOf(hello["proto"] as? Long ?: 0L, PROTO_VERSION)
            if (proto < 1) throw Exception("no common protocol version")
            val theirNonce = hello["nonce"] as? ByteArray ?: throw Exception("hello missing nonce")
            val proof = hello["proof"] as? ByteArray ?: throw Exception("hello missing proof")
            // Verify the DESKTOP knows the PSK before trusting anything it says
            // (DNS/mDNS spoofers and strangers on the LAN fail here).
            if (!PairingCrypto.constantTimeEquals(proof, hmac(DOMAIN_SRV, myNonce))) {
                throw Exception("desktop failed PSK proof — refusing link")
            }
            writeFrameTo(out, mapOf("t" to "auth", "proof" to hmac(DOMAIN_CLI, theirNonce)))
            val ready = readFrame(input)
            if (ready["t"] != "ready") throw Exception("auth rejected (${ready["t"]}/${ready["err"]})")
            sock.soTimeout = 0

            @Suppress("UNCHECKED_CAST")
            val caps = (hello["caps"] as? List<Any?>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            writeMutex.withLock {
                socket = sock
                output = out
                lastMedia = null
                lastNp = null
            }
            _state.value = LinkState.Connected(proto, caps)
            Log.i(TAG, "linked to $host:$port proto=$proto caps=$caps")
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }

        // -- read loop: dispatch desktop->phone commands until EOF/error --
        while (true) {
            val msg = readFrame(input)
            when (msg["t"]) {
                "cmd" -> {
                    val cmd = (msg["cmd"] as? Long)?.toInt() ?: continue
                    val arg = (msg["arg"] as? Long)?.toInt() ?: 0
                    Log.d(TAG, "cmd=$cmd arg=$arg")
                    when (cmd) {
                        CMD_PAUSE_CAPTURE -> AudioCaptureService.setPaused(true)
                        CMD_RESUME_CAPTURE -> AudioCaptureService.setPaused(false)
                        else -> MediaSessionListener.applyCommand(cmd, arg)
                    }
                }
                "ping" -> writeMutex.withLock {
                    writeFrameLocked(mapOf("t" to "pong", "n" to (msg["n"] as? Long ?: 0L)))
                }
                "bye" -> throw EOFException("desktop sent bye")
                else -> Log.d(TAG, "ignoring message t=${msg["t"]}")
            }
        }
    }

    // ---- plumbing ---------------------------------------------------------

    @Volatile
    private var socket: Socket? = null

    private fun closeSocket() {
        val s = socket
        socket = null
        output = null
        runCatching { s?.close() }
    }

    private fun hmac(domain: ByteArray, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalizedPsk.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        mac.update(domain)
        return mac.doFinal(nonce)
    }

    /** Must hold [writeMutex]. Returns false (and kills the socket) on failure. */
    private fun writeFrameLocked(msg: Map<String, Any?>): Boolean {
        val out = output ?: return false
        return try {
            writeFrameTo(out, msg)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            closeSocket() // read loop will notice and reconnect
            false
        }
    }

    private fun writeFrameTo(out: DataOutputStream, msg: Map<String, Any?>) {
        val body = CborLite.encode(msg)
        if (body.size > MAX_FRAME) throw Exception("frame too large (${body.size})")
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): Map<String, Any?> {
        val len = input.readInt()
        if (len <= 0 || len > MAX_FRAME) throw Exception("bad frame length $len")
        val body = ByteArray(len)
        input.readFully(body)
        return CborLite.decodeMap(body)
    }

    private fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(64)

    companion object {
        private const val TAG = "ControlChannel"
        const val PORT_DEFAULT = 4906
        const val PROTO_VERSION = 1L
        private const val MAX_FRAME = 4096
        private const val NONCE_LEN = 16
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val MAX_STR = 200
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val STABLE_CONNECTION_MS = 60_000L
        private val DOMAIN_SRV = "slink-srv".toByteArray(Charsets.US_ASCII)
        private val DOMAIN_CLI = "slink-cli".toByteArray(Charsets.US_ASCII)
        // Capabilities this app version speaks. The custom link is flavor-
        // INDEPENDENT — both foss and play ship exactly these.
        private val CLIENT_CAPS = listOf("media", "np", "cmd", "spectrum-udp", "pcm-party")

        // Mirror CommandUdpListener's capture-gating opcodes.
        private const val CMD_PAUSE_CAPTURE = 7
        private const val CMD_RESUME_CAPTURE = 8
    }
}
