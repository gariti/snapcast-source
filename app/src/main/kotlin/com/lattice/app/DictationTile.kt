package com.lattice.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The quick hotkey: one swipe from any screen, tap to start dictating to the
 * desktop, tap again to transcribe. The tile shows the take in progress and
 * greys out while the desktop bridge is unreachable.
 */
class DictationTile : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch {
            combine(DictationService.active, Link.client) { active, client -> active to (client?.sessionReady == true) }
                .collect { (active, ready) -> render(active, ready) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!Link.ready) {
            // Nothing to record into: open the app, which shows why.
            val i = android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
            return
        }
        DictationService.toggle(this)
    }

    private fun render(active: Boolean, ready: Boolean) {
        val tile = qsTile ?: return
        tile.label = "Dictate"
        tile.state = when {
            !ready -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !ready -> "desktop offline"
            active -> "listening — tap to type"
            else -> "to the desktop"
        }
        tile.updateTile()
    }
}
