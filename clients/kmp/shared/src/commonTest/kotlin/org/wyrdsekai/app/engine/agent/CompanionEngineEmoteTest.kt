package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * Tests for companion emote handling in the phone-side CompanionEngine.
 *
 * NOTE: The server-side CompanionActor (core/agent/CompanionActor.java) does NOT
 * handle emote actions. Its onRoomEvent switch falls through to `default -> {}`
 * for WorldEvent.Emoted, and it never emits RoomCommand.EmoteInRoom. Emotes are
 * a phone-only feature. Server tests are therefore not applicable.
 *
 * These tests verify:
 *   - Emote action from LLM output routes to EmoteInRoom command
 *   - Social action appends 's' and routes to EmoteInRoom
 *   - WhisperTo falls back to speak (no WhisperInRoom on phone yet)
 *   - Thinking indicator uses emote, not say
 *   - Companion ignores its own emotes (no self-echo)
 *   - Companion ignores emotes from others (only Said triggers inference)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionEngineEmoteTest {

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

    /** InferenceClient that returns a canned response. Never actually calls HTTP. */
    private class FakeInferenceClient(private val cannedResponse: String = "Hello there!") : InferenceClient() {
        var lastMessages: List<ChatMessage>? = null
        var callCount = 0

        override suspend fun complete(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            lastMessages = messages
            callCount++
            return ChatResponse(
                content = cannedResponse,
                promptTokens = 50,
                completionTokens = 10,
            )
        }
    }

    /**
     * Helper to boot a RoomEngine with a created room and the companion entered.
     */
    private suspend fun bootRoom(
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    // ── Test 1: Emote action sends EmoteInRoom ──

    @Test
    fun emote_action_sends_emote_to_room() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)

        // FakeInferenceClient returns a response containing an emote action block.
        // handleAction is private, so we exercise it through the full inference path:
        // a Said event triggers inference, the fake client returns the emote action,
        // and handleAction routes it to EmoteInRoom.
        val emoteClient = FakeInferenceClient("""Sure thing!
```json
{"action": "emote", "text": "smiles warmly"}
```""")
        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = emoteClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // Trigger a Said event from a player (not the companion, not narrator, not system)
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "Hello, Wyrd!"))
        // Let the debounce + inference cycle complete
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Verify EmoteInRoom was sent — journal should contain an Emoted event
        val events = journal.allEvents("nexus")
        val emoted = events.filterIsInstance<WorldEvent.Emoted>()
        // At least one should be "smiles warmly" from the action, plus possible "is thinking..." indicator
        assertTrue(emoted.any { it.text == "smiles warmly" && it.entityId == testProfile.entityId },
            "Expected an Emoted event with text 'smiles warmly' from ${testProfile.entityId}. " +
                "Emoted events: $emoted")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 2: Social action sends emote with 's' appended ──

    @Test
    fun social_action_sends_emote_to_room() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)
        val socialClient = FakeInferenceClient("""
```json
{"action": "social", "name": "nod"}
```""")

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = socialClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // Trigger inference via player speech
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "What do you think?"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val events = journal.allEvents("nexus")
        val emoted = events.filterIsInstance<WorldEvent.Emoted>()
        // Social("nod") should map to EmoteInRoom with text "nods"
        assertTrue(emoted.any { it.text == "nods" && it.entityId == testProfile.entityId },
            "Expected an Emoted event with text 'nods' from ${testProfile.entityId}. " +
                "Emoted events: $emoted")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 3: WhisperTo falls back to speak ──

    @Test
    fun whisper_to_action_speaks_whisper() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)
        val whisperClient = FakeInferenceClient("""I have a secret.
```json
{"action": "whisper_to", "target": "player-1", "text": "hey"}
```""")

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = whisperClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "Tell me a secret"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val events = journal.allEvents("nexus")
        val said = events.filterIsInstance<WorldEvent.Said>()
        // WhisperTo should fall back to speak("*whispers to player-1*")
        assertTrue(said.any { it.entityId == testProfile.entityId && it.text.contains("whispers to") },
            "Expected a Said event containing 'whispers to' from companion. " +
                "Said events from companion: ${said.filter { it.entityId == testProfile.entityId }}")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 4: Thinking indicator uses emote, not say ──

    @Test
    fun thinking_indicator_uses_emote_not_say() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)
        val inferenceClient = FakeInferenceClient("I'm here to help!")

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = inferenceClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // Trigger inference
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "Hey there!"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val events = journal.allEvents("nexus")
        val emoted = events.filterIsInstance<WorldEvent.Emoted>()
        val said = events.filterIsInstance<WorldEvent.Said>()

        // Thinking indicator should appear as an Emoted event, NOT as a Said event.
        // With dual inference routing, SIMPLE tier emits "considers..." and COMPLEX emits
        // "is thinking deeply..." — either way it's an emote, never a say.
        val thinkingEmotes = emoted.filter {
            it.entityId == testProfile.entityId &&
                (it.text == "considers..." || it.text == "is thinking deeply..." || it.text == "is thinking...")
        }
        assertTrue(thinkingEmotes.isNotEmpty(),
            "Expected a thinking indicator as an Emoted event from companion. " +
                "Emoted events: $emoted")
        val thinkingTexts = setOf("considers...", "is thinking deeply...", "is thinking...")
        assertFalse(said.any { it.entityId == testProfile.entityId && it.text in thinkingTexts },
            "Thinking indicator should NOT appear as a Said event — it must be an emote")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 5: Companion does not self-echo on emote ──

    @Test
    fun companion_does_not_self_echo_on_emote() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)
        val inferenceClient = FakeInferenceClient("Should not see this!")

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = inferenceClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // Reset call count after any start-up activity
        inferenceClient.callCount = 0

        // Send an Emoted event with the companion's own entityId directly through the room
        roomEngine.send(RoomEngineCommand.EmoteInRoom(
            entityId = testProfile.entityId,
            entityName = testProfile.name,
            text = "smiles",
        ))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // The companion's onRoomEvent should ignore emotes (falls through to else -> {}).
        // No inference should be triggered.
        assertEquals(0, inferenceClient.callCount,
            "Companion should NOT trigger inference on its own emote. " +
                "Expected 0 calls, got ${inferenceClient.callCount}")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 6: Companion does not respond to emotes from others ──

    @Test
    fun companion_does_not_respond_to_emotes_from_others() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)
        val inferenceClient = FakeInferenceClient("Should not see this either!")

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = inferenceClient,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // Reset call count after any start-up activity
        inferenceClient.callCount = 0

        // Send an Emoted event from a different entity
        roomEngine.send(RoomEngineCommand.EmoteInRoom(
            entityId = "player-1",
            entityName = "Alice",
            text = "waves",
        ))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Emotes should NOT trigger companion inference — only Said events do.
        // CompanionEngine.onRoomEvent only handles Said and EntityEntered.
        assertEquals(0, inferenceClient.callCount,
            "Companion should NOT trigger inference on emotes from other entities. " +
                "Expected 0 calls, got ${inferenceClient.callCount}")

        companion.shutdown()
        roomEngine.shutdown()
    }
}
