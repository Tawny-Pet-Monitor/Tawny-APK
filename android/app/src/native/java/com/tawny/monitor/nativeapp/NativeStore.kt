package com.tawny.monitor.nativeapp

import android.content.Context
import android.content.SharedPreferences
import com.tawny.core.newBondId
import com.tawny.core.newNostrSecret
import java.security.MessageDigest

/**
 * What the native engine keeps that the shell's own prefs ("tawny") don't:
 * the Monitor's gate (phones it knows), relay health, its Nostr secret per
 * channel, and on a Viewer its bond id and Nostr secret per channel. Secrets
 * are sealed with [SecretBox]; the file stays out of cloud backup
 * (res/xml/backup_rules.xml).
 */
class NativeStore(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("tawny_native", Context.MODE_PRIVATE)

    /** This Monitor's Nostr secret for the channel with [key]. */
    fun stationNostrSecret(key: String): String = secret("stationNostr:" + tag(key)) { newNostrSecret() }

    /** This phone's Nostr secret as a Viewer of the channel with [key]. */
    fun viewerNostrSecret(key: String): String = secret("viewerNostr:" + tag(key)) { newNostrSecret() }

    /** How this phone is known to the Monitor of [key] (32 hex). */
    @Synchronized
    fun bondId(key: String): String {
        val name = "pid:" + tag(key)
        return p.getString(name, null) ?: newBondId().also { p.edit().putString(name, it).apply() }
    }

    /** Whether the user already said this Monitor's security code looked right. */
    fun sasConfirmed(key: String) = p.getBoolean("sasOk:" + tag(key), false)

    fun confirmSas(key: String) = p.edit().putBoolean("sasOk:" + tag(key), true).apply()

    /** Monitor: the user said this phone's code looked right (by its bond id). */
    fun viewerSasOk(key: String, pid: String) = p.getBoolean("sasOk:" + tag(key) + ":" + pid, false)

    fun markViewerSasOk(key: String, pid: String) = p.edit().putBoolean("sasOk:" + tag(key) + ":" + pid, true).apply()

    /** Viewer: the Monitor's Nostr public key, as it handed it over (meta `np`). */
    fun monitorNostr(key: String): String? = p.getString("monitorNostr:" + tag(key), null)

    fun setMonitorNostr(key: String, pub: String) = p.edit().putString("monitorNostr:" + tag(key), pub).apply()

    fun gate(key: String): String = p.getString("gate:" + tag(key), "") ?: ""

    fun setGate(key: String, json: String) = p.edit().putString("gate:" + tag(key), json).apply()

    var relayHealth: String
        get() = p.getString(HEALTH, "") ?: ""
        set(v) = p.edit().putString(HEALTH, v).apply()

    fun nostrKnown(key: String): List<String> =
        p.getString("known:" + tag(key), "")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    fun setNostrKnown(key: String, v: List<String>) = p.edit().putString("known:" + tag(key), v.joinToString(",")).apply()

    // ---- what the JS app kept, brought over once (JsMigration)

    /** Whether anything is stored for [key] yet: an import never overwrites. */
    fun has(what: String, key: String) = p.contains("$what:" + tag(key))

    fun importStationNostrSecret(key: String, hex: String) = importSecret("stationNostr:" + tag(key), hex)

    fun importViewerNostrSecret(key: String, hex: String) = importSecret("viewerNostr:" + tag(key), hex)

    fun importBondId(key: String, pid: String) {
        if (!p.contains("pid:" + tag(key))) p.edit().putString("pid:" + tag(key), pid).apply()
    }

    @Synchronized
    private fun importSecret(name: String, hex: String) {
        if (!p.contains(name)) p.edit().putString(name, SecretBox.seal(hex)).apply()
    }

    /** Whether the one-time import from the JS app has run. */
    var jsImported: Boolean
        get() = p.getBoolean(JS_IMPORTED, false)
        set(v) = p.edit().putBoolean(JS_IMPORTED, v).apply()

    /**
     * A sealed secret: opened, or minted and sealed if there is none. One that
     * won't open (the Keystore key was lost, e.g. a restore onto another
     * phone) is replaced rather than guessed at.
     */
    @Synchronized
    private fun secret(name: String, mint: () -> String): String {
        p.getString(name, null)?.let { s -> SecretBox.open(s)?.let { return it } }
        return mint().also { p.edit().putString(name, SecretBox.seal(it)).apply() }
    }

    /** A channel's entries are filed under a hash of its key, never the key. */
    private fun tag(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(("tawny-native-store|$key").toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    private companion object {
        const val HEALTH = "relayHealth"
        const val JS_IMPORTED = "jsImported.v1"
    }
}
