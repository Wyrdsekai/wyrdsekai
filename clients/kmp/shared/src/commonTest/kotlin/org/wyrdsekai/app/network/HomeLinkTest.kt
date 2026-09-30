package org.wyrdsekai.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On the home network the phone uses the invite's https address and the home's
 * bus on wss; plain http/ws only to this device. A phone paired before home TLS
 * does not fall back to its plain http address ( W2).
 */
class HomeLinkTest {

    private val lan = "https://198.51.100.5:7443"
    private val fp = "ab".repeat(32)

    @Test
    fun plainTrafficIsAllowedOnlyToThisDevice() {
        assertTrue(HomeLink.isPlaintextOffDevice("http://198.51.100.5:7070"))
        assertTrue(HomeLink.isPlaintextOffDevice("198.51.100.5:7070"))
        assertTrue(HomeLink.isPlaintextOffDevice("ws://home.lan:4223"))
        assertTrue(HomeLink.isPlaintextOffDevice("nats://home.lan:4222"))
        assertFalse(HomeLink.isPlaintextOffDevice("https://198.51.100.5:7443"))
        assertFalse(HomeLink.isPlaintextOffDevice("wss://relay.example:4443"))
        assertFalse(HomeLink.isPlaintextOffDevice("http://localhost:7070"))
        assertFalse(HomeLink.isPlaintextOffDevice("localhost:7070"))
        assertFalse(HomeLink.isPlaintextOffDevice("http://127.0.0.1:7070/api"))
        assertFalse(HomeLink.isPlaintextOffDevice("ws://[::1]:4223"))
        // A name that starts like a loopback address can point anywhere.
        assertTrue(HomeLink.isPlaintextOffDevice("http://127.example.org:7070"))
        assertTrue(HomeLink.isPlaintextOffDevice("http://127.0.0.1.example.org:7070"))
    }

    @Test
    fun theInvitesHttpsAddressWinsOnTheHomeNetwork() {
        assertEquals(lan, HomeLink.homeBase("http://198.51.100.5:7070", lan, fp))
        assertEquals(lan, HomeLink.homeBase(null, "$lan/", fp))
    }

    @Test
    fun aPlainHomeAddressFromBeforeHomeTlsIsNotUsed() {
        assertNull(HomeLink.homeBase("http://198.51.100.5:7070", null, null))
        assertTrue(HomeLink.needsRepairForLan("http://198.51.100.5:7070", null, null))
        // Without the fingerprint the https address cannot be pinned, so it is not used either.
        assertNull(HomeLink.homeBase("http://198.51.100.5:7070", lan, null))
    }

    @Test
    fun encryptedAndLocalAddressesStay() {
        assertEquals("https://home.example", HomeLink.homeBase("https://home.example", null, null))
        assertEquals("localhost:7070", HomeLink.homeBase("localhost:7070", null, null))
        assertFalse(HomeLink.needsRepairForLan("wss://relay.example:4443", null, null))
        assertFalse(HomeLink.needsRepairForLan(null, null, null))
    }

    @Test
    fun theHomesBusIsTheAddressTheHomeNamed() {
        // The invite's home_bus / the pairing reply's natsUrl: a home on another bus port.
        assertEquals("wss://192.0.2.105:27223", HomeLink.homeBusUrl("wss://192.0.2.105:27223", "https://192.0.2.105:27443"))
        assertEquals("wss://192.0.2.105:27223", HomeLink.homeBusUrl("wss://192.0.2.105:27223/", null))
    }

    @Test
    fun withoutANamedBusTheDefaultPortOnTheLanHostIsTheFallback() {
        assertEquals("wss://198.51.100.5:4223", HomeLink.homeBusUrl(null, lan))
        assertEquals("wss://home.lan:4223", HomeLink.homeBusUrl(null, "https://home.lan:7443"))
        // A named bus the phone cannot use (plain, or the home's own loopback) falls back too.
        assertEquals("wss://198.51.100.5:4223", HomeLink.homeBusUrl("ws://198.51.100.5:4223", lan))
        assertEquals("wss://198.51.100.5:4223", HomeLink.homeBusUrl("nats://127.0.0.1:4222", lan))
        assertNull(HomeLink.homeBusUrl(null, null))
        assertNull(HomeLink.homeBusUrl("nats://127.0.0.1:27222", null))
    }

    @Test
    fun aBusAddressIsWssToAnotherMachineOnly() {
        assertEquals("wss://198.51.100.5:27223", HomeLink.usableHomeBus("wss://198.51.100.5:27223"))
        assertNull(HomeLink.usableHomeBus("ws://198.51.100.5:4223"))
        assertNull(HomeLink.usableHomeBus("nats://198.51.100.5:4222"))
        assertNull(HomeLink.usableHomeBus("nats://127.0.0.1:27222"))
        assertNull(HomeLink.usableHomeBus("wss://127.0.0.1:27223"))
        assertNull(HomeLink.usableHomeBus("wss://localhost:4223"))
        assertNull(HomeLink.usableHomeBus("wss://[::1]:4223"))
        assertNull(HomeLink.usableHomeBus(""))
        assertNull(HomeLink.usableHomeBus(null))
    }

    @Test
    fun aRelayAddressIsNeverLoopbackOrPlain() {
        assertEquals("wss://192.0.2.105:24443", HomeLink.usableRelayUrl("wss://192.0.2.105:24443"))
        assertEquals("wss://relay.example:4443", HomeLink.usableRelayUrl("wss://relay.example:4443/"))
        // What an older app kept from a pairing reply: the home's own loopback bus.
        assertNull(HomeLink.usableRelayUrl("nats://127.0.0.1:4222"))
        assertNull(HomeLink.usableRelayUrl("ws://127.0.0.1:4223"))
        assertNull(HomeLink.usableRelayUrl("wss://localhost:4443"))
        assertNull(HomeLink.usableRelayUrl("ws://192.0.2.105:4223"))
        assertNull(HomeLink.usableRelayUrl("nats://192.0.2.105:4222"))
        assertNull(HomeLink.usableRelayUrl("https://relay.example"))
        assertNull(HomeLink.usableRelayUrl(null))
    }

    @Test
    fun deviceLoopbackIsLiteralLoopbackOnly() {
        assertTrue(HomeLink.isDeviceLoopback("127.0.0.1"))
        assertTrue(HomeLink.isDeviceLoopback("127.4.5.6"))
        assertTrue(HomeLink.isDeviceLoopback("localhost"))
        assertTrue(HomeLink.isDeviceLoopback("[::1]"))
        assertFalse(HomeLink.isDeviceLoopback("10.0.2.2"))
        assertFalse(HomeLink.isDeviceLoopback("127.example.org"))
        assertFalse(HomeLink.isDeviceLoopback("192.0.2.105"))
    }
}
