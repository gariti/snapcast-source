package com.lattice.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
