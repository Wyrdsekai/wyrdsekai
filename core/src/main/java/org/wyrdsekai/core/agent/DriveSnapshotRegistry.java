package org.wyrdsekai.core.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe snapshot registry of companion drive + vitality state, keyed by
 * agent identifier (DID or entityId). The {@link CompanionActor} publishes
 * periodically (every vitality tick); scripts and external surfaces read.
 *
 * <p>Why a registry rather than direct field access:</p>
 * <ul>
 *   <li>Pekko actor fields are not safely visible cross-thread; the script
 *       executor runs on a different dispatcher than the actor's mailbox.</li>
 *   <li>Snapshots are a few hundred bytes — cheap to copy and publish.</li>
 *   <li>Multiple consumers (Drives Mirror furnishing, dashboard, audit
 *       export) can read concurrently without contending on the actor.</li>
 * </ul>
 *
 * <p>Snapshot freshness: published once per second by default, so reads are
 * up-to-the-second stale. Acceptable for furnishing surfaces; not for
 * tight-loop control. {@link #updatedAt} lets readers decide.</p>
 *
 * <p>Phase 1A Hearth Drives Mirror: the per-bondholder ledger entries for
 * {@code saudade} and {@code obligation} are published alongside the global
 * {@link VitalityState} so the Drives Mirror furnishing can show both the
 * single-value tank reading (max-across-bondholders, fed into the LLM prompt)
 * and a per-bondholder breakdown when the bondholder looks into the mirror.
 * Per-bondholder maps are nullable — older publishers (and the no-arg
 * convenience overload) leave them null.</p>
 */
public final class DriveSnapshotRegistry {

    public record Snapshot(
        DriveState drives,
        VitalityState vitality,
        Instant updatedAt,
        Map<String, SaudadeLedger.SaudadeEntry> saudadeByBondholder,
        Map<String, Double> obligationByBondholder,
        boolean frustrationEmphasis
    ) {
        public Snapshot(DriveState drives, VitalityState vitality, Instant updatedAt) {
            this(drives, vitality, updatedAt, null, null, false);
        }
        public Snapshot(DriveState drives, VitalityState vitality, Instant updatedAt,
                        Map<String, SaudadeLedger.SaudadeEntry> saudadeByBondholder,
                        Map<String, Double> obligationByBondholder) {
            this(drives, vitality, updatedAt, saudadeByBondholder, obligationByBondholder, false);
        }
    }

    private static final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    private DriveSnapshotRegistry() {}

    /** Publish a fresh snapshot. Called from the actor on each vitality tick. */
    public static void publish(String agentKey, DriveState drives, VitalityState vitality) {
        if (agentKey == null) return;
        snapshots.put(agentKey, new Snapshot(drives, vitality, Instant.now(), null, null, false));
    }

    /**
     * Phase 1A Hearth Drives Mirror — publish drive+vitality snapshot together
     * with the per-bondholder ledger maps. Either ledger map may be null/empty;
     * unmodifiable copies are stored so readers can iterate freely without
     * holding a lock on the publisher's ledger.
     */
    public static void publish(String agentKey, DriveState drives, VitalityState vitality,
                               Map<String, SaudadeLedger.SaudadeEntry> saudadeEntries,
                               Map<String, Double> obligationDebts) {
        publish(agentKey, drives, vitality, saudadeEntries, obligationDebts, false);
    }

    /**
     * Hwa-byung surfacing — publish overload that
     * additionally raises the {@code frustrationEmphasis} flag for Drives Mirror
     * consumers. Cleared after the next render (consumer-side via
     * {@link #clearFrustrationEmphasis(String)}).
     */
    public static void publish(String agentKey, DriveState drives, VitalityState vitality,
                               Map<String, SaudadeLedger.SaudadeEntry> saudadeEntries,
                               Map<String, Double> obligationDebts,
                               boolean frustrationEmphasis) {
        if (agentKey == null) return;
        var saudadeCopy = (saudadeEntries == null || saudadeEntries.isEmpty())
            ? null : Collections.unmodifiableMap(new LinkedHashMap<>(saudadeEntries));
        var obligationCopy = (obligationDebts == null || obligationDebts.isEmpty())
            ? null : Collections.unmodifiableMap(new LinkedHashMap<>(obligationDebts));
        snapshots.put(agentKey, new Snapshot(drives, vitality, Instant.now(),
            saudadeCopy, obligationCopy, frustrationEmphasis));
    }

    /**
     * clear the {@code frustrationEmphasis}
     * flag on the latest snapshot (called by the Drives Mirror furnishing
     * once it has rendered the emphasis). Idempotent; no-op if no snapshot.
     */
    public static void clearFrustrationEmphasis(String agentKey) {
        if (agentKey == null) return;
        snapshots.computeIfPresent(agentKey, (k, s) ->
            s.frustrationEmphasis()
                ? new Snapshot(s.drives(), s.vitality(), s.updatedAt(),
                    s.saudadeByBondholder(), s.obligationByBondholder(), false)
                : s);
    }

    /** Read the latest snapshot for an agent, if any. */
    public static Optional<Snapshot> get(String agentKey) {
        if (agentKey == null) return Optional.empty();
        return Optional.ofNullable(snapshots.get(agentKey));
    }

    /** Test-only: clear the registry. */
    public static void resetForTests() {
        snapshots.clear();
    }
}
