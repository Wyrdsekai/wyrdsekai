package org.wyrdsekai.core.familiar;

import java.time.Instant;

/**
 * Read-only view of a thought form for Trading Post listings.
 *
 * <p> — listings surface the form's identity, lineage
 * usage stats, tank profile, and seller-set price. Construction is
 * non-trivial only in choosing which fields to expose — the listing
 * doesn't carry the full {@code systemPrompt} so that buyers make an
 * informed judgment without the seller losing bargaining position (the
 * prompt is revealed only on purchase).</p>
 *
 * <p>An <em>example output</em> line may be included for buyer preview
 * — typically one representative turn from the seller's own summons.</p>
 */
public record FormListing(
    String formId,
    String name,
    String version,
    String originalAuthorDid,
    String sellerDid,
    int copyDepth,                   // number of COPIED_FROM edits in provenance
    long summonCount,
    long successCount,
    long failureCount,
    double successRatio,
    String tankProfileDescription,
    String evalCriteria,
    String exampleOutput,            // nullable — seller chooses what (if anything) to preview
    long priceCu,
    Instant listedAt
) {

    public FormListing {
        if (formId == null || formId.isBlank()) throw new IllegalArgumentException("formId required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (sellerDid == null || sellerDid.isBlank()) {
            throw new IllegalArgumentException("sellerDid required");
        }
        if (priceCu < 0) throw new IllegalArgumentException("price cannot be negative");
        if (listedAt == null) listedAt = Instant.now();
    }

    /** Build a listing from a ThoughtForm, sized to what buyers need. */
    public static FormListing from(ThoughtForm form, String sellerDid,
                                    long priceCu, String exampleOutput) {
        if (form == null) throw new IllegalArgumentException("form required");
        var depth = (int) form.provenance().lineage().stream()
            .filter(e -> e.action() == Provenance.Action.COPIED_FROM)
            .count();
        return new FormListing(
            form.id(),
            form.name(),
            form.version(),
            form.provenance().originalAuthor(),
            sellerDid,
            depth,
            form.summonCount(),
            form.successCount(),
            form.failureCount(),
            form.successRatio(),
            describeTanks(form.defaultTanks()),
            form.evalCriteria(),
            exampleOutput,
            priceCu,
            Instant.now());
    }

    /** Human-readable one-liner for a tank envelope. */
    static String describeTanks(Tanks tanks) {
        return "tokens=" + tanks.tokens()
            + " steps=" + tanks.steps()
            + " wallClock=" + tanks.wallClock() + "s";
    }

    /**
     * Whether this listing represents a second-hand fork (one or more
     * COPIED_FROM edits in the lineage). Buyers may weigh this — a form
     * that's been copied many hands tends to drift.
     */
    public boolean isFork() {
        return copyDepth > 0;
    }

    /** Compact display string — for MUD-style list rendering. */
    public String displayLine() {
        var sb = new StringBuilder();
        sb.append(name).append('@').append(version);
        sb.append(" [").append(priceCu).append(" CU]");
        if (summonCount > 0) {
            sb.append(" (").append(summonCount).append(" summons, ")
              .append(String.format("%.0f%%", successRatio * 100.0)).append(" success)");
        }
        if (isFork()) {
            sb.append(" [fork ").append(copyDepth).append("×");
            if (copyDepth >= FORK_DEPTH_WARN) sb.append(" — may have drifted");
            sb.append(']');
        }
        return sb.toString();
    }

    /** Fork-depth above this triggers a drift warning in the display. */
    public static final int FORK_DEPTH_WARN = 3;

    /** Whether buyers should be warned about potential drift. */
    public boolean isDeepFork() {
        return copyDepth >= FORK_DEPTH_WARN;
    }
}
