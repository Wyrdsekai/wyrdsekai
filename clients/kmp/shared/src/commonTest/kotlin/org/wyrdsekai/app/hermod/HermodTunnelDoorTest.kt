package org.wyrdsekai.app.hermod

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The relay leg of the phone's door: same tunnel subjects a remote
 * session rides, open payload selects the hermod door, frames are
 * PhoneDoorWire JSON, and tunnel-level error frames END the session
 * (transport truth, not door protocol).
 */
class HermodTunnelDoorTest {

    @Test
    fun openSelectsTheHermodDoorWithTheDeviceToken() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", sessionId = "s".repeat(16))
        pipe.open()
        val (subject, data) = between.published.single()
        assertEquals("wyrd.tunnel.zone1.${"s".repeat(16)}.open", subject)
        val payload = data.decodeToString()
        assertTrue(payload.contains("\"door\":\"hermod\""), payload)
        assertTrue(payload.contains("\"deviceToken\":\"wyrd_dev_abc\""), payload)
    }

    @Test
    fun framesRideUpAndDown() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", sessionId = "t".repeat(16))
        pipe.open()
        val base = "wyrd.tunnel.zone1.${"t".repeat(16)}"

        pipe.send("""{"type":"heartbeat","capabilityClass":"llm.phone","charging":true,"idle":true}""")
        assertTrue(between.published.any { (s, d) ->
            s == "$base.up" && d.decodeToString().contains("heartbeat")
        })

        between.publish("$base.down",
            """{"type":"hello","deviceId":"phone-7","householdId":"hh1"}""".encodeToByteArray())
        val msg = decodeHermod(pipe.inbound.receive())
        assertTrue(msg is HermodMessage.Hello, "$msg")
        assertEquals("phone-7", msg.deviceId)
    }

    @Test
    fun aTunnelErrorFrameEndsTheSession() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", sessionId = "u".repeat(16))
        pipe.open()
        val base = "wyrd.tunnel.zone1.${"u".repeat(16)}"
        between.publish("$base.down",
            """{"type":"error","seq":0,"code":"tunnel_auth","message":"hermod door requires a device token"}"""
                .encodeToByteArray())
        assertFailsWith<ClosedReceiveChannelException> { pipe.inbound.receive() }
    }

    @Test
    fun closeTellsTheZoneAndDrainsInbound() = runTest {
        val between = InMemoryBetweenClient().also { it.connect("mem") }
        val pipe = TunnelDoorFrames(between, "zone1", "wyrd_dev_abc", sessionId = "v".repeat(16))
        pipe.open()
        pipe.close()
        assertTrue(between.published.any { (s, _) ->
            s == "wyrd.tunnel.zone1.${"v".repeat(16)}.close"
        })
        assertFailsWith<ClosedReceiveChannelException> { pipe.inbound.receive() }
    }
}
