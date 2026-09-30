package org.wyrdsekai.app.hermod

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.crypto.FakeSealedHome
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The relay leg of the phone's door: the same sealed tunnel a remote session
 * rides. The first sealed frame selects the hermod door (the device token never
 * crosses the relay in the clear), frames are PhoneDoorWire JSON, and
 * tunnel-level error frames END the session (transport truth, not door protocol).
 */
class HermodTunnelDoorTest {

    @Test
    fun theDoorAndDeviceTokenTravelOnlyInsideTheSealedSession() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val session = "s".repeat(16)
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", home.zoneStatic.pub, sessionId = session)
        pipe.open()
        val open = between.published.first { it.first == "wyrd.tunnel.zone1.$session.open" }.second.decodeToString()
        assertTrue(open.startsWith("{\"v\":2,\"e\":\""), open)
        assertFalse(between.published.any { it.second.decodeToString().contains("wyrd_dev_abc") })
        val first = home.received[session]!!.first()
        assertTrue(first.contains("\"door\":\"hermod\""), first)
        assertTrue(first.contains("\"deviceToken\":\"wyrd_dev_abc\""), first)
    }

    @Test
    fun framesRideUpAndDownSealed() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val session = "t".repeat(16)
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", home.zoneStatic.pub, sessionId = session)
        pipe.open()

        pipe.send("""{"type":"heartbeat","capabilityClass":"llm.phone","charging":true,"idle":true}""")
        assertTrue(home.received[session]!!.last().contains("heartbeat"))
        assertFalse(between.published.any { (s, d) -> s.endsWith(".up") && d.decodeToString().contains("heartbeat") })

        home.sendDown(session, """{"type":"hello","deviceId":"phone-7","householdId":"hh1"}""")
        val msg = decodeHermod(pipe.inbound.receive())
        assertTrue(msg is HermodMessage.Hello, "$msg")
        assertEquals("phone-7", msg.deviceId)
    }

    @Test
    fun aTunnelErrorFrameEndsTheSession() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val session = "u".repeat(16)
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", home.zoneStatic.pub, sessionId = session)
        pipe.open()
        home.sendDown(session, """{"type":"error","seq":0,"code":"tunnel_auth","message":"hermod door requires a device token"}""")
        assertFailsWith<ClosedReceiveChannelException> { pipe.inbound.receive() }
    }

    @Test
    fun withoutTheHomesKeyNothingIsSent() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", zoneKey = null, sessionId = "w".repeat(16))
        pipe.open()
        pipe.send("""{"type":"heartbeat"}""")
        assertTrue(between.published.isEmpty())
        assertFailsWith<ClosedReceiveChannelException> { pipe.inbound.receive() }
    }

    @Test
    fun closeTellsTheZoneAndDrainsInbound() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val home = FakeSealedHome(between, "zone1").start()
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", home.zoneStatic.pub, sessionId = "v".repeat(16))
        pipe.open()
        pipe.close()
        assertTrue(between.published.any { (s, _) ->
            s == "wyrd.tunnel.zone1.${"v".repeat(16)}.close"
        })
        assertFailsWith<ClosedReceiveChannelException> { pipe.inbound.receive() }
    }
}
