package org.wyrdsekai.core.recipe;

import java.time.Duration;
import java.time.Instant;

/**
 * When a recipe that has been failing may try once more.
 *
 * <p>Three consecutive deploy failures trip the welfare ceiling, which exists so a recipe
 * cannot grind a companion down by failing at her over and over. That part is right. What
 * was wrong was the resting state: paused meant paused FOREVER, until a person noticed and
 * cleared it by hand. On an unattended household node the failure mode is silence — the
 * self-improvement loop simply stops, and nothing says so. Found live 2026-08-18, where a
 * configuration error had held a recipe paused for days.
 *
 * <p>So: a circuit breaker rather than a latch. After a cooldown, exactly ONE attempt is
 * allowed through. A success closes the breaker; a failure re-opens it with the cooldown
 * doubled. At worst that is one run per day, then one per two days, and so on — far too
 * slow to grind anyone, and enough that a transient cause heals itself instead of waiting
 * for someone to go looking.
 *
 * <p>Derived entirely from what the queue already records, so there is no new state to
 * persist, nothing to get out of sync, and it survives a restart for free. A SUCCEEDED row
 * breaks the consecutive-failure streak, which closes the breaker as a matter of
 * arithmetic rather than bookkeeping.
 */
public final class RecipeCircuitBreaker {

    /** First wait after the ceiling trips. */
    public static final Duration BASE_COOLDOWN = Duration.ofHours(24);
    /** However bad it gets, check back at least this often. */
    public static final Duration MAX_COOLDOWN = Duration.ofDays(7);

    private RecipeCircuitBreaker() {}

    public enum State {
        /** Below the ceiling — dispatch normally. */
        CLOSED,
        /** Tripped and still cooling down — do not dispatch. */
        OPEN,
        /** Cooled down — let exactly one attempt through. */
        HALF_OPEN
    }

    /** How long to wait before the next single attempt, given how many have failed. */
    public static Duration cooldownFor(int consecutiveFailures, int limit) {
        int over = Math.max(0, consecutiveFailures - limit);
        var cooldown = BASE_COOLDOWN;
        for (int i = 0; i < over && cooldown.compareTo(MAX_COOLDOWN) < 0; i++) {
            cooldown = cooldown.multipliedBy(2);
        }
        return cooldown.compareTo(MAX_COOLDOWN) > 0 ? MAX_COOLDOWN : cooldown;
    }

    /**
     * @param consecutiveFailures failures since the last success (queue-derived).
     * @param limit               the deploy ceiling.
     * @param lastTerminalAt      when the last run finished; null when unknown.
     */
    public static State stateFor(int consecutiveFailures, int limit,
            Instant lastTerminalAt, Instant now) {
        if (consecutiveFailures < limit) return State.CLOSED;
        // Unknown elapsed time lets ONE attempt through rather than latching shut. The
        // cost of being wrong here is a single extra run; the cost of the other default
        // is a loop that never runs again and never says why.
        if (lastTerminalAt == null || now == null) return State.HALF_OPEN;
        var elapsed = Duration.between(lastTerminalAt, now);
        if (elapsed.isNegative()) return State.OPEN;             // clock went backwards
        return elapsed.compareTo(cooldownFor(consecutiveFailures, limit)) >= 0
            ? State.HALF_OPEN : State.OPEN;
    }
}
