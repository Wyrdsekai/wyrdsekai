package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A relationship in the agent's social soul.
 * Tracks trust, rapport, interaction history, and bond depth.
 *
 * @param entityDid        Who (DID of the other entity)
 * @param entityName       Display name at last interaction
 * @param trust            0.0-1.0 trust level
 * @param rapport          0.0-1.0 rapport (from vitality tank)
 * @param bondDepth        Bond depth level (0=acquaintance, 4=soul-ingrained, per section 102)
 * @param interactionCount Total interactions
 * @param lastInteraction  When last interacted
 * @param summary          Compressed description of the relationship
 */
public record Relationship(
    @JsonProperty("entityDid") String entityDid,
    @JsonProperty("entityName") String entityName,
    @JsonProperty("trust") float trust,
    @JsonProperty("rapport") float rapport,
    @JsonProperty("bondDepth") int bondDepth,
    @JsonProperty("interactionCount") int interactionCount,
    @JsonProperty("lastInteraction") Instant lastInteraction,
    @JsonProperty("summary") String summary
) {
    @JsonCreator
    public Relationship {}

    static final String NEW_ACQUAINTANCE = "Recently met.";

    /** New acquaintance. */
    public static Relationship acquaintance(String did, String name) {
        return new Relationship(did, name, 0.3f, 0.3f, 0, 1, Instant.now(), NEW_ACQUAINTANCE);
    }

    /**
     * The summary the system writes from what it has counted. {@link #acquaintance} set
     * "Recently met." once and nothing ever revised it, so a bondholder of seven weeks and
     * thousands of exchanges was still described to the companion as recently met.
     */
    public static String describe(int bondDepth, int interactionCount) {
        if (interactionCount < 5) return NEW_ACQUAINTANCE;
        String who = switch (Math.max(0, bondDepth)) {
            case 0 -> "Someone you have talked with";
            case 1 -> "Someone familiar";
            case 2 -> "Someone close and trusted";
            default -> "One of the people closest to you";
        };
        return who + ": " + interactionCount + " exchanges so far.";
    }

    /** True when the summary is one this class wrote, so it may be rewritten from the counts.
     *  A summary written by anyone else is left alone. */
    public boolean summaryIsAutomatic() {
        return summary == null || summary.isBlank() || NEW_ACQUAINTANCE.equals(summary)
            || summary.endsWith(" exchanges so far.");
    }
}
