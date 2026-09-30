package org.wyrdsekai.app.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.wyrdsekai.app.engine.between.BudDelegation
import org.wyrdsekai.app.engine.soul.SoulSeedImporter
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The home puts a login in front of every /api route (ApiAuth, 2026-09-28):
 * each call the app makes to a non-public route sends its session or device
 * token as `Authorization: Bearer …`, never in the URL (where it would land in
 * logs). And the home-facing clients refuse plain http off the device.
 */
class ApiAuthHeaderTest {

    private data class Seen(val method: String, val path: String, val query: String?, val auth: String?)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery, ex.requestHeaders.getFirst("Authorization"))
            val body = when {
                ex.requestURI.path == "/api/mcp/login" -> """{"ok":true,"token":"sess-1","username":"alice"}"""
                ex.requestURI.path == "/api/soul/list" -> "[]"
                ex.requestURI.path == "/api/companion/ask" -> """{"text":"hi","actions":[]}"""
                ex.requestURI.path.startsWith("/api/soul/") -> """{"did":"did:x","name":"n"}"""
                ex.requestURI.path == "/api/pair/device" ->
                    """{"token":"wyrd_dev_1","householdId":"h","householdName":"H","serverDid":"d","natsUrl":"","serverUrl":""}"""
                else -> """{"ok":true,"id":"u","username":"alice","role":"member"}"""
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() = server.stop(0)

    private fun assertBearer(path: String, token: String) {
        val hits = seen.filter { it.path == path }
        assertTrue(hits.isNotEmpty(), "no call to $path")
        for (h in hits) {
            assertEquals("Bearer $token", h.auth, "$path")
            assertFalse(h.query.orEmpty().contains(token), "$path carries the token in its URL")
        }
    }

    @Test
    fun everyLoggedInApiCallSendsTheTokenAsABearerHeader() = runBlocking<Unit> {
        AuthClient(base).me("sess-1")
        AuthClient(base).linkDevice("sess-1", "wyrd_dev_1")
        assertBearer("/api/auth/me", "sess-1")
        assertBearer("/api/auth/link-device", "sess-1")

        SoulClient(base).getLatest("did:x", "sess-1")
        SoulClient(base).getHistory("did:x", "sess-1")
        assertBearer("/api/soul/did:x", "sess-1")
        assertBearer("/api/soul/did:x/history", "sess-1")

        SoulSeedImporter.fetchHouseholdSouls(base, "sess-1")
        assertBearer("/api/soul/list", "sess-1")

        PairingClient.pairSelf(base, "sess-1", "phone")
        PairingClient.checkStatus(base, "wyrd_dev_1")
        assertBearer("/api/pair/device", "sess-1")
        assertBearer("/api/pair/status", "wyrd_dev_1")

        val sc = ServerClient(base)
        sc.login("alice", "pw")
        sc.tell("bob", "hi")
        sc.doCommand("look")
        assertBearer("/api/mcp/tell", "sess-1")
        assertBearer("/api/mcp/do", "sess-1")

        BudDelegation(null, "n", "f", base, "wyrd_dev_1").delegate("hello")
        assertBearer("/api/companion/ask", "wyrd_dev_1")
    }

    @Test
    fun plainHttpToAnotherMachineIsRefusedBeforeItIsSent() = runBlocking<Unit> {
        val r = AuthClient("http://192.0.2.10:7070").login("alice", "pw")
        assertIs<PlaintextRefusedException>(r.exceptionOrNull())
        val s = AuthClient("192.0.2.10:7070").checkStatus()
        assertIs<PlaintextRefusedException>(s.exceptionOrNull())
    }
}
