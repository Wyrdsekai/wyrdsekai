package org.wyrdsekai.app.engine.agent

import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.oracle.PhonePrediction
import org.wyrdsekai.app.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FullPromptAssemblerTest {

    private val profile = Companions.NEXUS_COMPANION
    private val now = Clock.System.now()

    @Test
    fun assembleMinimalPrompt() {
        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
        )
        assertTrue(messages.size >= 1) // System prompt + optional time context
        assertEquals("system", messages[0].role)
    }

    @Test
    fun assembleWithRoomContext() {
        val snapshot = RoomSnapshot(
            roomId = "nexus",
            name = "The Nexus",
            description = "A crystalline chamber.",
            zone = "foundation",
            exits = listOf(Exit("north", "terminal", "To Terminal")),
            entities = listOf(Entity("p1", "Alice", "player", "")),
            objects = listOf(RoomObject("obj-1", "crystal", "A crystal", false)),
            hints = emptyList(),
        )

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = snapshot,
            recentSaid = emptyList(),
            triggerEvent = null,
        )
        assertTrue(messages.size >= 2) // system + room context
        assertTrue(messages[1].content.contains("The Nexus"))
        assertTrue(messages[1].content.contains("Alice"))
    }

    @Test
    fun assembleWithConversationHistory() {
        val snapshot = RoomSnapshot("nexus", "Nexus", "Hub.", "f",
            emptyList(), emptyList(), emptyList(), emptyList())

        val said = listOf(
            WorldEvent.Said("nexus", now, "p1", "Alice", "Hello!"),
            WorldEvent.Said("nexus", now, profile.entityId, profile.name, "Welcome, Alice!"),
        )

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = snapshot,
            recentSaid = said,
            triggerEvent = null,
        )

        // Should include conversation history with correct roles
        val userMessages = messages.filter { it.role == "user" }
        val assistantMessages = messages.filter { it.role == "assistant" }
        assertTrue(userMessages.isNotEmpty())
        assertTrue(assistantMessages.isNotEmpty())
    }

    @Test
    fun assembleWithTriggerEvent() {
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "What can I do here?")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = trigger,
        )

        val lastUser = messages.last { it.role == "user" }
        assertTrue(lastUser.content.contains("What can I do here?"))
    }

    @Test
    fun assembleWithVitality() {
        val vitality = VitalityState.initial().withEnergy(0.1)

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            vitality = vitality,
        )

        // Vitality description should be in a system message
        val systemMessages = messages.filter { it.role == "system" }
        assertTrue(systemMessages.any { it.content.contains("exhausted") })
    }

    @Test
    fun triggerNotDuplicatedInHistory() {
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "Hello!")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(trigger), // trigger already in history
            triggerEvent = trigger,
        )

        val userMessages = messages.filter { it.role == "user" }
        // Should only appear once (in history, not duplicated as trigger)
        assertEquals(1, userMessages.size)
    }

    @Test
    fun estimateTokens() {
        assertEquals(0, FullPromptAssembler.estimateTokens(""))
        assertEquals(1, FullPromptAssembler.estimateTokens("Hi"))
        assertEquals(6, FullPromptAssembler.estimateTokens("Hello, world! How are you?"))
    }

    @Test
    fun buildRoomContextIncludesAllSections() {
        val snapshot = RoomSnapshot(
            roomId = "nexus", name = "Nexus", description = "Hub.", zone = "f",
            exits = listOf(Exit("north", "terminal", "North corridor")),
            entities = listOf(Entity("p1", "Alice", "player", "")),
            objects = listOf(RoomObject("obj-1", "sword", "Sharp", true)),
            hints = emptyList(),
        )
        val context = FullPromptAssembler.buildRoomContext(snapshot)
        assertTrue(context.contains("Nexus"))
        assertTrue(context.contains("Alice"))
        assertTrue(context.contains("north"))
        assertTrue(context.contains("sword"))
    }

    @Test
    fun recencyAnchorIncludesState() {
        val snapshot = RoomSnapshot("nexus", "Nexus", "Hub.", "f",
            emptyList(), listOf(Entity("p1", "Alice", "player", "")), emptyList(), emptyList())
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "Hi")

        val anchor = FullPromptAssembler.buildRecencyAnchor(snapshot, trigger)
        assertTrue(anchor.contains("Nexus"))
        assertTrue(anchor.contains("Alice"))
        assertTrue(anchor.contains("Responding to"))
    }

    // ── Oracle Layer 3.25 tests ─────────────────────────────────────────

    @Test
    fun buildOracleContextEmptyForNoPredictions() {
        val result = FullPromptAssembler.buildOracleContext(emptyList())
        assertEquals("", result)
    }

    @Test
    fun buildOracleContextFiltersLowConfidence() {
        val predictions = listOf(
            PhonePrediction(text = "Low confidence", category = "pattern", confidence = 0.3),
            PhonePrediction(text = "High confidence", category = "anomaly", confidence = 0.8),
        )
        val result = FullPromptAssembler.buildOracleContext(predictions)
        assertTrue(result.contains("High confidence"))
        assertFalse(result.contains("Low confidence"))
    }

    @Test
    fun buildOracleContextLimitsToFive() {
        val predictions = (1..10).map {
            PhonePrediction(text = "Prediction $it", category = "pattern", confidence = 0.9)
        }
        val result = FullPromptAssembler.buildOracleContext(predictions)
        assertTrue(result.contains("(1)"))
        assertTrue(result.contains("(5)"))
        assertFalse(result.contains("(6)"))
    }

    @Test
    fun buildOracleContextMarksActionable() {
        val predictions = listOf(
            PhonePrediction(text = "Do this", category = "recommendation", confidence = 0.9, actionable = true),
        )
        val result = FullPromptAssembler.buildOracleContext(predictions)
        assertTrue(result.contains("[actionable]"))
    }

    @Test
    fun assembleWithOraclePredictions() {
        val predictions = listOf(
            PhonePrediction(text = "Weekly pattern detected", category = "pattern", confidence = 0.85),
        )
        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            oraclePredictions = predictions,
        )
        val systemMessages = messages.filter { it.role == "system" }
        assertTrue(systemMessages.any { it.content.contains("Oracle insights") })
        assertTrue(systemMessages.any { it.content.contains("Weekly pattern detected") })
    }

    @Test
    fun assembleWithoutOracleOmitsLayer() {
        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
            oraclePredictions = null,
        )
        val systemMessages = messages.filter { it.role == "system" }
        assertFalse(systemMessages.any { it.content.contains("Oracle insights") })
    }

    // ── Layer 3: last heard from you ──

    @Test
    fun lastHeardCountsTheLineBeforeTheTriggerNotTheTrigger() {
        // The engine files the trigger in memory before it assembles, so recentSaid
        // ends with it. Counting it put the gap under a minute: the line never showed.
        val earlier = WorldEvent.Said("nexus", now - 3.hours, "p1", "Alice", "Good night.")
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "Morning!")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(earlier, trigger),
            triggerEvent = trigger,
        )

        assertTrue(messages.any { it.role == "system" && it.content == "Last heard from you: 3 hours ago." },
            "expected Layer 3 from the line before the trigger: ${messages.map { it.content }}")
    }

    @Test
    fun lastHeardTellsHerLinesApartByEntityIdNotName() {
        // A person may share her name, and her lines from before a rename carry the old one.
        val person = WorldEvent.Said("nexus", now - 2.hours, "p1", profile.name, "See you tomorrow.")
        val herOldName = WorldEvent.Said("nexus", now - 1.hours, profile.entityId, "Old Name", "Rest well.")
        val trigger = WorldEvent.Said("nexus", now, "p1", profile.name, "I'm back.")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(person, herOldName, trigger),
            triggerEvent = trigger,
        )

        val layer3 = messages.filter { it.role == "system" && it.content.startsWith("Last heard from you") }
        assertEquals(listOf("Last heard from you: 2 hours ago."), layer3.map { it.content })
    }

    @Test
    fun lastHeardIsFromWhoeverSpeaksNowNotAnyoneInTheRoom() {
        // "You" is the person speaking now. Bob's line gave Alice the time since Bob spoke.
        val alice = WorldEvent.Said("nexus", now - 5.hours, "p1", "Alice", "Good night.")
        val bob = WorldEvent.Said("nexus", now - 3.hours, "p2", "Bob", "Anyone up?")
        val trigger = WorldEvent.Said("nexus", now, "p1", "Alice", "Morning!")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(alice, bob, trigger),
            triggerEvent = trigger,
        )

        val layer3 = messages.filter { it.role == "system" && it.content.startsWith("Last heard from you") }
        assertEquals(listOf("Last heard from you: 5 hours ago."), layer3.map { it.content })

        // Someone who has not spoken before has no gap to tell.
        val first = WorldEvent.Said("nexus", now, "p3", "Cara", "Hello?")
        val forCara = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(alice, bob, first),
            triggerEvent = first,
        )
        assertTrue(forCara.none { it.content.startsWith("Last heard from you") }, forCara.map { it.content }.toString())
    }

    @Test
    fun aGreetingCountsAnyPersonsLastLine() {
        // The greeting's trigger is the engine's own "system" line, not a person.
        val alice = WorldEvent.Said("nexus", now - 4.hours, "p1", "Alice", "Good night.")
        val greeting = WorldEvent.Said("nexus", now, "system", "system", "You has entered the room.")

        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = listOf(alice),
            triggerEvent = greeting,
        )

        val layer3 = messages.filter { it.role == "system" && it.content.startsWith("Last heard from you") }
        assertEquals(listOf("Last heard from you: 4 hours ago."), layer3.map { it.content })
    }
}
