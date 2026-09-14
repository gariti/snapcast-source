package com.lattice.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import kotlin.math.hypot

/**
 * The two-finger half of a gesture vocabulary whose one-finger half is already
 * spoken for.
 *
 * Both surfaces the app draws — the mirror's picture and a tmux pane's text —
 * resolve tap, long press, drag and swipe from a single pointer, so pinch
 * cannot come from `detectTransformGestures`: that claims one-finger drags too
 * and would eat the swipe that changes window. Each surface's own
 * `awaitEachGesture` loop watches for a second finger instead and feeds the
 * events here.
 *
 * Two rules, both of them things a touchscreen punishes you for getting wrong:
 *
 *  * **Once two fingers are down the gesture stays a transform until the last
 *    one lifts.** Falling back to the one-finger vocabulary mid-pinch fires a
 *    click wherever the remaining finger happens to be — into the window you
 *    were only trying to look at.
 *  * **The baseline re-seeds whenever the set of pressed pointers changes.**
 *    Putting a finger down or lifting one moves the centroid by a long way
 *    without anybody having dragged anything.
 */
class Pinch {
    private var ids: List<Long> = emptyList()
    private var centroid = Offset.Zero
    private var span = 0f

    /** One event's worth of two-finger motion. */
    data class Step(val zoom: Float, val pan: Offset, val centroid: Offset)

    /** Forget the baseline; the next [update] only re-seeds it. */
    fun reset() {
        ids = emptyList()
    }

    /**
     * Feed an event whose pressed pointers are the pinch. Returns null on the
     * event that seeds (or re-seeds) the baseline, a [Step] on every one after.
     */
    fun update(ev: PointerEvent): Step? {
        val pressed = ev.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
            reset()
            return null
        }
        val now = pressed.map { it.id.value }
        val c = pressed.fold(Offset.Zero) { a, p -> a + p.position } / pressed.size.toFloat()
        val s = pressed.fold(0f) { a, p -> a + hypot(p.position.x - c.x, p.position.y - c.y) } / pressed.size
        if (now != ids) {
            ids = now
            centroid = c
            span = s
            return null
        }
        // One finger left, or two fingers exactly on top of each other: pan
        // only. A ratio off a span of nothing is a division by nothing.
        val zoom = if (span > 1f && s > 1f) s / span else 1f
        val step = Step(zoom, c - centroid, c)
        centroid = c
        span = s
        return step
    }
}
