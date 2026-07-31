package org.wyrdsekai.core.safety;

import java.time.Instant;
import java.util.*;

/**
 * Household-level disaster recovery export (§96.9).
 * Exports full household state as a structured manifest that can be
 * encrypted and archived. Import on new hardware = full restoration.
 * <p>
 * Export format:
 * - household.json — config, zone layout, room scripts
 * - agents/{did}/ — manifest, items, lineage, journal
 * - rooms/scripts/ — JS room scripts
 * - rooms/state/ — room state snapshots
 * - between/topology.json — cable bundle config
 */
public class HouseholdExporter {

    /** A complete household export manifest. */
    public record ExportManifest(
        String householdId,
        Instant exportedAt,
        String version,
        HouseholdConfig config,
        List<AgentExport> agents,
        List<RoomExport> rooms,
        TopologyExport topology,
        ExportStats stats
    ) {}

    public record HouseholdConfig(
        String name,
        String safetyTier,
        Map<String, String> settings,
        List<String> zones
    ) {}

    public record AgentExport(
        String did,
        String name,
        String manifestJson,
        List<String> itemIds,
        int itemCount,
        String lineageJson,
        long journalEntries
    ) {}

    public record RoomExport(
        String roomId,
        String scriptPath,
        String stateJson,
        String zone
    ) {}

    public record TopologyExport(
        List<String> nodeIds,
        Map<String, String> cableBundles
    ) {}

    public record ExportStats(
        int agentCount,
        int roomCount,
        int totalItems,
        long totalJournalEntries,
        long estimatedSizeBytes
    ) {}

    /** Source for agent data. */
    @FunctionalInterface
    public interface AgentSource {
        List<AgentExport> exportAll();
    }

    /** Source for room data. */
    @FunctionalInterface
    public interface RoomSource {
        List<RoomExport> exportAll();
    }

    /** Source for topology data. */
    @FunctionalInterface
    public interface TopologySource {
        TopologyExport export();
    }

    private final String householdId;
    private HouseholdConfig config;

    public HouseholdExporter(String householdId) {
        this.householdId = householdId;
    }

    /** Set household configuration for export. */
    public HouseholdExporter withConfig(HouseholdConfig config) {
        this.config = config;
        return this;
    }

    /**
     * Export the full household state.
     *
     * @param agentSource    provides agent data
     * @param roomSource     provides room data
     * @param topologySource provides topology data
     * @return the export manifest
     */
    public ExportManifest export(AgentSource agentSource, RoomSource roomSource,
                                  TopologySource topologySource) {
        var agents = agentSource != null ? agentSource.exportAll() : List.<AgentExport>of();
        var rooms = roomSource != null ? roomSource.exportAll() : List.<RoomExport>of();
        var topology = topologySource != null ? topologySource.export()
            : new TopologyExport(List.of(), Map.of());

        int totalItems = agents.stream().mapToInt(AgentExport::itemCount).sum();
        long totalJournal = agents.stream().mapToLong(AgentExport::journalEntries).sum();
        long estimatedSize = totalItems * 512L + totalJournal * 256L + rooms.size() * 4096L;

        var stats = new ExportStats(agents.size(), rooms.size(), totalItems,
            totalJournal, estimatedSize);

        return new ExportManifest(
            householdId, Instant.now(), "1.0",
            config != null ? config : new HouseholdConfig(householdId, "standard", Map.of(), List.of()),
            List.copyOf(agents), List.copyOf(rooms), topology, stats
        );
    }

    /**
     * Validate an export manifest for completeness.
     *
     * @return list of warnings (empty = valid)
     */
    public static List<String> validate(ExportManifest manifest) {
        var warnings = new ArrayList<String>();

        if (manifest.householdId() == null || manifest.householdId().isBlank()) {
            warnings.add("Missing household ID");
        }
        if (manifest.agents().isEmpty()) {
            warnings.add("No agents in export");
        }
        for (var agent : manifest.agents()) {
            if (agent.did() == null || agent.did().isBlank()) {
                warnings.add("Agent with missing DID");
            }
            if (agent.manifestJson() == null || agent.manifestJson().isBlank()) {
                warnings.add("Agent " + agent.did() + " has no manifest");
            }
        }
        if (manifest.rooms().isEmpty()) {
            warnings.add("No rooms in export");
        }

        return warnings;
    }

    /**
     * Human-readable summary of an export.
     */
    public static String describe(ExportManifest manifest) {
        var sb = new StringBuilder();
        sb.append("=== Household Export ===\n");
        sb.append("Household: ").append(manifest.householdId()).append("\n");
        sb.append("Exported: ").append(manifest.exportedAt()).append("\n");
        sb.append("Version: ").append(manifest.version()).append("\n\n");

        sb.append("Agents: ").append(manifest.stats().agentCount()).append("\n");
        for (var agent : manifest.agents()) {
            sb.append("  - ").append(agent.name()).append(" (").append(agent.did()).append(")")
                .append(" — ").append(agent.itemCount()).append(" items\n");
        }

        sb.append("\nRooms: ").append(manifest.stats().roomCount()).append("\n");
        sb.append("Total items: ").append(manifest.stats().totalItems()).append("\n");
        sb.append("Journal entries: ").append(manifest.stats().totalJournalEntries()).append("\n");
        sb.append("Estimated size: ").append(manifest.stats().estimatedSizeBytes() / 1024)
            .append(" KB\n");

        return sb.toString();
    }
}
