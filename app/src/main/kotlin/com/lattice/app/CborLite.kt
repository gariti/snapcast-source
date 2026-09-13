package com.lattice.app

import java.io.ByteArrayOutputStream

/**
 * Minimal CBOR (RFC 8949) codec for the phone-link control channel — small
 * enough that a serialization library isn't worth its build-plugin surgery.
 *
 * Supports exactly what the slink protocol uses, DEFINITE lengths only:
 *   major 0/1  ints (encoded from Long; negatives supported for completeness)
 *   major 2    byte strings   (ByteArray)
 *   major 3    text strings   (String, UTF-8)
 *   major 4    arrays         (List<Any?>)
 *   major 5    maps           (Map<String, Any?> — text keys)
 *   major 7    false / true / null / float16 / float32 / float64 (decoded to Double;
 *              ciborium on the desktop shrinks every float to the smallest lossless
 *              width, so 1.0 arrives as a half float — proto 2 carries window rects)
 *
 * The desktop side (ciborium in snapcast-mixer's phone-link) also emits
 * definite-length values, so this decoder never sees indefinite lengths from
 * a well-behaved peer; anything else throws and the caller drops the link.
 */
object CborLite {

    class CborException(msg: String) : Exception(msg)

    // ---- encode -----------------------------------------------------------

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(64)
        writeValue(out, value)
        return out.toByteArray()
    }

    private fun writeTypeAndLen(out: ByteArrayOutputStream, major: Int, len: Long) {
        val mb = major shl 5
        when {
            len < 24 -> out.write(mb or len.toInt())
            len < 0x100 -> {
                out.write(mb or 24); out.write(len.toInt())
            }
            len < 0x10000 -> {
                out.write(mb or 25)
                out.write((len shr 8).toInt() and 0xFF); out.write(len.toInt() and 0xFF)
            }
            len < 0x100000000L -> {
                out.write(mb or 26)
                for (shift in 24 downTo 0 step 8) out.write((len shr shift).toInt() and 0xFF)
            }
            else -> {
                out.write(mb or 27)
                for (shift in 56 downTo 0 step 8) out.write((len shr shift).toInt() and 0xFF)
            }
        }
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xF6)
            is Boolean -> out.write(if (value) 0xF5 else 0xF4)
            is Int -> writeValue(out, value.toLong())
            is Float -> writeValue(out, value.toDouble())
            is Double -> {
                out.write(0xFB)
                val bits = java.lang.Double.doubleToLongBits(value)
                for (shift in 56 downTo 0 step 8) out.write((bits shr shift).toInt() and 0xFF)
            }
            is Long ->
                if (value >= 0) writeTypeAndLen(out, 0, value)
                else writeTypeAndLen(out, 1, -1L - value)
            is ByteArray -> {
                writeTypeAndLen(out, 2, value.size.toLong()); out.write(value)
            }
            is String -> {
                val b = value.toByteArray(Charsets.UTF_8)
                writeTypeAndLen(out, 3, b.size.toLong()); out.write(b)
            }
            is List<*> -> {
                writeTypeAndLen(out, 4, value.size.toLong())
                for (v in value) writeValue(out, v)
            }
            is Map<*, *> -> {
                writeTypeAndLen(out, 5, value.size.toLong())
                for ((k, v) in value) {
                    writeValue(out, k as? String ?: throw CborException("map keys must be strings"))
                    writeValue(out, v)
                }
            }
            else -> throw CborException("unsupported type ${value::class}")
        }
    }

    // ---- decode -----------------------------------------------------------

    fun decode(buf: ByteArray): Any? {
        val cur = Cursor(buf)
        val v = readValue(cur, 0)
        if (cur.pos != buf.size) throw CborException("trailing bytes")
        return v
    }

    /** Convenience: decode and require a text-keyed map (every slink message). */
    @Suppress("UNCHECKED_CAST")
    fun decodeMap(buf: ByteArray): Map<String, Any?> =
        decode(buf) as? Map<String, Any?> ?: throw CborException("expected map")

    private class Cursor(val buf: ByteArray) {
        var pos = 0
        fun byte(): Int {
            if (pos >= buf.size) throw CborException("truncated")
            return buf[pos++].toInt() and 0xFF
        }
        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > buf.size) throw CborException("truncated")
            val r = buf.copyOfRange(pos, pos + n)
            pos += n
            return r
        }
    }

    private const val MAX_NESTING = 12

    private fun readLen(cur: Cursor, info: Int): Long = when {
        info < 24 -> info.toLong()
        info == 24 -> cur.byte().toLong()
        info == 25 -> (cur.byte().toLong() shl 8) or cur.byte().toLong()
        info == 26 -> {
            var v = 0L
            repeat(4) { v = (v shl 8) or cur.byte().toLong() }
            v
        }
        info == 27 -> {
            var v = 0L
            repeat(8) { v = (v shl 8) or cur.byte().toLong() }
            v
        }
        else -> throw CborException("indefinite/reserved length (info=$info)")
    }

    private fun readValue(cur: Cursor, depth: Int): Any? {
        if (depth > MAX_NESTING) throw CborException("nesting too deep")
        val ib = cur.byte()
        val major = ib shr 5
        val info = ib and 0x1F
        return when (major) {
            0 -> readLen(cur, info)
            1 -> -1L - readLen(cur, info)
            2 -> cur.bytes(readLen(cur, info).toIntChecked())
            3 -> String(cur.bytes(readLen(cur, info).toIntChecked()), Charsets.UTF_8)
            4 -> {
                val n = readLen(cur, info).toIntChecked()
                val list = ArrayList<Any?>(n.coerceAtMost(64))
                repeat(n) { list.add(readValue(cur, depth + 1)) }
                list
            }
            5 -> {
                val n = readLen(cur, info).toIntChecked()
                val map = LinkedHashMap<String, Any?>(n.coerceAtMost(64))
                repeat(n) {
                    val k = readValue(cur, depth + 1) as? String
                        ?: throw CborException("non-text map key")
                    map[k] = readValue(cur, depth + 1)
                }
                map
            }
            7 -> when (info) {
                20 -> false
                21 -> true
                22 -> null
                25 -> halfToDouble((cur.byte() shl 8) or cur.byte())
                26 -> {
                    var v = 0
                    repeat(4) { v = (v shl 8) or cur.byte() }
                    java.lang.Float.intBitsToFloat(v).toDouble()
                }
                27 -> {
                    var v = 0L
                    repeat(8) { v = (v shl 8) or cur.byte().toLong() }
                    java.lang.Double.longBitsToDouble(v)
                }
                else -> throw CborException("unsupported simple value $info")
            }
            else -> throw CborException("unsupported major type $major")
        }
    }

    private fun Long.toIntChecked(): Int {
        // Frames are capped at 256 KiB on a proto-2 link (mirror frames and
        // window thumbnails ride inside one frame), so anything beyond that is
        // corrupt input, not a big message.
        if (this < 0 || this > MAX_LEN) throw CborException("implausible length $this")
        return this.toInt()
    }

    private const val MAX_LEN = 256L * 1024

    /** IEEE 754 binary16 → Double (RFC 8949 appendix D). */
    private fun halfToDouble(half: Int): Double {
        val exp = (half shr 10) and 0x1F
        val mant = half and 0x3FF
        val value = when (exp) {
            0 -> Math.scalb(mant.toDouble(), -24)
            31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> Math.scalb((mant + 1024).toDouble(), exp - 25)
        }
        return if (half and 0x8000 != 0) -value else value
    }
}
