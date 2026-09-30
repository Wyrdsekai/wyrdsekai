package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.wyrdsekai.app.crypto.FakeSealedHome
import org.wyrdsekai.app.crypto.LocalNatsServer
import org.wyrdsekai.app.crypto.RawResponder
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's NATS websocket client carries the sealed tunnel through a real
 * nats-server (loopback) set up like a relay after D4: binary frames arrive byte
 * for byte, SUB reaches the server before the PUB after it, and a request's reply
 * inbox under `_INBOX.<user>` is the one the server lets the phone read.
 * Skips when no nats-server binary is found (LocalNatsServer).
 */
class SealedTunnelOverNatsTest {

    private suspend fun awaitTrue(cond: () -> Boolean) = withTimeout(10_000) {
        while (!runCatching(cond).getOrDefault(false)) delay(20)
    }

    @Test
    fun aSealedSessionCrossesARealRelay() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val homeBus = NatsBetweenClient(scope).apply { setCredentials(LocalNatsServer.HOME_USER, LocalNatsServer.HOME_PASS) }
            homeBus.connect(server.wsUrl)
            val raw = mutableListOf<ByteArray>()
            homeBus.subscribe("wyrd.tunnel.zone1.*.up") { _, d -> synchronized(raw) { raw += d } }
            val home = FakeSealedHome(homeBus, "zone1").start()

            val phoneBus = NatsBetweenClient(scope).apply { setCredentials(LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS) }
            phoneBus.connect(server.wsUrl)
            delay(200) // the home's SUBs are in place

            val session = "abcdef0123456789abcdef0123456789"
            val conn = RelayTunnelServerConnection(phoneBus, "zone1", "the-session-token", home.zoneStatic.pub, session)
            val seen = mutableListOf<S2CMessage>()
            conn.onMessage { synchronized(seen) { seen += it } }
            conn.open()
            conn.send(C2SMessage.Look("c1", "ünïcode—room"))

            awaitTrue { (home.received[session]?.size ?: 0) >= 2 }
            assertEquals("{\"token\":\"the-session-token\"}", home.received[session]!![0])
            assertTrue(home.received[session]!![1].contains("ünïcode—room"))
            assertEquals(0, home.rejected)
            // What the relay carried was ciphertext.
            synchronized(raw) {
                assertTrue(raw.isNotEmpty())
                for (d in raw) assertFalse(String(d, Charsets.ISO_8859_1).contains("the-session-token"))
            }

            home.sendDown(session, """{"type":"error","seq":1,"code":"x","message":"over nats"}""")
            awaitTrue { synchronized(seen) { seen.isNotEmpty() } }
            assertEquals("over nats", (seen.single() as S2CMessage.Error).message)
            conn.close()
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun aRequestReplyLandsInTheUsersOwnInbox() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val inboxes = mutableListOf<String>()
            // The home answers on the request's reply subject.
            val responder = RawResponder(server.port, "wyrd.zone.zone1.auth.status") { inbox, _ ->
                synchronized(inboxes) { inboxes += inbox }
                "{\"ok\":true}".toByteArray()
            }.start()
            val phoneBus = NatsBetweenClient(scope).apply { setCredentials(LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS) }
            phoneBus.connect(server.wsUrl)
            delay(200)
            val reply = phoneBus.request("wyrd.zone.zone1.auth.status", "{}", timeoutMs = 5_000)
            assertEquals("{\"ok\":true}", reply)
            assertTrue(inboxes.single().startsWith("_INBOX.${LocalNatsServer.PHONE_USER}."), inboxes.single())
            responder.close()
        } finally {
            scope.cancel()
            server.close()
        }
    }
}
