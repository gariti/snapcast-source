package com.snapcastsource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioCaptureService : LifecycleService() {

    private var audioRecord: AudioRecord? = null
    private var streamer: TcpStreamer? = null
    private var projection: MediaProjection? = null
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent ?: return stopAndReturn("Missing intent")

        startInForeground()

        val resultCode = intent.getIntExtra("resultCode", 0)
        val data: Intent? = intent.getParcelableExtra("data")
        val host = intent.getStringExtra("host")
        val port = intent.getIntExtra("port", 4953)

        if (data == null || host.isNullOrBlank()) {
            return stopAndReturn("Missing projection data or host")
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    publishState(ConnectionState.Failed("Screen capture permission revoked"))
                    stopSelf()
                }
            }, null)
        }

        startCapture(projection!!, host, port)
        return START_NOT_STICKY
    }

    private fun startCapture(projection: MediaProjection, host: String, port: Int) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBuf * 2).coerceAtLeast(8192)

        val record = try {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (e: Exception) {
            publishState(ConnectionState.Failed("AudioRecord init failed: ${e.message}"))
            stopSelf()
            return
        }
        audioRecord = record

        val tcp = TcpStreamer(host, port)
        streamer = tcp

        val audioManager = getSystemService(AudioManager::class.java)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        // Bridge TcpStreamer.state → service-level state flow
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            launch { tcp.state.collect { _state.value = it } }

            val buf = ByteArray(bufSize)
            record.startRecording()
            tcp.connect()
            while (isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    val gain = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                    applyGain(buf, n, gain)
                    val rms = computeRms(buf, n)
                    tcp.write(buf, n, rms)
                }
            }
        }
    }

    private fun applyGain(buf: ByteArray, len: Int, gain: Float) {
        if (gain >= 0.999f) return
        if (gain <= 0f) {
            for (i in 0 until len) buf[i] = 0
            return
        }
        var i = 0
        while (i < len - 1) {
            val raw = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val signed = if (raw > 32767) raw - 65536 else raw
            val scaled = (signed * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun computeRms(buf: ByteArray, len: Int): Float {
        // 16-bit little-endian PCM stereo. Normalize to 0..1.
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i < len - 1) {
            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sumSq += signed.toDouble() * signed.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun publishState(s: ConnectionState) {
        _state.value = s
    }

    override fun onDestroy() {
        captureJob?.cancel()
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        streamer?.close()
        streamer = null
        projection?.stop()
        projection = null
        if (_state.value !is ConnectionState.Failed) {
            _state.value = ConnectionState.Idle
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun stopAndReturn(reason: String): Int {
        publishState(ConnectionState.Failed(reason))
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Snapcast Source")
            .setContentText("Streaming system audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID,
            "Snapcast Source",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val SAMPLE_RATE = 48000
        const val NOTIF_ID = 42
        const val CHANNEL_ID = "snapcast_source"

        // Service-singleton state flow so MainActivity can observe even after
        // process recreation. Service is the producer; nobody else writes here.
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val state: StateFlow<ConnectionState> = _state.asStateFlow()
    }
}
