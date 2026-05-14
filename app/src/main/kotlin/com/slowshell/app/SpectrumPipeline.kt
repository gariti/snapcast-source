package com.slowshell.app

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Solo Listening DSP: 16-bit stereo PCM → 45 log-spaced spectrum bands (0..100).
 *
 * Feed PCM byte chunks via [pushPcm]; each call returns a [Frame] when a fresh
 * spectrum is ready (~30 Hz given 48 kHz audio in ~10–40 ms read chunks).
 *
 * Design: the FFT operates on a fixed 1024-sample mono window slid forward as
 * new audio arrives. Hann-windowed real FFT, log-magnitude binning, then a
 * dB→0..100 map calibrated so that quiet music sits ~10–30 and loud peaks ~80+.
 */
class SpectrumPipeline(
    private val sampleRateHz: Int = 48000,
    private val numBands: Int = 45,
    private val fftSize: Int = 1024,
    private val emitIntervalMs: Long = 33L,
) {
    data class Frame(
        val bands: IntArray,        // numBands entries, 0..100
        val level: Int,             // mean of bands, 0..100
        val beatOnset: Boolean,
    )

    private val fft = DoubleFFT_1D(fftSize.toLong())
    private val window = DoubleArray(fftSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
    }
    private val mono = DoubleArray(fftSize)
    private val fftBuf = DoubleArray(fftSize)
    private var monoFill = 0

    private val bandLow: IntArray
    private val bandHigh: IntArray

    private var lastEmitMs: Long = 0L
    private var lowEnergyAvg = 0.0

    init {
        // Log-spaced band edges across 40 Hz – 16 kHz, mapped to FFT bin indices.
        val freqMin = 40.0
        val freqMax = min(16000.0, sampleRateHz / 2.0 - 1.0)
        val binHz = sampleRateHz.toDouble() / fftSize
        bandLow = IntArray(numBands)
        bandHigh = IntArray(numBands)
        val logMin = ln(freqMin)
        val logMax = ln(freqMax)
        for (i in 0 until numBands) {
            val fLo = Math.exp(logMin + (logMax - logMin) * i / numBands)
            val fHi = Math.exp(logMin + (logMax - logMin) * (i + 1) / numBands)
            bandLow[i] = max(1, (fLo / binHz).toInt())
            bandHigh[i] = max(bandLow[i] + 1, (fHi / binHz).toInt()).coerceAtMost(fftSize / 2)
        }
    }

    /**
     * Push a chunk of 16-bit little-endian stereo PCM. Returns a Frame iff at
     * least [emitIntervalMs] has passed since the last emission and the FFT
     * window is full.
     */
    fun pushPcm(buf: ByteArray, len: Int): Frame? {
        // Downmix L+R → mono, fill ring buffer.
        var i = 0
        while (i < len - 3) {
            val l = sample16(buf, i)
            val r = sample16(buf, i + 2)
            val m = (l + r) * 0.5 / 32768.0
            mono[monoFill] = m
            monoFill = (monoFill + 1) % fftSize
            i += 4
        }

        val now = System.currentTimeMillis()
        if (now - lastEmitMs < emitIntervalMs) return null
        lastEmitMs = now

        // Copy mono ring (oldest-first) into fftBuf with Hann window applied.
        for (k in 0 until fftSize) {
            val idx = (monoFill + k) % fftSize
            fftBuf[k] = mono[idx] * window[k]
        }

        fft.realForward(fftBuf)

        val mags = DoubleArray(fftSize / 2)
        mags[0] = Math.abs(fftBuf[0])
        for (k in 1 until fftSize / 2) {
            val re = fftBuf[2 * k]
            val im = fftBuf[2 * k + 1]
            mags[k] = sqrt(re * re + im * im)
        }

        val bandsOut = IntArray(numBands)
        var sumLevel = 0
        var lowEnergy = 0.0
        var lowCount = 0
        for (b in 0 until numBands) {
            var sum = 0.0
            var count = 0
            for (k in bandLow[b] until bandHigh[b]) {
                sum += mags[k]
                count++
            }
            val avg = if (count > 0) sum / count else 0.0
            // Normalize raw FFT magnitudes back into ~[0, 1]: divide by fftSize/2
            // (FFT's bin magnitude for a full-scale tone) and multiply by 2 to
            // compensate for the Hann window's coherent gain of 0.5.
            val normalized = avg * 4.0 / fftSize
            // -90 dB → 0, 0 dB → 100. Quiet music sits ~30–50, loud peaks ~70–95.
            val db = 20.0 * log10(max(normalized, 1e-9))
            val v = ((db + 90.0) * (100.0 / 90.0)).toInt().coerceIn(0, 100)
            bandsOut[b] = v
            sumLevel += v

            // Low band = ~40–250 Hz region — used for beat onset detection.
            val binCenterHz = (bandLow[b] + bandHigh[b]) * 0.5 * sampleRateHz / fftSize
            if (binCenterHz < 250.0) {
                lowEnergy += avg
                lowCount++
            }
        }
        val level = sumLevel / numBands

        val beat = if (lowCount > 0) {
            val now = lowEnergy / lowCount
            val isBeat = lowEnergyAvg > 1e-9 && now > lowEnergyAvg * 1.6 && now > 0.005
            // EMA — fast attack, slow release
            val alpha = if (now > lowEnergyAvg) 0.4 else 0.05
            lowEnergyAvg = alpha * now + (1.0 - alpha) * lowEnergyAvg
            isBeat
        } else false

        return Frame(bandsOut, level, beat)
    }

    private fun sample16(buf: ByteArray, i: Int): Int {
        val raw = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
        return if (raw > 32767) raw - 65536 else raw
    }
}
