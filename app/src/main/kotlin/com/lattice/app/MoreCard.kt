package com.lattice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxChip
import com.lattice.app.lx.LxDivider
import com.lattice.app.lx.LxEmptyState
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxRow
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxSectionGap
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxText
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.StatusTone
import com.lattice.app.lx.Type
import com.lattice.app.lx.rememberArm
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.util.Date

/**
 * More: rows with sub-cards. The desktop (the link, the fingerprint key,
 * pairing, unpair), the mirror's three knobs, and the rest behind a chevron.
 * Snapcast admin is the desktop's control panel's job now, not the phone's.
 */
@Composable
fun MoreCard(app: AppState, desk: Link.Desk, linkedSince: Long, page: MorePage?, onPage: (MorePage?) -> Unit) {
    when (page) {
        null -> MoreRoot(app, desk, onPage)
        MorePage.Desktop -> DesktopPage(app, linkedSince)
        MorePage.Music -> MusicPage(app)
        MorePage.Hidden -> HiddenPage(desk)
        MorePage.Link -> LinkPage(desk)
    }
}

@Composable
private fun MoreRoot(app: AppState, desk: Link.Desk, onPage: (MorePage?) -> Unit) {
    val ctx = LocalContext.current
    val client by Link.client.collectAsState()
    val st by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val linked = st is ControlChannelClient.LinkState.Connected
    val enrol by DesktopAuth.enrol.collectAsState()
    val hidden = desk.windows.count { !it.visible }
    var musicOn by remember { mutableStateOf(MediaSessionListener.isAccessGranted(ctx)) }
    LaunchedEffect(Unit) { musicOn = MediaSessionListener.isAccessGranted(ctx) }

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.more_title))
                LxCaption(stringResource(R.string.paired_with, Names.host(app.host)))
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxSection(stringResource(R.string.desktop), here = true)
                LxRow(
                    Names.host(app.host),
                    when {
                        !linked -> stringResource(R.string.desktop_meta_down)
                        enrol == DesktopAuth.Enrol.TRUSTED -> stringResource(R.string.desktop_meta_linked)
                        else -> stringResource(R.string.desktop_meta_linked_nokey)
                    },
                    icon = Icons.Filled.Computer, chevron = true,
                ) { onPage(MorePage.Desktop) }

                LxSectionGap()
                LxSection(stringResource(R.string.mirror_section))
                LxRow(stringResource(R.string.frame_rate), stringResource(R.string.frame_rate_meta), trailing = {
                    val fps = listOf(1, 2, 4, 8)
                    LxChip("${app.mirrorFps}", key = stringResource(R.string.fps), on = true) {
                        val next = fps[(fps.indexOf(app.mirrorFps).coerceAtLeast(0) + 1) % fps.size]
                        app.mirrorFps = next; app.onMirrorFpsChange(next)
                    }
                })
                LxDivider()
                LxRow(stringResource(R.string.pause_when_idle), stringResource(R.string.pause_when_idle_meta), trailing = {
                    val steps = listOf(30, 60, 300, 0)
                    LxChip(if (app.mirrorIdleSeconds <= 0) stringResource(R.string.never) else Names.seconds(app.mirrorIdleSeconds), on = app.mirrorIdleSeconds > 0) {
                        val next = steps[(steps.indexOf(app.mirrorIdleSeconds).coerceAtLeast(0) + 1) % steps.size]
                        app.mirrorIdleSeconds = next; app.onMirrorIdleChange(next)
                    }
                })
                LxDivider()
                LxRow(stringResource(R.string.on_mobile_data), stringResource(R.string.on_mobile_data_meta), trailing = {
                    LxChip(if (app.mirrorOnMetered) stringResource(R.string.on) else stringResource(R.string.off), on = app.mirrorOnMetered) {
                        app.mirrorOnMetered = !app.mirrorOnMetered; app.onMirrorOnMeteredChange(app.mirrorOnMetered)
                    }
                })

                LxSectionGap()
                LxSection(stringResource(R.string.also))
                LxRow(
                    stringResource(R.string.music_detection),
                    if (musicOn) stringResource(R.string.music_detection_meta_on) else stringResource(R.string.music_detection_meta_off),
                    icon = Icons.Filled.MusicNote, chevron = true,
                ) { onPage(MorePage.Music) }
                LxDivider()
                LxRow(stringResource(R.string.hidden_windows), if (hidden == 0) stringResource(R.string.none) else "$hidden", icon = Icons.Filled.VisibilityOff, chevron = true) { onPage(MorePage.Hidden) }
                LxDivider()
                LxRow(stringResource(R.string.link_details), stringResource(R.string.link_details_meta), icon = Icons.Filled.Cable, chevron = true) { onPage(MorePage.Link) }
            }
        },
        bottom = {
            LxButton("?", enabled = false) {}
            LxHints(listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_tap_chip))))
        },
    )
}

/** More › Desktop (frame P): the link, the fingerprint key, pairing; unpair in the band. */
@Composable
private fun DesktopPage(app: AppState, linkedSince: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client by Link.client.collectAsState()
    val st by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val linked = st is ControlChannelClient.LinkState.Connected
    val speaksAuth = (st as? ControlChannelClient.LinkState.Connected)?.caps?.contains("auth") == true
    val enrol by DesktopAuth.enrol.collectAsState()
    val authErr by DesktopAuth.lastError.collectAsState()
    val qrStatus by MainActivity.pairStatus.collectAsState()
    val forgetArm = rememberArm()
    val unpairArm = rememberArm()
    val pairing = rememberPairingState()
    val strings = rememberPairingStrings()
    var typing by remember { mutableStateOf(false) }
    val lx = LxTheme.current
    val time = remember(linkedSince) { if (linkedSince > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(linkedSince)) else null }
    val viaLan = remember(app.host) { !app.host.contains(".ts.net") }

    // Notifications: asked here, where the reason is on screen.
    var notifOk by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(Names.host(app.host).ifBlank { stringResource(R.string.desktop) })
                LxCaption(stringResource(R.string.more_desktop))
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState()).alpha(if (typing) 0.45f else 1f)) {
                LxSection(stringResource(R.string.link))
                LxRow(
                    if (linked) stringResource(R.string.linked) else stringResource(R.string.not_answering).replaceFirstChar(Char::uppercase),
                    listOfNotNull(
                        if (linked) (if (viaLan) stringResource(R.string.same_network) else stringResource(R.string.on_tailscale)) else null,
                        time?.let { stringResource(R.string.since, it) },
                    ).joinToString(" · ").ifBlank { null },
                    icon = Icons.Filled.Computer,
                )
                if (!notifOk) {
                    LxDivider()
                    LxRow(stringResource(R.string.allow_notifications), stringResource(R.string.allow_notifications_meta), icon = Icons.Filled.Notifications, chevron = true) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LxSectionGap()
                LxSection(stringResource(R.string.fingerprint), here = true)
                when (enrol) {
                    DesktopAuth.Enrol.TRUSTED -> LxRow(stringResource(R.string.key_trusted), stringResource(R.string.key_trusted_meta), icon = Icons.Filled.Fingerprint)
                    DesktopAuth.Enrol.PENDING -> LxRow(stringResource(R.string.key_waiting), stringResource(R.string.key_waiting_meta), icon = Icons.Filled.Fingerprint)
                    else -> LxRow(
                        stringResource(R.string.key_setup),
                        when {
                            enrol == DesktopAuth.Enrol.INVALIDATED -> stringResource(R.string.key_invalidated)
                            enrol == DesktopAuth.Enrol.REVOKED -> stringResource(R.string.key_revoked)
                            !linked -> stringResource(R.string.key_setup_unlinked)
                            !speaksAuth -> stringResource(R.string.key_setup_noauth)
                            else -> stringResource(R.string.key_setup_meta)
                        },
                        icon = Icons.Filled.Fingerprint, chevron = linked && speaksAuth, enabled = linked && speaksAuth,
                    ) { DesktopAuth.enrol(ctx) }
                }
                if (DesktopAuthKey.exists()) {
                    LxDivider()
                    LxRow(
                        stringResource(R.string.forget_the_key), stringResource(R.string.forget_the_key_meta), icon = Icons.Filled.KeyOff,
                        trailing = {
                            LxChip(if (forgetArm.armed) stringResource(R.string.tap_again) else stringResource(R.string.forget), warn = true, armed = forgetArm.armed) {
                                if (forgetArm.press()) DesktopAuth.forget(ctx)
                            }
                        },
                    )
                }
                authErr?.let { LxStatus(it, StatusTone.Error) }

                LxSectionGap()
                LxSection(stringResource(R.string.pairing))
                LxRow(stringResource(R.string.pair_again), stringResource(R.string.pair_again_meta), icon = Icons.Filled.QrCodeScanner, chevron = true) { openScanner(ctx) }
                LxDivider()
                LxRow(stringResource(R.string.type_a_code), stringResource(R.string.type_a_code_meta), icon = Icons.Filled.Keyboard, chevron = true) { typing = true }
                qrStatus?.let { LxStatus(it, if (it.startsWith("Paired")) StatusTone.Ok else StatusTone.Info) }
            }
            if (typing) {
                Spacer(Modifier.height(lx.u(0.6f)))
                PairingDialog(app, pairing, enabled = !pairing.searching)
            }
        },
        bottom = {
            if (typing) {
                LxHints(listOf(Hint("◀", stringResource(R.string.hint_back_to_more))))
                LxButton("◀", onClick = { typing = false })
                if (pairing.complete(app.psk)) LxButton(stringResource(R.string.connect), ButtonKind.Primary, enabled = !pairing.searching) {
                    pairing.connect(ctx, app, scope, strings)
                }
            } else {
                LxButton("?", enabled = false) {}
                LxHints(
                    if (unpairArm.armed) listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_unpair)))
                    else listOf(Hint("◀", stringResource(R.string.hint_back_to_more)))
                )
                LxButton(stringResource(R.string.unpair), ButtonKind.Danger, armed = unpairArm.armed) {
                    if (unpairArm.press()) { app.psk = ""; app.onPskChange("") }
                }
            }
        },
    )
}

/** More › Music detection: the notification-access beacon, in words. */
@Composable
private fun MusicPage(app: AppState) {
    val ctx = LocalContext.current
    val listener by MediaSessionListener.state.collectAsState()
    val beacon by MediaSessionBeaconService.serviceState.collectAsState()
    var granted by remember { mutableStateOf(MediaSessionListener.isAccessGranted(ctx)) }
    LaunchedEffect(Unit) { granted = MediaSessionListener.isAccessGranted(ctx) }
    val running = beacon is MediaSessionBeaconService.ServiceState.Running

    LxCard(
        active = true,
        top = { Column(Modifier.weight(1f)) { LxWordmark(stringResource(R.string.music_detection)) } },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxEmptyState(headline = stringResource(R.string.music_headline), footnote = stringResource(R.string.music_body))
                LxSectionGap()
                val (text, tone) = when {
                    !granted -> stringResource(R.string.off) to StatusTone.Info
                    app.host.isBlank() -> stringResource(R.string.not_paired) to StatusTone.Warn
                    running && listener.isPlaying -> stringResource(R.string.music_playing, listener.sessionCount) to StatusTone.Ok
                    running -> stringResource(R.string.music_listening) to StatusTone.Info
                    else -> stringResource(R.string.music_not_running) to StatusTone.Warn
                }
                LxStatus(text, tone)
                if (granted && !listener.listenerConnected) LxStatus(stringResource(R.string.music_listener_lost), StatusTone.Error)
            }
        },
        bottom = {
            LxHints(listOf(Hint("◀", stringResource(R.string.hint_back_to_more))))
            if (!granted) LxButton(stringResource(R.string.allow), ButtonKind.Primary) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    putExtra(":settings:fragment_args_key", "com.lattice.app/com.lattice.app.MediaSessionListener")
                })
            }
        },
    )
}

/** More › Hidden windows: the ones `Hide` put away, one tap brings back. */
@Composable
private fun HiddenPage(desk: Link.Desk) {
    val hidden = desk.windows.filter { !it.visible }
    LxCard(
        active = true,
        top = { Column(Modifier.weight(1f)) { LxWordmark(stringResource(R.string.hidden_windows)) } },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hidden.isEmpty()) LxEmptyState(headline = stringResource(R.string.none))
                hidden.forEachIndexed { i, w ->
                    if (i > 0) LxDivider()
                    LxRow(Names.windowTitle(w), Names.windowKind(w), chevron = true) {
                        Link.act("ShowWindow", "id" to w.id); Link.act("FocusWindow", "id" to w.id)
                    }
                }
            }
        },
        bottom = { LxHints(listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.show_window).lowercase()))) },
    )
}

/** More › Link details: the diagnostics, as rows. */
@Composable
private fun LinkPage(desk: Link.Desk) {
    val client by Link.client.collectAsState()
    val st by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val bridge by (client?.bridgeUp ?: remember { MutableStateFlow(false) }).collectAsState()
    val conn = st as? ControlChannelClient.LinkState.Connected
    val lx = LxTheme.current
    @Composable fun Value(v: String) = LxText(v, Type.caption, lx.ink(0.7f), maxLines = 1)
    LxCard(
        active = true,
        top = { Column(Modifier.weight(1f)) { LxWordmark(stringResource(R.string.link_details)) } },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxRow(stringResource(R.string.channel), trailing = {
                    Value(when (st) {
                        is ControlChannelClient.LinkState.Connected -> stringResource(R.string.up)
                        ControlChannelClient.LinkState.Connecting -> stringResource(R.string.connecting)
                        else -> stringResource(R.string.down)
                    })
                })
                LxDivider()
                LxRow(stringResource(R.string.protocol), trailing = { Value(conn?.proto?.toString() ?: "—") })
                LxDivider()
                LxRow(stringResource(R.string.bridge), trailing = { Value(if (bridge) stringResource(R.string.up) else stringResource(R.string.down)) })
                LxDivider()
                LxRow(stringResource(R.string.caps), conn?.caps?.sorted()?.joinToString(" · ") ?: "—")
                LxDivider()
                LxRow(stringResource(R.string.displays), desk.outputs.joinToString(" · ") { "${Names.display(it.name, desk.outputs)} (${it.name})" }.ifBlank { "—" })
                LxDivider()
                LxRow(stringResource(R.string.counts), trailing = { Value("${desk.workspaces.size} · ${desk.windows.size}") })
            }
        },
        bottom = { LxHints(listOf(Hint("◀", stringResource(R.string.hint_back_to_more)))) },
    )
}

@Suppress("unused")
private fun keepWidth(m: Modifier) = m.fillMaxWidth()
