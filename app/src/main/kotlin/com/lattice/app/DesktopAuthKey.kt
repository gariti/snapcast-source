package com.lattice.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's desktop-auth signing key: ECDSA P-256 in Android Keystore,
 * created so that the hardware refuses to sign unless a Class-3 biometric was
 * presented for THIS operation. That property, not any app code, is what makes
 * a signature from this key mean "the enrolled human touched the sensor":
 *
 *  - `setUserAuthenticationRequired(true)` + per-operation
 *    `AUTH_BIOMETRIC_STRONG` (API 30+; on 29 the −1 validity form): the
 *    Signature object must be unlocked through BiometricPrompt's CryptoObject
 *    before `sign()` works, and the TEE enforces it — a rooted OS, a stolen
 *    unlocked phone or a malicious app cannot sign.
 *  - `setInvalidatedByBiometricEnrollment(true)`: adding a fingerprint kills
 *    the key; the desktop has to trust a fresh one.
 *  - StrongBox (Titan M2 on the Pixel 7) when the device offers it, TEE
 *    otherwise; which one is reported to the desktop at enrolment.
 *
 * Ed25519 is not an option — Keystore only signs with EC (P-256) and RSA.
 * The desktop verifies with `p256` (SHA256withECDSA, DER signatures, SPKI DER
 * public keys — exactly what `Signature`/`PublicKey.encoded` produce).
 *
 * `kid` is the first 16 hex chars of SHA-256(SPKI DER); the desktop recomputes
 * it, so it is a name, never a claim.
 */
object DesktopAuthKey {
    private const val TAG = "DesktopAuthKey"
    private const val ALIAS = "lattice-desktop-auth"
    private const val PROVIDER = "AndroidKeyStore"

    data class Created(val kid: String, val spkiDer: ByteArray, val strongBox: Boolean)

    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(): Boolean = runCatching { store().containsAlias(ALIAS) }.getOrDefault(false)

    /** The SPKI DER of the public half, or null when there is no key. */
    fun publicKeyDer(): ByteArray? = runCatching {
        store().getCertificate(ALIAS)?.publicKey?.encoded
    }.getOrNull()

    fun kid(): String? = publicKeyDer()?.let { kidOf(it) }

    fun kidOf(spkiDer: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(spkiDer)
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** True when the key sits in StrongBox (vs. the TEE). */
    fun isStrongBox(): Boolean = runCatching {
        val pk = store().getKey(ALIAS, null) as? PrivateKey ?: return false
        val info = KeyFactory.getInstance(pk.algorithm, PROVIDER).getKeySpec(pk, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * Create the key (replacing any existing one). Tries StrongBox first and
     * falls back to the TEE — both are hardware-backed, both enforce the
     * biometric gate.
     */
    fun create(): Created {
        delete()
        val strongBox = try {
            generate(strongBox = true)
            true
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "no StrongBox, using the TEE")
            generate(strongBox = false)
            false
        } catch (e: Exception) {
            // Some devices surface the StrongBox refusal as a ProviderException.
            Log.i(TAG, "StrongBox refused (${e.javaClass.simpleName}), using the TEE")
            generate(strongBox = false)
            false
        }
        val spki = publicKeyDer() ?: throw IllegalStateException("key created but no public key")
        return Created(kidOf(spki), spki, strongBox)
    }

    private fun generate(strongBox: Boolean) {
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 = every operation needs its own authentication.
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox) b.setIsStrongBoxBacked(true)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        kpg.initialize(b.build())
        kpg.generateKeyPair()
    }

    fun delete() {
        runCatching { store().deleteEntry(ALIAS) }
    }

    /**
     * A Signature initialised for signing but not yet unlocked — hand it to
     * BiometricPrompt as the CryptoObject; only the object that comes back
     * from `onAuthenticationSucceeded` can sign.
     *
     * Throws KeyPermanentlyInvalidatedException when a fingerprint was added
     * since enrolment: the caller deletes the key and asks for a re-enrol.
     */
    fun signer(): Signature {
        val pk = store().getKey(ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("no desktop-auth key")
        return Signature.getInstance("SHA256withECDSA").apply { initSign(pk) }
    }
}
