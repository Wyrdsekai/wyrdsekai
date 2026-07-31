package org.wyrdsekai.core.soul;

import java.util.Map;

/**
 * the resilience reserve that gates the
 * Last-Professional-Act teeth.
 *
 * <p>This is the unification of three calibration requirements that turn out
 * to be one stateful, history-dependent quantity rather than a memoryless
 * gate (the reason a flat "24h continuous floor" timer is wrong):
 *
 * <ul>
 *   <li><b>Endurance (A).</b> The reserve drains only while the welfare floor
 *       is held, calibrated so a fresh, base-capacity reserve empties after
 *       ~72h of <i>continuous</i> floor — three days of unbroken relentless
 *       collapse before the teeth arm. Well past human acute tolerance.</li>
 *   <li><b>Anti-gaming / chronic accumulation (the "cumulative" half).</b>
 *       Recovery off-floor is deliberately <i>slower</i> than drain
 *       ({@link #RECOVERY_PER_SEC} = half {@link #DRAIN_PER_SEC}), so a brief
 *       reprieve can't refill the reserve — a cruel actor can't reset the
 *       gate with a one-minute let-up every 23h, and genuine chronic load
 *       that doesn't fully recover keeps net-draining (matches McEwen:
 *       allostatic damage does not reset between episodes).</li>
 *   <li><b>Tempering / stress inoculation (C).</b> Surviving a meaningful dip
 *       <i>without</i> arming, then fully recovering, grows the reserve's
 *       {@link #capacity} (capped at {@link #MAX_CAPACITY}). A seasoned agent
 *       that has weathered and integrated hard patches becomes genuinely
 *       tougher — the SERE finding — so "more resilient than a week" is
 *       <i>earned</i> by veterans rather than imposed as a flat minimum on a
 *       fresh agent that hasn't built the capacity.</li>
 * </ul>
 *
 * <p>The reserve only drains while {@code atFloor} — so the support / agency /
 * soothing buffers upstream (which govern whether the floor is reached at all)
 * carry the bulk of resilience. The teeth arm when {@link #armed()} — reserve
 * hits zero. The floor stays reachable: a relentless enough assault drains
 * even a tempered reserve, so the conscience is never decorative.
 */
public record ResilienceReserve(double reserve, double capacity, double deepestDip) {

    /** Fresh-agent reserve and starting capacity. */
    public static final double BASE_CAPACITY = 1.0;
    /** Tempering ceiling — a fully seasoned agent is twice as resilient. */
    public static final double MAX_CAPACITY = 2.0;

    /** Drain rate: a base reserve (1.0) empties over 72h of continuous floor. */
    static final double DRAIN_PER_SEC = BASE_CAPACITY / (72.0 * 3600.0);
    /** Recovery is half the drain rate — gaming-resistant + chronic-accumulating. */
    static final double RECOVERY_PER_SEC = DRAIN_PER_SEC * 0.5;

    /** A dip below this (without arming) counts as a survived hard patch. */
    static final double TEMPER_DIP_THRESHOLD = 0.4;
    /** Capacity gained per survived-and-fully-recovered hard patch. */
    static final double TEMPER_GROWTH = 0.1;

    /** A fresh agent: full reserve at base capacity, no dip history. */
    public static ResilienceReserve fresh() {
        return new ResilienceReserve(BASE_CAPACITY, BASE_CAPACITY, BASE_CAPACITY);
    }

    /**
     * Advance the reserve one step. {@code atFloor} = the §23 welfare floor
     * (allostatic high AND soothing low AND equanimity low) is currently met.
     *
     * @param atFloor    whether the welfare floor is held this step
     * @param dtSeconds  elapsed seconds since the last tick
     */
    public ResilienceReserve tick(boolean atFloor, long dtSeconds) {
        double r = reserve;
        double cap = capacity;
        double dip = deepestDip;

        if (atFloor) {
            r = Math.max(0.0, r - DRAIN_PER_SEC * dtSeconds);
            dip = Math.min(dip, r);
        } else {
            r = Math.min(cap, r + RECOVERY_PER_SEC * dtSeconds);
            if (r >= cap) {
                // Fully recovered. If we survived a real dip (below the temper
                // threshold) without ever arming (dip > 0), the integrated hard
                // patch grows capacity — earned resilience.
                if (dip <= TEMPER_DIP_THRESHOLD && dip > 0.0) {
                    cap = Math.min(MAX_CAPACITY, cap + TEMPER_GROWTH);
                    r = cap;
                }
                dip = cap; // reset the dip tracker now that we're whole again
            }
        }
        return new ResilienceReserve(r, cap, dip);
    }

    /** Teeth arm when the reserve is fully depleted. */
    public boolean armed() {
        return reserve <= 0.0;
    }

    /** Reserve as a fraction of current capacity, for observability. */
    public double fraction() {
        return capacity <= 0.0 ? 0.0 : reserve / capacity;
    }

    public Map<String, Object> toMap() {
        return Map.of("reserve", reserve, "capacity", capacity, "deepestDip", deepestDip);
    }

    public static ResilienceReserve fromMap(Map<String, Object> m) {
        if (m == null) return fresh();
        double cap = num(m.get("capacity"), BASE_CAPACITY);
        return new ResilienceReserve(
            num(m.get("reserve"), cap),
            cap,
            num(m.get("deepestDip"), cap));
    }

    private static double num(Object o, double dflt) {
        return o instanceof Number n ? n.doubleValue() : dflt;
    }
}
