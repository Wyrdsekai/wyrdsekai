package org.wyrdsekai.app.engine.between

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * NATS frames a MSG payload by BYTE length. The client reads the stream as
 * bytes: a payload with multi-byte UTF-8 (a steward Study's room_state with
 * em-dashes, 2026-07-24) must not bleed into the next "\r\nMSG …", and a sealed
 * tunnel frame is binary and may itself hold "\r\n" ( W3).
 */
class NatsBetweenClientFramingTest {

    private fun msgs(ops: List<NatsReadBuffer.Op>) = ops.filterIsInstance<NatsReadBuffer.Op.Msg>()

    @Test
    fun multiByteUtf8PayloadStopsAtItsByteLength() {
        val payload = "a—b" // em dash = 3 bytes: 5 bytes, 3 chars
        val stream = "MSG wyrd.tunnel.x 3 5\r\n$payload\r\nMSG wyrd.tunnel.y 4 2\r\nok\r\n"
        val out = msgs(NatsReadBuffer().feed(stream.encodeToByteArray()))
        assertEquals(2, out.size)
        assertEquals("a—b", out[0].payload.decodeToString())
        assertEquals("wyrd.tunnel.y", out[1].subject)
        assertEquals("ok", out[1].payload.decodeToString())
    }

    @Test
    fun binaryPayloadHoldingCrlfAndInvalidUtf8SurvivesByteForByte() {
        val payload = byteArrayOf(0, 13, 10, -1, -2, 77, 13, 10, 0)
        val stream = "MSG s 1 ${payload.size}\r\n".encodeToByteArray() + payload + "\r\nPING\r\n".encodeToByteArray()
        val ops = NatsReadBuffer().feed(stream)
        assertContentEquals(payload, msgs(ops).single().payload)
        assertTrue(ops.last() is NatsReadBuffer.Op.Ping)
    }

    @Test
    fun aFrameSplitAcrossChunksWaitsForTheRest() {
        val r = NatsReadBuffer()
        val whole = "MSG s 7 _INBOX.u.abc 4\r\nwxyz\r\n".encodeToByteArray()
        assertTrue(r.feed(whole.copyOfRange(0, 10)).isEmpty())
        assertTrue(r.feed(whole.copyOfRange(10, 27)).isEmpty())
        val m = msgs(r.feed(whole.copyOfRange(27, whole.size))).single()
        assertEquals(7, m.sid)
        assertEquals("wxyz", m.payload.decodeToString())
    }

    @Test
    fun theHandshakePongIsSurfaced() {
        val ops = NatsReadBuffer().feed("+OK\r\nPONG\r\n".encodeToByteArray())
        assertIs<NatsReadBuffer.Op.Pong>(ops.single())
    }

    @Test
    fun serverErrorsAreSurfacedAndInfoIsSkipped() {
        val ops = NatsReadBuffer().feed("INFO {}\r\n+OK\r\n-ERR 'Permissions Violation'\r\n".encodeToByteArray())
        assertIs<NatsReadBuffer.Op.Err>(ops.single())
    }

    @Test
    fun publishFramesCarryBinaryPayloadsAndTheReplyInbox() {
        val data = byteArrayOf(1, 13, 10, 2)
        val f = NatsBetweenClient.pubFrame("wyrd.tunnel.z.s.up", null, data)
        assertContentEquals("PUB wyrd.tunnel.z.s.up 4\r\n".encodeToByteArray() + data + "\r\n".encodeToByteArray(), f)
        val r = NatsBetweenClient.pubFrame("wyrd.zone.z.mcp.login", "_INBOX.phone-1.x", "{}".encodeToByteArray())
        assertEquals("PUB wyrd.zone.z.mcp.login _INBOX.phone-1.x 2\r\n{}\r\n", r.decodeToString())
    }

    @Test
    fun replyInboxesSitUnderTheRelayUsername() {
        assertEquals("_INBOX.relay_phone_ab12.", NatsBetweenClient.inboxFor("relay_phone_ab12"))
        assertEquals("_INBOX.", NatsBetweenClient.inboxFor(null))
    }
}
