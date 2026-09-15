package com.lattice.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PREFS_NAME = Prefs.FILE
private const val KEY_SLOT = "slot_index"
private const val KEY_HOST = "host"
private const val KEY_PARTY_MODE = "party_mode"
private const val KEY_PSK = PairingCrypto.PREFS_KEY_PSK

val SLOT_PORTS = listOf(4953, 4954, 4955, 4956)
const val VIZ_PORT = 4900           // legacy TCP PCM viz path (snapcast-viz-tap)
const val SPECTRUM_PORT = 4901      // Solo Listening UDP spectrum (phone-spectrum-tap)

// Default target: the desktop's Tailscale MagicDNS name. The link rides the
// tailnet (WireGuard-encrypted + device-authenticated) from anywhere; same-LAN
// plain IP remains a manual opt-in by simply typing the LAN IP here instead.
const val DEFAULT_HOST = "nixos-1.tailb7f992.ts.net"

fun portToSlot(port: Int): Int? {
    val idx = SLOT_PORTS.indexOf(port)
    return if (idx >= 0) idx + 1 else null
}

class MainActivity : ComponentActivity() {

    private var pendingHost: String = ""
    private var pendingPort: Int = SLOT_PORTS[0]
    private var pendingMode: String = "party"

    /** Lives between onResume and onPause; see [armUnlockPrompt]. */
    private var unlockWatch: Job? = null
    /** The last unlock challenge this activity raised the prompt for. */
    private var lastAutoPrompted: String? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", data)
                putExtra("host", pendingHost)
                putExtra("port", pendingPort)
                putExtra("mode", pendingMode)
            }
            startForegroundService(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The canvas runs under the status bar; the rail, the title strip and
        // the sheets each take the insets they need. targetSdk 35 forces this
        // on Android 15 regardless — calling it makes 14 behave the same way
        // instead of silently resizing the window underneath us.
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        // QR device flow: a lattice://pair deep link (scanned with the stock
        // camera) lands here on cold start. Must run BEFORE the prefs reads
        // below so the freshly stored code/host seed the UI's initial values.
        handlePairIntent(intent)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialSlotIdx = prefs.getInt(KEY_SLOT, 0).coerceIn(0, SLOT_PORTS.size - 1)
        val initialHost = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val initialPartyMode = prefs.getBoolean(KEY_PARTY_MODE, false)
        val initialPsk = prefs.getString(KEY_PSK, "") ?: ""
        val initialMirrorFps = prefs.getInt(Prefs.KEY_MIRROR_FPS, Prefs.DEFAULT_MIRROR_FPS).coerceIn(1, 15)
        val initialMirrorIdle = prefs.getInt(Prefs.KEY_MIRROR_IDLE_S, Prefs.DEFAULT_MIRROR_IDLE_S).coerceIn(0, 3600)
        val initialMirrorOnMetered = prefs.getBoolean(Prefs.KEY_MIRROR_ON_METERED, false)
        val initialTermZoom = prefs.getFloat(Prefs.KEY_TERM_ZOOM, 1f).coerceIn(TERM_ZOOM_MIN, TERM_ZOOM_MAX)

        // Persist the default host on first launch so MediaSessionBeaconService
        // (which reads prefs directly, not the in-memory UI state) can find it.
        if (!prefs.contains(KEY_HOST)) {
            prefs.edit().putString(KEY_HOST, initialHost).apply()
        }

        val appState = AppState(
            host = initialHost, slotIndex = initialSlotIdx, partyMode = initialPartyMode, psk = initialPsk,
            mirrorFps = initialMirrorFps, mirrorIdleSeconds = initialMirrorIdle, mirrorOnMetered = initialMirrorOnMetered,
            termZoom = initialTermZoom,
            onHostChange = { host -> prefs.edit().putString(KEY_HOST, host).apply() },
            onSlotChange = { idx -> prefs.edit().putInt(KEY_SLOT, idx).apply() },
            onPartyModeChange = { enabled -> prefs.edit().putBoolean(KEY_PARTY_MODE, enabled).apply() },
            // Store the canonical (normalized) code so the HMAC key matches the
            // desktop byte-for-byte.
            onPskChange = { psk -> prefs.edit().putString(KEY_PSK, PairingCrypto.normalize(psk)).apply() },
            onMirrorFpsChange = { fps -> prefs.edit().putInt(Prefs.KEY_MIRROR_FPS, fps).apply() },
            onMirrorIdleChange = { s -> prefs.edit().putInt(Prefs.KEY_MIRROR_IDLE_S, s).apply() },
            onMirrorOnMeteredChange = { on -> prefs.edit().putBoolean(Prefs.KEY_MIRROR_ON_METERED, on).apply() },
            onTermZoomChange = { z -> prefs.edit().putFloat(Prefs.KEY_TERM_ZOOM, z).apply() },
            onStartCapture = ::startCapture,
            onStopCapture = ::stopCapture,
        )

        setContent {
            LatticeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LatticeApp(appState)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Auto-start the cast-detection beacon whenever host is set AND
        // Notification Access is granted. Stays running until the user revokes
        // either condition; safe to call repeatedly (startForegroundService is
        // idempotent on the same component).
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, "") ?: ""
        if (host.isNotBlank() && MediaSessionListener.isAccessGranted(this)) {
            MediaSessionBeaconService.start(this)
        }
        armUnlockPrompt()
    }

    override fun onPause() {
        super.onPause()
        unlockWatch?.cancel()
        unlockWatch = null
    }

    /**
     * The desktop's lock screen no longer buzzes the phone (see
     * [DesktopAuth.dispatch]); opening the app is the gesture instead. If the
     * desktop is locked, hand straight over to the fingerprint.
     *
     * The window exists because of a cold start: the control channel belongs to
     * MediaSessionBeaconService and the `authq` usually lands a second or two
     * after the UI does, so a one-shot read at onResume would miss it. Watching
     * rather than polling also means an unlock that starts while you are already
     * looking at the app is picked up.
     *
     * [lastAutoPrompted] is what stops a loop: backing out of the prompt returns
     * here, onResume runs again, and the challenge is still open — but it is the
     * same id, so it is not raised a second time. A fresh one (the desktop
     * re-arms roughly once a minute) is a different id and does count.
     */
    private fun armUnlockPrompt() {
        unlockWatch?.cancel()
        unlockWatch = lifecycleScope.launch {
            val c = withTimeoutOrNull(UNLOCK_WATCH_MS) {
                DesktopAuth.pending
                    .map { DesktopAuth.openUnlock() }
                    .firstOrNull { it != null && it.id != lastAutoPrompted }
            } ?: return@launch
            lastAutoPrompted = c.id
            startActivity(AuthPromptActivity.intent(this@MainActivity, c.id))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm path: app already open when the QR was scanned. Store + verify,
        // then recreate so the Compose fields re-seed from the new prefs.
        if (handlePairIntent(intent)) recreate()
    }

    /**
     * QR device flow: parse lattice://pair?v=1&code=…&host=<magicdns>&lan=<ip>
     * (rendered by the desktop pairing script). Synchronously
     * stores the pairing code + a provisional host, then asynchronously proves
     * each candidate host via the 4905 HMAC challenge-response and keeps the
     * first that answers — tailnet name preferred, LAN fallback. Returns true
     * if the intent was a pair link.
     */
    private fun handlePairIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "lattice" || uri.host != "pair") return false

        val code = PairingCrypto.normalize(uri.getQueryParameter("code") ?: "")
        if (code.isEmpty()) {
            _pairStatus.value = "QR is missing the pairing code — regenerate it on the desktop."
            return true
        }
        val tsHost = uri.getQueryParameter("host")?.trim() ?: ""
        // `lan` may be a comma-separated list (multi-homed desktop, e.g.
        // wired + wifi) — try each in order.
        val lanHosts = (uri.getQueryParameter("lan") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }
        val vrfyPort = uri.getQueryParameter("vrfy")?.toIntOrNull() ?: DEFAULT_VRFY_PORT

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val provisional = tsHost.ifBlank { lanHosts.firstOrNull() ?: "" }
        prefs.edit().apply {
            putString(KEY_PSK, code)
            if (provisional.isNotBlank()) putString(KEY_HOST, provisional)
            apply()
        }
        // Keep the LAN addresses too, don't just verify and discard them: the
        // control channel races them against the tailnet name on every
        // reconnect, which is what keeps the link alive at home when the tunnel
        // drops. The primary stays the tailnet name — it is the one that also
        // works away from home.
        LinkHosts.storeLan(this, lanHosts)
        _pairStatus.value = "QR scanned — verifying desktop…"

        val appCtx = applicationContext
        val candidates = (listOf(tsHost) + lanHosts).filter { it.isNotBlank() }.distinct()
        // Process-level scope: survives the recreate() on the warm path.
        pairScope.launch {
            var chosen: String? = null
            for (c in candidates) {
                if (PairingVerifier.verify(c, vrfyPort, code)) {
                    chosen = c
                    break
                }
            }
            if (chosen != null) {
                // Only fall back to storing the LAN winner as the primary when
                // there is no tailnet name at all. Otherwise the tailnet name
                // stays primary even if it didn't answer just now (the tunnel
                // may simply be down) — it is the address that works away from
                // home, and the LAN winner is already a raced candidate.
                if (tsHost.isBlank()) prefs.edit().putString(KEY_HOST, chosen).apply()
                val via = if (chosen == tsHost) "Tailscale" else "LAN"
                _pairStatus.value = "Paired ✓ — desktop at $chosen ($via)"
                // Bounce the beacon so it picks up the new host + code now.
                if (MediaSessionListener.isAccessGranted(appCtx)) {
                    MediaSessionBeaconService.stop(appCtx)
                    MediaSessionBeaconService.start(appCtx)
                }
            } else if (provisional.isNotBlank()) {
                _pairStatus.value = "Code + host saved ($provisional), but the desktop " +
                    "didn't answer verification — is it on and on the same network/tailnet?"
            } else {
                _pairStatus.value = "Code saved. QR had no host — set the host field manually."
            }
        }
        return true
    }

    private fun startCapture(host: String, port: Int, mode: String) {
        pendingHost = host
        pendingPort = port
        pendingMode = mode
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, AudioCaptureService::class.java))
    }

    companion object {
        const val DEFAULT_VRFY_PORT = 4905

        /** How long after opening the app an `unlock` challenge still counts as
         *  "you opened the app to unlock the desktop". */
        private const val UNLOCK_WATCH_MS = 20_000L

        private val pairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Latest QR-pairing outcome, shown by DiscoveryCard.
        private val _pairStatus = MutableStateFlow<String?>(null)
        val pairStatus: StateFlow<String?> = _pairStatus.asStateFlow()
    }
}
