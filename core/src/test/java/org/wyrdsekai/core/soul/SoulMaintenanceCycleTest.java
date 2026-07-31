package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for awake (light) consolidation in SoulMaintenanceCycle.
 */
class SoulMaintenanceCycleTest {

    // --- runLightConsolidation ---

    @Test
    void lightConsolidation_emptyInputs_returnsZeroSummary() {
        var memory = CompactedMemory.empty();
        var buffer = new SignificanceBuffer();
        var fingerprint = BehavioralFingerprint.empty();

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(), buffer, fingerprint);

        assertEquals(0, summary.eventsScored());
        assertEquals(0, summary.nodesCompacted());
        assertEquals(0, summary.staleDropped());
    }

    @Test
    void lightConsolidation_scoresSignificanceBufferEntries() {
        var memory = CompactedMemory.empty();
        var buffer = new SignificanceBuffer();
        buffer.remember("User loves coffee", 0.9f);
        buffer.note("Mentioned cats");
        buffer.forget("old job", "no longer relevant");

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(), buffer, BehavioralFingerprint.empty());

        // All 3 entries scored
        assertEquals(3, summary.eventsScored());
    }

    @Test
    void lightConsolidation_prunesStaleMemoryNodes() {
        // Create memory with one very-low-importance node that should be pruned
        var staleNode = new MemoryNode(
            "mem-stale", "old forgotten thing", List.of("forgotten"),
            0.04f, 0.0f, false, "none", Instant.now(), 0, "en");
        var healthyNode = MemoryNode.neutral("mem-healthy", "important fact",
            List.of("important"));
        var formativeNode = MemoryNode.formative("mem-formative",
            "core identity", List.of("identity"), "joy", 0.9f);

        var memory = new CompactedMemory(
            List.of(staleNode, healthyNode, formativeNode),
            List.of(), Map.of());
        var buffer = new SignificanceBuffer();

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(), buffer, BehavioralFingerprint.empty());

        // The stale node (0.04 importance, after 0.05 light decay → ~-0.01) should be pruned
        // Healthy (0.5 - 0.05 = 0.45) and formative (immune) survive
        assertEquals(2, summary.nodesCompacted());
        assertTrue(summary.staleDropped() >= 1, "At least the stale node should be dropped");
    }

    @Test
    void lightConsolidation_mergesDuplicateNodes() {
        // Two non-formative nodes with identical keywords
        var node1 = new MemoryNode(
            "mem-1", "User said they like tea", List.of("tea", "likes", "beverage"),
            0.6f, 0.2f, false, "none", Instant.now(), 1, "en");
        var node2 = new MemoryNode(
            "mem-2", "User mentioned enjoying tea", List.of("tea", "likes", "beverage"),
            0.4f, 0.1f, false, "none", Instant.now(), 0, "en");
        var distinct = MemoryNode.neutral("mem-3", "Weather is cold",
            List.of("weather", "cold"));

        var memory = new CompactedMemory(
            List.of(node1, node2, distinct), List.of(), Map.of());

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(), new SignificanceBuffer(), BehavioralFingerprint.empty());

        // node1 and node2 should merge (100% keyword overlap), distinct survives
        assertEquals(2, summary.nodesCompacted());
        assertTrue(summary.staleDropped() >= 1, "Merged duplicate should count as dropped");
    }

    @Test
    void lightConsolidation_preservesFormativeMemories() {
        // Formative node with low importance — should still survive (formative exempt)
        var formative = new MemoryNode(
            "mem-core", "I am Ember, a companion.",
            List.of("identity", "ember"),
            0.01f, 0.9f, true, "joy", Instant.now(), 5, "en");

        var memory = new CompactedMemory(List.of(formative), List.of(), Map.of());

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(), new SignificanceBuffer(), BehavioralFingerprint.empty());

        // Formative should survive even with low importance
        assertEquals(1, summary.nodesCompacted());
        assertEquals(0, summary.staleDropped());
    }

    @Test
    void lightConsolidation_withRecentSaidEvents_doesNotCrash() {
        var memory = CompactedMemory.empty();
        var said1 = new WorldEvent.Said("room-1", Instant.now(),
            "player-1", "Alice", "Hello there!");
        var said2 = new WorldEvent.Said("room-1", Instant.now(),
            "agent-1", "Ember", "Hi Alice!");

        var summary = SoulMaintenanceCycle.runLightConsolidation(
            memory, List.of(said1, said2), new SignificanceBuffer(),
            BehavioralFingerprint.empty());

        assertNotNull(summary);
        assertEquals(0, summary.nodesCompacted());
    }

    // --- mergeDuplicateNodes ---

    @Test
    void mergeDuplicateNodes_neverMergesFormative() {
        var formative1 = MemoryNode.formative("f1", "core trait 1",
            List.of("identity", "core"), "joy", 0.8f);
        var formative2 = MemoryNode.formative("f2", "core trait 2",
            List.of("identity", "core"), "joy", 0.9f);

        var result = SoulMaintenanceCycle.mergeDuplicateNodes(
            List.of(formative1, formative2));

        // Both formatives should survive — never merged
        assertEquals(2, result.size());
    }
}
