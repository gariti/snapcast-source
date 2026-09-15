package com.lattice.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A dispatched agent's tmux pane, drawn as text.
 *
 * Everything here treats one [Vt.Screen] as a complete picture: the bridge
 * sends whole screens, never deltas, so there is no local scrollback buffer and
 * no cursor state to keep. History is `off` lines up, which the bridge captures
 * on request.
 *
 * The pane is 70 columns on this desktop (one tiled foot column), so fitting it
 * to a phone's width lands around 9–10 sp with nothing cut off. That fit is the
 * starting point; pinching scales it from there, and anything that then falls
 * outside the frame is reachable with a two-finger drag.
 */

private const val MIN_FONT = 6.5f
private const val MAX_FONT = 16f
/** What a pinch can reach, once the fit has been overruled on purpose. */
private const val ZOOM_MAX_FONT = 40f
private const val BASE_FONT = 12f

/** The pinch range for the terminal's text, as a multiple of the width fit. */
const val TERM_ZOOM_MIN = 0.5f
const val TERM_ZOOM_MAX = 4f

@Composable
fun TerminalView(
    screen: Vt.Screen?,
    enabled: Boolean,
    waiting: String?,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onScroll: (Int) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var widthPx by remember { mutableStateOf(0) }
    var heightPx by remember { mutableStateOf(0) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val cursorColor = MaterialTheme.colorScheme.primary

    // Where the text sits inside the frame once it is bigger than the frame.
    // Clamped against the measured text, so it can never be dragged off into
    // the void — and re-clamped when the zoom changes under it.
    var pan by remember { mutableStateOf(Offset.Zero) }
    var contentW by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    val slack = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
    // Read through State so the gesture's long-lived closures see the current
    // zoom, not whatever it was the first time this composed.
    val zoomNow by androidx.compose.runtime.rememberUpdatedState(zoom)
    val setZoom by androidx.compose.runtime.rememberUpdatedState(onZoom)

    fun clampPan(p: Offset): Offset = Offset(
        if (contentW <= widthPx) 0f else p.x.coerceIn((widthPx - contentW).toFloat(), 0f),
        if (contentH <= heightPx) 0f else p.y.coerceIn((heightPx - contentH).toFloat(), 0f),
    )

    LaunchedEffect(contentW, contentH, widthPx, heightPx) { pan = clampPan(pan) }

    Box(
        modifier
            // The surface's own handle: TalkBack announces what this is, and
            // it is what the on-device gesture test aims a pinch at (the
            // mirror has one already, as the Image's contentDescription).
            .semantics { contentDescription = "agent terminal" }
            .clip(RoundedCornerShape(6.dp))
            .background(Vt.DEFAULT_BG)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .onSizeChanged { widthPx = it.width; heightPx = it.height },
    ) {
        if (screen == null) {
            Text(
                waiting ?: if (enabled) "opening the session…" else "terminal off",
                Modifier.align(Alignment.Center).padding(16.dp),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            return@Box
        }

        // Fit the pane's columns to the phone's width. Measured once per
        // (cols, width): the pane's geometry changes when the desktop window
        // does, not six times a second.
        val fitFont = remember(screen.cols, widthPx) {
            if (widthPx <= 0) BASE_FONT else {
                val probe = measurer.measure(
                    AnnotatedString("M".repeat(screen.cols)),
                    TextStyle(fontFamily = FontFamily.Monospace, fontSize = BASE_FONT.sp),
                )
                val w = probe.size.width.toFloat().coerceAtLeast(1f)
                (BASE_FONT * (widthPx - 12) / w).coerceIn(MIN_FONT, MAX_FONT)
            }
        }
        // The fit is the default, not the law: pinch takes it up to something
        // you can read across the room, or down to the legibility floor.
        val fontSize = (fitFont * zoom).coerceIn(MIN_FONT, ZOOM_MAX_FONT)

        // One Text for the whole screen: uniform line height for free, and the
        // layout result gives an exact cursor box with no manual metrics. Lines
        // are padded out to `cols` so background runs fill their row and the
        // cursor always has a cell to sit in — which also makes the cursor's
        // string offset plain arithmetic.
        val body = remember(screen) {
            val b = AnnotatedString.Builder()
            screen.lines.forEachIndexed { i, line ->
                b.append(line)
                val pad = screen.cols - line.length
                if (pad > 0) b.append(" ".repeat(pad))
                if (i < screen.lines.size - 1) b.append("\n")
            }
            b.toAnnotatedString()
        }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .terminalGestures(
                    enabled = enabled,
                    lineHeightPx = { layout?.let { it.getLineBottom(0) - it.getLineTop(0) } ?: 20f },
                    off = screen.off,
                    hist = screen.hist,
                    onScroll = onScroll,
                    onSwipe = onSwipe,
                    onZoomBy = { f -> setZoom((zoomNow * f).coerceIn(TERM_ZOOM_MIN, TERM_ZOOM_MAX)) },
                    onPanBy = { d -> pan = clampPan(pan + d) },
                ),
        ) {
            // Measured unbounded so a zoomed-in screen lays out at its true
            // size and is moved into view, rather than being squeezed back
            // into the frame's width and losing its right-hand columns.
            Box(
                Modifier
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .graphicsLayer { translationX = pan.x; translationY = pan.y }
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        if (screen.cx < 0 || screen.cy < 0) return@drawBehind
                        // Every line is exactly `cols` long plus its newline, so
                        // the cursor cell's offset into the joined string is direct.
                        val len = l.layoutInput.text.length
                        if (len == 0) return@drawBehind
                        val idx = (screen.cy * (screen.cols + 1) + screen.cx).coerceIn(0, len - 1)
                        val r = runCatching { l.getBoundingBox(idx) }.getOrNull() ?: return@drawBehind
                        drawRect(
                            color = cursorColor.copy(alpha = 0.5f),
                            topLeft = Offset(r.left + 4.dp.toPx(), r.top + 3.dp.toPx()),
                            size = Size(r.width.coerceAtLeast(2f), r.height),
                        )
                    },
            ) {
                Text(
                    body,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
                    color = Vt.DEFAULT_FG,
                    softWrap = false,
                    onTextLayout = {
                        layout = it
                        contentW = it.size.width + slack
                        contentH = it.size.height + slack
                    },
                )
            }
        }

        if (kotlin.math.abs(zoom - 1f) > 0.01f) {
            Text(
                "%.0f%%".format(zoom * 100),
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (screen.off > 0) {
            Text(
                "↑ ${screen.off} back · ${screen.hist} held · tap to follow",
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One touch vocabulary for the terminal:
 *   vertical drag   scroll through tmux's history (finger down = older)
 *   tap             back to the live tail when scrolled back
 *   swipe ← / →     focus the next / previous desktop window, exactly as on the
 *                   mirror, so the gesture means the same thing in both modes
 *   pinch           text size, from half the width fit to four times it
 *   two-finger drag move the text about once it is too big for the frame
 *
 * The two-finger half is deliberately where zoom and pan live: one finger is
 * fully spoken for by scrollback and the window swipe, and a terminal you can
 * scroll by accident while trying to magnify it is worse than one you cannot
 * magnify at all.
 *
 * A tap deliberately does NOT move the desktop pointer. On the mirror a tap is
 * a click because you are pointing at a picture of a window; here you are
 * reading text, and a stray click into an agent's terminal is not something you
 * can take back.
 */
private fun Modifier.terminalGestures(
    enabled: Boolean,
    lineHeightPx: () -> Float,
    off: Int,
    hist: Int,
    onScroll: (Int) -> Unit,
    onSwipe: (Int) -> Unit,
    onZoomBy: (Float) -> Unit,
    onPanBy: (Offset) -> Unit,
): Modifier = this.pointerInput(enabled, off, hist) {
    if (!enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val swipeMin = 72.dp.toPx()
    val pinch = Pinch()
    awaitEachGesture {
        val down = awaitFirstDown()
        val start = down.position
        val t0 = System.currentTimeMillis()
        var moved = false
        var mode = 0 // 0 undecided · 1 vertical scroll · 2 horizontal swipe
        var swiped = false
        var pinching = false
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            if (pinching || pressed >= 2) {
                // A second finger takes the gesture over for good: releasing
                // back into the one-finger vocabulary would snap the view to
                // the live tail the moment you let go of a pinch.
                if (!pinching) { pinching = true; pinch.reset() }
                ev.changes.forEach { it.consume() }
                if (pressed == 0) break
                pinch.update(ev)?.let { onZoomBy(it.zoom); onPanBy(it.pan) }
                continue
            }
            val ch = ev.changes.firstOrNull() ?: continue
            val dx = ch.position.x - start.x
            val dy = ch.position.y - start.y
            if (!ch.pressed) {
                if (!moved && off > 0) onScroll(0)
                break
            }
            if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                moved = true
                mode = if (abs(dy) > abs(dx)) 1 else 2
            }
            when (mode) {
                1 -> {
                    ch.consume()
                    // Finger down reveals older lines, like sliding paper up.
                    val lines = (dy / lineHeightPx().coerceAtLeast(1f)).roundToInt()
                    onScroll((off + lines).coerceIn(0, hist))
                }
                2 -> if (!swiped && System.currentTimeMillis() - t0 < 600 &&
                    abs(dx) > swipeMin && abs(dx) > 2 * abs(dy)
                ) {
                    ch.consume()
                    swiped = true
                    onSwipe(if (dx < 0) -1 else 1)
                }
            }
        }
    }
}

/**
 * The keys a phone keyboard cannot reach, plus the one that raises it.
 *
 * `ctrl` is a latch rather than a chord: tap it, then tap a letter. Holding two
 * keys at once is not something a touchscreen does well, and every control byte
 * a TUI wants is ctrl-plus-one-letter anyway.
 */
@Composable
fun TerminalKeys(
    enabled: Boolean,
    onInput: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrl by remember { mutableStateOf(false) }
    var keyboardUp by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TermKey("esc", enabled) { onInput(Vt.ESC) }
            TermKey("⇥", enabled) { onInput(Vt.TAB) }
            TermKey("⇧⇥", enabled) { onInput(Vt.SHIFT_TAB) }
            TermKey("↑", enabled) { onInput(Vt.UP) }
            TermKey("↓", enabled) { onInput(Vt.DOWN) }
            TermKey("←", enabled) { onInput(Vt.LEFT) }
            TermKey("→", enabled) { onInput(Vt.RIGHT) }
            TermKey("⌫", enabled) { onInput(Vt.BACKSPACE) }
            TermKey("⏎", enabled) { onInput(Vt.ENTER) }
            TermKey("^C", enabled) { onInput(Vt.ctrl('c')) }
            FilterChip(
                selected = ctrl,
                onClick = { ctrl = !ctrl },
                enabled = enabled,
                label = { Text("ctrl", fontFamily = FontFamily.Monospace) },
            )
            FilterChip(
                selected = keyboardUp,
                onClick = { keyboardUp = !keyboardUp },
                enabled = enabled,
                label = { Text("abc", fontFamily = FontFamily.Monospace) },
            )
        }

        // The IME sink. It holds no text of its own: each keystroke goes down
        // the wire the moment it arrives and the session's own echo is the
        // feedback. That is what makes an arrow-key menu or a y/n prompt work
        // from a phone at all.
        if (keyboardUp) {
            var pending by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            BasicTextField(
                value = pending,
                onValueChange = { typed ->
                    if (typed.isEmpty()) return@BasicTextField
                    if (ctrl && typed.length == 1) {
                        onInput(Vt.ctrl(typed[0]))
                        ctrl = false
                    } else {
                        onInput(typed.toByteArray())
                    }
                    // Held at empty so the next keystroke is a fresh change.
                    pending = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        // A field held empty never reports backspace as a text
                        // change, so take it as a key instead.
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace) {
                            onInput(Vt.BACKSPACE); true
                        } else false
                    },
                singleLine = true,
                // Password stops Gboard composing whole words, which would
                // otherwise batch a word instead of delivering each key.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (pending.isEmpty()) {
                            Text(
                                if (ctrl) "ctrl + the next key" else "type — every key goes straight through",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun TermKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}
