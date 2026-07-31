package org.wyrdsekai.core.safety;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.safety.HouseholdExporter.*;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §96.9 — HouseholdImporter (disaster recovery restore).
 */
class HouseholdImporterTest {

    private HouseholdImporter importer;
    private ExportManifest validManifest;

    @BeforeEach
    void setup() {
        importer = new HouseholdImporter();
        validManifest = createValidManifest();
    }

    @Test
    void validate_valid_manifest() {
        var errors = importer.validate(validManifest);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_null_manifest() {
        var errors = importer.validate(null);
        assertFalse(errors.isEmpty());
    }

    @Test
    void validate_missing_household_id() {
        var bad = new ExportManifest(null, Instant.now(), "1.0",
            new HouseholdConfig("test", "standard", Map.of(), List.of()),
            List.of(new AgentExport("did:key:z6Mk1", "Lain", "{}", List.of(), 5, "{}", 100)),
            List.of(), new TopologyExport(List.of(), Map.of()),
            new ExportStats(1, 0, 5, 100, 5000));
        var errors = importer.validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("household ID")));
    }

    @Test
    void validate_incompatible_version() {
        var bad = new ExportManifest("h1", Instant.now(), "2.0",
            new HouseholdConfig("test", "standard", Map.of(), List.of()),
            List.of(new AgentExport("did:key:z6Mk1", "Lain", "{}", List.of(), 5, "{}", 100)),
            List.of(), new TopologyExport(List.of(), Map.of()),
            new ExportStats(1, 0, 5, 100, 5000));
        var errors = importer.validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("version")));
    }

    @Test
    void validate_agent_without_did() {
        var bad = new ExportManifest("h1", Instant.now(), "1.0",
            new HouseholdConfig("test", "standard", Map.of(), List.of()),
            List.of(new AgentExport(null, "Lain", "{}", List.of(), 5, "{}", 100)),
            List.of(), new TopologyExport(List.of(), Map.of()),
            new ExportStats(1, 0, 5, 100, 5000));
        var errors = importer.validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no DID")));
    }

    @Test
    void import_to_empty_household() {
        var result = importer.importHousehold(validManifest,
            new EmptyChecker(),
            (agent, strategy) -> true,
            (room, strategy) -> true,
            topology -> true);

        assertTrue(result.success());
        assertEquals(2, result.agentsImported());
        assertEquals(2, result.roomsImported());
        assertTrue(result.topologyImported());
        assertFalse(result.hasConflicts());
        assertFalse(result.hasErrors());
    }

    @Test
    void import_skips_existing_agents() {
        var checker = new TestChecker(Set.of("did:key:z6Mk1"), Set.of());
        var result = importer.importHousehold(validManifest, checker,
            (agent, strategy) -> true,
            (room, strategy) -> true,
            topology -> true);

        assertTrue(result.success());
        assertEquals(1, result.agentsImported()); // Only agent 2
        assertTrue(result.hasConflicts());
        assertEquals(1, result.conflicts().size());
        assertEquals("agent", result.conflicts().get(0).type());
    }

    @Test
    void import_with_overwrite_strategy() {
        importer.withDefaultStrategy(HouseholdImporter.ConflictStrategy.OVERWRITE);
        var checker = new TestChecker(Set.of("did:key:z6Mk1"), Set.of());
        var result = importer.importHousehold(validManifest, checker,
            (agent, strategy) -> true,
            (room, strategy) -> true,
            topology -> true);

        assertEquals(2, result.agentsImported()); // Both imported (overwrite)
        assertTrue(result.hasConflicts()); // Conflict still recorded
    }

    @Test
    void import_handles_agent_failure() {
        var result = importer.importHousehold(validManifest,
            new EmptyChecker(),
            (agent, strategy) -> { throw new RuntimeException("DB error"); },
            (room, strategy) -> true,
            topology -> true);

        assertFalse(result.success());
        assertTrue(result.hasErrors());
        assertTrue(result.errors().get(0).contains("DB error"));
    }

    @Test
    void import_handles_rejected_agent() {
        var result = importer.importHousehold(validManifest,
            new EmptyChecker(),
            (agent, strategy) -> false, // Sink rejects
            (room, strategy) -> true,
            topology -> true);

        assertFalse(result.success());
        assertEquals(0, result.agentsImported());
    }

    @Test
    void import_skips_existing_rooms() {
        var checker = new TestChecker(Set.of(), Set.of("nexus"));
        var result = importer.importHousehold(validManifest, checker,
            (agent, strategy) -> true,
            (room, strategy) -> true,
            topology -> true);

        assertEquals(1, result.roomsImported()); // Only home room
    }

    @Test
    void verify_round_trip_perfect() {
        var discrepancies = importer.verifyRoundTrip(validManifest, validManifest);
        assertTrue(discrepancies.isEmpty());
    }

    @Test
    void verify_round_trip_missing_agent() {
        var partial = new ExportManifest("h1", Instant.now(), "1.0",
            validManifest.config(),
            List.of(validManifest.agents().get(0)), // Only first agent
            validManifest.rooms(), validManifest.topology(), validManifest.stats());

        var discrepancies = importer.verifyRoundTrip(validManifest, partial);
        assertFalse(discrepancies.isEmpty());
        assertTrue(discrepancies.get(0).contains("Agent count"));
    }

    @Test
    void describe_result() {
        var result = importer.importHousehold(validManifest,
            new EmptyChecker(),
            (agent, strategy) -> true,
            (room, strategy) -> true,
            topology -> true);

        var description = HouseholdImporter.describe(result);
        assertTrue(description.contains("SUCCESS"));
        assertTrue(description.contains("Agents: 2"));
        assertTrue(description.contains("Rooms: 2"));
    }

    // --- Helpers ---

    private ExportManifest createValidManifest() {
        var agents = List.of(
            new AgentExport("did:key:z6Mk1", "Lain", "{\"did\":\"did:key:z6Mk1\"}",
                List.of("item1"), 5, "{}", 100),
            new AgentExport("did:key:z6Mk2", "Rei", "{\"did\":\"did:key:z6Mk2\"}",
                List.of("item2"), 3, "{}", 50)
        );
        var rooms = List.of(
            new RoomExport("nexus", "rooms/nexus.js", "{}", "zone-1"),
            new RoomExport("home", "rooms/home.js", "{}", "zone-1")
        );
        var config = new HouseholdConfig("TestHouse", "standard",
            Map.of("setting1", "value1"), List.of("zone-1"));
        var topology = new TopologyExport(
            List.of("node-1"), Map.of("cable-1", "node-1:node-2"));
        var stats = new ExportStats(2, 2, 8, 150, 10000);
        return new ExportManifest("household-1", Instant.now(), "1.0",
            config, agents, rooms, topology, stats);
    }

    private static class EmptyChecker implements HouseholdImporter.ExistenceChecker {
        @Override public boolean agentExists(String did) { return false; }
        @Override public boolean roomExists(String roomId) { return false; }
    }

    private static class TestChecker implements HouseholdImporter.ExistenceChecker {
        private final Set<String> existingAgents;
        private final Set<String> existingRooms;
        TestChecker(Set<String> agents, Set<String> rooms) {
            this.existingAgents = agents;
            this.existingRooms = rooms;
        }
        @Override public boolean agentExists(String did) { return existingAgents.contains(did); }
        @Override public boolean roomExists(String roomId) { return existingRooms.contains(roomId); }
    }
}
