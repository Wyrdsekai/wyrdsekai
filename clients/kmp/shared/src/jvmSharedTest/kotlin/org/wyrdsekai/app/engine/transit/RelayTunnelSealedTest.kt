package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.crypto.FakeSealedHome
import org.wyrdsekai.app.crypto.X25519KeyPair
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The phone terminal's session through the relay is sealed end to end
 * ( W3): the token rides the first sealed frame up
 * frames sent before the handshake are held and sealed in order, and the
 * relay sees no token and no session text.
 */
class RelayTunnelSealedTest {

    private val session = "0123456789abcdef0123456789abcdef"

    /** A relay that routes but holds replies until released, like a real network would. */
    private class SlowRelay(val inner: InMemoryBetweenClient) : BetweenClient by inner {
        val held = mutableListOf<Pair<String, ByteArray>>()
        var holding = true
        override fun publish(subject: String, data: ByteArray) {
            if (holding && subject.endsWith(".down")) held += subject to data else inner.publish(subject, data)
        }
        fun release() {
            holding = false
            held.forEach { (s, d) -> inner.publish(s, d) }
            held.clear()
        }
    }

    @Test
    fun theTokenAndTheSessionTravelOnlySealed() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val conn = RelayTunnelServerConnection(between, "zone1", "secret-session-token", home.zoneStatic.pub, session)
        val seen = mutableListOf<S2CMessage>()
        conn.onMessage { seen += it }
        conn.open()
        conn.send(C2SMessage.Look("c1", ""))

        assertEquals("{\"token\":\"secret-session-token\"}", home.received[session]!![0])
        assertTrue(home.received[session]!![1].contains("\"type\":\"look\""), home.received[session]!![1])
        for ((_, data) in between.published) {
            val text = data.decodeToString()
            assertFalse(text.contains("secret-session-token"), text)
            assertFalse(text.contains("\"look\""), text)
        }

        home.sendDown(session, """{"type":"error","seq":3,"code":"x","message":"from the home"}""")
        assertEquals("from the home", (seen.single() as S2CMessage.Error).message)
    }

    @Test
    fun framesSentBeforeTheHandshakeAreHeldAndSealedInOrder() = runTest {
        val inner = InMemoryBetweenClient().also { it.connect("mem") }
        val relay = SlowRelay(inner)
        val homeKey = X25519KeyPair.generate()
        // The home answers on the inner bus; the relay holds its answers.
        val home = FakeSealedHome(relay, "zone1", homeKey).start()
        val conn = RelayTunnelServerConnection(relay, "zone1", "tok", homeKey.pub, session)
        conn.open()
        conn.send(C2SMessage.Look("c1", ""))
        conn.send(C2SMessage.Look("c2", "north"))
        assertTrue(inner.published.none { it.first.endsWith(".up") })
        relay.release()
        val frames = home.received[session]!!
        assertEquals(3, frames.size)
        assertEquals("{\"token\":\"tok\"}", frames[0])
        assertTrue(frames[1].contains("\"c1\""))
        assertTrue(frames[2].contains("\"c2\""))
    }

    @Test
    fun aPhonePairedBeforeTheTunnelKeyIsToldToPairAgainAndSendsNothing() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val conn = RelayTunnelServerConnection(between, "zone1", "tok", zoneKey = null, sessionId = session)
        val seen = mutableListOf<S2CMessage>()
        conn.onMessage { seen += it }
        conn.open()
        conn.send(C2SMessage.Look("c1", ""))
        assertTrue(between.published.isEmpty())
        assertEquals("tunnel_key_missing", assertIs<S2CMessage.Error>(seen.single()).code)
        assertFalse(conn.isConnected)
    }

    @Test
    fun theHomesRefusalEndsTheSession() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start().also { it.refuseWith = "tunnel_busy" }
        val conn = RelayTunnelServerConnection(between, "zone1", "tok", home.zoneStatic.pub, session)
        val seen = mutableListOf<S2CMessage>()
        conn.onMessage { seen += it }
        conn.open()
        assertEquals("tunnel_busy", assertIs<S2CMessage.Error>(seen.single()).code)
        assertFalse(conn.isConnected)
        assertTrue(between.published.none { it.first.endsWith(".up") })
    }

    @Test
    fun aForgedFrameClosesTheSession() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val conn = RelayTunnelServerConnection(between, "zone1", "tok", home.zoneStatic.pub, session)
        val seen = mutableListOf<S2CMessage>()
        conn.onMessage { seen += it }
        conn.open()
        between.publish("wyrd.tunnel.zone1.$session.down", """{"type":"prose","seq":1,"speaker":"x","text":"forged"}""".encodeToByteArray())
        assertEquals("tunnel_integrity", assertIs<S2CMessage.Error>(seen.single()).code)
        assertTrue(between.published.any { it.first == "wyrd.tunnel.zone1.$session.close" })
        assertFalse(conn.isConnected)
    }

    @Test
    fun aRelayAnsweringWithItsOwnKeyCannotReadTheToken() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val realHome = X25519KeyPair.generate()
        val impostor = FakeSealedHome(between, "zone1").start() // holds a different static key
        val conn = RelayTunnelServerConnection(between, "zone1", "tok", realHome.pub, session)
        conn.open()
        // The impostor could not open the first sealed frame (the token): nothing was received.
        assertEquals(1, impostor.rejected)
        assertTrue(impostor.received[session].isNullOrEmpty())
    }
}
