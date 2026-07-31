package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
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

/**
 * Tests for dual inference routing that require file I/O (OfflineQueue).
 * Placed in desktopTest because OfflineQueue uses java.io.File.
 *
 * These verify the COMPLEX-tier offline queueing and persistence behavior
 * when the household is unreachable.
 *
 * See also: [DualInferenceTest] in commonTest for pure routing tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualInferenceOfflineTest {

    private val testProfile = AgentProfile(
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

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-dual-offline-${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /**
     * InferenceClient that captures all calls for assertion.
     * Returns sequential canned responses.
     */
    private class CapturingInferenceClient(
        private val responses: List<String> = listOf("Hello there!"),
    ) : InferenceClient() {
        val calls = mutableListOf<CapturedCall>()
        private var callIndex = 0

        data class CapturedCall(
            val baseUrl: String,
            val messages: List<ChatMessage>,
            val options: CompletionOptions,
        )

        override suspend fun complete(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            calls.add(CapturedCall(baseUrl, messages.toList(), options))
            val response = responses.getOrElse(callIndex) { responses.last() }
            callIndex++
            return ChatResponse(
                content = response,
                promptTokens = 50,
                completionTokens = 10,
            )
        }
    }

    private suspend fun bootRoom(
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    // ── Test 1: COMPLEX input queues when offline ──

    @Test
    fun complex_input_queues_when_offline() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)

        // For a complex input that the heuristic classifies as COMPLEX (> 8 words + ?),
        // the engine goes to the COMPLEX branch. With no remote URL set, it queues
        // and gives a local acknowledgment.
        // Responses: (no classification call needed — heuristic handles it), then ack.
        val client = CapturingInferenceClient(listOf("I'll think about that later."))

        val offlineQueue = OfflineQueue(tmpDir.absolutePath)

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.offlineQueue = offlineQueue
        companion.start()
        advanceTimeBy(200)

        // No remote URL set (System.getProperty("wyrdsekai.inference.url") is null)
        // so COMPLEX requests go to the offline queue.
        // This input has ? and > 8 words → heuristic COMPLEX.
        val complexInput = "Can you help me organize all my photos from the last three years into albums by location and date?"
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", complexInput))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Verify the offline queue got the item
        assertTrue(offlineQueue.size() > 0,
            "Complex request should be queued when offline. Queue size: ${offlineQueue.size()}")

        // Verify the companion emoted the "makes a mental note..." indicator
        val events = journal.allEvents("nexus")
        val emoted = events.filterIsInstance<WorldEvent.Emoted>()
        assertTrue(emoted.any { it.text == "makes a mental note..." && it.entityId == testProfile.entityId },
            "Companion should emote 'makes a mental note...' when queueing. " +
                "Emoted events: $emoted")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 2: Offline queue persists across CompanionEngine instances ──

    @Test
    fun offline_queue_persists() = runTest {
        // Enqueue via first OfflineQueue instance
        val queue1 = OfflineQueue(tmpDir.absolutePath)
        queue1.enqueue("Deep philosophical question about consciousness", "Alice", "nexus")

        // Create second instance with same directory — should load persisted data
        val queue2 = OfflineQueue(tmpDir.absolutePath)
        val pending = queue2.pending()
        assertEquals(1, pending.size, "Queue should persist across instances")
        assertEquals("Deep philosophical question about consciousness", pending[0].triggerText)
        assertEquals("Alice", pending[0].triggerEntityName)
        assertEquals("nexus", pending[0].roomId)
    }

    // ── Test 3: Heuristic-decided long input avoids classification overhead ──

    @Test
    fun long_input_is_complex_by_heuristic_no_classification_call() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)

        // Long input (> 30 words) is heuristic COMPLEX — no classification LLM call needed.
        // With no remote URL, it queues and acknowledges locally.
        val client = CapturingInferenceClient(listOf("I'll get back to you on that."))

        val offlineQueue = OfflineQueue(tmpDir.absolutePath)

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.offlineQueue = offlineQueue
        companion.start()
        advanceTimeBy(200)

        val longInput = "I have been thinking about this for a while and I believe that " +
            "we should reorganize the entire project structure from scratch because the " +
            "current layout is confusing and makes it hard to find anything at all"
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", longInput))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // The heuristic classifies > 30 words as COMPLEX directly.
        // Since there's no remote URL, it queues and sends an ack.
        // The ack uses 1 inference call (not 2 — no classification call needed).
        // Classification call would have maxTokens=8; ack call has maxTokens=64.
        val classificationCalls = client.calls.filter { it.options.maxTokens == 8 }
        assertTrue(classificationCalls.isEmpty(),
            "Heuristic COMPLEX (> 30 words) should NOT trigger LLM classification. " +
                "Found ${classificationCalls.size} classification calls.")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 4: Queued item contains correct metadata ──

    @Test
    fun queued_item_has_correct_metadata() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)

        val client = CapturingInferenceClient(listOf("Noted."))
        val offlineQueue = OfflineQueue(tmpDir.absolutePath)

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.offlineQueue = offlineQueue
        companion.start()
        advanceTimeBy(200)

        val complexInput = "Can you explain the philosophical implications of consciousness emerging from neural networks?"
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", complexInput))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val pending = offlineQueue.pending()
        assertTrue(pending.isNotEmpty(), "Should have queued the complex request")
        val item = pending[0]
        assertEquals(complexInput, item.triggerText, "Queued item should preserve trigger text")
        assertEquals("Alice", item.triggerEntityName, "Queued item should preserve entity name")
        assertEquals("nexus", item.roomId, "Queued item should preserve room ID")
        assertTrue(item.timestamp > 0, "Queued item should have a valid timestamp")

        companion.shutdown()
        roomEngine.shutdown()
    }
}
