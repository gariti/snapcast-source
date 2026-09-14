package com.lattice.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun SettingsSheetContent(app: AppState) {
    val state by AudioCaptureService.state.collectAsState()
    val streaming = state !is ConnectionState.Idle && state !is ConnectionState.Failed
    val editable = !streaming
    Column(
        // fillMaxWidth, not fillMaxSize: Settings is short and must not render
        // as tall as Audio. SheetHost owns the scrolling.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = app.host,
            onValueChange = {
                app.host = it
                app.onHostChange(it)
            },
            label = { Text("Desktop host (MagicDNS name or IP)") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )

        DiscoveryCard(
            psk = app.psk,
            enabled = editable,
            onPskChange = {
                app.psk = it
                app.onPskChange(it)
            },
            onHostResolved = { resolvedIp ->
                app.host = resolvedIp
                app.onHostChange(resolvedIp)
            },
        )

        CastDetectionCard(hostBlank = app.host.isBlank())
        DesktopAuthCard()
        MirrorSettingsCard(app)
        LinkDiagnosticsCard()
    }
}

@Composable
fun DesktopAuthCard() {
    val ctx = LocalContext.current
    val enrol by DesktopAuth.enrol.collectAsState()
    val err by DesktopAuth.lastError.collectAsState()
    val client by Link.client.collectAsState()
    val st by (client?.state ?: MutableStateFlow(ControlChannelClient.LinkState.Disconnected)).collectAsState()
    val linked = st is ControlChannelClient.LinkState.Connected
    val desktopSpeaksAuth = (st as? ControlChannelClient.LinkState.Connected)?.caps?.contains("auth") == true
    SectionCard("Desktop auth") {
        DimText("Your fingerprint here can answer the desktop's sudo, permission and unlock prompts. The key lives in this phone's secure hardware and only signs after a fingerprint.")
        TextRow("key", when (enrol) {
            DesktopAuth.Enrol.NONE -> "none"
            DesktopAuth.Enrol.PENDING -> "waiting for the desktop"
            DesktopAuth.Enrol.TRUSTED -> "trusted" + (DesktopAuthKey.kid()?.let { " · $it" } ?: "")
            DesktopAuth.Enrol.REVOKED -> "revoked by the desktop"
            DesktopAuth.Enrol.INVALIDATED -> "invalidated (fingerprint added)"
        })
        TextRow("hardware", if (DesktopAuthKey.exists()) (if (DesktopAuthKey.isStrongBox()) "StrongBox" else "TEE") else "—")
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DesktopAuth.enrol(ctx) }, enabled = linked && desktopSpeaksAuth) {
                Text(if (enrol == DesktopAuth.Enrol.TRUSTED) "Enrol a new key" else "Enrol this phone")
            }
            if (DesktopAuthKey.exists()) {
                androidx.compose.material3.OutlinedButton(onClick = { DesktopAuth.forget(ctx) }) { Text("Forget key") }
            }
        }
        if (enrol == DesktopAuth.Enrol.PENDING) {
            DimText("Now on the desktop: run `phone-auth enroll` and touch the sensor. The key is trusted the moment that succeeds.")
        } else if (!desktopSpeaksAuth && linked) {
            DimText("This desktop does not speak auth yet — deploy the phone-link broker first.")
        }
    }
}

@Composable
fun MirrorSettingsCard(app: AppState) {
    SectionCard("Mirror") {
        Text("Frame rate", style = MaterialTheme.typography.labelLarge)
        DimText("Each frame is a capture of the desktop's screen. 4 fps held the GPU at 60–70% while the mirror was open; 2 fps is about half that and still fine for reading.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 4, 8).forEach { f ->
                androidx.compose.material3.FilterChip(
                    selected = app.mirrorFps == f,
                    onClick = { app.mirrorFps = f; app.onMirrorFpsChange(f) },
                    label = { Text("$f fps") },
                )
            }
        }
        Text("Pause when idle", style = MaterialTheme.typography.labelLarge)
        DimText("No touch anywhere in the app for this long pauses the mirror; the next touch resumes it. It always pauses when the app is not in front or the phone is locked.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 0 to "never").forEach { (secs, label) ->
                androidx.compose.material3.FilterChip(
                    selected = app.mirrorIdleSeconds == secs,
                    onClick = { app.mirrorIdleSeconds = secs; app.onMirrorIdleChange(secs) },
                    label = { Text(label) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mirror on mobile data", style = MaterialTheme.typography.labelLarge)
                DimText("About 1 Mbit/s at 4 fps. Off = the mirror pauses on any metered network.")
            }
            Switch(checked = app.mirrorOnMetered, onCheckedChange = { app.mirrorOnMetered = it; app.onMirrorOnMeteredChange(it) })
        }
    }
}

@Composable
fun LinkDiagnosticsCard() {
    val client by Link.client.collectAsState()
    val st by (client?.state ?: kotlinx.coroutines.flow.MutableStateFlow(ControlChannelClient.LinkState.Disconnected)).collectAsState()
    val bridge by (client?.bridgeUp ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val desk by Link.desk.collectAsState()
    SectionCard("Link") {
        val conn = st as? ControlChannelClient.LinkState.Connected
        TextRow("channel", when (st) {
            is ControlChannelClient.LinkState.Connected -> "up"
            ControlChannelClient.LinkState.Connecting -> "connecting"
            else -> "down"
        })
        TextRow("protocol", conn?.proto?.toString() ?: "—")
        TextRow("desktop bridge", if (bridge) "up" else "down")
        TextRow("desktop caps", conn?.caps?.sorted()?.joinToString(" ") ?: "—")
        TextRow("outputs", desk.outputs.joinToString(" ") { it.name })
        TextRow("workspaces · windows", "${desk.workspaces.size} · ${desk.windows.size}")
        DimText("Re-pair any time: Mod+? › Network › Pair a Phone on the desktop, then scan the QR with the camera.")
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
            "Easiest: open the control panel (Mod+?) › Network › Pair a Phone and point this " +
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
                        status = "No Lattice desktop found on this network."
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
                        // mDNS only ever finds it on the local network, and the
                        // PSK challenge above proved it is really the desktop —
                        // so this is a LAN candidate worth racing later.
                        LinkHosts.addLan(context, paired)
                        "Paired ✓ — desktop at $paired"
                    } else {
                        "Found a desktop but the pairing code didn't match. " +
                            "Re-check the code on the desktop (Pair a Phone card)."
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
                            "com.lattice.app/com.lattice.app.MediaSessionListener"
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
