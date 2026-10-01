package com.lattice.app

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.lattice.app.lx.LxDialog
import com.lattice.app.lx.LxField
import com.lattice.app.lx.LxRow
import com.lattice.app.lx.LxSection
import com.lattice.app.lx.LxStatus
import com.lattice.app.lx.LxTheme
import com.lattice.app.lx.StatusTone
import kotlinx.coroutines.launch

/**
 * Pairing by code: ONE dialog, used over the first-run tile (frame A2) and
 * from More › Desktop › Type a code. One field; the keyboard rises only when
 * the field is tapped; `connect` (the band's primary, owned by the caller)
 * appears once the code is dirty. A desktop by name is the secondary row.
 */
class PairingState {
    var status by mutableStateOf<Pair<String, StatusTone>?>(null)
    var searching by mutableStateOf(false)
    var byAddress by mutableStateOf(false)

    /** The code is 16 base32 characters; four groups of four as typed. */
    fun complete(code: String) = PairingCrypto.normalize(code).length >= 16
}

@Composable
fun rememberPairingState() = remember { PairingState() }

/** mDNS + the HMAC challenge, or a straight verify of a typed address. */
fun PairingState.connect(ctx: Context, app: AppState, scope: kotlinx.coroutines.CoroutineScope, strings: PairingStrings) {
    searching = true
    status = strings.looking to StatusTone.Info
    scope.launch {
        val code = PairingCrypto.normalize(app.psk)
        if (code.isEmpty()) { status = strings.noDesktop to StatusTone.Error; searching = false; return@launch }
        var paired: String? = null
        if (byAddress && app.host.isNotBlank()) {
            if (PairingVerifier.verify(app.host, MainActivity.DEFAULT_VRFY_PORT, code)) paired = app.host
        } else {
            val candidates = DesktopDiscovery(ctx).discover()
            for (c in candidates) if (PairingVerifier.verify(c.host, c.vrfyPort, code)) { paired = c.host; break }
        }
        if (paired != null) {
            app.host = paired; app.onHostChange(paired)
            // Found on this network and the code proved it is the desktop: a
            // LAN candidate worth racing on every reconnect.
            if (!byAddress) LinkHosts.addLan(ctx, paired)
            // The beacon owns the control channel; bounce it onto the new pairing.
            if (MediaSessionListener.isAccessGranted(ctx)) { MediaSessionBeaconService.stop(ctx); MediaSessionBeaconService.start(ctx) }
            status = strings.paired.format(Names.host(paired)) to StatusTone.Ok
        } else {
            status = (if (byAddress) strings.didNotAnswer.format(Names.host(app.host)) else strings.noDesktop) to StatusTone.Error
        }
        searching = false
    }
}

class PairingStrings(val looking: String, val noDesktop: String, val paired: String, val didNotAnswer: String)

@Composable
fun rememberPairingStrings() = PairingStrings(
    stringResource(R.string.looking_for_desktop), stringResource(R.string.no_desktop_knows_code),
    stringResource(R.string.paired_with_x), stringResource(R.string.desktop_did_not_answer),
)

/** The dialog's body: the code field, its help, the secondary row (and the address once asked). */
@Composable
fun PairingDialog(app: AppState, state: PairingState, enabled: Boolean) {
    val lx = LxTheme.current
    LxDialog(title = stringResource(R.string.type_the_code_title), badge = stringResource(R.string.four_by_four)) {
        LxField(
            stringResource(R.string.code), app.psk,
            { app.psk = it; app.onPskChange(it) },
            enabled = enabled, keyboardType = KeyboardType.Ascii,
        )
        val (text, tone) = state.status ?: (stringResource(R.string.code_help) to StatusTone.Info)
        LxStatus(text, tone)
        Spacer(Modifier.height(lx.u(0.3f)))
        if (state.byAddress) {
            LxField(
                stringResource(R.string.address), app.host,
                { app.host = it; app.onHostChange(it) },
                stringResource(R.string.address_placeholder), enabled = enabled, keyboardType = KeyboardType.Uri,
            )
        } else {
            LxRow(stringResource(R.string.somewhere_else), stringResource(R.string.somewhere_else_meta), icon = Icons.Filled.Language, chevron = true) {
                state.byAddress = true
            }
        }
    }
}

/** The stock camera reads the desktop's QR and hands the lattice://pair link back to MainActivity. */
fun openScanner(ctx: Context) {
    runCatching { ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Suppress("unused")
private val keepSection = @Composable { LxSection("") }
