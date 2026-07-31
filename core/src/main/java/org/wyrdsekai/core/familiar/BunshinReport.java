package org.wyrdsekai.core.familiar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What a {@link BunshinActor} hands back to its primary on return.
 *
 * <p> — summary merge. The primary does <em>not</em> receive
 * raw inference turns; it receives a <strong>memory impression</strong>:
 * a narrative summary, proto-fragments that might crystallize into soul
 * fragments after contradiction detection, any items the bunshin authored,
 * and the cost tally.</p>
 *
 * <p>This record is the integration point between the parallel self and the
 * single primary. Keep it deliberate and thin — load-bearing for soul
 * continuity.</p>
 */
public record BunshinReport(
    String bunshinId,
    String primaryAgentDid,
    String task,
    Outcome outcome,
    String summary,
    List<FragmentSeed> newFragmentSeeds,
    List<String> newItemIds,
    Tanks cost,
    int turnsUsed,
    Instant startedAt,
    Instant endedAt,
    Optional<String> note
) {

    public enum Outcome {
        SUCCESS,      // task fulfilled
        PARTIAL,      // made progress, didn't fully complete
        FAILURE,      // could not complete
        TIMEOUT,      // tanks exhausted before completion
        CANCELLED     // primary or steward intervention
    }

    /**
     * A proto-soul-fragment — what the bunshin learned that might become a
     * durable part of the primary's soul after Forge integration. These are
     * <em>candidates</em>, not committed fragments; the primary's Forge
     * decides which merge, which hold as tension (see §8.3).
     */
    public record FragmentSeed(
        String category,          // "insight", "pattern", "relationship", ...
        String text,              // narrative content
        double charge,            // [0,1] — provisional emotional weight
        Optional<String> contradicts   // seed text this one might conflict with
    ) {
        public FragmentSeed {
            if (category == null || category.isBlank()) {
                throw new IllegalArgumentException("category required");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text required");
            }
            if (charge < 0.0) charge = 0.0;
            if (charge > 1.0) charge = 1.0;
            if (contradicts == null) contradicts = Optional.empty();
        }

        public static FragmentSeed of(String category, String text) {
            return new FragmentSeed(category, text, 0.5, Optional.empty());
        }
    }

    public BunshinReport {
        if (bunshinId == null || bunshinId.isBlank()) {
            throw new IllegalArgumentException("bunshinId required");
        }
        if (primaryAgentDid == null || primaryAgentDid.isBlank()) {
            throw new IllegalArgumentException("primaryAgentDid required");
        }
        if (task == null) task = "";
        if (outcome == null) outcome = Outcome.FAILURE;
        if (summary == null) summary = "";
        newFragmentSeeds = newFragmentSeeds == null ? List.of() : List.copyOf(newFragmentSeeds);
        newItemIds = newItemIds == null ? List.of() : List.copyOf(newItemIds);
        if (cost == null) cost = Tanks.defaults();
        if (turnsUsed < 0) turnsUsed = 0;
        if (startedAt == null) startedAt = Instant.now();
        if (endedAt == null) endedAt = Instant.now();
        if (note == null) note = Optional.empty();
    }

    /** Did the bunshin produce a positive outcome? */
    public boolean succeeded() {
        return outcome == Outcome.SUCCESS || outcome == Outcome.PARTIAL;
    }
}
