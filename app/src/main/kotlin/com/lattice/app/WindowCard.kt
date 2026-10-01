package com.lattice.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.lattice.app.lx.LxWordmark
import org.json.JSONObject

/**
 * Everything about the window in front of you, one tap behind its name:
 * what to SHOW (this window, or a whole display; the terminal or its
 * picture) and what to DO to it — the compositor's verbs as rows with a
 * plain-words hint, never niri's names.
 */
@Composable
fun WindowCard(
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
    var moveOpen by remember { mutableStateOf(false) }

    fun unit(name: String) = JSONObject().put(name, JSONObject())
    /** The verbs that act on the focused column focus the window first — they have no window argument. */
    fun focusThen(vararg actions: JSONObject) {
        w ?: return
        Link.act("FocusWindow", "id" to w.id)
        actions.forEach { Link.act(it) }
        onDone()
    }

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.window))
                LxCaption(w?.let { Names.windowTitle(it) } ?: stringResource(R.string.focus_something))
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LxSection(stringResource(R.string.show), here = true)
                LxChips {
                    // On an agent window the two modes sit side by side; on a
                    // page likewise. Everywhere else "this window" is the mirror.
                    when {
                        agentSession != null && !prefs.wholeOutput -> {
                            LxChip(stringResource(R.string.terminal), on = termMode) { prefs.mirrorFor = null }
                            LxChip(stringResource(R.string.mirror), on = !termMode) { prefs.mirrorFor = agentSession }
                        }
                        webPage != null && !prefs.wholeOutput -> {
                            LxChip(stringResource(R.string.page), on = webMode) { prefs.mirrorForWeb = null }
                            LxChip(stringResource(R.string.mirror), on = !webMode) { prefs.mirrorForWeb = webPage.url }
                        }
                        else -> LxChip(stringResource(R.string.this_window), on = !prefs.wholeOutput) { prefs.wholeOutput = false }
                    }
                    desk.outputs.forEach { o ->
                        LxChip(Names.display(o.name, desk.outputs), on = prefs.wholeOutput && prefs.output == o.name) {
                            prefs.wholeOutput = true; prefs.output = o.name
                        }
                    }
                }

                if (w != null) {
                    LxSectionGap()
                    LxSection(stringResource(R.string.do_))
                    LxRow(stringResource(R.string.fullscreen), icon = Icons.Filled.Fullscreen, enabled = enabled) {
                        Link.act("FullscreenWindow", "id" to w.id); onDone()
                    }
                    LxDivider()
                    LxRow(stringResource(R.string.maximize), stringResource(R.string.maximize_meta), icon = Icons.Filled.OpenInFull, enabled = enabled) {
                        focusThen(unit("MaximizeColumn"))
                    }
                    LxDivider()
                    if (w.floating) LxRow(stringResource(R.string.tile), stringResource(R.string.tile_meta), icon = Icons.Filled.ViewColumn, enabled = enabled) {
                        Link.act("ToggleWindowFloating", "id" to w.id); onDone()
                    } else LxRow(stringResource(R.string.float_), stringResource(R.string.float_meta), icon = Icons.Filled.PictureInPictureAlt, enabled = enabled) {
                        Link.act("ToggleWindowFloating", "id" to w.id); onDone()
                    }
                    LxDivider()
                    LxRow(stringResource(R.string.next_width), stringResource(R.string.next_width_meta), icon = Icons.Filled.SwapHoriz, enabled = enabled) {
                        focusThen(unit("SwitchPresetColumnWidth"))
                    }
                    LxDivider()
                    LxRow(stringResource(R.string.move), stringResource(R.string.move_meta), icon = Icons.Filled.SwapHoriz, chevron = true, current = moveOpen, enabled = enabled) {
                        moveOpen = !moveOpen
                    }
                    if (moveOpen) {
                        LxRow(stringResource(R.string.move_left), icon = Icons.Filled.KeyboardArrowLeft, enabled = enabled) { focusThen(unit("MoveColumnLeft")) }
                        LxRow(stringResource(R.string.move_right), icon = Icons.Filled.KeyboardArrowRight, enabled = enabled) { focusThen(unit("MoveColumnRight")) }
                        LxRow(stringResource(R.string.move_up), icon = Icons.Filled.ArrowUpward, enabled = enabled) { focusThen(unit("MoveWindowToWorkspaceUp")) }
                        LxRow(stringResource(R.string.move_down), icon = Icons.Filled.ArrowDownward, enabled = enabled) { focusThen(unit("MoveWindowToWorkspaceDown")) }
                        desk.outputs.filter { it.name != w.output }.forEach { o ->
                            LxRow(stringResource(R.string.move_to, Names.display(o.name, desk.outputs)), icon = Icons.Filled.Monitor, enabled = enabled) {
                                Link.act("MoveWindowToMonitor", "id" to w.id, "output" to o.name); onDone()
                            }
                        }
                    }
                    LxDivider()
                    if (w.visible) LxRow(stringResource(R.string.hide), stringResource(R.string.hide_meta), icon = Icons.Filled.VisibilityOff, enabled = enabled) {
                        Link.act("HideWindow", "id" to w.id); onDone()
                    } else LxRow(stringResource(R.string.show_window), icon = Icons.Filled.Visibility, enabled = enabled) {
                        Link.act("ShowWindow", "id" to w.id); onDone()
                    }
                }
            }
        },
        bottom = {
            LxButton("?", enabled = false) {}
            LxHints(listOf(Hint("◀", stringResource(R.string.hint_back))))
            if (prefs.mirrorOn) LxButton(stringResource(R.string.pause), icon = Icons.Filled.Pause) { prefs.mirrorOn = false }
            else LxButton(stringResource(R.string.resume), icon = Icons.Filled.PlayArrow, pressed = true) { prefs.mirrorOn = true }
        },
    )
}
