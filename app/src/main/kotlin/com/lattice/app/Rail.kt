package com.lattice.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The right-hand rail: the app's only navigation, and the only thing that is
 * always on screen.
 *
 * Close is pinned to the top and settings to the bottom, because those are the
 * two you reach for without looking. 48dp is Material's minimum touch target —
 * the canvas is width-bound on a portrait phone, so every dp the rail does not
 * take is picture.
 *
 * A sibling of the canvas in a Row, never an overlay: that is what keeps the
 * canvas's swipe handler and the rail from arbitrating touches at all. Do not
 * give this a drag detector, and do not re-hang it off a Box as CenterEnd.
 */
const val RAIL_WIDTH_DP = 48

@Composable
fun Rail(
    open: Sheet?,
    onOpen: (Sheet) -> Unit,
    onCloseWindow: () -> Unit,
    closeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(RAIL_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Right)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CloseEntry(enabled = closeEnabled, onConfirm = onCloseWindow)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RailButton(Icons.Filled.Keyboard, "input", open == Sheet.Input) { onOpen(Sheet.Input) }
            DictateEntry()
            RailButton(Icons.Filled.Headphones, "audio", open == Sheet.Audio) { onOpen(Sheet.Audio) }
        }

        RailButton(Icons.Filled.Settings, "settings", open == Sheet.Settings) { onOpen(Sheet.Settings) }
    }
}

/**
 * Closing a window has no undo, so the first tap arms and the second closes.
 * A dialog would be heavier than the action; a rail entry that visibly changes
 * state is the lightest confirmation that is still a confirmation.
 */
@Composable
private fun CloseEntry(enabled: Boolean, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(3000)
            armed = false
        }
    }
    val scale by animateFloatAsState(if (armed) 1.15f else 1f, tween(180), label = "closeArm")
    RailButton(
        icon = Icons.Filled.Close,
        label = if (armed) "sure?" else "close",
        selected = armed,
        enabled = enabled,
        tint = if (armed) MaterialTheme.colorScheme.error else null,
        selectedBg = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.scale(scale),
    ) {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }
}

/**
 * Replaces the floating Dictate FAB. The mic permission launcher moved here
 * with it; the result of a take comes back out of band on `Link.dict` and is
 * surfaced as a snackbar by the shell.
 */
@Composable
private fun DictateEntry() {
    val ctx = LocalContext.current
    val active by DictationService.active.collectAsState()
    val level by DictationService.level.collectAsState()
    val dict by Link.dict.collectAsState()
    val transcribing = dict is Link.DictState.Transcribing

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) DictationService.toggle(ctx)
    }

    // The mic level breathes the entry while a take is open. animateFloatAsState,
    // never a LaunchedEffect keyed on `level` — that would restart a tween on
    // every 100 ms frame and never finish one.
    val pulse by animateFloatAsState(
        if (active) 1f + (level.coerceIn(0f, 1f) * 0.22f) else 1f,
        tween(120),
        label = "micLevel",
    )
    RailButton(
        icon = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
        label = if (transcribing) "…" else "dictate",
        selected = active || transcribing,
        tint = if (active) MaterialTheme.colorScheme.error else null,
        selectedBg = if (active) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.scale(pulse),
    ) {
        Interaction.touch()
        if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) else DictationService.toggle(ctx)
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    tint: Color? = null,
    selectedBg: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) (selectedBg ?: MaterialTheme.colorScheme.primaryContainer) else Color.Transparent,
        tween(180),
        label = "railBg",
    )
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        tint != null -> tint
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .padding(vertical = 3.dp)
            .size(RAIL_WIDTH_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
    }
}
