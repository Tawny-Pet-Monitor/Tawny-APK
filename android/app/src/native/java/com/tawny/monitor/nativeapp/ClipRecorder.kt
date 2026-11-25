package com.tawny.monitor.nativeapp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import com.tawny.core.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A short clip of the Monitor's camera and room, as the web page's Record:
 * up to [MAX_MS], H.264 and AAC in an MP4. The picture comes from the core
 * (the latest decoded frame, upright: [NativeBridge.readFrame]); the sound is
 * what the speaker plays, taken from [SoundFeed.recordTap].
 *
 * One clip per instance: [start], then [stop] (or the time runs out), then
 * [onDone] gets the file, or null when nothing could be recorded.
 */
class ClipRecorder(
    cacheDir: File,
    private val sound: SoundFeed,
    private val log: (String) -> Unit,
    private val onDone: (File?) -> Unit,
) {
    private val file = File(cacheDir, "clip-${System.currentTimeMillis()}.mp4")
    private val lock = Any()
    @Volatile private var running = false
    private var t0 = 0L

    // Under `lock`: the muxer and the audio encoder are fed from two threads.
    private var muxer: MediaMuxer? = null
    private var audio: MediaCodec? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var keyWritten = false
    private var audioStartUs = -1L
    private var audioSamples = 0L
    private var wrote = 0

    // The video thread's own.
    private var video: MediaCodec? = null
    private var videoFormatAt = 0L

    fun start() {
        running = true
        t0 = System.nanoTime()
        NativeBridge.recordFrames(true)
        Thread(::run, "tawny-record").start()
    }

    fun stop() {
        running = false
    }

    private fun nowUs() = (System.nanoTime() - t0) / 1000

    private fun run() {
        val buf = ByteBuffer.allocateDirect(MAX_FRAME_BYTES)
        val bytes = ByteArray(MAX_FRAME_BYTES)
        val dims = IntArray(2)
        var seq = 0L
        var w = 0
        var h = 0
        var ok = false
        try {
            while (running && System.nanoTime() - t0 < MAX_MS * 1_000_000L) {
                val n = NativeBridge.readFrame(buf, seq, dims)
                if (n > 0) {
                    seq = n
                    if (video == null) {
                        w = dims[0]; h = dims[1]
                        open(w, h)
                    }
                    if (dims[0] == w && dims[1] == h) {
                        buf.rewind()
                        buf.get(bytes, 0, w * h * 3 / 2)
                        feedVideo(bytes, w, h)
                    }
                }
                video?.let { drain(it, isVideo = true, ending = false) }
                // No sound arriving (the far end went quiet for good): a silent clip.
                synchronized(lock) {
                    if (!muxing && videoTrack >= 0 && System.nanoTime() - videoFormatAt > NO_AUDIO_NS) {
                        audioTrack = -1
                        startMuxer()
                    }
                }
                Thread.sleep(8)
            }
            ok = finish()
        } catch (e: Exception) {
            log("record: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            NativeBridge.recordFrames(false)
            sound.recordTap = null
            release()
            if (!ok) file.delete()
            onDone(if (ok) file else null)
        }
    }

    private fun open(w: Int, h: Int) {
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        synchronized(lock) {
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }
        sound.recordTap = ::feedAudio
        log("record: ${w}x$h")
    }

    /** Packed I420 into the encoder's input image, whatever its layout. */
    private fun feedVideo(src: ByteArray, w: Int, h: Int) {
        val codec = video ?: return
        val i = codec.dequeueInputBuffer(10_000)
        if (i < 0) return
        val img = codec.getInputImage(i) ?: run { codec.queueInputBuffer(i, 0, 0, nowUs(), 0); return }
        val planes = img.planes
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        copyPlane(src, 0, w, h, planes[0])
        copyPlane(src, w * h, cw, ch, planes[1])
        copyPlane(src, w * h + cw * ch, cw, ch, planes[2])
        codec.queueInputBuffer(i, 0, w * h * 3 / 2, nowUs(), 0)
    }

    private fun copyPlane(src: ByteArray, off: Int, w: Int, h: Int, p: android.media.Image.Plane) {
        val dst = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        for (y in 0 until h) {
            val row = off + y * w
            if (ps == 1) {
                dst.position(y * rs)
                dst.put(src, row, w)
            } else {
                var at = y * rs
                for (x in 0 until w) {
                    if (at >= dst.limit()) break
                    dst.put(at, src[row + x])
                    at += ps
                }
            }
        }
    }

    /** 10 ms of the far end's sound (the speaker thread). Dropped if the encoder is busy. */
    private fun feedAudio(samples: ShortArray) = synchronized(lock) {
        val codec = audio ?: return@synchronized
        if (audioStartUs < 0) audioStartUs = nowUs()
        val i = codec.dequeueInputBuffer(0)
        if (i >= 0) {
            val ib = codec.getInputBuffer(i) ?: return@synchronized
            ib.clear()
            ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples)
            codec.queueInputBuffer(i, 0, samples.size * 2, audioStartUs + audioSamples * 1_000_000L / RATE, 0)
        }
        audioSamples += samples.size
        drain(codec, isVideo = false, ending = false)
    }

    /** Moves what an encoder has made into the muxer (once both tracks are known). */
    private fun drain(codec: MediaCodec, isVideo: Boolean, ending: Boolean) {
        val info = MediaCodec.BufferInfo()
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (ending) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!ending || ++waits > 50) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    val m = muxer ?: return
                    // A muxer takes no tracks once started (the clip went ahead
                    // without the late one): this stream is left out of it.
                    if (muxing) return@synchronized
                    if (isVideo) {
                        videoTrack = m.addTrack(codec.outputFormat)
                        videoFormatAt = System.nanoTime()
                    } else {
                        audioTrack = m.addTrack(codec.outputFormat)
                    }
                    if (videoTrack >= 0 && audioTrack >= 0) startMuxer()
                }
                o >= 0 -> {
                    val out = codec.getOutputBuffer(o)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && !config && info.size > 0) synchronized(lock) { write(isVideo, out, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun write(isVideo: Boolean, out: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (!muxing) return
        if (isVideo) {
            // A clip starts on a whole picture.
            if (!keyWritten && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) return
            keyWritten = true
            m.writeSampleData(videoTrack, out, info)
            wrote++
        } else if (audioTrack >= 0 && keyWritten) {
            m.writeSampleData(audioTrack, out, info)
        }
    }

    private fun startMuxer() {
        if (muxing) return
        muxer?.start()
        muxing = true
        // Frames made before now were dropped: start on a fresh key frame.
        runCatching { video?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Ends both streams and closes the file. Whether there is a clip. */
    private fun finish(): Boolean {
        sound.recordTap = null
        video?.let { v ->
            val i = v.dequeueInputBuffer(50_000)
            if (i >= 0) v.queueInputBuffer(i, 0, 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(v, isVideo = true, ending = true)
        }
        synchronized(lock) {
            audio?.let { a ->
                val i = a.dequeueInputBuffer(50_000)
                if (i >= 0) a.queueInputBuffer(i, 0, 0, audioStartUs.coerceAtLeast(0) + audioSamples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(a, isVideo = false, ending = true)
            }
            if (!muxing || wrote == 0) return false
            muxer?.stop()
            log("record: $wrote frames, ${audioSamples / RATE}s of sound")
            return true
        }
    }

    private fun release() {
        runCatching { video?.stop() }
        runCatching { video?.release() }
        video = null
        synchronized(lock) {
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            audio = null
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    companion object {
        /** As app.js VIDEO_MAX_MS. */
        const val MAX_MS = 20_000L
        private const val RATE = 48_000
        private const val VIDEO_BPS = 2_500_000
        private const val AUDIO_BPS = 96_000
        private const val NO_AUDIO_NS = 2_000_000_000L
        /** Room for a 1920×1088 picture either way up. */
        private const val MAX_FRAME_BYTES = 1920 * 1088 * 3 / 2
    }
}
