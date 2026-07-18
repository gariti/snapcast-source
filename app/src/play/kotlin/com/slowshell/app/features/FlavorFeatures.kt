package com.slowshell.app.features

/**
 * play flavor: Google Play policy compliant. FCM planned for the deferred
 * messaging layer; Play-restricted permissions are never requested — features
 * needing them stay disabled in this flavor.
 */
object FlavorFeatures : FeatureSet {
    override val flavor = "play"
    override val pushBackend = PushBackend.FCM
    override val mayRequestTelephonyPerms = false
    override val mayRequestAllFilesAccess = false
}
