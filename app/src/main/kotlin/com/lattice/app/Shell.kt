package com.lattice.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the canvas is showing. Hoisted out of the canvas itself because the
 * title strip, the window sheet and the input sheet all need to read it — the
 * input sheet in particular has to know whether it is typing at a tmux pane or
 * at the compositor.
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

/**
 * The app: one canvas showing whatever window the desktop has focused, and a
 * rail of things to do to it. There is no tab bar and no window list — the
 * canvas swipes between windows and everything else is a sheet over it.
 */
@Composable
fun LatticeApp(app: AppState) {
    val prefs = remember { CanvasPrefs() }
    val desk by Link.desk.collectAsState()
    val client by Link.client.collectAsState()
    val linkState by (client?.state ?: remember { MutableStateFlow(ControlChannelClient.LinkState.Disconnected) }).collectAsState()
    val bridgeUp by (client?.bridgeUp ?: remember { MutableStateFlow(false) }).collectAsState()
    val dict by Link.dict.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val snap = rememberSnapcastState(app.host)

    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    BackHandler(enabled = sheet != null) { sheet = null }

    val ready = bridgeUp &&
        (linkState as? ControlChannelClient.LinkState.Connected)?.proto?.let { it >= 2 } == true
    val focused = desk.focused

    // A dispatched agent's terminal is a `tmux-attach-<session>` window; on one
    // of those the canvas reads the pane as text instead of mirroring a picture
    // of it. Computed here rather than in the canvas because the input sheet
    // routes its keystrokes by the same answer.
    val agentSession = focused?.app
        ?.takeIf { it.startsWith(AGENT_APP_PREFIX) }
        ?.removePrefix(AGENT_APP_PREFIX)
        ?.takeIf { it.isNotEmpty() }
    val termMode = agentSession != null && !prefs.wholeOutput && prefs.mirrorFor != agentSession

    // And a browser window is a page: render it here instead of watching a
    // JPEG of it. Same shape as the terminal and computed in the same place,
    // for the same reason — the title strip and the window sheet both have to
    // agree with the canvas about what is on screen.
    //
    // The two are disjoint by construction: an agent terminal is a `foot`
    // window and a web page is a browser one, so `termMode` wins any tie
    // without either having to know about the other.
    val webPage = if (termMode) null else webPageFor(focused)
    val webMode = webPage != null && !prefs.wholeOutput && prefs.mirrorForWeb != webPage.url
    // The page's own name, reported up by the WebView once it has loaded.
    // The desktop window's title is the wrong thing to show for a kiosk: it
    // is the `dashboard-web · <url>` marker the address was parsed out of,
    // so it would just repeat the address bar back at you.
    var webTitle by remember { mutableStateOf<String?>(null) }

    // Dictation outcomes surface wherever you are.
    LaunchedEffect(dict) {
        when (val d = dict) {
            is Link.DictState.Done -> { snackbar.showSnackbar("Typed: " + d.text.take(80)); Link.clearDict() }
            is Link.DictState.Failed -> { snackbar.showSnackbar("Dictation: " + d.reason); Link.clearDict() }
            else -> {}
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
        Row(Modifier.fillMaxSize()) {
            // The canvas column. The sheet layer lives inside it, which is what
            // keeps the scrim off the rail without any z-order games.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                // The nav bar inset belongs on the canvas column, not on the
                // Box: the sheet layer below takes its own (ime ∪ navigationBars)
                // and would be padded twice. The title strip takes the status
                // bar itself for the same reason.
                //
                // Without this the terminal's key bar renders UNDER an opaque
                // 3-button nav bar — measured at y=2296 against a bar starting
                // at 2274, i.e. `esc` and the arrows were unreachable.
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                    TitleStrip(
                        focused = focused,
                        wholeOutput = prefs.wholeOutput,
                        outputName = prefs.output,
                        termMode = termMode,
                        webMode = webMode,
                        webTitle = webTitle,
                        linked = ready,
                        onClick = { sheet = Sheet.Window },
                    )
                    DesktopCanvas(
                        app = app,
                        prefs = prefs,
                        ready = ready,
                        agentSession = agentSession,
                        termMode = termMode,
                        webPage = webPage,
                        webMode = webMode,
                        onWebTitle = { webTitle = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                SheetHost(open = sheet, onDismiss = { sheet = null }) { which, scroll ->
                    when (which) {
                        Sheet.Window -> WindowSheetContent(
                            desk = desk,
                            prefs = prefs,
                            agentSession = agentSession,
                            termMode = termMode,
                            webPage = webPage,
                            webMode = webMode,
                            enabled = client?.sessionReady == true,
                            onDone = { Link.requestDesk() },
                        )
                        Sheet.Input -> InputSheetContent(ready = ready, termMode = termMode, scroll = scroll)
                        Sheet.Audio -> AudioSheetContent(app, snap)
                        Sheet.Settings -> SettingsSheetContent(app)
                    }
                }

                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).padding(12.dp).zIndex(2f),
                ) { Snackbar(it) }
            }

            Rail(
                open = sheet,
                onOpen = { s -> sheet = if (sheet == s) null else s },
                onCloseWindow = { focused?.let { Link.act("CloseWindow", "id" to it.id) } },
                closeEnabled = ready && focused != null,
            )
        }
    }
}

/**
 * What you are looking at, and the way in to everything else about it.
 *
 * The window's own title leads and its app-id follows, which is the way round
 * that tells three agent terminals apart — they all share the app-id prefix
 * `tmux-attach-claude-…` and differ only in the title.
 */
@Composable
private fun TitleStrip(
    focused: Link.Win?,
    wholeOutput: Boolean,
    outputName: String?,
    termMode: Boolean,
    webMode: Boolean,
    webTitle: String?,
    linked: Boolean,
    onClick: () -> Unit,
) {
    val primary = when {
        wholeOutput -> outputName ?: "whole output"
        focused == null -> "no focused window"
        // In web mode the page names itself; fall back to the window's title
        // only until the first load finishes.
        webMode && webTitle != null -> webTitle
        else -> focused.title.ifBlank { focused.app.ifBlank { "untitled" } }
    }
    val secondary = when {
        wholeOutput -> "every window on this output"
        focused == null -> if (linked) "focus something on the desktop" else "not linked"
        // A Chromium app-mode app-id is the URL with its punctuation mangled
        // (`chrome-claude.ai__artifact_BGVn…-Default`) — 50-odd characters
        // that say nothing the address bar right below is not already saying
        // properly, and long enough to push the mode label off the end of the
        // line. In web mode the app-id is noise; everywhere else it is the
        // thing that tells three agent terminals apart.
        webMode -> "web"
        else -> buildString {
            append(focused.app.ifBlank { "?" })
            if (termMode) append(" · terminal")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(46.dp)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (linked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "this window",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}
