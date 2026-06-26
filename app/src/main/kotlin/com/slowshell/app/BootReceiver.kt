package com.slowshell.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the media-session beacon back up after a reboot so background / cast
 * playback is detected without the user opening the app.
 *
 * Why this exists: MediaSessionBeaconService was previously only started from
 * MainActivity.onResume(), so after a reboot (or the OS killing the app) the
 * desktop never saw "music is playing" until the app was foregrounded — the
 * whole light show (riri colour wash + CircleWave rings, both gated on
 * PhoneSpectrumService.mediaSessionPlaying) stayed dark until then.
 *
 * Only starts the beacon when it can actually do something: a host is configured
 * and Notification Access is granted (the beacon reads media state from the
 * system-bound MediaSessionListener, which is useless without that grant).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        // Mirror MainActivity's prefs constants — keep in sync.
        val prefs = context.getSharedPreferences("slowshell_app_prefs", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""

        if (host.isBlank()) {
            Log.i(TAG, "Boot: no host configured, beacon not started")
            return
        }
        if (!MediaSessionListener.isAccessGranted(context)) {
            Log.i(TAG, "Boot: Notification Access not granted, beacon not started")
            return
        }

        Log.i(TAG, "Boot: starting media-session beacon")
        MediaSessionBeaconService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
