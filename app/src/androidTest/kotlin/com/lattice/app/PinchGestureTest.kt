package com.lattice.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two fingers on the real phone.
 *
 * This exists because there is no other way to put them there: a stock Pixel
 * refuses `sendevent` to its `/dev/input` nodes from adb (the `shell` user is not in
 * the `input` group), and `adb shell input` has never spoken multi-touch. So a
 * pinch can only be injected from inside an instrumentation, through
 * UiAutomator's `performMultiPointerGesture`.
 *
 * **These are live tests.** The gestures they check are wired to `enabled`,
 * which is the desktop link being up, so they need a paired desktop with the
 * bridge running and the app on its canvas. The terminal one additionally
 * needs the desktop focused on a `tmux-attach-` window, and skips itself
 * rather than failing when it is not.
 *
 *     ./gradlew connectedFossDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PinchGestureTest {
    private lateinit var device: UiDevice
    private var idleWas = Prefs.DEFAULT_MIRROR_IDLE_S

    @Before
    fun launch() {
        val inst = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(inst)
        // A dark screen is not a failed gesture, but it looks exactly like one
        // from here: the activity start is dropped and every assertion misses.
        device.wakeUp()
        // Home first: it closes the notification shade, which a two-finger
        // gesture near the top of the screen can pull down, and which then
        // covers the app so every later assertion misses.
        device.pressHome()
        val ctx = inst.targetContext
        // The mirror pauses after a stretch with no touch, and a paused mirror
        // has no picture to pinch. Hold that off for the run and put the
        // user's setting back afterwards.
        val prefs = Prefs.of(ctx)
        idleWas = prefs.getInt(Prefs.KEY_MIRROR_IDLE_S, Prefs.DEFAULT_MIRROR_IDLE_S)
        prefs.edit().putInt(Prefs.KEY_MIRROR_IDLE_S, 0).commit()
        val intent = requireNotNull(ctx.packageManager.getLaunchIntentForPackage(PKG))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // `Context.startActivity` from the instrumentation's own process is a
        // background activity launch, which Android has refused since 15 — it
        // fails silently and every assertion then misses. The instrumentation
        // is allowed to start it on its own behalf.
        inst.startActivitySync(intent)
        assertTrue("the app never came up", device.wait(Until.hasObject(By.pkg(PKG).depth(0)), 8_000))
        // The canvas is empty until the first frame (or the first tmux screen)
        // arrives, and an empty canvas has no gestures on it.
        assertTrue(
            "neither surface came up — is the desktop link up?",
            device.wait(Until.hasObject(By.desc(MIRROR)), 8_000) || device.hasObject(By.desc(TERMINAL)),
        )
        Thread.sleep(2_500)
    }

    @After
    fun restoreIdlePause() {
        Prefs.of(InstrumentationRegistry.getInstrumentation().targetContext)
            .edit().putInt(Prefs.KEY_MIRROR_IDLE_S, idleWas).commit()
    }

    /**
     * The mirror magnifies, and pinching back to life size puts it away again.
     * The badge is the assertion: `MirrorView` only draws it above 1.01×.
     */
    @Test
    fun mirrorPinchMagnifiesAndSnapsBack() {
        assumeTrue(
            "not on the mirror — the desktop is focused on an agent's terminal",
            device.hasObject(By.desc(MIRROR)),
        )
        pinchOut(MIRROR)
        assertTrue(
            "no zoom badge after a pinch — is the desktop bridge up and the mirror live?",
            device.wait(Until.hasObject(By.textContains("×")), 3_000),
        )
        pinchIn(MIRROR)
        assertTrue(
            "the picture stayed magnified after pinching back to life size",
            device.wait(Until.gone(By.textContains("×")), 3_000),
        )
    }

    /**
     * The terminal's text size. The assertion is the app's own persisted zoom
     * rather than the badge: the instrumentation runs IN the app's process, so
     * it can read what the pinch actually committed, and `term_zoom` surviving
     * is half of what this feature promises.
     */
    @Test
    fun terminalPinchChangesTextSize() {
        assumeTrue(
            "not in terminal mode — focus a tmux-attach-* window on the desktop first",
            device.hasObject(By.desc(TERMINAL)),
        )
        val prefs = Prefs.of(InstrumentationRegistry.getInstrumentation().targetContext)
        val before = prefs.getFloat(Prefs.KEY_TERM_ZOOM, 1f)

        pinchOut(TERMINAL)
        val out = settled(prefs, "pinching out did not enlarge the text") { it > before * 1.2f }
        assertTrue(
            "no zoom badge while the text is off the width fit",
            device.wait(Until.hasObject(By.textContains("%")), 2_000),
        )
        assertTrue("the text size ran past its ceiling ($out)", out <= TERM_ZOOM_MAX)

        pinchIn(TERMINAL)
        val back = settled(prefs, "pinching in did not shrink the text (was $out)") { it < out }
        assertTrue("the text size ran past its floor ($back)", back >= TERM_ZOOM_MIN)
    }

    /**
     * The zoom is written 600 ms after the pinch settles, so reading prefs the
     * instant the fingers lift reads the OLD value — which looks exactly like a
     * gesture that did nothing.
     */
    private fun settled(
        prefs: android.content.SharedPreferences,
        what: String,
        predicate: (Float) -> Boolean,
    ): Float {
        val deadline = System.currentTimeMillis() + 3_000
        var z = prefs.getFloat(Prefs.KEY_TERM_ZOOM, 1f)
        while (System.currentTimeMillis() < deadline && !predicate(z)) {
            Thread.sleep(100)
            z = prefs.getFloat(Prefs.KEY_TERM_ZOOM, 1f)
        }
        assertTrue("$what (settled at $z)", predicate(z))
        return z
    }

    /**
     * `UiObject2.pinchOpen` / `pinchClose` is the only multi-touch UiAutomator
     * will inject — `UiDevice.swipe(Point[], steps)` is one finger through
     * several waypoints, not several fingers, which is an easy hour to lose.
     * The gesture is aimed at the surface's own accessibility node so both
     * fingers land inside it rather than on the rail.
     */
    private fun surface(desc: String): UiObject2 = requireNotNull(
        device.wait(androidx.test.uiautomator.Until.findObject(By.desc(desc)), 5_000),
    ) { "no \"$desc\" on screen — is the desktop link up?" }

    private fun pinchOut(desc: String) {
        surface(desc).pinchOpen(0.7f)
        device.waitForIdle()
    }

    private fun pinchIn(desc: String) {
        surface(desc).pinchClose(0.7f)
        device.waitForIdle()
    }

    private companion object {
        const val PKG = "com.lattice.app"
        /** The contentDescription each surface carries, which is also its test handle. */
        const val MIRROR = "desktop mirror"
        const val TERMINAL = "agent terminal"
    }
}
