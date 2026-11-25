package com.tawny.monitor.nativeapp

import com.tawny.monitor.LiveStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** The security code check for one call. */
sealed interface SecurityCheck {
    data object Pending : SecurityCheck
    data class Code(val code: String) : SecurityCheck
    data object Unavailable : SecurityCheck
}

data class ViewerInfo(
    val id: String,
    val label: String,
    val name: String,
    /** Its bond id, if it sent one: stable across its reconnects. */
    val pid: String? = null,
    val connected: Boolean = false,
    val check: SecurityCheck = SecurityCheck.Pending,
    /** The user answered the code card (looks right). */
    val checked: Boolean = false,
)

data class Featured(val id: String, val width: Int, val height: Int)

/** One phone on a camera, as "Who's here" lists it. */
data class Who(val monitor: Boolean, val name: String, val device: String, val you: Boolean = false)

/** Everything the Monitor's live screen shows. */
data class MonitorUi(
    val phase: Phase = Phase.Off,
    val viewers: List<ViewerInfo> = emptyList(),
    val cameraOn: Boolean = false,
    val micMuted: Boolean = false,
    /** Some Viewer is holding its talk key ("Listening to you"). */
    val talking: Boolean = false,
    /** The camera's zoom, and when a Viewer last changed it (for the chip). */
    val zoom: Float = 1f,
    val zoomAt: Long = 0L,
    /** A Viewer's own camera on this screen ("My camera"), and its size. */
    val featured: Featured? = null,
    val log: List<String> = emptyList(),
) {
    enum class Phase { Off, Starting, On }
}

/** One Monitor per process: the service writes, the live page reads. */
object MonitorState {
    private val state = MutableStateFlow(MonitorUi())
    val ui: StateFlow<MonitorUi> = state

    /** What the live page asked the service to start with. */
    @Volatile var pending: LiveStart? = null

    /** The running engine, for the page's buttons; set by the service. */
    @Volatile var engine: MonitorEngine? = null

    /** The running service's camera, for flip and torch. */
    @Volatile var camera: CameraFeed? = null

    private val shellEvents = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Events for the shell, app.js style (`{"event":"watching","n":1}`). */
    val events: SharedFlow<String> = shellEvents

    fun post(event: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("event", event)
        fields.forEach { (k, v) -> o.put(k, v) }
        shellEvents.tryEmit(o.toString())
    }

    fun set(f: (MonitorUi) -> MonitorUi) = state.update(f)

    /** A diagnostics line: in logcat as `Tawny`, and in the shell's diagnostics log. */
    fun log(line: String) {
        android.util.Log.i("Tawny", line)
        post("diag", "line" to line)
        state.update { it.copy(log = (it.log + line).takeLast(LOG_LINES)) }
    }

    fun viewer(id: String, f: (ViewerInfo) -> ViewerInfo) = state.update { u ->
        u.copy(viewers = u.viewers.map { if (it.id == id) f(it) else it })
    }

    private const val LOG_LINES = 80
}
