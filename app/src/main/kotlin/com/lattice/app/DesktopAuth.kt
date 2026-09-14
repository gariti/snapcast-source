package com.lattice.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Desktop auth on the phone side: the desktop's phone-link broker
 * (lattice-phone-bridge `src/auth.rs`) sends a challenge, this shows it and,
 * after a fingerprint, sends back a signature the desktop verifies against the
 * key it pinned at enrolment. Message contract, both ways, in PHONE-LINK.md.
 *
 *   desktop → phone  authq{id, host, user, kind, action, message, caller, nonce, exp}
 *   desktop → phone  authc{id}                       cancelled / expired
 *   phone   → desktop authr{id, ok, kid, sig}         sig = DER ECDSA over [payload]
 *   phone   → desktop authe{kid, pub, label, strongbox}
 *   desktop → phone  auther{kid, status, reason}
 *
 * What gets signed — byte-for-byte what the desktop rebuilds:
 *   "lattice-auth-v1" 0 id 0 host 0 user 0 kind 0 action 0 nonce[32] exp(u64 BE)
 * `message` and `caller` are shown, not signed; `action` is the thing agreed to.
 */
object DesktopAuth {
    private const val TAG = "DesktopAuth"

    data class Challenge(
        val id: String,
        val host: String,
        val user: String,
        val kind: String,
        val action: String,
        val message: String,
        val caller: String,
        val nonce: ByteArray,
        val exp: Long,
    ) {
        /** Seconds left, floored at 0. */
        val remaining: Long get() = (exp - System.currentTimeMillis() / 1000).coerceAtLeast(0)

        /** A one-line human title for the kind of request. */
        val title: String
            get() = when (kind) {
                "unlock" -> "Unlock $host"
                "sudo" -> "sudo on $host"
                "polkit" -> "$host asks permission"
                "test" -> "Test from $host"
                else -> "$host asks: $kind"
            }
    }

    /** Where this phone stands with the desktop's key store. */
    enum class Enrol { NONE, PENDING, TRUSTED, REVOKED, INVALIDATED }

    private val _pending = MutableStateFlow<Map<String, Challenge>>(emptyMap())
    /** Open challenges, newest last. */
    val pending: StateFlow<Map<String, Challenge>> = _pending.asStateFlow()

    private val _enrol = MutableStateFlow(Enrol.NONE)
    val enrol: StateFlow<Enrol> = _enrol.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var collector: Job? = null
    private var appContext: Context? = null

    fun attach(context: Context, client: ControlChannelClient, scope: CoroutineScope) {
        appContext = context.applicationContext
        loadState(context)
        collector?.cancel()
        collector = scope.launch(Dispatchers.Default) {
            client.messages.collect { msg -> dispatch(context.applicationContext, msg) }
        }
    }

    private fun loadState(context: Context) {
        val p = Prefs.of(context)
        val stored = p.getString(Prefs.KEY_AUTH_STATE, null)
        _enrol.value = when {
            !DesktopAuthKey.exists() -> Enrol.NONE
            stored == null -> Enrol.PENDING
            else -> runCatching { Enrol.valueOf(stored) }.getOrDefault(Enrol.PENDING)
        }
    }

    private fun saveState(context: Context, e: Enrol) {
        _enrol.value = e
        Prefs.of(context).edit().putString(Prefs.KEY_AUTH_STATE, e.name).apply()
    }

    private fun dispatch(context: Context, msg: Map<String, Any?>) {
        when (msg["t"]) {
            "authq" -> {
                val c = Challenge(
                    id = msg["id"] as? String ?: return,
                    host = msg["host"] as? String ?: "desktop",
                    user = msg["user"] as? String ?: "",
                    kind = msg["kind"] as? String ?: "",
                    action = msg["action"] as? String ?: "",
                    message = msg["message"] as? String ?: "",
                    caller = msg["caller"] as? String ?: "",
                    nonce = msg["nonce"] as? ByteArray ?: return,
                    exp = msg["exp"] as? Long ?: return,
                )
                if (c.remaining == 0L) {
                    Log.w(TAG, "challenge ${c.id} arrived already expired")
                    return
                }
                _pending.value = _pending.value + (c.id to c)
                Log.i(TAG, "challenge ${c.id}: ${c.kind} / ${c.action}")
                AuthNotifications.show(context, c)
            }
            "authc" -> {
                val id = msg["id"] as? String ?: return
                drop(context, id)
            }
            "auther" -> {
                val status = msg["status"] as? String ?: return
                val reason = msg["reason"] as? String
                Log.i(TAG, "enrol: $status ${reason ?: ""}")
                when (status) {
                    "pending" -> saveState(context, Enrol.PENDING)
                    "trusted" -> { saveState(context, Enrol.TRUSTED); _lastError.value = null }
                    "revoked" -> { DesktopAuthKey.delete(); saveState(context, Enrol.REVOKED) }
                    "rejected" -> { _lastError.value = reason ?: "rejected"; saveState(context, Enrol.NONE) }
                }
            }
        }
    }

    private fun drop(context: Context, id: String) {
        if (_pending.value.containsKey(id)) {
            _pending.value = _pending.value - id
            AuthNotifications.cancel(context, id)
        }
    }

    // ---- what the UI calls ----------------------------------------------------

    /** The bytes the desktop verifies. Mirrors `challenge_payload` in auth.rs. */
    fun payload(c: Challenge): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.write("lattice-auth-v1".toByteArray(Charsets.UTF_8))
        for (part in listOf(c.id, c.host, c.user, c.kind, c.action)) {
            out.write(0)
            out.write(part.toByteArray(Charsets.UTF_8))
        }
        out.write(0)
        out.write(c.nonce)
        out.write(ByteBuffer.allocate(8).putLong(c.exp).array())
        return out.toByteArray()
    }

    /** Send the signed approval. `sig` is the DER signature from the unlocked Signature. */
    fun approve(context: Context, id: String, sig: ByteArray) {
        val kid = DesktopAuthKey.kid() ?: return
        val client = ControlChannelClient.current.value
        if (client == null) {
            _lastError.value = "not linked to the desktop"
            return
        }
        client.post(mapOf("t" to "authr", "id" to id, "ok" to true, "kid" to kid, "sig" to sig))
        drop(context, id)
    }

    fun deny(context: Context, id: String) {
        ControlChannelClient.current.value?.post(mapOf("t" to "authr", "id" to id, "ok" to false))
        drop(context, id)
    }

    /**
     * Create (or re-create) the key and offer it to the desktop. The desktop
     * parks it as pending until `phone-auth enroll` is run there with a local
     * fingerprint; `auther{trusted}` then flips the state here.
     */
    fun enrol(context: Context) {
        val client = ControlChannelClient.current.value
        if (client == null || !(client.state.value is ControlChannelClient.LinkState.Connected)) {
            _lastError.value = "not linked to the desktop"
            return
        }
        val created = try {
            DesktopAuthKey.create()
        } catch (e: Exception) {
            Log.e(TAG, "key creation failed", e)
            _lastError.value = "cannot create key: ${e.message}"
            return
        }
        _lastError.value = null
        saveState(context, Enrol.PENDING)
        client.post(
            mapOf(
                "t" to "authe",
                "kid" to created.kid,
                "pub" to created.spkiDer,
                "label" to "${android.os.Build.MODEL} · ${android.os.Build.VERSION.RELEASE}",
                "strongbox" to created.strongBox,
            )
        )
        Log.i(TAG, "enrol offered: kid=${created.kid} strongbox=${created.strongBox}")
    }

    /** Forget the key here. The desktop's copy goes with `phone-auth revoke <kid>`. */
    fun forget(context: Context) {
        DesktopAuthKey.delete()
        saveState(context, Enrol.NONE)
    }

    /** A new fingerprint was enrolled on the phone: the key is dead by design. */
    fun keyInvalidated(context: Context) {
        DesktopAuthKey.delete()
        saveState(context, Enrol.INVALIDATED)
        _lastError.value = "a fingerprint was added on the phone; enrol again"
    }
}
