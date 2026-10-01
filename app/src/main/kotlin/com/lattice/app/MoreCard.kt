package com.lattice.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Speaker
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
import com.lattice.app.lx.LxEmptyState
import com.lattice.app.lx.LxField
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * More: rows with sub-cards. The desktop (pairing, the fingerprint key,
 * unpair), the mirror's three knobs, and the rest behind a chevron each.
 * The host field is gone from the front: the QR fills it in.
 */
@Composable
fun MoreCard(app: AppState, snap: SnapcastState, desk: Link.Desk, page: MorePage?, onPage: (MorePage?) -> Unit) {
    when (page) {
        null -> MoreRoot(app, desk, onPage)
        MorePage.Desktop -> DesktopPage(app)
        MorePage.Music -> MusicPage(app)
        MorePage.Hidden -> HiddenPage(desk)
        MorePage.Speakers -> SpeakersPage(app, snap)
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
    val unpairArm = rememberArm()
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
                LxRow(stringResource(R.string.frame_rate), stringResource(R.string.frame_rate_meta)) {
                    val fps = listOf(1, 2, 4, 8)
                    LxChip("${app.mirrorFps}", key = stringResource(R.string.fps), on = true) {
                        val next = fps[(fps.indexOf(app.mirrorFps).coerceAtLeast(0) + 1) % fps.size]
                        app.mirrorFps = next; app.onMirrorFpsChange(next)
                    }
                }
                LxDivider()
                LxRow(stringResource(R.string.pause_when_idle), stringResource(R.string.pause_when_idle_meta)) {
                    val steps = listOf(30, 60, 300, 0)
                    LxChip(Names.seconds(app.mirrorIdleSeconds), on = app.mirrorIdleSeconds > 0) {
                        val next = steps[(steps.indexOf(app.mirrorIdleSeconds).coerceAtLeast(0) + 1) % steps.size]
                        app.mirrorIdleSeconds = next; app.onMirrorIdleChange(next)
                    }
                }
                LxDivider()
                LxRow(stringResource(R.string.on_mobile_data), stringResource(R.string.on_mobile_data_meta)) {
                    LxChip(if (app.mirrorOnMetered) stringResource(R.string.on) else stringResource(R.string.off), on = app.mirrorOnMetered) {
                        app.mirrorOnMetered = !app.mirrorOnMetered; app.onMirrorOnMeteredChange(app.mirrorOnMetered)
                    }
                }

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
                LxRow(stringResource(R.string.speakers), stringResource(R.string.speakers_meta), icon = Icons.Filled.Speaker, chevron = true) { onPage(MorePage.Speakers) }
                LxDivider()
                LxRow(stringResource(R.string.link_details), stringResource(R.string.link_details_meta), icon = Icons.Filled.Cable, chevron = true) { onPage(MorePage.Link) }
            }
        },
        bottom = {
            LxHints(
                if (unpairArm.armed) listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_unpair)))
                else listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_tap_chip)), Hint("◀", stringResource(R.string.hint_back_to_window)))
            )
            LxButton(stringResource(R.string.unpair), ButtonKind.Danger, armed = unpairArm.armed) {
                if (unpairArm.press()) { app.psk = ""; app.onPskChange("") }
            }
        },
    )
}

/** More › Desktop: the pairing (code, find) and the fingerprint key (enrol, forget). */
@Composable
private fun DesktopPage(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client by Link.client.collectAsState()
    val st by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val linked = st is ControlChannelClient.LinkState.Connected
    val speaksAuth = (st as? ControlChannelClient.LinkState.Connected)?.caps?.contains("auth") == true
    val enrol by DesktopAuth.enrol.collectAsState()
    val authErr by DesktopAuth.lastError.collectAsState()
    val qrStatus by MainActivity.pairStatus.collectAsState()
    var findStatus by remember { mutableStateOf<Pair<String, StatusTone>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val forgetArm = rememberArm()
    val capture by AudioCaptureService.state.collectAsState()
    val editable = capture is ConnectionState.Idle || capture is ConnectionState.Failed

    val sEnter = stringResource(R.string.enter_code_first)
    val sNone = stringResource(R.string.no_desktop_found)
    val sMismatch = stringResource(R.string.code_mismatch)
    val sPaired = stringResource(R.string.paired_at)
    fun find() {
        searching = true
        findStatus = null
        scope.launch {
            val normalized = PairingCrypto.normalize(app.psk)
            if (normalized.isEmpty()) { findStatus = sEnter to StatusTone.Warn; searching = false; return@launch }
            val candidates = DesktopDiscovery(ctx).discover()
            if (candidates.isEmpty()) { findStatus = sNone to StatusTone.Error; searching = false; return@launch }
            var paired: String? = null
            for (c in candidates) if (PairingVerifier.verify(c.host, c.vrfyPort, normalized)) { paired = c.host; break }
            findStatus = if (paired != null) {
                app.host = paired; app.onHostChange(paired)
                // mDNS only ever finds it on the local network, and the PSK
                // challenge proved it is really the desktop: a LAN candidate.
                LinkHosts.addLan(ctx, paired)
                sPaired.format(Names.host(paired)) to StatusTone.Ok
            } else sMismatch to StatusTone.Error
            searching = false
        }
    }

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.desktop))
                LxCaption(Names.host(app.host).ifBlank { stringResource(R.string.not_paired) })
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxSection(stringResource(R.string.pairing), here = true)
                LxField(stringResource(R.string.code), app.psk, { app.psk = it; app.onPskChange(it) }, stringResource(R.string.code_placeholder), enabled = editable)
                LxField(stringResource(R.string.address), app.host, { app.host = it; app.onHostChange(it) }, stringResource(R.string.address_placeholder), enabled = editable)
                qrStatus?.let { LxStatus(it, if (it.startsWith("Paired")) StatusTone.Ok else StatusTone.Info) }
                findStatus?.let { (t, tone) -> LxStatus(t, tone) }
                if (searching) LxStatus(stringResource(R.string.searching))

                LxSectionGap()
                LxSection(stringResource(R.string.fingerprint), badge = null)
                LxRow(
                    when (enrol) {
                        DesktopAuth.Enrol.NONE -> stringResource(R.string.key_none)
                        DesktopAuth.Enrol.PENDING -> stringResource(R.string.key_pending)
                        DesktopAuth.Enrol.TRUSTED -> stringResource(R.string.key_trusted, DesktopAuthKey.kid() ?: "")
                        DesktopAuth.Enrol.REVOKED -> stringResource(R.string.key_revoked)
                        DesktopAuth.Enrol.INVALIDATED -> stringResource(R.string.key_invalidated)
                    },
                    if (DesktopAuthKey.exists()) (if (DesktopAuthKey.isStrongBox()) stringResource(R.string.key_hardware_strongbox) else stringResource(R.string.key_hardware_tee))
                    else stringResource(R.string.fingerprint_meta),
                    icon = Icons.Filled.Fingerprint,
                )
                LxDivider()
                LxRow(
                    if (enrol == DesktopAuth.Enrol.TRUSTED) stringResource(R.string.enrol_again) else stringResource(R.string.enrol),
                    when {
                        enrol == DesktopAuth.Enrol.PENDING -> stringResource(R.string.enrol_meta_pending)
                        !linked -> stringResource(R.string.enrol_meta_unlinked)
                        !speaksAuth -> stringResource(R.string.enrol_meta_noauth)
                        else -> stringResource(R.string.fingerprint_meta)
                    },
                    enabled = linked && speaksAuth,
                    chevron = true,
                ) { DesktopAuth.enrol(ctx) }
                authErr?.let { LxStatus(it, StatusTone.Error) }
            }
        },
        bottom = {
            LxHints(
                if (forgetArm.armed) listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_forget)))
                else listOf(Hint("◀", stringResource(R.string.more_title)))
            )
            if (DesktopAuthKey.exists()) LxButton(stringResource(R.string.forget_key), ButtonKind.Danger, armed = forgetArm.armed) {
                if (forgetArm.press()) DesktopAuth.forget(ctx)
            }
            LxButton(stringResource(R.string.find_desktop), ButtonKind.Primary, enabled = editable && !searching, onClick = ::find)
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
            LxHints(listOf(Hint("◀", stringResource(R.string.more_title))))
            if (!granted) LxButton(stringResource(R.string.open_notification_access), ButtonKind.Primary) {
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
        bottom = { LxHints(listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.show_window).lowercase()), Hint("◀", stringResource(R.string.more_title)))) },
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
                LxRow(stringResource(R.string.channel)) {
                    Value(when (st) {
                        is ControlChannelClient.LinkState.Connected -> stringResource(R.string.up)
                        ControlChannelClient.LinkState.Connecting -> stringResource(R.string.connecting)
                        else -> stringResource(R.string.down)
                    })
                }
                LxDivider()
                LxRow(stringResource(R.string.protocol)) { Value(conn?.proto?.toString() ?: "—") }
                LxDivider()
                LxRow(stringResource(R.string.bridge)) { Value(if (bridge) stringResource(R.string.up) else stringResource(R.string.down)) }
                LxDivider()
                LxRow(stringResource(R.string.caps), conn?.caps?.sorted()?.joinToString(" · ") ?: "—")
                LxDivider()
                LxRow(stringResource(R.string.displays), desk.outputs.joinToString(" · ") { "${Names.display(it.name, desk.outputs)} (${it.name})" }.ifBlank { "—" })
                LxDivider()
                LxRow(stringResource(R.string.counts)) { Value("${desk.workspaces.size} · ${desk.windows.size}") }
            }
        },
        bottom = { LxHints(listOf(Hint("◀", stringResource(R.string.more_title)))) },
    )
}

// ── the house speakers (snapcast) ─────────────────────────────────────────

/**
 * The snapcast conversation, hoisted out of the card body on purpose: the
 * card's content is disposed every time it goes away, and a `setStream`
 * fired just before would be cancelled mid-RPC. Owned by the shell instead.
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
                error = e.message ?: "speakers"
            } finally {
                loading = false
            }
        }
    }

    fun setStream(host: String, client: SnapClient, streamId: String, scope: CoroutineScope) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SnapcastRpc(host).setGroupStream(client.groupId, streamId) }
                status = status?.let { st -> st.copy(clients = st.clients.map { if (it.groupId == client.groupId) it.copy(streamId = streamId) else it }) }
            } catch (e: Exception) {
                error = e.message ?: "stream"
            }
        }
    }

    fun toggleMute(host: String, client: SnapClient, scope: CoroutineScope) {
        val newMuted = !client.muted
        scope.launch {
            try {
                withContext(Dispatchers.IO) { SnapcastRpc(host).setClientMute(client.id, newMuted, client.volumePercent) }
                status = status?.let { st -> st.copy(clients = st.clients.map { if (it.id == client.id) it.copy(muted = newMuted) else it }) }
            } catch (e: Exception) {
                error = e.message ?: "mute"
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

/** More › Speakers: every snapcast client, muted or not, and which stream it plays. */
@Composable
private fun SpeakersPage(app: AppState, snap: SnapcastState) {
    val scope = rememberCoroutineScope()
    val clients = snap.status?.clients
    val streams = snap.status?.streamIds ?: emptyList()
    LxCard(
        active = true,
        top = { Column(Modifier.weight(1f)) { LxWordmark(stringResource(R.string.speakers)); LxCaption(Names.host(app.host)) } },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    snap.error != null -> LxStatus(stringResource(R.string.speakers_error), StatusTone.Error)
                    clients != null && clients.isEmpty() -> LxEmptyState(headline = stringResource(R.string.no_speakers))
                }
                clients?.forEachIndexed { i, c ->
                    if (i > 0) LxDivider()
                    LxRow(
                        c.name,
                        if (c.connected) c.streamId else stringResource(R.string.down),
                        icon = Icons.Filled.Speaker, dim = !c.connected,
                    ) {
                        LxChip(if (c.muted) stringResource(R.string.muted) else stringResource(R.string.audible), on = !c.muted, warn = c.muted) {
                            snap.toggleMute(app.host, c, scope)
                        }
                    }
                    if (streams.size > 1) LxChips {
                        streams.forEach { sid -> LxChip(sid, on = sid == c.streamId) { snap.setStream(app.host, c, sid, scope) } }
                    }
                }
            }
        },
        bottom = {
            LxHints(listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_tap_chip)), Hint("◀", stringResource(R.string.more_title))))
            LxButton(stringResource(R.string.refresh), enabled = !snap.loading) { snap.refresh(app.host, scope) }
        },
    )
}
