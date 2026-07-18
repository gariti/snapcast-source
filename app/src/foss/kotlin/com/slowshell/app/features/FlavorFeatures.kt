package com.slowshell.app.features

/**
 * foss flavor: F-Droid-friendly. UnifiedPush planned for the deferred
 * messaging layer; restricted permissions (telephony / all-files) are allowed
 * to be REQUESTED here once that layer ships — nothing requests them today.
 */
object FlavorFeatures : FeatureSet {
    override val flavor = "foss"
    override val pushBackend = PushBackend.UNIFIED_PUSH
    override val mayRequestTelephonyPerms = true
    override val mayRequestAllFilesAccess = true
}
