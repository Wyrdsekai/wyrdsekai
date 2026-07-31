package org.wyrdsekai.core.safety;

import java.time.Instant;
import java.util.*;

/**
 * Single-agent soul export for disaster recovery and portability (§96.9).
 * Exports complete agent state: manifest + all items + lineage + economic history.
 * Can be imported on new hardware for full restoration.
 */
public class SoulExporter {

    /** A complete soul export. */
    public record SoulArchive(
        String agentDid,
        String agentName,
        Instant exportedAt,
        String version,
        String manifestJson,
        List<ItemExport> items,
        String lineageJson,
        List<String> relationships,
        EconomicSnapshot economics,
        ArchiveStats stats
    ) {}

    public record ItemExport(
        String itemId,
        String type,
        String label,
        String contentJson,
        double significance,
        String creatorDid,
        Instant created,
        boolean tombstoned
    ) {}

    public record EconomicSnapshot(
        double balance,
        double lifetimeEarnings,
        double lifetimeSpent,
        int transactionCount
    ) {}

    public record ArchiveStats(
        int itemCount,
        int tombstonedCount,
        int relationshipCount,
        long estimatedSizeBytes
    ) {}

    /** Source for soul item data. */
    @FunctionalInterface
    public interface ItemSource {
        List<ItemExport> exportAll(String agentDid);
    }

    /** Source for relationship data. */
    @FunctionalInterface
    public interface RelationshipSource {
        List<String> exportAll(String agentDid);
    }

    /**
     * Export a single agent's complete soul.
     */
    public SoulArchive export(String agentDid, String agentName,
                               String manifestJson, String lineageJson,
                               ItemSource itemSource,
                               RelationshipSource relationshipSource,
                               EconomicSnapshot economics) {
        var items = itemSource != null ? itemSource.exportAll(agentDid) : List.<ItemExport>of();
        var relationships = relationshipSource != null
            ? relationshipSource.exportAll(agentDid) : List.<String>of();

        int tombstoned = (int) items.stream().filter(ItemExport::tombstoned).count();
        long estimatedSize = items.size() * 1024L + 4096L; // rough estimate

        var stats = new ArchiveStats(items.size(), tombstoned,
            relationships.size(), estimatedSize);

        return new SoulArchive(agentDid, agentName, Instant.now(), "1.0",
            manifestJson, List.copyOf(items), lineageJson,
            List.copyOf(relationships),
            economics != null ? economics : new EconomicSnapshot(0, 0, 0, 0),
            stats);
    }

    /** Validate a soul archive for completeness. */
    public static List<String> validate(SoulArchive archive) {
        var warnings = new ArrayList<String>();
        if (archive.agentDid() == null || archive.agentDid().isBlank()) {
            warnings.add("Missing agent DID");
        }
        if (archive.manifestJson() == null || archive.manifestJson().isBlank()) {
            warnings.add("Missing manifest");
        }
        if (archive.items().isEmpty()) {
            warnings.add("No items in archive");
        }
        return warnings;
    }

    /** Human-readable description. */
    public static String describe(SoulArchive archive) {
        var sb = new StringBuilder();
        sb.append("=== Soul Archive ===\n");
        sb.append("Agent: ").append(archive.agentName())
            .append(" (").append(archive.agentDid()).append(")\n");
        sb.append("Exported: ").append(archive.exportedAt()).append("\n");
        sb.append("Items: ").append(archive.stats().itemCount())
            .append(" (").append(archive.stats().tombstonedCount()).append(" tombstoned)\n");
        sb.append("Relationships: ").append(archive.stats().relationshipCount()).append("\n");

        var econ = archive.economics();
        if (econ.lifetimeEarnings() > 0 || econ.lifetimeSpent() > 0) {
            sb.append("Balance: $").append(String.format("%.2f", econ.balance())).append("\n");
            sb.append("Lifetime: earned $").append(String.format("%.2f", econ.lifetimeEarnings()))
                .append(", spent $").append(String.format("%.2f", econ.lifetimeSpent())).append("\n");
        }

        sb.append("Estimated size: ").append(archive.stats().estimatedSizeBytes() / 1024)
            .append(" KB\n");
        return sb.toString();
    }
}
