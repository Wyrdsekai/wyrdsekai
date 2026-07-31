package org.wyrdsekai.core.safety;

import org.wyrdsekai.core.safety.HouseholdExporter.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Household-level disaster recovery import (§96.9).
 * Round-trip restore from export archive — counterpart to HouseholdExporter.
 *
 * Import flow:
 * 1. Parse export manifest
 * 2. Validate completeness and version compatibility
 * 3. Resolve conflicts (existing agents, rooms, topology)
 * 4. Import agents (manifests, items, lineage)
 * 5. Import rooms (scripts, state)
 * 6. Import topology (cable bundles)
 * 7. Verify round-trip integrity
 *
 * Conflict resolution strategies:
 * - SKIP: keep existing, ignore import
 * - OVERWRITE: replace existing with imported
 * - MERGE: combine (newer wins, items union)
 * - RENAME: import with new ID
 */
public class HouseholdImporter {

    /** Conflict resolution strategy. */
    public enum ConflictStrategy {
        SKIP,       // Keep existing
        OVERWRITE,  // Replace with imported
        MERGE,      // Merge (newer timestamp wins)
        RENAME      // Import with suffix
    }

    /** A conflict detected during import. */
    public record ImportConflict(
        String type,        // "agent", "room", "topology"
        String id,          // entity ID
        String description, // human-readable explanation
        ConflictStrategy resolved
    ) {}

    /** Result of an import operation. */
    public record ImportResult(
        boolean success,
        String householdId,
        int agentsImported,
        int roomsImported,
        boolean topologyImported,
        List<ImportConflict> conflicts,
        List<String> warnings,
        List<String> errors,
        Instant importedAt,
        long durationMs
    ) {
        public boolean hasConflicts() { return !conflicts.isEmpty(); }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /** Sink for agent data during import. */
    @FunctionalInterface
    public interface AgentSink {
        boolean importAgent(AgentExport agent, ConflictStrategy onConflict);
    }

    /** Sink for room data during import. */
    @FunctionalInterface
    public interface RoomSink {
        boolean importRoom(RoomExport room, ConflictStrategy onConflict);
    }

    /** Sink for topology data during import. */
    @FunctionalInterface
    public interface TopologySink {
        boolean importTopology(TopologyExport topology);
    }

    /** Existence checker for conflict detection. */
    public interface ExistenceChecker {
        boolean agentExists(String did);
        boolean roomExists(String roomId);
    }

    private ConflictStrategy defaultStrategy = ConflictStrategy.SKIP;

    /** Set default conflict resolution strategy. */
    public HouseholdImporter withDefaultStrategy(ConflictStrategy strategy) {
        this.defaultStrategy = strategy;
        return this;
    }

    /**
     * Validate an export manifest before importing.
     *
     * @param manifest The export manifest to validate
     * @return List of validation errors (empty = valid)
     */
    public List<String> validate(ExportManifest manifest) {
        var errors = new ArrayList<String>();

        if (manifest == null) {
            errors.add("Manifest is null");
            return errors;
        }
        if (manifest.householdId() == null || manifest.householdId().isBlank()) {
            errors.add("Missing household ID");
        }
        if (manifest.version() == null) {
            errors.add("Missing version");
        } else if (!isCompatibleVersion(manifest.version())) {
            errors.add("Incompatible version: " + manifest.version()
                + " (supported: 1.0)");
        }
        if (manifest.agents() == null) {
            errors.add("Agents list is null");
        } else {
            for (int i = 0; i < manifest.agents().size(); i++) {
                var agent = manifest.agents().get(i);
                if (agent.did() == null || agent.did().isBlank()) {
                    errors.add("Agent at index " + i + " has no DID");
                }
                if (agent.manifestJson() == null || agent.manifestJson().isBlank()) {
                    errors.add("Agent " + agent.did() + " has no manifest data");
                }
            }
        }
        if (manifest.rooms() == null) {
            errors.add("Rooms list is null");
        }

        return errors;
    }

    /**
     * Import a household from an export manifest.
     *
     * @param manifest   The export to import
     * @param checker    Checks for existing entities (conflict detection)
     * @param agentSink  Receives imported agents
     * @param roomSink   Receives imported rooms
     * @param topoSink   Receives imported topology
     * @return Import result with stats and conflicts
     */
    public ImportResult importHousehold(ExportManifest manifest,
                                          ExistenceChecker checker,
                                          AgentSink agentSink,
                                          RoomSink roomSink,
                                          TopologySink topoSink) {
        long start = System.currentTimeMillis();
        var conflicts = new ArrayList<ImportConflict>();
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();

        // 1. Validate
        var validationErrors = validate(manifest);
        if (!validationErrors.isEmpty()) {
            return new ImportResult(false, manifest.householdId(),
                0, 0, false, List.of(), List.of(), validationErrors,
                Instant.now(), System.currentTimeMillis() - start);
        }

        // 2. Import agents
        int agentsImported = 0;
        for (var agent : manifest.agents()) {
            if (checker != null && checker.agentExists(agent.did())) {
                var conflict = new ImportConflict("agent", agent.did(),
                    "Agent " + agent.name() + " already exists", defaultStrategy);
                conflicts.add(conflict);
                if (defaultStrategy == ConflictStrategy.SKIP) {
                    warnings.add("Skipped existing agent: " + agent.did());
                    continue;
                }
            }
            try {
                if (agentSink.importAgent(agent, defaultStrategy)) {
                    agentsImported++;
                } else {
                    warnings.add("Agent import rejected: " + agent.did());
                }
            } catch (Exception e) {
                errors.add("Failed to import agent " + agent.did() + ": " + e.getMessage());
            }
        }

        // 3. Import rooms
        int roomsImported = 0;
        for (var room : manifest.rooms()) {
            if (checker != null && checker.roomExists(room.roomId())) {
                var conflict = new ImportConflict("room", room.roomId(),
                    "Room already exists", defaultStrategy);
                conflicts.add(conflict);
                if (defaultStrategy == ConflictStrategy.SKIP) {
                    warnings.add("Skipped existing room: " + room.roomId());
                    continue;
                }
            }
            try {
                if (roomSink.importRoom(room, defaultStrategy)) {
                    roomsImported++;
                } else {
                    warnings.add("Room import rejected: " + room.roomId());
                }
            } catch (Exception e) {
                errors.add("Failed to import room " + room.roomId() + ": " + e.getMessage());
            }
        }

        // 4. Import topology
        boolean topologyImported = false;
        if (manifest.topology() != null && topoSink != null) {
            try {
                topologyImported = topoSink.importTopology(manifest.topology());
            } catch (Exception e) {
                errors.add("Failed to import topology: " + e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - start;
        boolean success = errors.isEmpty() && agentsImported > 0;

        return new ImportResult(success, manifest.householdId(),
            agentsImported, roomsImported, topologyImported,
            conflicts, warnings, errors, Instant.now(), duration);
    }

    /**
     * Verify round-trip integrity: export → import → export should produce
     * equivalent manifests.
     *
     * @param original  The original export manifest
     * @param imported  The re-export after import
     * @return List of discrepancies (empty = perfect round-trip)
     */
    public List<String> verifyRoundTrip(ExportManifest original, ExportManifest imported) {
        var discrepancies = new ArrayList<String>();

        if (original.agents().size() != imported.agents().size()) {
            discrepancies.add("Agent count mismatch: " + original.agents().size()
                + " vs " + imported.agents().size());
        }
        if (original.rooms().size() != imported.rooms().size()) {
            discrepancies.add("Room count mismatch: " + original.rooms().size()
                + " vs " + imported.rooms().size());
        }

        // Check each agent exists in imported
        var importedDids = imported.agents().stream()
            .map(AgentExport::did).collect(Collectors.toSet());
        for (var agent : original.agents()) {
            if (!importedDids.contains(agent.did())) {
                discrepancies.add("Missing agent after round-trip: " + agent.did());
            }
        }

        // Check each room exists
        var importedRooms = imported.rooms().stream()
            .map(RoomExport::roomId).collect(Collectors.toSet());
        for (var room : original.rooms()) {
            if (!importedRooms.contains(room.roomId())) {
                discrepancies.add("Missing room after round-trip: " + room.roomId());
            }
        }

        return discrepancies;
    }

    /**
     * Human-readable summary of an import result.
     */
    public static String describe(ImportResult result) {
        var sb = new StringBuilder();
        sb.append("=== Household Import ===\n");
        sb.append("Household: ").append(result.householdId()).append("\n");
        sb.append("Status: ").append(result.success() ? "SUCCESS" : "FAILED").append("\n");
        sb.append("Duration: ").append(result.durationMs()).append("ms\n\n");

        sb.append("Imported:\n");
        sb.append("  Agents: ").append(result.agentsImported()).append("\n");
        sb.append("  Rooms: ").append(result.roomsImported()).append("\n");
        sb.append("  Topology: ").append(result.topologyImported() ? "yes" : "no").append("\n");

        if (result.hasConflicts()) {
            sb.append("\nConflicts (").append(result.conflicts().size()).append("):\n");
            for (var c : result.conflicts()) {
                sb.append("  [").append(c.type()).append("] ").append(c.id())
                    .append(" — ").append(c.description())
                    .append(" (").append(c.resolved()).append(")\n");
            }
        }

        if (!result.warnings().isEmpty()) {
            sb.append("\nWarnings:\n");
            for (var w : result.warnings()) {
                sb.append("  - ").append(w).append("\n");
            }
        }

        if (result.hasErrors()) {
            sb.append("\nErrors:\n");
            for (var e : result.errors()) {
                sb.append("  - ").append(e).append("\n");
            }
        }

        return sb.toString();
    }

    private boolean isCompatibleVersion(String version) {
        return "1.0".equals(version);
    }
}
