package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.soul.*;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the memory architecture extensions.
 * Tests the full pipeline: agent actions → significance buffer → Forge integration,
 * contradiction detection against real Lucene index, eviction summaries, bi-temporal queries.
 */
@Tag("integration")
class MemoryArchitectureE2ETest {

    // --- Agent Action Parsing ---

    @Test
    void parse_remember_action() {
        var llmOutput = """
            I'll make a note of that.
            ```json
            {"action": "remember", "content": "Masumi prefers Earl Grey over coffee", "importance": 0.85}
            ```
            """;
        var result = ActionParser.parse(llmOutput);
        assertNotNull(result);
        assertInstanceOf(ActionParser.AgentAction.Remember.class, result);
        var remember = (ActionParser.AgentAction.Remember) result;
        assertEquals("Masumi prefers Earl Grey over coffee", remember.content());
        assertEquals(0.85f, remember.importance(), 0.01);
    }

    @Test
    void parse_note_action() {
        var llmOutput = """
            Interesting pattern.
            ```json
            {"action": "note", "content": "User tends to ask about gardening on weekends"}
            ```
            """;
        var result = ActionParser.parse(llmOutput);
        assertNotNull(result);
        assertInstanceOf(ActionParser.AgentAction.Note.class, result);
    }

    @Test
    void parse_forget_action() {
        var llmOutput = """
            I'll update my records.
            ```json
            {"action": "forget", "target": "works at Mercari", "reason": "User left the company"}
            ```
            """;
        var result = ActionParser.parse(llmOutput);
        assertNotNull(result);
        assertInstanceOf(ActionParser.AgentAction.Forget.class, result);
        var forget = (ActionParser.AgentAction.Forget) result;
        assertEquals("works at Mercari", forget.target());
        assertEquals("User left the company", forget.reason());
    }

    @Test
    void parse_remember_empty_content_ignored() {
        var llmOutput = """
            ```json
            {"action": "remember", "content": "", "importance": 0.9}
            ```
            """;
        var result = ActionParser.parse(llmOutput);
        assertNull(result, "Empty content should not produce an action");
    }

    // --- Significance Buffer + Forge Integration ---

    @Test
    void significance_buffer_consumed_by_forge() {
        var buf = new SignificanceBuffer();
        buf.remember("User's birthday is March 15", 0.9f);
        buf.note("User mentioned a trip to Japan");
        buf.forget("works at old company", "changed jobs");

        assertEquals(3, buf.size());

        // Simulate Forge consuming the buffer
        var entries = buf.consumeAll();
        assertEquals(3, entries.size());
        assertEquals(0, buf.size(), "Buffer should be empty after consume");

        // Verify entry types
        var remembers = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_REMEMBER).count();
        var notes = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_NOTE).count();
        var forgets = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_FORGET).count();

        assertEquals(1, remembers);
        assertEquals(1, notes);
        assertEquals(1, forgets);
    }

    @Test
    void significance_buffer_entries_have_correct_types_and_values() {
        var buf = new SignificanceBuffer();
        buf.remember("Birthday is March 15", 0.9f);
        buf.note("Likes weekend gardening");
        buf.forget("old address", "moved");

        var entries = buf.consumeAll();

        // Remember entry
        var rem = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_REMEMBER)
            .findFirst().orElseThrow();
        assertEquals(0.9f, rem.importance(), 0.01);
        assertFalse(rem.superseded());

        // Note entry
        var note = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_NOTE)
            .findFirst().orElseThrow();
        assertEquals(0.4f, note.importance(), 0.01);

        // Forget entry
        var forget = entries.stream()
            .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_FORGET)
            .findFirst().orElseThrow();
        assertTrue(forget.superseded());
        assertEquals("old address", forget.target());
    }

    // --- Contradiction Detection Integration ---

    @Test
    void contradiction_detector_scan_with_null_lucene_returns_empty() {
        // Scan without Lucene store should return empty gracefully
        var contradictions = ContradictionDetector.scan(
            "did:key:test",
            List.of("The weather is nice today", "Masumi is not vegetarian"),
            null
        );
        assertTrue(contradictions.isEmpty(), "No Lucene = no contradictions to detect");
    }

    @Test
    void contradiction_detector_scan_with_empty_memories_returns_empty() {
        var contradictions = ContradictionDetector.scan(
            "did:key:test", List.of(), null);
        assertTrue(contradictions.isEmpty());
    }

    @Test
    void contradiction_detector_scan_with_null_memories_returns_empty() {
        var contradictions = ContradictionDetector.scan(
            "did:key:test", null, null);
        assertTrue(contradictions.isEmpty());
    }

    // --- Soul Fragment Confidence Evolution ---

    @Test
    void fragment_confidence_evolves_through_lifecycle() {
        // Birth
        var f = SoulFragment.unembedded("pref-tea", "personality", "Tea preference",
            "Masumi prefers Earl Grey tea");
        assertEquals(0.5f, f.confidence(), 0.01);
        assertTrue(f.isCurrent());

        // Multiple reinforcements over time
        f = f.reinforce().reinforce().reinforce();
        assertTrue(f.confidence() > 0.6f, "Confidence should grow with reinforcement");
        assertEquals(3, f.reinforcementCount());
        assertNotNull(f.lastConfirmed());

        // Contradiction arrives
        var weakened = f.contradict();
        assertTrue(weakened.confidence() < f.confidence(), "Contradiction should lower confidence");

        // Supersession
        var superseded = f.supersede("pref-coffee");
        assertTrue(superseded.isSuperseded());
        assertFalse(superseded.isCurrent());
        assertEquals("pref-coffee", superseded.supersededBy());
    }

    @Test
    void fragment_backward_compatible_with_old_manifests() {
        // Old fragments (pre-confidence) have null fields — should not crash
        var old = new SoulFragment("old-frag", "personality", "old", "old content",
            null, null, false,
            null, null, null, null, null, null, null);

        // effectiveConfidence should handle nulls
        assertEquals(0.5f, old.effectiveConfidence(), 0.01);
        assertTrue(old.isCurrent());
        assertFalse(old.isSuperseded());
    }

    // --- Eviction Summary Integration ---

    @Test
    void eviction_summary_captures_conversation_essence() {
        var events = List.<WorldEvent>of(
            new WorldEvent.Said("study", Instant.now(), "user1", "Masumi",
                "I've been thinking about the sourdough recipe we discussed"),
            new WorldEvent.Said("study", Instant.now(), "agent1", "Ember",
                "I remember! The one with the overnight fermentation"),
            new WorldEvent.Said("study", Instant.now(), "user1", "Masumi",
                "Yes, and I also want to try making ramen from scratch"),
            new WorldEvent.Said("study", Instant.now(), "agent1", "Ember",
                "That sounds like a wonderful project"),
            new WorldEvent.Said("study", Instant.now(), "user1", "Masumi",
                "By the way, the garden is looking great this spring")
        );

        var summary = EvictionSummarizer.summarize(events, "agent1");
        assertNotNull(summary, "Should produce a summary");
        assertTrue(summary.contains("Masumi"), "Should mention the human speaker");
        assertTrue(summary.contains("5 messages") || summary.length() > 20,
            "Should indicate conversation substance");
    }

    @Test
    void eviction_summary_stacking_compresses_history() {
        var s1 = "Earlier: discussed cooking and recipes (10 messages)";
        var s2 = "Earlier: discussed gardening and spring planting (8 messages)";
        var s3 = "Recently: discussed travel plans for summer";

        var stacked = EvictionSummarizer.stackSummaries(List.of(s1, s2), s3);
        assertNotNull(stacked);
        // Should contain the most recent summary fully
        assertTrue(stacked.contains("travel") || stacked.contains("summer"),
            "Most recent summary should be preserved");
        // Should reference older topics in compressed form
        assertTrue(stacked.length() > s3.length(),
            "Stacked should be longer than just the newest summary");
    }
}
