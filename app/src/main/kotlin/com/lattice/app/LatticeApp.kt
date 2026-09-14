package com.lattice.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Everything the shell and its sheets share. Owned by MainActivity, seeded from prefs. */
class AppState(
    host: String, slotIndex: Int, partyMode: Boolean, psk: String, mirrorFps: Int,
    mirrorIdleSeconds: Int, mirrorOnMetered: Boolean, termZoom: Float,
    val onHostChange: (String) -> Unit,
    val onSlotChange: (Int) -> Unit,
    val onPartyModeChange: (Boolean) -> Unit,
    val onPskChange: (String) -> Unit,
    val onMirrorFpsChange: (Int) -> Unit,
    val onMirrorIdleChange: (Int) -> Unit,
    val onMirrorOnMeteredChange: (Boolean) -> Unit,
    val onTermZoomChange: (Float) -> Unit,
    val onStartCapture: (String, Int, String) -> Unit,
    val onStopCapture: () -> Unit,
) {
    var host by mutableStateOf(host)
    var slotIndex by mutableStateOf(slotIndex)
    var partyMode by mutableStateOf(partyMode)
    var psk by mutableStateOf(psk)
    var mirrorFps by mutableStateOf(mirrorFps)
    var mirrorIdleSeconds by mutableStateOf(mirrorIdleSeconds)
    var mirrorOnMetered by mutableStateOf(mirrorOnMetered)
    /** Pinch zoom on the terminal, live while a pinch is in flight. */
    var termZoom by mutableStateOf(termZoom)
}

// ---- shared bits ----------------------------------------------------------------

@Composable
fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun TextRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
    }
}

@Composable
fun DimText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

@Suppress("unused")
private val keepColor = Color.Unspecified
