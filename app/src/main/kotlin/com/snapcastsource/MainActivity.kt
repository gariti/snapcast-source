package com.snapcastsource

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var host by remember { mutableStateOf("192.168.1.10") }
    var portText by remember { mutableStateOf("4953") }
    var running by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    onStart(host, portText.toIntOrNull() ?: 4953)
                    running = true
                },
                enabled = !running
            ) { Text("Start") }

            OutlinedButton(
                onClick = {
                    onStop()
                    running = false
                },
                enabled = running
            ) { Text("Stop") }
        }

        Text(
            text = if (running) "Streaming…" else "Idle",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
