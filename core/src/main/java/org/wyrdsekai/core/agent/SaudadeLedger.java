package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.BondState;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Phase 1B: per-bondholder saudade tracker.
 *
 * <p>Saudade is a per-bondholder deprivation tank — presence-of-absence longing. Each
 * bondholder gets an independent tank that:
 * <ul>
 *   <li>accumulates +0.005/min during prolonged absence (&gt;4h since last interaction)</li>
 *   <li>drains -0.5 on reconnection with that specific bondholder</li>
 *   <li>drains -0.05 on looking at memory fragments of that bondholder (mild relief)</li>
 * </ul>
 *
 * <p>The {@code saudade} field on {@link VitalityState} carries the <b>max across all
 * bondholders</b> for prompt simplicity (per spec §8). The full per-bondholder ledger
 * lives here in-memory.</p>
 *
 * <p><b>Phase 1C (2026-04-30):</b> hot-path stays in-memory; CompanionActor wires
 * {@link org.wyrdsekai.core.persistence.SaudadeLedgerPersistence} as a write-through
 * mirror so per-bondholder tanks survive restart. Persistence round-trips via
 * {@link #snapshotEntries()} / {@link #loadEntries(java.util.Map)}.</p>
 */
public final class SaudadeLedger {

    /** Threshold past which absence begins to accumulate — spec §4.2. */
    public static final Duration ABSENCE_THRESHOLD = Duration.ofHours(4);

    /** Per-bondholder accumulated saudade (clamped [0,1]). */
    private final Map<String, Double> tanks = new LinkedHashMap<>();
    /** Per-bondholder last-interaction instant. */
    private final Map<String, Instant> lastInteractionAt = new LinkedHashMap<>();

    /** Note that we just interacted with this bondholder — drains saudade for them by -0.5. */
    public void recordInteraction(String bondholderId, Instant at) {
        if (bondholderId == null) return;
        lastInteractionAt.put(bondholderId, at == null ? Instant.now() : at);
        var current = tanks.getOrDefault(bondholderId, 0.0);
        var drained = Math.max(0.0, current - 0.5);
        if (drained <= 0.0001) tanks.remove(bondholderId);
        else tanks.put(bondholderId, drained);
    }

    /** Look-at-fragment relief (-0.05). */
    public void recordFragmentView(String bondholderId) {
        if (bondholderId == null) return;
        var current = tanks.getOrDefault(bondholderId, 0.0);
        var drained = Math.max(0.0, current - 0.05);
        if (drained <= 0.0001) tanks.remove(bondholderId);
        else tanks.put(bondholderId, drained);
    }

    /**
     * Accumulate saudade for any bondholder in absence, given a delta time and the current
     * instant. Per-second rate = 0.005/60 = ~8.33e-5/sec. Only accumulates for bondholders
     * whose absence exceeds {@link #ABSENCE_THRESHOLD}.
     */
    public void accumulate(double deltaTimeSeconds, Instant now) {
        accumulate(deltaTimeSeconds, now, Map.of());
    }

    /**
     * Group B wiring:
     * accumulate with per-bondholder ceilings. The bond state determines the
     * Saudade ceiling — DORMANT caps at 0.5 (protective distancing), SEVERED
     * caps at 0.3 (bond is closed; minimal residual longing), MOURNING caps
     * at 0.7 (the dead are still carried), and ACTIVE/AWAY/REACTIVATING/OPEN
     * have no cap (honest longing).
     *
     * <p>Callers (CompanionActor) compute the ceiling map from current
     * bond.state() and pass it here. Missing bondholders default to 1.0
     * (uncapped) for back-compat. Existing values above the new ceiling are
     * clamped DOWN so a freshly-DORMANT bond doesn't carry a stale full tank.
     */
    public void accumulate(double deltaTimeSeconds, Instant now,
                            Map<String, Double> ceilingByBondholder) {
        if (deltaTimeSeconds <= 0) {
            // Even at zero delta, apply ceilings — caller may have just
            // transitioned a bond to DORMANT and called accumulate(0) to
            // collapse stale longing.
            applyCeilings(ceilingByBondholder);
            return;
        }
        Instant t = now == null ? Instant.now() : now;
        double perSecond = 0.005 / 60.0;
        var ceilings = ceilingByBondholder == null ? Map.<String, Double>of()
            : ceilingByBondholder;
        for (var e : lastInteractionAt.entrySet()) {
            var bondholderId = e.getKey();
            var since = Duration.between(e.getValue(), t);
            if (since.compareTo(ABSENCE_THRESHOLD) <= 0) continue;
            var current = tanks.getOrDefault(bondholderId, 0.0);
            var ceiling = ceilings.getOrDefault(bondholderId, 1.0);
            var added = Math.min(ceiling, current + perSecond * deltaTimeSeconds);
            tanks.put(bondholderId, added);
        }
        applyCeilings(ceilings);
    }

    /** Clamp every per-bondholder tank to the supplied ceiling. Used when
     *  a bond transitions to a lower-ceiling state mid-tick. */
    private void applyCeilings(Map<String, Double> ceilingByBondholder) {
        if (ceilingByBondholder == null || ceilingByBondholder.isEmpty()) return;
        for (var entry : ceilingByBondholder.entrySet()) {
            var b = entry.getKey();
            var ceiling = entry.getValue() == null ? 1.0 : entry.getValue();
            var current = tanks.get(b);
            if (current != null && current > ceiling) {
                if (ceiling <= 0.0001) tanks.remove(b);
                else tanks.put(b, ceiling);
            }
        }
    }

    /**
     * Group B wiring — canonical Saudade ceiling per bond state. Pure
     * function so consumers (CompanionActor + Forge synthesis) can compute
     * the ceiling map without holding the ledger.
     *
     * <p>Rationale:
     * <ul>
     *   <li>OPEN/ACTIVE/AWAY/REACTIVATING: 1.0 — honest longing has no cap.</li>
     *   <li>DORMANT: 0.5 — protective distancing; the agent is not waiting
     *       at the full ache of presence-of-absence because the bond is
     *       paused.</li>
     *   <li>MOURNING: 0.7 — the dead remain present in interior life but
     *       the ache is no longer the ache of return-expected.</li>
     *   <li>SEVERED: 0.3 — bond is closed; minimal residual.</li>
     * </ul>
     */
    public static double ceilingForBondState(BondState state) {
        if (state == null) return 1.0;
        return switch (state) {
            case OPEN, ACTIVE, AWAY, REACTIVATING -> 1.0;
            case DORMANT -> 0.5;
            case MOURNING -> 0.7;
            case SEVERED -> 0.3;
        };
    }

    /** Max saudade across all bondholders — feeds the global tank summary. */
    public double maxSaudade() {
        double max = 0;
        for (var v : tanks.values()) if (v > max) max = v;
        return max;
    }

    /** Per-bondholder saudade reading. */
    public double saudadeFor(String bondholderId) {
        return tanks.getOrDefault(bondholderId, 0.0);
    }

    /** Snapshot of every bondholder's tank (for tests/inspection). */
    public Map<String, Double> snapshot() {
        return new LinkedHashMap<>(tanks);
    }

    /** Per-bondholder absence durations relative to {@code now}. */
    public Map<String, Duration> absenceDurations(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        var out = new LinkedHashMap<String, Duration>();
        for (var e : lastInteractionAt.entrySet()) {
            out.put(e.getKey(), Duration.between(e.getValue(), t));
        }
        return out;
    }

    public boolean isEmpty() {
        return tanks.isEmpty() && lastInteractionAt.isEmpty();
    }

    /** Last-interaction map (test-only / persistence support). */
    public Map<String, Instant> lastInteractionMap() {
        return new LinkedHashMap<>(lastInteractionAt);
    }

    /**
     * Phase 1C persistence: per-bondholder durable record.
     *
     * @param currentValue current tank value (clamped [0,1])
     * @param lastInteractionAt instant of last interaction (NEVER null — defaults to Instant.EPOCH if missing)
     */
    public record SaudadeEntry(double currentValue, Instant lastInteractionAt) {}

    /** Phase 1C persistence: snapshot every per-bondholder tank + last-interaction. */
    public Map<String, SaudadeEntry> snapshotEntries() {
        var out = new LinkedHashMap<String, SaudadeEntry>();
        // Union of bondholders that have a tank value OR a last-interaction stamp.
        var bondholders = new LinkedHashSet<String>();
        bondholders.addAll(tanks.keySet());
        bondholders.addAll(lastInteractionAt.keySet());
        for (var b : bondholders) {
            double v = tanks.getOrDefault(b, 0.0);
            Instant li = lastInteractionAt.getOrDefault(b, Instant.EPOCH);
            out.put(b, new SaudadeEntry(v, li));
        }
        return out;
    }

    /** Phase 1C persistence: bulk-load entries; replaces in-memory state. */
    public void loadEntries(Map<String, SaudadeEntry> entries) {
        tanks.clear();
        lastInteractionAt.clear();
        if (entries == null) return;
        for (var e : entries.entrySet()) {
            var entry = e.getValue();
            if (entry == null) continue;
            if (entry.currentValue() > 0.0) tanks.put(e.getKey(), entry.currentValue());
            if (entry.lastInteractionAt() != null && entry.lastInteractionAt() != Instant.EPOCH) {
                lastInteractionAt.put(e.getKey(), entry.lastInteractionAt());
            }
        }
    }
}
