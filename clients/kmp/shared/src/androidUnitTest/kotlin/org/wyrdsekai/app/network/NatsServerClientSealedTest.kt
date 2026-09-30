package org.wyrdsekai.app.network

import kotlinx.coroutines.runBlocking
import org.wyrdsekai.app.i18n.currentUiStrings
import org.wyrdsekai.app.crypto.LocalNatsServer
import org.wyrdsekai.app.crypto.RawResponder
import org.wyrdsekai.app.crypto.TestHome
import org.wyrdsekai.app.crypto.X25519KeyPair
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's jnats request client against a real nats-server set up like a
 * relay after D4 (loopback; skipped without a nats-server binary): every zone
 * request is sealed to the home's key ( W3), its reply
 * inbox sits under `_INBOX.<relay user>`, and without the key nothing is sent.
 */
class NatsServerClientSealedTest {

    private val zone = "zone1"

    @Test
    fun loginIsSealedAndTheReplyComesBackToTheUsersInbox() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val home = X25519KeyPair.generate()
        val seen = CopyOnWriteArrayList<String>()
        val inboxes = CopyOnWriteArrayList<String>()
        val subject = "wyrd.zone.$zone.mcp.login"
        val responder = RawResponder(server.port, subject) { replyTo, body ->
            inboxes += replyTo
            seen += String(body, Charsets.UTF_8)
            val opened = TestHome.openRequest(home.priv, subject, String(body, Charsets.UTF_8))
            assertTrue(opened.plain.contains("\"password\":\"correct horse\""), opened.plain)
            opened.sealReply("""{"ok":true,"token":"tok-1","userId":"u-1","username":"alice"}""").toByteArray()
        }.start()
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, home.pub)
        try {
            val auth = client.login("alice", "correct horse")
            assertEquals("tok-1", auth.token)
            assertEquals("u-1", auth.userId)
            assertTrue(inboxes.single().startsWith("_INBOX.${LocalNatsServer.PHONE_USER}."), inboxes.single())
            assertFalse(seen.single().contains("correct horse"))
            assertTrue(seen.single().startsWith("{\"v\":2,"))
        } finally {
            client.disconnect()
            responder.close()
            server.close()
        }
    }

    @Test
    fun aForgedUnsealedReplyIsNotBelieved() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val home = X25519KeyPair.generate()
        val subject = "wyrd.zone.$zone.mcp.login"
        // Whoever answers without the home's key can only answer in the clear.
        val responder = RawResponder(server.port, subject) { _, _ ->
            """{"ok":true,"token":"forged","username":"alice"}""".toByteArray()
        }.start()
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, home.pub)
        try {
            val e = assertFailsWith<IllegalStateException> { client.login("alice", "pw") }
            assertEquals("sealed_reply_invalid", e.message)
        } finally {
            client.disconnect()
            responder.close()
            server.close()
        }
    }

    @Test
    fun withoutTheHomesKeyNothingIsSent() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val seen = CopyOnWriteArrayList<String>()
        val responder = RawResponder(server.port, "wyrd.zone.$zone.>") { _, body ->
            seen += String(body, Charsets.UTF_8)
            """{"ok":true,"token":"t"}""".toByteArray()
        }.start()
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, zoneKey = null)
        try {
            val e = assertFailsWith<IllegalStateException> { client.login("alice", "pw") }
            assertEquals("zone_key_missing", e.message)
            assertTrue(client.writeJournal("dear diary").error == "Not logged in")
            assertTrue(seen.isEmpty())
            // A stranger's question may still go in the clear: which zone answers here.
            assertEquals(null, client.discoverZone())
        } finally {
            client.disconnect()
            responder.close()
            server.close()
        }
    }

    @Test
    fun aKnockOnAnotherZoneGoesToThatZoneAndItsAnswerComesBack() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val seen = CopyOnWriteArrayList<String>()
        // The other zone's home, which knows no key of this phone's: the knock arrives in the clear.
        val responder = RawResponder(server.port, "wyrd.zone.neighbours.directory.knock") { _, body ->
            seen += String(body, Charsets.UTF_8)
            """{"ok":true,"requestId":"req-9"}""".toByteArray()
        }.start()
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, X25519KeyPair.generate().pub)
        try {
            val answer = client.requestAccess("neighbours", "ada")
            assertEquals("req-9", answer.requestId)
            assertTrue(seen.single().contains("\"requesterName\":\"ada\""), seen.single())
        } finally {
            client.disconnect()
            responder.close()
            server.close()
        }
    }

    @Test
    fun aKnockNobodyAnswersIsToldPlainlyAndQuickly() = runBlocking {
        val server = LocalNatsServer.startOrNull() ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, X25519KeyPair.generate().pub)
        try {
            val t0 = System.nanoTime()
            val answer = client.requestAccess("nobody-here", "ada")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(null, answer.requestId)
            assertEquals(currentUiStrings().secKnockNoAnswer, answer.message)
            assertTrue(ms < 3_000, "no responders is known at once, not after the request timeout ($ms ms)")
        } finally {
            client.disconnect()
            server.close()
        }
    }

    @Test
    fun aKnockAnOlderRelayRefusesIsToldPlainlyAndQuickly() = runBlocking {
        // An older relay: the phone may publish only its own zone's subjects.
        val server = LocalNatsServer.startOrNull(listOf("wyrd.zone.$zone.>", "wyrd.discover.>")) ?: run {
            println("SKIP: no nats-server (set WYRDSEKAI_TEST_NATS_SERVER)")
            return@runBlocking
        }
        val client = NatsServerClient(server.wsUrl, zone, LocalNatsServer.PHONE_USER, LocalNatsServer.PHONE_PASS, X25519KeyPair.generate().pub)
        try {
            val t0 = System.nanoTime()
            val answer = client.requestAccess("neighbours", "ada")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(null, answer.requestId)
            assertEquals(currentUiStrings().secKnockRefusedByRelay, answer.message)
            assertTrue(ms < 3_000, "the relay's refusal is known at once, not after the request timeout ($ms ms)")
        } finally {
            client.disconnect()
            server.close()
        }
    }

    @Test
    fun onlyAStrangersQuestionsMayGoInTheClear() {
        assertTrue(NatsServerClient.mayGoInTheClear("wyrd.discover.zone"))
        assertTrue(NatsServerClient.mayGoInTheClear("wyrd.zone.z.directory.knock"))
        assertTrue(NatsServerClient.mayGoInTheClear("wyrd.zone.z.directory.search"))
        for (s in listOf("mcp.login", "mcp.tell", "study.journal", "auth.register", "auth.redeem", "auth.status",
            "pair.device", "account.zonebank.get", "account.zonebank.put", "library.search", "directory.knock.list")) {
            assertFalse(NatsServerClient.mayGoInTheClear("wyrd.zone.z.$s"), s)
        }
    }

    @Test
    fun plainWebsocketOffTheDeviceIsRefused() = runBlocking {
        val client = NatsServerClient("ws://192.0.2.10:4223", zone, "u", "p", X25519KeyPair.generate().pub)
        assertFailsWith<PlaintextRefusedException> { client.connect() }
        Unit
    }
}
