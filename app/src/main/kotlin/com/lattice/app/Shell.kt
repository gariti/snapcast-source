package com.lattice.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxDivider
import com.lattice.app.lx.LxEmptyState
import com.lattice.app.lx.LxField
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxKey
import com.lattice.app.lx.LxRow
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxSectionGap
import com.lattice.app.lx.LxStage
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxTitleDoor
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.Marker
import com.lattice.app.lx.StatusTone
import com.lattice.app.lx.rememberArm
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * What the canvas is showing. Hoisted out of the canvas itself because the
 * band, the Window card and the Keys card all need to read it.
 */
class CanvasPrefs {
    var wholeOutput by mutableStateOf(false)
    var output by mutableStateOf<String?>(null)
    var mirrorOn by mutableStateOf(true)
    var mirrorFor by mutableStateOf<String?>(null)
    var mirrorForWeb by mutableStateOf<String?>(null)
}

/** The second card on the stage, if any. */
enum class Sheet { Window, Keys, Say, Ears, More, Gestures }

/** A page inside the More card. */
enum class MorePage { Desktop, Music, Hidden, Link }

/**
 * The app: a STAGE with one card — the window the desktop has focused — and,
 * when you ask for one, a second card that rises under it. No rail, no tab
 * bar: the canvas card's own bands hold every command, the canvas swipes
 * between windows. The desktop's own shape (a Card App), on a phone.
 */
@Composable
fun LatticeApp(app: AppState) {
    val ctx = LocalContext.current
    val prefs = remember { CanvasPrefs() }
    val desk by Link.desk.collectAsState()
    val client by Link.client.collectAsState()
    val linkState by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val bridgeUp by (client?.bridgeUp ?: remember { MutableStateFlow(false) }).collectAsState()
    val dict by Link.dict.collectAsState()

    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var morePage by rememberSaveable { mutableStateOf<MorePage?>(null) }
    BackHandler(enabled = sheet != null) {
        if (sheet == Sheet.More && morePage != null) morePage = null else sheet = null
    }

    // ONE meaning of "linked": every surface gates on this.
    val ready = bridgeUp &&
        (linkState as? ControlChannelClient.LinkState.Connected)?.proto?.let { it >= 2 } == true
    val focused = desk.focused

    // When the link last came up, and when it last went — for "since 8:14".
    var linkedSince by remember { mutableLongStateOf(0L) }
    var lostSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ready) {
        val now = System.currentTimeMillis()
        if (ready) linkedSince = now else lostSince = now
    }

    val agentSession = focused?.app
        ?.takeIf { it.startsWith(AGENT_APP_PREFIX) }
        ?.removePrefix(AGENT_APP_PREFIX)
        ?.takeIf { it.isNotEmpty() }
    val termMode = agentSession != null && !prefs.wholeOutput && prefs.mirrorFor != agentSession
    val webPage = if (termMode) null else webPageFor(focused)
    val webMode = webPage != null && !prefs.wholeOutput && prefs.mirrorForWeb != webPage.url
    var webTitle by remember { mutableStateOf<String?>(null) }
    val web = rememberWebController()

    // A Say result lands on the canvas card's status line for 4 s.
    var notice by remember { mutableStateOf<Pair<String, StatusTone>?>(null) }
    val typedQuote = stringResource(R.string.typed_quote)
    val couldNotHear = stringResource(R.string.could_not_hear)
    LaunchedEffect(dict) {
        when (val d = dict) {
            is Link.DictState.Done -> { notice = typedQuote.format(d.text.take(60)) to StatusTone.Ok; Link.clearDict() }
            is Link.DictState.Failed -> { notice = couldNotHear to StatusTone.Error; Link.clearDict() }
            else -> {}
        }
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(4000); notice = null } }

    val paired = app.psk.isNotBlank()

    Box(
        Modifier
            .fillMaxSize()
            // Every touch anywhere counts as "using it", so the mirror's idle
            // timer only fires when the phone really is idle.
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
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            when {
                !paired -> FirstRunCard(app, Modifier.weight(1f))
                !ready && sheet != Sheet.More -> LinkLostCard(
                    app = app, since = lostSince, modifier = Modifier.weight(1f),
                    onMore = { sheet = Sheet.More; morePage = null },
                )
                else -> {
                    val shrunk = sheet != null
                    CanvasCard(
                        app = app, prefs = prefs, desk = desk, ready = ready, web = web,
                        agentSession = agentSession, termMode = termMode, webPage = webPage, webMode = webMode,
                        webTitle = webTitle, onWebTitle = { webTitle = it },
                        sheet = sheet,
                        onSheet = { s -> sheet = if (sheet == s) null else s; if (s != Sheet.More) morePage = null },
                        onCollapse = { sheet = null; morePage = null },
                        shrunk = shrunk, notice = notice,
                        modifier = if (shrunk) Modifier else Modifier.weight(1f),
                    )
                }
            }

            // The second card, rendered for the LAST sheet asked for so the
            // slide-out animates a full card, never an empty box.
            var shown by remember { mutableStateOf(Sheet.Window) }
            LaunchedEffect(sheet) { if (sheet != null) shown = sheet!! }
            val rise by animateFloatAsState(if (sheet != null && paired) 1f else 0f, tween(LxTheme.current.fast), label = "rise")
            if (paired && (sheet != null || rise > 0.01f)) {
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
                        Sheet.Say -> SayCard(ready = ready, focused = focused, onDone = { if (sheet == Sheet.Say) sheet = null })
                        Sheet.Ears -> EarsCard(app = app, ready = ready)
                        Sheet.More -> MoreCard(app = app, desk = desk, linkedSince = linkedSince, page = morePage, onPage = { morePage = it })
                        Sheet.Gestures -> GesturesCard()
                    }
                }
            }
        }
    }
}

/** Focus the next or previous window on the desktop: the swipe's verb, and its tappable twin. */
private fun focusNeighbour(dir: Int) {
    Link.act(JSONObject().put(if (dir < 0) "FocusWindowDownOrColumnRight" else "FocusWindowUpOrColumnLeft", JSONObject()))
}

/**
 * The canvas card: what the desktop has focused. The top band is the window's
 * name (the Window card's door) and the four commands (keys · say · ears ·
 * more); the tile is the picture, the text or the page, with the other
 * windows under it; the bottom band is `?`, ONE hint, and close.
 */
@Composable
private fun CanvasCard(
    app: AppState,
    prefs: CanvasPrefs,
    desk: Link.Desk,
    ready: Boolean,
    web: WebController,
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
    // The caption is workspace · fps only: short enough never to truncate.
    val caption = when {
        prefs.wholeOutput -> stringResource(R.string.whole_display)
        focused == null -> stringResource(R.string.focus_something)
        termMode -> "${stringResource(R.string.agent_session)} · ${(app.termZoom * 100).toInt()} %"
        webMode -> listOfNotNull(stringResource(R.string.web_page), Names.workspace(ws)).joinToString(" · ")
        else -> listOfNotNull(
            Names.workspace(ws),
            if (!prefs.mirrorOn) stringResource(R.string.paused) else "${app.mirrorFps} ${stringResource(R.string.fps)}",
        ).joinToString(" · ")
    }

    // ONE hint: the least guessable gesture on this surface.
    val hint: Hint? = when {
        closeArm.armed -> Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_close))
        !prefs.mirrorOn && !termMode && !webMode -> Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_pill_resume))
        termMode -> Hint(stringResource(R.string.hint_drag), stringResource(R.string.hint_history))
        webMode -> null
        else -> Hint(stringResource(R.string.hint_hold), stringResource(R.string.hint_right_click))
    }

    var termKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(termMode) { if (!termMode) termKeyboard = false }

    // Web: "signed out here", once per site.
    val seenSites = remember { mutableSetOf<String>() }
    val site = webPage?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    var signedOutNote by remember { mutableStateOf(false) }
    LaunchedEffect(site, webMode) { signedOutNote = webMode && site != null && seenSites.add(site) }

    // TalkBack's custom actions on the picture: every gesture's tappable twin.
    val aNext = stringResource(R.string.act_next_window); val aPrev = stringResource(R.string.act_prev_window)
    val aRight = stringResource(R.string.act_right_click); val aIn = stringResource(R.string.act_zoom_in); val aOut = stringResource(R.string.act_zoom_out)
    val aPause = stringResource(R.string.act_pause); val aResume = stringResource(R.string.act_resume)
    var zoomRequest by remember { mutableStateOf(0) }
    val pictureActions = listOf(
        CustomAccessibilityAction(aNext) { focusNeighbour(-1); true },
        CustomAccessibilityAction(aPrev) { focusNeighbour(1); true },
        CustomAccessibilityAction(aRight) { Link.pointerIn(0.5f, 0.5f); Link.click("right"); true },
        CustomAccessibilityAction(aIn) { zoomRequest++; true },
        CustomAccessibilityAction(aOut) { zoomRequest--; true },
        CustomAccessibilityAction(if (prefs.mirrorOn) aPause else aResume) { prefs.mirrorOn = !prefs.mirrorOn; true },
    )
    val pictureName = stringResource(R.string.picture_of, title)

    LxCard(
        modifier = modifier,
        active = !shrunk,
        top = {
            LxTitleDoor(
                title = title, caption = caption, unfold = !shrunk,
                contentDescription = if (shrunk) stringResource(R.string.back_to_window_a11y, title) else stringResource(R.string.window_actions, title),
                // On a page the picture owns its swipes, so ⇆ window is the title.
                modifier = if (webMode && !shrunk) Modifier.pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (abs(dx) > 72.dp.toPx()) focusNeighbour(if (dx < 0) -1 else 1) },
                        onHorizontalDrag = { _, d -> dx += d },
                    )
                } else Modifier,
            ) { if (shrunk) onCollapse() else onSheet(Sheet.Window) }
            LxButton(null, icon = Icons.Filled.Keyboard, pressed = sheet == Sheet.Keys, contentDescription = stringResource(R.string.keys)) { onSheet(Sheet.Keys) }
            LxButton(null, icon = Icons.Filled.Mic, pressed = sheet == Sheet.Say, contentDescription = stringResource(R.string.say)) { onSheet(Sheet.Say) }
            LxButton(null, icon = Icons.Filled.Headphones, pressed = sheet == Sheet.Ears, contentDescription = stringResource(R.string.ears)) { onSheet(Sheet.Ears) }
            LxButton(null, icon = Icons.Filled.Tune, pressed = sheet == Sheet.More, contentDescription = stringResource(R.string.more)) { onSheet(Sheet.More) }
        },
        tilePadding = if (shrunk) lx.u(0.5f) else lx.inset,
        tile = {
            if (webMode && webPage != null && !shrunk) {
                // The page's address, as a field: tap to type another.
                var editing by remember { mutableStateOf<String?>(null) }
                LxField(
                    stringResource(R.string.page_label), editing ?: prettyUrl(web.current.ifBlank { webPage.url }),
                    { editing = it }, enabled = ready,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    onDone = { editing?.let { web.load(it) }; editing = null },
                )
                Spacer(Modifier.height(lx.u(0.5f)))
            }
            val surfaceModifier = when {
                shrunk -> Modifier.fillMaxWidth().height(lx.u(3.2f)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.hint_back_to_window)) { onCollapse() }
                termMode || webMode -> Modifier.fillMaxWidth().weight(1f)
                else -> Modifier.fillMaxWidth().heightIn(max = lx.u(22f))
            }.semantics { contentDescription = pictureName; if (!shrunk && !webMode) customActions = pictureActions }
            DesktopCanvas(
                app = app, prefs = prefs, ready = ready, agentSession = agentSession, termMode = termMode,
                webPage = webPage, webMode = webMode, web = web, onWebTitle = onWebTitle, compact = shrunk,
                zoomRequest = zoomRequest,
                modifier = surfaceModifier,
            )
            if (!shrunk && !termMode && !webMode) {
                LxSectionGap()
                WindowList(desk = desk, ws = ws, prefs = prefs, enabled = ready, modifier = Modifier.weight(1f, fill = true))
            }
            if (!shrunk) {
                Spacer(Modifier.height(lx.u(0.4f)))
                val offDisplays = desk.outputs.filter { o -> desk.workspaces.none { it.output == o.name } }
                val sOff = stringResource(R.string.display_off)
                val (text, tone) = when {
                    notice != null -> notice
                    webMode && web.failure != null -> stringResource(R.string.page_did_not_load) to StatusTone.Error
                    webMode && signedOutNote -> stringResource(R.string.signed_out_here) to StatusTone.Warn
                    !prefs.mirrorOn && !termMode && !webMode -> stringResource(R.string.paused_status) to StatusTone.Info
                    else -> (stringResource(R.string.linked) + offDisplays.joinToString("") { " · " + sOff.format(Names.display(it.name, desk.outputs).lowercase()) }) to StatusTone.Ok
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
        bottom = if (shrunk) null else {
            {
                LxButton("?", contentDescription = stringResource(R.string.gestures), pressed = sheet == Sheet.Gestures) { onSheet(Sheet.Gestures) }
                LxHints(listOfNotNull(hint))
                if (webMode && webPage != null) {
                    LxButton(null, icon = Icons.Filled.Refresh, contentDescription = stringResource(R.string.reload)) { web.reload() }
                    LxButton(null, icon = Icons.Filled.DesktopWindows, contentDescription = stringResource(R.string.open_on_desktop)) {
                        Link.act("Spawn", "command" to org.json.JSONArray(listOf("xdg-open", web.current.ifBlank { webPage.url })))
                    }
                }
                LxButton(
                    stringResource(R.string.close), ButtonKind.Danger,
                    enabled = ready && focused != null, armed = closeArm.armed,
                    contentDescription = stringResource(R.string.close_x, title),
                ) {
                    if (closeArm.press()) focused?.let { Link.act("CloseWindow", "id" to it.id) }
                }
            }
        },
    )
}

/** The other windows on this workspace, as rows: the visible twin of the swipe. */
@Composable
private fun WindowList(desk: Link.Desk, ws: Link.Workspace?, prefs: CanvasPrefs, enabled: Boolean, modifier: Modifier = Modifier) {
    val windows = when {
        prefs.wholeOutput -> desk.windows.filter { it.output == prefs.output }
        ws != null -> desk.windowsOn(ws.id)
        else -> desk.windows
    }
    val listName = stringResource(R.string.windows)
    Column(modifier) {
        LxSection(listName, badge = listOfNotNull(Names.workspace(ws), windows.size.toString()).joinToString(" · "), here = true)
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .semantics { contentDescription = listName; collectionInfo = CollectionInfo(windows.size, 1) },
        ) {
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
                    current = w.focused, dim = !w.visible, enabled = enabled,
                ) {
                    if (!w.visible) Link.act("ShowWindow", "id" to w.id)
                    Link.act("FocusWindow", "id" to w.id)
                }
            }
        }
    }
}

/** Before pairing (frame A): one card, one job — and the code dialog over it (A2). */
@Composable
private fun FirstRunCard(app: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lx = LxTheme.current
    val pairing = rememberPairingState()
    val strings = rememberPairingStrings()
    var typing by remember { mutableStateOf(false) }
    BackHandler(enabled = typing) { typing = false }
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
            Box(Modifier.alpha(if (typing) 0.45f else 1f)) {
                LxEmptyState(
                    headline = stringResource(R.string.pair_headline),
                    steps = listOf(stringResource(R.string.pair_step_1), stringResource(R.string.pair_step_2), stringResource(R.string.pair_step_3)),
                    footnote = stringResource(R.string.pair_footnote),
                )
            }
            if (typing) {
                Spacer(Modifier.height(lx.u(0.8f)))
                PairingDialog(app, pairing, enabled = !pairing.searching)
            }
            Spacer(Modifier.weight(1f))
        },
        bottom = {
            if (typing) {
                LxHints(listOf(Hint("◀", stringResource(R.string.back_to_scan))))
                // connect appears once the code is complete, never dimmed before.
                if (pairing.complete(app.psk)) LxButton(stringResource(R.string.connect), ButtonKind.Primary, enabled = !pairing.searching) {
                    pairing.connect(ctx, app, scope, strings)
                }
            } else {
                Spacer(Modifier.weight(1f))
                LxButton(stringResource(R.string.type_the_code)) { typing = true }
                LxButton(stringResource(R.string.scan), ButtonKind.Primary, icon = Icons.Filled.QrCodeScanner) { openScanner(ctx) }
            }
        },
    )
}

/** Paired, not answering (frame L): keeps the desktop's name, retries on its own, offers More only. */
@Composable
private fun LinkLostCard(app: AppState, since: Long, modifier: Modifier = Modifier, onMore: () -> Unit) {
    val ctx = LocalContext.current
    val host = Names.host(app.host)
    val time = remember(since) { if (since > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(since)) else null }
    LxCard(
        modifier = modifier,
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(host)
                LxCaption(time?.let { stringResource(R.string.not_answering_since, it) } ?: stringResource(R.string.not_answering))
            }
            LxButton(null, icon = Icons.Filled.Tune, contentDescription = stringResource(R.string.more), onClick = onMore)
        },
        tile = {
            Spacer(Modifier.weight(1f))
            LxEmptyState(
                headline = stringResource(R.string.desktop_not_answering, host),
                steps = listOf(stringResource(R.string.lost_step_1), stringResource(R.string.lost_step_2), stringResource(R.string.lost_step_3)),
                footnote = stringResource(R.string.lost_footnote),
            )
            Spacer(Modifier.weight(1f))
            LxStatus(time?.let { stringResource(R.string.last_answer, it) } ?: stringResource(R.string.not_answering), StatusTone.Warn)
        },
        bottom = {
            Spacer(Modifier.weight(1f))
            LxButton(stringResource(R.string.try_now), ButtonKind.Primary, icon = Icons.Filled.Refresh) {
                // The beacon owns the control channel; bouncing it reconnects now.
                if (MediaSessionListener.isAccessGranted(ctx)) { MediaSessionBeaconService.stop(ctx); MediaSessionBeaconService.start(ctx) }
            }
        },
    )
}

/** The gesture sheet (frame N): every gesture, the cap in a gutter, and the tap that does the same. */
@Composable
private fun GesturesCard() {
    val lx = LxTheme.current
    @Composable fun G(cap: String, name: String, meta: String) {
        LxRow(name, meta, trailing = null, marker = null, icon = null, modifier = Modifier)
    }
    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.gestures_title))
                LxCaption(stringResource(R.string.gestures_caption))
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxSection(stringResource(R.string.on_the_picture), here = true)
                GestureRow(stringResource(R.string.hint_tap), stringResource(R.string.g_click), stringResource(R.string.g_click_m))
                GestureRow(stringResource(R.string.hint_hold), stringResource(R.string.g_right), stringResource(R.string.g_right_m))
                GestureRow(stringResource(R.string.g_hold_move), stringResource(R.string.g_drag), stringResource(R.string.g_drag_m))
                GestureRow(stringResource(R.string.hint_swipe), stringResource(R.string.g_next), stringResource(R.string.g_next_m))
                GestureRow(stringResource(R.string.hint_pinch), stringResource(R.string.g_zoom), stringResource(R.string.g_zoom_m))
                GestureRow(stringResource(R.string.g_swipe_title), stringResource(R.string.g_next), stringResource(R.string.g_next_web_m))
                LxSectionGap()
                LxSection(stringResource(R.string.on_a_terminal))
                GestureRow(stringResource(R.string.hint_drag), stringResource(R.string.g_history), stringResource(R.string.g_history_m))
                GestureRow(stringResource(R.string.hint_pinch), stringResource(R.string.g_text_size), stringResource(R.string.g_text_size_m))
            }
        },
        bottom = { LxHints(listOf(Hint("◀", stringResource(R.string.hint_back_to_window)))) },
    )
}

/** One gesture row: the cap in a fixed gutter, what it does, its tappable twin. */
@Composable
private fun GestureRow(cap: String, name: String, meta: String) {
    val lx = LxTheme.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().heightIn(min = lx.tap).clip(RoundedCornerShape(lx.radiusRow))
            .semantics { contentDescription = "$cap: $name, $meta" },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(lx.u(0.6f)),
    ) {
        Box(Modifier.fillMaxWidth(0.26f)) { LxKey(cap, k = 0.75f) }
        Column(Modifier.weight(1f)) {
            com.lattice.app.lx.LxText(name, com.lattice.app.lx.Type.body.copy(weight = androidx.compose.ui.text.font.FontWeight.Normal))
            com.lattice.app.lx.LxText(meta, com.lattice.app.lx.Type.caption, lx.ink(com.lattice.app.lx.Alpha.secondary), maxLines = 2)
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
@Suppress("unused") private val keepNotifications = Icons.Filled.Notifications
