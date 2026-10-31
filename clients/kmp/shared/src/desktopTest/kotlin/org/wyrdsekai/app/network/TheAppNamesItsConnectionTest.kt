package org.wyrdsekai.app.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The app names itself on every connect, so a connection that drops comes back to the same
 * session on the home: no one in the room sees it leave or arrive, and the replay asks that same
 * session for what was said meanwhile.
 */
class TheAppNamesItsConnectionTest {

    /** A socket whose connection never starts: only its address is read. */
    private fun socket(): WyrdWebSocket {
        val ws = WyrdWebSocket(CoroutineScope(Job().apply { cancel() }))
        ws.connect("http://home.local:7070", "tok")
        return ws
    }

    private fun clientOf(url: String): String? =
        url.substringAfter('?').split('&').firstOrNull { it.startsWith("client=") }?.substringAfter('=')

    @Test
    fun the_same_name_on_every_connect() {
        val ws = socket()
        val first = clientOf(ws.buildWsUrl())
        assertNotNull(first)
        ws.setCurrentRoomId("hearth")
        assertEquals(first, clientOf(ws.buildWsUrl()), "a reconnect carries the same name")
    }

    @Test
    fun two_apps_have_names_of_their_own() {
        assertNotEquals(clientOf(socket().buildWsUrl()), clientOf(socket().buildWsUrl()))
    }
}
