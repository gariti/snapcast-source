package com.slowshell.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "slowshell_app_prefs"
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }

        // QR device flow: a slowshell://pair deep link (scanned with the stock
        // camera) lands here on cold start. Must run BEFORE the prefs reads
        // below so the freshly stored code/host seed the UI's initial values.
        handlePairIntent(intent)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialSlotIdx = prefs.getInt(KEY_SLOT, 0).coerceIn(0, SLOT_PORTS.size - 1)
        val initialHost = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val initialPartyMode = prefs.getBoolean(KEY_PARTY_MODE, false)
        val initialPsk = prefs.getString(KEY_PSK, "") ?: ""

        // Persist the default host on first launch so MediaSessionBeaconService
        // (which reads prefs directly, not the in-memory UI state) can find it.
        if (!prefs.contains(KEY_HOST)) {
            prefs.edit().putString(KEY_HOST, initialHost).apply()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SnapcastSourceScreen(
                        initialHost = initialHost,
                        initialSlotIndex = initialSlotIdx,
                        initialPartyMode = initialPartyMode,
                        initialPsk = initialPsk,
                        onSlotChange = { idx ->
                            prefs.edit().putInt(KEY_SLOT, idx).apply()
                        },
                        onHostChange = { host ->
                            prefs.edit().putString(KEY_HOST, host).apply()
                        },
                        onPskChange = { psk ->
                            // Store the canonical (normalized) code so the HMAC
                            // key matches the desktop byte-for-byte.
                            prefs.edit().putString(KEY_PSK, PairingCrypto.normalize(psk)).apply()
                        },
                        onPartyModeChange = { enabled ->
                            prefs.edit().putBoolean(KEY_PARTY_MODE, enabled).apply()
                        },
                        onStart = ::startCapture,
                        onStop = ::stopCapture
                    )
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Warm path: app already open when the QR was scanned. Store + verify,
        // then recreate so the Compose fields re-seed from the new prefs.
        if (handlePairIntent(intent)) recreate()
    }

    /**
     * QR device flow: parse slowshell://pair?v=1&code=…&host=<magicdns>&lan=<ip>
     * (rendered by the desktop's `slowshell-pairing-code`). Synchronously
     * stores the pairing code + a provisional host, then asynchronously proves
     * each candidate host via the 4905 HMAC challenge-response and keeps the
     * first that answers — tailnet name preferred, LAN fallback. Returns true
     * if the intent was a pair link.
     */
    private fun handlePairIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "slowshell" || uri.host != "pair") return false

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
                prefs.edit().putString(KEY_HOST, chosen).apply()
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

        private val pairScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Latest QR-pairing outcome, shown by DiscoveryCard.
        private val _pairStatus = MutableStateFlow<String?>(null)
        val pairStatus: StateFlow<String?> = _pairStatus.asStateFlow()
    }
}

@Composable
fun SnapcastSourceScreen(
    initialHost: String,
    initialSlotIndex: Int,
    initialPartyMode: Boolean,
    initialPsk: String,
    onSlotChange: (Int) -> Unit,
    onHostChange: (String) -> Unit,
    onPskChange: (String) -> Unit,
    onPartyModeChange: (Boolean) -> Unit,
    onStart: (String, Int, String) -> Unit,
    onStop: () -> Unit
) {
    var host by remember { mutableStateOf(initialHost) }
    var slotIndex by remember { mutableIntStateOf(initialSlotIndex) }
    var partyMode by remember { mutableStateOf(initialPartyMode) }
    var psk by remember { mutableStateOf(initialPsk) }

    val state by AudioCaptureService.state.collectAsState()
    val streaming = state !is ConnectionState.Idle && state !is ConnectionState.Failed
    val editable = !streaming

    val scope = rememberCoroutineScope()
    var snapStatus by remember { mutableStateOf<SnapStatus?>(null) }
    var snapError by remember { mutableStateOf<String?>(null) }
    var snapLoading by remember { mutableStateOf(false) }

    fun refreshClients() {
        if (host.isBlank()) return
        snapLoading = true
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) { SnapcastRpc(host).getStatus() }
                snapStatus = s
                snapError = null
            } catch (e: Exception) {
                snapError = e.message ?: "Failed to talk to snapserver"
            } finally {
                snapLoading = false
            }
        }
    }

    fun setClientStream(client: SnapClient, streamId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SnapcastRpc(host).setGroupStream(client.groupId, streamId)
                }
                snapStatus = snapStatus?.copy(
                    clients = snapStatus!!.clients.map {
                        if (it.groupId == client.groupId) it.copy(streamId = streamId) else it
                    }
                )
            } catch (e: Exception) {
                snapError = e.message ?: "Failed to set stream"
            }
        }
    }

    fun toggleClientMute(client: SnapClient) {
        val newMuted = !client.muted
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SnapcastRpc(host).setClientMute(client.id, newMuted, client.volumePercent)
                }
                snapStatus = snapStatus?.copy(
                    clients = snapStatus!!.clients.map {
                        if (it.id == client.id) it.copy(muted = newMuted) else it
                    }
                )
            } catch (e: Exception) {
                snapError = e.message ?: "Failed to mute client"
            }
        }
    }

    LaunchedEffect(host) { refreshClients() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SlowShell",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                onHostChange(it)
            },
            label = { Text("Desktop host (MagicDNS name or IP)") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )

        DiscoveryCard(
            psk = psk,
            enabled = editable,
            onPskChange = {
                psk = it
                onPskChange(it)
            },
            onHostResolved = { resolvedIp ->
                host = resolvedIp
                onHostChange(resolvedIp)
            },
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Mode", style = MaterialTheme.typography.labelMedium)
            ModePicker(
                partyMode = partyMode,
                enabled = editable,
                onSelect = { isParty ->
                    partyMode = isParty
                    onPartyModeChange(isParty)
                }
            )
            Text(
                if (partyMode)
                    "Party — broadcast PCM to snapserver speakers"
                else
                    "Solo — on-device FFT, UDP spectrum to laptop visualizer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (partyMode) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Slot (party-mode lane)",
                    style = MaterialTheme.typography.labelMedium
                )
                SlotPicker(
                    selectedIndex = slotIndex,
                    enabled = editable,
                    onSelect = {
                        slotIndex = it
                        onSlotChange(it)
                    }
                )
            }
            MediaVolumeSlider()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val port = if (partyMode) SLOT_PORTS[slotIndex] else SPECTRUM_PORT
                    val mode = if (partyMode) "party" else "solo"
                    onStart(host, port, mode)
                },
                enabled = !streaming
            ) { Text("Start") }

            OutlinedButton(
                onClick = onStop,
                enabled = streaming
            ) { Text("Stop") }
        }

        StatusCard(state)

        CastDetectionCard(hostBlank = host.isBlank())

        ClientsCard(
            status = snapStatus,
            error = snapError,
            loading = snapLoading,
            onRefresh = ::refreshClients,
            onSetStream = ::setClientStream,
            onToggleMute = ::toggleClientMute
        )
    }
}

@Composable
fun DiscoveryCard(
    psk: String,
    enabled: Boolean,
    onPskChange: (String) -> Unit,
    onHostResolved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val qrPairStatus by MainActivity.pairStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Pair with desktop", style = MaterialTheme.typography.titleMedium)
        Text(
            "Easiest: run `slowshell-pairing-code` on the desktop and point this " +
                "phone's CAMERA app at the QR — it fills everything in and verifies " +
                "the desktop cryptographically. Manual fallback: type the code below " +
                "and tap Find (mDNS + the same challenge-response).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        qrPairStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Paired"))
                    Color(0xFF4CAF50)
                else
                    MaterialTheme.colorScheme.outline,
            )
        }
        OutlinedTextField(
            value = psk,
            onValueChange = onPskChange,
            label = { Text("Pairing code") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                searching = true
                status = "Searching…"
                scope.launch {
                    val normalized = PairingCrypto.normalize(psk)
                    if (normalized.isEmpty()) {
                        status = "Enter the pairing code first."
                        searching = false
                        return@launch
                    }
                    val candidates = DesktopDiscovery(context).discover()
                    if (candidates.isEmpty()) {
                        status = "No SlowShell desktop found on this network."
                        searching = false
                        return@launch
                    }
                    var paired: String? = null
                    for (c in candidates) {
                        if (PairingVerifier.verify(c.host, c.vrfyPort, normalized)) {
                            paired = c.host
                            break
                        }
                    }
                    status = if (paired != null) {
                        onHostResolved(paired)
                        "Paired ✓ — desktop at $paired"
                    } else {
                        "Found a desktop but the pairing code didn't match. " +
                            "Re-check the code on the desktop (slowshell-pairing-code)."
                    }
                    searching = false
                }
            },
            enabled = enabled && !searching,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (searching) "Searching…" else "Find desktop") }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun CastDetectionCard(hostBlank: Boolean) {
    val context = LocalContext.current
    val listenerState by MediaSessionListener.state.collectAsState()
    val beaconState by MediaSessionBeaconService.serviceState.collectAsState()

    // Recompute "access granted" on every recomposition so returning from
    // Settings reflects the new state without an extra observer.
    var accessGranted by remember { mutableStateOf(MediaSessionListener.isAccessGranted(context)) }
    LaunchedEffect(Unit) { accessGranted = MediaSessionListener.isAccessGranted(context) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Cast detection", style = MaterialTheme.typography.titleMedium)

        if (!accessGranted) {
            Text(
                "Notification Access needed to detect music playing through cast targets " +
                    "(KEF, Chromecast, AirPlay). Without it, the visualizer can't tell " +
                    "you're playing music when audio bypasses the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        // Some OEMs honor this extra to scroll to the app's row.
                        putExtra(
                            ":settings:fragment_args_key",
                            "com.slowshell.app/com.slowshell.app.MediaSessionListener"
                        )
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Notification Access settings") }
        } else if (hostBlank) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF888888))
                )
                Spacer(Modifier.width(8.dp))
                Text("Access granted — set host above to start beacon")
            }
        } else {
            val running = beaconState is MediaSessionBeaconService.ServiceState.Running
            val dotColor = when {
                running && listenerState.isPlaying -> Color(0xFF4CAF50)  // green
                running -> Color(0xFF1976D2)                              // blue (running, idle)
                else -> Color(0xFF888888)                                 // gray
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                val label = when {
                    running && listenerState.isPlaying -> "Playing — ${listenerState.sessionCount} session(s)"
                    running -> "Listening — ${listenerState.sessionCount} session(s), none playing"
                    else -> "Beacon not running"
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            val running2 = beaconState
            if (running2 is MediaSessionBeaconService.ServiceState.Running) {
                Text(
                    "→ ${running2.host}:${running2.port}  · packets: ${running2.packetsSent}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    if (running2.linkConnected)
                        "Link: control channel (authenticated, event-driven)"
                    else
                        "Link: legacy UDP beacon (control channel down — check pairing code / desktop)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running2.linkConnected)
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.outline,
                )
            }
            if (!listenerState.listenerConnected) {
                Text(
                    "Listener disconnected — try toggling Notification Access off and on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ClientsCard(
    status: SnapStatus?,
    error: String?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSetStream: (SnapClient, String) -> Unit,
    onToggleMute: (SnapClient) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Clients", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "Loading…" else "Refresh")
            }
        }

        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        val clients = status?.clients
        val streams = status?.streamIds ?: emptyList()

        when {
            status == null && !loading && error == null ->
                Text(
                    "Set the host above to load clients.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            clients != null && clients.isEmpty() ->
                Text(
                    "No snapclients connected to this server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            clients != null -> clients.forEach { client ->
                ClientRow(
                    client = client,
                    streams = streams,
                    onSetStream = { sid -> onSetStream(client, sid) },
                    onToggleMute = { onToggleMute(client) }
                )
            }
        }
    }
}

@Composable
fun ClientRow(
    client: SnapClient,
    streams: List<String>,
    onSetStream: (String) -> Unit,
    onToggleMute: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (client.connected) Color(0xFF4CAF50) else Color(0xFF888888))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    client.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (client.connected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
            OutlinedButton(
                onClick = onToggleMute,
                colors = if (client.muted) {
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                }
            ) {
                Text(if (client.muted) "Muted" else "Audible")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            streams.forEach { sid ->
                val selected = sid == client.streamId
                if (selected) {
                    Button(
                        onClick = { onSetStream(sid) },
                        modifier = Modifier.weight(1f)
                    ) { Text(sid) }
                } else {
                    OutlinedButton(
                        onClick = { onSetStream(sid) },
                        modifier = Modifier.weight(1f)
                    ) { Text(sid) }
                }
            }
        }
    }
}

@Composable
fun MediaVolumeSlider() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION" &&
                    intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC
                ) {
                    vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Mix volume (this device)",
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = vol,
            onValueChange = { newVal ->
                vol = newVal
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    newVal.toInt().coerceIn(0, maxVol),
                    0
                )
            },
            valueRange = 0f..maxVol.toFloat(),
            steps = (maxVol - 1).coerceAtLeast(0)
        )
        Text(
            "${(vol / maxVol * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ModePicker(
    partyMode: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("Party" to true, "Solo Listening" to false).forEach { (label, isParty) ->
            val selected = partyMode == isParty
            if (selected) {
                Button(
                    onClick = { onSelect(isParty) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(label) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(isParty) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(label) }
            }
        }
    }
}

@Composable
fun SlotPicker(
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SLOT_PORTS.forEachIndexed { idx, port ->
            val selected = idx == selectedIndex
            if (selected) {
                Button(
                    onClick = { onSelect(idx) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("${idx + 1}") }
            } else {
                OutlinedButton(
                    onClick = { onSelect(idx) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("${idx + 1}") }
            }
        }
    }
}

@Composable
fun StatusCard(state: ConnectionState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (label, color) = when (state) {
            is ConnectionState.Idle -> "Idle" to Color(0xFF888888)
            is ConnectionState.Connecting -> "Connecting…" to Color(0xFFFFA000)
            is ConnectionState.Connected -> "Connected" to Color(0xFF4CAF50)
            is ConnectionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})" to Color(0xFFFFA000)
            is ConnectionState.Failed -> "Failed" to Color(0xFFE53935)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .width(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
            Text(
                text = "  $label",
                style = MaterialTheme.typography.titleMedium
            )
        }

        when (state) {
            is ConnectionState.Connecting -> {
                Text(targetLine(state.host, state.port), fontFamily = FontFamily.Monospace)
            }
            is ConnectionState.Connected -> {
                Text(targetLine(state.host, state.port), fontFamily = FontFamily.Monospace)
                Text(
                    "Sent: ${formatBytes(state.bytesSent)}",
                    fontFamily = FontFamily.Monospace
                )
                AudioMeter(rms = state.rms)
            }
            is ConnectionState.Reconnecting -> {
                Text(targetLine(state.host, state.port), fontFamily = FontFamily.Monospace)
                Text(
                    "Last error: ${state.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            is ConnectionState.Failed -> {
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> { /* idle: no extra detail */ }
        }
    }
}

private fun targetLine(host: String, port: Int): String {
    if (port == SPECTRUM_PORT) return "→ $host:$port (Solo / spectrum)"
    if (port == VIZ_PORT) return "→ $host:$port (Visualize PCM)"
    val slot = portToSlot(port)
    return if (slot != null) "→ $host:$port (Slot $slot)" else "→ $host:$port"
}

@Composable
fun AudioMeter(rms: Float) {
    Column {
        Text(
            "Audio level",
            style = MaterialTheme.typography.labelMedium
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = rms.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        if (rms > 0.01f) Color(0xFF4CAF50) else Color(0xFF666666)
                    )
            )
        }
        if (rms < 0.001f) {
            Text(
                "(silence — make sure music is playing)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> "%.2f MB".format(mb)
        kb >= 1.0 -> "%.1f KB".format(kb)
        else -> "$bytes B"
    }
}
