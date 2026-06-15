package com.slowshell.app

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared crypto contract for desktop pairing — MUST stay byte-for-byte in sync
 * with the NixOS responder (flakes/slowshell-discovery.nix, responderPy).
 *
 * Why this exists: mDNS discovery (DesktopDiscovery) tells us an IP claims to be
 * the SlowShell desktop, but mDNS is unauthenticated — anyone on the LAN can lie.
 * Before we trust that IP (and especially before we let it command our media via
 * CommandUdpListener), we prove it holds the shared pairing code via a fresh-
 * nonce challenge-response. A passive observer or a replayer of a static mDNS
 * fingerprint can't forge the HMAC without the code.
 *
 * Protocol (TCP, vrfy port from the mDNS TXT record):
 *   phone -> desktop:  MAGIC(4) | VERSION(1) | nonce(16)          = 21 bytes
 *   desktop -> phone:  HMAC-SHA256(pskBytes, the 21-byte request) = 32 bytes
 *
 * Key derivation: the HMAC key is the US-ASCII bytes of the NORMALIZED pairing
 * code (uppercase, only base32 chars A-Z/2-7). The desktop stores the code in
 * exactly this canonical form, so both sides key on identical bytes.
 */
object PairingCrypto {
    const val PREFS_KEY_PSK = "pairing_psk"

    private val MAGIC = byteArrayOf(
        'S'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 'V'.code.toByte()
    )
    private const val VERSION: Byte = 1

    const val NONCE_LEN = 16
    const val REQUEST_LEN = 4 + 1 + NONCE_LEN  // 21
    const val RESPONSE_LEN = 32                // HMAC-SHA256 digest

    /** Canonicalize a pairing code: uppercase, keep only base32 chars. */
    fun normalize(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' || it in '2'..'7' }

    /** MAGIC | VERSION | nonce — the exact bytes sent on the wire AND HMAC'd. */
    fun buildRequest(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN)
        return MAGIC + byteArrayOf(VERSION) + nonce
    }

    /** HMAC-SHA256 of [request] keyed on the normalized pairing code. */
    fun expectedResponse(normalizedPsk: String, request: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalizedPsk.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(request)
    }

    /** Constant-time comparison of two digests. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
