package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A learned heuristic from past task execution — what worked, what didn't.
 *
 * <p>Research finding: failure-derived heuristics substantially outperform
 * success-derived ones. Failure heuristics provide negative constraints that
 * prune ineffective strategies.</p>
 */
public record Heuristic(
    @JsonProperty("domain") String domain,
    @JsonProperty("trigger") String trigger,
    @JsonProperty("guidance") String guidance,
    @JsonProperty("type") HeuristicType type,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("useCount") int useCount
) {
    @JsonCreator public Heuristic {}

    public enum HeuristicType {
        /** Learned from failure: "when X, don't do Y — try Z instead" */
        FAILURE_AVOIDANCE,
        /** Learned from success: "for X, this approach works" */
        SUCCESS_PATTERN,
        /** Learned from reconsideration: "when X happens, re-evaluate Y" */
        RECONSIDERATION
    }

    public Heuristic withIncrementedUse() {
        return new Heuristic(domain, trigger, guidance, type, confidence, useCount + 1);
    }
}
