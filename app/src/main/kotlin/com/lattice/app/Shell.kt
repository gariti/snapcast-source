package com.lattice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxDivider
import com.lattice.app.lx.LxEmptyState
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxRow
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxSectionGap
import com.lattice.app.lx.LxStage
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.Marker
import com.lattice.app.lx.StatusTone
import com.lattice.app.lx.rememberArm
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the canvas is showing. Hoisted out of the canvas itself because the
 * band, the Window card and the Keys card all need to read it — the Keys card
 * in particular has to know whether it is typing at a tmux pane or at the
 * compositor.
 */
class CanvasPrefs {
    /** Whole output rather than the focused window. */
    var wholeOutput by mutableStateOf(false)
    /** Which output, while `wholeOutput`. */
    var output by mutableStateOf<String?>(null)
    /** The user's live/paused switch. */
    var mirrorOn by mutableStateOf(true)
    /** An agent session the user asked to see as a picture instead of text. */
    var mirrorFor by mutableStateOf<String?>(null)
    /** A web page the user asked to see as a picture instead of a real page. */
    var mirrorForWeb by mutableStateOf<String?>(null)
}

/** The second card on the stage, if any. */
enum class Sheet { Window, Keys, Ears, More, Gestures }

/** A page inside the More card. */
enum class MorePage { Desktop, Music, Hidden, Speakers, Link }

/**
 * The app: a STAGE with one card — the window the desktop has focused — and,
 * when you ask for one, a second card that rises under it. There is no rail
 * and no tab bar: the canvas card's own bands hold every command, and the
 * canvas swipes between windows.
 *
 * This is the desktop's own shape (a Card App: band · tile · band, cards on
 * the blurred wallpaper), on a phone.
 */
@Composable
fun LatticeApp(app: AppState) {
    val prefs = remember { CanvasPrefs() }
    val desk by Link.desk.collectAsState()
    val client by Link.client.collectAsState()
    val linkState by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val bridgeUp by (client?.bridgeUp ?: remember { MutableStateFlow(false) }).collectAsState()
    val dict by Link.dict.collectAsState()
    val snap = rememberSnapcastState(app.host)

    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var morePage by rememberSaveable { mutableStateOf<MorePage?>(null) }
    // Esc unwinds one level: a page, then the card.
    BackHandler(enabled = sheet != null) {
        if (sheet == Sheet.More && morePage != null) morePage = null else sheet = null
    }

    val ready = bridgeUp &&
        (linkState as? ControlChannelClient.LinkState.Connected)?.proto?.let { it >= 2 } == true
    val linkUp = linkState is ControlChannelClient.LinkState.Connected
    val focused = desk.focused

    // A dispatched agent's terminal is a `tmux-attach-<session>` window; on one
    // of those the canvas reads the pane as text instead of mirroring a picture
    // of it. Computed here rather than in the canvas because the Keys card
    // routes its keystrokes by the same answer.
    val agentSession = focused?.app
        ?.takeIf { it.startsWith(AGENT_APP_PREFIX) }
        ?.removePrefix(AGENT_APP_PREFIX)
        ?.takeIf { it.isNotEmpty() }
    val termMode = agentSession != null && !prefs.wholeOutput && prefs.mirrorFor != agentSession

    // And a browser window is a page: render it here instead of watching a
    // JPEG of it. The two are disjoint by construction (an agent terminal is
    // a `foot` window, a page is a browser one), so `termMode` wins any tie.
    val webPage = if (termMode) null else webPageFor(focused)
    val webMode = webPage != null && !prefs.wholeOutput && prefs.mirrorForWeb != webPage.url
    var webTitle by remember { mutableStateOf<String?>(null) }

    // Dictation outcomes surface on the canvas card's status line, briefly.
    var notice by remember { mutableStateOf<Pair<String, StatusTone>?>(null) }
    LaunchedEffect(dict) {
        when (val d = dict) {
            is Link.DictState.Done -> { notice = "Typed: " + d.text.take(80) to StatusTone.Ok; Link.clearDict() }
            is Link.DictState.Failed -> { notice = "Could not dictate: " + d.reason to StatusTone.Error; Link.clearDict() }
            else -> {}
        }
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(4000); notice = null } }

    val paired = app.psk.isNotBlank()

    Box(
        Modifier
            .fillMaxSize()
            // Every touch anywhere counts as "using it", so the mirror's idle
            // timer only fires when the phone really is idle. Initial pass and
            // never consuming, so no child gesture is disturbed.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        Interaction.touch()
                    }
                }
            },
    ) {
        LxStage(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                // The IME inset already subsumes the navigation bar, so union
                // them: padding both would double-count under a keyboard.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            if (!paired) {
                FirstRunCard(
                    modifier = Modifier.weight(1f),
                    onTypeCode = { sheet = Sheet.More; morePage = MorePage.Desktop },
                )
                // The first run has one way in to More › Desktop and nothing
                // else: the rest of the stage is empty until the phone is paired.
            } else {
                val shrunk = sheet != null
                CanvasCard(
                    app = app, prefs = prefs, desk = desk, ready = ready, linkUp = linkUp,
                    agentSession = agentSession, termMode = termMode, webPage = webPage, webMode = webMode,
                    webTitle = webTitle, onWebTitle = { webTitle = it },
                    sheet = sheet, onSheet = { s -> sheet = if (sheet == s) null else s; if (s != Sheet.More) morePage = null },
                    onCollapse = { sheet = null; morePage = null },
                    shrunk = shrunk, notice = notice,
                    modifier = if (shrunk) Modifier else Modifier.weight(1f),
                )
            }

            // The second card. Rendered for the LAST sheet asked for so the
            // slide-out animates a full card, never an empty box — a hard cut,
            // which this app does not do.
            var shown by remember { mutableStateOf(Sheet.Window) }
            LaunchedEffect(sheet) { if (sheet != null) shown = sheet!! }
            val rise by animateFloatAsState(if (sheet != null) 1f else 0f, tween(LxTheme.current.fast), label = "rise")
            if (sheet != null || rise > 0.01f) {
                val lx = LxTheme.current
                val travel = with(LocalDensity.current) { lx.u(2f).toPx() }
                Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = rise; translationY = (1f - rise) * travel }) {
                    when (shown) {
                        Sheet.Window -> WindowCard(
                            desk = desk, prefs = prefs, agentSession = agentSession, termMode = termMode,
                            webPage = webPage, webMode = webMode, enabled = ready,
                            onDone = { Link.requestDesk() },
                        )
                        Sheet.Keys -> KeysCard(ready = ready, termMode = termMode, focused = focused)
                        Sheet.Ears -> EarsCard(app = app, ready = ready)
                        Sheet.More -> MoreCard(app = app, snap = snap, desk = desk, page = morePage, onPage = { morePage = it })
                        Sheet.Gestures -> GesturesCard(termMode = termMode, webMode = webMode)
                    }
                }
            }
        }
    }
}

/**
 * The canvas card: what the desktop has focused, as a card. The top band is
 * the window's name and the four commands (keys · say · ears · more); the tile
 * is the picture, the text or the page, and under the picture the other
 * windows on the workspace; the bottom band is `?`, the surface's own
 * gestures, and close.
 */
@Composable
private fun CanvasCard(
    app: AppState,
    prefs: CanvasPrefs,
    desk: Link.Desk,
    ready: Boolean,
    linkUp: Boolean,
    agentSession: String?,
    termMode: Boolean,
    webPage: WebPage?,
    webMode: Boolean,
    webTitle: String?,
    onWebTitle: (String?) -> Unit,
    sheet: Sheet?,
    onSheet: (Sheet) -> Unit,
    onCollapse: () -> Unit,
    shrunk: Boolean,
    notice: Pair<String, StatusTone>?,
    modifier: Modifier = Modifier,
) {
    val lx = LxTheme.current
    val focused = desk.focused
    val ws = focused?.workspace?.let { id -> desk.workspaces.firstOrNull { it.id == id } }
    val termScreen by Link.term.collectAsState()
    val closeArm = rememberArm()

    val title = when {
        prefs.wholeOutput -> prefs.output?.let { Names.display(it, desk.outputs) } ?: stringResource(R.string.whole_display)
        focused == null -> stringResource(R.string.no_window)
        webMode && webTitle != null -> webTitle
        else -> Names.windowTitle(focused)
    }
    val caption = when {
        prefs.wholeOutput -> stringResource(R.string.whole_display)
        focused == null -> if (linkUp) stringResource(R.string.focus_something) else stringResource(R.string.not_linked)
        else -> listOfNotNull(
            Names.windowKind(focused),
            Names.workspace(ws),
            focused.output.ifBlank { null }?.let { Names.display(it, desk.outputs) },
            if (termMode) stringResource(R.string.terminal) else null,
            if (!prefs.mirrorOn) stringResource(R.string.paused) else if (!termMode && !webMode) "${app.mirrorFps} ${stringResource(R.string.fps)}" else null,
        ).joinToString(" · ")
    }

    val hints: List<Hint> = when {
        shrunk -> listOf(Hint("◀", stringResource(R.string.hint_back_to_window)))
        !prefs.mirrorOn -> listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_resume)), Hint(stringResource(R.string.hint_swipe), stringResource(R.string.hint_window)))
        termMode -> listOf(
            Hint(stringResource(R.string.hint_drag), stringResource(R.string.hint_scroll)),
            Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_follow)),
            Hint(stringResource(R.string.hint_pinch), stringResource(R.string.hint_size)),
            Hint(stringResource(R.string.hint_swipe), stringResource(R.string.hint_window)),
        )
        webMode -> listOf(Hint("◀", "back in the page"))
        else -> listOf(
            Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_click)),
            Hint(stringResource(R.string.hint_hold), stringResource(R.string.hint_right)),
            Hint(stringResource(R.string.hint_swipe), stringResource(R.string.hint_window)),
            Hint(stringResource(R.string.hint_pinch), stringResource(R.string.hint_zoom)),
        )
    }

    // The terminal's keyboard sink: opened only from the strip's keyboard cap.
    var termKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(termMode) { if (!termMode) termKeyboard = false }

    LxCard(
        modifier = modifier,
        active = !shrunk,
        top = {
            // The window's name is the way in to everything about it.
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(lx.radiusRow))
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.window)) { if (shrunk) onCollapse() else onSheet(Sheet.Window) }
                    .padding(horizontal = lx.u(0.3f), vertical = lx.u(0.2f)),
            ) {
                LxWordmark(title)
                LxCaption(caption)
            }
            LxButton(null, icon = Icons.Filled.Keyboard, pressed = sheet == Sheet.Keys, contentDescription = stringResource(R.string.keys)) { onSheet(Sheet.Keys) }
            SayButton(ready = ready)
            LxButton(null, icon = Icons.Filled.Headphones, pressed = sheet == Sheet.Ears, contentDescription = stringResource(R.string.ears)) { onSheet(Sheet.Ears) }
            LxButton(null, icon = Icons.Filled.Tune, pressed = sheet == Sheet.More, contentDescription = stringResource(R.string.more)) { onSheet(Sheet.More) }
        },
        tilePadding = if (shrunk) lx.u(0.5f) else lx.inset,
        tile = {
            // The surface: the picture, the text or the page. Never taken out
            // of the composition to show something else — its effects run the
            // mirror and the terminal, and a sheet draws under it, not instead.
            val surfaceModifier = when {
                // Shrunk under a sheet: a tap on the sliver brings the window back.
                shrunk -> Modifier.fillMaxWidth().height(lx.u(3.2f)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.hint_back_to_window)) { onCollapse() }
                termMode || webMode -> Modifier.fillMaxWidth().weight(1f)
                else -> Modifier.fillMaxWidth().heightIn(max = lx.u(22f))
            }
            DesktopCanvas(
                app = app, prefs = prefs, ready = ready, agentSession = agentSession, termMode = termMode,
                webPage = webPage, webMode = webMode, onWebTitle = onWebTitle, compact = shrunk,
                modifier = surfaceModifier,
            )
            if (!shrunk && !termMode && !webMode) {
                LxSectionGap()
                WindowList(desk = desk, ws = ws, prefs = prefs, enabled = ready, modifier = Modifier.weight(1f, fill = true))
            }
            if (!shrunk) {
                Spacer(Modifier.height(lx.u(0.4f)))
                val (text, tone) = notice ?: when {
                    !linkUp -> stringResource(R.string.not_linked) to StatusTone.Info
                    !prefs.mirrorOn && !termMode && !webMode -> stringResource(R.string.paused_status) to StatusTone.Info
                    else -> (stringResource(R.string.linked) + " · " + desk.outputs.joinToString(" · ") { Names.display(it.name, desk.outputs) }) to StatusTone.Ok
                }
                LxStatus(text, tone)
            }
        },
        between = if (termMode && !shrunk) {
            {
                TerminalKeyStrips(
                    enabled = ready && termScreen != null,
                    onInput = { Link.termInput(it) },
                    keyboardOpen = termKeyboard,
                    onKeyboard = { termKeyboard = !termKeyboard },
                )
            }
        } else null,
        bottom = {
            LxButton("?", contentDescription = stringResource(R.string.gestures), pressed = sheet == Sheet.Gestures) { onSheet(Sheet.Gestures) }
            LxHints(if (closeArm.armed) listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_close))) else hints)
            LxButton(
                stringResource(R.string.close), ButtonKind.Danger,
                enabled = ready && focused != null, armed = closeArm.armed,
            ) {
                if (closeArm.press()) focused?.let { Link.act("CloseWindow", "id" to it.id) }
            }
        },
    )
}

/**
 * The other windows on this workspace, as rows: the visible twin of the swipe,
 * with the desktop's own state markers. The focused one is the current row.
 */
@Composable
private fun WindowList(desk: Link.Desk, ws: Link.Workspace?, prefs: CanvasPrefs, enabled: Boolean, modifier: Modifier = Modifier) {
    val windows = when {
        prefs.wholeOutput -> desk.windows.filter { it.output == prefs.output }
        ws != null -> desk.windowsOn(ws.id)
        else -> desk.windows
    }
    Column(modifier) {
        LxSection(
            stringResource(R.string.windows),
            badge = listOfNotNull(Names.workspace(ws), windows.size.toString()).joinToString(" · "),
            here = true,
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            windows.forEachIndexed { i, w ->
                if (i > 0) LxDivider()
                val kind = Names.windowKind(w)
                LxRow(
                    name = Names.windowTitle(w),
                    meta = if (w.focused) "$kind · ${stringResource(R.string.showing)}" else if (!w.visible) "$kind · ${stringResource(R.string.hidden)}" else kind,
                    marker = when {
                        w.focused -> Marker.Attached
                        !w.visible -> Marker.Exited
                        w.app.startsWith(AGENT_APP_PREFIX) -> Marker.Background
                        else -> Marker.Idle
                    },
                    current = w.focused,
                    dim = !w.visible,
                    enabled = enabled,
                    onClick = {
                        if (!w.visible) Link.act("ShowWindow", "id" to w.id)
                        Link.act("FocusWindow", "id" to w.id)
                    },
                )
            }
        }
    }
}

/**
 * Say: the mic. Replaces the rail's dictate entry; the result comes back out
 * of band on `Link.dict` and lands on the canvas card's status line.
 */
@Composable
private fun SayButton(ready: Boolean) {
    val ctx = LocalContext.current
    val active by DictationService.active.collectAsState()
    val level by DictationService.level.collectAsState()
    val dict by Link.dict.collectAsState()
    val transcribing = dict is Link.DictState.Transcribing
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) DictationService.toggle(ctx)
    }
    // The mic level breathes the button while a take is open — the one live
    // thing on the band, and only while recording. animateFloatAsState, never
    // a LaunchedEffect keyed on `level`: that would restart a tween on every
    // 100 ms frame and never finish one.
    val pulse by animateFloatAsState(if (active) 1f + (level.coerceIn(0f, 1f) * 0.18f) else 1f, tween(120), label = "micLevel")
    LxButton(
        null,
        kind = if (active) ButtonKind.Danger else ButtonKind.Quiet,
        icon = if (active) Icons.Filled.Stop else Icons.Filled.Mic,
        pressed = transcribing,
        armed = active,
        enabled = ready || active,
        contentDescription = stringResource(R.string.say),
        modifier = Modifier.scale(pulse),
    ) {
        Interaction.touch()
        if (!granted) ask.launch(Manifest.permission.RECORD_AUDIO) else DictationService.toggle(ctx)
    }
}

/** Before pairing: the one card, and the one job. */
@Composable
private fun FirstRunCard(modifier: Modifier = Modifier, onTypeCode: () -> Unit) {
    val ctx = LocalContext.current
    LxCard(
        modifier = modifier,
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.app_name))
                LxCaption(stringResource(R.string.not_paired))
            }
        },
        tile = {
            Spacer(Modifier.weight(1f))
            LxEmptyState(
                headline = stringResource(R.string.pair_headline),
                steps = listOf(stringResource(R.string.pair_step_1), stringResource(R.string.pair_step_2), stringResource(R.string.pair_step_3)),
                footnote = stringResource(R.string.pair_footnote),
            )
            Spacer(Modifier.weight(1f))
        },
        bottom = {
            LxHints(listOf(Hint(stringResource(R.string.scan), stringResource(R.string.hint_scan))))
            LxButton(stringResource(R.string.type_the_code), onClick = onTypeCode)
            LxButton(stringResource(R.string.scan), ButtonKind.Primary, icon = Icons.Filled.QrCodeScanner) {
                // The stock camera reads the desktop's QR and hands the
                // lattice://pair link back to MainActivity.
                runCatching { ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        },
    )
}

/** The gesture sheet: every gesture the surface answers, as rows. The `?` opens it. */
@Composable
private fun GesturesCard(termMode: Boolean, webMode: Boolean) {
    val rows: List<Pair<String, String>> = when {
        termMode -> listOf(
            stringResource(R.string.g_drag_term) to stringResource(R.string.g_drag_term_t),
            stringResource(R.string.g_tap_term) to stringResource(R.string.g_tap_term_t),
            stringResource(R.string.g_pinch) to stringResource(R.string.g_pinch_term_t),
            stringResource(R.string.g_swipe) to stringResource(R.string.g_swipe_mirror),
        )
        webMode -> listOf(
            stringResource(R.string.g_tap) to "the page's own",
            stringResource(R.string.g_pinch) to "the page's own",
        )
        else -> listOf(
            stringResource(R.string.g_tap) to stringResource(R.string.g_tap_mirror),
            stringResource(R.string.g_double) to stringResource(R.string.g_double_mirror),
            stringResource(R.string.g_hold) to stringResource(R.string.g_hold_mirror),
            stringResource(R.string.g_swipe) to stringResource(R.string.g_swipe_mirror),
            stringResource(R.string.g_pinch) to stringResource(R.string.g_pinch_mirror),
        )
    }
    LxCard(
        active = true,
        top = { Column(Modifier.weight(1f)) { LxWordmark(stringResource(R.string.gestures_title)) } },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) LxDivider()
                    LxRow(name = k, meta = v)
                }
            }
        },
        bottom = { LxHints(listOf(Hint("◀", stringResource(R.string.hint_back_to_window)))) },
    )
}
