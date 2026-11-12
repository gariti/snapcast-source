package com.lattice.app

import kotlin.math.abs

/**
 * Where a track is, and whether that is news. Pure (no Android types), so the
 * JVM unit tests drive it directly.
 *
 * The desktop is told the position only on an EVENT (metadata, playback state,
 * reconnect) and extrapolates it itself between frames. Some players call
 * onPlaybackStateChanged every second with an advancing position; those must
 * cost nothing on the wire. So "has it changed?" is answered against what the
 * LAST frame predicts for now, not against the last raw number.
 */
object PlaybackClock {
    /** Drift past this many ms is a jump (a seek, a stall, a skip). */
    const val JUMP_TOLERANCE_MS = 1_500L

    /**
     * One timing snapshot: [posMs] was true at [atMs] (SystemClock.elapsedRealtime
     * on the phone). [speed] only moves the clock while [playing].
     */
    data class Timing(
        val playing: Boolean,
        val durMs: Long,
        val posMs: Long,
        val atMs: Long,
        val speed: Float,
        val canSeek: Boolean,
    )

    /**
     * Position at [nowMs] from a PlaybackState-style anchor. Never negative,
     * never past a known duration.
     */
    fun extrapolate(
        posMs: Long,
        atMs: Long,
        speed: Float,
        playing: Boolean,
        nowMs: Long,
        durMs: Long,
    ): Long {
        var p = posMs.coerceAtLeast(0L)
        if (playing && atMs > 0 && nowMs > atMs && speed > 0f) {
            p += ((nowMs - atMs).toDouble() * speed).toLong()
        }
        if (durMs > 0) p = p.coerceAtMost(durMs)
        return p
    }

    /** [t]'s position moved forward to [nowMs]. */
    fun at(t: Timing, nowMs: Long): Timing =
        t.copy(posMs = extrapolate(t.posMs, t.atMs, t.speed, t.playing, nowMs, t.durMs), atMs = nowMs)

    /**
     * True when [next] tells the desktop something [prev] did not already
     * imply: a different state / speed / length / seekability, or a position
     * more than [JUMP_TOLERANCE_MS] away from where [prev] says it should be by
     * [next]'s time. No previous frame is always news.
     */
    fun changed(prev: Timing?, next: Timing): Boolean {
        if (prev == null) return true
        if (prev.playing != next.playing || prev.durMs != next.durMs ||
            prev.canSeek != next.canSeek || prev.speed != next.speed
        ) return true
        val predicted = extrapolate(prev.posMs, prev.atMs, prev.speed, prev.playing, next.atMs, prev.durMs)
        return abs(next.posMs - predicted) > JUMP_TOLERANCE_MS
    }
}
