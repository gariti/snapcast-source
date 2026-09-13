package com.lattice.app

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
 * or read EOF and the client reconnects with capped exponential backoff, cut
 * short by any network change (see NetworkMonitor) so a VPN or Wi-Fi coming
 * back re-dials at once instead of sitting out the rest of a 5-minute sleep.
 *
 * Reconnects RACE every known address of the desktop (see [LinkHosts] and
 * [connectFirstOf]) instead of trusting one: tailnet name and LAN IP are dialled
 * together and whichever completes the handshake first wins. So the link
 * survives the tunnel dropping while at home, survives leaving the house on
 * cellular, and needs no notion of "which network am I on" — it re-decides on
 * every reconnect.
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
    context: Context,
    private val scope: CoroutineScope,
    hosts: List<String>,
    private val port: Int,
    private val normalizedPsk: String,
) {
    // Application context: the reference is held for the life of the reconnect
    // loop, which is longer than any single onStartCommand.
    private val appContext = context.applicationContext

    /** Every address the desktop may answer on, in head-start order. */
    private val hosts = hosts.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /**
     * The address that last completed a handshake, tried first on the next
     * reconnect so the usual case ends the race before a second socket opens.
     * Deliberately in-memory and deliberately cleared on any network change —
     * after a route change it is precisely the address most likely to be wrong.
     */
    @Volatile
    private var lastGood: String? = null

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

    /** Last resolved artwork, keyed by the URI it came from. */
    private var artCache: Pair<String, ArtLoader.Art?>? = null

    /** Art keys already shipped on THIS connection; cleared on disconnect. */
    private val sentArtKeys = mutableSetOf<String>()

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

        // Resolve device-local artwork BEFORE taking the write lock: reading the
        // provider and possibly re-encoding a JPEG is slow, and stalling every
        // other frame behind it would make the link look wedged. Remote (http)
        // art resolves to null here — the desktop fetches those URLs itself.
        val art = resolveArt(s.artUri)

        return writeMutex.withLock {
            val key = listOf(
                s.isPlaying, s.title, s.artist, s.app, s.artUri,
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
                    "art" to s.artUri.take(MAX_URL),
                    // Content hash of the blob that follows. The desktop keys its
                    // art cache on this, so an unchanged cover costs one string.
                    "artkey" to (art?.key ?: ""),
                    "vol" to s.volumePercent.coerceIn(0, 100).toLong(),
                    "ctl" to s.volumeControllable,
                    "remote" to s.volumeRemote,
                )
            )
            if (ok) lastNp = key
            // Ship the bytes after the np frame so the desktop already knows which
            // key it is waiting for. Once per key per connection — the desktop
            // caches to disk, and a reconnect re-sends (cheap, and far simpler
            // than negotiating what the other side still has).
            if (ok && art != null && sentArtKeys.add(art.key)) {
                if (!sendArtLocked(art)) sentArtKeys.remove(art.key)
            }
            ok
        }
    }

    /**
     * Load artwork for [uri], reusing the last result when the URI is unchanged.
     * Without this cache every now-playing tick (position, volume…) would re-read
     * and re-hash the same cover.
     */
    private fun resolveArt(uri: String): ArtLoader.Art? {
        if (!ArtLoader.isLocal(uri)) return null
        artCache?.let { (cachedUri, cachedArt) -> if (cachedUri == uri) return cachedArt }
        val art = ArtLoader.load(appContext, uri)
        artCache = uri to art
        return art
    }

    /**
     * Send artwork as `{t:"art", key, i, n, d}` chunks. Caller must hold the write
     * lock. Chunked rather than sent whole because MAX_FRAME is a deliberate
     * anti-DoS bound on both ends — raising it for one bulk payload would weaken
     * every other frame's guarantee.
     */
    private fun sendArtLocked(art: ArtLoader.Art): Boolean {
        val total = (art.bytes.size + ART_CHUNK - 1) / ART_CHUNK
        if (total <= 0 || total > ART_MAX_CHUNKS) {
            Log.w(TAG, "art ${art.key} needs $total chunks, refusing")
            return false
        }
        for (i in 0 until total) {
            val from = i * ART_CHUNK
            val to = minOf(from + ART_CHUNK, art.bytes.size)
            val ok = writeFrameLocked(
                mapOf(
                    "t" to "art",
                    "key" to art.key,
                    "i" to i.toLong(),
                    "n" to total.toLong(),
                    "d" to art.bytes.copyOfRange(from, to),
                )
            )
            if (!ok) {
                Log.w(TAG, "art ${art.key} send failed at chunk $i/$total")
                return false
            }
        }
        Log.i(TAG, "sent art ${art.key} (${art.bytes.size}B in $total chunks)")
        return true
    }

    // ---- connection loop --------------------------------------------------

    private suspend fun runLoop() {
        if (normalizedPsk.isEmpty()) {
            Log.w(TAG, "no pairing code set — control channel disabled (fail closed)")
            _state.value = LinkState.Disconnected
            return
        }
        if (hosts.isEmpty()) {
            Log.w(TAG, "no desktop address configured — control channel disabled")
            _state.value = LinkState.Disconnected
            return
        }
        // Wake on network changes as well as on the timer. Without this the loop
        // is blind to the one event that most often makes a dead route work —
        // the VPN carrying the desktop coming back up — and sits out the rest of
        // a backoff that may already be at its 5-minute ceiling.
        val network = NetworkMonitor.subscribe(appContext)
        try {
            var backoffMs = INITIAL_BACKOFF_MS
            while (scope.isActive && job?.isActive == true) {
                _state.value = LinkState.Connecting
                val connectedAt = System.currentTimeMillis()
                // Anything that changed before this attempt is already baked into
                // it; only a change from here on justifies cutting the wait short.
                network.drainPending()
                try {
                    runSession(connectFirstOf(candidates()))
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
                if (network.awaitChange(backoffMs)) {
                    // New route: the old backoff was measuring a path that no
                    // longer exists, so start over from the short interval — and
                    // re-race blind, since the winner of the last race was picked
                    // on a network we may no longer be on.
                    Log.i(TAG, "network changed — retrying link now")
                    lastGood = null
                    backoffMs = INITIAL_BACKOFF_MS
                }
            }
        } finally {
            network.close()
        }
    }

    /** A handshaken, authenticated connection to one specific address. */
    private class Session(
        val host: String,
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
        val proto: Long,
        val caps: Set<String>,
    )

    /** Dial order: whatever worked last, then the configured addresses. */
    private fun candidates(): List<String> {
        val good = lastGood
        return if (good == null || hosts.firstOrNull() == good) hosts
        else listOf(good) + hosts.filter { it != good }
    }

    /**
     * Happy eyeballs: dial every candidate address and keep the first that
     * completes the FULL handshake, then abort the rest.
     *
     * Racing is what makes the link independent of which network the phone is
     * on. At home with the tunnel down the LAN address wins; on cellular the LAN
     * addresses fail (usually instantly, ENETUNREACH) and the tailnet name wins;
     * with everything up, whichever is quicker wins and the answer costs nothing
     * to be wrong about, because it is re-decided on the next reconnect.
     *
     * The race is decided on the HANDSHAKE, never on the TCP connect. Someone
     * else on the LAN — or a device that inherited the desktop's old DHCP lease —
     * can accept a connection instantly and would win a connect-only race,
     * costing us the real desktop's link. It cannot produce the PSK proof, so
     * here it just loses. (This is also why the PCM path in TcpStreamer is NOT
     * raced: it has no handshake, so "first to accept" is all a race could ever
     * mean there, and that is not a safe thing to hand a microphone stream to.)
     *
     * Staggering means the head candidate usually finishes before the second
     * socket is even opened, so the common case still costs exactly one dial.
     */
    private suspend fun connectFirstOf(candidates: List<String>): Session = coroutineScope {
        val opened = CopyOnWriteArrayList<Socket>()
        // Set BEFORE the sweep below, so a socket created concurrently with the
        // sweep is closed by whichever of the two observes the other.
        val decided = AtomicBoolean(false)
        val results = Channel<Result<Session>>(Channel.UNLIMITED)

        candidates.forEachIndexed { i, candidate ->
            launch(Dispatchers.IO) {
                if (i > 0) delay(i * CANDIDATE_STAGGER_MS)
                results.send(runCatching { openSession(candidate, opened, decided) })
            }
        }

        var winner: Session? = null
        val failures = mutableListOf<String>()
        try {
            var pending = candidates.size
            while (pending > 0 && winner == null) {
                pending--
                results.receive()
                    .onSuccess { winner = it }
                    .onFailure { failures += it.message ?: it.toString() }
            }
        } finally {
            // Also runs when the whole client is being torn down mid-race, in
            // which case there is no winner and every socket is a loser.
            decided.set(true)
            coroutineContext.cancelChildren()
            // Closing a loser's socket is what actually unblocks it — it may be
            // parked in a blocking connect or read, which coroutine cancellation
            // cannot touch, but which Socket.close() is documented to break with
            // a SocketException. Without this the scope would not join until the
            // losers timed out, delaying the WINNER's link by up to 10 s.
            val kept = winner?.socket
            opened.forEach { if (it !== kept) runCatching { it.close() } }
        }

        winner ?: throw Exception("no address answered — ${failures.joinToString("; ")}")
    }

    /**
     * Blocking connect + mutual-auth handshake against ONE address. Returns only
     * when the desktop has proven it holds the PSK and sent "ready".
     */
    private fun openSession(
        candidate: String,
        opened: MutableList<Socket>,
        decided: AtomicBoolean,
    ): Session {
        val sock = Socket()
        opened.add(sock)
        if (decided.get()) {
            // Another candidate won while this socket was being created.
            runCatching { sock.close() }
            throw Exception("$candidate: race already decided")
        }
        try {
            sock.connect(InetSocketAddress(candidate, port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            val input = DataInputStream(sock.getInputStream().buffered())
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
            return Session(candidate, sock, input, out, proto, caps)
        } catch (e: Exception) {
            runCatching { sock.close() }
            // Keep the address in the message: the failure that matters is
            // usually "which one of them died, and how".
            throw Exception("$candidate: ${e.message}")
        }
    }

    /** Install the winning session and pump it until EOF/error. */
    private suspend fun runSession(session: Session) {
        writeMutex.withLock {
            socket = session.socket
            output = session.output
            lastMedia = null
            lastNp = null
            // The desktop's art cache is per-run, and a fresh daemon has an empty
            // one — so treat every connection as needing the blob re-sent. The
            // artCache (URI -> bytes) survives; only the "already shipped" set
            // resets, so this costs a re-send, not a re-decode.
            sentArtKeys.clear()
        }
        lastGood = session.host
        _state.value = LinkState.Connected(session.proto, session.caps)
        Log.i(TAG, "linked to ${session.host}:$port proto=${session.proto} caps=${session.caps}")

        // Resume/reconnect refresh: the desktop keeps whatever we last
        // pushed, so a wake with no track change would leave it showing the
        // OLD song. Dedup was just cleared above, so proactively re-push the
        // current media + now-playing snapshot — the desktop syncs
        // immediately instead of waiting for the next track change.
        val snap = MediaSessionListener.state.value
        sendMedia(snap.isPlaying, snap.sessionCount)
        sendNowPlaying(snap)

        val input = session.input
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
        // Head start for each earlier candidate in the race. Long enough that a
        // working first address usually wins before the next socket opens, short
        // enough to be invisible when it doesn't.
        private const val CANDIDATE_STAGGER_MS = 250L
        private const val MAX_STR = 200
        // Art URLs run longer than titles but must stay well under MAX_FRAME.
        private const val MAX_URL = 1024
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L
        private const val STABLE_CONNECTION_MS = 60_000L
        private val DOMAIN_SRV = "slink-srv".toByteArray(Charsets.US_ASCII)
        private val DOMAIN_CLI = "slink-cli".toByteArray(Charsets.US_ASCII)
        // Capabilities this app version speaks. The custom link is flavor-
        // INDEPENDENT — both foss and play ship exactly these.
        private val CLIENT_CAPS = listOf("media", "np", "cmd", "spectrum-udp", "pcm-party", "art")

        /**
         * Art chunk payload. Must leave room for the frame's other keys inside
         * MAX_FRAME — the map overhead ("t"/"key"/"i"/"n"/"d" plus the 16-char
         * key) is well under 100 bytes, so 3072 has ample margin.
         */
        private const val ART_CHUNK = 3072

        /** Ceiling on chunks per cover; ART_CHUNK * this bounds the transfer. */
        private const val ART_MAX_CHUNKS = 512

        // Mirror CommandUdpListener's capture-gating opcodes.
        private const val CMD_PAUSE_CAPTURE = 7
        private const val CMD_RESUME_CAPTURE = 8
    }
}
