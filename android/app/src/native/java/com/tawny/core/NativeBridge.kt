package com.tawny.core

import android.content.Context
import java.nio.ByteBuffer

/**
 * The calls that skip the generated bindings: libwebrtc needs the app's
 * Context once, camera frames are read straight out of CameraX's direct
 * buffers (no copy on this side), and sound moves every 10 ms. Everything
 * else is in `tawny_ffi.kt`.
 */
object NativeBridge {
    @Volatile private var ready = false

    /** Loads the core and hands libwebrtc the application context. Idempotent. */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (ready) return true
        System.loadLibrary("tawny_ffi")
        ready = init(context.applicationContext)
        return ready
    }

    private external fun init(context: Context): Boolean

    /**
     * One YUV_420_888 image. The buffers must stay open until this returns.
     * Returns whether a running Monitor took it.
     */
    external fun pushFrame(
        width: Int, height: Int,
        y: ByteBuffer, yRow: Int,
        u: ByteBuffer, v: ByteBuffer, uvRow: Int, uvPixel: Int,
        rotation: Int,
    ): Boolean

    /** 10 ms of microphone sound: 480 samples, 48 kHz mono. Whether a Monitor took it. */
    external fun pushMic(samples: ShortArray): Boolean

    /** Fills [out] with every incoming track's sound, mixed; returns how many samples had sound. */
    external fun readSpeaker(out: ShortArray): Int

    /** Keep the latest incoming frame for a recording (true), or stop (false). */
    external fun recordFrames(on: Boolean)

    /**
     * The latest incoming frame, upright, packed I420 (Y, U, V), into the
     * direct buffer [dst] if it is newer than frame number [after]; its width
     * and height into [dims]. Returns its number, or -1 when there is none newer.
     */
    external fun readFrame(dst: ByteBuffer, after: Long, dims: IntArray): Long

    /** Draw the Monitor's camera on [surface] from now on; null to stop (call before it goes). */
    external fun setSurface(surface: android.view.Surface?)
}
