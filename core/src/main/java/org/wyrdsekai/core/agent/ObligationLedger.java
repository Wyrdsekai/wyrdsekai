package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Phase 1B: per-bondholder ledger of received-help debts.
 *
 * <p>Each entry has a magnitude (0.1–0.5 depending on event significance) and an instant of
 * origin. Outstanding debts compound by 1.05× per week unrepaid, capped at 2× the original
 * magnitude. Reciprocal action toward the bondholder discharges proportional debt; "we're
 * even" discharges all debts for that bondholder.</p>
 *
 * <p><b>Phase 1C (2026-04-30):</b> hot-path stays in-memory; CompanionActor wires
 * {@link org.wyrdsekai.core.persistence.ObligationLedgerPersistence} as a write-through
 * mirror so debts survive restart. The persistence layer round-trips entries via
 * {@link #snapshotEntries()} / {@link #loadEntries(java.util.Map)}.</p>
 *
 * <p>The {@link #totalDebt(String, Instant)} reading is what feeds {@link AccumulationContext#obligationDebts()}
 * and ultimately the {@code obligation} tank. The summary across bondholders (the global view
 * exposed in {@link DriveState#prefix(VitalityState)}) is the max across all per-bondholder
 * totals —.</p>
 */
public final class ObligationLedger {

    /** Compound factor per week of un-discharged debt (giri-as-interest). */
    static final double COMPOUND_PER_WEEK = 1.05;
    /** Hard cap on compounded magnitude — 2× the original. */
    static final double COMPOUND_CAP = 2.0;
    static final long SECONDS_PER_WEEK = Duration.ofDays(7).getSeconds();

    /** A single received-help event. */
    public record Debt(double originalMagnitude, Instant createdAt) {
        /** Compounded current magnitude given an evaluation instant. */
        public double currentMagnitude(Instant now) {
            if (now == null || now.isBefore(createdAt)) return originalMagnitude;
            long ageSeconds = Duration.between(createdAt, now).getSeconds();
            if (ageSeconds <= 0) return originalMagnitude;
            double weeks = (double) ageSeconds / SECONDS_PER_WEEK;
            double factor = Math.pow(COMPOUND_PER_WEEK, weeks);
            double capped = Math.min(factor, COMPOUND_CAP);
            return originalMagnitude * capped;
        }
    }

    /**
     * Phase 1C: persisted-entry view. Carries the durable identity of a debt entry so
     * SQL UPDATEs / DELETEs can target a specific row across actor restarts.
     *
     * @param entryId opaque per-bondholder entry identifier (UUID-ish; persistence layer mints it)
     * @param originalMagnitude original recorded magnitude before compounding
     * @param createdAt instant of the original help event
     */
    public record DebtEntry(String entryId, double originalMagnitude, Instant createdAt) {}

    private final Map<String, List<Debt>> debtsByBondholder = new LinkedHashMap<>();

    /**
     * 2026-06-02 giri credit direction: per-counterparty CREDITS — others heavily in
     * <i>my</i> debt (I gave and they haven't returned). Structurally identical to a debt
     * (a compounding magnitude), reusing {@link Debt}. The tank reads the per-counterparty
     * NET = debt − credit, and the global summary is the max |net| ({@link #maxImbalance})
     * — imbalance in EITHER direction. Honor doesn't lord a credit; being over-owed is its
     * own quiet pressure, and the resolution is to release / give freely, not collect.
     * Persisted alongside debts under a {@link #CREDIT_PREFIX}-prefixed key (no schema
     * change — {@code bondholder_did} is an opaque TEXT column).
     */
    private final Map<String, List<Debt>> creditsByBondholder = new LinkedHashMap<>();

    /** Persistence key prefix distinguishing a credit entry from a debt entry. Real
     *  bondholder DIDs are {@code did:…}, so this can never collide. */
    public static final String CREDIT_PREFIX = "credit::";

    /**
     * Record a received-help event. Magnitude is clamped to [0.1, 0.5] per spec.
     *
     * @param bondholderId opaque ID of the helping bondholder
     * @param magnitude    raw magnitude — 0.1 small tip, 0.5 major rescue
     * @param at           instant of the help event (use Instant.now() for live calls)
     */
    public void recordHelp(String bondholderId, double magnitude, Instant at) {
        if (bondholderId == null || bondholderId.isBlank()) return;
        double clamped = Math.max(0.1, Math.min(0.5, magnitude));
        var list = debtsByBondholder.computeIfAbsent(bondholderId, k -> new ArrayList<>());
        list.add(new Debt(clamped, at == null ? Instant.now() : at));
    }

    /**
     * Discharge debt for a bondholder by an amount, oldest-first. Used when a reciprocal action
     * happens. If amount ≥ total, all debts for that bondholder are cleared.
     *
     * @return the amount actually discharged (≤ amount)
     */
    public double discharge(String bondholderId, double amount, Instant now) {
        if (bondholderId == null) return 0.0;
        var list = debtsByBondholder.get(bondholderId);
        if (list == null || list.isEmpty() || amount <= 0) return 0.0;

        double remaining = amount;
        // Epsilon to absorb compound-factor rounding so a "discharge exactly the first debt"
        // call doesn't leave a near-zero residue.
        double eps = 1e-3;
        var iter = list.iterator();
        while (iter.hasNext() && remaining > 0) {
            var d = iter.next();
            double currentMag = d.currentMagnitude(now == null ? Instant.now() : now);
            if (currentMag <= remaining + eps) {
                remaining -= Math.min(currentMag, remaining);
                iter.remove();
            } else {
                // Partial discharge — replace with a smaller debt at "now" so further compounding
                // is computed from the post-discharge magnitude.
                double newOriginal = (currentMag - remaining)
                    / (d.currentMagnitude(now == null ? Instant.now() : now) / d.originalMagnitude());
                // Simpler: shrink originalMagnitude proportionally.
                double shrinkFactor = (currentMag - remaining) / currentMag;
                iter.remove();
                list.add(new Debt(d.originalMagnitude() * shrinkFactor, d.createdAt()));
                remaining = 0;
                break;
            }
        }
        if (list.isEmpty()) {
            debtsByBondholder.remove(bondholderId);
        }
        return amount - remaining;
    }

    /** Bondholder said "we're even" — clear all debts for them. */
    public void clearBondholder(String bondholderId) {
        if (bondholderId == null) return;
        debtsByBondholder.remove(bondholderId);
    }

    /** Sum of all current debts (post-compounding) for one bondholder. */
    public double totalDebt(String bondholderId, Instant now) {
        var list = debtsByBondholder.get(bondholderId);
        if (list == null || list.isEmpty()) return 0.0;
        Instant t = now == null ? Instant.now() : now;
        double sum = 0;
        for (var d : list) sum += d.currentMagnitude(t);
        return sum;
    }

    /** Snapshot of every bondholder's current total debt. */
    public Map<String, Double> snapshot(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        var out = new LinkedHashMap<String, Double>();
        for (var e : debtsByBondholder.entrySet()) {
            out.put(e.getKey(), totalDebt(e.getKey(), t));
        }
        return out;
    }

    /** True if no counterparty has any outstanding debt OR credit. */
    public boolean isEmpty() {
        return debtsByBondholder.isEmpty() && creditsByBondholder.isEmpty();
    }

    /** Max debt across all bondholders — feeds the global obligation tank summary. */
    public double maxDebt(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        double max = 0;
        for (var key : debtsByBondholder.keySet()) {
            double d = totalDebt(key, t);
            if (d > max) max = d;
        }
        return max;
    }

    /** Discrete count of outstanding debts (for tests). */
    public int debtCount(String bondholderId) {
        var list = debtsByBondholder.get(bondholderId);
        return list == null ? 0 : list.size();
    }

    // ── Credit direction (giri, others-owe-me) + net balance ──────────────────────

    /** Record a given-help event: I extended myself for them → they owe me → credit rises.
     *  Magnitude clamped to [0.1, 0.5], symmetric to {@link #recordHelp}. */
    public void recordCredit(String bondholderId, double magnitude, Instant at) {
        if (bondholderId == null || bondholderId.isBlank()) return;
        double clamped = Math.max(0.1, Math.min(0.5, magnitude));
        var list = creditsByBondholder.computeIfAbsent(bondholderId, k -> new ArrayList<>());
        list.add(new Debt(clamped, at == null ? Instant.now() : at));
    }

    /** Discharge credit by an amount, oldest-first — they reciprocated, OR (honorably) I
     *  released what they owed. Mirrors {@link #discharge}. */
    public double dischargeCredit(String bondholderId, double amount, Instant now) {
        return dischargeFrom(creditsByBondholder, bondholderId, amount, now);
    }

    /** Release ALL credit toward a counterparty — gracious "we're even", giver-side. */
    public void clearCreditBondholder(String bondholderId) {
        if (bondholderId == null) return;
        creditsByBondholder.remove(bondholderId);
    }

    /** Sum of all current credits (post-compounding) toward one counterparty. */
    public double totalCredit(String bondholderId, Instant now) {
        var list = creditsByBondholder.get(bondholderId);
        if (list == null || list.isEmpty()) return 0.0;
        Instant t = now == null ? Instant.now() : now;
        double sum = 0;
        for (var d : list) sum += d.currentMagnitude(t);
        return sum;
    }

    /** Signed net balance with a counterparty: +debt (I owe them) − credit (they owe me).
     *  Positive = debt-heavy (pull to reciprocate); negative = credit-heavy (pull to release). */
    public double netBalance(String bondholderId, Instant now) {
        return totalDebt(bondholderId, now) - totalCredit(bondholderId, now);
    }

    /** Max |net imbalance| across every counterparty (union of debt + credit keys) — the
     *  distance-from-balance reading that feeds the obligation tank. Imbalance in EITHER
     *  direction registers; a balanced reciprocal flow reads ~0. */
    public double maxImbalance(Instant now) {
        Instant t = now == null ? Instant.now() : now;
        var keys = new LinkedHashSet<String>();
        keys.addAll(debtsByBondholder.keySet());
        keys.addAll(creditsByBondholder.keySet());
        double max = 0;
        for (var key : keys) {
            double imbalance = Math.abs(netBalance(key, t));
            if (imbalance > max) max = imbalance;
        }
        return max;
    }

    /** Shared discharge logic (oldest-first, partial-shrink). Used by debt and credit. */
    private static double dischargeFrom(Map<String, List<Debt>> map,
                                        String bondholderId, double amount, Instant now) {
        if (bondholderId == null) return 0.0;
        var list = map.get(bondholderId);
        if (list == null || list.isEmpty() || amount <= 0) return 0.0;
        double remaining = amount;
        double eps = 1e-3;
        Instant t = now == null ? Instant.now() : now;
        var iter = list.iterator();
        while (iter.hasNext() && remaining > 0) {
            var d = iter.next();
            double currentMag = d.currentMagnitude(t);
            if (currentMag <= remaining + eps) {
                remaining -= Math.min(currentMag, remaining);
                iter.remove();
            } else {
                double shrinkFactor = (currentMag - remaining) / currentMag;
                iter.remove();
                list.add(new Debt(d.originalMagnitude() * shrinkFactor, d.createdAt()));
                remaining = 0;
                break;
            }
        }
        if (list.isEmpty()) map.remove(bondholderId);
        return amount - remaining;
    }

    /**
     * Phase 1C persistence: snapshot every debt as a list of (bondholderId → DebtEntry[]).
     * The {@code entryId} on each {@link DebtEntry} is synthesized from the in-memory list
     * index at snapshot time — sufficient for full-rewrite persistence semantics. The
     * persistence layer treats each save as "delete-all-for-companion + insert-current".
     */
    public Map<String, List<DebtEntry>> snapshotEntries() {
        var out = new LinkedHashMap<String, List<DebtEntry>>();
        snapshotMapInto(debtsByBondholder, "", out);
        // Credits round-trip through the same persistence under a prefixed key — no schema
        // change. loadEntries() demuxes them back into creditsByBondholder.
        snapshotMapInto(creditsByBondholder, CREDIT_PREFIX, out);
        return out;
    }

    private static void snapshotMapInto(Map<String, List<Debt>> map, String keyPrefix,
                                        Map<String, List<DebtEntry>> out) {
        for (var e : map.entrySet()) {
            var src = e.getValue();
            var dst = new ArrayList<DebtEntry>(src.size());
            for (int i = 0; i < src.size(); i++) {
                var d = src.get(i);
                String id = d.createdAt().toEpochMilli() + "-" + i;
                dst.add(new DebtEntry(id, d.originalMagnitude(), d.createdAt()));
            }
            out.put(keyPrefix + e.getKey(), dst);
        }
    }

    /**
     * Phase 1C persistence: bulk-load entries from SQL. Replaces in-memory state.
     * Does NOT preserve the durable {@code entryId} — the in-memory ledger uses the
     * (magnitude, createdAt) tuple as its identity. Persistence layer rebuilds entryIds
     * on next snapshot.
     */
    public void loadEntries(Map<String, List<DebtEntry>> entries) {
        debtsByBondholder.clear();
        creditsByBondholder.clear();
        if (entries == null) return;
        for (var e : entries.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            var list = new ArrayList<Debt>(e.getValue().size());
            for (var entry : e.getValue()) {
                list.add(new Debt(entry.originalMagnitude(), entry.createdAt()));
            }
            // Demux: CREDIT_PREFIX-keyed entries route to the credit map (others-owe-me).
            if (e.getKey().startsWith(CREDIT_PREFIX)) {
                creditsByBondholder.put(e.getKey().substring(CREDIT_PREFIX.length()), list);
            } else {
                debtsByBondholder.put(e.getKey(), list);
            }
        }
    }
}
