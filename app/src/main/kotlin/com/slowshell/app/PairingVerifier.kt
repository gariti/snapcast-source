package com.slowshell.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Proves a discovered host is the real SlowShell desktop via a fresh-nonce
 * challenge-response (see PairingCrypto). Returns true ONLY if the host answers
 * with the correct HMAC, i.e. it holds the same pairing code we do.
 *
 * Fail-closed: any error (timeout, refused, short read, wrong HMAC, no PSK set)
 * returns false. A false result must never cause the host to be trusted.
 */
object PairingVerifier {
    private const val TAG = "PairingVerifier"

    suspend fun verify(
        host: String,
        vrfyPort: Int,
        normalizedPsk: String,
        timeoutMs: Int = 3000,
    ): Boolean = withContext(Dispatchers.IO) {
        if (normalizedPsk.isEmpty()) return@withContext false

        val nonce = ByteArray(PairingCrypto.NONCE_LEN)
        SecureRandom().nextBytes(nonce)
        val request = PairingCrypto.buildRequest(nonce)

        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, vrfyPort), timeoutMs)
                sock.soTimeout = timeoutMs
                sock.getOutputStream().apply { write(request); flush() }

                val resp = ByteArray(PairingCrypto.RESPONSE_LEN)
                val ins = sock.getInputStream()
                var off = 0
                while (off < resp.size) {
                    val n = ins.read(resp, off, resp.size - off)
                    if (n < 0) break
                    off += n
                }
                if (off < resp.size) {
                    Log.w(TAG, "short response from $host:$vrfyPort ($off bytes)")
                    return@withContext false
                }
                val expected = PairingCrypto.expectedResponse(normalizedPsk, request)
                val ok = PairingCrypto.constantTimeEquals(resp, expected)
                Log.i(TAG, "verify $host:$vrfyPort -> ${if (ok) "MATCH" else "mismatch"}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "verify $host:$vrfyPort failed: ${e.message}")
            false
        }
    }
}
