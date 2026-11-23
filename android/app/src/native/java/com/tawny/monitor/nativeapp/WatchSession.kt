package com.tawny.monitor.nativeapp

import android.content.Context
import android.os.Build
import com.tawny.core.WatchHandle
import com.tawny.core.WatchListener
import com.tawny.core.WatchSettings
import com.tawny.monitor.LiveStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.Timer
import kotlin.concurrent.schedule

/** What the Viewer's live screen shows. */
data class WatchUi(
    val phase: Phase = Phase.Connecting,
    val way: String? = null,
    val check: SecurityCheck = SecurityCheck.Pending,
    val videoW: Int = 0,
    val videoH: Int = 0,
    val talking: Boolean = false,
    val batteryLevel: Int? = null,
    val batteryCharging: Boolean? = null,
    val torchOn: Boolean = false,
    val torchSupported: Boolean = false,
    val paused: Boolean = false,
    val toast: String? = null,
    /** Who else is on this camera, from the Monitor; null from an older one. */
    val roster: List<Who>? = null,
    /** The Monitor zooms its camera itself (else the zoom is done here). */
    val zoomSupported: Boolean = false,
) {
    enum class Phase { Connecting, Live, Lost }
}

/**
 * One call to one Monitor, on the Rust engine. Reports to the shell with the
 * events app.js used (`live`, `error` + reason, `unreachable`). Start off the
 * main thread.
 */
class WatchSession(
    context: Context,
    private val store: NativeStore,
    val args: LiveStart,
    private val post: (String, Array<out Pair<String, Any?>>) -> Unit,
    private val words: (Int, Array<out Any>) -> String,
) : WatchListener {
    private val state = MutableStateFlow(WatchUi())
    val ui: StateFlow<WatchUi> = state
    private var handle: WatchHandle? = null
    private var everLive = false
    private val timer = Timer("tawny-watch", true)
    val sound = SoundFeed(context.applicationContext) { log(it) }

    fun start() {
        val servers = Servers.parse(args.servers)
        // The Monitor's key from the pairing, or the one it handed over since.
        val station = args.nostrStation ?: store.monitorNostr(args.key)
        val nostr = station != null && servers.nostrRelays.isNotEmpty()
        handle = WatchHandle.start(
            WatchSettings(
                channelKey = args.key,
                pid = store.bondId(args.key),
                code = args.pairCode,
                label = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(24),
                name = args.myName.ifBlank { null },
                mic = true,
                lan = args.signal.takeIf { !servers.nostrOnly },
                rendezvous = args.rendezvous.takeIf { it.isNotBlank() && args.token != null && !servers.nostrOnly },
                ticket = args.token,
                nostrStation = station.takeIf { nostr },
                nostrSecret = if (nostr) store.viewerNostrSecret(args.key) else null,
                nostrRelays = if (nostr) servers.nostrRelays else emptyList(),
                nostrHealth = store.relayHealth,
                iceServers = servers.ice,
            ),
            this,
        )
        // As app.js: a Monitor that never answers is "offline", said by the shell.
        timer.schedule(UNREACHABLE_MS) { if (!everLive) post("unreachable", emptyArray()) }
    }

    /** Hold-to-talk: the microphone runs only while it is held. */
    fun setTalking(on: Boolean) {
        if (on) sound.startMic { log(it) } else sound.stopMic()
        handle?.setTalking(on)
        state.update { it.copy(talking = on) }
    }

    /** "My camera": this phone's camera to the Monitor, or stop. */
    fun setCamera(on: Boolean) = runCatching { handle?.setCamera(on) }

    fun control(frame: JSONObject) = runCatching { handle?.sendControl(frame.toString()) }

    fun clearToast() = state.update { it.copy(toast = null) }

    fun stop() {
        timer.cancel()
        sound.close()
        handle?.let { it.stop(); it.close() }
        handle = null
    }

    fun diag(line: String) = log(line)

    private fun log(line: String) {
        android.util.Log.i("Tawny", line)
        post("diag", arrayOf("line" to line))
    }

    // ---- WatchListener (core event thread)

    override fun onWay(name: String) {
        log("calling over $name")
        state.update { it.copy(way = name) }
    }

    override fun onConnected() {
        if (!everLive) post("live", emptyArray())
        everLive = true
        state.update { it.copy(phase = WatchUi.Phase.Live) }
        sound.startSpeaker()
        args.myName.takeIf { it.isNotBlank() }?.let { control(JSONObject().put("type", "meta").put("name", it)) }
    }

    override fun onSecurityCode(code: String?) = state.update {
        it.copy(check = code?.let { c -> SecurityCheck.Code(c) } ?: SecurityCheck.Unavailable)
    }

    override fun onRefused(reason: String) {
        sound.stopSpeaker()
        post("error", arrayOf("reason" to reason))
    }

    override fun onLeft() {
        sound.stopSpeaker()
        state.update { it.copy(phase = WatchUi.Phase.Lost, check = SecurityCheck.Pending) }
    }

    override fun onVideoSize(width: UInt, height: UInt) =
        state.update { it.copy(videoW = width.toInt(), videoH = height.toInt()) }

    override fun onControl(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (m.optString("type")) {
            "battery" -> state.update {
                val lv = m.optDouble("level", Double.NaN)
                it.copy(
                    batteryLevel = if (lv.isNaN()) null else lv.toInt().coerceIn(0, 100),
                    batteryCharging = if (m.opt("charging") is Boolean) m.getBoolean("charging") else null,
                )
            }
            "torch" -> state.update { it.copy(torchOn = m.optBoolean("on"), torchSupported = m.optBoolean("supported")) }
            "chime-ack" -> {
                val label = chimeLabel(m.optString("sound")) ?: return
                state.update { it.copy(toast = words(com.tawny.monitor.R.string.w_toast_chime_played, arrayOf(label))) }
            }
            "meta" -> meta(m)
            "cameras" -> state.update { it.copy(zoomSupported = m.optBoolean("zoomSupported")) }
            else -> {}
        }
    }

    /** app.js's `meta` for a Viewer: the roster, the pause flag, the pet name. */
    private fun meta(m: JSONObject) {
        (m.opt("roster") as? org.json.JSONArray)?.let { raw ->
            val list = (0 until minOf(raw.length(), ROSTER_MAX)).mapNotNull { i ->
                val e = raw.optJSONObject(i) ?: return@mapNotNull null
                val role = e.optString("role")
                if (role != "station" && role != "viewer") return@mapNotNull null
                Who(
                    monitor = role == "station",
                    name = MonitorEngine.cleanName((e.opt("name") as? String).orEmpty()),
                    device = (e.opt("device") as? String).orEmpty().filter { it.code in 0x20..0x7E }.trim().take(20),
                    you = e.opt("you") == true,
                )
            }
            state.update { it.copy(roster = list) }
            if (m.opt("joined") == true) post("join-sound", emptyArray())
            return
        }
        // The Monitor's Nostr key (app.js saveMonitorNostrPub): the relays are
        // used for it from the next call on.
        (m.opt("np") as? String)?.lowercase()?.takeIf { NOSTR_PUB.matches(it) }?.let { np ->
            if (store.monitorNostr(args.key) != np && args.nostrStation != np) {
                store.setMonitorNostr(args.key, np)
                log("nostr: the Monitor handed over its key; the relays are used from the next session")
            }
        }
        (m.opt("paused") as? Boolean)?.let { p -> state.update { it.copy(paused = p) } }
        (m.opt("petName") as? String)?.replace(Regex("\\s+"), " ")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }?.let {
            post("petname", arrayOf("name" to it))
        }
    }

    /** Ask the Monitor to zoom its camera (a pinch let go). */
    fun zoom(level: Float) = control(JSONObject().put("type", "camera-control").put("zoom", level.toDouble()))

    /** This phone's name changed ("Who's here" → Change my name). */
    fun rename(name: String) = control(JSONObject().put("type", "meta").put("name", name))

    private fun chimeLabel(sound: String): String? = when (sound) {
        "bark" -> words(com.tawny.monitor.R.string.w_chime_bark, emptyArray())
        "pspsps" -> words(com.tawny.monitor.R.string.w_chime_pspsps, emptyArray())
        "meow" -> words(com.tawny.monitor.R.string.w_chime_meow, emptyArray())
        "goodboy" -> words(com.tawny.monitor.R.string.w_chime_goodboy, emptyArray())
        "bell" -> words(com.tawny.monitor.R.string.w_chime_bell, emptyArray())
        else -> null
    }

    override fun onTransport(name: String, up: Boolean) = log("$name ${if (up) "up" else "down"}")

    override fun onRelayHealth(json: String) { store.relayHealth = json }

    override fun onLog(line: String) = log(line)

    private companion object {
        const val UNREACHABLE_MS = 30_000L
        val NOSTR_PUB = Regex("^[0-9a-f]{64}$")

        /** The Monitor and its three Viewers (app.js ROSTER_MAX). */
        const val ROSTER_MAX = 4
    }
}
