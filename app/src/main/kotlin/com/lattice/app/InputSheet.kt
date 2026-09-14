package com.lattice.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * The modifier latches. Latches rather than chords because holding two keys at
 * once is not something a touchscreen does well: tap `ctrl`, then tap the key.
 * The order is fixed so the desktop's chord parser always sees one spelling.
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
 * by name, so it cannot lose a race with desktop focus, and it is the same wire
 * the canvas's own key bar uses. But it only speaks what `Vt` can express, so
 * anything else (Home/End/PgUp/PgDn/Delete, F-keys, any alt/super chord) falls
 * through to the compositor, which delivers it to the focused window exactly as
 * a real keyboard would — through foot, into tmux, same as a human typing.
 */
private class Sink(val termMode: Boolean) {
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
        // ALREADY produced the shifted glyph, so shift+A on the desktop would
        // apply it twice and land wrong. It composes with named keys only.
        val armed = mods.armed.filter { it != "shift" }
        when {
            armed.isEmpty() -> text(s)
            // Some IMEs (swipe input, Samsung's keyboard) batch a whole word
            // into one change despite KeyboardType.Password. An 8-letter chord
            // is meaningless, so a batch is always text.
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
 * Everything you send to the desktop by hand: a pointer, the keys a phone
 * keyboard has no room for, and the phone keyboard itself wired straight
 * through.
 */
@Composable
fun InputSheetContent(ready: Boolean, termMode: Boolean, scroll: androidx.compose.foundation.ScrollState) {
    val mods = remember { ModState() }
    val sink = remember(termMode) { Sink(termMode) }
    var padOpen by rememberSaveable { mutableStateOf(false) }
    var keysOpen by rememberSaveable { mutableStateOf(false) }

    // With the keyboard up the sheet has ~284dp left, and the F1–F12 block
    // alone eats most of it — measured on a Pixel 7, where it pushed the
    // modifier latches clean off the top of the scroller. So the bulky rows
    // give way when the IME rises, and the sheet scrolls to its own bottom,
    // where the latches and the sink live.
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeUp) {
        if (imeUp) {
            padOpen = false
            keysOpen = false
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (termMode) "Send input · to the tmux pane" else "Send input · to the focused window",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (!ready) NoticeCard("Desktop not reachable", "Pointer and keyboard need the desktop's bridge.")

        Disclosure("Trackpad", padOpen, { padOpen = !padOpen }) { TrackpadBody(ready) }
        Disclosure("More keys", keysOpen, { keysOpen = !keysOpen }) { KeyBar(EXTRA_KEYS, sink, mods, ready) }

        // These three are last, so they are the rows nearest the keyboard and
        // the ones the auto-scroll lands on, and they are never collapsed.
        //
        // esc, ⏎, ⌫ and the arrows are what you reach for WHILE typing — a
        // y/n prompt, an arrow-key menu — so hiding them behind a disclosure
        // when the IME rises would hide them exactly when they are wanted.
        // The bulky remainder (home/end/page, F1–F12) is what gives way.
        KeyBar(CORE_KEYS, sink, mods, ready)
        ModLatches(mods = mods, enabled = ready)
        DesktopImeSink(mods = mods, sink = sink, enabled = ready)
    }
}

@Composable
private fun Disclosure(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(200), label = "chevron")
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onToggle() }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "hide $title" else "show $title",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
        ) { body() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModLatches(mods: ModState, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(selected = mods.ctrl, onClick = { mods.ctrl = !mods.ctrl }, label = { Text("ctrl") }, enabled = enabled)
        FilterChip(selected = mods.alt, onClick = { mods.alt = !mods.alt }, label = { Text("alt") }, enabled = enabled)
        FilterChip(selected = mods.shift, onClick = { mods.shift = !mods.shift }, label = { Text("shift") }, enabled = enabled)
        FilterChip(selected = mods.sup, onClick = { mods.sup = !mods.sup }, label = { Text("super") }, enabled = enabled)
    }
}

@Composable
private fun TrackpadBody(enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "one finger moves · tap clicks · two fingers scroll · hold then drag to drag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        TrackpadSurface(enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Link.click("left") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Left") }
            OutlinedButton(onClick = { Link.click("middle") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Middle") }
            OutlinedButton(onClick = { Link.click("right") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Right") }
        }
    }
}

@Composable
private fun TrackpadSurface(enabled: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val gain = 1.8f
                awaitEachGesture {
                    val down = awaitPointerEvent()
                    val t0 = System.currentTimeMillis()
                    var moved = 0f
                    var twoFinger = false
                    var dragging = false
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
                            // A still press past 350 ms becomes a drag (button held).
                            if (!dragging && moved < 6f && System.currentTimeMillis() - t0 > 350) {
                                dragging = true
                                Link.button("left", true)
                            }
                            if (abs(dx) + abs(dy) > 0f) Link.pointerRel(dx, dy)
                        }
                        ev.changes.forEach { it.consume() }
                        last = now
                    }
                    if (dragging) Link.button("left", false)
                    else if (!twoFinger && moved < 6f && System.currentTimeMillis() - t0 < 300) Link.click("left")
                }
            },
    ) {
        Text("·", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
    }
}

/** Reachable while you type: the keys a soft keyboard has no room for. */
private val CORE_KEYS = listOf(
    "Escape" to "esc", "Tab" to "⇥", "Return" to "⏎", "BackSpace" to "⌫",
    "Left" to "←", "Down" to "↓", "Up" to "↑", "Right" to "→",
)

/** The rest, behind a disclosure — bulky and rarely wanted mid-sentence. */
private val EXTRA_KEYS = listOf(
    "Delete" to "del", "space" to "␣",
    "Home" to "home", "End" to "end", "Page_Up" to "pgup", "Page_Down" to "pgdn",
) + (1..12).map { "F$it" to "F$it" }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyBar(keys: List<Pair<String, String>>, sink: Sink, mods: ModState, enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        keys.forEach { (k, label) ->
            AssistChip(
                onClick = { Interaction.touch(); sink.named(k, mods.armed); mods.clear() },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

/**
 * The phone's own keyboard, wired straight through.
 *
 * The field holds no text: every keystroke goes down the wire the moment it
 * arrives and the desktop's own echo is the feedback. That is the difference
 * between this and the compose-then-send box it replaces — you can answer a
 * y/n prompt or drive an arrow-key menu with it.
 */
@Composable
private fun DesktopImeSink(mods: ModState, sink: Sink, enabled: Boolean) {
    var pending by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Raise the IME only AFTER the sheet's 240 ms enter tween: asking during it
    // makes the insets animation and the slide fight, and the keyboard
    // intermittently fails to appear at all.
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        kotlinx.coroutines.delay(280)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BasicTextField(
        value = pending,
        onValueChange = { typed ->
            if (typed.isEmpty()) return@BasicTextField
            // The soft keyboard is another window, so the root touch probe
            // never sees typing. Without this the mirror would pause behind
            // the sheet while you are actively using it.
            Interaction.touch()
            sink.typed(typed, mods)
            pending = ""
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                // A field held empty never reports these as a text change.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Backspace -> { Interaction.touch(); sink.named("BackSpace", mods.armed); mods.clear(); true }
                    Key.Enter -> { Interaction.touch(); sink.named("Return", mods.armed); mods.clear(); true }
                    else -> false
                }
            },
        singleLine = true,
        enabled = enabled,
        // Password stops Gboard composing whole words, which would otherwise
        // batch a word instead of delivering each key as you press it.
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (pending.isEmpty()) {
                    Text(
                        if (mods.armed.isEmpty()) "type — every key goes straight through"
                        else mods.armed.joinToString("+") + " + the next key",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                inner()
            }
        },
    )
}
