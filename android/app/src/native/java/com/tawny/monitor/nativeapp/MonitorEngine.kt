package com.tawny.monitor.nativeapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tawny.core.MonitorHandle
import com.tawny.core.MonitorListener
import com.tawny.core.MonitorSettings
import com.tawny.core.TawnyException
import com.tawny.core.nostrPublicKey
import com.tawny.monitor.LiveStart
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address

/**
 * The running core Monitor. Turns its events into [MonitorState] and into the
 * shell's own events (the ones app.js used to post), and answers the
 * Viewers' control frames the way app.js did. Listener calls arrive on the
 * core's event thread, in order.
 */
class MonitorEngine(
    context: Context,
    private val store: NativeStore,
    private val start: LiveStart,
    /** Camera wanted (true) or not. */
    private val onCapture: (Boolean) -> Unit,
    /** Some Viewer is talking (true) or none is. */
    private val onTalking: (Boolean) -> Unit,
    /** A Viewer asked for the torch; returns the state to report. */
    private val onTorch: (Boolean) -> TorchState,
    /** A Viewer pinched: the zoom it asked for; returns the zoom applied. */
    private val onZoom: (Float) -> Float,
    /** How far the camera zooms (1: not at all). */
    private val maxZoom: () -> Float,
) : MonitorListener {
    private val ctx = context.applicationContext
    private var handle: MonitorHandle? = null
    private val talking = mutableSetOf<String>()
    private var connected = setOf<String>()
    private var chimeAt = 0L
    private var torchAt = 0L
    private var lastBattery: JSONObject? = null
    private var zoomAt = 0L

    /** This Monitor's own name in the roster; changed from "Who's here". */
    var myName: String = MonitorEngine.cleanName(start.myName)
        private set

    fun rename(name: String) {
        myName = cleanName(name)
        sendRoster()
    }
    @Volatile var torch: TorchState = TorchState(on = false, supported = false, facing = "back")

    data class TorchState(val on: Boolean, val supported: Boolean, val facing: String)

    /** Starts the Monitor. Blocks briefly: call off the main thread. */
    fun start(mic: Boolean) {
        val servers = Servers.parse(start.servers)
        val wifi = if (start.lanPort >= 0 && !servers.nostrOnly) wifiAddress() else null
        val secret = store.stationNostrSecret(start.key)
        fun settings(port: Int?) = MonitorSettings(
            channelKey = start.key,
            roomName = start.name,
            ticket = start.token,
            nostrSecret = secret.takeIf { servers.nostrRelays.isNotEmpty() },
            nostrRelays = servers.nostrRelays,
            nostrHealth = store.relayHealth,
            nostrKnown = store.nostrKnown(start.key),
            rendezvous = start.rendezvous.takeIf { it.isNotBlank() && !servers.nostrOnly && start.token != null },
            lanPort = port?.toUShort(),
            lanIp = wifi,
            gate = store.gate(start.key),
            mic = mic,
            width = 960u,
            height = 540u,
            iceServers = servers.ice,
        )
        val want = if (wifi != null) start.lanPort.coerceIn(0, 65535) else null
        val h = try {
            MonitorHandle.start(settings(want), this)
        } catch (e: TawnyException.Start) {
            // The port Viewers know is taken: any free one beats no Wi-Fi path.
            if (want == null || want == 0 || !(e.message ?: "").contains("LAN relay")) throw e
            MonitorState.log("port $want is taken; using another")
            MonitorHandle.start(settings(0), this)
        }
        handle = h
        start.pairCode?.let { h.setPairCode(it, start.pairExpMs.toULong()) }
        MonitorState.set { it.copy(phase = MonitorUi.Phase.On) }
        MonitorState.post("live")
        MonitorState.post("waiting", "max" to MAX_VIEWERS)
        lanPortNow = h.lanPort()?.toInt()
        lanPortNow?.let { MonitorState.post("lanPort", "port" to it) }
        nostrPubNow = runCatching { nostrPublicKey(secret) }.getOrNull()?.takeIf { servers.nostrRelays.isNotEmpty() }
        nostrPubNow?.let { MonitorState.post("nostrKey", "pub" to it) }
    }

    private var lanPortNow: Int? = null
    private var nostrPubNow: String? = null

    /** Says it all again, for a live screen opened onto a running Monitor. */
    fun replay() {
        MonitorState.post("live")
        if (connected.isEmpty()) MonitorState.post("waiting", "max" to MAX_VIEWERS)
        else MonitorState.post("watching", "n" to connected.size, "max" to MAX_VIEWERS)
        lanPortNow?.let { MonitorState.post("lanPort", "port" to it) }
        nostrPubNow?.let { MonitorState.post("nostrKey", "pub" to it) }
    }

    fun setPairCode(code: String, expiresMs: Long) = handle?.setPairCode(code, expiresMs.toULong())

    fun setDimmed(dimmed: Boolean) = handle?.setDimmed(dimmed)

    fun disconnect(id: String) = handle?.disconnect(id)

    /** The phone's battery, mirrored to every Viewer (app.js's `battery` frame). */
    fun battery(pct: Int, charging: Boolean) {
        val f = JSONObject().put("type", "battery").put("level", pct).put("charging", charging)
        lastBattery = f
        runCatching { handle?.broadcast(f.toString()) }
    }

    /** The torch or the camera changed: tell every Viewer (app.js's `torch` frame). */
    fun broadcastTorch() = runCatching { handle?.broadcast(torchFrame().toString()) }

    private fun torchFrame() = JSONObject().put("type", "torch")
        .put("on", torch.on).put("supported", torch.supported).put("facing", torch.facing)

    fun stop() {
        handle?.let { it.stop(); it.close() }
        handle = null
    }

    private fun wifiAddress(): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress?.let { return it }
        }
        return null
    }

    private fun recount() {
        val now = MonitorState.ui.value.viewers.filter { it.connected }.map { it.id }.toSet()
        if (now == connected) return
        val fresh = now - connected
        connected = now
        if (now.isEmpty()) MonitorState.post("waiting", "max" to MAX_VIEWERS)
        else MonitorState.post("watching", "n" to now.size, "max" to MAX_VIEWERS)
        fresh.forEach { id ->
            MonitorState.post("viewer-on")
            // A phone that just connected gets the state the others already have.
            lastBattery?.let { send(id, it) }
            send(id, torchFrame())
            // As app.js's answerPeer: the pet name, where to find this Monitor
            // on the relays next time, and (from here, always) the zoom.
            send(id, JSONObject().put("type", "meta").put("petName", start.name).apply {
                nostrPubNow?.let { put("np", it) }
            })
            if (maxZoom() > 1f) {
                send(id, JSONObject().put("type", "cameras").put("zoomSupported", true)
                    .put("list", JSONArray().put(JSONObject().put("index", 0).put("label", "1×"))))
            }
        }
        sendRoster(joined = fresh)
    }

    private fun send(id: String, frame: JSONObject) = runCatching { handle?.sendTo(id, frame.toString()) }

    /**
     * Who is on this camera, as one Viewer should see it (app.js rosterFor):
     * this Monitor first, then every phone watching, that Viewer marked `you`.
     */
    private fun rosterFor(id: String?): JSONArray {
        val list = JSONArray().put(
            JSONObject().put("role", "station").put("name", myName).put("device", DEVICE),
        )
        MonitorState.ui.value.viewers.filter { it.connected }.forEach { v ->
            list.put(
                JSONObject().put("role", "viewer").put("name", v.name).put("device", v.label.take(20))
                    .apply { if (v.id == id) put("you", true) },
            )
        }
        return list
    }

    /**
     * The list changed: every Viewer gets its copy. The ones already watching
     * hear that someone joined only if this Monitor's Announce switch is on.
     */
    private fun sendRoster(joined: Set<String> = emptySet()) {
        connected.forEach { id ->
            val m = JSONObject().put("type", "meta").put("roster", rosterFor(id))
            if (start.announce && joined.isNotEmpty() && id !in joined) m.put("joined", true)
            send(id, m)
        }
    }

    // ---- MonitorListener (core event thread)

    override fun onCapture(on: Boolean) {
        MonitorState.set { it.copy(cameraOn = on) }
        onCapture.invoke(on)
    }

    override fun onJoined(id: String, label: String, name: String, pid: String?) {
        // A phone whose code was checked before (app.js keeps it per bond id) isn't asked again.
        val known = pid != null && store.viewerSasOk(start.key, pid)
        MonitorState.set { u ->
            u.copy(viewers = u.viewers.filterNot { it.id == id } + ViewerInfo(id, label, name, pid = pid, checked = known))
        }
    }

    /** The user said this phone's code looks right: remembered for next time. */
    fun sasChecked(id: String) {
        MonitorState.viewer(id) { it.copy(checked = true) }
        MonitorState.ui.value.viewers.firstOrNull { it.id == id }?.pid?.let { store.markViewerSasOk(start.key, it) }
    }

    override fun onConnected(id: String) {
        MonitorState.viewer(id) { it.copy(connected = true) }
        recount()
    }

    override fun onSecurityCode(id: String, code: String?) = MonitorState.viewer(id) {
        it.copy(check = code?.let { c -> SecurityCheck.Code(c) } ?: SecurityCheck.Unavailable)
    }

    override fun onViewerVideo(id: String, width: UInt, height: UInt) = MonitorState.set { u ->
        when {
            width > 0u && height > 0u -> u.copy(featured = Featured(id, width.toInt(), height.toInt()))
            u.featured?.id == id -> u.copy(featured = null)
            else -> u
        }
    }

    override fun onLeft(id: String) {
        MonitorState.set { u ->
            u.copy(viewers = u.viewers.filterNot { it.id == id }, featured = u.featured?.takeIf { it.id != id })
        }
        if (talking.remove(id) && talking.isEmpty()) talkingChanged(false)
        recount()
    }

    private fun talkingChanged(on: Boolean) {
        MonitorState.set { it.copy(talking = on) }
        onTalking(on)
    }

    override fun onGate(json: String) = store.setGate(start.key, json)

    override fun onNostrKnown(keys: List<String>) = store.setNostrKnown(start.key, keys)

    override fun onRelayHealth(json: String) { store.relayHealth = json }

    override fun onControl(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val from = o.optString("from")
        when (o.optString("type")) {
            "talking" -> {
                val was = talking.isNotEmpty()
                if (o.optBoolean("on")) talking.add(from) else talking.remove(from)
                if (was != talking.isNotEmpty()) talkingChanged(talking.isNotEmpty())
            }
            "chime" -> {
                // Rate-limited as in app.js; a dropped chime gets no ack.
                val now = System.currentTimeMillis()
                val sound = o.optString("sound")
                if (now - chimeAt <= 300 || sound !in CHIMES) return
                chimeAt = now
                MonitorState.post("chime", "slug" to sound)
                runCatching { handle?.sendTo(from, JSONObject().put("type", "chime-ack").put("sound", sound).toString()) }
            }
            "torch" -> {
                // Peer-controlled hardware: a real boolean only, and rate-limited.
                if (!o.has("on") || o.opt("on") !is Boolean) return
                val now = System.currentTimeMillis()
                if (now - torchAt < 400) return
                torchAt = now
                torch = onTorch(o.getBoolean("on"))
                broadcastTorch()
            }
            "meta" -> {
                val name = (o.opt("name") as? String)?.let(::cleanName) ?: return
                if (MonitorState.ui.value.viewers.firstOrNull { it.id == from }?.name == name) return
                MonitorState.viewer(from) { it.copy(name = name) }
                sendRoster()
            }
            "camera-control" -> {
                // Peer-controlled hardware, as in app.js: a finite number,
                // clamped; the one lens this Monitor offers needs no switching.
                val z = (o.opt("zoom") as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: return
                val now = System.currentTimeMillis()
                if (now - zoomAt < 100) return
                zoomAt = now
                val applied = onZoom(z.coerceIn(1f, 8f))
                MonitorState.set { it.copy(zoom = applied, zoomAt = now) }
            }
            else -> MonitorState.log("control: ${json.take(120)}")
        }
    }

    override fun onTransport(name: String, up: Boolean) = MonitorState.log("$name ${if (up) "up" else "down"}")

    override fun onLog(line: String) = MonitorState.log(line)

    companion object {
        const val MAX_VIEWERS = 3

        /** This phone, as the roster names it (app.js shortDeviceLabel). */
        val DEVICE: String = android.os.Build.MODEL.filter { it.code in 0x20..0x7E }.trim().take(18)
        val CHIMES = setOf("bark", "pspsps", "meow", "goodboy", "bell")

        /** Printable, short: a name a Viewer gave itself. */
        fun cleanName(s: String) = s.filter { !it.isISOControl() && it.code !in 0x202A..0x202E && it.code !in 0x2066..0x2069 }
            .trim().take(24)
    }
}
