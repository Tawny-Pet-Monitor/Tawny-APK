package com.tawny.monitor.nativeapp

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.tawny.core.NativeBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The camera, bound to the service's lifecycle, feeding the core. It runs only
 * between [start] and [stop]: the service starts it when a Viewer connects and
 * stops it when the last one leaves. While the live screen is up it also
 * shows the picture there ([setPreview]). Call everything on the main thread.
 */
class CameraFeed(
    context: Context,
    private val owner: LifecycleOwner,
    /** Start on the front camera (a Viewer's "My camera"). */
    front: Boolean = false,
    private val log: (String) -> Unit,
) {
    private val ctx = context.applicationContext
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tawny-camera") }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewSurface: Preview.SurfaceProvider? = null
    private var camera: Camera? = null
    private var wanted = false
    private var back = !front
    @Volatile private var periodNs = NORMAL_PERIOD_NS
    @Volatile private var nextNs = 0L
    private var seen = 0
    private var sent = 0
    private var windowNs = 0L

    val facing: String get() = if (back) "back" else "front"

    /** Fewer frames when dimmed (nobody is looking at this screen). */
    fun setDimmed(dimmed: Boolean) {
        periodNs = if (dimmed) DIMMED_PERIOD_NS else NORMAL_PERIOD_NS
    }

    fun start(onError: (String) -> Unit) {
        wanted = true
        if (analysis == null) bind(onError)
    }

    fun stop() {
        wanted = false
        torchWanted = false
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        analysis = null
        preview = null
        camera = null
    }

    /** The live screen's preview surface, or null when it goes away. */
    fun setPreview(surface: Preview.SurfaceProvider?) {
        previewSurface = surface
        if (wanted && analysis != null) bind { log(it) }
    }

    /**
     * A preview view is going away: stop drawing on it, unless another one
     * has already taken over (the new view is made before the old one's
     * cleanup runs, when the stage swaps the full picture for the corner).
     */
    fun dropPreview(surface: Preview.SurfaceProvider) {
        if (previewSurface === surface) setPreview(null)
    }

    /** Front ↔ back. Returns the side now in use. */
    fun flip(): String {
        back = !back
        torchWanted = false
        zoomWanted = 1f
        if (wanted) bind { log(it) }
        return facing
    }

    private var torchWanted = false

    /** Whether the camera in use has a flash unit (known before it runs). */
    fun hasTorch(): Boolean = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return false
        cameraId()?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } == true
    }.getOrDefault(false)

    /** The torch. Asked for before the camera runs, it lights when it does. */
    fun torch(on: Boolean): MonitorEngine.TorchState {
        if (!hasTorch()) return MonitorEngine.TorchState(false, false, facing)
        torchWanted = on
        camera?.cameraControl?.enableTorch(on)
        return MonitorEngine.TorchState(on, true, facing)
    }

    private var zoomWanted = 1f

    /** How far the camera in use zooms (1 when it can't), capped at app.js's 8×. */
    fun maxZoom(): Float = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return 1f
        val ch = cm.getCameraCharacteristics(cameraId() ?: return 1f)
        val top = if (android.os.Build.VERSION.SDK_INT >= 30) {
            ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
        } else null
        (top ?: ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(1f, MAX_ZOOM)
    }.getOrDefault(1f)

    /** Zoom (a Viewer's pinch). Returns the ratio applied. */
    fun zoom(ratio: Float): Float {
        zoomWanted = ratio.coerceIn(1f, maxZoom())
        camera?.cameraControl?.setZoomRatio(zoomWanted)
        return zoomWanted
    }

    private fun cameraId(): String? {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        val lens = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens }
    }

    fun close() {
        stop()
        exec.shutdown()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (!wanted) return@addListener
            try {
                val p = future.get()
                p.unbindAll()
                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(960, 540), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                fpsRange()?.let {
                    Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                val a = builder.build().also { it.setAnalyzer(exec, ::push) }
                val pv = previewSurface?.let { s -> Preview.Builder().build().also { it.surfaceProvider = s } }
                // A phone (or emulator) with one camera only: use that one.
                if (!back && !p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) back = true
                if (back && !p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) back = false
                val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                camera = p.bindToLifecycle(owner, selector, *listOfNotNull(a, pv).toTypedArray())
                if (torchWanted && hasTorch()) camera?.cameraControl?.enableTorch(true)
                if (zoomWanted > 1f) camera?.cameraControl?.setZoomRatio(zoomWanted)
                provider = p
                analysis = a
                preview = pv
            } catch (e: Exception) {
                onError("camera: ${e.message ?: e.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * The camera's frame-rate range that reaches 24 fps with the lowest floor
     * nearest 15: smooth enough to watch, and in a dim room the camera may
     * slow to 15 for a brighter picture, not to 12 or below. The default
     * (often 12-30 fps) gave 12 fps indoors.
     */
    private fun fpsRange(): Range<Int>? = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        val lens = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens }
            ?: return null
        val ranges = cm.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        val top = ranges.map { it.upper }.filter { it >= TARGET_FPS }.minOrNull() ?: return null
        ranges.filter { it.upper == top }.minByOrNull { kotlin.math.abs(it.lower - FLOOR_FPS) }
    }.getOrNull()

    private fun push(img: ImageProxy) {
        img.use {
            // Pace to the target rate on average: the next slot moves on by one
            // period, not to this frame's time, so a 25 or 30 fps camera gives
            // 24 fps (dropping a frame now and then), not half of it.
            val t = it.imageInfo.timestamp
            seen++
            if (windowNs == 0L) windowNs = t
            if (t - windowNs >= STATS_NS) {
                val secs = (t - windowNs) / 1e9
                log("camera ${it.width}x${it.height}: %.1f fps in, %.1f fps sent".format(seen / secs, sent / secs))
                seen = 0; sent = 0; windowNs = t
            }
            if (t < nextNs) return
            nextNs = maxOf(nextNs + periodNs, t - periodNs)
            val p = it.planes
            if (p.size < 3) return
            sent++
            NativeBridge.pushFrame(
                it.width, it.height,
                p[0].buffer, p[0].rowStride,
                p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                it.imageInfo.rotationDegrees,
            )
        }
    }

    private companion object {
        // 24 fps, and 15 fps dimmed (call::policy's DIMMED_CAPTURE_FPS).
        const val NORMAL_PERIOD_NS = 1_000_000_000L / 24
        const val DIMMED_PERIOD_NS = 1_000_000_000L / 15
        const val STATS_NS = 30_000_000_000L
        const val TARGET_FPS = 24
        const val FLOOR_FPS = 15
        const val MAX_ZOOM = 8f
    }
}
