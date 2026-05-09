package com.snapcastsource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        intent ?: return stopAndReturn()

        startInForeground()

        val resultCode = intent.getIntExtra("resultCode", 0)
        val data: Intent? = intent.getParcelableExtra("data")
        val host = intent.getStringExtra("host")
        val port = intent.getIntExtra("port", 4953)

        if (data == null || host.isNullOrBlank()) {
            Log.e(TAG, "Missing projection data or host")
            return stopAndReturn()
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
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

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize)
            .build()
        audioRecord = record

        val tcp = TcpStreamer(host, port)
        streamer = tcp

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(bufSize)
            record.startRecording()
            tcp.connect()
            while (isActive) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) tcp.write(buf, n)
            }
        }
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun stopAndReturn(): Int {
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
    }
}
