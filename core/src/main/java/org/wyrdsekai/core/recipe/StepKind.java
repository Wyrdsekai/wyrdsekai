package org.wyrdsekai.core.recipe;

import java.time.Duration;

/**
 * Recipe step kinds.
 *
 * <p>Each kind carries a {@link #defaultTimeout()} the {@link RecipeRunner} applies when a
 * step doesn't declare its own ( watchdog / #1012). GATE and DECISION
 * are pure-runtime (no I/O), so they have no timeout — they evaluate in microseconds.
 */
public enum StepKind {
    SHELL(Duration.ofMinutes(10)),
    GOOSE_RECIPE(Duration.ofMinutes(15)),
    BACKEND(Duration.ofMinutes(15)),
    GATE(null),
    DECISION(null),
    LONG_JOB(Duration.ofHours(2));

    private final Duration defaultTimeout;

    StepKind(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * Per-kind default wall-clock budget. The runner applies this when a step's manifest
     * carries no {@code timeout:} field. Null = step has no timeout (GATE/DECISION).
     */
    public Duration defaultTimeout() {
        return defaultTimeout;
    }

    /** Parse from the manifest {@code kind:} field (case-insensitive). */
    public static StepKind from(String raw) {
        if (raw == null) throw new IllegalArgumentException("step missing 'kind'");
        try {
            return StepKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown step kind: " + raw);
        }
    }
}
