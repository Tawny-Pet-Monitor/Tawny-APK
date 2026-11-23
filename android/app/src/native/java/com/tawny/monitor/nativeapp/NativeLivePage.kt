package com.tawny.monitor.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import com.tawny.core.NativeBridge
import com.tawny.monitor.LivePage
import com.tawny.monitor.LivePageFactory
import com.tawny.monitor.LiveShell
import com.tawny.monitor.LiveStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onSubscription
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The shell finds this by name (LivePageFactory.native). */
object NativeLivePages : LivePageFactory {
    // The page's video surface and camera call into the core as soon as they
    // exist, so the shell asks this first (loading it once per process, ~0.1 s)
    // and uses the web page when it can't load.
    override fun usable(activity: Activity): Boolean = NativeBridge.ensureLoaded(activity)

    override fun create(activity: Activity, shell: LiveShell): LivePage = NativeLivePage(activity, shell)
}

/**
 * The live screen on the Rust engine: the Monitor (a foreground service, so
 * it carries on with the screen off) or a Viewer (a call while this screen is
 * up). Looks like the web page it replaces; reports to the shell with the
 * same events.
 */
class NativeLivePage(private val activity: Activity, private val shell: LiveShell) : LivePage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = NativeStore(activity)
    var role by mutableStateOf("")
        private set
    var insets by mutableStateOf(Insets())
        private set
    var dimmed by mutableStateOf(false)
        private set
    var watch by mutableStateOf<WatchSession?>(null)
        private set
    var start: LiveStart? = null
        private set
    var motion = true
        private set
    /** The Viewer's video view, for snapshots. */
    var surface: TextureView? = null

    /** The user's theme ("system", "light", "dark") and what "system" is now. */
    var themeMode by mutableStateOf("system")
        private set
    var systemDark by mutableStateOf(true)
        private set
    val dark get() = themeMode == "dark" || (themeMode != "light" && systemDark)

    /** A short line over the picture (the theme toggle's name for itself). */
    var toast by mutableStateOf<String?>(null)
    var whoOpen by mutableStateOf(false)
    var myName by mutableStateOf("")
        private set

    /** "My camera": this phone's front camera going to the Monitor. */
    var myCamera by mutableStateOf<CameraFeed?>(null)
        private set
    private var cameraAsked = false

    /** A clip being recorded, and its seconds left. */
    var recordLeftMs by mutableStateOf<Long?>(null)
        private set
    private var clip: ClipRecorder? = null

    /** The first-call tour on screen, and where each key it points at is. */
    var coach by mutableStateOf<Coach?>(null)
    val targets = mutableStateMapOf<String, Rect>()
    private val coachShown = mutableSetOf<String>()

    data class Coach(val role: String, val steps: List<Pair<String, Int>>, val at: Int = 0)

    data class Insets(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

    override val view: View = ComposeView(activity).apply {
        setContent { TawnyTheme { LiveScreen(this@NativeLivePage) } }
    }

    override fun start(args: LiveStart) {
        start = args
        role = args.role
        motion = args.motion
        themeMode = args.theme.takeIf { it in THEMES } ?: "system"
        systemDark = args.systemTheme == "dark"
        myName = args.myName
        coachShown += args.coachSeen
        // Once per install: the JS engine's pairings come across first.
        JsMigration.ensure(activity, store, { line -> android.util.Log.i("Tawny", line); post("diag", "line" to line) }) {
            if (destroyed) return@ensure
            if (args.role == "station") startStation(args) else startViewer(args)
        }
    }

    private var destroyed = false

    private fun startStation(args: LiveStart) {
        val running = MonitorState.engine
        scope.launch {
            MonitorState.events
                // Back to a Monitor that kept running with this screen closed:
                // it says everything again (its LAN port, its Nostr key, who
                // is watching), but only once this screen listens. The events
                // keep nothing for late listeners, so a replay sent before
                // that was lost, and the QR came back without the Wi-Fi path.
                .onSubscription { running?.replay() }
                .collect { shell.post(it) }
        }
        if (running != null) return
        MonitorState.pending = args
        MonitorService.start(activity)
    }

    private fun startViewer(args: LiveStart) {
        val s = WatchSession(
            activity, store, args,
            post = { ev, fields -> post(ev, *fields) },
            words = { id, a -> activity.getString(id, *a) },
        )
        watch = s
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    check(NativeBridge.ensureLoaded(activity)) { "the media engine didn't load" }
                    s.start()
                }.exceptionOrNull()
            }
            if (err != null) post("error", "message" to (err.message ?: err.javaClass.simpleName))
        }
    }

    private fun post(event: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("event", event)
        fields.forEach { (k, v) -> o.put(k, v) }
        scope.launch { shell.post(o.toString()) }
    }

    // ---- what the controls do

    fun endCall() {
        if (role == "station") {
            post("ended")
            MonitorService.stop(activity)
        } else {
            post("ended")
        }
    }

    fun setDim(on: Boolean) {
        // A screen that dims takes the tour with it (it counts as shown).
        if (on) endCoach(true)
        dimmed = on
        shell.setDimmed(on)
        MonitorState.engine?.setDimmed(on)
    }

    fun flipCamera() {
        val cam = MonitorState.camera ?: return
        val side = cam.flip()
        MonitorState.engine?.let { it.torch = MonitorEngine.TorchState(false, it.torch.supported, side); it.broadcastTorch() }
    }

    fun setMicMuted(muted: Boolean) = MonitorService.running?.setMicMuted(muted)

    fun disconnect(id: String) = scope.launch(Dispatchers.IO) { MonitorState.engine?.disconnect(id) }

    fun looksRight(id: String) {
        MonitorState.engine?.sasChecked(id) ?: MonitorState.viewer(id) { it.copy(checked = true) }
    }

    fun chime(sound: String) = watch?.control(JSONObject().put("type", "chime").put("sound", sound))

    fun torch(on: Boolean) = watch?.control(JSONObject().put("type", "torch").put("on", on))

    fun sasConfirmed(): Boolean = start?.let { store.sasConfirmed(it.key) } ?: false

    fun confirmSas() { start?.let { store.confirmSas(it.key) } }

    /** The Monitor's picture as it is on screen now, to the gallery. */
    fun snapshot() {
        val bmp = surface?.bitmap ?: return
        scope.launch(Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            val name = "tawny-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".png"
            shell.saveImage(out.toByteArray(), name)
        }
    }

    /** Follow system → light → dark, as the web toggle; the shell keeps the choice. */
    fun cycleTheme() {
        val next = THEMES[(THEMES.indexOf(themeMode) + 1) % THEMES.size]
        themeMode = next
        post("theme", "mode" to next)
        toast = activity.getString(
            when (next) {
                "dark" -> com.tawny.monitor.R.string.w_theme_dark
                "light" -> com.tawny.monitor.R.string.w_theme_light
                else -> com.tawny.monitor.R.string.w_theme_system
            },
        )
    }

    /** "Who's here" → Change my name: kept by the shell, told to the other end. */
    fun rename(name: String) {
        val clean = MonitorEngine.cleanName(name)
        myName = clean
        post("myname", "name" to clean)
        if (role == "station") scope.launch(Dispatchers.IO) { MonitorState.engine?.rename(clean) }
        else watch?.rename(clean)
    }

    fun coachSeen(r: String) = r in coachShown

    /** Starts the tour over the keys that are on screen now, once per role. */
    fun startCoach(r: String, steps: List<Pair<String, Int>>) {
        if (coach != null || coachSeen(r)) return
        val shown = steps.filter { it.first in targets }
        if (shown.isNotEmpty()) coach = Coach(r, shown)
    }

    /** Next step, or the end; [shown] false when the tour was cut short by the code card. */
    fun coachNext() {
        val c = coach ?: return
        if (c.at + 1 < c.steps.size) coach = c.copy(at = c.at + 1) else endCoach(true)
    }

    fun endCoach(shown: Boolean) {
        val c = coach ?: return
        coach = null
        if (shown) {
            coachShown += c.role
            post("coachDone", "role" to c.role)
        }
    }

    /** My camera: on (asking for the camera the first time) or off. */
    fun toggleMyCamera() {
        if (myCamera != null) { stopMyCamera(); return }
        val w = watch ?: return
        if (w.ui.value.phase != WatchUi.Phase.Live) {
            toast = activity.getString(com.tawny.monitor.R.string.w_toast_start_call_first)
            return
        }
        if (activity.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // The answer comes back as the app returning to the front.
            cameraAsked = true
            activity.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_REQUEST)
            return
        }
        startMyCamera()
    }

    private fun startMyCamera() {
        val w = watch ?: return
        val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        val cam = CameraFeed(activity, owner, front = true) { w.diag(it) }
        myCamera = cam
        w.setCamera(true)
        cam.start {
            w.diag(it)
            toast = activity.getString(com.tawny.monitor.R.string.w_toast_could_not_open_camera)
            stopMyCamera()
        }
    }

    fun stopMyCamera() {
        myCamera?.close()
        myCamera = null
        watch?.setCamera(false)
    }

    /** Record: a clip of up to 20 s to the gallery, as the web page's key. */
    fun toggleRecord() {
        clip?.let { it.stop(); return }
        val w = watch ?: return
        if (w.ui.value.videoW == 0) {
            toast = activity.getString(com.tawny.monitor.R.string.w_toast_nothing_to_capture)
            return
        }
        val name = "tawny-" + java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", java.util.Locale.US).format(java.util.Date()) + ".mp4"
        val r = ClipRecorder(activity.cacheDir, w.sound, log = { w.diag(it) }) { file ->
            scope.launch {
                clip = null
                recordLeftMs = null
                if (file == null) {
                    toast = activity.getString(com.tawny.monitor.R.string.w_toast_video_not_supported)
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull().also { file.delete() } }
                bytes?.let { shell.saveVideo(it, name, "video/mp4") }
            }
        }
        clip = r
        r.start()
        toast = activity.getString(com.tawny.monitor.R.string.w_toast_recording, (ClipRecorder.MAX_MS / 1000).toString())
        val started = System.currentTimeMillis()
        scope.launch {
            while (clip === r) {
                recordLeftMs = (ClipRecorder.MAX_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0)
                kotlinx.coroutines.delay(250)
            }
        }
    }

    // ---- LivePage: the shell's calls

    override fun pairCode(code: String, expiresMs: Long) {
        scope.launch(Dispatchers.IO) { MonitorState.engine?.setPairCode(code, expiresMs) }
    }

    override fun battery(pct: Int, charging: Boolean) {
        scope.launch(Dispatchers.IO) { MonitorState.engine?.battery(pct, charging) }
    }

    override fun orientation(deviceCw: Int, windowCw: Int) {
        // Frames carry their own rotation from the camera; nothing to send.
    }

    override fun torchOff() {
        val e = MonitorState.engine ?: return
        e.torch = MonitorState.camera?.torch(false) ?: e.torch.copy(on = false)
        e.broadcastTorch()
    }

    override fun systemTheme(theme: String) {
        systemDark = theme == "dark"
    }

    override fun safeInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insets = Insets(top, bottom, left, right)
    }

    override fun foreground(on: Boolean) {
        if (on && cameraAsked) {
            cameraAsked = false
            val granted = activity.checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) startMyCamera()
            else toast = activity.getString(com.tawny.monitor.R.string.w_toast_could_not_open_camera)
        }
    }

    override fun destroy() {
        destroyed = true
        clip?.stop()
        myCamera?.close()
        myCamera = null
        scope.cancel()
        watch?.let { w -> Thread { w.stop() }.start() }
        watch = null
        // Leaving the live screen inside the app ends a Monitor; the Activity
        // going away (swiped from recents) does not: that is what the
        // foreground service and its Stop button are for.
        if (role == "station" && !activity.isFinishing && !activity.isDestroyed) MonitorService.stop(activity)
    }

    private companion object {
        val THEMES = listOf("system", "light", "dark")
        const val CAMERA_REQUEST = 7301
    }
}
