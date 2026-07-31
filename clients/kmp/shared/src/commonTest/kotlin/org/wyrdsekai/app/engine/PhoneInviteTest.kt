package org.wyrdsekai.app.engine

import org.wyrdsekai.app.engine.discovery.PhoneInvite
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wyrdphone:// invite parsing. Fixtures mirror
 * what registration.py mint_phone_invite emits (compact JSON, base64url,
 * no padding).
 */
@OptIn(ExperimentalEncodingApi::class)
class PhoneInviteTest {

    private fun encode(host: String, json: String): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(json.encodeToByteArray())
        return "wyrdphone://$host/$b64"
    }

    private val selfSignedJson = """
        {"household_id":"hh-9ce1d3b57ebf","kind":"phone","minted_at":1781225931,
         "relays":[{"ca_fp":"E5:F0:A2:3E","fp":"63:24:CB:6B",
                    "nats_password":"pw-secret","nats_user":"relay_phone",
                    "ws_url":"wss://127.0.0.1:4443"}],
         "v":1,"zone_id":"unspecified"}
    """.trimIndent()

    @Test
    fun detectsInviteUrls() {
        assertTrue(PhoneInvite.isPhoneInviteUrl("  wyrdphone://x/abc "))
        assertTrue(PhoneInvite.isPhoneInviteUrl("WYRDPHONE://x/abc"))
        assertFalse(PhoneInvite.isPhoneInviteUrl("wyrdrelay://x/abc"))
        assertFalse(PhoneInvite.isPhoneInviteUrl("https://example.org"))
    }

    @Test
    fun parsesSelfSignedInvite() {
        val invite = PhoneInvite.parse(encode("127.0.0.1:4443", selfSignedJson))
        assertEquals(1, invite.relays.size)
        val r = invite.relays[0]
        assertEquals("wss://127.0.0.1:4443", r.wsUrl)
        assertEquals("relay_phone", r.natsUser)
        assertEquals("pw-secret", r.natsPassword)
        assertEquals("63:24:CB:6B", r.fp)
        assertEquals("E5:F0:A2:3E", r.caFp)
        assertEquals("hh-9ce1d3b57ebf", invite.householdId)
        assertEquals(1781225931L, invite.mintedAt)
        // "unspecified" sentinel maps to null.
        assertNull(invite.zoneId)
    }

    @Test
    fun parsesAcmeInviteWithoutPinMaterial() {
        val invite = PhoneInvite.parse(encode("relay.example.org",
            """{"kind":"phone","relays":[{"nats_password":"pw",
                "nats_user":"relay_phone","ws_url":"wss://relay.example.org"}],"v":1}"""))
        assertNull(invite.relays[0].fp)
        assertNull(invite.relays[0].caFp)
    }

    @Test
    fun preservesFailoverOrdering() {
        val invite = PhoneInvite.parse(encode("a",
            """{"kind":"phone","relays":[
                {"nats_password":"p1","nats_user":"u1","ws_url":"wss://first"},
                {"nats_password":"p2","nats_user":"u2","ws_url":"wss://second"}],"v":1}"""))
        assertEquals(listOf("wss://first", "wss://second"), invite.relays.map { it.wsUrl })
    }

    @Test
    fun rejectsMalformedInput() {
        assertFailsWith<IllegalArgumentException> { PhoneInvite.parse("https://nope") }
        assertFailsWith<IllegalArgumentException> { PhoneInvite.parse("wyrdphone://host-only") }
        assertFailsWith<IllegalArgumentException> { PhoneInvite.parse("wyrdphone://h/!!notb64!!") }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("h", """{"kind":"zone","relays":[],"v":1}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("h", """{"kind":"phone","relays":[],"v":1}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("h",
                """{"kind":"phone","relays":[{"ws_url":"wss://x"}],"v":1}"""))
        }
    }
}
