package com.lattice.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Everything about the window in front of you, one tap behind its name.
 *
 * There is deliberately no list of other windows here: the canvas swipes
 * between them, and a phone-sized list of a 10-workspace desktop was three
 * identically-truncated `tmux-attach-claude-nix…` rows and a lot of scrolling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WindowSheetContent(
    desk: Link.Desk,
    prefs: CanvasPrefs,
    agentSession: String?,
    termMode: Boolean,
    webPage: WebPage?,
    webMode: Boolean,
    enabled: Boolean,
    onDone: () -> Unit,
) {
    val w = desk.focused
    val ws = w?.workspace?.let { id -> desk.workspaces.firstOrNull { it.id == id } }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            w?.title?.ifBlank { null } ?: w?.app?.ifBlank { null } ?: "No focused window",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                w?.app?.ifBlank { null },
                ws?.name?.ifBlank { null } ?: ws?.let { "ws ${it.idx}" },
                w?.output?.ifBlank { null },
            ).joinToString(" · ").ifBlank { "focus a window on the desktop" },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )

        // --- what the canvas is showing ------------------------------------
        Text("View", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // On an agent window the two modes sit side by side; everywhere
            // else "focused window" just means the mirror, as it always has.
            if (agentSession != null && !prefs.wholeOutput) {
                FilterChip(selected = termMode, onClick = { prefs.mirrorFor = null }, label = { Text("terminal") })
                FilterChip(selected = !termMode, onClick = { prefs.mirrorFor = agentSession }, label = { Text("mirror") })
            } else if (webPage != null && !prefs.wholeOutput) {
                // Same pair as the terminal's, for the same reason: the page
                // renders here, but the picture is the desktop's own
                // authenticated screen and stays one tap away — which matters
                // most for exactly the sites the phone is logged out of.
                FilterChip(selected = webMode, onClick = { prefs.mirrorForWeb = null }, label = { Text("web") })
                FilterChip(selected = !webMode, onClick = { prefs.mirrorForWeb = webPage.url }, label = { Text("mirror") })
            } else {
                FilterChip(
                    selected = !prefs.wholeOutput,
                    onClick = { prefs.wholeOutput = false },
                    label = { Text("focused window") },
                )
            }
            desk.outputs.forEach { o ->
                FilterChip(
                    selected = prefs.wholeOutput && prefs.output == o.name,
                    onClick = { prefs.wholeOutput = true; prefs.output = o.name },
                    label = { Text(o.name) },
                )
            }
            FilterChip(
                selected = prefs.mirrorOn,
                onClick = { prefs.mirrorOn = !prefs.mirrorOn },
                label = { Text(if (prefs.mirrorOn) "live" else "paused") },
            )
        }

        // --- what to do to it ----------------------------------------------
        if (w != null) {
            Text("Window", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            WindowActions(w, desk, enabled = enabled, onDone = onDone)
        }
        Spacer12()
    }
}

@Composable private fun Spacer12() = androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))

/**
 * The compositor verbs. The ones that act on a column act on the FOCUSED
 * column, so those focus the window first — `MoveColumnLeft` has no window
 * argument to give it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WindowActions(w: Link.Win, desk: Link.Desk, enabled: Boolean, onDone: () -> Unit) {
    fun focusThen(vararg actions: JSONObject) {
        Link.act("FocusWindow", "id" to w.id)
        actions.forEach { Link.act(it) }
        onDone()
    }
    fun unit(name: String) = JSONObject().put(name, JSONObject())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AssistChip(onClick = { Link.act("FullscreenWindow", "id" to w.id); onDone() }, label = { Text("fullscreen") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("MaximizeColumn")) }, label = { Text("maximize") }, enabled = enabled)
        AssistChip(onClick = { Link.act("ToggleWindowFloating", "id" to w.id); onDone() }, label = { Text(if (w.floating) "tile" else "float") }, enabled = enabled)
        AssistChip(onClick = { Link.act(if (w.visible) "HideWindow" else "ShowWindow", "id" to w.id); onDone() }, label = { Text(if (w.visible) "hide" else "show") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("SwitchPresetColumnWidth")) }, label = { Text("width ⇄") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("MoveColumnLeft")) }, label = { Text("← column") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("MoveColumnRight")) }, label = { Text("column →") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("MoveWindowToWorkspaceUp")) }, label = { Text("ws ▲") }, enabled = enabled)
        AssistChip(onClick = { focusThen(unit("MoveWindowToWorkspaceDown")) }, label = { Text("ws ▼") }, enabled = enabled)
        desk.outputs.filter { it.name != w.output }.forEach { o ->
            AssistChip(onClick = { Link.act("MoveWindowToMonitor", "id" to w.id, "output" to o.name); onDone() }, label = { Text("→ ${o.name}") }, enabled = enabled)
        }
    }
}
