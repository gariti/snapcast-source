package com.slowshell.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Resolves a MediaSession album-art URI to JPEG bytes the desktop can display.
 *
 * Streaming apps (Spotify, YT Music, Tidal…) publish an https:// CDN URL, which
 * the desktop fetches itself — those are [isRemote] and this loader declines
 * them, so nothing is copied over the link that the desktop could get directly.
 *
 * Everything else is a device-local handle: a `content://` FileProvider URI or a
 * `file://` path. Those mean nothing on the desktop (they address THIS device's
 * provider), which is why local-only sources used to show no cover at all.
 * Podcast Addict is the motivating case: it publishes
 * `content://com.bambuna.podcastaddict.artworkFileProvider/<id>_<name>.jpg`,
 * whose provider is world-readable, so any app on the phone — including this one
 * — can just open it. We read those bytes and ship them over slink instead.
 *
 * Quality note: the bytes are passed through UNCHANGED whenever they already fit
 * [MAX_DIM] and [MAX_BYTES]. Re-encoding a JPEG that is already the right size
 * only loses detail, and the cached artwork these apps hold is typically modest
 * (Podcast Addict downscales to 750x750 on download regardless of what the feed
 * served). Re-encoding is a fallback for oversized local files, not the norm.
 */
object ArtLoader {

    /** Resolved artwork: content-addressed so the desktop can cache by [key]. */
    class Art(val key: String, val bytes: ByteArray)

    /** True for URLs the desktop can fetch on its own — do not ship these. */
    fun isRemote(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true)

    /** True for a URI this loader can turn into bytes (device-local handles). */
    fun isLocal(uri: String): Boolean = uri.isNotEmpty() && !isRemote(uri)

    /**
     * Read and normalize the artwork at [uri]. Returns null when there is
     * nothing to send (empty, remote, unreadable, or not a decodable image) —
     * never throws, since failing to find a cover must not disturb the link.
     */
    fun load(context: Context, uri: String): Art? {
        if (!isLocal(uri)) return null
        return try {
            val raw = readAll(context, uri) ?: return null
            val jpeg = normalize(raw) ?: return null
            Art(sha256Hex(jpeg).take(KEY_LEN), jpeg)
        } catch (e: Exception) {
            // A provider we lack permission for, a revoked URI, a deleted cache
            // entry: all expected in the wild and all mean "no cover".
            Log.w(TAG, "art load failed for $uri: $e")
            null
        }
    }

    /** Read the stream, refusing anything absurd rather than OOMing the app. */
    private fun readAll(context: Context, uri: String): ByteArray? {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
        stream.use { input ->
            val out = ByteArrayOutputStream(DEFAULT_BUF)
            val buf = ByteArray(DEFAULT_BUF)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_RAW) {
                    Log.w(TAG, "art exceeds ${MAX_RAW}B, refusing")
                    return null
                }
            }
            return if (out.size() == 0) null else out.toByteArray()
        }
    }

    /**
     * Pass the bytes through untouched when they are already a sane size;
     * otherwise downscale to [MAX_DIM] and re-encode. Returns null if the bytes
     * are not a decodable image.
     */
    private fun normalize(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val longest = maxOf(w, h)
        if (longest <= MAX_DIM && raw.size <= MAX_BYTES) return raw

        // inSampleSize halves in powers of two; pick the largest step that stays
        // at or above the target so the final scale never upsamples.
        val opts = BitmapFactory.Options().apply {
            var sample = 1
            while (longest / (sample * 2) >= MAX_DIM) sample *= 2
            inSampleSize = sample
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val scaled = try {
            val dl = maxOf(decoded.width, decoded.height)
            if (dl <= MAX_DIM) {
                decoded
            } else {
                val ratio = MAX_DIM.toFloat() / dl
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "art scale OOM: $e")
            decoded.recycle()
            return null
        }

        val out = ByteArrayOutputStream(MAX_BYTES / 4)
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    private const val TAG = "ArtLoader"
    private const val DEFAULT_BUF = 16 * 1024

    /** Hard read ceiling — a cover art file larger than this is not cover art. */
    private const val MAX_RAW = 8 * 1024 * 1024

    /**
     * Longest-edge ceiling. The desktop art card tops out around 1280 px even on
     * the 5K panel, so 1600 leaves headroom without shipping wallpaper-sized
     * images once per track.
     */
    private const val MAX_DIM = 1600

    /** Above this, re-encode even if the dimensions are acceptable. */
    private const val MAX_BYTES = 768 * 1024

    private const val JPEG_QUALITY = 90

    /** Hex chars of the content hash used as the cache key. */
    private const val KEY_LEN = 16
}
