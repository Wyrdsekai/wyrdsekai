package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.soul.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for the agent narration system:
 * - DreamWeaver generates dreams from Forge output
 * - AgentNarration generates contextual sleep/wake/room/memory speech
 * - ActionParser strips raw JSON from agent speech
 * - Fragment reinforcement across Forge cycles
 * - Contradiction detection applies to fragment confidence
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentNarrationE2ETest {

    // ==================================================================
    // Dream generation end-to-end
    // ==================================================================

    @Test @Order(1)
    void dream_from_full_manifest() {
        // Simulate a real Forge output — manifest with topics, emotions, fragments, relationships
        var fingerprint = new BehavioralFingerprint(
            Map.of("energy", 0.5f), Map.of(), Map.of(), Map.of("say", 0.6f, "move", 0.4f),
            Map.of("exploration", 0.9f, "the nexus", 0.5f, "knowledge", 0.4f),
            Map.of(), 45.0f, 2.0f,
            List.of("uses emotes", "asks questions"),
            Map.of("curiosity", 0.8f, "wonder", 0.5f));

        var fragment = new SoulFragment("f1", "memory", "a discovery",
            "I found something unexpected in the library today",
            null, null, false, 0.6f, 1, Instant.now(), null, null, null, null);

        var rel = new Relationship("did:key:operator", "Masumi", 0.9f, 0.8f, 2, 15,
            Instant.now(), "My steward");

        var manifest = new SoulManifest(
            "did:key:test", null, null, null, 5, Instant.now(), null,
            null, null, List.of(fragment), 3, null,
            null, null,
            null, List.of(rel), null, null,
            null, fingerprint, null, null, null, null, null, null, null, null);

        var before = new CompactedMemory(
            List.of(memNode("n1"), memNode("n2"), memNode("n3"), memNode("n4")),
            List.of(), Map.of());
        var after = new CompactedMemory(
            List.of(memNode("n1"), memNode("n2")),
            List.of(), Map.of());

        var dream = DreamWeaver.weave(manifest, before, after);
        assertTrue(dream.isPresent(), "Full manifest should produce a dream");
        assertTrue(dream.get().startsWith("*stirs from sleep"), "Dream should have wake framing");
        assertFalse(dream.get().contains("{"), "Dream should not contain JSON");
    }

    @Test @Order(2)
    void dream_variety_across_cycles() {
        var fingerprint = new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of("say", 0.5f),
            Map.of("exploration", 0.8f, "rooms", 0.6f),
            Map.of(), 0, 0, List.of(),
            Map.of("curiosity", 0.7f, "warmth", 0.4f));

        var manifest = new SoulManifest(
            "did:key:test", null, null, null, 3, Instant.now(), null,
            null, null, List.of(), 3, null, null, null,
            null, List.of(), null, null, null, fingerprint, null, null, null, null, null, null, null, null);

        var dreams = new HashSet<String>();
        for (int i = 0; i < 30; i++) {
            DreamWeaver.weave(manifest, null, null).ifPresent(dreams::add);
        }
        assertTrue(dreams.size() >= 3, "Should produce varied dreams (got " + dreams.size() + ")");
    }

    // ==================================================================
    // Sleep narration end-to-end
    // ==================================================================

    @Test @Order(10)
    void sleep_narration_reflects_exhaustion() {
        var text = AgentNarration.sleepEntry(0.05, null, 0, false);
        assertNotNull(text);
        // Exhaustion lines are more desperate
        assertTrue(text.contains("*"), "Should be an emote");
    }

    @Test @Order(11)
    void sleep_narration_reflects_emotion() {
        var joyText = AgentNarration.sleepEntry(0.20, "joy", 5, false);
        var griefText = AgentNarration.sleepEntry(0.20, "grief", 5, false);
        // They should be different (different emotions = different narration)
        // Run multiple times to account for randomness
        var joySet = new HashSet<String>();
        var griefSet = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            joySet.add(AgentNarration.sleepEntry(0.20, "joy", 5, false));
            griefSet.add(AgentNarration.sleepEntry(0.20, "grief", 5, false));
        }
        // The sets should not be identical (different emotion pools)
        assertNotEquals(joySet, griefSet, "Different emotions should produce different narration pools");
    }

    @Test @Order(12)
    void sleep_narration_reflects_unresolved() {
        var resolved = AgentNarration.sleepEntry(0.20, null, 3, false);
        var unresolved = AgentNarration.sleepEntry(0.20, null, 3, true);
        // Unresolved should reference the dangling thread
        var unresolvedSet = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            unresolvedSet.add(AgentNarration.sleepEntry(0.20, null, 3, true));
        }
        assertTrue(unresolvedSet.stream().anyMatch(s ->
            s.contains("unfinished") || s.contains("sorted") || s.contains("dangling")),
            "Unresolved narration should reference something pending");
    }

    // ==================================================================
    // Room arrival narration
    // ==================================================================

    @Test @Order(20)
    void room_arrival_first_visit_with_entity() {
        var text = AgentNarration.roomArrival("The Library", List.of("Ember"), List.of("card catalog"), true);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("Ember"), "Should mention the entity present");
    }

    @Test @Order(21)
    void room_arrival_first_visit_with_object() {
        var text = AgentNarration.roomArrival("The Forge", List.of(), List.of("obsidian anvil"), true);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("obsidian anvil"), "Should notice the object");
    }

    @Test @Order(22)
    void room_arrival_return_visit_mostly_silent() {
        int silent = 0;
        for (int i = 0; i < 100; i++) {
            if (AgentNarration.roomArrival("The Nexus", List.of("Wyrd"), List.of(), false).isEmpty()) {
                silent++;
            }
        }
        assertTrue(silent > 60, "Return visits should mostly be silent (" + silent + "/100)");
    }

    // ==================================================================
    // Memory reinforcement narration
    // ==================================================================

    @Test @Order(30)
    void memory_reinforcement_high_confidence() {
        var text = AgentNarration.memoryReinforced("core identity", 0.95f);
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("*") || text.get().contains("certain") || text.get().contains("sure"),
            "High confidence should feel solid");
    }

    @Test @Order(31)
    void memory_reinforcement_low_confidence_silent() {
        var text = AgentNarration.memoryReinforced("weak signal", 0.3f);
        assertTrue(text.isEmpty(), "Low confidence reinforcement should be silent");
    }

    // ==================================================================
    // Contradiction narration
    // ==================================================================

    @Test @Order(40)
    void contradiction_detected_narration() {
        var text = AgentNarration.contradictionDetected("I prefer solitude", "I sought out company");
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("*") || text.get().contains("contradict") || text.get().contains("confusion"),
            "Should express internal dissonance");
    }

    // ==================================================================
    // JSON speech leak fix
    // ==================================================================

    @Test @Order(50)
    void raw_json_stripped_from_prose() {
        // This was the actual bug: LLM outputs raw JSON as speech
        var input = "{\"action\": \"go_to_room\", \"target\": \"to-new-room-1221\", \"reason\": \"unvisited exit\"}";
        var prose = ActionParser.extractProse(input);
        assertTrue(prose.isEmpty(), "Raw JSON should be stripped entirely: got '" + prose + "'");
    }

    @Test @Order(51)
    void json_with_prose_prefix_keeps_prose() {
        var input = "I haven't explored the Terminal yet. Let me go north. {\"action\": \"go_to_room\", \"target\": \"north\"}";
        var prose = ActionParser.extractProse(input);
        assertTrue(prose.contains("Terminal"), "Should keep the prose prefix");
        assertFalse(prose.contains("go_to_room"), "Should strip the JSON");
    }

    @Test @Order(52)
    void fenced_json_at_start_no_prose() {
        var input = "```json\n{\"action\":\"suggest_hints\"}\n```";
        var prose = ActionParser.extractProse(input);
        assertTrue(prose.isEmpty(), "Fenced JSON at start should produce empty prose");
    }

    @Test @Order(53)
    void fenced_json_with_prose_before() {
        var input = "Here's what I think we should do.\n```json\n{\"action\":\"say\"}\n```";
        var prose = ActionParser.extractProse(input);
        assertEquals("Here's what I think we should do.", prose);
    }

    @Test @Order(54)
    void normal_text_preserved() {
        var prose = ActionParser.extractProse("Hello, it's nice to meet you. How are you today?");
        assertEquals("Hello, it's nice to meet you. How are you today?", prose);
    }

    // ==================================================================
    // Fragment reinforcement across cycles
    // ==================================================================

    @Test @Order(60)
    void fragment_reinforcement_increases_confidence() {
        var original = SoulFragment.unembedded("f1", "behavior", "curious", "I ask a lot of questions");
        var reinforced = original.reinforce();
        assertTrue(reinforced.confidence() > original.confidence(),
            "Reinforced fragment should have higher confidence");
        assertEquals(1, reinforced.reinforcementCount());
    }

    @Test @Order(61)
    void fragment_contradiction_decreases_confidence() {
        var original = new SoulFragment("f1", "belief", "patient", "I am a patient agent",
            null, null, false, 0.8f, 3, Instant.now(), Instant.now(), null, null, null);
        var contradicted = original.contradict();
        assertTrue(contradicted.confidence() < original.confidence(),
            "Contradicted fragment should have lower confidence");
    }

    @Test @Order(62)
    void reinforcement_merges_matching_fragments() {
        var existing = List.of(
            SoulFragment.unembedded("f1", "behavior", "curious", "I explore new rooms"),
            SoulFragment.unembedded("f2", "memory", "discovery", "I found the library"));

        var newFragments = List.of(
            SoulFragment.unembedded("f3", "behavior", "curious", "I always want to see what's around the corner"),
            SoulFragment.unembedded("f4", "behavior", "helpful", "I assist when asked"));

        var merged = SoulMaintenanceCycle.reinforceFragments(existing, newFragments);

        // "curious" exists in both → should be reinforced, not duplicated
        var curiousFragments = merged.stream()
            .filter(f -> "curious".equals(f.label()))
            .toList();
        assertEquals(1, curiousFragments.size(), "Matching fragments should merge, not duplicate");
        assertTrue(curiousFragments.getFirst().reinforcementCount() > 0,
            "Merged fragment should have reinforcement count > 0");

        // "helpful" is new → should be added
        assertTrue(merged.stream().anyMatch(f -> "helpful".equals(f.label())),
            "New fragment should be added");

        // "discovery" wasn't matched → should be preserved
        assertTrue(merged.stream().anyMatch(f -> "discovery".equals(f.label())),
            "Unmatched existing fragment should be preserved");
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private MemoryNode memNode(String id) {
        return new MemoryNode(id, "something happened", List.of("event"),
            1.0f, 0.5f, false, null, Instant.now(), 1, null);
    }
}
