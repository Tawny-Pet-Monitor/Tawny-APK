package com.tawny.monitor

import android.app.Activity
import android.view.View

/**
 * The live screen of a call, as the shell sees it. Two implementations: the
 * web page in a WebView (public/app.js), and the native one built on the
 * Rust core (`nativeapp.NativeLivePage`, present only in builds that bundle
 * the core). Everything around the call (onboarding, the role choice, the
 * pairing sheet, the error screens) is the shell's own and is the same for
 * both: the page reports what happens through [LiveShell.post], with the
 * same events app.js has always sent (`live`, `idle`, `ended`, `watching`,
 * `waiting`, `chime`, `error`, `nostrKey`, …).
 */
interface LivePage {
    /** What to put on screen. */
    val view: View

    /** `tawnyStart()`: begin the call. */
    fun start(args: LiveStart)

    /** Monitor: the code in the QR changed (`tawnyPairCode`). */
    fun pairCode(code: String, expiresMs: Long)

    /** Monitor: this phone's battery (`tawnyBattery`), for the Viewers. */
    fun battery(pct: Int, charging: Boolean)

    /** The phone and window rotation, clockwise degrees (`tawnyOrientation`). */
    fun orientation(deviceCw: Int, windowCw: Int)

    /** Turn the torch off now (`tawnyTorchOff`). */
    fun torchOff()

    /** The system palette changed (`tawnySystemTheme`): "light" or "dark". */
    fun systemTheme(theme: String)

    /** Space the system bars take over the video, in dp. */
    fun safeInsets(top: Int, bottom: Int, left: Int, right: Int)

    /** The app came to the front (true) or went behind (false). */
    fun foreground(on: Boolean)

    /** The call is over and the screen is going away. */
    fun destroy()
}

/** Everything `tawnyStart()` is given. */
data class LiveStart(
    /** "station" (the Monitor) or "viewer". */
    val role: String,
    val key: String,
    val name: String,
    /** The LAN relay to dial (Viewer) or none. */
    val signal: String?,
    val rendezvous: String,
    val token: String?,
    val theme: String,
    val systemTheme: String,
    val motion: Boolean,
    val announce: Boolean,
    val myName: String,
    val lang: String,
    val pairCode: String?,
    val pairExpMs: Long,
    /** Viewer: the Monitor's Nostr key from the pairing. */
    val nostrStation: String?,
    /** The user's servers, as `serversJson()` writes them. */
    val servers: String,
    /** Monitor: the LAN relay port to listen on (the saved one); -1 for none. */
    val lanPort: Int = -1,
    /** Roles ("viewer", "station") whose first-call tour was already shown. */
    val coachSeen: Set<String> = emptySet(),
)

/** What a live page may ask of the shell (the WebView's `Bridge`). */
interface LiveShell {
    /** An app.js-style event: `{"event":"live"}`, `{"event":"watching","n":1}`, … */
    fun post(json: String)

    /** Dim mode: the backlight down (true) or back (false). */
    fun setDimmed(on: Boolean)

    /** Save a PNG snapshot to the gallery. */
    fun saveImage(png: ByteArray, filename: String)

    /** Save a short clip to the gallery. */
    fun saveVideo(bytes: ByteArray, filename: String, mime: String)
}

/** Makes a native live page, in builds that bundle the Rust core. */
interface LivePageFactory {
    fun create(activity: Activity, shell: LiveShell): LivePage

    /** Whether this page can run on this device (its engine loads). Never throws. */
    fun usable(activity: Activity): Boolean = true

    companion object {
        /**
         * The native factory if this build has one. Looked up by name because
         * it lives in the `native` source set, which other builds don't
         * compile; when the native engine becomes the only one this goes.
         */
        val native: LivePageFactory? by lazy {
            runCatching {
                Class.forName("com.tawny.monitor.nativeapp.NativeLivePages")
                    .getField("INSTANCE").get(null) as LivePageFactory
            }.getOrNull()
        }
    }
}
