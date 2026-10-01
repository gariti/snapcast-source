package com.lattice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lattice.app.lx.Alpha
import com.lattice.app.lx.ButtonKind
import com.lattice.app.lx.Hint
import com.lattice.app.lx.LxButton
import com.lattice.app.lx.LxCaption
import com.lattice.app.lx.LxCard
import com.lattice.app.lx.LxHints
import com.lattice.app.lx.LxStage
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxText
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.LxWordmark
import com.lattice.app.lx.StatusTone
import com.lattice.app.lx.Type
import com.lattice.app.lx.rememberArm
import kotlinx.coroutines.delay

/**
 * The approval screen for one desktop challenge: a card. The question in
 * plain words, the raw action under it in the caption role, the fingerprint,
 * and two answers — Deny (danger, arms first) in the band and the sensor.
 *
 * A FragmentActivity because BiometricPrompt needs one; deliberately NOT
 * MainActivity so the pairing intent flow there stays untouched. Shows over
 * the lock screen, never in recents, and closes itself when the challenge is
 * cancelled, expires or is answered elsewhere.
 *
 * The BiometricPrompt opens the moment the screen does — one tap is the whole
 * gesture. Its CryptoObject is the key's Signature; the unlocked Signature
 * signs the challenge payload → `authr`. There is no code path that signs
 * without it. Backing out of the prompt lands on the card for a second try.
 */
class AuthPromptActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        setContent {
            LxTheme {
                PromptScreen(id)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second challenge while this one is up: restart on the new id.
        setIntent(intent)
        recreate()
    }

    @Composable
    private fun PromptScreen(id: String) {
        val lx = LxTheme.current
        val pending by DesktopAuth.pending.collectAsState()
        val c = pending[id]
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var remaining by remember { mutableStateOf(c?.remaining ?: 0L) }
        var autoStarted by remember { mutableStateOf(false) }
        val denyArm = rememberArm()

        // The tap on the notification IS the approve gesture: go straight to
        // the fingerprint. A cancelled prompt falls back to the card.
        LaunchedEffect(c?.id) {
            if (c != null && !autoStarted) {
                autoStarted = true
                busy = true
                approveWithBiometric(c, onError = { error = it; busy = false })
            }
        }
        LaunchedEffect(c) { if (c == null) finish() }
        LaunchedEffect(id) {
            while (true) {
                remaining = DesktopAuth.pending.value[id]?.remaining ?: 0L
                if (remaining <= 0L) { finish(); break }
                delay(1000)
            }
        }
        if (c == null) return

        val host = Names.host(c.host)
        val question = when (c.kind) {
            "unlock" -> stringResource(R.string.unlock_q, host)
            "sudo" -> stringResource(R.string.sudo_q, host)
            "test" -> stringResource(R.string.test_q, host)
            else -> stringResource(R.string.polkit_q, c.message.ifBlank { c.action })
        }

        LxStage(Modifier.windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
            LxCard(
                modifier = Modifier.weight(1f),
                active = true,
                top = {
                    Column(Modifier.weight(1f)) {
                        LxWordmark(stringResource(R.string.asks, host))
                        LxCaption(stringResource(R.string.for_fingerprint, remaining))
                    }
                },
                tile = {
                    Spacer(Modifier.weight(1f))
                    LxText(question, Type.display)
                    Spacer(Modifier.size(lx.u(0.8f)))
                    LxText(
                        listOfNotNull(
                            c.action.takeIf { it.isNotBlank() && it != c.message },
                            if (c.caller.isNotBlank()) stringResource(R.string.asked_by, c.caller, c.user) else stringResource(R.string.for_user, c.user),
                        ).joinToString("\n"),
                        Type.caption, lx.ink(Alpha.secondary),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            busy = true; error = null
                            approveWithBiometric(c, onError = { error = it; busy = false })
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(lx.u(0.4f)),
                    ) {
                        Icon(Icons.Filled.Fingerprint, stringResource(R.string.approve), Modifier.size(lx.u(5.5f)), tint = lx.roles.accent)
                        LxText(stringResource(R.string.touch_to_approve), Type.caption, lx.ink(Alpha.secondary))
                    }
                    Spacer(Modifier.weight(1f))
                    error?.takeIf { it.isNotBlank() }?.let { LxStatus(it, StatusTone.Error) }
                },
                bottom = {
                    LxButton("?", enabled = false) {}
                    LxHints(
                        if (denyArm.armed) listOf(Hint(stringResource(R.string.hint_tap), stringResource(R.string.tap_again_to_deny)))
                        else listOf(Hint("touch", stringResource(R.string.hint_touch_approves)), Hint("◀", stringResource(R.string.hint_back_leaves)))
                    )
                    LxButton(stringResource(R.string.deny), ButtonKind.Danger, armed = denyArm.armed, enabled = !busy) {
                        if (denyArm.press()) { DesktopAuth.deny(this@AuthPromptActivity, id); finish() }
                    }
                },
            )
        }
    }

    private fun approveWithBiometric(c: DesktopAuth.Challenge, onError: (String) -> Unit) {
        val signer = try {
            DesktopAuthKey.signer()
        } catch (e: KeyPermanentlyInvalidatedException) {
            DesktopAuth.keyInvalidated(this)
            onError(getString(R.string.auth_err_invalidated))
            return
        } catch (e: Exception) {
            onError(getString(R.string.auth_err_nokey, e.message ?: ""))
            return
        }
        val payload = DesktopAuth.payload(c)
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val sig = result.cryptoObject?.signature
                    if (sig == null) {
                        onError(getString(R.string.auth_err_sign, "no crypto object"))
                        return
                    }
                    try {
                        sig.update(payload)
                        val der = sig.sign()
                        DesktopAuth.approve(this@AuthPromptActivity, c.id, der)
                        finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "sign failed", e)
                        onError(getString(R.string.auth_err_sign, e.message ?: ""))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancel / back / the negative button: not a deny, just
                    // back to the card with its two answers.
                    val userBackedOut = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    onError(if (userBackedOut) "" else errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // A non-matching finger; the prompt stays up and retries.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(c.title)
            .setSubtitle(c.action)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel))
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signer))
    }

    companion object {
        private const val TAG = "AuthPrompt"
        const val EXTRA_ID = "challenge_id"

        fun intent(context: Context, id: String): Intent =
            Intent(context, AuthPromptActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Suppress("unused")
private val keepWeight = FontWeight.Light
