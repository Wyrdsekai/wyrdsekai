package org.wyrdsekai.app.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Round trips with fresh keys, and every refusal the protocol promises. */
class SealedRoundTripTest {

    private val session = "0123456789abcdef0123456789abcdef"
    private val up = "wyrd.tunnel.z.$session.up".encodeToByteArray()
    private val down = "wyrd.tunnel.z.$session.down".encodeToByteArray()

    private fun pair(): Pair<SealedTunnel.Keys, SealedTunnel.Keys> {
        val zone = X25519KeyPair.generate()
        val zoneEph = X25519KeyPair.generate()
        val phone = X25519KeyPair.generate()
        return SealedTunnel.complete(phone, zone.pub, zoneEph.pub, session) to TestHome.accept(zone, zoneEph, phone.pub, session)
    }

    @Test
    fun framesRoundTripBothWaysWithCountersPerDirection() {
        val (phone, home) = pair()
        for (i in 0 until 5) {
            assertEquals("up $i", home.up.open(phone.up.seal("up $i".encodeToByteArray(), up), up).decodeToString())
        }
        assertEquals("down", phone.down.open(home.down.seal("down".encodeToByteArray(), down), down).decodeToString())
        assertEquals(5, phone.up.counter)
        assertEquals(1, phone.down.counter)
    }

    @Test
    fun aFrameOutOfOrderIsRefused() {
        val (phone, home) = pair()
        phone.up.seal("first".encodeToByteArray(), up)
        val second = phone.up.seal("second".encodeToByteArray(), up)
        assertFailsWith<SealedException> { home.up.open(second, up) }
    }

    @Test
    fun aReplayedFrameIsRefused() {
        val (phone, home) = pair()
        val f = phone.up.seal("once".encodeToByteArray(), up)
        home.up.open(f, up)
        assertFailsWith<SealedException> { home.up.open(f, up) }
    }

    @Test
    fun aFrameMovedToAnotherSubjectIsRefused() {
        val (phone, home) = pair()
        val f = phone.up.seal("x".encodeToByteArray(), up)
        assertFailsWith<SealedException> { home.up.open(f, "wyrd.tunnel.z.other0000000000000.up".encodeToByteArray()) }
    }

    @Test
    fun anAlteredFrameIsRefused() {
        val (phone, home) = pair()
        val f = phone.up.seal("hello".encodeToByteArray(), up)
        f[f.size - 1] = (f[f.size - 1].toInt() xor 1).toByte()
        assertFailsWith<SealedException> { home.up.open(f, up) }
    }

    @Test
    fun aRelayStandingInForTheHomeCannotOpenTheFrames() {
        val zone = X25519KeyPair.generate()
        val relay = X25519KeyPair.generate()
        val relayEph = X25519KeyPair.generate()
        val phone = X25519KeyPair.generate()
        // The relay answers the open with its own ephemeral key but does not hold zk's private half.
        val phoneKeys = SealedTunnel.complete(phone, zone.pub, relayEph.pub, session)
        val relayKeys = TestHome.accept(relay, relayEph, phone.pub, session)
        assertFailsWith<SealedException> { relayKeys.up.open(phoneKeys.up.seal("{\"token\":\"t\"}".encodeToByteArray(), up), up) }
    }

    @Test
    fun aLowOrderPublicKeyIsRefused() {
        assertFailsWith<SealedException> { SealCrypto.x25519(X25519KeyPair.generate().priv, ByteArray(32)) }
        assertFailsWith<SealedException> { SealCrypto.x25519(ByteArray(32), ByteArray(31)) }
    }

    @Test
    fun eachRequestHasAFreshEphemeralKeyAndNonce() {
        val zone = X25519KeyPair.generate()
        val a = SealedRequest.seal(zone.pub, "wyrd.zone.z.mcp.login", "{}", 1)
        val b = SealedRequest.seal(zone.pub, "wyrd.zone.z.mcp.login", "{}", 1)
        assertNotEquals(a.wire, b.wire)
    }

    @Test
    fun aRequestRoundTripsThroughTheHome() {
        val zone = X25519KeyPair.generate()
        val subject = "wyrd.zone.z.study.journal"
        val sealed = SealedRequest.seal(zone.pub, subject, "{\"token\":\"t\",\"content\":\"dear diary\"}", 42)
        val opened = TestHome.openRequest(zone.priv, subject, sealed.wire)
        assertEquals("{\"ts\":42,\"body\":{\"token\":\"t\",\"content\":\"dear diary\"}}", opened.plain)
        assertEquals("{\"ok\":true}", sealed.openReply(opened.sealReply("{\"ok\":true}")))
    }

    @Test
    fun aRequestSealedToAnotherHomeDoesNotOpen() {
        val subject = "wyrd.zone.z.mcp.login"
        val sealed = SealedRequest.seal(X25519KeyPair.generate().pub, subject, "{}", 1)
        assertFailsWith<SealedException> { TestHome.openRequest(X25519KeyPair.generate().priv, subject, sealed.wire) }
    }

    @Test
    fun anUnsealedSuccessReplyIsRefused() {
        val sealed = SealedRequest.seal(X25519KeyPair.generate().pub, "wyrd.zone.z.mcp.login", "{}", 1)
        assertFailsWith<SealedException> { sealed.openReply("{\"ok\":true,\"token\":\"forged\"}") }
    }

    @Test
    fun aReplySealedForAnotherRequestIsRefused() {
        val zone = X25519KeyPair.generate()
        val subject = "wyrd.zone.z.mcp.login"
        val mine = SealedRequest.seal(zone.pub, subject, "{}", 1)
        val other = SealedRequest.seal(zone.pub, subject, "{}", 1)
        val replyToOther = TestHome.openRequest(zone.priv, subject, other.wire).sealReply("{\"ok\":true}")
        assertFailsWith<SealedException> { mine.openReply(replyToOther) }
    }

    @Test
    fun aReplyMovedToAnotherSubjectIsRefused() {
        val zone = X25519KeyPair.generate()
        val sealed = SealedRequest.seal(zone.pub, "wyrd.zone.z.mcp.login", "{}", 1)
        val opened = TestHome.openRequest(zone.priv, "wyrd.zone.z.mcp.login", sealed.wire)
        assertFailsWith<SealedException> { sealed.openReply(opened.sealReply("{\"ok\":true}", "wyrd.zone.z.mcp.tell")) }
    }

    @Test
    fun theHomesClearRefusalsPassThrough() {
        val sealed = SealedRequest.seal(X25519KeyPair.generate().pub, "wyrd.zone.z.mcp.login", "{}", 1)
        assertEquals("{\"ok\":false,\"error\":\"sealed_refused\"}", sealed.openReply("{\"ok\":false,\"error\":\"sealed_refused\"}"))
        assertEquals("{\"ok\":false,\"error\":\"sealed_required\"}", sealed.openReply("{\"ok\":false,\"error\":\"sealed_required\"}"))
        assertFailsWith<SealedException> { sealed.openReply("{\"ok\":false,\"error\":\"invalid_credentials\"}") }
    }

    @Test
    fun zoneKeysDecodeFromInvites() {
        val k = X25519KeyPair.generate().pub
        assertContentEquals(k, decodeZoneKey(b64url(k)))
        assertEquals(null, decodeZoneKey("short"))
        assertEquals(null, decodeZoneKey(null))
    }
}
