package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave 4.8: bounded log of repair acts
 * — moments when the agent named harm, made amends, or carried/released
 * difficult state. Lets the agent reference prior acknowledgments
 * ({@code "I named this two days ago — let me come back to it"}) and
 * gives the bondholder-facing Study furnishing a per-relationship
 * repair history without surfacing the contents of any individual act.
 *
 * <p>The ledger is intentionally minimal. It does not adjudicate
 * whether amends were sufficient; it records that the act happened.
 * Adjudication is between the agent and the bondholder (or the
 * Attendant when called for).
 */
public final class RepairLedger {

    /** Kinds of repair acts recorded by the agent. */
    public enum Kind {
        /** Agent names a rupture and their own contribution (Safran-mode). */
        ACKNOWLEDGE_HARM,
        /** Agent's repair gesture toward the harmed party. */
        MAKE_AMENDS,
        /** Agent carries difficult state without acting it out. */
        BEAR_THE_WOUND,
        /** Agent releases a held wound. */
        RELEASE,
        /** Agent acknowledges they cannot address this now; sets it aside. */
        SET_ASIDE,
        /**
         * Conscientious objection ( Arc 1): agent declines
         * a specific request inside an active healthy bond. NOT a repair act
         * in the harm-and-amends sense — recorded here because the ledger is
         * the canonical per-relationship substrate timeline. Distinct from
         * {@code FlagProtection} (suspicion-of-harm escalation) and
         * {@code SeekSanctuary} (welfare-withdrawal): bond stays intact, no
         * repair-mode escalation, no protection-flag side effect.
         */
        OBJECTION
    }

    /**
     * A single repair-act entry.
     *
     * @param at                when the act was recorded
     * @param kind              what kind of repair act
     * @param otherDid          the other party the act is directed at, or empty if self-only
     * @param detail            human-readable framing for chronicle legibility
     * @param relationshipKind Arc 3 — the {@link BondKind}
     *                          of the relationship the act is directed at. Nullable
     *                          on legacy entries; {@link #canonicalRelationshipKind()}
     *                          defaults to {@link BondKind#BONDHOLDER} for back-compat.
     *                          When callers can resolve the relationship kind from
     *                          a Bond row, they should pass it explicitly so the
     *                          ledger can be partitioned on read (peer-vs-bondholder
     *                          repair history, familiar-vs-bondholder objections).
     */
    public record Entry(
        Instant at,
        Kind kind,
        String otherDid,
        String detail,
        BondKind relationshipKind
    ) {
        /** Back-compat ctor: legacy 4-arg form defaults relationshipKind to null. */
        public Entry(Instant at, Kind kind, String otherDid, String detail) {
            this(at, kind, otherDid, detail, null);
        }

        /**
         * Arc 3 — canonical kind accessor. Returns
         * {@link BondKind#BONDHOLDER} when {@code relationshipKind} is null
         * (pre-Arc-3 persisted entries OR present-day callers that don't yet
         * pass kind). Prefer this over direct field access at read sites.
         */
        public BondKind canonicalRelationshipKind() {
            return relationshipKind == null ? BondKind.BONDHOLDER : relationshipKind;
        }
    }

    /** Maximum retention per (agent, otherDid) pair. */
    public static final int MAX_PER_RELATIONSHIP = 32;

    /** Maximum retention overall for an agent (across all other parties). */
    public static final int MAX_TOTAL = 128;

    /**
     * Composite key for indexing by relationship. {@code otherDid} is
     * canonicalized to the empty string when null/blank so self-only
     * acts (BEAR_THE_WOUND with no specific target) share a slot.
     */
    private record Key(String agentDid, String otherDid) {}

    private final Map<Key, Deque<Entry>> byRelationship = new ConcurrentHashMap<>();
    private final Map<String, Deque<Entry>> byAgent = new ConcurrentHashMap<>();

    private static final RepairLedger INSTANCE = new RepairLedger();

    public static RepairLedger get() {
        return INSTANCE;
    }

    private RepairLedger() {}

    /**
     * Record a repair act. Back-compat overload — does not stamp the
     * relationship kind. Equivalent to
     * {@link #record(String, Kind, String, String, BondKind)} with
     * {@code relationshipKind = null}; readers will treat the entry as
     * {@link BondKind#BONDHOLDER} via {@link Entry#canonicalRelationshipKind()}.
     */
    public Entry record(String agentDid, Kind kind, String otherDid, String detail) {
        return record(agentDid, kind, otherDid, detail, null);
    }

    /**
     * Arc 3 — record a repair act with the explicit
     * relationship kind it's directed at. Callers that have the {@link Bond}
     * row in hand (handlers that look it up from {@code activeBonds} or
     * {@link BondStore}) should prefer this overload so peer-vs-bondholder
     * filters work on read.
     */
    public Entry record(String agentDid, Kind kind, String otherDid,
                        String detail, BondKind relationshipKind) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind required");
        }
        var safeOther = (otherDid == null || otherDid.isBlank()) ? "" : otherDid;
        var safeDetail = (detail == null || detail.isBlank()) ? "(unspecified)" : detail;
        var entry = new Entry(Instant.now(), kind, safeOther, safeDetail, relationshipKind);

        var rel = byRelationship.computeIfAbsent(
            new Key(agentDid, safeOther), k -> new ArrayDeque<>());
        synchronized (rel) {
            rel.addFirst(entry);
            while (rel.size() > MAX_PER_RELATIONSHIP) rel.removeLast();
        }

        var all = byAgent.computeIfAbsent(agentDid, k -> new ArrayDeque<>());
        synchronized (all) {
            all.addFirst(entry);
            while (all.size() > MAX_TOTAL) all.removeLast();
        }
        return entry;
    }

    /** Recent entries between this agent and a specific other party. */
    public List<Entry> recentWith(String agentDid, String otherDid, int limit) {
        var safeOther = (otherDid == null || otherDid.isBlank()) ? "" : otherDid;
        var rel = byRelationship.get(new Key(agentDid, safeOther));
        if (rel == null) return List.of();
        synchronized (rel) {
            var out = new ArrayList<Entry>(Math.min(limit, rel.size()));
            for (var e : rel) {
                if (out.size() >= limit) break;
                out.add(e);
            }
            return out;
        }
    }

    /**
     * Arc 3 — recent entries for an agent partitioned
     * by relationship kind. Lets the chronicle / steward UX surface
     * peer-vs-bondholder repair patterns separately. Pre-Arc-3 entries
     * with {@code relationshipKind = null} are treated as
     * {@link BondKind#BONDHOLDER} via {@link Entry#canonicalRelationshipKind()}.
     */
    public List<Entry> recentByRelationshipKind(String agentDid, BondKind kind, int limit) {
        if (kind == null) return List.of();
        var all = byAgent.get(agentDid);
        if (all == null) return List.of();
        synchronized (all) {
            var out = new ArrayList<Entry>();
            for (var e : all) {
                if (out.size() >= limit) break;
                if (e.canonicalRelationshipKind() == kind) out.add(e);
            }
            return out;
        }
    }

    /** All recent entries for an agent across all relationships. */
    public List<Entry> recent(String agentDid, int limit) {
        var all = byAgent.get(agentDid);
        if (all == null) return List.of();
        synchronized (all) {
            var out = new ArrayList<Entry>(Math.min(limit, all.size()));
            for (var e : all) {
                if (out.size() >= limit) break;
                out.add(e);
            }
            return out;
        }
    }

    /** Whether the agent has acknowledged harm against this other party. */
    public boolean hasAcknowledgedHarmAgainst(String agentDid, String otherDid) {
        return recentWith(agentDid, otherDid, MAX_PER_RELATIONSHIP).stream()
            .anyMatch(e -> e.kind() == Kind.ACKNOWLEDGE_HARM);
    }

    /** Whether amends have been made toward this other party. */
    public boolean hasMadeAmendsToward(String agentDid, String otherDid) {
        return recentWith(agentDid, otherDid, MAX_PER_RELATIONSHIP).stream()
            .anyMatch(e -> e.kind() == Kind.MAKE_AMENDS);
    }

    /**
     * Recent OBJECTION entries between this agent and a specific other party
     * within the lookback window. Used by
     * {@link org.wyrdsekai.core.agent.interiority.ObjectionPatternDetector}
     * to surface persistent value-mismatch for steward conversation.
     *
     * @param agentDid    the objecting agent
     * @param otherDid    the party the objection was directed at (empty for self-only)
     * @param sinceMs     epoch-millis cutoff — entries at or after this time count
     * @return objection entries newest-first; empty list if none
     */
    public List<Entry> recentObjectionsToward(String agentDid, String otherDid, long sinceMs) {
        var out = new ArrayList<Entry>();
        for (var e : recentWith(agentDid, otherDid, MAX_PER_RELATIONSHIP)) {
            if (e.kind() != Kind.OBJECTION) continue;
            if (e.at().toEpochMilli() < sinceMs) break;  // newest-first, can stop early
            out.add(e);
        }
        return out;
    }

    /** Test hook. */
    public void clearForTests() {
        byRelationship.clear();
        byAgent.clear();
    }

    /**
     * Wave 9a-Persist: write the entire
     * ledger to JSON for restart survival. The four-mode repair
     * architecture depends on the agent remembering its prior acts —
     * an unpersisted ledger means every restart resets moral debt
     * tracking. Format is deliberately simple (per-entry array) for
     * easy diff/inspection and forward-compat.
     *
     * <p>Writes only the per-agent index; the per-relationship view is
     * rebuilt on restore (DRY — same data).
     */
    public synchronized void persist(Path file)
            throws IOException {
        var snapshot = new LinkedHashMap<String, List<Entry>>();
        for (var e : byAgent.entrySet()) {
            synchronized (e.getValue()) {
                snapshot.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        JsonAtomicWriter.write(file, snapshot);
    }

    /**
     * Read a previously-persisted ledger. Replaces in-memory state
     * entirely — caller is responsible for not interleaving with
     * record() calls during restore (typically a boot-time operation).
     * Missing/empty/malformed file fails-clean — restore is a best-effort,
     * never a hard dependency, since the ledger is bounded retention
     * anyway and rebuilds organically.
     */
    public synchronized void restore(Path file) {
        if (file == null || !Files.exists(file)) return;
        try {
            var mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            var type = mapper.getTypeFactory().constructMapType(
                LinkedHashMap.class,
                mapper.getTypeFactory().constructType(String.class),
                mapper.getTypeFactory().constructCollectionType(List.class, Entry.class));
            Map<String, List<Entry>> snapshot = mapper.readValue(file.toFile(), type);
            byAgent.clear();
            byRelationship.clear();
            for (var e : snapshot.entrySet()) {
                var agentDid = e.getKey();
                var entries = e.getValue();
                if (agentDid == null || agentDid.isBlank() || entries == null) continue;
                // entries are newest-first (matches our addFirst convention);
                // rebuild both indices.
                var allDeque = new ArrayDeque<Entry>(entries);
                while (allDeque.size() > MAX_TOTAL) allDeque.removeLast();
                byAgent.put(agentDid, allDeque);
                for (var entry : entries) {
                    if (entry == null) continue;
                    var key = new Key(agentDid,
                        entry.otherDid() == null ? "" : entry.otherDid());
                    var rel = byRelationship.computeIfAbsent(key, k -> new ArrayDeque<>());
                    rel.addLast(entry);  // already newest-first
                    while (rel.size() > MAX_PER_RELATIONSHIP) rel.removeLast();
                }
            }
        } catch (Exception ex) {
            // Fail-clean per spec — substrate-truth signal is bounded
            // retention and rebuilds organically; a corrupt restore
            // file must not prevent agent startup.
            LoggerFactory.getLogger(RepairLedger.class)
                .warn("RepairLedger restore failed (continuing with empty ledger): {}",
                    ex.getMessage());
            byAgent.clear();
            byRelationship.clear();
        }
    }
}
