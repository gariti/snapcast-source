package com.lattice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay

/**
 * The approval screen for one desktop challenge. A FragmentActivity because
 * BiometricPrompt needs one; deliberately NOT MainActivity so the pairing
 * intent flow there stays untouched. Shows over the lock screen (the desktop
 * is often asking exactly when the phone is on the nightstand), never in
 * recents, and closes itself when the challenge is cancelled or expires.
 *
 * Approve → BiometricPrompt with the key's Signature as CryptoObject → the
 * unlocked Signature signs the challenge payload → `authr`. The signature can
 * only exist if the prompt succeeded; there is no code path that signs
 * without it.
 */
class AuthPromptActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        setContent {
            LatticeTheme {
                Surface(Modifier.fillMaxSize()) {
                    PromptScreen(id)
                }
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
        val pending by DesktopAuth.pending.collectAsState()
        val c = pending[id]
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var remaining by remember { mutableStateOf(c?.remaining ?: 0L) }

        // Cancelled / expired / answered elsewhere → gone.
        LaunchedEffect(c) {
            if (c == null) finish()
        }
        LaunchedEffect(id) {
            while (true) {
                remaining = DesktopAuth.pending.value[id]?.remaining ?: 0L
                if (remaining <= 0L) { finish(); break }
                delay(1000)
            }
        }
        if (c == null) return

        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
                Text(c.title, style = MaterialTheme.typography.headlineSmall)
                Text(c.action, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                if (c.caller.isNotBlank()) Text("from ${c.caller}", style = MaterialTheme.typography.bodyMedium)
                if (c.message.isNotBlank()) Text(c.message, style = MaterialTheme.typography.bodyMedium)
                Text("as ${c.user} · expires in ${remaining}s", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { DesktopAuth.deny(this@AuthPromptActivity, id); finish() }, enabled = !busy) {
                        Text("Deny")
                    }
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            approveWithBiometric(c, onError = { error = it; busy = false })
                        },
                        enabled = !busy,
                    ) { Text("Approve with fingerprint") }
                }
            }
        }
    }

    private fun approveWithBiometric(c: DesktopAuth.Challenge, onError: (String) -> Unit) {
        val signer = try {
            DesktopAuthKey.signer()
        } catch (e: KeyPermanentlyInvalidatedException) {
            DesktopAuth.keyInvalidated(this)
            onError("a fingerprint was added on the phone — enrol again from Settings")
            return
        } catch (e: Exception) {
            onError("no desktop-auth key: ${e.message}")
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
                        onError("prompt returned no crypto object")
                        return
                    }
                    try {
                        sig.update(payload)
                        val der = sig.sign()
                        DesktopAuth.approve(this@AuthPromptActivity, c.id, der)
                        finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "sign failed", e)
                        onError("signing failed: ${e.message}")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
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
            .setNegativeButtonText("Cancel")
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
