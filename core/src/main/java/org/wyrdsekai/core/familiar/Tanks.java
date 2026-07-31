package org.wyrdsekai.core.familiar;

/**
 * Resource envelope for a {@link Familiar} or bunshin — the per-entity budget
 * that governs lifetime.
 *
 * <p>. Tanks drain as the entity works; when any tank reaches
 * zero, the entity dies gracefully and summary-returns to its parent.</p>
 *
 * <p>Unlike a permissioned capability, tanks are <em>resources</em>. The
 * entity doesn't ask "may I continue?" — it burns fuel until the fuel is gone.
 * This is the seed-not-cage model: visibility + exhaustion over gates.</p>
 *
 * @param tokens     LLM tokens total across the entity's lifetime
 * @param steps      max tool calls + inference turns combined
 * @param wallClock  max wall-clock duration (seconds)
 * @param nestDepth  0 = cannot spawn familiars itself
 * @param cu         ComputeUnits (§98 economy) — real cost to the household
 */
public record Tanks(
    int tokens,
    int steps,
    int wallClock,
    int nestDepth,
    int cu
) {

    public Tanks {
        if (tokens < 0) throw new IllegalArgumentException("tokens must be >= 0");
        if (steps < 0) throw new IllegalArgumentException("steps must be >= 0");
        if (wallClock < 0) throw new IllegalArgumentException("wallClock must be >= 0");
        if (nestDepth < 0) throw new IllegalArgumentException("nestDepth must be >= 0");
        if (cu < 0) throw new IllegalArgumentException("cu must be >= 0");
    }

    /** Default familiar allocation. */
    public static Tanks defaults() {
        return new Tanks(1000, 20, 60, 1, 10);
    }

    /** Absolute ceiling; no familiar may exceed this without user approval. */
    public static Tanks maxCeiling() {
        return new Tanks(10000, 200, 600, 2, 100);
    }

    /** Strict envelope for experimentation — aggressive caps. */
    public static Tanks strict() {
        return new Tanks(500, 10, 30, 0, 5);
    }

    /** Is this envelope within the ceiling? Every tank must be <=. */
    public boolean withinCeiling(Tanks ceiling) {
        return tokens <= ceiling.tokens
            && steps <= ceiling.steps
            && wallClock <= ceiling.wallClock
            && nestDepth <= ceiling.nestDepth
            && cu <= ceiling.cu;
    }

    /** New Tanks with each field decremented by the matching delta; floored at 0. */
    public Tanks subtract(Tanks delta) {
        return new Tanks(
            Math.max(0, tokens - delta.tokens),
            Math.max(0, steps - delta.steps),
            Math.max(0, wallClock - delta.wallClock),
            Math.max(0, nestDepth),  // nestDepth doesn't drain
            Math.max(0, cu - delta.cu)
        );
    }

    /** Burn a single work unit — typical step overhead. */
    public Tanks burnStep(int tokensUsed, int wallClockSeconds, int cuCost) {
        return new Tanks(
            Math.max(0, tokens - tokensUsed),
            Math.max(0, steps - 1),
            Math.max(0, wallClock - wallClockSeconds),
            nestDepth,
            Math.max(0, cu - cuCost)
        );
    }

    /** Any tank at zero means the entity must stop. */
    public boolean exhausted() {
        return tokens == 0 || steps == 0 || wallClock == 0;
    }

    /** Which specific tank exhausted (for reporting). */
    public String exhaustedReason() {
        if (tokens == 0) return "out of tokens";
        if (steps == 0) return "out of steps";
        if (wallClock == 0) return "out of time";
        return "active";
    }
}
