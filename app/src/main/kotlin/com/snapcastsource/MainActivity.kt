package com.snapcastsource

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "snapcast_source_prefs"
private const val KEY_SLOT = "slot_index"
private const val KEY_HOST = "host"
private const val KEY_PARTY_MODE = "party_mode"

val SLOT_PORTS = listOf(4953, 4954, 4955, 4956)
const val VIZ_PORT = 4900

fun portToSlot(port: Int): Int? {
    val idx = SLOT_PORTS.indexOf(port)
    return if (idx >= 0) idx + 1 else null
}

class MainActivity : ComponentActivity() {

    private var pendingHost: String = ""
    private var pendingPort: Int = SLOT_PORTS[0]

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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialSlotIdx = prefs.getInt(KEY_SLOT, 0).coerceIn(0, SLOT_PORTS.size - 1)
        val initialHost = prefs.getString(KEY_HOST, "192.168.0.163") ?: "192.168.0.163"
        val initialPartyMode = prefs.getBoolean(KEY_PARTY_MODE, false)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SnapcastSourceScreen(
                        initialHost = initialHost,
                        initialSlotIndex = initialSlotIdx,
                        initialPartyMode = initialPartyMode,
                        onSlotChange = { idx ->
                            prefs.edit().putInt(KEY_SLOT, idx).apply()
                        },
                        onHostChange = { host ->
                            prefs.edit().putString(KEY_HOST, host).apply()
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

    private fun startCapture(host: String, port: Int) {
        pendingHost = host
        pendingPort = port
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        stopService(Intent(this, AudioCaptureService::class.java))
    }
}

@Composable
fun SnapcastSourceScreen(
    initialHost: String,
    initialSlotIndex: Int,
    initialPartyMode: Boolean,
    onSlotChange: (Int) -> Unit,
    onHostChange: (String) -> Unit,
    onPartyModeChange: (Boolean) -> Unit,
    onStart: (String, Int) -> Unit,
    onStop: () -> Unit
) {
    var host by remember { mutableStateOf(initialHost) }
    var slotIndex by remember { mutableIntStateOf(initialSlotIndex) }
    var partyMode by remember { mutableStateOf(initialPartyMode) }

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
            text = "Snapcast Source",
            style = MaterialTheme.typography.headlineMedium
        )

        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                onHostChange(it)
            },
            label = { Text("Snapserver host") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Party mode", style = MaterialTheme.typography.titleMedium)
                Text(
                    "broadcast to speakers",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Switch(
                checked = partyMode,
                onCheckedChange = {
                    partyMode = it
                    onPartyModeChange(it)
                },
                enabled = editable
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
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val port = if (partyMode) SLOT_PORTS[slotIndex] else VIZ_PORT
                    onStart(host, port)
                },
                enabled = !streaming
            ) { Text("Start") }

            OutlinedButton(
                onClick = onStop,
                enabled = streaming
            ) { Text("Stop") }
        }

        StatusCard(state)

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
    if (port == VIZ_PORT) return "→ $host:$port (Visualize)"
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
