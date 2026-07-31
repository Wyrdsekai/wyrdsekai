package org.wyrdsekai.core.interop;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports SIEF (SoulItem Exchange Format) items into Wyrdsekai (§97.3).
 * Validates, caps significance by trust level, and routes through
 * DockQuarantine for Forge review.
 */
public class SiefImporter {

    /** Result of a SIEF import attempt. */
    public record ImportResult(
        String itemId,
        boolean accepted,
        double cappedSignificance,
        SiefSerializer.ImportTrust trustLevel,
        List<String> issues,
        String quarantineId
    ) {}

    private final DockQuarantine quarantine;

    public SiefImporter(DockQuarantine quarantine) {
        this.quarantine = quarantine;
    }

    /**
     * Import a SIEF item. Validates, assesses trust, caps significance,
     * and submits to quarantine.
     *
     * @param item      the SIEF item
     * @param sourceTier the source agent's resolved trust tier
     * @return import result
     */
    public ImportResult importItem(SiefSerializer.SiefItem item, TrustTier sourceTier) {
        // Validate
        var issues = SiefSerializer.validate(item);
        if (!issues.isEmpty()) {
            return new ImportResult(null, false, 0,
                SiefSerializer.ImportTrust.MINIMAL, issues, null);
        }

        // Assess trust
        var trust = SiefSerializer.assessTrust(item);

        // Cap significance (use the more restrictive of trust assessment and tier)
        double tierCap = switch (sourceTier) {
            case ANONYMOUS -> 0.1;
            case VERIFIED -> 0.5;
            case TRUSTED -> 0.8;
            case HOUSEHOLD, FAMILY -> 1.0;
        };
        double trustCap = trust.significanceCap();
        double significance = item.metadata() != null ? item.metadata().significance() : 0.5;
        double capped = Math.min(significance, Math.min(tierCap, trustCap));

        // Submit to quarantine
        var creatorDid = item.creator() != null ? item.creator().did() : "unknown";
        var quarantined = quarantine.submit(
            item.label(), creatorDid, sourceTier,
            item.content(), item.type(), capped
        );

        return new ImportResult(
            item.label(), quarantined.status() == DockQuarantine.QuarantineStatus.PENDING,
            capped, trust, List.of(), quarantined.quarantineId()
        );
    }

    /**
     * Import multiple SIEF items.
     */
    public List<ImportResult> importBatch(List<SiefSerializer.SiefItem> items,
                                           TrustTier sourceTier) {
        var results = new ArrayList<ImportResult>();
        for (var item : items) {
            results.add(importItem(item, sourceTier));
        }
        return results;
    }
}
