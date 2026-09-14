package com.lattice.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/** Everything the four tabs share. Owned by MainActivity, seeded from prefs. */
class AppState(
    host: String, slotIndex: Int, partyMode: Boolean, psk: String, mirrorFps: Int,
    val onHostChange: (String) -> Unit,
    val onSlotChange: (Int) -> Unit,
    val onPartyModeChange: (Boolean) -> Unit,
    val onPskChange: (String) -> Unit,
    val onMirrorFpsChange: (Int) -> Unit,
    val onStartCapture: (String, Int, String) -> Unit,
    val onStopCapture: () -> Unit,
) {
    var host by mutableStateOf(host)
    var slotIndex by mutableStateOf(slotIndex)
    var partyMode by mutableStateOf(partyMode)
    var psk by mutableStateOf(psk)
    var mirrorFps by mutableStateOf(mirrorFps)
}

enum class Tab(val label: String) { Desktop("Desktop"), Input("Input"), Windows("Windows"), Audio("Audio"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LatticeApp(app: AppState) {
    var tab by rememberSaveable { mutableStateOf(Tab.Desktop) }
    val client by Link.client.collectAsState()
    val linkState by (client?.state ?: kotlinx.coroutines.flow.MutableStateFlow(ControlChannelClient.LinkState.Disconnected)).collectAsState()
    val bridgeUp by (client?.bridgeUp ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val dict by Link.dict.collectAsState()

    // Dictation outcomes surface as a snackbar on whichever tab is open.
    LaunchedEffect(dict) {
        when (val d = dict) {
            is Link.DictState.Done -> { snackbar.showSnackbar("Typed: " + d.text.take(80)); Link.clearDict() }
            is Link.DictState.Failed -> { snackbar.showSnackbar("Dictation: " + d.reason); Link.clearDict() }
            else -> {}
        }
    }

    val status = when (val s = linkState) {
        is ControlChannelClient.LinkState.Connected ->
            if (s.proto < 2) "linked · old desktop" else if (bridgeUp) "linked" else "linked · bridge offline"
        ControlChannelClient.LinkState.Connecting -> "connecting…"
        else -> if (client == null) "not running" else "offline"
    }
    val statusColor = when {
        linkState is ControlChannelClient.LinkState.Connected && bridgeUp -> MaterialTheme.colorScheme.primary
        linkState is ControlChannelClient.LinkState.Connected -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Lattice", fontFamily = FontFamily.Monospace)
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
                        Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Desktop -> Icons.Filled.Monitor
                                    Tab.Input -> Icons.Filled.Keyboard
                                    Tab.Windows -> Icons.Filled.Apps
                                    Tab.Audio -> Icons.Filled.Headphones
                                    Tab.Settings -> Icons.Filled.Settings
                                }, contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = { DictateFab() },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Desktop -> DesktopScreen(app, ready = bridgeUp && (linkState as? ControlChannelClient.LinkState.Connected)?.proto?.let { it >= 2 } == true)
                Tab.Input -> InputScreen(ready = bridgeUp && (linkState as? ControlChannelClient.LinkState.Connected)?.proto?.let { it >= 2 } == true)
                Tab.Windows -> WindowsScreen()
                Tab.Audio -> AudioScreen(app)
                Tab.Settings -> SettingsScreen(app)
            }
        }
    }
}

// ---- dictation -------------------------------------------------------------

@Composable
fun DictateFab() {
    val ctx = LocalContext.current
    val active by DictationService.active.collectAsState()
    val dict by Link.dict.collectAsState()
    val level by DictationService.level.collectAsState()
    val micGranted = remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        micGranted.value = ok
        if (ok) DictationService.toggle(ctx)
    }
    val transcribing = dict is Link.DictState.Transcribing
    ExtendedFloatingActionButton(
        onClick = {
            if (!micGranted.value) ask.launch(Manifest.permission.RECORD_AUDIO)
            else DictationService.toggle(ctx)
        },
        icon = { Icon(if (active) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null) },
        text = {
            Text(
                when {
                    active -> "Done" + if (level > 0.02f) " ●" else ""
                    transcribing -> "Transcribing…"
                    else -> "Dictate"
                }
            )
        },
        expanded = true,
        containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
    )
}

// ---- Desktop tab -------------------------------------------------------------

@Composable
fun DesktopScreen(app: AppState, ready: Boolean) {
    val fps = app.mirrorFps
    val desk by Link.desk.collectAsState()
    val frame by Link.frame.collectAsState()
    val mirrorError by Link.mirrorError.collectAsState()
    var wholeOutput by rememberSaveable { mutableStateOf(false) }
    var output by rememberSaveable { mutableStateOf<String?>(null) }
    var mirrorOn by rememberSaveable { mutableStateOf(true) }
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
    LaunchedEffect(fallback?.name, wholeOutput, ready, mirrorOn, foreground, fps) {
        if (ready && mirrorOn && foreground && fallback != null) {
            Link.mirror(on = true, focused = !wholeOutput, output = fallback.name, fps = fps, width = if (wholeOutput) 1024 else 768)
        } else {
            Link.mirror(on = false)
        }
    }
    DisposableEffect(Unit) { onDispose { Link.mirror(on = false) } }
    // Watchdog: an "on" can get lost in a link or bridge restart, and a
    // bridge that restarts forgets what it was showing. Whenever the mirror
    // should be live and no frame has arrived for 3 s, ask again.
    LaunchedEffect(fallback?.name, wholeOutput, ready, mirrorOn, foreground, fps) {
        while (ready && mirrorOn && foreground && fallback != null) {
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
                Link.mirror(on = true, focused = !wholeOutput, output = fallback.name, fps = fps, width = if (wholeOutput) 1024 else 768)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // One thin strip: what is shown, and the two toggles.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = !wholeOutput, onClick = { wholeOutput = false }, label = { Text("focused window") })
            outputs.forEach { o ->
                FilterChip(selected = wholeOutput && fallback?.name == o.name, onClick = { wholeOutput = true; output = o.name }, label = { Text(o.name) })
            }
            FilterChip(selected = mirrorOn, onClick = { mirrorOn = !mirrorOn }, label = { Text(if (mirrorOn) "live" else "paused") })
        }
        val title = if (!wholeOutput) focused?.let { "${it.app} — ${it.title}" } ?: "no focused window" else fallback?.let { "${it.name} · ${it.w}×${it.h}" } ?: ""
        Text(
            title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!ready) NoticeCard("Desktop not reachable", "The mirror needs the desktop's bridge. Check Settings for the link state.", Modifier.padding(16.dp))
        mirrorError?.let { Text("Mirror: $it", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        // The picture takes everything that is left, letterboxed to its own
        // aspect ratio; taps land inside the window it shows.
        Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
            MirrorView(frame = frame, enabled = ready)
        }
    }
}

@Composable
fun MirrorView(frame: Link.Frame?, enabled: Boolean) {
    // The picture on screen is decoupled from the newest frame so a swipe can
    // slide the OLD window out and the NEW one in: `shown` is what we draw,
    // `offset` is its horizontal translation in px, `awaitingWin` is set while
    // we wait for the first frame of a different window after a swipe.
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var awaitingFrom by remember { mutableStateOf<Long?>(null) }
    var swipeDir by remember { mutableStateOf(0) }
    var widthPx by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()

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
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .mirrorGestures(
                enabled = enabled,
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
                bmp.asImageBitmap(), contentDescription = "desktop mirror",
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = offset.value },
                contentScale = ContentScale.FillBounds,
            )
        } else {
            Text(
                if (enabled) "waiting for the first frame…" else "mirror off",
                Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * One gesture vocabulary for the mirror, resolved from a single touch:
 *   tap            click where you tapped        double tap   double click
 *   long press     right click                   long press + move   drag (left held)
 *   swipe ← / →    focus the next / previous window — the mirror follows focus,
 *                  so the picture switches with it (every window in a column
 *                  counts, not just columns)
 * `onFollow` gets the horizontal displacement while a swipe is forming,
 * `onSwipe` its direction once committed, `onLetGo` a release without one.
 */
private fun Modifier.mirrorGestures(
    enabled: Boolean,
    onFollow: (Float) -> Unit,
    onSwipe: (Int) -> Unit,
    onLetGo: () -> Unit,
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val swipeMin = 72.dp.toPx()
    val longPressMs = 350L
    var lastTapAt = 0L
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
        while (true) {
            val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
            val now = System.currentTimeMillis()
            if (ev == null) {
                // Still pressed, nothing new: a long press becomes a drag the
                // moment the finger moves, or a right click if it never does.
                if (!moved && !dragging && now - t0 > longPressMs) {
                    Link.pointerIn(start.x / size.width, start.y / size.height)
                    Link.button("left", true)
                    dragging = true
                }
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
                        Link.pointerIn(start.x / size.width, start.y / size.height)
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
            pos = ch.position
            val dx = pos.x - start.x
            val dy = pos.y - start.y
            if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
            if (dragging) {
                Link.pointerIn(pos.x / size.width, pos.y / size.height)
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

@Composable
fun InputScreen(ready: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!ready) NoticeCard("Desktop not reachable", "Pointer and keyboard need the desktop's bridge.")
        Trackpad(enabled = ready)
        KeyRow(enabled = ready)
        TextComposer(enabled = ready)
        Spacer(Modifier.height(72.dp))
    }
}

@Composable
fun Trackpad(enabled: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Trackpad", style = MaterialTheme.typography.labelMedium)
            Text("one finger moves · tap clicks · two fingers scroll · hold then drag to drag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Link.click("left") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Left") }
                OutlinedButton(onClick = { Link.click("middle") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Middle") }
                OutlinedButton(onClick = { Link.click("right") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Right") }
            }
        }
    }
}

@Composable
fun KeyRow(enabled: Boolean) {
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var sup by remember { mutableStateOf(false) }
    fun send(key: String) {
        val mods = buildList {
            if (ctrl) add("ctrl"); if (alt) add("alt"); if (shift) add("shift"); if (sup) add("super")
        }
        Link.key((mods + key).joinToString("+"))
        ctrl = false; alt = false; shift = false; sup = false
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keys", style = MaterialTheme.typography.labelMedium)
            Text("Modifiers arm the next key. Chords reach the focused app, not the compositor — use Windows for those.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("ctrl") }, enabled = enabled)
                FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("alt") }, enabled = enabled)
                FilterChip(selected = shift, onClick = { shift = !shift }, label = { Text("shift") }, enabled = enabled)
                FilterChip(selected = sup, onClick = { sup = !sup }, label = { Text("super") }, enabled = enabled)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Escape" to "esc", "Tab" to "tab", "Return" to "⏎", "BackSpace" to "⌫", "Delete" to "del", "space" to "␣").forEach { (k, l) ->
                    AssistChip(onClick = { send(k) }, label = { Text(l) }, enabled = enabled)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Left" to "←", "Down" to "↓", "Up" to "↑", "Right" to "→", "Home" to "home", "End" to "end", "Page_Up" to "pgup", "Page_Down" to "pgdn").forEach { (k, l) ->
                    AssistChip(onClick = { send(k) }, label = { Text(l) }, enabled = enabled)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..12).forEach { n -> AssistChip(onClick = { send("F$n") }, label = { Text("F$n") }, enabled = enabled) }
            }
        }
    }
}

@Composable
fun TextComposer(enabled: Boolean) {
    var text by remember { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Type into the focused window", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                enabled = enabled, minLines = 2, placeholder = { Text("text lands as keystrokes; ⏎ sends Return") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Link.typeText(text); text = "" }, enabled = enabled && text.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Type") }
                OutlinedButton(onClick = { Link.typeText(text); Link.key("Return"); text = "" }, enabled = enabled && text.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Type + ⏎") }
            }
        }
    }
}

// ---- Windows tab --------------------------------------------------------------

@Composable
fun WindowsScreen() {
    val desk by Link.desk.collectAsState()
    val thumbs by Link.thumbs.collectAsState()
    var selectedWs by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedWin by rememberSaveable { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val client by Link.client.collectAsState()
    val ready = client?.sessionReady == true

    val ws = desk.workspaces.firstOrNull { it.id == selectedWs } ?: desk.workspaces.firstOrNull { it.focused } ?: desk.workspaces.firstOrNull { it.active }
    val wins = ws?.let { desk.windowsOn(it.id) } ?: desk.windows
    val sel = desk.windows.firstOrNull { it.id == selectedWin }

    // Thumbnails for what is on screen, refreshed when the set changes.
    LaunchedEffect(wins.map { it.id to it.visible }, ready) {
        if (ready) wins.filter { it.visible && it.rect != null }.forEach { Link.requestThumb(it.id, 480) }
    }

    Column(Modifier.fillMaxSize()) {
        if (!ready) NoticeCard("Desktop not reachable", "Window control needs the desktop's bridge.", Modifier.padding(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            desk.workspaces.forEach { w ->
                FilterChip(
                    selected = ws?.id == w.id,
                    onClick = { selectedWs = w.id },
                    label = { Text((w.name.ifBlank { "ws ${w.idx}" }) + " · " + w.output + if (w.active) " ●" else "") },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(wins, key = { it.id }) { w ->
                val selected = sel?.id == w.id
                Card(
                    onClick = { selectedWin = w.id },
                    colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    border = if (w.focused) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
                            val bmp = thumbs[w.id]
                            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Text(
                                if (!w.visible) "hidden" else if (w.rect == null) "off screen" else "…",
                                Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(w.app.ifBlank { "?" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (sel != null) {
            WindowActions(sel, desk, enabled = ready, onDone = { scope.launch { Link.requestDesk() } })
        }
    }
}

@Composable
fun WindowActions(w: Link.Win, desk: Link.Desk, enabled: Boolean, onDone: () -> Unit) {
    fun focusThen(vararg actions: JSONObject) {
        Link.act("FocusWindow", "id" to w.id)
        actions.forEach { Link.act(it) }
        onDone()
    }
    fun unit(name: String) = JSONObject().put(name, JSONObject())
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${w.app} — ${w.title}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { Link.act("FocusWindow", "id" to w.id); onDone() }, label = { Text("focus") }, enabled = enabled)
                AssistChip(onClick = { Link.act("CloseWindow", "id" to w.id); onDone() }, label = { Text("close") }, enabled = enabled)
                AssistChip(onClick = { Link.act("FullscreenWindow", "id" to w.id); onDone() }, label = { Text("fullscreen") }, enabled = enabled)
                AssistChip(onClick = { focusThen(unit("MaximizeColumn")) }, label = { Text("maximize") }, enabled = enabled)
                AssistChip(onClick = { Link.act("ToggleWindowFloating", "id" to w.id); onDone() }, label = { Text(if (w.floating) "tile" else "float") }, enabled = enabled)
                AssistChip(onClick = { Link.act(if (w.visible) "HideWindow" else "ShowWindow", "id" to w.id); onDone() }, label = { Text(if (w.visible) "hide" else "show") }, enabled = enabled)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { focusThen(unit("MoveColumnLeft")) }, label = { Text("← column") }, enabled = enabled)
                AssistChip(onClick = { focusThen(unit("MoveColumnRight")) }, label = { Text("column →") }, enabled = enabled)
                AssistChip(onClick = { focusThen(unit("MoveWindowToWorkspaceUp")) }, label = { Text("ws ▲") }, enabled = enabled)
                AssistChip(onClick = { focusThen(unit("MoveWindowToWorkspaceDown")) }, label = { Text("ws ▼") }, enabled = enabled)
                AssistChip(onClick = { focusThen(unit("SwitchPresetColumnWidth")) }, label = { Text("width ⇄") }, enabled = enabled)
                desk.outputs.filter { it.name != w.output }.forEach { o ->
                    AssistChip(onClick = { Link.act("MoveWindowToMonitor", "id" to w.id, "output" to o.name); onDone() }, label = { Text("→ ${o.name}") }, enabled = enabled)
                }
            }
        }
    }
}

// ---- shared bits ----------------------------------------------------------------

@Composable
fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
fun MeterBar(level: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(6.dp))
}

@Composable
fun TextRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
    }
}

@Composable
fun SmallTextButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

@Composable
fun DimText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

@Suppress("unused")
private val keepColor = Color.Unspecified
