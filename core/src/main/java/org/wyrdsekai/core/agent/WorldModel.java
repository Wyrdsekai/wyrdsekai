package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Causal world model — learns state transitions from observed actions.
 * Level 1: Transition table. No ML, no external deps. Pure observation.
 *
 * Records (state_key, action) → outcome for every action the companion takes.
 * Before executing an action, checks if the predicted outcome advances the goal.
 * Prevents: action loops, repeated no-effect actions, known-failure retries.
 *
 * State is abstracted to a compact key (room + entity count + object names hash)
 * to allow generalization across similar situations.
 */
public final class WorldModel {

    private static final Logger log = LoggerFactory.getLogger(WorldModel.class);
    private static final int MAX_HISTORY = 5000;

    /**
     * Communication and memory primitives that are legitimately called many
     * times per session — blocking them as "action loops" breaks reply
     * delivery and recall. See MEMORY_DIAGNOSIS_2026-04-23.md: probe silences
     * and library_search fallthroughs were caused by recall/tell_agent being
     * blocked from an earlier plant in the same probe run.
     */
    private static final Set<String> LOOP_EXEMPT_ACTIONS = Set.of(
        "tell_agent",   // reply delivery — must be repeatable across turns
        "recall",       // memory query — probing same DID is normal
        "emote",        // expressive communication — purely additive
        "goal_done",    // loop terminator — must be callable when task done
        "respond_agent" // alternate reply form
    );

    /**
     * Observed transition: what happened when we did this action in this state.
     */
    public record Transition(
        String stateKey,       // compact state representation
        String actionType,     // go_to_room, searching_glass, tell_agent, etc.
        String actionTarget,   // target param of the action
        String outcomeStateKey,// state after action (null if same)
        boolean success,
        String outcomeText,    // brief description of what happened
        Instant timestamp
    ) {}

    /**
     * Prediction: what we expect will happen.
     */
    public record Prediction(
        boolean stateWillChange,  // false = action has no effect
        boolean likelySuccess,    // based on historical success rate
        double confidence,        // 0-1 based on observation count
        String expectedOutcome,   // what we expect
        int observationCount      // how many times we've seen this
    ) {}

    /** Transition history: (stateKey + actionType + actionTarget) → list of outcomes. */
    private final ConcurrentHashMap<String, List<Transition>> transitions = new ConcurrentHashMap<>();

    /** Recent action tracking for loop detection. */
    private final LinkedList<String> recentActions = new LinkedList<>();
    private static final int RECENT_WINDOW = 10;

    // ── Recording ──

    /**
     * Record an observed state transition after an action executes.
     */
    public void recordTransition(RoomSnapshot preState, String actionType, String actionTarget,
                                  RoomSnapshot postState, boolean success, String outcomeText) {
        var preKey = stateKey(preState);
        var postKey = postState != null ? stateKey(postState) : preKey;
        var changed = !preKey.equals(postKey);

        var transition = new Transition(
            preKey, actionType, actionTarget,
            changed ? postKey : null,
            success, outcomeText, Instant.now());

        var key = transitionKey(preKey, actionType, actionTarget);
        transitions.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(transition);

        // Track recent actions for loop detection
        synchronized (recentActions) {
            recentActions.addLast(actionType + ":" + actionTarget);
            while (recentActions.size() > RECENT_WINDOW) {
                recentActions.removeFirst();
            }
        }

        // Evict old transitions if history is too large
        if (transitions.values().stream().mapToInt(List::size).sum() > MAX_HISTORY) {
            evictOldest();
        }

        log.debug("WorldModel: recorded {} {} → {} (success={})",
            actionType, actionTarget, changed ? "state_changed" : "no_change", success);
    }

    // ── Prediction ──

    /**
     * Predict what will happen if we execute this action in this state.
     * Returns null if we have no data for this situation.
     */
    public Prediction predict(RoomSnapshot currentState, String actionType, String actionTarget) {
        var preKey = stateKey(currentState);
        var key = transitionKey(preKey, actionType, actionTarget);
        var history = transitions.get(key);

        if (history == null || history.isEmpty()) {
            // No data — can't predict
            return null;
        }

        int total = history.size();
        int successes = (int) history.stream().filter(Transition::success).count();
        int stateChanges = (int) history.stream()
            .filter(t -> t.outcomeStateKey() != null).count();

        var lastOutcome = history.getLast();
        double confidence = Math.min(1.0, total / 5.0); // 5 observations = full confidence

        return new Prediction(
            stateChanges > 0,                    // will state change?
            (double) successes / total > 0.5,    // likely success?
            confidence,
            lastOutcome.outcomeText(),
            total
        );
    }

    /**
     * Check if executing this action would be a loop (same action repeated with no progress).
     */
    public boolean isActionLoop(String actionType, String actionTarget) {
        var actionKey = actionType + ":" + actionTarget;
        synchronized (recentActions) {
            int count = 0;
            for (var recent : recentActions) {
                if (recent.equals(actionKey)) count++;
            }
            return count >= 2; // same action attempted 2+ times recently
        }
    }

    /**
     * Suggest an alternative action if the proposed one is predicted to fail or loop.
     * Returns a hint string for the model, or null if no suggestion.
     */
    public String suggestAlternative(RoomSnapshot currentState, String actionType, String actionTarget) {
        var prediction = predict(currentState, actionType, actionTarget);
        boolean isCommunicationPrimitive = LOOP_EXEMPT_ACTIONS.contains(actionType);

        // State-change gate: skip for communication/memory primitives.
        // recall/emote/tell_agent are informational — not changing world
        // state is their correct behavior. Without this skip, after a few
        // recalls the "no effect" signal blocks further recalls and
        // memory retrieval dies (diagnosed 2026-04-23).
        if (prediction != null && !prediction.stateWillChange()
                && prediction.confidence() > 0.5
                && !isCommunicationPrimitive) {
            return "This action had no effect last time (done " + prediction.observationCount()
                + " times). Try a different approach.";
        }

        // Loop gate: skip for communication/memory primitives. These are
        // expected to be called many times per session; repetition is not
        // a loop.
        if (!isCommunicationPrimitive && isActionLoop(actionType, actionTarget)) {
            return "You've already tried " + actionType + " " + actionTarget
                + " recently. Move on to the next step of your plan.";
        }

        if (prediction != null && !prediction.likelySuccess() && prediction.confidence() > 0.5) {
            return "This action failed before: " + prediction.expectedOutcome()
                + ". Consider an alternative.";
        }

        return null;
    }

    // ── State Key ──

    /**
     * Compact state representation for transition lookup.
     * Abstracts room state to: roomId + entity count + sorted object names hash.
     * This allows generalization: "being in the library with 2 entities" is the same
     * state regardless of which specific entities are present.
     */
    static String stateKey(RoomSnapshot snapshot) {
        if (snapshot == null) return "unknown";
        var objects = snapshot.objects().stream()
            .map(o -> o.name()).sorted().toList();
        return snapshot.roomId()
            + "|e=" + snapshot.entities().size()
            + "|o=" + objects.hashCode();
    }

    private static String transitionKey(String stateKey, String actionType, String actionTarget) {
        return stateKey + "|" + actionType + "|" + (actionTarget != null ? actionTarget : "");
    }

    // ── Maintenance ──

    private void evictOldest() {
        // Remove oldest transitions to stay under MAX_HISTORY
        var oldest = transitions.values().stream()
            .flatMap(List::stream)
            .min(Comparator.comparing(Transition::timestamp));
        if (oldest.isPresent()) {
            var key = transitionKey(oldest.get().stateKey(),
                oldest.get().actionType(), oldest.get().actionTarget());
            var list = transitions.get(key);
            if (list != null && !list.isEmpty()) {
                list.removeFirst();
                if (list.isEmpty()) transitions.remove(key);
            }
        }
    }

    /**
     * Immutable snapshot of all observed transitions, keyed by
     * {@code stateKey|actionType|actionTarget}. Used by
     * {@link WorldModelPromptRenderer} to build the ACTION CONSEQUENCES
     * section of the Drive-9B prompt prefix. Returned map is a deep copy —
     * mutating it does not affect the live model.
     */
    public Map<String, List<Transition>> transitionsSnapshot() {
        var out = new LinkedHashMap<String, List<Transition>>(transitions.size());
        for (var e : transitions.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return out;
    }

    /** Stats for debugging. */
    public Map<String, Object> stats() {
        int totalTransitions = transitions.values().stream().mapToInt(List::size).sum();
        return Map.of(
            "uniqueStateActions", transitions.size(),
            "totalTransitions", totalTransitions,
            "recentActions", new ArrayList<>(recentActions)
        );
    }

    /**
     * Clear all accumulated state — both long-term transitions and the
     * recent-action window. Used by {@code CompanionActor.onResetState} so
     * per-test resets in the E2E harness don't leak action history into the
     * next test. Without this, the recent-action window can say "you already
     * tried {@code tell_agent} recently" on the very first invocation of a
     * fresh test, causing cross-test failures like {@code careResponseToDistress}
     * failing because grief before it had driven tell_agent into the recency
     * bucket.
     *
     * <p>Not for production reset paths — production companions should accumulate
     * transition data across their lifetime. Tests run with
     * {@link CompanionActor.ResetState} which is the only intended caller.</p>
     */
    public void reset() {
        transitions.clear();
        recentActions.clear();
    }
}
