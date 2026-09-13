package com.lattice.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * The phone as the desktop's microphone. Records 16 kHz mono s16 (whisper's
 * native format, no resample on either side) and streams it over the link as
 * `{t:"dicta"}` frames of 100 ms; the desktop's `dictate` script transcribes
 * and types the result into the focused window.
 *
 * Started from the Quick Settings tile, the link notification's action or the
 * in-app button — all three call [toggle]. A second call stops and
 * transcribes; [cancel] throws the take away.
 */
class DictationService : LifecycleService() {

    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { finish(cancel = false); return START_NOT_STICKY }
            ACTION_CANCEL -> { finish(cancel = true); return START_NOT_STICKY }
        }
        if (job?.isActive == true) return START_NOT_STICKY

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no RECORD_AUDIO permission")
            Link.clearDict()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Link.ready) {
            Log.w(TAG, "desktop bridge not ready")
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        _active.value = true
        Link.dict("start")
        job = lifecycleScope.launch(Dispatchers.IO) { record() }
        return START_NOT_STICKY
    }

    private fun record() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, FRAME_BYTES * 4),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "AudioRecord refused: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }
        rec.startRecording()
        val buf = ByteArray(FRAME_BYTES)
        val started = System.currentTimeMillis()
        try {
            while (lifecycleScope.isActive && job?.isActive == true) {
                var got = 0
                while (got < FRAME_BYTES) {
                    val n = rec.read(buf, got, FRAME_BYTES - got)
                    if (n <= 0) return
                    got += n
                }
                levelFlow.value = rms(buf)
                Link.dictAudio(buf.copyOf())
                if (System.currentTimeMillis() - started > MAX_MS) {
                    Log.i(TAG, "take hit the length cap")
                    lifecycleScope.launch { finish(cancel = false) }
                    return
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            levelFlow.value = 0f
        }
    }

    private fun rms(b: ByteArray): Float {
        var acc = 0.0
        var i = 0
        while (i + 1 < b.size) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            acc += (s * s).toDouble()
            i += 2
        }
        return (sqrt(acc / (b.size / 2)) / 32768.0).toFloat()
    }

    private fun finish(cancel: Boolean) {
        val wasRecording = job?.isActive == true
        job?.cancel()
        job = null
        if (wasRecording) Link.dict(if (cancel) "cancel" else "stop")
        _active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        _active.value = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, DictationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, AudioCaptureService.CHANNEL_ID)
            .setContentTitle("Dictating to the desktop")
            .setContentText("Tap Done to transcribe and type it.")
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Done", stop).build())
            .addAction(Notification.Action.Builder(null, "Discard", cancel).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val TAG = "Dictation"
        const val NOTIF_ID = 44
        const val ACTION_START = "com.lattice.app.DICTATE_START"
        const val ACTION_STOP = "com.lattice.app.DICTATE_STOP"
        const val ACTION_CANCEL = "com.lattice.app.DICTATE_CANCEL"
        private const val RATE = 16_000
        /** 100 ms of 16 kHz mono s16. */
        private const val FRAME_BYTES = RATE / 10 * 2
        private const val MAX_MS = 300_000L

        private val _active = MutableStateFlow(false)
        /** True while the microphone is open. */
        val active: StateFlow<Boolean> = _active.asStateFlow()
        private val levelFlow = MutableStateFlow(0f)
        /** RMS of the last 100 ms block, 0..1, for the level meter. */
        val level: StateFlow<Float> = levelFlow.asStateFlow()

        /** Start a take, or stop and transcribe the one in flight. */
        fun toggle(context: Context) {
            val app = context.applicationContext
            if (_active.value) {
                app.startService(Intent(app, DictationService::class.java).setAction(ACTION_STOP))
            } else {
                app.startForegroundService(Intent(app, DictationService::class.java).setAction(ACTION_START))
            }
        }

        fun cancel(context: Context) {
            if (_active.value) {
                context.applicationContext.startService(
                    Intent(context.applicationContext, DictationService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }
}
