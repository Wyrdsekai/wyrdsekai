package org.wyrdsekai.app.engine.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.RemoteAuthType

/**
 * The two paths that send her full layered prompt to the household model: the
 * COMPLEX raw-remote fallback and the offline replay.
 *
 * Each leaves with one system message at index 0 (a Qwen template on a raw
 * llama-server rejects any other). Each goes to the household over HTTP with the
 * configured auth header and model, and never to the model on the device: on
 * Android the configured client is LocalFirstInferenceClient, which answers on
 * the device whenever a model is loaded and ignores the URL. When the household
 * does not answer, the request is queued, as before. One replay runs at a time,
 * and a turn waits for it.
 *
 * Desktop-only: the household is a local HTTP endpoint read on the wire, the
 * queue persists through java.io.File, and wyrdsekai.inference.url is a JVM
 * system property set and cleared around each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepPathsLeaveAsTheTemplateAcceptsTest {

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

    private class Request(val authorization: String?, val body: JsonObject)

    private lateinit var tmpDir: File
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Request>()
    /** Household requests and on-device completions, in the order they happened. */
    private val order = CopyOnWriteArrayList<String>()
    /** How long the household takes to answer, in real milliseconds. */
    @Volatile private var householdDelayMs = 0L
    private lateinit var remoteUrl: String

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-deep-${System.nanoTime()}")
        tmpDir.mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            requests += Request(exchange.requestHeaders.getFirst("Authorization"), body)
            order += "household: ${lastUser(body)}"
            Thread.sleep(householdDelayMs)
            val reply = """{"choices":[{"message":{"role":"assistant","content":"At noon."}}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            val bytes = reply.encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        remoteUrl = "http://127.0.0.1:${server.address.port}"
        System.setProperty("wyrdsekai.inference.url", remoteUrl)
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("wyrdsekai.inference.url")
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    /**
     * What NodeManager.android builds: the cloud key and model set on a client
     * that answers on the device whenever a model is loaded, whatever the URL
     * (LocalFirstInferenceClient, which is androidMain and not reachable here).
     */
    private inner class DeviceFirstClient(private val reply: String? = "From the phone.") : InferenceClient() {
        val onDevice = mutableListOf<List<ChatMessage>>()

        init {
            setRemoteAuth(RemoteAuthType.BEARER, "sk-test-key")
            setRemoteModel("anthropic/claude-sonnet-4")
        }

        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            onDevice += messages
            order += "device: ${messages.last { it.role == "user" }.content.lines().last()}"
            return ChatResponse(reply ?: error("the device model failed"), 1, 1)
        }
    }

    private fun lastUser(body: JsonObject): String =
        body["messages"]!!.jsonArray.map { it.jsonObject }
            .last { it["role"]!!.jsonPrimitive.content == "user" }["content"]!!.jsonPrimitive.content
            .lines().last()

    private fun roles(body: JsonObject) = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    private fun assertOneLeadingSystem(roles: List<String>) {
        assertEquals(listOf(0), roles.indices.filter { roles[it] == "system" },
            "one system message, at index 0: $roles")
    }

    private fun queueOf(vararg texts: String): OfflineQueue {
        val asked = System.currentTimeMillis() - 25 * 60_000
        File(tmpDir, "offline-queue.json").writeText(
            texts.withIndex().joinToString(",", "[", "]") { (i, text) ->
                """{"triggerId":"t$i","triggerText":"$text","triggerEntityName":"Alice",""" +
                    """"roomId":"nexus","timestamp":${asked + i}}"""
            },
        )
        return OfflineQueue(tmpDir.absolutePath)
    }

    private suspend fun room(scope: kotlinx.coroutines.CoroutineScope): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    private fun hers(journal: InMemoryEventJournal) =
        journal.allEvents("nexus").filter {
            (it is WorldEvent.Said && it.entityId == profile.entityId) ||
                (it is WorldEvent.Emoted && it.entityId == profile.entityId)
        }.map { if (it is WorldEvent.Said) it.text else (it as WorldEvent.Emoted).text }

    /**
     * Listens now for her line [text]; await it once the turn is going. The
     * household is real I/O, which advanceUntilIdle does not wait for.
     */
    private fun TestScope.whenSaid(companion: CompanionEngine, text: String): Deferred<String> =
        backgroundScope.async { companion.companionSpeech.first { it == text } }.also { runCurrent() }

    // > 8 words with a question mark: heuristic COMPLEX. No bud delegation, so her
    // full layered prompt goes to the household model's URL.
    private val complexQuestion = "Can you help me organize all my photos from the last three years into albums?"

    @Test
    fun the_raw_remote_fallback_goes_to_the_household_with_one_leading_system_message() = runTest {
        val (roomEngine, journal) = room(backgroundScope)
        val client = DeviceFirstClient()
        val companion = CompanionEngine(profile, roomEngine, client, remoteUrl, null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        val answered = whenSaid(companion, "At noon.")
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", complexQuestion))
        advanceTimeBy(5000)
        answered.await()

        val request = requests.single()
        assertEquals(emptyList(), client.onDevice, "a model loaded on the phone did not take the household's turn")
        assertEquals("Bearer sk-test-key", request.authorization, "the configured auth header")
        assertEquals("anthropic/claude-sonnet-4", request.body["model"]!!.jsonPrimitive.content)
        assertOneLeadingSystem(roles(request.body))
        val sent = request.body["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content }
        assertTrue(sent[0].startsWith("You are Wyrd, a test companion.\n\n"), sent[0])
        assertTrue(sent[0].contains("Current location: The Nexus"), "the room layer is merged in")
        assertTrue(sent.last().startsWith("[Now: "), "the Now line opens her last user turn")
        assertTrue(sent.last().endsWith("\nAlice says: $complexQuestion"))
        assertTrue("At noon." in hers(journal), "she said the household's answer: ${hers(journal)}")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun when_the_household_does_not_answer_the_deep_turn_is_queued_not_answered_on_the_device() = runTest {
        // A port nothing listens on: the household is down.
        val closed = ServerSocket(0).use { it.localPort }
        System.setProperty("wyrdsekai.inference.url", "http://127.0.0.1:$closed")
        val (roomEngine, _) = room(backgroundScope)
        val client = DeviceFirstClient()
        val companion = CompanionEngine(profile, roomEngine, client, "http://127.0.0.1:$closed", null, backgroundScope)
        val queue = OfflineQueue(tmpDir.absolutePath)
        companion.offlineQueue = queue
        companion.start()
        advanceTimeBy(200)

        val acknowledged = whenSaid(companion, "From the phone.")
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", complexQuestion))
        advanceTimeBy(5000)
        acknowledged.await()

        assertEquals(1, queue.size(), "queued for the household")
        // The phone's model gave only the short acknowledgement, never her deep prompt.
        val asked = client.onDevice.single()
        assertTrue(asked[0].content.contains("Acknowledge briefly"), asked[0].content)

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun a_replay_goes_to_the_household_with_the_configured_auth_not_to_the_device() = runTest {
        val queue = queueOf("When does the tide turn?")
        val (roomEngine, journal) = room(backgroundScope)
        val client = DeviceFirstClient()
        val companion = CompanionEngine(profile, roomEngine, client, remoteUrl, null, backgroundScope)
        companion.offlineQueue = queue

        companion.replayOfflineQueue()

        val request = requests.single()
        assertEquals(emptyList(), client.onDevice, "the phone's model did not answer the household's request")
        assertEquals("Bearer sk-test-key", request.authorization, "the configured auth header")
        assertEquals("anthropic/claude-sonnet-4", request.body["model"]!!.jsonPrimitive.content, "the configured model")
        assertOneLeadingSystem(roles(request.body))
        val system = request.body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith("You are Wyrd, a test companion.\n\n"), system)
        assertEquals(0, queue.size(), "the queue drained")
        assertEquals(listOf("catches up on earlier conversations...", "About \"When does the tide turn?\" — At noon."),
            hers(journal))

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun nothing_answered_means_no_catch_up_is_announced() = runTest {
        System.clearProperty("wyrdsekai.inference.url")
        val queue = queueOf("When does the tide turn?")
        val (roomEngine, journal) = room(backgroundScope)
        val client = DeviceFirstClient()
        val companion = CompanionEngine(profile, roomEngine, client, "http://unused", null, backgroundScope)
        companion.offlineQueue = queue

        companion.replayOfflineQueue()

        // She announced a catch-up before looking for a household URL, then
        // returned without replaying anything, and again after every deep turn.
        assertEquals(emptyList(), hers(journal), "no catch-up announced")
        assertEquals(emptyList(), client.onDevice, "the phone's model is not the household")
        assertEquals(1, queue.size(), "kept for the next drain")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun two_replays_at_once_answer_each_request_once() = runTest {
        householdDelayMs = 300
        val queue = queueOf("When does the tide turn?", "And the moon?")
        val (roomEngine, journal) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, DeviceFirstClient(), remoteUrl, null, backgroundScope)
        companion.offlineQueue = queue

        // A second deep turn used to launch a second replay over the same pending
        // list while the first was still waiting on the household.
        val first = backgroundScope.launch { companion.replayOfflineQueue() }
        val second = backgroundScope.launch { companion.replayOfflineQueue() }
        first.join()
        second.join()

        assertEquals(listOf("household: Alice says: When does the tide turn?", "household: Alice says: And the moon?"), order.toList(),
            "one request per queued item")
        val said = hers(journal)
        assertEquals(1, said.count { it == "catches up on earlier conversations..." }, said.toString())
        assertEquals(1, said.count { it.startsWith("About \"When does the tide turn?\"") }, said.toString())
        assertEquals(0, queue.size())

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun a_turn_waits_for_the_replay_in_flight() = runTest {
        householdDelayMs = 500
        val queue = queueOf("When does the tide turn?", "And the moon?")
        val (roomEngine, _) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, DeviceFirstClient(), remoteUrl, null, backgroundScope)
        companion.offlineQueue = queue
        companion.start()
        advanceTimeBy(200)

        backgroundScope.launch { companion.replayOfflineQueue() }
        runCurrent()
        // A greeting: the quick path, answered by the configured client.
        val greeted = whenSaid(companion, "From the phone.")
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        greeted.await()

        assertEquals(
            listOf("household: Alice says: When does the tide turn?", "household: Alice says: And the moon?", "device: Alice says: hello"),
            order.toList(),
            "the turn ran after the replay, not beside it",
        )

        companion.shutdown()
        roomEngine.shutdown()
    }
}
