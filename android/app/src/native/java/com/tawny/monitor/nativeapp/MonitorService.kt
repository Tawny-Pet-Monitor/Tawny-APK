package com.tawny.monitor.nativeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tawny.core.NativeBridge
import com.tawny.monitor.MainActivity
import com.tawny.monitor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Monitor, as a `camera|microphone` foreground service, so it keeps
 * working with the screen off.
 *
 * Google Play's rules for this, and how they're met:
 * - Started only by [start] from the visible app (the live screen, after the
 *   shell's disclosure and permission prompts). Never from the background,
 *   never at boot, and never restarted by the system (`START_NOT_STICKY`).
 * - A notification shows the whole time, saying whether the camera is on, with
 *   Stop.
 * - The camera and microphone run only while a Viewer is connected.
 * - No wake lock of the app's own: the foreground service keeps the process
 *   alive, and while the microphone is open the system's audio path keeps the
 *   CPU awake for it.
 */
class MonitorService : LifecycleService() {
    private lateinit var store: NativeStore
    private lateinit var camera: CameraFeed
    lateinit var sound: SoundFeed
        private set
    private var mic = false
    private var capturing = false
    private var engine: MonitorEngine? = null
    private var started = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dimmed = i.action == Intent.ACTION_SCREEN_OFF
            engine?.setDimmed(dimmed)
            camera.setDimmed(dimmed)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = NativeStore(this)
        camera = CameraFeed(this, this) { MonitorState.log(it) }
        sound = SoundFeed(this) { MonitorState.log(it) }
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            MonitorState.post("ended")
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val args = MonitorState.pending ?: run { stopSelf(); return START_NOT_STICKY }
        started = true

        mic = granted(Manifest.permission.RECORD_AUDIO)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            ServiceCompat.startForeground(this, NOTICE_ID, notice(cameraOn = false), types)
        } catch (e: Exception) {
            // Started from the background, or a permission was revoked: refuse
            // rather than run without the user seeing it.
            fail("couldn't start monitoring: ${e.message ?: e.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        ContextCompat.registerReceiver(
            this, screen,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        MonitorState.set { it.copy(phase = MonitorUi.Phase.Starting, viewers = emptyList()) }
        MonitorState.camera = camera

        val e = MonitorEngine(
            this, store, args,
            onCapture = { on -> lifecycleScope.launch(Dispatchers.Main) { onCapture(on) } },
            onTalking = { on -> if (on) sound.startSpeaker() else sound.stopSpeaker() },
            onTorch = { on -> runOnMain { camera.torch(on) } },
            onZoom = { z -> runOnMain { camera.zoom(z) } },
            maxZoom = { camera.maxZoom() },
        )
        engine = e
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    if (!NativeBridge.ensureLoaded(this@MonitorService)) "the media engine didn't load"
                    else { e.start(mic); null }
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
            }
            if (err != null) fail(err) else {
                MonitorState.engine = e
                e.torch = MonitorEngine.TorchState(false, camera.hasTorch(), camera.facing)
                val off = !(getSystemService(PowerManager::class.java)?.isInteractive ?: true)
                e.setDimmed(off)
                camera.setDimmed(off)
            }
        }
        return START_NOT_STICKY
    }

    /** Mute (true) or unmute the microphone: muted, it is closed, not just silent. */
    fun setMicMuted(muted: Boolean) {
        MonitorState.set { it.copy(micMuted = muted) }
        if (!mic) return
        if (muted) sound.stopMic() else if (capturing) sound.startMic { MonitorState.log(it) }
    }

    private fun onCapture(on: Boolean) {
        capturing = on
        if (on) camera.start { msg -> MonitorState.log(msg) } else camera.stop()
        // The microphone follows the camera: on only while someone watches.
        if (mic && !MonitorState.ui.value.micMuted) {
            if (on) sound.startMic { MonitorState.log(it) } else sound.stopMic()
        }
        if (!on) engine?.let { it.torch = it.torch.copy(on = false) }
        getSystemService(NotificationManager::class.java)?.notify(NOTICE_ID, notice(on))
    }

    private fun <T> runOnMain(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val latch = java.util.concurrent.CountDownLatch(1)
        var r: T? = null
        ContextCompat.getMainExecutor(this).execute { r = f(); latch.countDown() }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        return r as T
    }

    private fun fail(why: String) {
        MonitorState.log("monitor failed: $why")
        MonitorState.post("error", "message" to why)
        MonitorState.set { it.copy(phase = MonitorUi.Phase.Off) }
        stopSelf()
    }

    override fun onDestroy() {
        if (started) runCatching { unregisterReceiver(screen) }
        running = null
        MonitorState.engine = null
        MonitorState.camera = null
        camera.close()
        sound.close()
        engine?.stop()
        engine = null
        MonitorState.set { MonitorUi() }
        super.onDestroy()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notice(cameraOn: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.monitor_channel_about); setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(getString(R.string.monitor_notice_title))
            .setContentText(getString(if (cameraOn) R.string.monitor_notice_live else R.string.monitor_notice_idle))
            .setContentIntent(open)
            .addAction(0, getString(R.string.monitor_notice_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTICE_ID = 7
        private const val ACTION_STOP = "com.tawny.monitor.nativeapp.STOP"

        /** The running service, for the live screen's buttons. */
        @Volatile var running: MonitorService? = null
            private set

        /** Call only from the visible app, after the disclosure and permissions. */
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))

        /** Ends monitoring (no event: the caller already told the shell). */
        fun stop(c: Context) = c.stopService(Intent(c, MonitorService::class.java))
    }
}
