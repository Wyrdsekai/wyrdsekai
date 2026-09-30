package org.wyrdsekai.app.engine.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.NowLine

/**
 * A request queued while the household was unreachable is answered later. It is
 * stamped as of when it is answered, and when it waited a minute or more
 * `[Asked: …]` follows the Now line so she knows it waited.
 *
 * The replay goes out through the engine's configured client (a plain
 * InferenceClient here), and this test reads what it actually puts on the wire:
 * a local HTTP endpoint stands in for the household model.
 * Desktop-only: OfflineQueue persists through java.io.File.
 */
class OfflineReplayKnowsWhenItWasAskedTest {

    private val profile = AgentProfile(
        name = "Wyrd",
        entityId = "companion-wyrd",
        entityType = "agent",
        description = "A test companion",
        systemPrompt = "You are Wyrd, a test companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    private lateinit var tmpDir: File
    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-replay-${System.nanoTime()}")
        tmpDir.mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val reply = """{"choices":[{"message":{"role":"assistant","content":"At noon."}}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            val bytes = reply.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        System.setProperty("wyrdsekai.inference.url", "http://127.0.0.1:${server.address.port}")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("wyrdsekai.inference.url")
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun lastUserContent(body: String): String =
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .last { it["role"]!!.jsonPrimitive.content == "user" }["content"]!!.jsonPrimitive.content

    @Test
    fun a_replayed_request_is_answered_now_and_says_when_it_was_asked() = runTest {
        val before = Clock.System.now()
        val longAgo = Instant.fromEpochMilliseconds((before - 25.minutes).toEpochMilliseconds())
        val justNow = before - 20.seconds
        File(tmpDir, "offline-queue.json").writeText(
            """[{"triggerId":"t1","triggerText":"When does the tide turn?","triggerEntityName":"Alice",""" +
                """"roomId":"nexus","timestamp":${longAgo.toEpochMilliseconds()}},""" +
                """{"triggerId":"t2","triggerText":"And the moon?","triggerEntityName":"Alice",""" +
                """"roomId":"nexus","timestamp":${justNow.toEpochMilliseconds()}}]""",
        )
        val roomEngine = RoomEngine("nexus", InMemoryEventJournal(), null, null, backgroundScope)
        roomEngine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        val companion = CompanionEngine(profile, roomEngine, InferenceClient(), "http://unused", null, backgroundScope)
        val queue = OfflineQueue(tmpDir.absolutePath)
        companion.offlineQueue = queue

        companion.replayOfflineQueue()
        val after = Clock.System.now()

        assertEquals(2, bodies.size, "both queued requests were answered")
        val zone = NowLine.zone()
        val nowLines = setOf(NowLine.dateTimeText(before, zone), NowLine.dateTimeText(after, zone))

        val waited = lastUserContent(bodies[0]).split("\n")
        assertTrue(waited[0] in nowLines, "stamped as of when it is answered: ${waited[0]}")
        assertEquals(NowLine.askedText(longAgo, zone), waited[1], "and when it was asked, right under it")
        assertEquals("Alice says: When does the tide turn?", waited[2])
        assertEquals(3, waited.size)

        val barely = lastUserContent(bodies[1]).split("\n")
        assertTrue(barely[0] in nowLines, barely[0])
        assertEquals("Alice says: And the moon?", barely[1], "under a minute it did not wait")
        assertEquals(2, barely.size)

        for (body in bodies) assertFalse(body.contains("Current time"), "no trimmable clock layer")
        assertEquals(0, queue.size(), "the queue drained")

        companion.shutdown()
        roomEngine.shutdown()
    }
}
