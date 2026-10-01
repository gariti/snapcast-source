package com.lattice.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxChip
import com.lattice.app.lx.LxChips
import com.lattice.app.lx.LxDivider
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxRow
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxSectionGap
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.StatusTone

/**
 * Ears: what is on your ears and whether the desktop plays here; and sending
 * this phone's audio to the desktop. Two jobs, not four — the phone's own
 * media transport is Android's, and the house speakers' admin moved to
 * More › Speakers.
 */
@Composable
fun EarsCard(app: AppState, ready: Boolean) {
    val ctx = LocalContext.current
    val route by AudioRouteMonitor.route.collectAsState()
    val listening by ListenService.active.collectAsState()
    val state by AudioCaptureService.state.collectAsState()
    val streaming = state !is ConnectionState.Idle && state !is ConnectionState.Failed
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AudioRouteMonitor.refresh(ctx) }
    val needsBt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AudioRouteMonitor.hasBtPermission(ctx)

    val earsName = when (route.out) {
        "bt", "wired", "usb" -> route.name.ifBlank { stringResource(R.string.ears_title) }
        else -> stringResource(R.string.phone_speaker)
    }
    val earsCaption = listOfNotNull(earsName, route.battery?.let { "$it %" }).joinToString(" · ")

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.ears_title))
                LxCaption(earsCaption)
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxSection(stringResource(R.string.hear), here = true)
                LxRow(
                    earsName,
                    listOfNotNull(route.battery?.let { "$it %" }, stringResource(R.string.headset_meta)).joinToString(" · "),
                    icon = if (route.out == "speaker") Icons.Filled.Speaker else Icons.Filled.Headphones,
                )
                LxDivider()
                LxRow(
                    stringResource(R.string.listen),
                    if (ready) stringResource(R.string.listen_meta) else stringResource(R.string.listen_needs_link),
                    icon = Icons.Filled.Hearing,
                    enabled = ready || listening,
                    onClick = { ListenService.set(ctx, !listening) },
                ) {
                    LxChip(if (listening) stringResource(R.string.on) else stringResource(R.string.off), on = listening, enabled = ready || listening) {
                        ListenService.set(ctx, !listening)
                    }
                }
                if (needsBt) {
                    LxDivider()
                    LxRow(stringResource(R.string.allow_bluetooth), stringResource(R.string.allow_bluetooth_meta), icon = Icons.Filled.Bluetooth, chevron = true) {
                        askBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }

                LxSectionGap()
                LxSection(stringResource(R.string.send_audio))
                LxChips {
                    LxChip(stringResource(R.string.to_speakers), on = app.partyMode, enabled = !streaming) { app.partyMode = true; app.onPartyModeChange(true) }
                    LxChip(stringResource(R.string.to_visualizer), on = !app.partyMode, enabled = !streaming) { app.partyMode = false; app.onPartyModeChange(false) }
                }
                if (app.partyMode) {
                    LxSectionGap()
                    LxChips {
                        SLOT_PORTS.forEachIndexed { idx, _ ->
                            LxChip("${idx + 1}", on = app.slotIndex == idx, enabled = !streaming, key = stringResource(R.string.speaker_slot)) {
                                app.slotIndex = idx; app.onSlotChange(idx)
                            }
                        }
                    }
                }
                LxSectionGap()
                val (text, tone) = when (val s = state) {
                    is ConnectionState.Idle -> stringResource(R.string.not_sending) to StatusTone.Info
                    is ConnectionState.Connecting -> stringResource(R.string.connecting) to StatusTone.Info
                    is ConnectionState.Connected ->
                        if (s.rms < 0.001f) stringResource(R.string.sending_silent) to StatusTone.Warn
                        else stringResource(R.string.sending) to StatusTone.Ok
                    is ConnectionState.Reconnecting -> stringResource(R.string.reconnecting) to StatusTone.Warn
                    is ConnectionState.Failed -> "${stringResource(R.string.could_not_connect)} · ${s.reason}" to StatusTone.Error
                }
                LxStatus(text, tone)
            }
        },
        bottom = {
            LxHints(
                if (streaming) listOf(Hint("◀", stringResource(R.string.hint_back_to_window)))
                else listOf(Hint(stringResource(R.string.start), stringResource(R.string.hint_start_record)))
            )
            if (streaming) LxButton(stringResource(R.string.stop), ButtonKind.Danger, onClick = app.onStopCapture)
            else LxButton(stringResource(R.string.start), ButtonKind.Primary, icon = Icons.Filled.Cast, enabled = app.host.isNotBlank()) {
                val port = if (app.partyMode) SLOT_PORTS[app.slotIndex] else SPECTRUM_PORT
                app.onStartCapture(app.host, port, if (app.partyMode) "party" else "solo")
            }
        },
    )
}
