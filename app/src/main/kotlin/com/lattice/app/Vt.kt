package com.lattice.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * The little bit of terminal the phone needs.
 *
 * The desktop bridge does not hand us a byte stream from a program — it hands
 * us `tmux capture-pane -p -e`, which is the grid tmux has already rendered,
 * annotated with SGR colour runs and nothing else. No cursor motion, no scroll
 * regions, no alternate screen: tmux resolved all of that away before we saw
 * it. So this is an SGR parser, not a VT emulator, and one screen is one
 * self-contained thing to draw.
 *
 * Anything that is not an SGR sequence (`ESC [ … m`) is skipped rather than
 * interpreted — a stray OSC title or a lone ESC must not leak into the text.
 */
object Vt {

    /** One rendered screen: rows of styled text, plus where the cursor is. */
    class Screen(
        val session: String,
        val cols: Int,
        val rows: Int,
        /** Cursor cell, or -1 when scrolled back into history (it is off-screen). */
        val cx: Int,
        val cy: Int,
        /** Lines above the live tail this screen was taken from; 0 = following. */
        val off: Int,
        /** Lines tmux is holding, the ceiling on [off]. */
        val hist: Int,
        val title: String,
        val lines: List<AnnotatedString>,
    )

    // The 16 ANSI slots, tuned to the app's wallust palette so a terminal on
    // the phone and a terminal on the desktop read as the same machine.
    private val ANSI = intArrayOf(
        0x101314, 0xCF8070, 0x8FC9A2, 0xDCC38A, 0x7FA8CF, 0xB99BC9, 0x7DD7DB, 0xB6E3E5,
        0x415051, 0xF2A08F, 0xAEE3BE, 0xF0DBA6, 0x9EC4E8, 0xD2B8E0, 0xA6ECEF, 0xEDF4F4,
    )

    val DEFAULT_FG = Color(0xFFD2F1F2)
    val DEFAULT_BG = Color(0xFF0F1314)

    /**
     * xterm-256: 0–15 the ANSI slots above, 16–231 the 6×6×6 cube, 232–255 the
     * 24-step grey ramp. Claude Code's output is almost entirely `38;5;n`, so
     * this table is the difference between a colourful screen and a grey one.
     */
    fun xterm256(n: Int): Color = when {
        n < 0 -> DEFAULT_FG
        n < 16 -> Color(0xFF000000.toInt() or ANSI[n])
        n < 232 -> {
            val i = n - 16
            Color(CUBE[i / 36 % 6], CUBE[i / 6 % 6], CUBE[i % 6])
        }
        n < 256 -> { val v = 8 + (n - 232) * 10; Color(v, v, v) }
        else -> DEFAULT_FG
    }

    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    private class State {
        var fg: Color? = null
        var bg: Color? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        var reverse = false

        fun reset() {
            fg = null; bg = null
            bold = false; dim = false; italic = false; underline = false; reverse = false
        }

        fun style(): SpanStyle {
            var f = fg ?: DEFAULT_FG
            var b = bg
            if (reverse) {
                val nb = f
                f = b ?: DEFAULT_BG
                b = nb
            }
            // tmux renders faint text with SGR 2 and Compose has no faint
            // weight, so it becomes alpha — which is what faint looks like.
            if (dim) f = f.copy(alpha = 0.55f)
            return SpanStyle(
                color = f,
                background = b ?: Color.Unspecified,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (underline) TextDecoration.Underline else null,
            )
        }
    }

    /**
     * `capture-pane -e` bytes → one styled line per row, padded out to [rows]
     * so the grid keeps its shape even when tmux trims trailing blank lines.
     */
    fun parse(data: ByteArray, rows: Int): List<AnnotatedString> {
        val text = String(data, Charsets.UTF_8)
        val st = State()
        val out = ArrayList<AnnotatedString>(rows.coerceAtLeast(1))
        // A Builder cannot be rewound, so each line gets its own; the SGR state
        // carries across them, which is how a terminal replays this stream.
        var b = AnnotatedString.Builder()
        var span = b.pushStyle(st.style())
        var run = StringBuilder()

        fun flush() {
            if (run.isNotEmpty()) { b.append(run.toString()); run = StringBuilder() }
        }
        fun newLine() {
            flush()
            b.pop(span)
            out.add(b.toAnnotatedString())
            b = AnnotatedString.Builder()
            span = b.pushStyle(st.style())
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '' && i + 1 < text.length && text[i + 1] == '[' -> {
                    var j = i + 2
                    while (j < text.length && text[j] !in '@'..'~') j++
                    if (j >= text.length) { i = text.length; break }
                    if (text[j] == 'm') {
                        flush()
                        b.pop(span)
                        applySgr(st, text.substring(i + 2, j))
                        span = b.pushStyle(st.style())
                    }
                    i = j + 1
                }
                // Any other escape: drop the introducer, and for OSC everything
                // up to its BEL or ST terminator.
                c == '' -> {
                    if (i + 1 < text.length && text[i + 1] == ']') {
                        var j = i + 2
                        while (j < text.length && text[j] != '' &&
                            !(text[j] == '' && j + 1 < text.length && text[j + 1] == '\\')) j++
                        i = if (j < text.length && text[j] == '') j + 1 else j + 2
                    } else {
                        i += 2
                    }
                }
                c == '\n' -> { newLine(); i++ }
                c == '\r' -> i++
                c == '\t' -> { run.append("        "); i++ }
                c.code < 0x20 -> i++
                else -> { run.append(c); i++ }
            }
        }
        flush()
        b.pop(span)
        out.add(b.toAnnotatedString())

        while (out.size < rows) out.add(AnnotatedString(""))
        return if (out.size > rows) out.subList(0, rows) else out
    }

    private fun applySgr(st: State, body: String) {
        if (body.isEmpty()) { st.reset(); return }
        val p = body.split(';').map { it.toIntOrNull() ?: 0 }
        var k = 0
        while (k < p.size) {
            when (val n = p[k]) {
                0 -> st.reset()
                1 -> st.bold = true
                2 -> st.dim = true
                3 -> st.italic = true
                4 -> st.underline = true
                7 -> st.reverse = true
                22 -> { st.bold = false; st.dim = false }
                23 -> st.italic = false
                24 -> st.underline = false
                27 -> st.reverse = false
                39 -> st.fg = null
                49 -> st.bg = null
                in 30..37 -> st.fg = xterm256(n - 30)
                in 90..97 -> st.fg = xterm256(n - 90 + 8)
                in 40..47 -> st.bg = xterm256(n - 40)
                in 100..107 -> st.bg = xterm256(n - 100 + 8)
                38, 48 -> {
                    // 38;5;n (indexed) or 38;2;r;g;b (truecolor).
                    val isFg = n == 38
                    when (p.getOrNull(k + 1)) {
                        5 -> {
                            val c = xterm256(p.getOrNull(k + 2) ?: 7)
                            if (isFg) st.fg = c else st.bg = c
                            k += 2
                        }
                        2 -> {
                            val c = Color(
                                (p.getOrNull(k + 2) ?: 0).coerceIn(0, 255),
                                (p.getOrNull(k + 3) ?: 0).coerceIn(0, 255),
                                (p.getOrNull(k + 4) ?: 0).coerceIn(0, 255),
                            )
                            if (isFg) st.fg = c else st.bg = c
                            k += 4
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
            k++
        }
    }

    // ---- keys -------------------------------------------------------------

    /**
     * What the key bar sends. tmux hands these to the pane verbatim through
     * `send-keys -H`, so they are the bytes a real terminal emulator would
     * write; application-cursor mode does not come into it because tmux
     * normalises that away on the pane's behalf.
     */
    val ESC = byteArrayOf(0x1b)
    val TAB = byteArrayOf(0x09)
    val ENTER = byteArrayOf(0x0d)
    val BACKSPACE = byteArrayOf(0x7f)
    val UP = "[A".toByteArray()
    val DOWN = "[B".toByteArray()
    val RIGHT = "[C".toByteArray()
    val LEFT = "[D".toByteArray()
    val SHIFT_TAB = "[Z".toByteArray()

    /** Ctrl-<letter>: 'c' → 0x03. */
    fun ctrl(ch: Char): ByteArray = byteArrayOf((ch.uppercaseChar().code and 0x1f).toByte())
}
