package com.snapcastsource

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {

    private var pendingHost: String = ""
    private var pendingPort: Int = 4953

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SnapcastSourceScreen(
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
    onStart: (String, Int) -> Unit,
    onStop: () -> Unit
) {
    var host by remember { mutableStateOf("192.168.0.163") }
    var portText by remember { mutableStateOf("4953") }

    val state by AudioCaptureService.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            onValueChange = { host = it },
            label = { Text("Snapserver host") },
            singleLine = true,
            enabled = state is ConnectionState.Idle || state is ConnectionState.Failed,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            enabled = state is ConnectionState.Idle || state is ConnectionState.Failed,
            modifier = Modifier.fillMaxWidth()
        )

        val streaming = state !is ConnectionState.Idle && state !is ConnectionState.Failed

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onStart(host, portText.toIntOrNull() ?: 4953) },
                enabled = !streaming
            ) { Text("Start") }

            OutlinedButton(
                onClick = onStop,
                enabled = streaming
            ) { Text("Stop") }
        }

        StatusCard(state)
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
                Text("→ ${state.host}:${state.port}", fontFamily = FontFamily.Monospace)
            }
            is ConnectionState.Connected -> {
                Text("→ ${state.host}:${state.port}", fontFamily = FontFamily.Monospace)
                Text(
                    "Sent: ${formatBytes(state.bytesSent)}",
                    fontFamily = FontFamily.Monospace
                )
                AudioMeter(rms = state.rms)
            }
            is ConnectionState.Reconnecting -> {
                Text("→ ${state.host}:${state.port}", fontFamily = FontFamily.Monospace)
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
