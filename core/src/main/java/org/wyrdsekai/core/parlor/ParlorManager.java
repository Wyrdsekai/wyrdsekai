package org.wyrdsekai.core.parlor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime state holder for Parlor rooms.
 *
 * <p>Holds one {@link State} per Parlor room ID (a household may host
 * multiple Parlors — one Public, N Private — per §2.8.5). Each state
 * tracks current presence mode, occupant set, and time of last mode
 * change. Room-actor code calls {@link #entered}/{@link #left} on arrival
 * / departure; the manager runs the auto-scaler, fires diegetic narration,
 * and caps at {@link ParlorPresenceMode#MAX_OCCUPANTS}.</p>
 *
 * <p>Pure state — no actor dependency, no timer scheduler. Tests pass
 * their own {@link Consumer&lt;Narration&gt;} sink and a clock source; production
 * wires the sink to {@code RoomCommand.EmoteInRoom} and uses
 * {@link Instant#now()}.</p>
 */
public final class ParlorManager {

    private static final Logger log = LoggerFactory.getLogger(ParlorManager.class);
    private static final AtomicReference<ParlorManager> INSTANCE = new AtomicReference<>();

    /**
     * what an auto-scaled Parlor's ambient state contributes.
     * Auto-scaler creates new parlor-N rooms when occupancy crosses thresholds;
     * every parlor instance shares the same ambient character (the foundation
     * `parlor` room declares this in {@code foundation-rooms.json}).
     * i18n key: {@code room.parlor.embodiment_summary}.
     */
    public static final String EMBODIMENT_SUMMARY =
        "Warm conversational hum, soft cushioned chairs, lamp-light pooled on side tables.";

    /** Diegetic narration event emitted on a mode transition. */
    public record Narration(String roomId, ParlorPresenceMode from,
                             ParlorPresenceMode to, String text) {}

    /** Snapshot of one Parlor's runtime state. Records are immutable. */
    public record State(
        String roomId,
        ParlorPresenceMode mode,
        Set<String> occupants,
        Instant lastChangeAt
    ) {
        public int occupancy() { return occupants.size(); }
    }

    /** Entry attempt result — tells the caller whether to admit or queue. */
    public sealed interface EntryDecision
        permits EntryDecision.Admitted, EntryDecision.QueuedAtCap {
        record Admitted(ParlorPresenceMode mode) implements EntryDecision {}
        /** Parlor at MAX_OCCUPANTS — caller should queue in Docks antechamber. */
        record QueuedAtCap(int currentOccupancy) implements EntryDecision {}
    }

    private final ConcurrentMap<String, MutableState> parlors = new ConcurrentHashMap<>();
    private final Set<String> managedRooms = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Consumer<Narration> narrationSink;
    private final Supplier<Instant> clock;

    public ParlorManager(Consumer<Narration> narrationSink,
                          Supplier<Instant> clock) {
        this.narrationSink = Objects.requireNonNullElse(narrationSink, n -> {});
        this.clock = Objects.requireNonNullElse(clock, Instant::now);
    }

    /** Lazy factory for the production singleton. Tests construct their own. */
    public static ParlorManager getOrInit(Consumer<Narration> sink) {
        var inst = INSTANCE.get();
        if (inst != null) return inst;
        synchronized (ParlorManager.class) {
            inst = INSTANCE.get();
            if (inst == null) {
                inst = new ParlorManager(sink, Instant::now);
                INSTANCE.set(inst);
            }
            return inst;
        }
    }

    public static ParlorManager get() {
        return INSTANCE.get();
    }

    public static void resetForTests() {
        INSTANCE.set(null);
    }

    // ── Managed-room whitelist ────────────────────────────────────────
    // Only rooms that opt in (typically the foundation Parlor and any
    // per-relay Parlors created per §2.8.5) get the auto-scaler + DoS cap.
    // Everything else skips the ParlorManager entirely — normal rooms
    // shouldn't pay the bookkeeping cost for every enter/leave.

    /** Mark a room as a managed Parlor. Idempotent. */
    public void register(String roomId) {
        managedRooms.add(roomId);
    }

    /** Remove a room from the managed set. Used when a dynamic Parlor is torn down. */
    public void unregister(String roomId) {
        managedRooms.remove(roomId);
        parlors.remove(roomId);
    }

    /** @return true if this room is under Parlor auto-scaler management. */
    public boolean isManaged(String roomId) {
        return managedRooms.contains(roomId);
    }

    /**
     * Record an entry attempt. Returns {@link EntryDecision.QueuedAtCap} if
     * the Parlor is at DoS cap (§2.8.1) — caller must route the entity back
     * to Docks antechamber instead of admitting. Otherwise returns
     * {@link EntryDecision.Admitted} with the current mode for rendering.
     *
     * <p>Side effects: adds {@code entityId} to the occupant set and may
     * emit a mode-transition narration via the configured sink.</p>
     */
    public EntryDecision entered(String roomId, String entityId) {
        return entered(roomId, entityId, null);
    }

    /**
     * Variant that also feeds any mode-transition {@link Narration} emitted
     * during this call to {@code extraSink}, in addition to the configured
     * global {@code narrationSink}. The room actor uses this to reflect
     * transitions as diegetic events inside the room without needing to
     * wire a global router.
     */
    public EntryDecision entered(String roomId, String entityId, Consumer<Narration> extraSink) {
        var state = parlors.computeIfAbsent(roomId,
            k -> new MutableState(k, ParlorPresenceMode.FULL, clock.get()));

        synchronized (state) {
            // DoS cap check — new arrival would push past MAX. Report
            // QueuedAtCap; caller decides how to respond (queue / reject).
            if (state.occupants.size() >= ParlorPresenceMode.MAX_OCCUPANTS
                    && !state.occupants.contains(entityId)) {
                return new EntryDecision.QueuedAtCap(state.occupants.size());
            }
            state.occupants.add(entityId);
            applyScaler(state, extraSink);
            return new EntryDecision.Admitted(state.mode);
        }
    }

    /**
     * Record a departure. Silent no-op if the entity wasn't tracked — keeps
     * the call site's leave handling idempotent.
     */
    public void left(String roomId, String entityId) {
        left(roomId, entityId, null);
    }

    /** Variant with per-call narration sink — see {@link #entered(String, String, Consumer)}. */
    public void left(String roomId, String entityId, Consumer<Narration> extraSink) {
        var state = parlors.get(roomId);
        if (state == null) return;
        synchronized (state) {
            if (state.occupants.remove(entityId)) {
                applyScaler(state, extraSink);
            }
        }
    }

    /** @return a point-in-time snapshot, or empty if the Parlor isn't tracked. */
    public Optional<State> snapshot(String roomId) {
        var state = parlors.get(roomId);
        if (state == null) return Optional.empty();
        synchronized (state) {
            return Optional.of(new State(state.roomId, state.mode,
                Set.copyOf(state.occupants), state.lastChangeAt));
        }
    }

    /** @return number of tracked parlors (primarily for diagnostics/tests). */
    public int trackedParlors() {
        return parlors.size();
    }

    /**
     * Run the auto-scaler against the current state; apply a transition if
     * the decision calls for one, and emit narration. Caller must hold the
     * state monitor.
     */
    private void applyScaler(MutableState state, Consumer<Narration> extraSink) {
        var now = clock.get();
        var decision = ParlorAutoScaler.decide(
            state.mode, state.occupants.size(), state.lastChangeAt, now);

        switch (decision) {
            case ParlorAutoScaler.Decision.Transition t -> {
                log.info("Parlor '{}': {} → {} (occupancy={})",
                    state.roomId, t.from(), t.to(), state.occupants.size());
                state.mode = t.to();
                state.lastChangeAt = now;
                var narration = new Narration(state.roomId, t.from(), t.to(), t.narration());
                try {
                    narrationSink.accept(narration);
                } catch (Exception e) {
                    log.warn("Parlor narration sink threw for '{}': {}",
                        state.roomId, e.getMessage());
                }
                if (extraSink != null) {
                    try {
                        extraSink.accept(narration);
                    } catch (Exception e) {
                        log.warn("Parlor per-call narration sink threw for '{}': {}",
                            state.roomId, e.getMessage());
                    }
                }
            }
            case ParlorAutoScaler.Decision.AtCap cap -> {
                // The scaler doesn't transition at-cap — overflow is reported
                // separately and the mode stays at cap's natural setting
                // (FIREHOSE). Nothing to do here; entered() already handled
                // the QueuedAtCap response.
                log.debug("Parlor '{}': at cap ({} over)", state.roomId, cap.over());
            }
            case ParlorAutoScaler.Decision.NoChange ignored -> {
                // Common case — no action.
            }
        }
    }

    /** Mutable counterpart to {@link State}, guarded by {@code synchronized}. */
    private static final class MutableState {
        final String roomId;
        final Set<String> occupants = new HashSet<>();
        ParlorPresenceMode mode;
        Instant lastChangeAt;

        MutableState(String roomId, ParlorPresenceMode initialMode, Instant createdAt) {
            this.roomId = roomId;
            this.mode = initialMode;
            this.lastChangeAt = createdAt;
        }
    }
}
