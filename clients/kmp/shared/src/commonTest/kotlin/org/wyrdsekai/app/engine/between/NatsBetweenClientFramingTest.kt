package org.wyrdsekai.app.engine.between

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NATS frames a MSG payload by BYTE length, but the receive buffer is a UTF-16
 * String. Indexing the payload by the byte count over-reads whenever the payload
 * holds a multi-byte UTF-8 char — bleeding the next frame's "\r\nMSG …" into the
 * payload. A steward Study's room_state (em-dashes/ellipses in descriptions) hit
 * this: the frame failed to parse ("Expected EOF, had M") and was dropped, so the
 * phone showed the GENERIC Study, not the real furnishings. (home-server 2026-07-24)
 */
class NatsBetweenClientFramingTest {

    private fun charLen(s: String, bytes: Int) =
        NatsBetweenClient.utf8PrefixCharLen(s, bytes)

    @Test
    fun asciiByteLenEqualsCharLen() {
        assertEquals(5, charLen("hello world", 5))
        assertEquals(11, charLen("hello world", 11))
    }

    @Test
    fun multiByteCharsMakeByteLenExceedCharLen() {
        // "a—b" = a(1) + em-dash(3 bytes, 1 char) + b(1) = 5 bytes, 3 chars.
        val s = "a—b" // U+2014 EM DASH → 3 UTF-8 bytes
        assertEquals(5, s.encodeToByteArray().size)
        assertEquals(3, s.length)
        // 5 payload bytes → char index 3 (the whole "a—b"), NOT char index 5.
        assertEquals(3, charLen(s, 5))
    }

    @Test
    fun stopsAtPayloadBoundaryWhenTrailerFollows() {
        // The exact bug: payload "a—" (4 bytes, 2 chars) immediately followed by
        // the next frame's protocol bytes. Framing by BYTE length must return the
        // char index for the payload only (2), never reach into "\r\nMSG …".
        val buf = "a—\r\nMSG wyrd.tunnel.x 3 21901\r\n{...}"
        // payload is "a—" = 1 + 3 = 4 bytes
        assertEquals(2, charLen(buf, 4))
        // and the char at that index is the payload's trailing \r (start of \r\n)
        assertEquals('\r', buf[charLen(buf, 4)])
    }

    @Test
    fun incompleteBufferReturnsMinusOne() {
        // Only 3 bytes buffered but 5 requested → wait for more.
        assertEquals(-1, charLen("a—", 6))
        // Lone high surrogate (pair not yet buffered) → wait.
        assertEquals(-1, charLen("x\uD83D", 5))
        assertEquals(-1, charLen("", 1))
    }

    @Test
    fun surrogatePairCountsAsFourBytesTwoChars() {
        // 😀 U+1F600 = surrogate pair (2 chars) = 4 UTF-8 bytes.
        val s = "😀" // 😀
        assertEquals(4, s.encodeToByteArray().size)
        assertEquals(2, s.length)
        assertEquals(2, charLen(s, 4))
    }
}
