package com.slowshell.app.features

import android.content.Context

/**
 * Per-flavor capability seam for the DEFERRED messaging layer (SMS /
 * notifications / KDE-Connect-style features). Nothing here is implemented
 * yet — this interface exists so the `foss` and `play` product flavors can
 * diverge on restricted-permission strategy WITHOUT touching the link code:
 *
 *   foss  -> UnifiedPush + may request restricted perms (telephony, all-files)
 *   play  -> FCM + never requests Play-restricted perms
 *
 * The custom link itself (control channel + spectrum + party PCM) is flavor-
 * INDEPENDENT and must never be gated on anything in this file.
 *
 * Each flavor source set provides `object FlavorFeatures : FeatureSet` in this
 * package; main-source code references [FlavorFeatures] directly and the
 * variant build picks the right one.
 */
enum class PushBackend {
    /** Planned for foss: self-hostable, no Google dependency. */
    UNIFIED_PUSH,

    /** Planned for play: Firebase Cloud Messaging. */
    FCM,
}

interface FeatureSet {
    /** Flavor name, for logging/diagnostics ("foss" / "play"). */
    val flavor: String

    /** Push transport this flavor WILL use once messaging ships. */
    val pushBackend: PushBackend

    /** May this build ask for SMS/telephony permissions? (Play policy: no.) */
    val mayRequestTelephonyPerms: Boolean

    /** May this build ask for MANAGE_EXTERNAL_STORAGE? (Play policy: no.) */
    val mayRequestAllFilesAccess: Boolean

    /**
     * TODO(messaging): initialize the flavor's push transport and register the
     * endpoint over the control channel (a future "push-endpoint" message —
     * the hello/caps handshake already lets the desktop discover support).
     * Deliberately a no-op until the messaging layer lands.
     */
    fun initPush(context: Context) {}
}
