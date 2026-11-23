package com.tawny.monitor.nativeapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/**
 * Brings over, once, what the JavaScript engine kept in its page's storage,
 * so updating to the native engine keeps every pairing: how each Viewer is
 * known to its Monitor (bond ids), the phones a Monitor knows, both ends'
 * Nostr keys and known peers, the Monitor key a Viewer was handed, and which
 * security codes were already checked.
 *
 * The page lived at `http://127.0.0.1:8809`, or the next free port up to
 * 8833 when that one was taken (the app's own loopback server), so the storage
 * of each of those origins is read by a WebView that is never shown and is
 * thrown away. That WebView never reaches the network: it answers its
 * one request itself with an empty page. (Loading from the port would let any
 * app that got to 127.0.0.1:8809 first serve a page into the origin that
 * holds the old secrets.) Nothing is deleted from the old storage and nothing
 * native is overwritten: a failure leaves both as they were, and it is tried
 * again on the next call. It counts as done only when every origin answered.
 */
object JsMigration {
    /** The ports the JS page may have been served from (LocalWeb's AssetHttpServer). */
    private val PORTS = 8809..8833
    private const val TIMEOUT_MS = 20_000L
    private fun page(port: Int) = "http://127.0.0.1:$port/tawny-import"
    private const val EMPTY = "<!doctype html><meta charset=utf-8><title>Tawny</title>"
    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** Runs the import if it hasn't run, then [done] (on the main thread). */
    @SuppressLint("SetJavaScriptEnabled")
    fun ensure(activity: Activity, store: NativeStore, log: (String) -> Unit, done: () -> Unit) {
        if (store.jsImported) { done(); return }
        val web = WebView(activity)
        val main = Handler(Looper.getMainLooper())
        val pages = PORTS.map(::page).toSet()
        var port = PORTS.first
        var brought = 0
        var unread = false
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            web.stopLoading()
            web.destroy()
            if (unread) {
                log("js import: not every old origin could be read; it is tried again next time")
            } else {
                store.jsImported = true
                log("js import: $brought item(s) brought over")
            }
            done()
        }
        fun next() {
            if (finished) return
            if (port > PORTS.last) finish() else web.loadUrl(page(port))
        }
        web.settings.javaScriptEnabled = true      // only for our one read below
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            // Every request is answered here; none goes out.
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse =
                if (req.url.toString() in pages) {
                    WebResourceResponse("text/html", "utf-8", EMPTY.byteInputStream())
                } else {
                    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                }

            override fun onPageFinished(view: WebView, url: String) {
                if (finished || url != page(port)) return
                view.evaluateJavascript(READ) { result ->
                    // A JSON string, or "null" when the read didn't run.
                    if (result == null || result == "null") {
                        unread = true
                    } else {
                        runCatching { brought += import(result, store) }
                            .onFailure { e -> unread = true; log("js import failed: ${e.message}") }
                    }
                    port++
                    next()
                }
            }
        }
        main.postDelayed({ if (!finished) { unread = true; log("js import: the old page storage didn't open"); finish() } }, TIMEOUT_MS)
        next()
    }

    /** Every `tawny.*` entry of the origin's localStorage, as one JSON object. */
    private const val READ = """(function () {
  var o = {};
  for (var i = 0; i < localStorage.length; i++) {
    var k = localStorage.key(i);
    if (k && k.indexOf('tawny.') === 0) o[k] = localStorage.getItem(k);
  }
  return JSON.stringify(o);
})()"""

    /**
     * Files the old entries in the native store. [result] is what
     * `evaluateJavascript` handed back: a JSON string holding the object.
     * Returns how many entries were brought over.
     */
    fun import(result: String, store: NativeStore): Int {
        val text = JSONTokener(result).nextValue() as? String
            ?: error("the read handed back no JSON string")
        val all = JSONObject(text)
        fun get(k: String): String? = all.optString(k, "").takeIf { it.isNotEmpty() }
        val channels = runCatching { JSONArray(get("tawny.channels.v1") ?: "[]") }.getOrDefault(JSONArray())
        var n = 0
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i) ?: continue
            val id = ch.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val key = ch.optString("key").takeIf { it.isNotEmpty() } ?: continue
            val room = roomId(key)

            get("tawny.bond.$id")?.lowercase()?.takeIf { HEX32.matches(it) }?.let { store.importBondId(key, it); n++ }
            get("tawny.nostrStationKey.$id")?.lowercase()?.takeIf { HEX64.matches(it) }
                ?.let { store.importStationNostrSecret(key, it); n++ }
            get("tawny.nostrKey.$room")?.lowercase()?.takeIf { HEX64.matches(it) }
                ?.let { store.importViewerNostrSecret(key, it); n++ }
            get("tawny.nostrStation.$id")?.lowercase()?.takeIf { HEX64.matches(it) }?.let {
                if (store.monitorNostr(key) == null) { store.setMonitorNostr(key, it); n++ }
            }
            // A Monitor's phones, oldest first: the gate it admits them by.
            val paired = pubs(get("tawny.paired.$id"), HEX32)
            if (paired.isNotEmpty() && store.gate(key).isEmpty()) {
                store.setGate(key, JSONObject().put("code", JSONObject.NULL).put("known", JSONArray(paired)).toString())
                n++
            }
            val peers = pubs(get("tawny.nostrPeers.$room"), HEX64)
            if (peers.isNotEmpty() && store.nostrKnown(key).isEmpty()) { store.setNostrKnown(key, peers); n++ }
            // Codes already checked: on a Viewer, its Monitor's; on a Monitor, per phone.
            if (get("tawny.sasok.$id") != null && !store.sasConfirmed(key)) { store.confirmSas(key); n++ }
            val prefix = "tawny.sasok.$id."
            all.keys().asSequence().filter { it.startsWith(prefix) }.forEach { k ->
                val pid = k.removePrefix(prefix).lowercase()
                if (HEX32.matches(pid) && !store.viewerSasOk(key, pid)) { store.markViewerSasOk(key, pid); n++ }
            }
        }
        return n
    }

    private fun pubs(json: String?, shape: Regex): List<String> =
        runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).lowercase().takeIf { s -> shape.matches(s) } }
        }

    /** The relay room id the JS engine filed per-room entries under (core `ids::room_id`). */
    fun roomId(key: String): String =
        MessageDigest.getInstance("SHA-256").digest("tawny-room-v1|$key".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
}
