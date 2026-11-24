package com.tawny.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The diagnostics log is the one a user may choose to send. The privacy
 * policy says it holds hashed room identifiers and connection events, never
 * a key or an IP address: the lines below are real ones from the native
 * engine and the page.
 */
class DiagRedactTest {
    private fun r(s: String) = Diag.redact(s)

    @Test fun addressesAreMasked() {
        assertEquals("dial lan <lan-ip>:43377 room=ab12cd34ef56ab78", r("dial lan 192.168.1.34:43377 room=ab12cd34ef56ab78"))
        assertEquals("stun answer <ip>", r("stun answer 8.8.8.8"))
        assertEquals("page at 127.0.0.1:8809", r("page at 127.0.0.1:8809"))
    }

    @Test fun theHashedRoomIdStays() {
        assertEquals("cloud welcome room=9f3a1c0de4b5a6c7 peers=1", r("cloud welcome room=9f3a1c0de4b5a6c7 peers=1"))
    }

    @Test fun peerIdsAndKeysGo() {
        assertEquals("call with <id>: Disconnected", r("call with 3f9c2a7be41d: Disconnected"))
        assertEquals("nostr open as <id> on 3 relay(s)", r("nostr open as e8a05f74890e on 3 relay(s)"))
        assertEquals("nostr: viewer <id> went quiet", r("nostr: viewer c3400f6e went quiet"))
        assertEquals(
            "offer refused from <id>: unsigned on the LAN",
            r("offer refused from 0827bcca44acada87864ff90d708e3913cf3bb6f42aae07f58cb2c5eaf03e510: unsigned on the LAN"),
        )
    }

    @Test fun numbersAndCodesStay() {
        assertEquals("camera 960x540: 20.0 fps in, 20.0 fps sent", r("camera 960x540: 20.0 fps in, 20.0 fps sent"))
        assertEquals("pairing code rotated — 600s", r("pairing code rotated — 600s"))
        assertEquals("expires 1795454097", r("expires 1795454097"))
        assertEquals("── launched v1.0.5 (48)", r("── launched v1.0.5 (48)"))
    }

    @Test fun linkSecretsGo() {
        val out = r("adopt tawny://pair?k=Zx9_-abcDEF0123456789&n=Pet&t=NvM7UXwo8X79&c=WXXHeH07nDk&np=e8a05f74890e")
        assertFalse(out, out.contains("Zx9_"))
        assertFalse(out, out.contains("NvM7"))
        assertFalse(out, out.contains("WXXH"))
        assertFalse(out, out.contains("e8a05f"))
    }

    @Test fun namesGo() {
        assertEquals(
            "pair accepted name=<name> lan=- relay=- ticket=yes code=yes",
            r("pair accepted name=\"Mochi\" lan=- relay=- ticket=yes code=yes"),
        )
        assertEquals(
            "control: {\"type\":\"meta\",\"name\":\"<name>\",\"from\":\"<id>\"}",
            r("control: {\"type\":\"meta\",\"name\":\"Pp\",\"from\":\"c3400f6e8186\"}"),
        )
    }
}
