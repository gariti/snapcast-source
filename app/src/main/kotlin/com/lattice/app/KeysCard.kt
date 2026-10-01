package com.lattice.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import com.lattice.app.lx.Alpha
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
import com.lattice.app.lx.lxFocusRing
import kotlin.math.abs

/**
 * The modifier latches. Latches rather than chords because holding two keys at
 * once is not something a touchscreen does well: tap `Ctrl`, then tap the key.
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

/**
 * The Keys card: a trackpad (always open) and the keys a phone keyboard has
 * no room for, laid out as a real keyboard: the letters' own place (A–Z,
 * between Tab and Enter) is the ONE tap that raises the phone's keyboard; the
 * special keys sit where a desktop keyboard puts them, the arrows as an
 * inverted T under Ins/Home/PgUp · Del/End/PgDn.
 */
@Composable
fun KeysCard(ready: Boolean, termMode: Boolean, focused: Link.Win?) {
    val lx = LxTheme.current
    val mods = remember { ModState() }
    val sink = remember(termMode) { KeySink(termMode) }
    var typing by remember { mutableStateOf(false) }
    var fKeys by remember { mutableStateOf(false) }

    fun send(key: String) { Interaction.touch(); sink.named(key, mods.armed); mods.clear() }

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
                KeyboardGrid(
                    enabled = ready, mods = mods, typing = typing, fKeys = fKeys,
                    onKey = ::send, onLetters = { typing = !typing }, onFKeys = { fKeys = !fKeys },
                )
                if (fKeys) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(lx.u(0.25f))) {
                        (1..6).forEach { n -> LxKey("F$n", k = 1.3f, enabled = ready, modifier = Modifier.weight(1f)) { send("F$n") } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(lx.u(0.25f))) {
                        (7..12).forEach { n -> LxKey("F$n", k = 1.3f, enabled = ready, modifier = Modifier.weight(1f)) { send("F$n") } }
                    }
                }
                if (typing) {
                    Spacer(Modifier.height(lx.u(0.2f)))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(lx.radiusRow)).background(lx.ink(Alpha.fieldBoxFocused))) {
                        ImeSink(
                            open = typing, enabled = ready,
                            placeholder = if (mods.armed.isEmpty()) stringResource(R.string.type_placeholder)
                            else mods.armed.joinToString("+") + " " + stringResource(R.string.latch_next),
                            onText = { sink.typed(it, mods) },
                            onBackspace = { send("BackSpace") },
                            onEnter = { send("Return") },
                        )
                    }
                }
            }
        },
        bottom = {
            val latch = mods.armed.joinToString("+") { it.replaceFirstChar(Char::uppercase) }
            LxButton("?", enabled = false) {}
            LxHints(if (latch.isNotEmpty()) listOf(Hint(latch, stringResource(R.string.latch_next))) else emptyList())
        },
    )
}

/**
 * The keyboard grid, as a desktop keyboard lays it out. Left block: Esc ·
 * Tab · Shift down the edge, the letters (one tall button, the tap that raises
 * the phone's keyboard) in the middle, Backspace and a tall Enter on its
 * right; Ctrl · Super · Alt · Space along the bottom. Right block: Ins Home
 * PgUp / Del End PgDn / F1–12 ↑ / ← ↓ →. Every cap is one LxKey.
 */
@Composable
private fun KeyboardGrid(
    enabled: Boolean,
    mods: ModState,
    typing: Boolean,
    fKeys: Boolean,
    onKey: (String) -> Unit,
    onLetters: () -> Unit,
    onFKeys: () -> Unit,
) {
    val lx = LxTheme.current
    val gap = lx.u(0.25f)
    val k = 1.45f
    val rowH = lx.unit * k * 1.8f
    @Composable fun RowScope.Key(legend: String, key: String, w: Float = 1f, icon: ImageVector? = null, desc: String? = null, on: Boolean = false, onClick: (() -> Unit)? = null) {
        LxKey(legend, k = k, on = on, enabled = enabled, icon = icon, contentDescription = desc ?: legend,
            modifier = Modifier.weight(w).height(rowH)) { onClick?.invoke() ?: onKey(key) }
    }
    @Composable fun ColumnScope.Key(legend: String, key: String, icon: ImageVector? = null, desc: String? = null, on: Boolean = false, tall: Boolean = false, onClick: (() -> Unit)? = null) {
        LxKey(legend, k = k, on = on, enabled = enabled, icon = icon, contentDescription = desc ?: legend,
            modifier = Modifier.fillMaxWidth().height(if (tall) rowH * 2 + gap else rowH)) { onClick?.invoke() ?: onKey(key) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            // the left edge: Esc · Tab · Shift
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Key(stringResource(R.string.k_esc), "Escape")
                Key("", "Tab", icon = Icons.AutoMirrored.Filled.KeyboardTab, desc = stringResource(R.string.k_tab))
                Key(stringResource(R.string.k_shift), "", on = mods.shift) { mods.shift = !mods.shift }
            }
            // the letters: one tall button, the tap that types
            LettersKey(Modifier.weight(4f).height(rowH * 3 + gap * 2), enabled = enabled, on = typing, onClick = onLetters)
            // Backspace · Enter (tall)
            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Key("", "BackSpace", icon = Icons.AutoMirrored.Filled.Backspace, desc = stringResource(R.string.k_backspace))
                Key("", "Return", icon = Icons.AutoMirrored.Filled.KeyboardReturn, desc = stringResource(R.string.k_enter), tall = true)
            }
            Spacer(Modifier.weight(0.5f))
            // the right block
            Column(Modifier.weight(6f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Key(stringResource(R.string.k_ins), "Insert"); Key(stringResource(R.string.k_home), "Home"); Key(stringResource(R.string.k_pgup), "Page_Up")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Key(stringResource(R.string.k_del), "Delete"); Key(stringResource(R.string.k_end), "End"); Key(stringResource(R.string.k_pgdn), "Page_Down")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    LxKey(stringResource(R.string.f_keys), k = k, quiet = true, on = fKeys, enabled = enabled, modifier = Modifier.weight(1f).height(rowH), onClick = onFKeys)
                    Key("", "Up", icon = Icons.Filled.ArrowUpward, desc = stringResource(R.string.k_up))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Key(stringResource(R.string.k_ctrl), "", 2f, on = mods.ctrl) { mods.ctrl = !mods.ctrl }
            Key(stringResource(R.string.k_super), "", 2f, on = mods.sup) { mods.sup = !mods.sup }
            Key(stringResource(R.string.k_alt), "", 2f, on = mods.alt) { mods.alt = !mods.alt }
            Key("", "space", 2f, icon = Icons.Filled.SpaceBar, desc = stringResource(R.string.k_space))
            Spacer(Modifier.weight(0.5f))
            Key("", "Left", 2f, icon = Icons.Filled.ArrowBack, desc = stringResource(R.string.k_left))
            Key("", "Down", 2f, icon = Icons.Filled.ArrowDownward, desc = stringResource(R.string.k_down))
            Key("", "Right", 2f, icon = Icons.Filled.ArrowForward, desc = stringResource(R.string.k_right))
        }
    }
}

/** The A–Z button: the letters' own place, and the one tap that raises the phone's keyboard. */
@Composable
private fun LettersKey(modifier: Modifier, enabled: Boolean, on: Boolean, onClick: () -> Unit) {
    val lx = LxTheme.current
    val desc = stringResource(R.string.type_with_keyboard)
    Box(
        modifier
            .clip(RoundedCornerShape(lx.radiusCard))
            .background(if (on) lx.accent(Alpha.chipCurrent) else lx.ink(Alpha.keycapFill))
            .border(lx.hairline, if (on) lx.accent(0.8f) else lx.ink(Alpha.keycapBorder), RoundedCornerShape(lx.radiusCard))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .lxFocusRing(true)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(lx.u(0.2f))) {
            Icon(Icons.Filled.Keyboard, null, Modifier.width(lx.u(1.5f)), tint = lx.roles.ink)
            LxText(stringResource(R.string.letters), Type.label.copy(tracking = 0.sp), lx.roles.ink)
            LxText(stringResource(R.string.tap_to_type), Type.caption, lx.ink(Alpha.keycapCaption))
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
            .aspectRatio(16f / 7f)
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
            lx.ink(if (enabled) Alpha.caption else 0.25f), align = TextAlign.Center,
        )
    }
}
