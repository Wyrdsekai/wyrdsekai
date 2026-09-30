package org.wyrdsekai.app.engine

import org.wyrdsekai.app.engine.discovery.PhoneInvite
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The invite fields of D1: the home's tunnel key
 * `zk`, the household CA fingerprint `home_ca_fp` and the home's HTTPS address
 * `lan_https`, read the same way from a QR code, a link or a paste.
 */
@OptIn(ExperimentalEncodingApi::class)
class PhoneInviteSecurityTest {

    private fun encode(json: String): String =
        "wyrdphone://relay.example:4443/" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    private val zk = "3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08" // RFC 7748 Bob's public key
    private val caFp = "A1:B2:" + "c3".repeat(30)
    private val relay = """{"ws_url":"wss://relay.example:4443","nats_user":"relay_phone_x","nats_password":"pw"}"""

    @Test
    fun readsTheHomesKeyFingerprintAndAddress() {
        val invite = PhoneInvite.parse(encode(
            """{"kind":"phone","relays":[$relay],"zone_id":"z1","zk":"$zk","home_ca_fp":"$caFp","lan_https":"https://198.51.100.5:7443/"}"""))
        assertEquals(zk, invite.zk)
        assertEquals("a1b2" + "c3".repeat(30), invite.homeCaFp)
        assertEquals("https://198.51.100.5:7443", invite.lanHttps)
    }

    @Test
    fun readsTheHomesBusApartFromTheRelay() {
        val invite = PhoneInvite.parse(encode(
            """{"kind":"phone","relays":[$relay],"zone_id":"z1","zk":"$zk","home_ca_fp":"$caFp","lan_https":"https://192.0.2.105:27443","home_bus":"wss://192.0.2.105:27223"}"""))
        assertEquals("wss://192.0.2.105:27223", invite.homeBus)
        // The relay stays the invite's relay entry; the bus never stands in for it.
        assertEquals("wss://relay.example:4443", invite.relays.single().wsUrl)
    }

    @Test
    fun aPlainOrLoopbackHomeBusIsNotKept() {
        for (bus in listOf("ws://192.0.2.105:4223", "nats://127.0.0.1:4222", "wss://127.0.0.1:27223")) {
            val invite = PhoneInvite.parse(encode(
                """{"kind":"phone","relays":[$relay],"zone_id":"z1","home_ca_fp":"$caFp","lan_https":"https://192.0.2.105:27443","home_bus":"$bus"}"""))
            assertNull(invite.homeBus, bus)
        }
    }

    @Test
    fun aHomeNetworkInviteNeedsNoRelay() {
        val invite = PhoneInvite.parse(encode(
            """{"kind":"phone","relays":[],"zone_id":"z1","zk":"$zk","home_ca_fp":"$caFp","lan_https":"https://home.lan:7443"}"""))
        assertTrue(invite.relays.isEmpty())
        assertEquals("https://home.lan:7443", invite.lanHttps)
    }

    @Test
    fun anInviteFromBefore050StillReads() {
        val invite = PhoneInvite.parse(encode("""{"kind":"phone","relays":[$relay],"zone_id":"z1"}"""))
        assertNull(invite.zk)
        assertNull(invite.homeCaFp)
        assertNull(invite.lanHttps)
        assertNull(invite.homeBus)
    }

    @Test
    fun malformedSecurityFieldsAreRefused() {
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("""{"kind":"phone","relays":[$relay],"zk":"c2hvcnQ"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("""{"kind":"phone","relays":[$relay],"home_ca_fp":"abcd"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("""{"kind":"phone","relays":[$relay],"home_ca_fp":"$caFp","lan_https":"http://198.51.100.5:7070"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            PhoneInvite.parse(encode("""{"kind":"phone","relays":[],"lan_https":"https://home.lan:7443"}"""))
        }
    }
}
