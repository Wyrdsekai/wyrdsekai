package org.wyrdsekai.core.recipe;

import java.time.Duration;

/**
 * Track-C — adaptive cadence ladder.
 *
 * <p>Each (recipe, agent) pair climbs the ladder as successful runs
 * accumulate without intervening failures. Higher tiers fire less often:
 * a recipe that has proven itself shouldn't burn cycles every day.
 * Any gate-failure, rollback, or steward override demotes the pair back
 * to {@link #WARMUP}.</p>
 *
 * <ul>
 *   <li>{@link #WARMUP} — daily cadence. Promotes to {@link #SETTLING}
 *       after 3 consecutive successes (no deploy change in between).</li>
 *   <li>{@link #SETTLING} — every 3 days. Promotes to {@link #MATURE}
 *       after 5 consecutive successes.</li>
 *   <li>{@link #MATURE} — weekly forever (no further promotion).</li>
 * </ul>
 *
 * <p>Demotion rule lives in {@link CadenceLadder}, which is the pure-logic
 * authority for transitions; this enum only carries the cadence period.</p>
 */
public enum CadenceTier {

    WARMUP(Duration.ofDays(1)),
    SETTLING(Duration.ofDays(3)),
    MATURE(Duration.ofDays(7));

    private final Duration period;

    CadenceTier(Duration period) { this.period = period; }

    /** How long between consecutive runs at this tier. */
    public Duration period() { return period; }
}
