package com.lattice.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Listen to desktop": plays the `{t:"pcm"}` frames the bridge streams
 * (48 kHz s16 stereo, 20 ms each) through whatever the phone is routing audio
 * to — the headphones on your head. A ~300 ms AudioTrack buffer absorbs
 * tailnet jitter; frames the link dropped just leave a gap.
 */
class ListenService : LifecycleService() {

    private var pump: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (pump?.isActive == true) return START_NOT_STICKY
        if (!Link.ready) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        _active.value = true
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            maxOf(minBuf, BUFFER_BYTES), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.play()
        Link.listen(true)
        pump = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Link.pcm.collect { frame ->
                    var off = 0
                    while (off < frame.size) {
                        val n = track.write(frame, off, frame.size - off)
                        if (n < 0) { Log.w(TAG, "AudioTrack write $n"); return@collect }
                        off += n
                    }
                }
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }
        return START_NOT_STICKY
    }

    private fun stop() {
        pump?.cancel()
        pump = null
        Link.listen(false)
        _active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pump?.cancel()
        _active.value = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, AudioCaptureService.CHANNEL_ID)
            .setContentTitle("Listening to the desktop")
            .setContentText("Desktop audio is playing here.")
            .setSmallIcon(R.drawable.ic_stat_headphones)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "Listen"
        const val NOTIF_ID = 45
        const val ACTION_STOP = "com.lattice.app.LISTEN_STOP"
        private const val RATE = 48_000
        /** ~300 ms of 48 kHz stereo s16. */
        private const val BUFFER_BYTES = RATE * 4 * 3 / 10

        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        fun set(context: Context, on: Boolean) {
            val app = context.applicationContext
            val i = Intent(app, ListenService::class.java)
            if (on) app.startForegroundService(i) else app.startService(i.setAction(ACTION_STOP))
        }
    }
}
