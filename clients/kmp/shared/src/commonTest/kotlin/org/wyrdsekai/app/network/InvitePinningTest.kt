package org.wyrdsekai.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InvitePinningTest {

    @Test
    fun parsesWssHostAndPort() {
        assertEquals("relay.example.com" to 4443,
            parseWsHostPort("wss://relay.example.com:4443"))
        assertEquals("203.0.113.7" to 5000,
            parseWsHostPort("wss://203.0.113.7:5000/some/path"))
    }

    @Test
    fun defaultsPortByScheme() {
        assertEquals("relay.example.com" to 443,
            parseWsHostPort("wss://relay.example.com"))
        assertEquals("relay.example.com" to 80,
            parseWsHostPort("ws://relay.example.com/path"))
        assertEquals("relay.example.com" to 443,
            parseWsHostPort("https://relay.example.com"))
    }

    @Test
    fun stripsPathAndQuery() {
        assertEquals("h" to 4443, parseWsHostPort("wss://h:4443/x?y=z"))
        assertEquals("h" to 443, parseWsHostPort("wss://h?y=z"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseWsHostPort("not a url"))
        assertNull(parseWsHostPort("wss://"))
        assertNull(parseWsHostPort("wss://host:notaport"))
        assertNull(parseWsHostPort(""))
    }
}
