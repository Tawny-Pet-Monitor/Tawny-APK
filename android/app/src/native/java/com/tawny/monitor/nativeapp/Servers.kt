package com.tawny.monitor.nativeapp

import com.tawny.core.IceServerSpec
import com.tawny.monitor.BuildConfig
import org.json.JSONObject

/** The user's server choices, as the shell's `serversJson()` hands them over. */
data class Servers(
    val nostrRelays: List<String>,
    /** Pair over Nostr only: no LAN relay, no rendezvous. */
    val nostrOnly: Boolean,
    val ice: List<IceServerSpec>,
    /**
     * The build's rendezvous, kept behind the user's own (the core gives up on
     * that one if it won't let us in). Only sent when the user named a relay,
     * and never under tighter privacy.
     */
    val rendezvousFallback: String? = null,
) {
    companion object {
        private val STUN_DEFAULT = listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")

        fun parse(json: String): Servers {
            val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
            val strict = o.optBoolean("strict")
            val relays = o.optJSONArray("nostr")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.filter { it.matches(Regex("^wss?://\\S+$")) }
                ?: if (strict) emptyList()
                else BuildConfig.NOSTR_RELAYS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val stun = o.optJSONArray("stun")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: if (strict) emptyList() else STUN_DEFAULT
            val ice = buildList {
                if (stun.isNotEmpty()) add(IceServerSpec(stun, "", ""))
                o.optJSONArray("turn")?.let { a ->
                    for (i in 0 until a.length()) {
                        val t = a.optJSONObject(i) ?: continue
                        val urls = t.optJSONArray("urls")?.let { u -> (0 until u.length()).map { u.optString(it) } } ?: continue
                        add(IceServerSpec(urls, t.optString("username"), t.optString("credential", t.optString("password"))))
                    }
                }
            }
            val fallback = o.optString("fallback").takeIf { !strict && it.matches(Regex("^wss?://\\S+$")) }
            return Servers(relays, o.optBoolean("nostrOnly"), ice, fallback)
        }
    }
}
