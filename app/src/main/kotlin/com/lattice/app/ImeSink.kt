package com.lattice.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.lattice.app.lx.Alpha
import com.lattice.app.lx.LxText
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.Type

/**
 * The phone's own keyboard, wired straight through.
 *
 * The field holds no text: every keystroke goes down the wire the moment it
 * arrives and the desktop's own echo is the feedback. That is what lets a
 * y/n prompt or an arrow-key menu be driven from a phone at all.
 *
 * It opens ONLY from a key (`type` on the Keys card, the keyboard cap on the
 * terminal strip): a caret is sacred, and the keyboard rising on its own was
 * the old Input sheet's worst habit. `open` is that request; this raises the
 * IME once per rising edge and never on its own.
 */
@Composable
fun ImeSink(
    open: Boolean,
    enabled: Boolean,
    placeholder: String,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lx = LxTheme.current
    var pending by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(open, enabled) {
        if (!open || !enabled) return@LaunchedEffect
        // After the card's rise (160 ms) so the insets animation and the
        // slide do not fight — that fight made the keyboard fail to appear.
        kotlinx.coroutines.delay(200)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    BasicTextField(
        value = pending,
        onValueChange = { typed ->
            if (typed.isEmpty()) return@BasicTextField
            // The soft keyboard is another window, so the root touch probe
            // never sees typing; without this the mirror would pause while
            // you are actively using it.
            Interaction.touch()
            onText(typed)
            pending = ""
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = lx.u(0.5f), vertical = lx.u(0.5f))
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                // A field held empty never reports these as a text change.
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Backspace -> { Interaction.touch(); onBackspace(); true }
                    Key.Enter -> { Interaction.touch(); onEnter(); true }
                    else -> false
                }
            },
        singleLine = true,
        enabled = enabled,
        // Password stops Gboard composing whole words, which would otherwise
        // batch a word instead of delivering each key as you press it.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = TextStyle(fontFamily = lx.face, fontSize = lx.sp(Type.body), color = lx.roles.ink),
        cursorBrush = SolidColor(lx.roles.accent),
        decorationBox = { inner ->
            Box {
                if (pending.isEmpty()) LxText(placeholder, Type.caption, lx.ink(Alpha.placeholder), maxLines = 1)
                inner()
            }
        },
    )
}
