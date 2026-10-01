package com.lattice.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lattice.app.lx.Alpha
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxChip
import com.lattice.app.lx.LxChips
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxKey
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxSectionGap
import com.lattice.app.lx.LxText
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.Type
import kotlin.math.abs

/**
 * The modifier latches. Latches rather than chords because holding two keys at
 * once is not something a touchscreen does well: tap `ctrl`, then tap the key.
 * One-shot: the next key takes the latch and clears it.
 */
class ModState {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    var sup by mutableStateOf(false)

    val armed: List<String>
        get() = buildList {
            if (ctrl) add("ctrl"); if (alt) add("alt"); if (shift) add("shift"); if (sup) add("super")
        }

    fun clear() { ctrl = false; alt = false; shift = false; sup = false }
}

/**
 * Where a keystroke goes, given what the canvas is showing.
 *
 * On a tmux pane the terminal wire is the direct one — it addresses the session
 * by name, so it cannot lose a race with desktop focus. But it only speaks what
 * `Vt` can express, so anything else (Home/End/PgUp/PgDn/Delete, F-keys, any
 * alt/super chord) falls through to the compositor, which delivers it to the
 * focused window exactly as a real keyboard would.
 */
class KeySink(val termMode: Boolean) {
    fun text(s: String) {
        if (s.isEmpty()) return
        if (termMode) Link.termInput(s) else Link.typeText(s)
    }

    fun named(key: String, mods: List<String>) {
        if (termMode) {
            if (mods.isEmpty()) vt(key)?.let { Link.termInput(it); return }
            if (mods == listOf("ctrl") && key.length == 1) { Link.termInput(Vt.ctrl(key[0])); return }
        }
        Link.key((mods + key).joinToString("+"))
    }

    /** A typed character, with whatever latches are armed. */
    fun typed(s: String, mods: ModState) {
        // `shift` never composes with a typed character: the soft keyboard has
        // ALREADY produced the shifted glyph. It composes with named keys only.
        val armed = mods.armed.filter { it != "shift" }
        when {
            armed.isEmpty() -> text(s)
            // Some IMEs batch a whole word into one change despite
            // KeyboardType.Password. An 8-letter chord is meaningless.
            s.length > 1 -> text(s)
            else -> {
                val sym = keysym(s[0].lowercaseChar())
                if (sym != null) named(sym, armed) else text(s)
            }
        }
        mods.clear()
    }

    private fun vt(key: String): ByteArray? = when (key) {
        "Escape" -> Vt.ESC
        "Tab" -> Vt.TAB
        "Return" -> Vt.ENTER
        "BackSpace" -> Vt.BACKSPACE
        "Up" -> Vt.UP
        "Down" -> Vt.DOWN
        "Left" -> Vt.LEFT
        "Right" -> Vt.RIGHT
        else -> null
    }
}

/** X keysym names for the punctuation a chord might want. */
private fun keysym(c: Char): String? = when {
    c.isLetterOrDigit() && c.code < 128 -> c.toString()
    else -> when (c) {
        ' ' -> "space"; '/' -> "slash"; '.' -> "period"; ',' -> "comma"
        '-' -> "minus"; '=' -> "equal"; ';' -> "semicolon"; '\'' -> "apostrophe"
        '[' -> "bracketleft"; ']' -> "bracketright"; '\\' -> "backslash"
        '`' -> "grave"; '\n' -> "Return"; '\t' -> "Tab"
        else -> null
    }
}

private val ROW_1 = listOf("Escape" to "esc", "Tab" to "⇥", "Return" to "⏎", "BackSpace" to "⌫", "Delete" to "del")
private val ROW_2 = listOf("Left" to "←", "Down" to "↓", "Up" to "↑", "Right" to "→")
private val MORE_1 = listOf("space" to "␣", "Home" to "home", "End" to "end", "Page_Up" to "pgup", "Page_Down" to "pgdn")
private val F_KEYS = (1..12).map { "F$it" to "F$it" }

/**
 * The Keys card: a trackpad (always open), the keys a phone keyboard has no
 * room for, the latches, and `type`, which is the ONLY thing that raises the
 * phone's keyboard.
 */
@Composable
fun KeysCard(ready: Boolean, termMode: Boolean, focused: Link.Win?) {
    val lx = LxTheme.current
    val mods = remember { ModState() }
    val sink = remember(termMode) { KeySink(termMode) }
    var more by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }

    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.keys_title))
                LxCaption(
                    if (termMode) stringResource(R.string.to_terminal)
                    else stringResource(R.string.to_window, focused?.let { Names.windowTitle(it) } ?: stringResource(R.string.no_window))
                )
            }
        },
        tile = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(lx.u(0.45f))) {
                LxSection(stringResource(R.string.trackpad), here = true)
                Trackpad(enabled = ready)
                LxChips {
                    LxChip(stringResource(R.string.left), enabled = ready) { Link.click("left") }
                    LxChip(stringResource(R.string.middle), enabled = ready) { Link.click("middle") }
                    LxChip(stringResource(R.string.right), enabled = ready) { Link.click("right") }
                }

                LxSectionGap()
                LxSection(stringResource(R.string.keys_title), badge = stringResource(R.string.next_key_latch))
                KeyRow(ROW_1, sink, mods, ready)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ROW_2.forEach { (k, label) ->
                        LxKey(label, k = 1.55f, enabled = ready, modifier = Modifier.weight(1f).padding(end = lx.u(0.3f))) {
                            Interaction.touch(); sink.named(k, mods.armed); mods.clear()
                        }
                    }
                    LxKey(if (more) stringResource(R.string.fewer_keys) else stringResource(R.string.more_keys), k = 1.55f, enabled = ready, modifier = Modifier.weight(1.2f)) { more = !more }
                }
                if (more) {
                    KeyRow(MORE_1, sink, mods, ready)
                    KeyRow(F_KEYS.subList(0, 6), sink, mods, ready)
                    KeyRow(F_KEYS.subList(6, 12), sink, mods, ready)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(lx.u(0.3f))) {
                    LxKey("ctrl", k = 1.55f, on = mods.ctrl, enabled = ready, modifier = Modifier.weight(1f)) { mods.ctrl = !mods.ctrl }
                    LxKey("alt", k = 1.55f, on = mods.alt, enabled = ready, modifier = Modifier.weight(1f)) { mods.alt = !mods.alt }
                    LxKey("shift", k = 1.55f, on = mods.shift, enabled = ready, modifier = Modifier.weight(1f)) { mods.shift = !mods.shift }
                    LxKey("super", k = 1.55f, on = mods.sup, enabled = ready, modifier = Modifier.weight(1f)) { mods.sup = !mods.sup }
                }

                if (typing) {
                    Spacer(Modifier.height(lx.u(0.2f)))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(lx.radiusRow)).background(lx.ink(Alpha.fieldBoxFocused))) {
                        ImeSink(
                            open = typing, enabled = ready,
                            placeholder = if (mods.armed.isEmpty()) stringResource(R.string.type_placeholder)
                            else mods.armed.joinToString("+") + " " + stringResource(R.string.latch_next),
                            onText = { sink.typed(it, mods) },
                            onBackspace = { sink.named("BackSpace", mods.armed); mods.clear() },
                            onEnter = { sink.named("Return", mods.armed); mods.clear() },
                        )
                    }
                }
            }
        },
        bottom = {
            val latch = mods.armed.joinToString("+")
            LxHints(
                if (latch.isNotEmpty()) listOf(Hint(latch, stringResource(R.string.latch_next)))
                else listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.hint_click)), Hint("◀", stringResource(R.string.hint_back_to_window)))
            )
            LxButton(stringResource(R.string.type_), ButtonKind.Primary, icon = Icons.Filled.Keyboard, enabled = ready, pressed = typing) { typing = !typing }
        },
    )
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, sink: KeySink, mods: ModState, enabled: Boolean) {
    val lx = LxTheme.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(lx.u(0.3f))) {
        keys.forEach { (k, label) ->
            LxKey(label, k = 1.55f, enabled = enabled, modifier = Modifier.weight(1f)) {
                Interaction.touch(); sink.named(k, mods.armed); mods.clear()
            }
        }
    }
}

/**
 * The trackpad: one finger moves, a tap clicks, two fingers scroll, a still
 * press past 350 ms holds the button for a drag. The words are on the pad
 * itself, so the gesture is never a secret.
 */
@Composable
private fun Trackpad(enabled: Boolean) {
    val lx = LxTheme.current
    var dragging by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(lx.radiusCard))
            .background(lx.ink(if (dragging) 0.08f else 0.04f))
            .border(lx.hairline, if (dragging) lx.roles.accent else lx.roles.line.copy(alpha = 0.4f), RoundedCornerShape(lx.radiusCard))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val gain = 1.8f
                awaitEachGesture {
                    val down = awaitPointerEvent()
                    val t0 = System.currentTimeMillis()
                    var moved = 0f
                    var twoFinger = false
                    var held = false
                    var last = down.changes.map { it.position }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val now = pressed.map { it.position }
                        if (pressed.size >= 2) {
                            twoFinger = true
                            if (last.size >= 2) {
                                val dy = ((now[0].y - last[0].y) + (now[1].y - last[1].y)) / 2f
                                val dx = ((now[0].x - last[0].x) + (now[1].x - last[1].x)) / 2f
                                if (abs(dx) + abs(dy) > 0.5f) Link.scroll(dx / 4f, dy / 4f)
                            }
                        } else if (!twoFinger && last.isNotEmpty()) {
                            val dx = (now[0].x - last[0].x) / density * gain
                            val dy = (now[0].y - last[0].y) / density * gain
                            moved += abs(dx) + abs(dy)
                            if (!held && moved < 6f && System.currentTimeMillis() - t0 > 350) {
                                held = true
                                dragging = true
                                Link.button("left", true)
                            }
                            if (abs(dx) + abs(dy) > 0f) Link.pointerRel(dx, dy)
                        }
                        ev.changes.forEach { it.consume() }
                        last = now
                    }
                    if (held) { Link.button("left", false); dragging = false }
                    else if (!twoFinger && moved < 6f && System.currentTimeMillis() - t0 < 300) Link.click("left")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        LxText(
            stringResource(R.string.trackpad_hint), Type.caption,
            lx.ink(if (enabled) 0.45f else 0.25f), align = TextAlign.Center,
        )
    }
}
