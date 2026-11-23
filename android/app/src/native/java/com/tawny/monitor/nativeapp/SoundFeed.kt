package com.tawny.monitor.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.tawny.core.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Monitor's sound, through the app rather than libwebrtc's audio module.
 *
 * Mic: `AudioRecord` on the voice-call source (the platform's echo cancelling
 * and noise suppression apply), 10 ms at a time into the core. It runs only
 * while someone is watching, like the camera.
 *
 * Speaker: an `AudioTrack` on the loudspeaker. On the Monitor it plays what
 * Viewers say, only while one of them is talking; on a Viewer, the Monitor's
 * room while the call is up.
 */
class SoundFeed(context: Context, private val log: (String) -> Unit) {
    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var micOn = false
    @Volatile private var talkOn = false  // the speaker is playing
    private val levelNow = MutableStateFlow(0f)

    /** How loud the far end is, 0..1, for the rail's meter (about 20 updates a second). */
    val level: StateFlow<Float> = levelNow

    /** Listen muted: the far end is still read (and metered), not played. */
    @Volatile var speakerMuted = false
    private var micThread: Thread? = null
    private var talkThread: Thread? = null

    /** Requires RECORD_AUDIO (checked by the service before it asks). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startMic(onError: (String) -> Unit) {
        if (micOn) return
        micOn = true
        micThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min, FRAME * 2 * 4),
                )
            } catch (e: Exception) {
                onError("microphone: ${e.message ?: e.javaClass.simpleName}"); micOn = false; return@Thread
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                onError("microphone unavailable"); rec.release(); micOn = false; return@Thread
            }
            val buf = ShortArray(FRAME)
            try {
                rec.startRecording()
                while (micOn) {
                    var got = 0
                    while (got < FRAME && micOn) {
                        val n = rec.read(buf, got, FRAME - got)
                        if (n < 0) { onError("microphone read failed ($n)"); micOn = false; break }
                        got += n
                    }
                    if (got == FRAME) NativeBridge.pushMic(buf)
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "tawny-mic").apply { start() }
    }

    @Synchronized
    fun stopMic() {
        micOn = false
        micThread?.join(500)
        micThread = null
    }

    /** Gets every 10 ms of the far end's sound while set (a recording). */
    @Volatile var recordTap: ((ShortArray) -> Unit)? = null

    @Synchronized
    fun startSpeaker() {
        if (talkOn) return
        talkOn = true
        speaker(true)
        talkThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buf = ShortArray(FRAME)
            var frames = 0
            var voiced = 0
            var peak = 0
            try {
                track.play()
                // write() blocks once the track's buffer is full, which paces this loop.
                while (talkOn) {
                    frames++
                    if (NativeBridge.readSpeaker(buf) > 0 && buf.any { it > 500 || it < -500 }) voiced++
                    for (v in buf) { val a = if (v < 0) -v else v.toInt(); if (a > peak) peak = a }
                    if (frames % 5 == 0) { levelNow.value = (peak / 32768f).coerceIn(0f, 1f); peak = 0 }
                    // A clip records the room whether or not this phone is listening.
                    // A recorder fault ends the clip's sound, never the call
                    // (an uncaught throw here would end the whole app).
                    recordTap?.let { tap ->
                        try {
                            tap(buf)
                        } catch (t: Throwable) {
                            recordTap = null
                            log("recording: the sound stopped (${t.javaClass.simpleName})")
                        }
                    }
                    if (speakerMuted) buf.fill(0)
                    track.write(buf, 0, FRAME)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                levelNow.value = 0f
                log("speaker: ${frames / 100.0}s played, ${if (frames > 0) voiced * 100 / frames else 0}% with sound")
            }
        }, "tawny-talk").apply { start() }
    }

    @Synchronized
    fun stopSpeaker() {
        if (!talkOn) return
        talkOn = false
        talkThread?.join(500)
        talkThread = null
        speaker(false)
    }

    fun close() {
        stopMic()
        stopSpeaker()
    }

    /** Voice-call mode, out of the loudspeaker (the platform's echo cancelling needs call mode). */
    private fun speaker(on: Boolean) {
        val am = am ?: return
        if (on) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private companion object {
        const val RATE = 48_000
        const val FRAME = 480
    }
}
