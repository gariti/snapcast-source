package com.slowshell.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NotificationListenerService that observes all active MediaSessions on the
 * device — including ones playing to remote cast targets (KEF, Chromecast,
 * AirPlay). Used to drive the "music is playing on the phone" beacon when the
 * audio is not flowing through the phone's audio output (so
 * AudioPlaybackCaptureConfiguration would return silence).
 *
 * NOT a full notification interceptor — we only override the lifecycle hooks
 * to fetch a MediaSessionManager bound to our ComponentName (which the system
 * grants us when the user enables Notification Access).
 */
class MediaSessionListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateControllerCallbacks(controllers ?: emptyList())
            publishState(controllers ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = msm
        val component = ComponentName(this, MediaSessionListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            val initial = msm.getActiveSessions(component)
            updateControllerCallbacks(initial)
            publishState(initial)
            Log.i(TAG, "MediaSessionListener connected — ${initial.size} active session(s)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification Access not granted: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        clearControllerCallbacks()
        sessionManager = null
        activeController = null
        _state.value = State(isPlaying = false, sessionCount = 0, listenerConnected = false)
        super.onListenerDisconnected()
    }

    private fun updateControllerCallbacks(controllers: List<MediaController>) {
        // Unregister callbacks for controllers that disappeared.
        val current = controllers.toSet()
        val gone = controllerCallbacks.keys.filter { it !in current }
        for (c in gone) {
            controllerCallbacks.remove(c)?.let { c.unregisterCallback(it) }
        }
        // Register callbacks for new controllers.
        for (c in controllers) {
            if (controllerCallbacks.containsKey(c)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    // Re-derive from the live set — single source of truth.
                    publishState(sessionManager?.getActiveSessions(
                        ComponentName(this@MediaSessionListener, MediaSessionListener::class.java)
                    ) ?: emptyList())
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    // Seamless track changes within a playlist keep the state at
                    // PLAYING and only swap metadata, so onPlaybackStateChanged
                    // may never fire — without this the desktop sticks on the
                    // previous title/artist/art. Re-publish on every metadata swap.
                    publishState(sessionManager?.getActiveSessions(
                        ComponentName(this@MediaSessionListener, MediaSessionListener::class.java)
                    ) ?: emptyList())
                }

                override fun onSessionDestroyed() {
                    controllerCallbacks.remove(c)
                    publishState(sessionManager?.getActiveSessions(
                        ComponentName(this@MediaSessionListener, MediaSessionListener::class.java)
                    ) ?: emptyList())
                }
            }
            c.registerCallback(cb)
            controllerCallbacks[c] = cb
        }
    }

    private fun clearControllerCallbacks() {
        for ((controller, cb) in controllerCallbacks) {
            controller.unregisterCallback(cb)
        }
        controllerCallbacks.clear()
    }

    private fun publishState(controllers: List<MediaController>) {
        val playing = controllers.any { c ->
            c.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        // Pick the controller that best represents "what's playing": prefer a
        // PLAYING session, else the first one. This is the command target and
        // the source of now-playing metadata/volume.
        val active = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        activeController = active

        val md = active?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val app = active?.packageName ?: ""

        // Album-art URI. Streaming apps (Spotify, YT Music, Tidal…) expose an
        // https:// CDN URL here that the desktop can load directly for full-res
        // cover art; local/offline sources give a content:// URI the desktop
        // can't resolve, so the desktop side only uses http(s) values.
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: ""

        // Volume: a remote (cast) session reports its target's volume here, so
        // setVolumeTo() on this controller adjusts the KEF/Chromecast volume.
        val pi = active?.playbackInfo
        val maxVol = pi?.maxVolume ?: 0
        val curVol = pi?.currentVolume ?: 0
        val isRemote = pi?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        // VolumeProvider.VOLUME_CONTROL_FIXED == 0 means volume can't be changed.
        val controllable = pi != null && maxVol > 0 &&
            pi.volumeControl != android.media.VolumeProvider.VOLUME_CONTROL_FIXED
        val percent = if (maxVol > 0) ((curVol * 100) / maxVol).coerceIn(0, 100) else 0

        _state.value = State(
            isPlaying = playing,
            sessionCount = controllers.size,
            listenerConnected = true,
            title = title,
            artist = artist,
            app = app,
            artUri = artUri,
            volumePercent = percent,
            volumeMax = maxVol,
            volumeControllable = controllable,
            volumeRemote = isRemote,
        )
    }

    data class State(
        val isPlaying: Boolean,
        val sessionCount: Int,
        val listenerConnected: Boolean,
        val title: String = "",
        val artist: String = "",
        val app: String = "",
        val artUri: String = "",
        val volumePercent: Int = 0,
        val volumeMax: Int = 0,
        val volumeControllable: Boolean = false,
        val volumeRemote: Boolean = false,
    )

    companion object {
        private const val TAG = "MediaSessionListener"

        // Reverse-command opcodes (must match phone-command sender + QML).
        const val CMD_SET_VOLUME = 1
        const val CMD_PLAY = 2
        const val CMD_PAUSE = 3
        const val CMD_PLAY_PAUSE = 4
        const val CMD_NEXT = 5
        const val CMD_PREV = 6

        // The controller representing current playback — the command target.
        // Updated on every publishState; cleared on disconnect.
        @Volatile
        private var activeController: MediaController? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Apply a desktop-originated command to the active MediaController.
         * setVolumeTo() on a remote (cast) session adjusts the cast target's
         * volume, which is exactly the KEF/Chromecast case. Posted to the main
         * looper since transport/volume calls expect a Looper thread.
         */
        fun applyCommand(cmd: Int, arg: Int) {
            val c = activeController ?: run {
                Log.w(TAG, "command $cmd ignored — no active controller")
                return
            }
            mainHandler.post {
                try {
                    when (cmd) {
                        CMD_SET_VOLUME -> {
                            val pi = c.playbackInfo
                            val max = pi?.maxVolume ?: 0
                            if (max > 0) {
                                // round(arg% * max)
                                val target = (arg.coerceIn(0, 100) * max + 50) / 100
                                c.setVolumeTo(target, 0)
                            }
                        }
                        CMD_PLAY -> c.transportControls.play()
                        CMD_PAUSE -> c.transportControls.pause()
                        CMD_PLAY_PAUSE ->
                            if (c.playbackState?.state == PlaybackState.STATE_PLAYING)
                                c.transportControls.pause()
                            else c.transportControls.play()
                        CMD_NEXT -> c.transportControls.skipToNext()
                        CMD_PREV -> c.transportControls.skipToPrevious()
                        else -> Log.w(TAG, "unknown command opcode $cmd")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "applyCommand($cmd) failed: ${e.message}")
                }
            }
        }

        private val _state = MutableStateFlow(
            State(isPlaying = false, sessionCount = 0, listenerConnected = false)
        )
        val state: StateFlow<State> = _state.asStateFlow()

        /** Whether the user has granted Notification Access for this package. */
        fun isAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(context, MediaSessionListener::class.java).flattenToString()
            return flat.split(":").any { it == expected }
        }
    }
}
