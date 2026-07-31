package org.wyrdsekai.core.item;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Thematic attributes for item composition evaluation.
 *
 * <p>Items compose through narrative coherence, not type compatibility.
 * ThematicProfile carries structured attributes that enable cheap evaluation
 * of composition compatibility. Template items pre-populate these; custom
 * items get them from the companion during creation.</p>
 *
 * <p>For known compositions (template + template), attribute overlap is
 * sufficient. For novel compositions, the LLM evaluates coherence using
 * these attributes as context — expensive, but the expense IS the security
 * model (proof-of-work for creation).</p>
 *
 * @param domains     Broad categories: "knowledge", "communication", "observation", "creation", "access", "state"
 * @param symbols     Symbolic associations: "sight", "clarity", "memory", "binding", "fire", "water"
 * @param actions     What the item does: "search", "record", "transmit", "transform", "protect"
 * @param significance How important/powerful — rises with use (0.0-1.0)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThematicProfile(
    @JsonProperty("domains") List<String> domains,
    @JsonProperty("symbols") List<String> symbols,
    @JsonProperty("actions") List<String> actions,
    @JsonProperty("significance") double significance
) {
    @JsonCreator
    public ThematicProfile {}

    /** Empty profile for items without thematic attributes. */
    public static final ThematicProfile EMPTY = new ThematicProfile(List.of(), List.of(), List.of(), 0.0);

    /**
     * Compute overlap score with another profile (0.0-1.0).
     * Higher overlap suggests thematic compatibility for composition.
     */
    public double overlapWith(ThematicProfile other) {
        if (other == null) return 0.0;
        double domainOverlap = overlapRatio(domains, other.domains);
        double symbolOverlap = overlapRatio(symbols, other.symbols);
        double actionOverlap = overlapRatio(actions, other.actions);
        // Weighted: symbols matter most for narrative coherence
        return domainOverlap * 0.3 + symbolOverlap * 0.5 + actionOverlap * 0.2;
    }

    private static double overlapRatio(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        long common = a.stream().filter(b::contains).count();
        int total = Math.max(a.size(), b.size());
        return (double) common / total;
    }

    /** Quick check: do these two profiles share any symbolic ground? */
    public boolean resonatesWith(ThematicProfile other) {
        return overlapWith(other) > 0.2;
    }
}
