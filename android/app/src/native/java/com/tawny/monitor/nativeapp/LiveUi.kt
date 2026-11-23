package com.tawny.monitor.nativeapp

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.annotation.DrawableRes
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import android.content.res.Configuration
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tawny.core.NativeBridge
import com.tawny.monitor.R
import kotlinx.coroutines.delay

/* The web page's overlay palette (style.css :root), the same in both themes:
   everything over the video is drawn on the always-dark stage. */
internal val Glass = Color(0xAD17130F)
internal val GlassDeep = Color(0xE6130F0C)
internal val GlassLine = Color(0x2EFFF6EC)
internal val GlassFg = Color(0xFFF7F1E8)
internal val GlassDim = Color(0xFFD6C9B8)

internal val R_CTL = RoundedCornerShape(11.dp)
internal val R_CHIP = RoundedCornerShape(7.dp)
internal val R_CARD = RoundedCornerShape(16.dp)

/** The app's own tokens, from res/values(-night)/colors.xml. */
internal data class Tokens(
    val dark: Boolean,
    val stage: Color, val panel: Color, val raise: Color, val line: Color,
    val fg: Color, val dim: Color, val berry: Color, val sky: Color, val live: Color, val onAccent: Color,
) {
    val berryLit get() = lerp(berry, Color.White, 0.24f)
    val skyLit get() = lerp(sky, Color.White, 0.24f)
    val berryDeep get() = lerp(berry, Color.Black, 0.2f)
}

/** Light or dark for the live screen: the theme toggle's choice, not the Activity's. */
private val LocalLiveDark = staticCompositionLocalOf { true }

@Composable
internal fun tokens(): Tokens {
    val dark = LocalLiveDark.current
    val ctx = LocalContext.current
    // Read through a context set to the chosen night mode, so the toggle
    // repaints the screen without recreating the Activity mid-call.
    val c = remember(dark, ctx) {
        val conf = Configuration(ctx.resources.configuration)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        ctx.createConfigurationContext(conf)
    }
    fun k(id: Int) = Color(c.getColor(id))
    return remember(c) {
        Tokens(
            dark = dark,
            stage = k(R.color.stage), panel = k(R.color.panel), raise = k(R.color.raise), line = k(R.color.line),
            fg = k(R.color.text), dim = k(R.color.dim), berry = k(R.color.berry), sky = k(R.color.sky),
            live = k(R.color.live), onAccent = k(R.color.on_accent),
        )
    }
}

@Composable
fun LiveScreen(page: NativeLivePage) {
    CompositionLocalProvider(LocalLiveDark provides page.dark) {
        Box(Modifier.fillMaxSize()) {
            when (page.role) {
                "station" -> StationLive(page)
                "viewer" -> page.watch?.let { ViewerLive(page, it) }
                else -> {}
            }
            PageToast(page)
            page.coach?.let { CoachOverlay(page, it) }
        }
    }
}

private val STATION_TOUR = listOf(
    "who" to R.string.w_coach_who_monitor, "dim" to R.string.w_coach_dim,
    "flip" to R.string.w_coach_flip, "stop" to R.string.w_coach_stop,
)
private val VIEWER_TOUR = listOf(
    "talk" to R.string.w_coach_talk, "chime" to R.string.w_coach_chime, "who" to R.string.w_coach_who,
    "torch" to R.string.w_coach_light, "snap" to R.string.w_coach_snap, "leave" to R.string.w_coach_leave,
)

/**
 * The tour runs once per role, the first time the call is up with nothing
 * else on top: a moment after, so the picture lands first. The security-code
 * card cuts in front of it (and it runs again after the card).
 */
@Composable
private fun TourWhen(page: NativeLivePage, role: String, ready: Boolean, cardUp: Boolean, steps: List<Pair<String, Int>>) {
    LaunchedEffect(ready, cardUp, page.dimmed) {
        if (cardUp) page.coach?.let { if (it.role == role) page.endCoach(false) }
        if (!ready || cardUp || page.dimmed || page.coachSeen(role)) return@LaunchedEffect
        delay(1400)
        page.startCoach(role, steps)
    }
}

// ============================================================ the Monitor

@Composable
private fun StationLive(page: NativeLivePage) {
    val t = tokens()
    val ui by MonitorState.ui.collectAsState()
    val connected = ui.viewers.filter { it.connected }
    val level by (MonitorService.running?.sound?.level ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0f) }).collectAsState()
    Column(Modifier.fillMaxSize().background(t.stage)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val featured = ui.featured
            if (featured != null) {
                // A Viewer's own camera, big; this phone's drops to the corner.
                Video(page, featured.width, featured.height)
                if (ui.cameraOn) MyCameraPip(page) { MonitorState.camera }
            } else if (ui.cameraOn) {
                CameraPreview()
            }
            Rail(page, live = connected.isNotEmpty(), level = level) {
                if (page.start?.announce == true) AnnounceChip()
                if (connected.isNotEmpty()) PhoneCount(connected.size, full = connected.size >= MonitorEngine.MAX_VIEWERS, page = page)
                // Exactly one phone watching: name it; more, and the count says it.
                connected.singleOrNull()?.let { v -> Tag(v.name.ifBlank { v.label }, device = true) }
            }
            if (ui.talking) TalkFlag(page)
            ZoomChip(ui.zoom, ui.zoomAt)
            ThemeToggle(page, pip = ui.featured != null && ui.cameraOn)
            // One card at a time: the oldest phone whose code hasn't been looked at.
            val card = connected.firstOrNull { !it.checked && it.check != SecurityCheck.Pending }
            TourWhen(page, "station", ready = connected.isNotEmpty(), cardUp = card != null, steps = STATION_TOUR)
            card?.let { v ->
                SasCard(
                    note = when (v.check) {
                        is SecurityCheck.Code -> stringResource(R.string.w_sas_monitor_note) + ":"
                        else -> stringResource(R.string.w_sas_monitor_fail)
                    },
                    code = (v.check as? SecurityCheck.Code)?.code,
                    ok = stringResource(R.string.w_sas_looks_right),
                    no = stringResource(R.string.w_sas_disconnect_it),
                    onOk = { page.looksRight(v.id) },
                    onNo = { page.disconnect(v.id) },
                )
            }
        }
        ControlBar(page) {
            Ctl(R.drawable.ic_ctl_flip, stringResource(R.string.w_ctl_flip_camera), target = page to "flip", onClick = page::flipCamera)
            Ctl(
                R.drawable.ic_ctl_mic,
                stringResource(if (ui.micMuted) R.string.w_ctl_unmute_mic else R.string.w_ctl_mute_mic),
                pressed = ui.micMuted, slash = R.drawable.ic_ctl_mic_slash, alarm = true,
            ) { page.setMicMuted(!ui.micMuted) }
            Ctl(R.drawable.ic_ctl_dim, stringResource(R.string.w_ctl_dim_screen), target = page to "dim") { page.setDim(true) }
            Ctl(R.drawable.ic_ctl_end, stringResource(R.string.w_ctl_end_call), danger = true, target = page to "stop", onClick = page::endCall)
        }
    }
    if (page.whoOpen) {
        val list = listOf(Who(monitor = true, name = page.myName, device = MonitorEngine.DEVICE, you = true)) +
            connected.map { Who(monitor = false, name = it.name, device = it.label.take(20)) }
        WhoSheet(page, list) { page.whoOpen = false }
    }
    if (page.dimmed) DimOverlay { page.setDim(false) }
}

@Composable
private fun CameraPreview() {
    val mine = remember { arrayOfNulls<androidx.camera.core.Preview.SurfaceProvider>(1) }
    AndroidView(
        factory = { c ->
            PreviewView(c).apply {
                // TextureView-backed, so the rail and cards draw over it.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FIT_CENTER
                mine[0] = surfaceProvider
                MonitorState.camera?.setPreview(surfaceProvider)
            }
        },
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "This phone's camera" },
    )
    DisposableEffect(Unit) { onDispose { mine[0]?.let { MonitorState.camera?.dropPreview(it) } } }
}

/**
 * This phone's own camera in the corner (style.css #local): a Viewer's "My
 * camera", or a Monitor's while a Viewer's camera has the stage.
 */
@Composable
private fun BoxScope.MyCameraPip(page: NativeLivePage, feed: () -> CameraFeed?) {
    val mine = remember { arrayOfNulls<androidx.camera.core.Preview.SurfaceProvider>(1) }
    AndroidView(
        factory = { c ->
            PreviewView(c).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                mine[0] = surfaceProvider
                feed()?.setPreview(surfaceProvider)
            }
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = (12 + page.insets.right).dp, bottom = 12.dp)
            .fillMaxWidth(0.27f)
            .widthIn(max = 150.dp)
            .aspectRatio(3f / 4f)
            .clip(R_CTL)
            .border(1.dp, GlassLine, R_CTL)
            .background(Color.Black)
            .semantics { contentDescription = "Your camera" },
    )
    DisposableEffect(Unit) { onDispose { mine[0]?.let { feed()?.dropPreview(it) } } }
}

/** Dim mode: black, the backlight down (the shell), a tap brings it back. */
@Composable
private fun DimOverlay(onWake: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = "Wake the screen", onClick = onWake),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            stringResource(R.string.w_dim_wake), color = Color.White.copy(alpha = 0.22f),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 48.dp),
        )
    }
}

// ============================================================ a Viewer

@Composable
private fun ViewerLive(page: NativeLivePage, s: WatchSession) {
    val t = tokens()
    val ui by s.ui.collectAsState()
    val level by s.sound.level.collectAsState()
    var chimes by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(page.sasConfirmed()) }
    LaunchedEffect(ui.toast) { if (ui.toast != null) { delay(2600); s.clearToast() } }
    var zoom by remember { mutableStateOf(1f) }
    var zoomAt by remember { mutableStateOf(0L) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize().background(t.stage)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(ui.zoomSupported) {
                pinch(
                    onZoom = { f ->
                        zoom = (zoom * f).coerceIn(1f, 8f)
                        if (zoom <= 1.005f) pan = Offset.Zero
                        zoomAt = System.currentTimeMillis()
                    },
                    // Zoomed on this phone (the Monitor can't): one finger moves the view.
                    onPan = { d -> if (!ui.zoomSupported && zoom > 1.005f) pan += d },
                    // The Monitor zooms its own camera: sharper than enlarging here.
                    onEnd = { if (ui.zoomSupported) s.zoom(zoom) },
                )
            },
        ) {
            Video(page, ui.videoW, ui.videoH, digital = if (ui.zoomSupported) 1f else zoom, pan = pan) { pan = it }
            if (page.myCamera != null) MyCameraPip(page) { page.myCamera }
            Rail(page, live = ui.phase == WatchUi.Phase.Live, level = level) {
                // From the Monitor's list, the one way in to who else is watching.
                ui.roster?.let { r -> PhoneCount(r.count { !it.monitor }, full = false, page = page) }
                ui.batteryLevel?.let { BatteryTag(it, ui.batteryCharging == true, t) }
                Tag(page.start?.name ?: "", keepCase = true)
            }
            ZoomChip(zoom, zoomAt)
            ThemeToggle(page, pip = page.myCamera != null)
            // A centred note while there is no picture to trust.
            val note = when {
                ui.paused -> stringResource(R.string.w_status_monitor_paused)
                ui.phase == WatchUi.Phase.Connecting -> stringResource(R.string.w_status_connecting)
                ui.phase == WatchUi.Phase.Lost -> stringResource(R.string.w_status_reconnecting)
                else -> null
            }
            note?.let { StageNote(it) }
            ui.toast?.let { Toast(it) }
            TalkFab(page, enabled = ui.phase == WatchUi.Phase.Live, talking = ui.talking, onTalk = s::setTalking, t = t)
            // Off the Wi-Fi, the first time with this Monitor: compare the code.
            val cardUp = !asked && ui.check != SecurityCheck.Pending
            TourWhen(page, "viewer", ready = ui.phase == WatchUi.Phase.Live, cardUp = cardUp, steps = VIEWER_TOUR)
            if (cardUp) {
                SasCard(
                    note = if (ui.check is SecurityCheck.Code) stringResource(R.string.w_sas_viewer_note)
                    else stringResource(R.string.w_sas_viewer_fail),
                    code = (ui.check as? SecurityCheck.Code)?.code,
                    ok = stringResource(R.string.w_sas_looks_right),
                    no = stringResource(R.string.w_sas_disconnect),
                    onOk = { asked = true; page.confirmSas() },
                    onNo = page::endCall,
                )
            }
            if (chimes) ChimeTray { sound -> page.chime(sound); chimes = false }
        }
        ControlBar(page) {
            Ctl(R.drawable.ic_ctl_chime, stringResource(R.string.w_ctl_chime), pressed = chimes, target = page to "chime") { chimes = !chimes }
            Ctl(
                R.drawable.ic_ctl_torch,
                stringResource(if (ui.torchOn) R.string.w_ctl_light_on else R.string.w_ctl_light),
                pressed = ui.torchOn, enabled = ui.torchSupported, target = page to "torch",
            ) { page.torch(!ui.torchOn) }
            Ctl(
                R.drawable.ic_ctl_listen,
                stringResource(if (muted) R.string.w_ctl_unmute else R.string.w_ctl_mute_listen),
                pressed = muted, slash = R.drawable.ic_ctl_listen_slash,
            ) { muted = !muted; s.sound.speakerMuted = muted }
            Ctl(
                R.drawable.ic_ctl_mycam, stringResource(R.string.w_ctl_my_camera),
                pressed = page.myCamera != null, onClick = page::toggleMyCamera,
            )
            Ctl(R.drawable.ic_ctl_snap, stringResource(R.string.w_ctl_snapshot), target = page to "snap", onClick = page::snapshot)
            val left = page.recordLeftMs
            Ctl(
                R.drawable.ic_ctl_record,
                if (left != null) "0:%02d".format(((left + 999) / 1000).toInt()) else stringResource(R.string.w_ctl_record),
                pressed = left != null, alarm = true, onClick = page::toggleRecord,
            )
            Ctl(R.drawable.ic_ctl_end, stringResource(R.string.w_ctl_hang_up), danger = true, target = page to "leave", onClick = page::endCall)
        }
    }
    if (page.whoOpen) WhoSheet(page, ui.roster) { page.whoOpen = false }
}

/**
 * Two fingers zoom (each move's factor to [onZoom]), one finger drags
 * ([onPan]); [onEnd] when the fingers lift after a pinch.
 */
private suspend fun PointerInputScope.pinch(onZoom: (Float) -> Unit, onPan: (Offset) -> Unit, onEnd: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinched = false
        do {
            val ev = awaitPointerEvent()
            val down = ev.changes.count { it.pressed }
            if (down >= 2) {
                val f = ev.calculateZoom()
                if (f != 1f) {
                    pinched = true
                    onZoom(f)
                    ev.changes.forEach { it.consume() }
                }
            } else if (down == 1 && !pinched) {
                val d = ev.calculatePan()
                if (d != Offset.Zero) onPan(d)
            }
        } while (ev.changes.any { it.pressed })
        if (pinched) onEnd()
    }
}

@Composable
private fun BoxScope.Video(
    page: NativeLivePage,
    videoW: Int,
    videoH: Int,
    digital: Float = 1f,
    pan: Offset = Offset.Zero,
    clampPan: (Offset) -> Unit = {},
) {
    val ratio = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 16f / 9f
    var box by remember { mutableStateOf(IntSize.Zero) }
    AndroidView(
        // A TextureView, not a SurfaceView: it composites like any other view,
        // so the rail, the talk key and the cards draw over the picture (a
        // SurfaceView's hole sat under the stage background: a black screen).
        factory = { c ->
            TextureView(c).apply {
                keepScreenOn = true
                page.surface = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var s: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        s = Surface(st).also { NativeBridge.setSurface(it) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        NativeBridge.setSurface(null)
                        s?.release()
                        s = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier
            .align(Alignment.Center)
            .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
            .onSizeChanged { box = it }
            .graphicsLayer {
                scaleX = digital
                scaleY = digital
                translationX = pan.x
                translationY = pan.y
            }
            .semantics { contentDescription = "The Monitor's camera" },
    )
    // As app.js clampZoomPan: never past the picture's own edge.
    LaunchedEffect(pan, digital, box) {
        val mx = (digital - 1f) / 2f * box.width
        val my = (digital - 1f) / 2f * box.height
        val c = Offset(pan.x.coerceIn(-mx, mx), pan.y.coerceIn(-my, my))
        if (c != pan) clampPan(c)
    }
}

/** Hold-to-talk: a floating key of its own, centred above the controls. */
@Composable
private fun BoxScope.TalkFab(page: NativeLivePage, enabled: Boolean, talking: Boolean, onTalk: (Boolean) -> Unit, t: Tokens) {
    val label = stringResource(if (talking) R.string.w_ctl_talking else R.string.w_hold_to_talk)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 34.dp)
            .size(68.dp)
            .coachTarget(page, "talk")
            .clip(CircleShape)
            .background(if (talking) t.berry else Glass)
            .border(1.dp, if (talking) t.berry else GlassLine, CircleShape)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                role = Role.Button
                contentDescription = label
                stateDescription = label
            }
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    // `finally`: when the call drops mid-press, this gesture
                    // is cancelled inside tryAwaitRelease(), and the microphone
                    // must not be left open.
                    try {
                        onTalk(true)
                        tryAwaitRelease()
                    } finally {
                        onTalk(false)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_ctl_talk), null, tint = if (talking) t.onAccent else GlassFg, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun BoxScope.ChimeTray(onChime: (String) -> Unit) {
    val sounds = listOf(
        "bark" to R.string.w_chime_bark, "pspsps" to R.string.w_chime_pspsps, "meow" to R.string.w_chime_meow,
        "goodboy" to R.string.w_chime_goodboy, "bell" to R.string.w_chime_bell,
    )
    Column(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        sounds.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (slug, label) ->
                    Box(
                        Modifier.clip(R_CTL).background(GlassDeep).border(1.dp, GlassLine, R_CTL)
                            .clickable(role = Role.Button) { onChime(slug) }
                            .heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(label), color = GlassFg, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.StageNote(text: String) {
    Column(
        Modifier.align(Alignment.Center).clip(R_CARD).background(GlassDeep).border(1.dp, GlassLine, R_CARD)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) { Text(text, color = GlassFg, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun BoxScope.Toast(text: String) {
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(R_CHIP).background(GlassDeep)
            .border(1.dp, GlassLine, R_CHIP).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = GlassFg, style = MaterialTheme.typography.bodyMedium) }
}

// ============================================================ shared pieces

/** The status rail: a glass chip (dot), the far end's sound level, then small tags. */
@Composable
private fun BoxScope.Rail(page: NativeLivePage, live: Boolean, level: Float, right: @Composable RowScope.() -> Unit) {
    val t = tokens()
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x6B0A0805), Color.Transparent)))
            .padding(top = (9 + page.insets.top).dp, start = (12 + page.insets.left).dp, end = (12 + page.insets.right).dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.clip(R_CHIP).background(Glass).border(1.dp, GlassLine, R_CHIP).padding(6.dp)) {
            Dot(live, page.motion, t)
        }
        Meter(level, t)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

@Composable
private fun Dot(live: Boolean, motion: Boolean, t: Tokens) {
    val breathe = if (live && motion) {
        rememberInfiniteTransition(label = "dot").animateFloat(
            0.55f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f
    Box(Modifier.size(8.dp).alpha(breathe).clip(CircleShape).background(if (live) t.live else t.dim))
}

/** Twelve bars; lit up to the level, the loudest in the warning red. */
@Composable
private fun Meter(level: Float, t: Tokens) {
    val n = 12
    val lit = (level * 2.2f * n).toInt().coerceIn(0, n)
    Row(
        Modifier.height(14.dp).semantics { contentDescription = "Sound level at the far end" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(n) { i ->
            val on = i < lit
            Box(
                Modifier.width(3.dp).fillMaxHeight(if (on) 1f else 0.42f).clip(RoundedCornerShape(99.dp))
                    .background(if (!on) Color(0x3DF7F1E8) else if (i >= n - 2) t.live else t.berryLit),
            )
        }
    }
}

/**
 * A rail tag. A phone's own name ([device]) is a real name, not shouted, in
 * the brighter ink (style.css #peerlabel); the pet's name keeps its case too.
 */
@Composable
private fun Tag(text: String, keepCase: Boolean = false, device: Boolean = false) {
    if (text.isBlank()) return
    Box(
        Modifier.widthIn(max = 140.dp).clip(R_CHIP)
            .background(if (device) Color.White.copy(alpha = 0.08f) else Glass).border(1.dp, GlassLine, R_CHIP)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            if (keepCase || device) text else text.uppercase(), color = if (device) GlassFg else GlassDim, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = if (device) 0.01.em else 0.12.em,
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The phones-watching chip; the one thing in the rail you can tap (Who's here). */
@Composable
private fun PhoneCount(n: Int, full: Boolean, page: NativeLivePage) {
    val t = tokens()
    val label = when {
        page.role != "station" -> stringResource(R.string.w_who_open)
        full -> stringResource(R.string.w_aria_watching_full, n)
        n == 1 -> stringResource(R.string.w_aria_watching_one, n)
        else -> stringResource(R.string.w_aria_watching_many, n)
    }
    Row(
        Modifier.coachTarget(page, "who").clip(R_CHIP)
            .background(if (full) lerp(Glass, t.live, 0.18f) else Glass)
            .border(1.dp, if (full) t.live.copy(alpha = 0.6f) else GlassLine, R_CHIP)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.w_who_open)) { page.whoOpen = true }
            // The chip is small; the tap area is not.
            .heightIn(min = 22.dp).padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_ctl_phones), null, tint = GlassDim, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = GlassDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}

/** The Monitor's battery: an outline cell, the fill, a bolt while charging. */
@Composable
private fun BatteryTag(level: Int, charging: Boolean, t: Tokens) {
    val c = if (level <= 15 && !charging) t.live else GlassDim
    Row(
        Modifier.clip(R_CHIP).background(Glass).border(1.dp, GlassLine, R_CHIP).padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Monitor battery $level%" + if (charging) ", charging" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 20.dp, height = 10.dp).border(1.dp, c, RoundedCornerShape(2.dp)).padding(1.5.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).background(c, RoundedCornerShape(1.dp)))
        }
        if (charging) Text(" ⚡", color = c, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text("$level%", color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}

/** The safety-code card: the one card a user compares character by character. */
@Composable
private fun BoxScope.SasCard(note: String, code: String?, ok: String, no: String, onOk: () -> Unit, onNo: () -> Unit) {
    val t = tokens()
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .fillMaxWidth(0.88f)
            .widthIn(max = 360.dp)
            .clip(R_CARD)
            .background(GlassDeep)
            .border(1.dp, GlassLine, R_CARD)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(note, color = GlassDim, fontSize = 14.sp, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            code ?: stringResource(R.string.w_sas_code_unavailable),
            color = if (code != null) t.skyLit else t.live,
            fontSize = if (code != null) 32.sp else 16.sp, letterSpacing = if (code != null) 0.18.em else 0.em,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CardButton(ok, primary = true, onClick = onOk, t = t)
            CardButton(no, primary = false, onClick = onNo, t = t)
        }
    }
}

@Composable
private fun RowScope.CardButton(label: String, primary: Boolean, onClick: () -> Unit, t: Tokens) {
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(R_CTL)
            .background(if (primary) t.berry else Color.White.copy(alpha = 0.07f))
            .border(1.dp, if (primary) t.berry else GlassLine, R_CTL)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(), color = if (primary) t.onAccent else GlassFg, letterSpacing = 0.12.em,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The control row: the web page's `.controls`, keys with an icon plate and a word. */
@Composable
private fun ControlBar(page: NativeLivePage, content: @Composable RowScope.() -> Unit) {
    val t = tokens()
    Column(Modifier.fillMaxWidth().background(t.panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.line))
        Row(
            Modifier.fillMaxWidth().padding(
                start = (6 + page.insets.left).dp, end = (6 + page.insets.right).dp,
                top = 7.dp, bottom = (7 + page.insets.bottom).dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            content = content,
        )
    }
}

@Composable
private fun RowScope.Ctl(
    @DrawableRes icon: Int,
    label: String,
    pressed: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    @DrawableRes slash: Int? = null,
    /** The page and the name the first-call tour knows this key by. */
    target: Pair<NativeLivePage, String>? = null,
    /** Latched means "this is live" (a muted mic, a clip running): the warm red. */
    alarm: Boolean = false,
    onClick: () -> Unit,
) {
    val t = tokens()
    val acc = if (danger || alarm) t.live else t.berry
    val plate = when {
        danger -> lerp(t.panel, t.live, 0.11f)
        pressed -> lerp(t.panel, acc, 0.18f)
        else -> t.raise
    }
    val edge = when {
        danger -> lerp(t.line, t.live, 0.34f)
        pressed -> lerp(t.line, acc, 0.60f)
        else -> t.line
    }
    // As .ctl in style.css: the word and the glyph wear the dim ink; a
    // latched key takes the accent; End call's glyph is red at rest.
    val ink = if (pressed) (if (alarm) t.live else t.berryDeep) else t.dim
    Column(
        Modifier
            .weight(1f)
            .clip(R_CTL)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.42f)
            .semantics { if (slash != null) stateDescription = if (pressed) "On" else "Off" }
            .padding(top = 2.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.widthIn(max = 58.dp).fillMaxWidth().height(34.dp)
                .then(if (target != null) Modifier.coachTarget(target.first, target.second) else Modifier)
                .clip(R_CTL).background(plate).border(1.dp, edge, R_CTL),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = if (danger) t.live else ink, modifier = Modifier.size(25.dp))
            if (slash != null && pressed) Icon(painterResource(slash), null, tint = t.live, modifier = Modifier.size(25.dp))
        }
        // A one-word label never breaks inside the word ("SNAPSHO-T" with seven
        // keys): it may run a little past its key, as on the web page; two
        // words wrap between them ("MY / CAMERA").
        val oneWord = ' ' !in label.trim()
        Text(
            label.uppercase(), color = ink, fontSize = 11.5.sp, letterSpacing = 0.04.em,
            maxLines = if (oneWord) 1 else 2, softWrap = !oneWord,
            textAlign = TextAlign.Center, lineHeight = 13.sp, style = MaterialTheme.typography.labelMedium,
            modifier = if (oneWord) Modifier.wrapContentWidth(unbounded = true) else Modifier,
        )
    }
}
