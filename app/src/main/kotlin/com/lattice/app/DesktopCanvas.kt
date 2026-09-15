package com.lattice.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

// ---- the canvas -------------------------------------------------------------

/**
 * The whole of what the app shows: the window the desktop currently has
 * focused, live. A picture of it from wf-recorder, or — when it is a dispatched
 * agent's terminal — the tmux pane read as text.
 *
 * Never take this out of the composition to show something else. Its
 * `DisposableEffect`s stop the mirror and close the terminal on disposal, so
 * hiding it behind a sheet would tear down the stream you are about to come
 * back to. The sheets draw OVER it for exactly that reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DesktopCanvas(
    app: AppState,
    prefs: CanvasPrefs,
    ready: Boolean,
    agentSession: String?,
    termMode: Boolean,
    webPage: WebPage?,
    webMode: Boolean,
    onWebTitle: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fps = app.mirrorFps
    val desk by Link.desk.collectAsState()
    val frame by Link.frame.collectAsState()
    val mirrorError by Link.mirrorError.collectAsState()
    val wholeOutput = prefs.wholeOutput
    val output = prefs.output
    val mirrorOn = prefs.mirrorOn
    val outputs = desk.outputs
    val focused = desk.focused
    val fallback = outputs.firstOrNull { it.name == output } ?: outputs.firstOrNull { it.name == focused?.output } ?: outputs.firstOrNull()

    // The mirror follows the activity: a locked phone or a backgrounded app
    // must not keep wf-recorder and ~1 Mbit/s of frames running on the desktop.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Focused window by default (the bridge follows focus); whole output on
    // request. Only while this tab is up, the app is in front, and the bridge
    // is reachable.
    // Idle: no touch anywhere in the app for the configured stretch.
    val lastTouch by Interaction.lastTouch.collectAsState()
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch, app.mirrorIdleSeconds) {
        idle = false
        if (app.mirrorIdleSeconds > 0) {
            kotlinx.coroutines.delay(app.mirrorIdleSeconds * 1000L)
            idle = true
        }
    }
    // Metered: mobile data (or a hotspot) unless allowed in Settings.
    val ctx = LocalContext.current
    var metered by remember { mutableStateOf(isMetered(ctx)) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) { metered = isMetered(ctx) }
            override fun onAvailable(n: android.net.Network) { metered = isMetered(ctx) }
            override fun onLost(n: android.net.Network) { metered = isMetered(ctx) }
        }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    val meteredBlock = metered && !app.mirrorOnMetered
    // Live only while the app is in front, someone is touching the phone now
    // and then, and the network is not metered.
    val live = ready && mirrorOn && foreground && !idle && !meteredBlock

    // `agentSession` and `termMode` are decided by the shell — the input sheet
    // routes its keystrokes by the same answer, so there is one place that
    // works it out and everyone reads it.
    val termScreen by Link.term.collectAsState()
    val termError by Link.termError.collectAsState()
    val termLive = ready && mirrorOn && foreground && !idle && !meteredBlock && termMode

    LaunchedEffect(agentSession, termLive) {
        if (termLive && agentSession != null) Link.term(agentSession) else Link.termClose()
    }
    DisposableEffect(Unit) { onDispose { Link.termClose() } }
    // Same watchdog the mirror has, for the same reason: an "open" can be lost
    // in a link or bridge restart, and a restarted bridge has forgotten what
    // it was following.
    LaunchedEffect(agentSession, termLive) {
        while (termLive && agentSession != null) {
            kotlinx.coroutines.delay(3000)
            val now = System.currentTimeMillis()
            val last = Link.lastTermAt
            val silent = if (last == 0L) now - Link.termAskedAt > 6000 else now - last > 20000
            // A session with nothing happening in it legitimately sends
            // nothing, so only re-ask when we never got a first screen.
            if (silent && last == 0L) Link.termReopen()
        }
    }

    // The terminal's text size survives the app being killed — you set it once
    // for your eyes, not once per session. Written when the pinch settles
    // rather than on every frame of it.
    LaunchedEffect(app.termZoom) {
        kotlinx.coroutines.delay(600)
        app.onTermZoomChange(app.termZoom)
    }

    // Pinching the picture asks the bridge for a BIGGER capture rather than
    // magnifying the pixels it already sent: `lattice-link` clamps width to
    // 240…1920 and hands it to ffmpeg's scaler, so this costs bandwidth and
    // nothing else — and only while somebody is actually zoomed in.
    //
    // Settled and quantized first. Every width change restarts wf-recorder on
    // the desktop, and a restart per pinch frame is a stutter machine.
    var mirrorZoom by remember { mutableStateOf(1f) }
    var settledZoom by remember { mutableStateOf(1f) }
    LaunchedEffect(mirrorZoom) {
        kotlinx.coroutines.delay(400)
        settledZoom = mirrorZoom
    }
    val baseWidth = if (wholeOutput) 1024 else 768
    val mirrorWidth = remember(baseWidth, settledZoom) {
        val want = (baseWidth * settledZoom).toInt()
        ((want + 255) / 256 * 256).coerceIn(baseWidth, 1920)
    }

    LaunchedEffect(fallback?.name, wholeOutput, live, fps, termMode, webMode, mirrorWidth) {
        if (live && !termMode && !webMode && fallback != null) {
            Link.mirror(on = true, focused = !wholeOutput, output = fallback.name, fps = fps, width = mirrorWidth)
        } else {
            Link.mirror(on = false)
        }
    }
    DisposableEffect(Unit) { onDispose { Link.mirror(on = false) } }
    // Watchdog: an "on" can get lost in a link or bridge restart, and a
    // bridge that restarts forgets what it was showing. Whenever the mirror
    // should be live and no frame has arrived for 3 s, ask again.
    LaunchedEffect(fallback?.name, wholeOutput, live, fps, termMode, webMode, mirrorWidth) {
        while (live && !termMode && !webMode && fallback != null) {
            kotlinx.coroutines.delay(2500)
            val now = System.currentTimeMillis()
            val last = Link.lastFrameAt
            // A capture takes up to ~1 s to start and the focus can move a
            // few times in a row; only a real silence counts.
            // At 1 fps a frame every second is normal; the silence threshold
            // scales with the chosen rate.
            val gap = maxOf(4000L, 3000L / fps.coerceAtLeast(1))
            val silent = if (last == 0L) now - Link.mirrorAskedAt > 6000 else now - last > gap
            if (silent) {
                Link.mirror(on = true, focused = !wholeOutput, output = fallback.name, fps = fps, width = mirrorWidth)
            }
        }
    }

    // The chips that used to sit above the picture (terminal|mirror, the
    // per-output whole-screen picks, live|paused) moved into the This-window
    // sheet behind the title strip. The canvas is the picture and nothing else.
    Column(modifier) {
        if (!ready) NoticeCard("Desktop not reachable", "The mirror needs the desktop's bridge. Check Settings for the link state.", Modifier.padding(16.dp))
        val surfaceError = when {
            termMode -> termError?.let { "Terminal: $it" }
            webMode -> null
            else -> mirrorError?.let { "Mirror: $it" }
        }
        surfaceError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }

        val pausedWhy = when {
            !ready || !mirrorOn -> null
            idle -> "paused — no touch for ${app.mirrorIdleSeconds} s · tap to resume"
            meteredBlock -> "paused on mobile data · allow it under Settings › Mirror"
            else -> null
        }
        // The surface takes everything that is left. In terminal mode it is
        // text on a grid; otherwise the mirror's picture, letterboxed to its
        // own aspect ratio, with taps landing inside the window it shows.
        // No padding: the canvas is width-bound in portrait (the rail already
        // took 48dp), and MirrorView letterboxes to its own aspect ratio, so
        // every dp here is picture.
        //
        // systemGestureExclusion keeps Android's left-edge back gesture off the
        // leftward swipe that moves focus. The system caps exclusions at 200dp
        // per edge, so this protects the bottom of the canvas rather than all
        // of it — and it is moot on this phone today, which is in 3-button
        // mode (`settings get secure navigation_mode` = 0). It matters the day
        // that changes.
        Box(
            Modifier.weight(1f).fillMaxWidth().systemGestureExclusion(),
            contentAlignment = Alignment.Center,
        ) {
            if (webMode && webPage != null) {
                // The page, rendered here. No mirror is running behind it —
                // there would be nothing to look at, and not running it is
                // the whole saving.
                WebSurface(
                    url = webPage.url,
                    enabled = ready && foreground,
                    onOpenOnDesktop = { u -> Link.act("Spawn", "command" to org.json.JSONArray(listOf("xdg-open", u))) },
                    onTitle = onWebTitle,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (termMode) {
                TerminalView(
                    screen = termScreen,
                    enabled = ready && termLive,
                    waiting = pausedWhy ?: if (!termLive) "terminal paused" else null,
                    zoom = app.termZoom,
                    onZoom = { app.termZoom = it },
                    onScroll = { Link.termScroll(it) },
                    onSwipe = { dir ->
                        Link.act(JSONObject().put(
                            if (dir < 0) "FocusWindowDownOrColumnRight" else "FocusWindowUpOrColumnLeft",
                            JSONObject(),
                        ))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MirrorView(frame = frame, enabled = ready, onZoom = { mirrorZoom = it })
                if (pausedWhy != null) {
                    Text(
                        pausedWhy,
                        Modifier
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (termMode) {
            TerminalKeys(
                enabled = ready && termLive && termScreen != null,
                onInput = { Link.termInput(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The compositor app-id every dispatched agent's terminal carries. The Wrangler
 * opens a `foot` whose app-id is this plus the tmux session name
 * (`scripts/wrangler-dispatch.sh`), which is the whole of how the phone knows
 * an agent session when it sees one.
 */
const val AGENT_APP_PREFIX = "tmux-attach-"

private fun isMetered(ctx: android.content.Context): Boolean =
    ctx.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered == true

/** How far two fingers will magnify the mirror's picture. */
private const val MIRROR_MAX_ZOOM = 5f

@Composable
fun MirrorView(frame: Link.Frame?, enabled: Boolean, onZoom: (Float) -> Unit = {}) {
    // The picture on screen is decoupled from the newest frame so a swipe can
    // slide the OLD window out and the NEW one in: `shown` is what we draw,
    // `offset` is its horizontal translation in px, `awaitingWin` is set while
    // we wait for the first frame of a different window after a swipe.
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var awaitingFrom by remember { mutableStateOf<Long?>(null) }
    var swipeDir by remember { mutableStateOf(0) }
    var widthPx by remember { mutableStateOf(1f) }
    var heightPx by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()

    // Pinch zoom. `scale` magnifies the picture about wherever the fingers
    // were, `pan` slides it, and pan is clamped so the picture always covers
    // the frame — there is never a band of background down one side.
    //
    // The canvas gets `scale` back out because a 768-wide capture is 768 wide
    // however hard you pinch: it asks the bridge for a bigger one rather than
    // hand you soft pixels.
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var lastWin by remember { mutableStateOf<Long?>(null) }
    val reportZoom by androidx.compose.runtime.rememberUpdatedState(onZoom)

    fun clampPan(p: Offset, s: Float): Offset =
        if (s <= 1f) Offset.Zero else Offset(
            p.x.coerceIn((1f - s) * widthPx, 0f),
            p.y.coerceIn((1f - s) * heightPx, 0f),
        )

    fun zoomBy(zoom: Float, move: Offset, about: Offset) {
        val next = (scale * zoom).coerceIn(1f, MIRROR_MAX_ZOOM)
        val k = next / scale
        // Whatever is under the fingers stays under them.
        val kept = Offset(about.x - (about.x - pan.x) * k, about.y - (about.y - pan.y) * k)
        scale = next
        pan = clampPan(kept + move, next)
    }

    fun settleZoom() {
        // Forgiving bottom end: pinch roughly back to life size and it snaps,
        // so you never end up stuck at 1.03× wondering why it looks soft.
        if (scale < 1.12f) {
            scale = 1f
            pan = Offset.Zero
        }
        reportZoom(scale)
    }

    // Animations run in the composable's own scope, never inside a
    // LaunchedEffect keyed on the frame: a new frame every 250 ms would cancel
    // the slide mid-way and leave the picture parked off-screen (it did).
    fun slideBackIn() {
        scope.launch {
            offset.snapTo(swipeDir * widthPx)
            offset.animateTo(0f, androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    fun springHome() {
        scope.launch { offset.animateTo(0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium)) }
    }
    LaunchedEffect(frame) {
        val f = frame ?: run { shown = null; return@LaunchedEffect }
        // A different window is a different picture: drop the magnification
        // rather than land the user in the corner of something they have not
        // seen yet. The mirror follows desktop focus, so this fires whether or
        // not the swipe that changed window came from the phone.
        if (lastWin != null && f.win != lastWin && scale > 1f) {
            scale = 1f
            pan = Offset.Zero
            reportZoom(1f)
        }
        lastWin = f.win
        val waiting = awaitingFrom
        if (waiting != null && f.win != waiting) {
            // The next window's first picture: bring it in from the far side.
            awaitingFrom = null
            shown = f.bitmap
            slideBackIn()
        } else if (waiting == null) {
            shown = f.bitmap
        }
    }
    // No other window answered the swipe: put the picture back.
    LaunchedEffect(awaitingFrom) {
        if (awaitingFrom != null) {
            kotlinx.coroutines.delay(700)
            if (awaitingFrom != null) {
                awaitingFrom = null
                frame?.let { shown = it.bitmap }
                springHome()
            }
        }
    }

    val bmp = shown
    val ratio = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 16f / 10f
    Box(
        Modifier
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            // The surface's handle, on the frame rather than the picture: it
            // has to exist before the first frame arrives, or "waiting for a
            // frame" and "not mirroring at all" look identical from outside.
            .semantics { contentDescription = "desktop mirror" }
            .onSizeChanged {
                widthPx = it.width.toFloat().coerceAtLeast(1f)
                heightPx = it.height.toFloat().coerceAtLeast(1f)
            }
            .mirrorGestures(
                enabled = enabled,
                scaleOf = { scale },
                panOf = { pan },
                onZoomBy = { zoom, move, about -> zoomBy(zoom, move, about) },
                onZoomEnd = { settleZoom() },
                onPanBy = { d -> pan = clampPan(pan + d, scale) },
                onFollow = { dx -> scope.launch { offset.snapTo(dx * 0.85f) } },
                onSwipe = { dir ->
                    // dir = -1: content goes left (next window), +1: right (previous).
                    swipeDir = -dir
                    awaitingFrom = frame?.win ?: -1L
                    scope.launch {
                        offset.animateTo(dir * widthPx, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                    }
                },
                onLetGo = {
                    scope.launch { offset.animateTo(0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium)) }
                },
            ),
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // Origin top-left so the magnification is plain arithmetic
                    // at the tap end: content = (touch − pan) / scale.
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.value + pan.x
                    translationY = pan.y
                },
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Text(
                if (enabled) "waiting for the first frame…" else "mirror off",
                Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium,
            )
        }
        if (scale > 1.01f) {
            Text(
                "%.1f×".format(scale),
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One gesture vocabulary for the mirror, resolved from the pointers themselves:
 *   tap            click where you tapped        double tap   double click
 *   long press     right click                   long press + move   drag (left held)
 *   swipe ← / →    focus the next / previous window — the mirror follows focus,
 *                  so the picture switches with it (every window in a column
 *                  counts, not just columns)
 *   pinch          magnify the picture, about the fingers; two-finger drag
 *                  moves it, and once magnified a one-finger drag moves it too
 *                  (the window-changing swipe wants the whole picture on screen
 *                  to mean anything)
 *
 * `onFollow` gets the horizontal displacement while a swipe is forming,
 * `onSwipe` its direction once committed, `onLetGo` a release without one.
 * Every tap is mapped back through the magnification before it is sent, so a
 * click lands where the finger was in the WINDOW, not on the screen.
 */
private fun Modifier.mirrorGestures(
    enabled: Boolean,
    scaleOf: () -> Float,
    panOf: () -> Offset,
    onZoomBy: (Float, Offset, Offset) -> Unit,
    onZoomEnd: () -> Unit,
    onPanBy: (Offset) -> Unit,
    onFollow: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
    onLetGo: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val swipeMin = 72.dp.toPx()
    val longPressMs = 350L
    var lastTapAt = 0L
    val pinch = Pinch()
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val start = down.position
        val t0 = System.currentTimeMillis()
        var pos = start
        var moved = false
        var dragging = false
        var swiped = false
        var following = false
        var pinching = false

        // Where a touch points in the WINDOW's own coordinates: the picture
        // may be magnified and slid under the finger.
        fun uv(p: Offset): Offset {
            val s = scaleOf()
            val q = panOf()
            return Offset(
                (((p.x - q.x) / s) / size.width).coerceIn(0f, 1f),
                (((p.y - q.y) / s) / size.height).coerceIn(0f, 1f),
            )
        }

        while (true) {
            val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
            val now = System.currentTimeMillis()
            if (ev == null) {
                // Still pressed, nothing new: a long press becomes a drag the
                // moment the finger moves, or a right click if it never does.
                if (!pinching && !moved && !dragging && now - t0 > longPressMs) {
                    val u = uv(start)
                    Link.pointerIn(u.x, u.y)
                    Link.button("left", true)
                    dragging = true
                }
                continue
            }
            val pressed = ev.changes.count { it.pressed }
            if (pinching || pressed >= 2) {
                if (!pinching) {
                    // A second finger cancels whatever the first was becoming:
                    // a held button must not stay held through a pinch.
                    pinching = true
                    if (dragging) { Link.button("left", false); dragging = false }
                    if (following) { onLetGo(); following = false }
                    pinch.reset()
                }
                ev.changes.forEach { it.consume() }
                if (pressed == 0) { onZoomEnd(); break }
                pinch.update(ev)?.let { onZoomBy(it.zoom, it.pan, it.centroid) }
                continue
            }
            val ch = ev.changes.firstOrNull() ?: continue
            ch.consume()
            if (!ch.pressed) {
                // Finger up: decide what the touch was.
                when {
                    swiped -> {}
                    dragging -> {
                        Link.button("left", false)
                        if (!moved) {
                            // Long press without motion: a right click, not a drag.
                            Link.click("right")
                        }
                    }
                    !moved -> {
                        val u = uv(start)
                        Link.pointerIn(u.x, u.y)
                        if (now - lastTapAt < 300) {
                            Link.click("left"); Link.click("left")
                            lastTapAt = 0
                        } else {
                            Link.click("left")
                            lastTapAt = now
                        }
                    }
                    following -> onLetGo()
                }
                break
            }
            val prev = pos
            pos = ch.position
            val dx = pos.x - start.x
            val dy = pos.y - start.y
            if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
            if (dragging) {
                val u = uv(pos)
                Link.pointerIn(u.x, u.y)
            } else if (scaleOf() > 1.01f) {
                // Magnified: one finger moves the picture. Tap, double tap and
                // long press all still mean what they meant.
                if (moved) onPanBy(pos - prev)
            } else if (!swiped && moved) {
                // Mostly-horizontal motion drags the picture with the finger.
                if (abs(dx) > abs(dy)) {
                    following = true
                    onFollow(dx)
                }
                if (now - t0 < 600 && abs(dx) > swipeMin && abs(dx) > 2 * abs(dy)) {
                    swiped = true
                    // Content follows the finger: swipe left shows what is to the right.
                    Link.act(JSONObject().put(if (dx < 0) "FocusWindowDownOrColumnRight" else "FocusWindowUpOrColumnLeft", JSONObject()))
                    onSwipe(if (dx < 0) -1 else 1)
                }
            }
        }
    }
}
