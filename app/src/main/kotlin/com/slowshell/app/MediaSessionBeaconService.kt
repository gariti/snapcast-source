package com.slowshell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that sends a small UDP beacon ("media session is playing")
 * to the NixOS host whenever the phone has at least one active MediaSession.
 * Independent of AudioCaptureService — works for cast playback where audio
 * never touches the phone's audio path.
 *
 * Cadence:
 *   - 500 ms while at least one session is playing
 *   - 10 s idle heartbeat otherwise, but fires IMMEDIATELY on any media-state
 *     change (play/pause/session/listener) so the desktop never lags a change
 *
 * Reads host from MainActivity's SharedPreferences. Beacon port is fixed at 4902.
 */
class MediaSessionBeaconService : LifecycleService() {

    private var sink: MediaSessionUdpSink? = null
    private var nowPlayingSink: NowPlayingUdpSink? = null
    private var beaconJob: Job? = null
    private var commandListener: CommandUdpListener? = null
    private var commandJob: Job? = null
    private var host: String = ""

    override fun onCreate() {
        super.onCreate()
        host = readHostPref()
        // Ensure the notification channel exists. AudioCaptureService normally
        // creates it, but on the boot-start path (BootReceiver) that service has
        // never run, and startForeground() on a missing channel fails.
        ensureNotificationChannel()
    }

    private fun ensureNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(AudioCaptureService.CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    AudioCaptureService.CHANNEL_ID,
                    "SlowShell",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground()

        if (host.isBlank()) {
            Log.w(TAG, "No host configured; beacon idle")
            _serviceState.value = ServiceState.Idle("No host configured")
            return START_STICKY
        }

        if (sink == null) {
            try {
                sink = MediaSessionUdpSink(host, BEACON_PORT)
                nowPlayingSink = NowPlayingUdpSink(host, NOWPLAYING_PORT)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open UDP sink: ${e.message}")
                _serviceState.value = ServiceState.Failed("UDP open failed: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Reverse command channel (desktop -> phone): bind once, run for the
        // service's lifetime. Independent of host config (we listen on all ifaces).
        if (commandJob?.isActive != true) {
            // Only the configured desktop host may send us control commands.
            val listener = CommandUdpListener(COMMAND_PORT, host).also { commandListener = it }
            commandJob = lifecycleScope.launch(Dispatchers.IO) { listener.listen() }
        }

        if (beaconJob?.isActive != true) {
            beaconJob = lifecycleScope.launch(Dispatchers.IO) {
                _serviceState.value = ServiceState.Running(host, BEACON_PORT)
                var packetsSent = 0L
                var lastUiMs = 0L
                while (isActive) {
                    val s = MediaSessionListener.state.value
                    sink?.send(s.isPlaying, s.sessionCount)
                    nowPlayingSink?.send(s)
                    packetsSent++
                    // Only repaint the UI when something user-visible changed, or
                    // at most every ~3 s to refresh the packet counter — avoids a
                    // recomposition on every idle keepalive.
                    val current = _serviceState.value
                    val now = System.currentTimeMillis()
                    if (current is ServiceState.Running) {
                        val changed = current.lastIsPlaying != s.isPlaying ||
                            current.lastSessionCount != s.sessionCount ||
                            current.listenerConnected != s.listenerConnected
                        if (changed || now - lastUiMs >= UI_REFRESH_MS) {
                            lastUiMs = now
                            _serviceState.value = current.copy(
                                packetsSent = packetsSent,
                                lastIsPlaying = s.isPlaying,
                                lastSessionCount = s.sessionCount,
                                listenerConnected = s.listenerConnected,
                            )
                        }
                    }
                    // Playing: tight cadence for now-playing freshness. Idle: long
                    // heartbeat to let the radio sleep, BUT wake instantly on any
                    // media-state change so the desktop reacts the moment you hit
                    // play (no multi-second lag from a long idle sleep).
                    val interval = if (s.isPlaying) PLAYING_INTERVAL_MS else IDLE_INTERVAL_MS
                    withTimeoutOrNull(interval) {
                        MediaSessionListener.state.first { it != s }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        beaconJob?.cancel()
        beaconJob = null
        commandListener?.close()
        commandListener = null
        commandJob?.cancel()
        commandJob = null
        sink?.close()
        sink = null
        nowPlayingSink?.close()
        nowPlayingSink = null
        _serviceState.value = ServiceState.Idle("Stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun readHostPref(): String {
        // Mirror MainActivity's prefs constants — keep in sync.
        val prefs = getSharedPreferences("slowshell_app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("host", "") ?: ""
    }

    private fun startInForeground() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, AudioCaptureService.CHANNEL_ID)
            .setContentTitle("SlowShell")
            .setContentText("Cast playback detection active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    sealed class ServiceState {
        data class Idle(val reason: String) : ServiceState()
        data class Running(
            val host: String,
            val port: Int,
            val packetsSent: Long = 0L,
            val lastIsPlaying: Boolean = false,
            val lastSessionCount: Int = 0,
            val listenerConnected: Boolean = false,
        ) : ServiceState()
        data class Failed(val reason: String) : ServiceState()
    }

    companion object {
        private const val TAG = "MediaSessionBeacon"
        const val BEACON_PORT = 4902
        const val NOWPLAYING_PORT = 4903   // phone -> desktop, richer now-playing frame
        const val COMMAND_PORT = 4904      // desktop -> phone, control commands
        const val NOTIF_ID = 43

        private const val PLAYING_INTERVAL_MS = 500L
        // Idle heartbeat — long, since any real state change wakes the loop
        // immediately via the state-flow await. Keeps the radio asleep longer.
        private const val IDLE_INTERVAL_MS = 10_000L
        // Cap on how often an unchanged idle beacon repaints the packet counter.
        private const val UI_REFRESH_MS = 3_000L

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle("Not started"))
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, MediaSessionBeaconService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaSessionBeaconService::class.java))
        }
    }
}
