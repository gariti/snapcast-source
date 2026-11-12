package com.lattice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {
    private fun playing(pos: Long, at: Long, speed: Float = 1f, dur: Long = 240_000L) =
        PlaybackClock.Timing(playing = true, durMs = dur, posMs = pos, atMs = at, speed = speed, canSeek = true)

    @Test fun extrapolatesOnlyWhilePlaying() {
        assertEquals(15_000L, PlaybackClock.extrapolate(10_000, 1_000, 1f, true, 6_000, 0))
        assertEquals(20_000L, PlaybackClock.extrapolate(10_000, 1_000, 2f, true, 6_000, 0))
        assertEquals(10_000L, PlaybackClock.extrapolate(10_000, 1_000, 1f, false, 6_000, 0))
    }

    @Test fun extrapolationIsClampedToTheTrack() {
        assertEquals(240_000L, PlaybackClock.extrapolate(239_000, 0 + 1, 1f, true, 10_001, 240_000))
        assertEquals(0L, PlaybackClock.extrapolate(-1, 1, 1f, false, 2, 0))
    }

    @Test fun firstFrameIsAlwaysNews() {
        assertTrue(PlaybackClock.changed(null, playing(0, 1)))
    }

    @Test fun aPlayerTickingEverySecondIsNotNews() {
        // The player re-announces itself every second, a little late or early.
        val sent = playing(pos = 60_000, at = 100_000)
        for (i in 1..120) {
            val now = 100_000L + i * 1_000L
            val tick = playing(pos = 60_000L + i * 1_000L + (if (i % 2 == 0) 300 else -300), at = now)
            assertFalse("tick $i", PlaybackClock.changed(sent, tick))
        }
    }

    @Test fun aSeekIsNews() {
        val sent = playing(pos = 60_000, at = 100_000)
        assertTrue(PlaybackClock.changed(sent, playing(pos = 120_000, at = 105_000)))
        // Back by two seconds is a jump too.
        assertTrue(PlaybackClock.changed(sent, playing(pos = 63_000, at = 105_000)))
        // Under the tolerance is drift, not a seek.
        assertFalse(PlaybackClock.changed(sent, playing(pos = 66_400, at = 105_000)))
    }

    @Test fun stateSpeedLengthAndSeekabilityAreNews() {
        val sent = playing(pos = 60_000, at = 100_000)
        assertTrue(PlaybackClock.changed(sent, sent.copy(playing = false, speed = 0f)))
        assertTrue(PlaybackClock.changed(sent, sent.copy(speed = 1.5f)))
        assertTrue(PlaybackClock.changed(sent, sent.copy(durMs = 1L)))
        assertTrue(PlaybackClock.changed(sent, sent.copy(canSeek = false)))
    }

    @Test fun pausedRepeatsAreNotNews() {
        val paused = playing(pos = 42_000, at = 1_000).copy(playing = false, speed = 0f)
        assertFalse(PlaybackClock.changed(paused, paused.copy(atMs = 900_000)))
    }

    @Test fun atMovesTheAnchorToNow() {
        val t = PlaybackClock.at(playing(pos = 1_000, at = 10_000), 13_000)
        assertEquals(4_000L, t.posMs)
        assertEquals(13_000L, t.atMs)
        // A re-anchored frame predicts the same as the original.
        assertFalse(PlaybackClock.changed(playing(pos = 1_000, at = 10_000), t))
    }

    @Test fun seekArgOfHoursSurvivesCbor() {
        val fiveHours = 5L * 3600 * 1000
        val frame = CborLite.decodeMap(CborLite.encode(mapOf("t" to "cmd", "cmd" to 9L, "arg" to fiveHours)))
        assertEquals(fiveHours, frame["arg"] as Long)
        val np = CborLite.decodeMap(CborLite.encode(mapOf("spd" to 1.25, "pos" to 61_500L, "seek" to true)))
        assertEquals(1.25, np["spd"] as Double, 0.0)
        assertEquals(61_500L, np["pos"])
        assertEquals(true, np["seek"])
    }
}
