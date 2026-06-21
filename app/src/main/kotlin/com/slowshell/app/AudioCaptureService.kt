package com.slowshell.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioCaptureService : LifecycleService() {

    enum class Mode { PARTY, SOLO }

    private var audioRecord: AudioRecord? = null
    private var streamer: TcpStreamer? = null
    private var spectrumSink: SpectrumUdpSink? = null
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
        val mode = if (intent.getStringExtra("mode") == "solo") Mode.SOLO else Mode.PARTY

        if (data == null || host.isNullOrBlank()) {
            return stopAndReturn("Missing projection data or host")
        }

        // A second start (tapping Start again, or switching slot/mode without
        // stopping first) must NOT leave the previous capture loop and its TCP
        // socket alive. Two sockets on one mixer slot make the desktop
        // abort-and-replace them in a loop, which the phone sees as endless
        // connect/disconnect — and burns double the CPU/radio. Tear the prior
        // session fully down before building the new one.
        teardownCapture()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    publishState(ConnectionState.Failed("Screen capture permission revoked"))
                    stopSelf()
                }
            }, null)
        }

        startCapture(projection!!, mode, host, port)
        return START_NOT_STICKY
    }

    private fun startCapture(projection: MediaProjection, mode: Mode, host: String, port: Int) {
        _paused.value = false  // clear any stale pause from a previous session
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

        val audioManager = getSystemService(AudioManager::class.java)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        when (mode) {
            Mode.PARTY -> startPartyCapture(record, audioManager, maxVol, host, port, bufSize)
            Mode.SOLO -> startSoloCapture(record, host, port, bufSize)
        }
    }

    private fun startPartyCapture(
        record: AudioRecord,
        audioManager: AudioManager,
        maxVol: Int,
        host: String,
        port: Int,
        bufSize: Int,
    ) {
        val tcp = TcpStreamer(host, port, lifecycleScope)
        streamer = tcp

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            launch { tcp.state.collect { _state.value = it } }

            val buf = ByteArray(bufSize)
            record.startRecording()
            tcp.connect()
            var lastLoudMs = System.currentTimeMillis()
            while (isActive) {
                if (paused.value) {
                    // Desktop has no consumer — stop capturing until it resumes.
                    runCatching { record.stop() }
                    Log.i(TAG, "Party capture paused by desktop")
                    paused.first { !it }  // suspends; coroutine cancellation breaks out
                    runCatching { record.startRecording() }
                    lastLoudMs = System.currentTimeMillis()  // don't trip silence-stop on resume
                    Log.i(TAG, "Party capture resumed")
                    continue
                }
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    val gain = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                    applyGain(buf, n, gain)
                    val rms = computeRms(buf, n)
                    tcp.write(buf, n, rms)
                    if (rms > PARTY_SILENCE_RMS) {
                        lastLoudMs = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - lastLoudMs > IDLE_TIMEOUT_MS) {
                        Log.i(TAG, "Auto-stopping party capture: silent for >${IDLE_TIMEOUT_MS / 60000} min")
                        stopSelf()
                        break
                    }
                } else if (n < 0) {
                    // Negative = AudioRecord error (dead object / invalid op / stale
                    // projection). read() returns these IMMEDIATELY, so without a
                    // bail-out this loop busy-spins a core at 100% forever and the
                    // foreground service keeps it alive. Stop instead of spinning.
                    Log.e(TAG, "AudioRecord.read error $n; stopping party capture")
                    publishState(ConnectionState.Failed("Audio capture error ($n)"))
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun startSoloCapture(
        record: AudioRecord,
        host: String,
        port: Int,
        bufSize: Int,
    ) {
        val sink = SpectrumUdpSink(host, port)
        spectrumSink = sink
        val pipeline = SpectrumPipeline(sampleRateHz = SAMPLE_RATE)

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            _state.value = ConnectionState.Connecting(host, port)
            val buf = ByteArray(bufSize)
            record.startRecording()
            // No connection handshake on UDP; flip to Connected so UI reflects intent.
            _state.value = ConnectionState.Connected(host, port, 0L, 0f)
            var packetsSent = 0L
            var lastLoudMs = System.currentTimeMillis()
            var lastUiMs = 0L
            while (isActive) {
                if (paused.value) {
                    // Desktop has no consumer — stop capturing until it resumes.
                    runCatching { record.stop() }
                    Log.i(TAG, "Solo capture paused by desktop")
                    paused.first { !it }  // suspends; coroutine cancellation breaks out
                    runCatching { record.startRecording() }
                    lastLoudMs = System.currentTimeMillis()
                    Log.i(TAG, "Solo capture resumed")
                    continue
                }
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    val frame = pipeline.pushPcm(buf, n)
                    if (frame != null) {
                        sink.send(frame)
                        packetsSent++
                        // Throttle UI state to ~1 Hz — frames arrive ~30 Hz and
                        // each copy would otherwise recompose the status screen.
                        val now = System.currentTimeMillis()
                        val current = _state.value
                        if (current is ConnectionState.Connected && now - lastUiMs >= UI_UPDATE_MS) {
                            lastUiMs = now
                            _state.value = current.copy(
                                bytesSent = packetsSent * SpectrumUdpSink.FRAME_SIZE,
                                rms = frame.level / 100f,
                            )
                        }
                        if (frame.level > SOLO_SILENCE_LEVEL) {
                            lastLoudMs = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastLoudMs > IDLE_TIMEOUT_MS) {
                            Log.i(TAG, "Auto-stopping solo capture: silent for >${IDLE_TIMEOUT_MS / 60000} min")
                            stopSelf()
                            break
                        }
                    }
                } else if (n < 0) {
                    // Negative = AudioRecord error; read() returns immediately, so
                    // bail out instead of busy-spinning a core (see party loop).
                    Log.e(TAG, "AudioRecord.read error $n; stopping solo capture")
                    publishState(ConnectionState.Failed("Audio capture error ($n)"))
                    stopSelf()
                    break
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
        teardownCapture()
        if (_state.value !is ConnectionState.Failed) {
            _state.value = ConnectionState.Idle
        }
        super.onDestroy()
    }

    /**
     * Fully stop the current capture session: cancel the capture loop, close the
     * TCP/UDP sink, release the AudioRecord, and stop the MediaProjection. Safe
     * to call when nothing is running (all fields already null). Leaves [_state]
     * untouched so the caller decides the resulting state (Idle on destroy, or
     * the new Connecting state when restarting).
     */
    private fun teardownCapture() {
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        streamer?.stop()
        streamer = null
        spectrumSink?.close()
        spectrumSink = null
        projection?.stop()
        projection = null
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
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SlowShell")
            .setContentText("Streaming system audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
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
            "SlowShell",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val SAMPLE_RATE = 48000
        // Throttle interval for high-frequency UI telemetry (bytesSent/rms).
        private const val UI_UPDATE_MS = 1_000L
        const val NOTIF_ID = 42
        const val CHANNEL_ID = "slowshell_app"

        // Auto-stop the capture after this much continuous silence so the
        // laptop's SleepInhibitor can release (and the phone stops draining
        // battery / network bandwidth on silent UDP frames).
        private const val IDLE_TIMEOUT_MS = 5L * 60 * 1000  // 5 minutes
        // SpectrumPipeline.Frame.level is 0..100 (mean of bands); even quiet
        // music registers >1, true silence is 0.
        private const val SOLO_SILENCE_LEVEL = 1
        // computeRms returns a 0..1 float; silence is 0, line noise <0.001.
        private const val PARTY_SILENCE_RMS = 0.005f

        // Service-singleton state flow so MainActivity can observe even after
        // process recreation. Service is the producer; nobody else writes here.
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val state: StateFlow<ConnectionState> = _state.asStateFlow()

        // Consumer-gated streaming: the desktop sends a pause/resume capture
        // command (CommandUdpListener cmd 7/8) when its visualizer is/ isn't
        // on-screen. While paused, the capture loop stops AudioRecord — no
        // MediaProjection read, no FFT, no transmit — but keeps the service and
        // socket alive so resume is instant. Saves phone battery when nothing
        // on the desktop is actually displaying the stream.
        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused.asStateFlow()
        fun setPaused(p: Boolean) { _paused.value = p }
    }
}
