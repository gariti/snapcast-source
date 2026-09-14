package com.lattice.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Which sheet is up over the canvas, if any. */
enum class Sheet { Window, Input, Audio, Settings }

/**
 * The sheet layer: a scrim over the canvas and a surface that slides up from
 * the bottom of it.
 *
 * Deliberately NOT material3's `ModalBottomSheet`, for three reasons that are
 * all structural rather than cosmetic:
 *
 *  1. It is a separate window, so its scrim covers the rail and eats rail
 *     taps. There is no supported way to pass a region of it through to the
 *     activity window underneath, and the rail staying live is the whole point
 *     of this design.
 *  2. `Interaction.touch()` — the idle probe the mirror's pause timer rides on
 *     — is a pointer handler on the activity window's root. Touches delivered
 *     to a separate sheet window never reach it, so the mirror would pause
 *     *behind* an open sheet after `mirrorIdleSeconds` of working in it.
 *  3. It owns its own insets, with a long tail of `imePadding()` bugs. In one
 *     window the IME is a one-liner.
 *
 * Host it inside the canvas column of the Row, so the scrim and the sheet are
 * geometrically confined there and the rail needs no z-order tricks.
 */
@Composable
fun SheetHost(
    open: Sheet?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // The content gets the scroller it lives in, so a sheet that has to keep
    // its bottom rows reachable under the keyboard can scroll itself there.
    content: @Composable (Sheet, androidx.compose.foundation.ScrollState) -> Unit,
) {
    // Render the LAST sheet asked for, not `open`. Rendering `open?.let { … }`
    // empties the body the instant it goes null, so the slide-out would
    // animate an empty box — a hard cut, which this project does not do.
    var shown by remember { mutableStateOf(Sheet.Settings) }
    LaunchedEffect(open) { if (open != null) shown = open }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxSheet = maxHeight * 0.78f

        AnimatedVisibility(
            visible = open != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }

        AnimatedVisibility(
            visible = open != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
        ) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheet)
                    // The IME inset already subsumes the navigation bar, so
                    // union them — imePadding().navigationBarsPadding() would
                    // double-count whenever the keyboard is up.
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                // The one and only scroller in the sheet layer: no sheet
                // content may bring a verticalScroll, LazyColumn, weight or
                // fillMaxSize of its own.
                val scroll = rememberScrollState()
                Column(Modifier.verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                    content(shown, scroll)
                }
            }
        }
    }
}
