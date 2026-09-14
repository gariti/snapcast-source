package com.lattice.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The snapcast conversation, hoisted out of the sheet body on purpose.
 *
 * The sheet's content is disposed every time you dismiss it. Left inside, each
 * open would re-hit snapserver and flash "Loading…", and a `setStream` fired
 * just before a dismissal would be cancelled mid-RPC along with the composable's
 * scope. Owned by the shell instead, so it lives as long as the app does.
 */
class SnapcastState {
    var status by mutableStateOf<SnapStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun refresh(host: String, scope: CoroutineScope) {
        if (host.isBlank()) return
        loading = true
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { SnapcastRpc(host).getStatus() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Failed to talk to snapserver"
            } finally {
                loading = false
            }
        }
    }

    fun setStream(host: String, client: SnapClient, streamId: String, scope: CoroutineScope) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SnapcastRpc(host).setGroupStream(client.groupId, streamId) }
                status = status?.let { st ->
                    st.copy(clients = st.clients.map { if (it.groupId == client.groupId) it.copy(streamId = streamId) else it })
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to set stream"
            }
        }
    }

    fun toggleMute(host: String, client: SnapClient, scope: CoroutineScope) {
        val newMuted = !client.muted
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SnapcastRpc(host).setClientMute(client.id, newMuted, client.volumePercent) }
                status = status?.let { st ->
                    st.copy(clients = st.clients.map { if (it.id == client.id) it.copy(muted = newMuted) else it })
                }
            } catch (e: Exception) {
                error = e.message ?: "Failed to mute client"
            }
        }
    }
}

@Composable
fun rememberSnapcastState(host: String): SnapcastState {
    val s = remember { SnapcastState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(host) { s.refresh(host, scope) }
    return s
}

@Composable
fun AudioSheetContent(app: AppState, snap: SnapcastState) {
    val host = app.host
    val state by AudioCaptureService.state.collectAsState()
    val streaming = state !is ConnectionState.Idle && state !is ConnectionState.Failed
    val editable = !streaming
    val scope = rememberCoroutineScope()

    Column(
        // fillMaxWidth, not fillMaxSize: the sheet sizes itself to its content,
        // and SheetHost owns the one and only scroller in this layer.
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NowPlayingCard()
        HeadphonesCard()

        SectionCard("Send audio to the desktop") {
            ModePicker(
                partyMode = app.partyMode,
                enabled = editable,
                onSelect = { isParty ->
                    app.partyMode = isParty
                    app.onPartyModeChange(isParty)
                }
            )
            Text(
                if (app.partyMode)
                    "Party — broadcast this phone's audio to the snapserver speakers"
                else
                    "Solo — on-device FFT, spectrum to the desktop visualizer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (app.partyMode) {
                Text("Slot (party-mode lane)", style = MaterialTheme.typography.labelMedium)
                SlotPicker(
                    selectedIndex = app.slotIndex,
                    enabled = editable,
                    onSelect = {
                        app.slotIndex = it
                        app.onSlotChange(it)
                    }
                )
                MediaVolumeSlider()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val port = if (app.partyMode) SLOT_PORTS[app.slotIndex] else SPECTRUM_PORT
                        val mode = if (app.partyMode) "party" else "solo"
                        app.onStartCapture(host, port, mode)
                    },
                    enabled = !streaming
                ) { Text("Start") }

                OutlinedButton(
                    onClick = app.onStopCapture,
                    enabled = streaming
                ) { Text("Stop") }
            }
            StatusCard(state)
        }

        ClientsCard(
            status = snap.status,
            error = snap.error,
            loading = snap.loading,
            onRefresh = { snap.refresh(host, scope) },
            onSetStream = { client, streamId -> snap.setStream(host, client, streamId, scope) },
            onToggleMute = { client -> snap.toggleMute(host, client, scope) },
        )
    }
}

@Composable
fun NowPlayingCard() {
    val ms by MediaSessionListener.state.collectAsState()
    if (ms.title.isBlank() && ms.artist.isBlank()) return
    SectionCard("Now playing on this phone") {
        Text(ms.title.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Text(
            listOf(ms.artist, ms.app).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { MediaSessionListener.applyCommand(MediaSessionListener.CMD_PREV, 0) }, modifier = Modifier.weight(1f)) { Text("⏮") }
            Button(onClick = { MediaSessionListener.applyCommand(MediaSessionListener.CMD_PLAY_PAUSE, 0) }, modifier = Modifier.weight(1f)) { Text(if (ms.isPlaying) "⏸" else "▶") }
            OutlinedButton(onClick = { MediaSessionListener.applyCommand(MediaSessionListener.CMD_NEXT, 0) }, modifier = Modifier.weight(1f)) { Text("⏭") }
        }
        if (ms.volumeRemote) DimText("Playing on a cast target — volume is the target's.")
    }
}

@Composable
fun HeadphonesCard() {
    val ctx = LocalContext.current
    val route by AudioRouteMonitor.route.collectAsState()
    val listening by ListenService.active.collectAsState()
    val client by Link.client.collectAsState()
    val ready = client?.sessionReady == true
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AudioRouteMonitor.refresh(ctx) }
    SectionCard("On your ears") {
        val what = when (route.out) {
            "bt" -> "🎧 ${route.name}" + (route.battery?.let { " · $it%" } ?: "")
            "wired" -> "🎧 ${route.name}"
            "usb" -> "🎧 ${route.name}"
            else -> "🔈 Phone speaker"
        }
        Text(what, style = MaterialTheme.typography.titleMedium)
        DimText("The desktop's status bar shows this too.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Listen to the desktop", style = MaterialTheme.typography.labelLarge)
                DimText(if (ready) "Desktop audio plays here, ~half a second behind." else "Needs the desktop bridge.")
            }
            Switch(checked = listening, enabled = ready, onCheckedChange = { ListenService.set(ctx, it) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AudioRouteMonitor.hasBtPermission(ctx)) {
            TextButton(onClick = { askBt.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth to show the headset name and battery") }
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
