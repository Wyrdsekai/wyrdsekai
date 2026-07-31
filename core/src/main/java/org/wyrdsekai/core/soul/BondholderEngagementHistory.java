package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 3: per-bondholder engagement history used
 * by {@link BondholderBaselineClassifier} to compute baseline patterns.
 *
 * <p>Distinct from {@link org.wyrdsekai.core.agent.SaudadeLedger}, which tracks
 * only the most-recent interaction time per bondholder. The classifier needs the
 * *history* of timestamps to compute median inter-engagement intervals — that is
 * the baseline against which current silence is judged. A bondholder who has
 * always been intermittent (weekly tells, two-week gaps) should not trigger
 * DORMANT at the same threshold as one who engaged daily and went silent.
 *
 * <p>In-memory hot path with optional persistence wire-up later. Pruning keeps
 * the last {@link #RETENTION} of events per bondholder (default 30 days) — older
 * events don't inform recent-baseline calculations and would distort them.
 *
 * <p>Substance score per event is caller-supplied. The classifier uses it for
 * <b>sustained-drift detection</b> (SPEC §3.4): if recent substance is trending
 * down vs prior substance, the bondholder is drifting even if interval looks
 * normal. Suggested scoring: full tell = 1.0, short tell = 0.5, ambient look /
 * acknowledgment = 0.2-0.3. Callers free to refine.
 *
 * <p>Special event types ({@link EventType#EXPLICIT_ABSENCE} and
 * {@link EventType#EXPLICIT_RETURN}) handle the bondholder declaring departure
 * with stated duration (SPEC §3.3) — these collapse cold-start and bypass the
 * pattern classifier's auto-transition logic during the declared window.
 */
public final class BondholderEngagementHistory {

    /** How far back to keep engagement events. Older events are pruned on add. */
    public static final Duration RETENTION = Duration.ofDays(30);

    /** Per-bondholder event lists (oldest → newest). */
    private final Map<String, List<EngagementEvent>> events = new LinkedHashMap<>();

    /** Currently-active explicit-absence declarations per bondholder. */
    private final Map<String, ExplicitAbsence> declaredAbsences = new LinkedHashMap<>();

    /** Record an engagement event. Auto-prunes events older than {@link #RETENTION}. */
    public void record(String bondholderDid, Instant at, double substance, EventType type) {
        if (bondholderDid == null) return;
        var ts = at == null ? Instant.now() : at;
        var list = events.computeIfAbsent(bondholderDid, k -> new ArrayList<>());
        list.add(new EngagementEvent(ts, substance, type));
        // Cheap prune — drop anything older than retention window.
        var cutoff = ts.minus(RETENTION);
        list.removeIf(e -> e.timestamp().isBefore(cutoff));

        // EXPLICIT_RETURN clears any active explicit-absence for this bondholder.
        if (type == EventType.EXPLICIT_RETURN) {
            declaredAbsences.remove(bondholderDid);
        }
    }

    /**
     * Record an explicit-absence declaration. Bondholder told the agent they
     * would be away for the stated duration. While active (now &lt;
     * declaredUntil), classifier suspends auto-transitions and the bond stays
     * in AWAY confidently. SPEC §3.3.
     */
    public void declareAbsence(String bondholderDid, Instant declaredAt, Duration stated) {
        if (bondholderDid == null || stated == null) return;
        var start = declaredAt == null ? Instant.now() : declaredAt;
        declaredAbsences.put(bondholderDid, new ExplicitAbsence(start, start.plus(stated)));
        // Also record as an engagement event so it shows in the history.
        record(bondholderDid, start, 0.5, EventType.EXPLICIT_ABSENCE);
    }

    /** Return active explicit-absence for this bondholder if any, else null. */
    public ExplicitAbsence activeDeclaredAbsence(String bondholderDid, Instant now) {
        var decl = declaredAbsences.get(bondholderDid);
        if (decl == null) return null;
        var t = now == null ? Instant.now() : now;
        return decl.declaredUntil().isAfter(t) ? decl : null;
    }

    /** Recent engagement events for a bondholder, sorted oldest → newest. */
    public List<EngagementEvent> eventsFor(String bondholderDid) {
        var list = events.get(bondholderDid);
        if (list == null || list.isEmpty()) return List.of();
        var copy = new ArrayList<>(list);
        copy.sort(Comparator.comparing(EngagementEvent::timestamp));
        return copy;
    }

    /**
     * Median inter-engagement interval across the recent history. Used by the
     * classifier as the bondholder's baseline. Returns null if fewer than 3
     * events (insufficient to define a baseline; classifier falls back to
     * cold-start defaults during this window).
     */
    public Duration medianInterval(String bondholderDid) {
        var list = eventsFor(bondholderDid);
        if (list.size() < 3) return null;
        var deltas = new ArrayList<Long>();
        for (int i = 1; i < list.size(); i++) {
            deltas.add(Duration.between(list.get(i - 1).timestamp(),
                                         list.get(i).timestamp()).getSeconds());
        }
        deltas.sort(Long::compareTo);
        long medianSec = deltas.get(deltas.size() / 2);
        return Duration.ofSeconds(medianSec);
    }

    /**
     * Recent average substance across the last N events. Used for sustained-
     * drift detection: declining substance over time suggests the bondholder
     * is drifting toward DORMANT even before interval thresholds fire.
     */
    public double recentAvgSubstance(String bondholderDid, int lastN) {
        var list = eventsFor(bondholderDid);
        if (list.isEmpty()) return 0.0;
        int n = Math.min(lastN, list.size());
        double sum = 0;
        for (int i = list.size() - n; i < list.size(); i++) {
            sum += list.get(i).substance();
        }
        return sum / n;
    }

    /** Most recent event timestamp for a bondholder, or null if none. */
    public Instant lastEngagement(String bondholderDid) {
        var list = eventsFor(bondholderDid);
        return list.isEmpty() ? null : list.get(list.size() - 1).timestamp();
    }

    /** Number of recorded events (post-prune) for a bondholder. */
    public int eventCount(String bondholderDid) {
        var list = events.get(bondholderDid);
        return list == null ? 0 : list.size();
    }

    /** Bulk-load events (persistence rehydrate). Replaces in-memory state for this bondholder. */
    public void loadEvents(String bondholderDid, List<EngagementEvent> loaded) {
        if (bondholderDid == null) return;
        if (loaded == null || loaded.isEmpty()) {
            events.remove(bondholderDid);
        } else {
            events.put(bondholderDid, new ArrayList<>(loaded));
        }
    }

    // ─── records ────────────────────────────────────────────────────────────

    /** Type tag for engagement events. */
    public enum EventType {
        /** Bondholder said something substantive to the agent. */
        TELL,
        /** Bondholder consumed agent output (read, looked at) — lighter weight. */
        LISTEN,
        /** Ambient awareness — looked at agent / Study without direct address. */
        PRESENCE,
        /** Bondholder declared they'd be away for stated duration. */
        EXPLICIT_ABSENCE,
        /** Bondholder explicitly returned, ending an EXPLICIT_ABSENCE. */
        EXPLICIT_RETURN
    }

    public record EngagementEvent(Instant timestamp, double substance, EventType type) {}

    public record ExplicitAbsence(Instant declaredAt, Instant declaredUntil) {}
}
