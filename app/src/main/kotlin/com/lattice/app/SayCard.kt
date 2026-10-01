package com.lattice.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxMeter
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.StatusTone
import kotlinx.coroutines.delay

/**
 * Say: a card under the canvas, not a snackbar. Opening it starts the take;
 * the level and the time are the only live things (sampled at 6 Hz); `type it`
 * stops and transcribes; Back discards. The result lands on the canvas
 * card's status line ("Typed · …"), which the shell owns.
 */
@Composable
fun SayCard(ready: Boolean, focused: Link.Win?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lx = LxTheme.current
    val active by DictationService.active.collectAsState()
    val dict by Link.dict.collectAsState()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) DictationService.toggle(ctx)
    }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    // The level, sampled at 6 Hz — never on the service's 100 ms cadence.
    var level by remember { mutableStateOf(0f) }
    LaunchedEffect(active) {
        if (active) {
            startedAt = System.currentTimeMillis()
            while (true) {
                level = DictationService.level.value
                elapsed = (System.currentTimeMillis() - startedAt) / 1000
                delay(160)
            }
        } else level = 0f
    }
    // Opening the card IS the start, once the microphone is allowed.
    LaunchedEffect(granted, ready) {
        if (granted && ready && !active && dict is Link.DictState.Idle) DictationService.toggle(ctx)
    }
    // Done or failed: the shell shows the result on the canvas; this card is finished.
    LaunchedEffect(dict) {
        if (dict is Link.DictState.Done || dict is Link.DictState.Failed) onDone()
    }
    // Back discards the take.
    BackHandler(enabled = active) { DictationService.cancel(ctx); onDone() }

    val transcribing = dict is Link.DictState.Transcribing
    LxCard(
        active = true,
        top = {
            Column(Modifier.weight(1f)) {
                LxWordmark(stringResource(R.string.say_title))
                LxCaption(stringResource(R.string.into_x, focused?.let { Names.windowTitle(it) } ?: stringResource(R.string.into_the_desktop)))
            }
        },
        tile = {
            LxSection(
                if (transcribing) stringResource(R.string.working_out_words) else stringResource(R.string.listening),
                badge = if (active) "%d:%02d".format(elapsed / 60, elapsed % 60) else null,
                here = true,
            )
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, null, Modifier.size(lx.u(5.5f)), tint = lx.roles.accent)
                LxMeter(level, Modifier.padding(top = lx.u(1.2f), start = lx.u(2f), end = lx.u(2f)))
            }
            Spacer(Modifier.weight(1f))
            when {
                !granted -> LxStatus(stringResource(R.string.say_needs_mic), StatusTone.Warn)
                !ready -> LxStatus(stringResource(R.string.not_linked), StatusTone.Warn)
                else -> LxStatus(stringResource(R.string.say_status))
            }
        },
        bottom = {
            LxButton("?", enabled = false) {}
            LxHints(listOf(Hint("◀", stringResource(R.string.discard))))
            when {
                !granted -> LxButton(stringResource(R.string.allow), ButtonKind.Primary) { ask.launch(Manifest.permission.RECORD_AUDIO) }
                active -> LxButton(stringResource(R.string.type_it), ButtonKind.Primary, icon = Icons.Filled.KeyboardReturn) { DictationService.toggle(ctx) }
                else -> {}
            }
        },
    )
}
