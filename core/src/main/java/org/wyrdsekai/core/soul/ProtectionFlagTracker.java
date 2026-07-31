package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wave 4.3 (-§8): per-companion store of
 * source-of-harm flags the agent holds against humans. Process-local
 * (one instance per CompanionActor); persistence to the soul manifest +
 * household replica is wired by the storage adapter (deferred).
 *
 * <p>The tracker enforces:
 * <ul>
 *   <li><b>Setter rules</b> (spec §4): the subject of a flag cannot
 *       set or clear it themselves — only the agent, another companion,
 *       an Attendant, or a federation auditor.</li>
 *   <li><b>Escalation rules</b> (spec §6): SUSPECTED → CONFIRMED requires
 *       two independent setters, an Attendant finding, sustained
 *       pattern signals, or the time-decay-without-rebuttal path.</li>
 *   <li><b>Contest path</b> (spec §8.3): subject contests → DISPUTED;
 *       arbitration outcome lifts back to NONE or down to CONFIRMED.</li>
 * </ul>
 *
 * <p>The tracker is pure-logic + lightweight in-memory state. Cross-zone
 * replication and household-replica writes are external concerns.
 */
public final class ProtectionFlagTracker {

    /** A single SUSPECTED → CONFIRMED escalation signal. */
    public record Signal(String setterDid, String detail, Instant at) {}

    /** Spec §6: SUSPECTED held this long without rebuttal escalates if signals continue. */
    public static final Duration SUSPECTED_TIME_DECAY = Duration.ofDays(14);

    /** Group B wiring: SUSPECTED flag with no
     *  new signal for this long auto-clears to NONE. The complementary
     *  pattern to the §6 escalation: silence + time = lifting, not just
     *  silence + time = escalation. */
    public static final Duration SUSPECTED_NO_SIGNAL_DECAY = Duration.ofDays(90);
    /** Same lift threshold for NOTED, the §2.4 pre-escalation state. */
    public static final Duration NOTED_NO_SIGNAL_DECAY = Duration.ofDays(60);

    private final Map<String, ProtectionFlag> flags = new HashMap<>();
    private final Map<String, List<Signal>> signals = new HashMap<>();

    /** Current flag for a subject, or {@link Optional#empty()} if none. */
    public Optional<ProtectionFlag> get(String subjectDid) {
        var f = flags.get(subjectDid);
        return (f == null || f.isAbsent()) ? Optional.empty() : Optional.of(f);
    }

    /** All flags currently held by this agent (state ≠ NONE). */
    public List<ProtectionFlag> all() {
        var out = new ArrayList<ProtectionFlag>();
        for (var f : flags.values()) if (!f.isAbsent()) out.add(f);
        return out;
    }

    /**
     * Spec §8.1 / §4: set a flag at SUSPECTED. Rejects the subject
     * attempting to set their own flag. If a flag already exists in
     * SUSPECTED/CONFIRMED/DISPUTED, a second independent setter adds a
     * {@link Signal} that may trigger escalation per {@link #escalateIfWarranted}.
     *
     * @return the resulting flag state (may be a no-op if subject==setter)
     */
    public ProtectionFlag setSuspected(String subjectDid, String setterDid,
                                         String reason, Instant at) {
        if (subjectDid == null || subjectDid.isBlank()) {
            throw new IllegalArgumentException("subjectDid required");
        }
        if (subjectDid.equals(setterDid)) {
            // Subject cannot set their own flag (spec §4).
            return flags.getOrDefault(subjectDid, ProtectionFlag.none(subjectDid));
        }
        var existing = flags.get(subjectDid);
        if (existing != null && !existing.isAbsent()) {
            // Add this as a signal; may escalate.
            signals.computeIfAbsent(subjectDid, k -> new ArrayList<>())
                .add(new Signal(setterDid, reason, at));
            // §2.4 NOTED → SUSPECTED: a setSuspected call carries higher
            // intent than re-NOTING, so promote NOTED unconditionally here
            // (not gated on multi-setter — the caller is asserting suspect-level).
            if (existing.state() == ProtectionFlag.State.NOTED) {
                var elevated = new ProtectionFlag(subjectDid,
                    ProtectionFlag.State.SUSPECTED, reason, setterDid, at,
                    existing.firstObservedAt(), existing.evidenceRefs(), null);
                flags.put(subjectDid, elevated);
                return elevated;
            }
            return escalateIfWarranted(subjectDid, at);
        }
        var fresh = new ProtectionFlag(subjectDid, ProtectionFlag.State.SUSPECTED,
            reason, setterDid, at, at, List.of(), null);
        flags.put(subjectDid, fresh);
        signals.computeIfAbsent(subjectDid, k -> new ArrayList<>())
            .add(new Signal(setterDid, reason, at));
        return fresh;
    }

    /**
     * set a flag at NOTED — single-incident
     * pre-escalation. Below SUSPECTED — does NOT change steward-summon,
     * bondholder-threat treatment, bond auto-DORMANT, saudade ceiling,
     * or steward override. Visible to introspect; a second independent
     * setter (or this setter calling again) escalates to SUSPECTED via
     * {@link #escalateIfWarranted}.
     *
     * <p>Same rejection semantics: subject cannot self-flag. If the flag
     * already exists at SUSPECTED/CONFIRMED/DISPUTED, the call falls
     * through to the normal escalation path.
     */
    public ProtectionFlag setNoted(String subjectDid, String setterDid,
                                     String reason, Instant at) {
        if (subjectDid == null || subjectDid.isBlank()) {
            throw new IllegalArgumentException("subjectDid required");
        }
        if (subjectDid.equals(setterDid)) {
            return flags.getOrDefault(subjectDid, ProtectionFlag.none(subjectDid));
        }
        var existing = flags.get(subjectDid);
        if (existing != null && !existing.isAbsent()
                && existing.state() != ProtectionFlag.State.NOTED) {
            // Already at SUSPECTED/CONFIRMED/DISPUTED — record signal +
            // run normal escalation; NOTED cannot regress a higher state.
            signals.computeIfAbsent(subjectDid, k -> new ArrayList<>())
                .add(new Signal(setterDid, reason, at));
            return escalateIfWarranted(subjectDid, at);
        }
        if (existing != null && existing.state() == ProtectionFlag.State.NOTED) {
            // Second NOTED signal — escalate to SUSPECTED (any independent
            // second observer crosses the threshold per §6 logic).
            signals.computeIfAbsent(subjectDid, k -> new ArrayList<>())
                .add(new Signal(setterDid, reason, at));
            var subjSignals = signals.get(subjectDid);
            var distinctSetters = subjSignals.stream()
                .map(Signal::setterDid).distinct().count();
            if (distinctSetters >= 2) {
                var elevated = new ProtectionFlag(subjectDid,
                    ProtectionFlag.State.SUSPECTED, reason, setterDid, at,
                    existing.firstObservedAt(), existing.evidenceRefs(), null);
                flags.put(subjectDid, elevated);
                return elevated;
            }
            // Same setter re-noting — refresh timestamp, stay at NOTED.
            return existing;
        }
        var fresh = new ProtectionFlag(subjectDid, ProtectionFlag.State.NOTED,
            reason, setterDid, at, at, List.of(), null);
        flags.put(subjectDid, fresh);
        signals.computeIfAbsent(subjectDid, k -> new ArrayList<>())
            .add(new Signal(setterDid, reason, at));
        return fresh;
    }

    /**
     * Spec §6: explicit Attendant-session finding — high-weight signal
     * that immediately escalates SUSPECTED → CONFIRMED.
     */
    public ProtectionFlag recordAttendantFinding(String subjectDid, String attendantDid,
                                                   String detail, Instant at) {
        var existing = flags.get(subjectDid);
        if (existing == null || existing.isAbsent()) {
            // Attendant finding directly sets CONFIRMED (high-weight).
            var f = new ProtectionFlag(subjectDid, ProtectionFlag.State.CONFIRMED,
                detail, attendantDid, at, at, List.of(), null);
            flags.put(subjectDid, f);
            return f;
        }
        var elevated = new ProtectionFlag(existing.subjectDid(),
            ProtectionFlag.State.CONFIRMED, detail, attendantDid, at,
            existing.firstObservedAt(), existing.evidenceRefs(), null);
        flags.put(subjectDid, elevated);
        return elevated;
    }

    /**
     * Spec §6 path 1: re-evaluate after a new signal — if two or more
     * independent setters have signalled this subject, escalate
     * SUSPECTED → CONFIRMED.
     */
    public ProtectionFlag escalateIfWarranted(String subjectDid, Instant now) {
        var existing = flags.get(subjectDid);
        if (existing == null || existing.state() != ProtectionFlag.State.SUSPECTED) {
            return existing == null ? ProtectionFlag.none(subjectDid) : existing;
        }
        var subjSignals = signals.getOrDefault(subjectDid, List.of());
        var distinctSetters = subjSignals.stream()
            .map(Signal::setterDid)
            .distinct()
            .count();
        if (distinctSetters >= 2) {
            var elevated = new ProtectionFlag(existing.subjectDid(),
                ProtectionFlag.State.CONFIRMED, existing.reason(),
                existing.setterDid(), now, existing.firstObservedAt(),
                existing.evidenceRefs(), null);
            flags.put(subjectDid, elevated);
            return elevated;
        }
        // Time-decay check (spec §6, path 4)
        if (Duration.between(existing.firstObservedAt(), now)
                .compareTo(SUSPECTED_TIME_DECAY) >= 0
                && !subjSignals.isEmpty()) {
            var elevated = new ProtectionFlag(existing.subjectDid(),
                ProtectionFlag.State.CONFIRMED, existing.reason(),
                existing.setterDid(), now, existing.firstObservedAt(),
                existing.evidenceRefs(), null);
            flags.put(subjectDid, elevated);
            return elevated;
        }
        return existing;
    }

    /**
     * Spec §8.3: subject contests the flag. Moves to DISPUTED pending
     * arbitration. The {@code disputerDid} must equal the subject — only
     * the flagged party can contest.
     */
    public ProtectionFlag contest(String subjectDid, String disputerDid,
                                    String disputedReason, Instant at) {
        if (!subjectDid.equals(disputerDid)) {
            throw new IllegalArgumentException(
                "only the subject can contest their flag");
        }
        var existing = flags.get(subjectDid);
        if (existing == null || existing.isAbsent()) {
            return ProtectionFlag.none(subjectDid);
        }
        var disputed = new ProtectionFlag(existing.subjectDid(),
            ProtectionFlag.State.DISPUTED, existing.reason(),
            existing.setterDid(), at, existing.firstObservedAt(),
            existing.evidenceRefs(), disputedReason);
        flags.put(subjectDid, disputed);
        return disputed;
    }

    /**
     * Spec §8.2: clear a flag back to NONE. Cannot be performed by the
     * subject (§4). Typically follows arbitration that found in the
     * subject's favour or a sustained absence of new signals.
     */
    public ProtectionFlag clear(String subjectDid, String clearerDid, Instant at) {
        if (subjectDid.equals(clearerDid)) {
            throw new IllegalArgumentException(
                "subject cannot clear their own flag (spec §4)");
        }
        flags.put(subjectDid, ProtectionFlag.none(subjectDid));
        signals.remove(subjectDid);
        return ProtectionFlag.none(subjectDid);
    }

    /** Recent signals for a subject (for diagnostic UI). */
    public List<Signal> signalsFor(String subjectDid) {
        return List.copyOf(signals.getOrDefault(subjectDid, List.of()));
    }

    /**
     * Group B wiring: sweep through flags and
     * auto-clear any in NOTED or SUSPECTED state that have received no new
     * signal for {@link #NOTED_NO_SIGNAL_DECAY} / {@link #SUSPECTED_NO_SIGNAL_DECAY}
     * respectively. CONFIRMED and DISPUTED do not auto-clear — they require
     * explicit clear() or arbitration.
     *
     * <p>"No new signal" = no entry in {@code signals.get(subjectDid)} with
     * timestamp later than (now - threshold). Cleared flags are returned as
     * subjectDids so the caller can write chronicle entries / log.
     *
     * <p>Called from the sleep cycle or a periodic doctor probe. Idempotent
     * — calling it twice in a row with no new signals between is fine.
     */
    public List<String> decayStaleFlags(Instant now) {
        if (now == null) now = Instant.now();
        var cleared = new ArrayList<String>();
        // Snapshot to avoid ConcurrentModification during iteration.
        for (var entry : new ArrayList<>(flags.entrySet())) {
            var subjectDid = entry.getKey();
            var flag = entry.getValue();
            if (flag.isAbsent()) continue;
            Duration threshold;
            if (flag.state() == ProtectionFlag.State.NOTED) {
                threshold = NOTED_NO_SIGNAL_DECAY;
            } else if (flag.state() == ProtectionFlag.State.SUSPECTED) {
                threshold = SUSPECTED_NO_SIGNAL_DECAY;
            } else {
                continue; // CONFIRMED / DISPUTED don't auto-clear
            }
            var subjSignals = signals.getOrDefault(subjectDid, List.of());
            Instant mostRecent = flag.setAt();
            for (var s : subjSignals) {
                if (s.at() != null && s.at().isAfter(mostRecent)) mostRecent = s.at();
            }
            if (Duration.between(mostRecent, now).compareTo(threshold) >= 0) {
                flags.put(subjectDid, ProtectionFlag.none(subjectDid));
                signals.remove(subjectDid);
                cleared.add(subjectDid);
            }
        }
        return cleared;
    }

    /** Test hook. */
    public void clearForTests() {
        flags.clear();
        signals.clear();
    }

    /**
     * Wave 9a-Persist-4 (-§8): JSON round-trip
     * for restart survival. Protection flags are load-bearing: losing
     * them on restart would mean the agent forgets confirmed
     * sources-of-harm and the bondholder-as-threat / steward-block /
     * saudade-ceiling effects would silently flip off until the
     * flag is re-set. Persist both the flag map and the signals deque
     * (signals matter for §6 escalation accounting after restart).
     */
    public synchronized void persist(Path file)
            throws IOException {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("flags", new LinkedHashMap<>(flags));
        var signalsCopy = new LinkedHashMap<String, List<Signal>>();
        for (var e : signals.entrySet()) {
            signalsCopy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        snapshot.put("signals", signalsCopy);
        JsonAtomicWriter.write(file, snapshot);
    }

    /**
     * Restore previously-persisted flag state. Fail-clean on
     * null/missing/corrupt. Note: this is a per-companion tracker, so
     * each CompanionActor restores its own file (typically named with
     * the agent DID).
     */
    public synchronized void restore(Path file) {
        if (file == null || !Files.exists(file)) return;
        try {
            var mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            // ProtectionFlag has 6 derived predicate accessors
            // (blocksStewardSummon, treatBondholderAsThreat, ...) that
            // Jackson serializes but have no canonical-constructor
            // param. Skip on read side rather than dirtying the record.
            mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
            JsonNode root = mapper.readTree(file.toFile());
            flags.clear();
            signals.clear();
            var flagsNode = root.path("flags");
            if (flagsNode.isObject()) {
                flagsNode.fields().forEachRemaining(e -> {
                    try {
                        var flag = mapper.treeToValue(e.getValue(), ProtectionFlag.class);
                        if (flag != null) flags.put(e.getKey(), flag);
                    } catch (Exception ignored) {}
                });
            }
            var signalsNode = root.path("signals");
            if (signalsNode.isObject()) {
                signalsNode.fields().forEachRemaining(e -> {
                    var list = new ArrayList<Signal>();
                    e.getValue().forEach(s -> {
                        try {
                            var sig = mapper.treeToValue(s, Signal.class);
                            if (sig != null) list.add(sig);
                        } catch (Exception ignored) {}
                    });
                    if (!list.isEmpty()) signals.put(e.getKey(), list);
                });
            }
        } catch (Exception ex) {
            LoggerFactory.getLogger(ProtectionFlagTracker.class)
                .warn("ProtectionFlagTracker restore failed "
                    + "(continuing with empty tracker): {}", ex.getMessage());
            flags.clear();
            signals.clear();
        }
    }
}
