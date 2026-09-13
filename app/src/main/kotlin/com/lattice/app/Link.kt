package com.lattice.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The desktop as the app sees it — the proto-2 session channel decoded into
 * state the screens draw, plus the verbs they call.
 *
 * Attached to the live [ControlChannelClient] by MediaSessionBeaconService;
 * everything here survives the client reconnecting (the desk snapshot is
 * re-pushed by the bridge on every link).
 */
object Link {
    private const val TAG = "Link"

    data class Output(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, val scale: Double)
    data class Workspace(
        val id: Long, val idx: Long, val name: String, val output: String,
        val active: Boolean, val focused: Boolean, val activeWindow: Long?,
    )
    data class Win(
        val id: Long, val title: String, val app: String, val workspace: Long?, val output: String,
        val focused: Boolean, val floating: Boolean, val visible: Boolean,
        /** Output-relative logical px of the window, null while scrolled out of view. */
        val rect: FloatArray?,
    )
    data class Desk(
        val outputs: List<Output> = emptyList(),
        val workspaces: List<Workspace> = emptyList(),
        val windows: List<Win> = emptyList(),
    ) {
        val focused: Win? get() = windows.firstOrNull { it.focused }
        fun windowsOn(ws: Long) = windows.filter { it.workspace == ws }
    }

    sealed class DictState {
        data object Idle : DictState()
        data object Listening : DictState()
        data object Transcribing : DictState()
        data class Done(val text: String) : DictState()
        data class Failed(val reason: String) : DictState()
    }

    private val _client = MutableStateFlow<ControlChannelClient?>(null)
    val client: StateFlow<ControlChannelClient?> = _client.asStateFlow()

    private val _desk = MutableStateFlow(Desk())
    val desk: StateFlow<Desk> = _desk.asStateFlow()

    /** One decoded mirror frame plus what it shows. */
    class Frame(val bitmap: Bitmap, val output: String, val win: Long?, val rect: IntArray?)

    /** Newest mirror frame, already decoded. */
    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    /** Window thumbnails by id (or an error string when one could not be taken). */
    private val _thumbs = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<Long, Bitmap>> = _thumbs.asStateFlow()

    private val _dict = MutableStateFlow<DictState>(DictState.Idle)
    val dict: StateFlow<DictState> = _dict.asStateFlow()

    private val _listening = MutableStateFlow(false)
    /** Desktop audio is being streamed to this phone. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** The last mirror error the bridge reported (bad output, wf-recorder missing). */
    private val _mirrorError = MutableStateFlow<String?>(null)
    val mirrorError: StateFlow<String?> = _mirrorError.asStateFlow()

    /** Raw PCM frames for ListenService; DROP_OLDEST keeps latency bounded. */
    val pcm = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Replies to actions, keyed by the request number the caller chose. */
    val actionReplies = MutableSharedFlow<Pair<Long, String?>>(extraBufferCapacity = 32, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private var collector: Job? = null
    @Volatile private var mirrorOn = false
    private var actionSeq = 0L

    fun attach(client: ControlChannelClient, scope: CoroutineScope) {
        collector?.cancel()
        _client.value = client
        collector = scope.launch(Dispatchers.Default) {
            client.messages.collect { msg -> dispatch(msg) }
        }
    }

    fun detach() {
        collector?.cancel()
        collector = null
        _client.value = null
        _frame.value = null
        _listening.value = false
        _dict.value = DictState.Idle
    }

    private fun dispatch(msg: Map<String, Any?>) {
        when (msg["t"]) {
            "desk" -> parseDesk(msg)?.let { _desk.value = it }
            "frame" -> {
                val out = msg["output"] as? String ?: return
                val d = msg["d"] as? ByteArray ?: return
                // A late frame from a stopped mirror must not resurrect a
                // stale picture.
                if (!mirrorOn) return
                val bmp = BitmapFactory.decodeByteArray(d, 0, d.size) ?: return
                @Suppress("UNCHECKED_CAST")
                val rect = (msg["rect"] as? List<Any?>)?.map { (it as? Long)?.toInt() ?: 0 }?.takeIf { it.size == 4 }?.toIntArray()
                _frame.value = Frame(bmp, out, msg["win"] as? Long, rect)
            }
            "thumbr" -> {
                val id = msg["id"] as? Long ?: return
                val d = msg["d"] as? ByteArray
                if (d != null) {
                    BitmapFactory.decodeByteArray(d, 0, d.size)?.let { _thumbs.value = _thumbs.value + (id to it) }
                } else {
                    Log.d(TAG, "thumb $id: ${msg["err"]}")
                }
            }
            "actr" -> {
                val n = msg["n"] as? Long ?: 0L
                val ok = msg["ok"] as? Boolean ?: false
                val err = msg["err"] as? String
                if (!ok) Log.w(TAG, "action #$n failed: $err")
                actionReplies.tryEmit(n to (if (ok) null else (err ?: "failed")))
            }
            "dictr" -> {
                val text = msg["text"] as? String ?: ""
                _dict.value = when (msg["state"]) {
                    "listening" -> DictState.Listening
                    "transcribing" -> DictState.Transcribing
                    "done" -> DictState.Done(text)
                    "failed" -> DictState.Failed(text.ifBlank { "failed" })
                    else -> DictState.Idle
                }
            }
            "pcm" -> (msg["d"] as? ByteArray)?.let { pcm.tryEmit(it) }
            "listen" -> {
                _listening.value = msg["on"] as? Boolean ?: false
                (msg["err"] as? String)?.let { Log.w(TAG, "listen: $it") }
            }
            "mirror" -> {
                _mirrorError.value = msg["err"] as? String
                if (msg["on"] == false) mirrorOn = false
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDesk(m: Map<String, Any?>): Desk? {
        fun num(v: Any?): Double = when (v) {
            is Long -> v.toDouble(); is Double -> v; is Int -> v.toDouble(); else -> 0.0
        }
        val outputs = (m["outputs"] as? List<Map<String, Any?>>)?.map {
            Output(
                it["name"] as? String ?: "", num(it["x"]).toInt(), num(it["y"]).toInt(),
                num(it["w"]).toInt(), num(it["h"]).toInt(), num(it["scale"]).takeIf { s -> s > 0 } ?: 1.0,
            )
        } ?: return null
        val workspaces = (m["workspaces"] as? List<Map<String, Any?>>)?.map {
            Workspace(
                it["id"] as? Long ?: 0L, it["idx"] as? Long ?: 0L, it["name"] as? String ?: "",
                it["output"] as? String ?: "", it["active"] as? Boolean ?: false,
                it["focused"] as? Boolean ?: false, it["win"] as? Long,
            )
        } ?: emptyList()
        val windows = (m["windows"] as? List<Map<String, Any?>>)?.map {
            val r = (it["rect"] as? List<Any?>)?.map { v -> num(v).toFloat() }
            Win(
                it["id"] as? Long ?: 0L, it["title"] as? String ?: "", it["app"] as? String ?: "",
                it["ws"] as? Long, it["output"] as? String ?: "",
                it["focused"] as? Boolean ?: false, it["floating"] as? Boolean ?: false,
                it["visible"] as? Boolean ?: true,
                if (r != null && r.size == 4) r.toFloatArray() else null,
            )
        } ?: emptyList()
        return Desk(outputs, workspaces, windows)
    }

    // ---- verbs ----------------------------------------------------------------

    val ready: Boolean get() = _client.value?.sessionReady == true

    /** Run a compositor action. The argument is the niri_ipc `Action` JSON. */
    fun act(action: JSONObject): Long {
        val n = ++actionSeq
        _client.value?.post(mapOf("t" to "act", "n" to n, "a" to action.toString()))
        return n
    }

    fun act(name: String, vararg fields: Pair<String, Any?>): Long =
        act(JSONObject().put(name, JSONObject().apply { for ((k, v) in fields) put(k, v) }))

    fun requestDesk() { _client.value?.post(mapOf("t" to "desk")) }

    fun requestThumb(id: Long, width: Int = 480) {
        _client.value?.post(mapOf("t" to "thumb", "id" to id, "w" to width))
    }

    /**
     * Start or stop the mirror. `focused` follows the focused window wherever
     * it is (the bridge retargets on focus change); otherwise the named
     * output is shown whole. `output` is the fallback while the focused
     * window has no rect.
     */
    fun mirror(on: Boolean, focused: Boolean = true, output: String = "", fps: Int = 4, width: Int = 768) {
        Log.i(TAG, "mirror(on=$on focused=$focused output=$output)")
        mirrorOn = on
        if (!on) {
            _client.value?.post(mapOf("t" to "mirror", "on" to false))
            _frame.value = null
        } else {
            _mirrorError.value = null
            _client.value?.post(
                mapOf(
                    "t" to "mirror", "on" to true, "target" to (if (focused) "focused" else "output"),
                    "output" to output, "fps" to fps, "width" to width,
                )
            )
        }
    }

    /** (u, v) inside the mirrored picture → the pointer lands there. */
    fun pointerIn(u: Float, v: Float) {
        _client.value?.post(mapOf("t" to "ptr", "op" to "win", "u" to u.toDouble(), "v" to v.toDouble()))
    }

    fun pointerAbs(output: String, u: Float, v: Float) {
        _client.value?.post(mapOf("t" to "ptr", "op" to "abs", "output" to output, "u" to u.toDouble(), "v" to v.toDouble()))
    }
    fun pointerRel(dx: Float, dy: Float) {
        _client.value?.post(mapOf("t" to "ptr", "op" to "rel", "dx" to dx.toDouble(), "dy" to dy.toDouble()))
    }
    fun button(name: String, down: Boolean) {
        _client.value?.post(mapOf("t" to "ptr", "op" to "btn", "btn" to name, "down" to down))
    }
    fun click(name: String = "left") { button(name, true); button(name, false) }
    fun scroll(dx: Float, dy: Float) {
        _client.value?.post(mapOf("t" to "ptr", "op" to "scroll", "dx" to dx.toDouble(), "dy" to dy.toDouble()))
    }
    fun typeText(text: String) {
        if (text.isNotEmpty()) _client.value?.post(mapOf("t" to "key", "text" to text))
    }
    /** A named key or chord in lattice's send-key spelling: "Return", "ctrl+c", "super+Tab". */
    fun key(chord: String) { _client.value?.post(mapOf("t" to "key", "key" to chord)) }

    fun dict(op: String) {
        if (op == "start") _dict.value = DictState.Listening
        _client.value?.post(mapOf("t" to "dict", "op" to op))
    }
    fun dictAudio(pcm: ByteArray) { _client.value?.post(mapOf("t" to "dicta", "d" to pcm)) }
    fun clearDict() { _dict.value = DictState.Idle }

    fun listen(on: Boolean) { _client.value?.post(mapOf("t" to "listen", "on" to on)) }

    fun audioRoute(out: String, name: String, battery: Int?) {
        _client.value?.post(
            if (battery == null) mapOf("t" to "audio", "out" to out, "name" to name)
            else mapOf("t" to "audio", "out" to out, "name" to name, "battery" to battery)
        )
    }
}
